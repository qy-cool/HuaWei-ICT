package config

import (
	"path/filepath"
	"testing"
)

func setValidEnvironment(t *testing.T) {
	t.Helper()
	t.Setenv("CLOIDE_BACKEND_URL", "ws://localhost:18080")
	t.Setenv("CLOIDE_SERVER_ID", "42")
	t.Setenv("CLOIDE_AGENT_TOKEN", "test-agent-token")
	t.Setenv("CLOIDE_COLLECT_INTERVAL_SECONDS", "7")
	t.Setenv("CLOIDE_HEARTBEAT_INTERVAL_SECONDS", "30")
	t.Setenv("CLOIDE_RECONNECT_INITIAL_SECONDS", "5")
	t.Setenv("CLOIDE_RECONNECT_MAX_SECONDS", "60")
	t.Setenv("CLOIDE_METRICS_BUFFER_PATH", filepath.Join(t.TempDir(), "metrics-buffer.json"))
	t.Setenv("CLOIDE_METRICS_BUFFER_MAX_ENTRIES", "720")
	t.Setenv("CLOIDE_METRICS_ACK_TIMEOUT_SECONDS", "15")
	t.Setenv("CLOIDE_METRICS_RETRY_INITIAL_SECONDS", "2")
	t.Setenv("CLOIDE_METRICS_RETRY_MAX_SECONDS", "60")
	t.Setenv("CLOIDE_METRICS_REPLAY_MIN_INTERVAL_MILLIS", "2500")
	t.Setenv("CLOIDE_METRICS_RETRY_JITTER_ENABLED", "true")
	t.Setenv("CLOIDE_LOG_LEVEL", "debug")
	t.Setenv("CLOIDE_TERMINAL_ENABLED", "false")
	t.Setenv("CLOIDE_TERMINAL_SHELL", "/bin/bash")
	t.Setenv("CLOIDE_TERMINAL_MAX_SESSIONS", "4")
	t.Setenv("CLOIDE_TERMINAL_MAX_INPUT_BYTES", "16384")
	t.Setenv("CLOIDE_TERMINAL_MAX_OUTPUT_BYTES", "16384")
	t.Setenv("CLOIDE_TERMINAL_OUTPUT_RATE_BYTES_PER_SECOND", "262144")
	t.Setenv("CLOIDE_TERMINAL_OUTPUT_BURST_BYTES", "524288")
	t.Setenv("CLOIDE_TERMINAL_OUTPUT_QUEUE_SIZE", "64")
	t.Setenv("CLOIDE_TERMINAL_IDLE_TIMEOUT_SECONDS", "1200")
	t.Setenv("CLOIDE_TERMINAL_MAX_LIFETIME_SECONDS", "28800")
}

// TestLoadValidConfig 验证合法环境变量能完整加载并覆盖默认值。
func TestLoadValidConfig(t *testing.T) {
	setValidEnvironment(t)
	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.ServerID != 42 || cfg.CollectIntervalSeconds != 7 || cfg.LogLevel != "debug" ||
		cfg.TerminalOutputRateBytesPerSecond != 256*1024 || cfg.TerminalOutputBurstBytes != 512*1024 {
		t.Fatalf("unexpected config: %+v", cfg)
	}
}

func TestLoadUsesTerminalOutputRateDefaults(t *testing.T) {
	setValidEnvironment(t)
	t.Setenv("CLOIDE_TERMINAL_OUTPUT_RATE_BYTES_PER_SECOND", "")
	t.Setenv("CLOIDE_TERMINAL_OUTPUT_BURST_BYTES", "")
	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.TerminalOutputRateBytesPerSecond != 256*1024 || cfg.TerminalOutputBurstBytes != 512*1024 {
		t.Fatalf("unexpected terminal output defaults: %+v", cfg)
	}
}

func TestLoadUsesByteCapDefaultAndValidValue(t *testing.T) {
	setValidEnvironment(t)
	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.MetricsBufferMaxBytes != 0 {
		t.Fatalf("default metrics buffer max bytes = %d, want 0 (unlimited)", cfg.MetricsBufferMaxBytes)
	}
	t.Setenv("CLOIDE_METRICS_BUFFER_MAX_BYTES", "262144")
	cfg, err = Load()
	if err != nil {
		t.Fatalf("Load() with byte cap error = %v", err)
	}
	if cfg.MetricsBufferMaxBytes != 262144 {
		t.Fatalf("metrics buffer max bytes = %d, want 262144", cfg.MetricsBufferMaxBytes)
	}
}

func TestLoadRejectsInvalidInteger(t *testing.T) {
	setValidEnvironment(t)
	t.Setenv("CLOIDE_HEARTBEAT_INTERVAL_SECONDS", "not-a-number")
	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want invalid integer error")
	}
}

func TestLoadRejectsInvalidValues(t *testing.T) {
	tests := []struct {
		name  string
		key   string
		value string
	}{
		{"missing backend URL", "CLOIDE_BACKEND_URL", ""},
		{"invalid backend URL", "CLOIDE_BACKEND_URL", "http://localhost:18080"},
		{"backend URL with path", "CLOIDE_BACKEND_URL", "wss://example.com/api"},
		{"backend URL with query", "CLOIDE_BACKEND_URL", "wss://example.com?x=1"},
		{"backend URL with fragment", "CLOIDE_BACKEND_URL", "wss://example.com#frag"},
		{"backend URL missing host", "CLOIDE_BACKEND_URL", "wss://"},
		{"missing token", "CLOIDE_AGENT_TOKEN", ""},
		{"zero collect interval", "CLOIDE_COLLECT_INTERVAL_SECONDS", "0"},
		{"zero heartbeat interval", "CLOIDE_HEARTBEAT_INTERVAL_SECONDS", "0"},
		{"zero reconnect initial", "CLOIDE_RECONNECT_INITIAL_SECONDS", "0"},
		{"metrics buffer relative path", "CLOIDE_METRICS_BUFFER_PATH", "metrics-buffer.json"},
		{"metrics buffer capacity zero", "CLOIDE_METRICS_BUFFER_MAX_ENTRIES", "0"},
		{"metrics acknowledgement timeout zero", "CLOIDE_METRICS_ACK_TIMEOUT_SECONDS", "0"},
		{"metrics retry initial zero", "CLOIDE_METRICS_RETRY_INITIAL_SECONDS", "0"},
		{"metrics retry max below initial", "CLOIDE_METRICS_RETRY_MAX_SECONDS", "1"},
		{"metrics replay interval too large", "CLOIDE_METRICS_REPLAY_MIN_INTERVAL_MILLIS", "60001"},
		{"metrics buffer bytes below minimum", "CLOIDE_METRICS_BUFFER_MAX_BYTES", "1023"},
		{"metrics retry jitter invalid", "CLOIDE_METRICS_RETRY_JITTER_ENABLED", "sometimes"},
		{"terminal sessions too large", "CLOIDE_TERMINAL_MAX_SESSIONS", "5"},
		{"terminal input too large", "CLOIDE_TERMINAL_MAX_INPUT_BYTES", "16385"},
		{"terminal output rate zero", "CLOIDE_TERMINAL_OUTPUT_RATE_BYTES_PER_SECOND", "0"},
		{"terminal output burst below output block", "CLOIDE_TERMINAL_OUTPUT_BURST_BYTES", "16383"},
		{"terminal relative shell", "CLOIDE_TERMINAL_SHELL", "bash"},
		{"process top N too large", "CLOIDE_PROCESS_TOP_N", "51"},
		{"process top N negative", "CLOIDE_PROCESS_TOP_N", "-1"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			setValidEnvironment(t)
			if tt.key == "CLOIDE_TERMINAL_SHELL" {
				t.Setenv("CLOIDE_TERMINAL_ENABLED", "true")
			}
			t.Setenv(tt.key, tt.value)
			if _, err := Load(); err == nil {
				t.Fatal("Load() error = nil, want validation error")
			}
		})
	}
}

func TestLoadRejectsReconnectMaxBelowInitial(t *testing.T) {
	setValidEnvironment(t)
	t.Setenv("CLOIDE_RECONNECT_INITIAL_SECONDS", "10")
	t.Setenv("CLOIDE_RECONNECT_MAX_SECONDS", "5")
	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want reconnect range error")
	}
}

// TestLoadUsesProcessTopNDefaultsAndOverride 验证进程采集默认开启 10 条、
// 显式设 0 表示关闭。
func TestLoadUsesProcessTopNDefaultsAndOverride(t *testing.T) {
	setValidEnvironment(t)
	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.ProcessTopN != 10 {
		t.Fatalf("default ProcessTopN = %d, want 10", cfg.ProcessTopN)
	}

	t.Setenv("CLOIDE_PROCESS_TOP_N", "0")
	cfg, err = Load()
	if err != nil {
		t.Fatalf("Load() with disabled process top N error = %v", err)
	}
	if cfg.ProcessTopN != 0 {
		t.Fatalf("ProcessTopN = %d, want 0", cfg.ProcessTopN)
	}
}

// TestLoadExtendedResourcesDefaultsAndOverride 验证扩展资源采集默认开启、
// 显式置 0 可关闭（协议 v1.5 的运行时开关）。
func TestLoadExtendedResourcesDefaultsAndOverride(t *testing.T) {
	setValidEnvironment(t)
	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if !cfg.ExtendedResources {
		t.Fatal("ExtendedResources should default to true")
	}

	t.Setenv("CLOIDE_EXTENDED_RESOURCES", "0")
	cfg, err = Load()
	if err != nil {
		t.Fatalf("Load() with disabled extended resources error = %v", err)
	}
	if cfg.ExtendedResources {
		t.Fatal("ExtendedResources = true, want false when CLOIDE_EXTENDED_RESOURCES=0")
	}
}
