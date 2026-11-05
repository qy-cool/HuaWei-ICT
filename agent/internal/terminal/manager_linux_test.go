//go:build linux

package terminal

import (
	"context"
	"os"
	"sync"
	"testing"
	"time"
)

func TestManagerOpenInputOutputAndClose(t *testing.T) {
	if _, err := os.Stat("/bin/bash"); err != nil {
		t.Skip("/bin/bash is unavailable")
	}
	output := make(chan []byte, 1)
	closed := make(chan string, 1)
	manager, err := NewManager(testConfig(), Callbacks{
		Output: func(sessionID string, data []byte) { output <- data },
		Closed: func(sessionID string, reason string, exitCode *int) { closed <- reason },
	})
	if err != nil {
		t.Fatalf("NewManager() error = %v", err)
	}
	if err = manager.Open(context.Background(), "session-1", 80, 24); err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	if err = manager.WriteInput("session-1", []byte("printf terminal-test\\n")); err != nil {
		t.Fatalf("WriteInput() error = %v", err)
	}
	select {
	case data := <-output:
		if len(data) == 0 {
			t.Fatal("empty terminal output")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("terminal output timeout")
	}
	if err = manager.Resize("session-1", 100, 30); err != nil {
		t.Fatalf("Resize() error = %v", err)
	}
	if err = manager.Close("session-1", "test_close"); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
	select {
	case reason := <-closed:
		if reason != "test_close" {
			t.Fatalf("close reason = %q", reason)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("terminal close timeout")
	}
}

func TestForwardOutputClosesWhenRateLimitIsExhausted(t *testing.T) {
	output := make(chan []byte, 2)
	closed := make(chan string, 1)
	m := &manager{callbacks: Callbacks{
		Output: func(sessionID string, data []byte) { output <- data },
		Closed: func(sessionID string, reason string, exitCode *int) { closed <- reason },
	}, sessions: make(map[string]*session)}
	s := &session{id: "session-1", outputQueue: make(chan []byte, 2), done: make(chan struct{}),
		waitDone: make(chan struct{}), outputLimit: newOutputLimiter(1, 4), closeOnce: sync.Once{}}
	m.sessions[s.id] = s
	s.outputQueue <- []byte("1234")
	s.outputQueue <- []byte("5")
	go m.forwardOutput(s)
	select {
	case data := <-output:
		if string(data) != "1234" {
			t.Fatalf("output = %q, want first permitted output", data)
		}
	case <-time.After(time.Second):
		t.Fatal("permitted output timeout")
	}
	select {
	case reason := <-closed:
		if reason != "output_rate_exceeded" {
			t.Fatalf("close reason = %q, want output_rate_exceeded", reason)
		}
	case <-time.After(time.Second):
		t.Fatal("rate-limit close timeout")
	}
	select {
	case data := <-output:
		t.Fatalf("unexpected excess output: %q", data)
	default:
	}
}

func TestOutputLimiterRefillsAndIsIndependentPerSession(t *testing.T) {
	now := time.Date(2026, time.July, 26, 0, 0, 0, 0, time.UTC)
	clock := func() time.Time { return now }
	firstSession := newOutputLimiterAt(2, 4, clock)
	secondSession := newOutputLimiterAt(2, 4, clock)

	if !firstSession.allow(4) {
		t.Fatal("first session should allow its initial burst")
	}
	if firstSession.allow(1) {
		t.Fatal("first session should reject output after its burst is exhausted")
	}
	if !secondSession.allow(4) {
		t.Fatal("second session must retain its independent initial burst")
	}

	now = now.Add(time.Second)
	if !firstSession.allow(2) {
		t.Fatal("first session should refill two bytes after one second")
	}
	if firstSession.allow(1) {
		t.Fatal("first session should reject output after consuming refilled tokens")
	}
}

func TestManagerLimitsSessions(t *testing.T) {
	if _, err := os.Stat("/bin/bash"); err != nil {
		t.Skip("/bin/bash is unavailable")
	}
	config := testConfig()
	config.MaxSessions = 1
	manager, err := NewManager(config, Callbacks{})
	if err != nil {
		t.Fatalf("NewManager() error = %v", err)
	}
	if err = manager.Open(context.Background(), "session-1", 80, 24); err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	if err = manager.Open(context.Background(), "session-2", 80, 24); err != ErrSessionLimit {
		t.Fatalf("Open() error = %v, want ErrSessionLimit", err)
	}
	manager.CloseAll("test_done")
}

func testConfig() Config {
	return Config{Enabled: true, Shell: "/bin/bash", MaxSessions: 4, MaxInputBytes: 16 * 1024,
		MaxOutputBytes: 16 * 1024, OutputRateBytesPerSecond: 256 * 1024, OutputBurstBytes: 512 * 1024,
		OutputQueueSize: 8, IdleTimeout: time.Minute, MaxLifetime: time.Minute}
}
