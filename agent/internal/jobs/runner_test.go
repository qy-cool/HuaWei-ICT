package jobs

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"testing"
	"time"
)

func TestLoadRunnerAcceptsSourceSyncWithoutRegisteredRepositories(t *testing.T) {
	if runtime.GOOS != "linux" || os.Geteuid() == 0 {
		t.Skip("LoadRunner requires a non-root Linux account")
	}
	root := t.TempDir()
	toolchainRoot := filepath.Join(root, "rootfs")
	if err := os.Mkdir(toolchainRoot, 0700); err != nil {
		t.Fatal(err)
	}
	// LoadRunner only resolves the sandbox tools. An unregistered job must never invoke them.
	for _, tool := range []string{"bwrap", "prlimit"} {
		if err := os.WriteFile(filepath.Join(root, tool), []byte("#!/bin/sh\nexit 99\n"), 0700); err != nil {
			t.Fatal(err)
		}
	}
	t.Setenv("PATH", root)
	configPath := filepath.Join(root, "jobs.json")
	config := map[string]any{
		"state_directory": filepath.Join(root, "ledger"),
		"toolchain_root":  toolchainRoot,
		"toolchains":      []string{"jdk21"},
		"timeout_seconds": 15,
	}
	writeConfig := func() {
		t.Helper()
		body, err := json.Marshal(config)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(configPath, body, 0600); err != nil {
			t.Fatal(err)
		}
	}
	for _, syncRoot := range []string{"", "relative/path", "/"} {
		config["sync_root"] = syncRoot
		writeConfig()
		if _, _, _, err := LoadRunner(configPath, nil); err == nil {
			t.Fatalf("LoadRunner accepted empty repositories with sync_root %q", syncRoot)
		}
	}

	config["sync_root"] = filepath.Join(root, "sync")
	writeConfig()
	var registration Registration
	state, timeout, run, err := LoadRunner(configPath, &registration)
	if err != nil {
		t.Fatal(err)
	}
	if timeout != 15*time.Second || !slices.Equal(registration.Toolchains, []string{"jdk21"}) ||
		!slices.Equal(registration.Actions, []string{"source.sync.v1"}) {
		t.Fatalf("unexpected timeout %v or registration %+v", timeout, registration)
	}
	ready, err := json.Marshal(registration.Snapshot(state))
	if err != nil {
		t.Fatal(err)
	}
	var capabilities map[string]json.RawMessage
	if err := json.Unmarshal(ready, &capabilities); err != nil {
		t.Fatal(err)
	}
	if got := string(capabilities["repositories"]); got != "[]" {
		t.Fatalf("capabilities.repositories = %q, want []", got)
	}
	if got := run(context.Background(), request(), func(string) {}); got.Status != "failed" || got.ExitCode != -1 ||
		got.Output != "repository is not registered for this project" {
		t.Fatalf("unregistered repository result = %+v", got)
	}
}

func TestClassifyCancellationDistinguishesDeadlineFromCancel(t *testing.T) {
	// deadline 到期：failed + exit -1 + 输出前缀标注（协议终态分类的关键差异点）。
	deadline := classifyCancellation(context.DeadlineExceeded, Result{"j", "succeeded", 0, "tail"})
	if deadline.Status != "failed" || deadline.ExitCode != -1 || !strings.HasPrefix(deadline.Output, "test deadline exceeded\n") {
		t.Fatalf("deadline classification: %+v", deadline)
	}
	// 其余取消（含主动 cancel）：cancelled，不加前缀。
	cancelled := classifyCancellation(context.Canceled, Result{"j", "succeeded", 0, "tail"})
	if cancelled.Status != "cancelled" || cancelled.ExitCode != -1 || strings.Contains(cancelled.Output, "test deadline exceeded") {
		t.Fatalf("cancel classification: %+v", cancelled)
	}
	// 未取消：结果原样返回。
	untouched := classifyCancellation(nil, Result{"j", "succeeded", 0, "tail"})
	if untouched.Status != "succeeded" || untouched.ExitCode != 0 || untouched.Output != "tail" {
		t.Fatalf("untouched classification: %+v", untouched)
	}
	// 包装后的 DeadlineExceeded（errors.Is 语义）同样判 failed。
	wrapped := classifyCancellation(fmt.Errorf("watchdog: %w", context.DeadlineExceeded), Result{"j", "failed", 5, ""})
	if wrapped.Status != "failed" || !strings.HasPrefix(wrapped.Output, "test deadline exceeded\n") {
		t.Fatalf("wrapped classification: %+v", wrapped)
	}
}
