package jobs

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const testID = "8aff7ee7-b9b1-44b0-89a3-a4e4a1a080aa"

func request() Request {
	return Request{testID, 1, "orders", strings.Repeat("a", 40), time.Now().UTC().Add(time.Hour).Format(time.RFC3339)}
}
func openTest(t *testing.T, dir string, run Execute, send Send) *Manager {
	t.Helper()
	m, err := Open(context.Background(), dir, "wss://control.example", 1, time.Minute, run, send, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	return m
}
func receive[T any](t *testing.T, ch <-chan T) T {
	t.Helper()
	select {
	case v := <-ch:
		return v
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for job event")
		var zero T
		return zero
	}
}

func TestLostReceiptRestartAndRedeliveryRunTestOnlyOnce(t *testing.T) {
	dir, req := t.TempDir(), request()
	var calls atomic.Int32
	started, release := make(chan struct{}), make(chan struct{})
	run := func(ctx context.Context, r Request, log func(string)) Result {
		calls.Add(1)
		close(started)
		select {
		case <-release:
		case <-ctx.Done():
		}
		log("unit tests: 1 passed")
		return Result{r.ID, "succeeded", 0, "unit tests: 1 passed"}
	}
	dropped := make(chan bool, 10)
	m := openTest(t, dir, run, func(ctx context.Context, kind string, body any) error {
		if kind == "job.result" {
			dropped <- true
			return errors.New("lost before confirmation")
		}
		return nil
	})
	m.Start()
	if err := m.Accept(req); err != nil {
		t.Fatal(err)
	}
	receive(t, started)
	if err := m.Accept(req); err != nil {
		t.Fatal(err)
	}
	changed := req
	changed.Project++
	if err := m.Accept(changed); err == nil {
		t.Fatal("accepted same ID for different project")
	}
	close(release)
	receive(t, dropped)
	m.Close()
	results := make(chan Result, 10)
	next := openTest(t, dir, run, func(ctx context.Context, kind string, body any) error {
		if kind == "job.result" {
			results <- body.(Result)
		}
		return nil
	})
	defer next.Close()
	next.poll()
	result := receive(t, results)
	if result.Status != "succeeded" || result.ExitCode != 0 || result.Output != "unit tests: 1 passed" {
		t.Fatalf("bad replay: %+v", result)
	}
	if err := next.Acknowledge(req.ID); err != nil {
		t.Fatal(err)
	}
	next.poll()
	select {
	case <-results:
		t.Fatal("replayed acknowledged result without request")
	default:
	}
	if err := next.Accept(req); err != nil {
		t.Fatal(err)
	}
	if receive(t, results) != result || calls.Load() != 1 {
		t.Fatalf("requests executed %d times", calls.Load())
	}
}

func TestCrashReservationIsUncertainAfterRestart(t *testing.T) {
	dir := t.TempDir()
	req := request()
	m := openTest(t, dir, nil, nil)
	if err := saveRecord(m.dir, record{Request: req}); err != nil {
		t.Fatal(err)
	}
	m.Close()
	results := make(chan Result, 2)
	m = openTest(t, dir, nil, func(ctx context.Context, kind string, body any) error {
		if kind == "job.result" {
			results <- body.(Result)
		}
		return nil
	})
	defer m.Close()
	m.poll()
	if got := receive(t, results); got.Status != "uncertain" {
		t.Fatalf("crashed job = %+v", got)
	}
	if err := m.Accept(req); err != nil {
		t.Fatal(err)
	}
	if got := receive(t, results); got.Status != "uncertain" {
		t.Fatalf("redelivered crashed job = %+v", got)
	}
}

func TestCancellationBeforeDispatchAndExpiredRequestsNeverStart(t *testing.T) {
	dir := t.TempDir()
	req := request()
	results := make(chan Result, 5)
	m := openTest(t, dir, nil, func(ctx context.Context, kind string, body any) error {
		if kind == "job.result" {
			results <- body.(Result)
		}
		return nil
	})
	defer m.Close()
	if err := m.Cancel(req.ID); err != nil {
		t.Fatal(err)
	}
	if err := m.Accept(req); err != nil {
		t.Fatal(err)
	}
	if got := receive(t, results); got.Status != "cancelled" {
		t.Fatal(got)
	}
	if err := m.Acknowledge(req.ID); err != nil {
		t.Fatal(err)
	}
	req.ID = "11111111-1111-1111-1111-111111111111"
	req.Deadline = "2001-01-01T00:00:00Z"
	if err := m.Accept(req); err != nil {
		t.Fatal(err)
	}
	m.poll()
	if got := receive(t, results); got.Status != "cancelled" {
		t.Fatal(got)
	}
	req.ID = "../escaped"
	if err := m.Accept(req); err == nil {
		t.Fatal("accepted unsafe ID")
	}
	if err := m.Cancel(filepath.Join(dir, "unsafe")); err == nil {
		t.Fatal("accepted unsafe cancellation")
	}
}

func TestActiveJobCanBeCancelled(t *testing.T) {
	started := make(chan struct{})
	results := make(chan Result, 4)
	m := openTest(t, t.TempDir(), func(ctx context.Context, r Request, log func(string)) Result {
		close(started)
		<-ctx.Done()
		return Result{r.ID, "cancelled", -1, "stopped"}
	}, func(ctx context.Context, kind string, body any) error {
		if kind == "job.result" {
			results <- body.(Result)
		}
		return nil
	})
	m.Start()
	defer m.Close()
	req := request()
	if err := m.Accept(req); err != nil {
		t.Fatal(err)
	}
	receive(t, started)
	if err := m.Cancel(req.ID); err != nil {
		t.Fatal(err)
	}
	if got := receive(t, results); got.Status != "cancelled" {
		t.Fatal(got)
	}
}
