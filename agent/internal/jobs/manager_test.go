package jobs

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
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
func TestForeignLedgerFileDisablesExecutorInsteadOfExiting(t *testing.T) {
	// 异物 .json（非 UUID 文件名）：Open 降级返回 ErrLedgerCorrupt 而非致命错误，
	// 调用方据此停用执行器并保持 Agent 主体运行（不触发 systemd 重启风暴）。
	dir := t.TempDir()
	ledger := recordDirectory(dir, "wss://control.example", 1)
	if err := os.MkdirAll(ledger, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(ledger, "notes.json"), []byte(`{"x":1}`), 0600); err != nil {
		t.Fatal(err)
	}
	m, err := Open(context.Background(), dir, "wss://control.example", 1, time.Minute, nil, nil,
		slog.New(slog.NewTextHandler(io.Discard, nil)))
	if m != nil || !errors.Is(err, ErrLedgerCorrupt) {
		t.Fatalf("expected degraded open (nil manager, ErrLedgerCorrupt), got manager=%v err=%v", m, err)
	}
}
func TestMixedLedgerCorruptionDegradesWithoutPartialReplay(t *testing.T) {
	// 好记录 + 损坏内容混合：整体降级（nil manager），不从可能损坏的账本做部分重放。
	dir := t.TempDir()
	ledger := recordDirectory(dir, "wss://control.example", 1)
	if err := os.MkdirAll(ledger, 0700); err != nil {
		t.Fatal(err)
	}
	if err := saveRecord(ledger, record{Request: request(), Result: &Result{testID, "succeeded", 0, "ok"}}); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(ledger, "0bb1b0d4-0b11-4d11-9c11-1d11e11f11aa.json"), []byte("garbage"), 0600); err != nil {
		t.Fatal(err)
	}
	m, err := Open(context.Background(), dir, "wss://control.example", 1, time.Minute, nil, nil,
		slog.New(slog.NewTextHandler(io.Discard, nil)))
	if m != nil || !errors.Is(err, ErrLedgerCorrupt) {
		t.Fatalf("expected degraded open (nil manager, ErrLedgerCorrupt), got manager=%v err=%v", m, err)
	}
}
func TestUnsavedResultBlocksNewJobsAndRecovers(t *testing.T) {
	// 结果写盘失败（同名目录阻塞 rename，双平台确定性注入）：unsaved 期间拒绝新作业、
	// 不发 job.ready；故障消除后 poll 把终态转正并回放结果。
	dir := t.TempDir()
	results := make(chan Result, 10)
	ready := make(chan string, 10)
	var m *Manager
	run := func(ctx context.Context, r Request, log func(string)) Result {
		// 预约记录已由 Accept 落盘；执行期间把 <id>.json 换成同名目录，使结果 saveRecord 的 rename 失败。
		os.Remove(filepath.Join(m.dir, r.ID+".json"))
		if err := os.Mkdir(filepath.Join(m.dir, r.ID+".json"), 0700); err != nil {
			t.Error(err)
		}
		return Result{r.ID, "succeeded", 0, "ok"}
	}
	m = openTest(t, dir, run, func(ctx context.Context, kind string, body any) error {
		if kind == "job.ready" {
			ready <- kind
		}
		if kind == "job.result" {
			results <- body.(Result)
		}
		return nil
	})
	defer m.Close()
	req := request()
	if err := m.Accept(req); err != nil {
		t.Fatal(err)
	}
	// 有界等待执行 goroutine 把 unsaved 置位。
	for deadline := time.Now().Add(3 * time.Second); ; {
		m.mu.Lock()
		unsaved := m.unsaved != nil
		m.mu.Unlock()
		if unsaved {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("timed out waiting for unsaved result")
		}
		time.Sleep(5 * time.Millisecond)
	}
	// unsaved 期间：另一作业被拒；poll 因写盘失败早退，不发送 ready。
	second := Request{"0bb1b0d4-0b11-4d11-9c11-1d11e11f11aa", 1, "orders", strings.Repeat("b", 40),
		time.Now().UTC().Add(time.Hour).Format(time.RFC3339)}
	if err := m.Accept(second); err == nil || !strings.Contains(err.Error(), "awaiting durable result") {
		t.Fatalf("expected new job rejection while unsaved, got %v", err)
	}
	m.poll()
	select {
	case <-ready:
		t.Fatal("job.ready must not be emitted while the result awaits disk retry")
	default:
	}
	// 故障消除后转正：ready 恢复协商，结果回放。
	if err := os.RemoveAll(filepath.Join(m.dir, req.ID+".json")); err != nil {
		t.Fatal(err)
	}
	m.poll()
	receive(t, ready)
	if got := receive(t, results); got.ID != req.ID || got.Status != "succeeded" || got.Output != "ok" {
		t.Fatalf("replayed result: %+v", got)
	}
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
