package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"cloide/agent/internal/collector"
	"cloide/agent/internal/config"
	"cloide/agent/internal/syncs"
	"cloide/agent/internal/wsclient"
)

func TestSourceSyncDispatchReachesExecutor(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	executed := make(chan syncs.Request, 1)
	run := func(_ context.Context, req syncs.Request, _ func(string, int64)) syncs.Result {
		executed <- req
		return syncs.Result{ID: req.ID, Status: "ready", Commit: req.Commit, Tree: req.Tree, Bundle: req.BundleSHA}
	}
	manager, err := syncs.Open(context.Background(), t.TempDir(), "wss://control.example", 2, run,
		func(context.Context, string, any) error { return nil }, logger)
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	want := syncs.Request{
		ID: "00000000-0000-4000-8000-000000000001", Snapshot: "00000000-0000-4000-8000-000000000002",
		Project: 1, Repository: 8, Generation: 1, Commit: strings.Repeat("a", 40), Tree: strings.Repeat("b", 40),
		BundleSHA: strings.Repeat("c", 64), Bytes: 42, Deadline: time.Now().Add(time.Minute).UTC().Format(time.RFC3339),
	}
	payload, err := json.Marshal(want)
	if err != nil {
		t.Fatal(err)
	}
	handle := nodeMessageHandler(nil, nil, nil, manager, logger)
	handle(context.Background(), wsclient.AgentMessage{Type: "source.sync", Payload: payload})
	select {
	case got := <-executed:
		if got != want {
			t.Fatalf("executed request = %+v, want %+v", got, want)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("source.sync dispatch did not reach the executor")
	}
}

type recordingCollector struct {
	metrics collector.Metrics
	err     error
	calls   int
}

func (c *recordingCollector) Collect() (collector.Metrics, error) {
	c.calls++
	return c.metrics, c.err
}

type recordingReporter struct {
	metrics collector.Metrics
	err     error
	calls   int
}

func (r *recordingReporter) Report(metrics collector.Metrics) error {
	r.calls++
	r.metrics = metrics
	return r.err
}

func TestReporterOptionsMapsAllReliableDeliverySettings(t *testing.T) {
	options := newReporterOptions(&config.Config{
		MetricsAckTimeoutSeconds:       15,
		MetricsRetryInitialSeconds:     2,
		MetricsRetryMaxSeconds:         60,
		MetricsRetryJitterEnabled:      true,
		MetricsReplayMinIntervalMillis: 2500,
	})
	if options.AckTimeout != 15*time.Second || options.RetryInitial != 2*time.Second ||
		options.RetryMax != 60*time.Second || !options.RetryJitter ||
		options.ReplayMinInterval != 2500*time.Millisecond {
		t.Fatalf("unexpected reporter options: %+v", options)
	}
}

func TestReportMetricsReportsCollectedSnapshot(t *testing.T) {
	cpu := 12.5
	metrics := collector.Metrics{CPUPercent: &cpu}
	metricsCollector := &recordingCollector{metrics: metrics}
	metricsReporter := &recordingReporter{}

	reportMetrics(metricsCollector, metricsReporter, slog.New(slog.NewTextHandler(io.Discard, nil)))

	if metricsCollector.calls != 1 || metricsReporter.calls != 1 {
		t.Fatalf("calls: collector=%d reporter=%d, want 1 each", metricsCollector.calls, metricsReporter.calls)
	}
	if metricsReporter.metrics.CPUPercent == nil || *metricsReporter.metrics.CPUPercent != cpu {
		t.Fatalf("reported metrics = %+v, want cpu %.1f", metricsReporter.metrics, cpu)
	}
}

func TestReportMetricsDoesNotReportCollectionFailure(t *testing.T) {
	metricsCollector := &recordingCollector{err: errors.New("collect failed")}
	metricsReporter := &recordingReporter{}

	reportMetrics(metricsCollector, metricsReporter, slog.New(slog.NewTextHandler(io.Discard, nil)))

	if metricsCollector.calls != 1 || metricsReporter.calls != 0 {
		t.Fatalf("calls: collector=%d reporter=%d, want 1 and 0", metricsCollector.calls, metricsReporter.calls)
	}
}

func TestReportMetricsContinuesAfterReportFailure(t *testing.T) {
	metricsCollector := &recordingCollector{}
	metricsReporter := &recordingReporter{err: errors.New("send failed")}

	reportMetrics(metricsCollector, metricsReporter, slog.New(slog.NewTextHandler(io.Discard, nil)))

	if metricsCollector.calls != 1 || metricsReporter.calls != 1 {
		t.Fatalf("calls: collector=%d reporter=%d, want 1 each", metricsCollector.calls, metricsReporter.calls)
	}
}
