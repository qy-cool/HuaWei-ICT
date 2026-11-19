package syncs

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const testSyncID = "8aff7ee7-b9b1-44b0-89a3-a4e4a1a080aa"
const testSnapshot = "1bb1b0d4-0b11-4d11-9c11-1d11e11f11aa"

func envelope(commit string, bundle string) Request {
	return Request{testSyncID, 1, 8, testSnapshot, 1, commit, strings.Repeat("c", 40),
		bundle, 4096, time.Now().UTC().Add(time.Hour).Format(time.RFC3339)}
}

func openTest(t *testing.T, dir string, run Execute, send Send) *Manager {
	t.Helper()
	m, err := Open(context.Background(), dir, "wss://control.example", 2, run, send,
		slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func discardingSend(ctx context.Context, kind string, body any) error { return nil }

func TestAcceptRunsExecutorAndReplaysSameResult(t *testing.T) {
	// 接单 → 执行 → 结果落账并回放；相同 syncId 同信封摘要的重复送达返回原结果，
	// 换摘要（换 commit）的重复送达拒绝（协议：同 ID 换 SHA/目录/代次记录冲突）。
	dir := t.TempDir()
	results := make(chan Result, 4)
	var runCalls int
	run := func(ctx context.Context, req Request, progress func(string, int64)) Result {
		runCalls++
		progress("transferring", 128)
		progress("verifying", req.Bytes)
		return Result{req.ID, "ready", req.Commit, req.Tree, req.BundleSHA, "installed"}
	}
	m := openTest(t, dir, run, func(ctx context.Context, kind string, body any) error {
		if result, ok := body.(Result); ok {
			results <- result
		}
		return nil
	})
	defer m.Close()
	m.Start()
	if err := m.Accept(envelope(strings.Repeat("a", 40), strings.Repeat("d", 64))); err != nil {
		t.Fatal(err)
	}
	select {
	case result := <-results:
		if result.Status != "ready" || result.Commit != strings.Repeat("a", 40) {
			t.Fatalf("unexpected result %+v", result)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("result not delivered")
	}
	// 同信封重放：返回原结果且不再执行。
	if err := m.Accept(envelope(strings.Repeat("a", 40), strings.Repeat("d", 64))); err != nil {
		t.Fatal(err)
	}
	select {
	case result := <-results:
		if result.Status != "ready" {
			t.Fatalf("replay returned %+v", result)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("replay not delivered")
	}
	if runCalls != 1 {
		t.Fatalf("expected single execution, got %d", runCalls)
	}
	// 同 ID 换摘要：拒绝执行。
	if err := m.Accept(envelope(strings.Repeat("b", 40), strings.Repeat("d", 64))); err == nil {
		t.Fatal("expected conflict for same ID with different envelope")
	}
}

func TestInvalidRequestsAreRejectedBeforePersistence(t *testing.T) {
	dir := t.TempDir()
	m := openTest(t, dir, nil, discardingSend)
	defer m.Close()
	bad := []Request{
		{ID: "not-a-uuid", Snapshot: testSnapshot, Project: 1, Repository: 8, Generation: 1,
			Commit: strings.Repeat("a", 40), Tree: strings.Repeat("c", 40), BundleSHA: strings.Repeat("d", 64),
			Bytes: 10, Deadline: time.Now().Add(time.Hour).Format(time.RFC3339)},
		{ID: testSyncID, Snapshot: testSnapshot, Project: 1, Repository: 8, Generation: 0,
			Commit: strings.Repeat("a", 40), Tree: strings.Repeat("c", 40), BundleSHA: strings.Repeat("d", 64),
			Bytes: 10, Deadline: time.Now().Add(time.Hour).Format(time.RFC3339)},
		{ID: testSyncID, Snapshot: testSnapshot, Project: 1, Repository: 8, Generation: 1,
			Commit: "short", Tree: strings.Repeat("c", 40), BundleSHA: strings.Repeat("d", 64),
			Bytes: 10, Deadline: time.Now().Add(time.Hour).Format(time.RFC3339)},
		{ID: testSyncID, Snapshot: testSnapshot, Project: 1, Repository: 8, Generation: 1,
			Commit: strings.Repeat("a", 40), Tree: strings.Repeat("c", 40), BundleSHA: strings.Repeat("d", 64),
			Bytes: 0, Deadline: time.Now().Add(time.Hour).Format(time.RFC3339)},
		{ID: testSyncID, Snapshot: testSnapshot, Project: 1, Repository: 8, Generation: 1,
			Commit: strings.Repeat("a", 40), Tree: strings.Repeat("c", 40), BundleSHA: strings.Repeat("d", 64),
			Bytes: 10, Deadline: "yesterday"},
	}
	for _, request := range bad {
		if err := m.Accept(request); err == nil {
			t.Fatalf("expected rejection for %+v", request)
		}
	}
	if _, err := os.Stat(filepath.Join(m.dir, testSyncID+".json")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("invalid request must not persist a ledger record")
	}
}

func TestExpiredDeadlineRecordsCancelledWithoutExecution(t *testing.T) {
	dir := t.TempDir()
	executed := false
	run := func(ctx context.Context, req Request, progress func(string, int64)) Result {
		executed = true
		return failed(req, "must not run")
	}
	m := openTest(t, dir, run, discardingSend)
	defer m.Close()
	request := envelope(strings.Repeat("a", 40), strings.Repeat("d", 64))
	request.Deadline = time.Now().UTC().Add(-time.Minute).Format(time.RFC3339)
	if err := m.Accept(request); err != nil {
		t.Fatal(err)
	}
	r, err := readRecord(m.dir, testSyncID)
	if err != nil {
		t.Fatal(err)
	}
	if r.Result == nil || r.Result.Status != "cancelled" || executed {
		t.Fatalf("expected durable cancelled record without execution, got %+v executed=%v", r.Result, executed)
	}
}

func TestRestartWithoutResultReportsUncertain(t *testing.T) {
	// 已接单但没有持久终态：重启后 recover 回报 uncertain（对账语义，A2-02）。
	dir := t.TempDir()
	request := envelope(strings.Repeat("a", 40), strings.Repeat("d", 64))
	ledger := recordDirectory(dir, "wss://control.example", 2)
	if err := os.MkdirAll(ledger, 0700); err != nil {
		t.Fatal(err)
	}
	if err := saveRecord(ledger, record{Request: request}); err != nil {
		t.Fatal(err)
	}
	results := make(chan Result, 2)
	m := openTest(t, dir, nil, func(ctx context.Context, kind string, body any) error {
		if result, ok := body.(Result); ok {
			results <- result
		}
		return nil
	})
	defer m.Close()
	if _, ok := m.pending[testSyncID]; !ok {
		t.Fatal("expected pending replay after restart")
	}
	m.poll()
	select {
	case result := <-results:
		if result.Status != "uncertain" {
			t.Fatalf("expected uncertain replay, got %+v", result)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("uncertain replay not delivered")
	}
}

func TestCancelBeforeExecutionAndAcknowledgeClearsPending(t *testing.T) {
	dir := t.TempDir()
	m := openTest(t, dir, nil, discardingSend)
	defer m.Close()
	if err := m.Cancel(testSyncID); err != nil {
		t.Fatal(err)
	}
	r, err := readRecord(m.dir, testSyncID)
	if err != nil || r.Result == nil || r.Result.Status != "cancelled" {
		t.Fatalf("expected cancelled-before-execution record, got %+v err=%v", r, err)
	}
	m.pending[testSyncID] = r
	if err := m.Acknowledge(testSyncID); err != nil {
		t.Fatal(err)
	}
	if _, ok := m.pending[testSyncID]; ok {
		t.Fatal("acknowledged record must leave the pending replay set")
	}
	r, err = readRecord(m.dir, testSyncID)
	if err != nil || !r.Acked {
		t.Fatalf("acknowledgement must be durable, got %+v err=%v", r, err)
	}
}

func TestForeignLedgerFileDisablesSyncExecutor(t *testing.T) {
	// 异物文件名：Open 降级返回 ErrLedgerCorrupt（停用同步，采集与告警继续）。
	dir := t.TempDir()
	ledger := recordDirectory(dir, "wss://control.example", 2)
	if err := os.MkdirAll(ledger, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(ledger, "notes.json"), []byte(`{"x":1}`), 0600); err != nil {
		t.Fatal(err)
	}
	m, err := Open(context.Background(), dir, "wss://control.example", 2, nil, nil,
		slog.New(slog.NewTextHandler(io.Discard, nil)))
	if m != nil || !errors.Is(err, ErrLedgerCorrupt) {
		t.Fatalf("expected degraded open, got manager=%v err=%v", m, err)
	}
}
