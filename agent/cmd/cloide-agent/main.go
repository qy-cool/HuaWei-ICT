// Package main 是 cloide-shell Agent 的启动入口。
//
// 加载配置并启动 WebSocket、指标采集和指标上报。
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"cloide/agent/internal/collector"
	"cloide/agent/internal/config"
	"cloide/agent/internal/jobs"
	"cloide/agent/internal/metricbuffer"
	"cloide/agent/internal/reporter"
	"cloide/agent/internal/syncs"
	"cloide/agent/internal/wsclient"
)

// main 加载配置并运行 Agent，配置错误或连接运行异常时以非零状态退出。
func main() {
	cfg, err := config.Load()
	if err != nil {
		fmt.Fprintf(os.Stderr, "config load failed: %v\n", err)
		os.Exit(1)
	}

	logger := newLogger(cfg.LogLevel)
	logger.Info("cloide agent starting",
		"backend_url", cfg.BackendURL,
		"server_id", cfg.ServerID,
		"collect_interval", cfg.CollectIntervalSeconds,
		"heartbeat_interval", cfg.HeartbeatIntervalSeconds,
	)

	client := wsclient.NewClient(
		cfg.BackendURL,
		cfg.ServerID,
		cfg.AgentToken,
		logger,
		time.Duration(cfg.HeartbeatIntervalSeconds)*time.Second,
		time.Duration(cfg.ReconnectInitialSeconds)*time.Second,
		time.Duration(cfg.ReconnectMaxSeconds)*time.Second,
	)
	terminalAgent, err := newTerminalAgent(cfg, client, logger)
	if err != nil {
		logger.Error("terminal initialization failed", "error", err)
		os.Exit(1)
	}
	metricsBuffer, err := metricbuffer.Open(cfg.MetricsBufferPath, cfg.ServerID, cfg.MetricsBufferMaxEntries, cfg.MetricsBufferMaxBytes)
	if err != nil {
		logger.Error("metrics buffer initialization failed", "error", err)
		os.Exit(1)
	}
	metricsReporter := reporter.NewReporter(cfg.ServerID, logger, client, metricsBuffer, newReporterOptions(cfg))
	commandAgent := newCommandAgent(cfg, client, logger)
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	var jobManager *jobs.Manager
	if cfg.JobManifest != "" {
		jobManager, err = openRepositoryJobs(ctx, cfg, client, logger)
		if err != nil {
			if errors.Is(err, jobs.ErrLedgerCorrupt) {
				// 记录级账本损坏：停用执行器但保持采集与告警在线（job.* 路由与 job.ready
				// 均以 jobManager 为空跳过），避免 systemd Restart=on-failure 下的重启风暴；
				// 清理账本目录并重启后恢复。
				logger.Error("repository test runner disabled by corrupt job ledger; clean the ledger directory and restart to re-enable", "error", err)
			} else {
				logger.Error("repository test runner initialization failed", "error", err)
				os.Exit(1)
			}
		}
		if jobManager != nil {
			jobManager.Start()
		}
	}
	// 源码同步（source.sync.v1）：manifest 配置 sync_root 才启用；账本损坏时停用
	// 同步执行器但保持采集、告警与作业路由在线，与作业账本的降级策略一致。
	var syncManager *syncs.Manager
	if cfg.JobManifest != "" {
		syncManager, err = openSourceSyncs(ctx, cfg, client, logger)
		if err != nil {
			if errors.Is(err, syncs.ErrLedgerCorrupt) {
				logger.Error("source sync executor disabled by corrupt ledger; clean the ledger directory and restart to re-enable", "error", err)
			} else {
				logger.Error("source sync initialization failed", "error", err)
				os.Exit(1)
			}
		}
		if syncManager != nil {
			syncManager.Start()
		}
	}
	// 集中消息路由：terminal.* 与 command.* 各自独立子协议，未知类型仅记调试日志。
	client.SetMessageHandler(func(ctx context.Context, message wsclient.AgentMessage) {
		switch {
		case strings.HasPrefix(message.Type, "terminal."):
			terminalAgent.handle(ctx, message)
		case strings.HasPrefix(message.Type, "command."):
			commandAgent.handle(ctx, message)
		case strings.HasPrefix(message.Type, "job.") && jobManager != nil:
			if err := handleRepositoryJob(jobManager, message); err != nil {
				logger.Warn("job frame rejected", "type", message.Type, "error", err)
			}
		case strings.HasPrefix(message.Type, "source.sync.") && syncManager != nil:
			if err := handleSourceSync(syncManager, message); err != nil {
				logger.Warn("source sync frame rejected", "type", message.Type, "error", err)
			}
		default:
			logger.Debug("unsupported agent message", "type", message.Type)
		}
	})
	client.SetMetricsAckHandler(metricsReporter.HandleMetricsAck)
	client.SetMetricsNackHandler(metricsReporter.HandleMetricsNack)
	client.SetHeartbeatStatsProvider(func() wsclient.HeartbeatPayload {
		stats := metricsBuffer.Stats()
		return wsclient.NewHeartbeatPayloadWithDeliveryStats(stats.PendingCount, stats.PendingBytes,
			stats.OldestCollectedAt, stats.DropCount, stats.DeadLetterCount, stats.DeadLetterBytes)
	})
	client.SetAuthenticatedHandler(func() {
		metricsReporter.HandleAuthenticated()
		if jobManager != nil {
			jobManager.Wake()
		}
	})
	client.SetDisconnectHandler(func() {
		metricsReporter.HandleDisconnect()
		terminalAgent.manager.CloseAll("agent_disconnected")
	})

	metricsCollector := collector.NewGopsutilCollector()
	if sampler := collector.NewProcessSampler(cfg.ProcessTopN); sampler != nil {
		metricsCollector.SetProcessSampler(sampler)
	}
	// 扩展资源采集（每挂载点容量与分网卡速率）默认开启，CLOIDE_EXTENDED_RESOURCES=0 关闭。
	if cfg.ExtendedResources {
		metricsCollector.SetExtendedSampler(collector.NewExtendedSampler())
	}
	err = runWithDependencies(ctx, cfg, logger, client, metricsCollector, metricsReporter)
	if jobManager != nil {
		jobManager.Close()
	}
	if err != nil {
		logger.Error("agent exited with error", "error", err)
		os.Exit(1)
	}
	terminalAgent.manager.CloseAll("agent_shutdown")
	logger.Info("agent shutdown complete")
}

// newReporterOptions converts validated Agent configuration into the single
// Reporter option set used by both the production and test-support paths.
func newReporterOptions(cfg *config.Config) reporter.Options {
	return reporter.Options{
		AckTimeout:        time.Duration(cfg.MetricsAckTimeoutSeconds) * time.Second,
		RetryInitial:      time.Duration(cfg.MetricsRetryInitialSeconds) * time.Second,
		RetryMax:          time.Duration(cfg.MetricsRetryMaxSeconds) * time.Second,
		RetryJitter:       cfg.MetricsRetryJitterEnabled,
		ReplayMinInterval: time.Duration(cfg.MetricsReplayMinIntervalMillis) * time.Millisecond,
		NackRetryMax:      cfg.MetricsNackRetryMax,
		NackRetryInitial:  time.Duration(cfg.MetricsNackRetryInitialSeconds) * time.Second,
		NackRetryMaxDelay: time.Duration(cfg.MetricsNackRetryMaxSeconds) * time.Second,
	}
}

// metricsReporter 定义 Agent 运行循环所需的最小指标上报能力。
type metricsReporter interface {
	Report(collector.Metrics) error
}

// run 在同一可取消生命周期内运行 WebSocket、指标采集和上报。
func run(ctx context.Context, cfg *config.Config, logger *slog.Logger, client *wsclient.Client) error {
	metricsBuffer, err := metricbuffer.Open(cfg.MetricsBufferPath, cfg.ServerID, cfg.MetricsBufferMaxEntries, cfg.MetricsBufferMaxBytes)
	if err != nil {
		return fmt.Errorf("open metrics buffer: %w", err)
	}
	return runWithDependencies(ctx, cfg, logger, client, collector.NewGopsutilCollector(),
		reporter.NewReporter(cfg.ServerID, logger, client, metricsBuffer, newReporterOptions(cfg)))
}

// runWithDependencies 允许测试替换采集器和上报器，生产环境由 run 注入真实实现。
func runWithDependencies(ctx context.Context, cfg *config.Config, logger *slog.Logger, client *wsclient.Client,
	metricsCollector collector.Collector, metricsReporter metricsReporter) error {
	collectTicker := time.NewTicker(time.Duration(cfg.CollectIntervalSeconds) * time.Second)
	defer collectTicker.Stop()

	clientErrCh := make(chan error, 1)
	go func() {
		clientErrCh <- client.Run(ctx)
	}()

	reportMetrics(metricsCollector, metricsReporter, logger)
	for {
		select {
		case <-ctx.Done():
			err := <-clientErrCh
			if errors.Is(err, context.Canceled) {
				return nil
			}
			return err
		case err := <-clientErrCh:
			if errors.Is(err, context.Canceled) {
				return nil
			}
			return err
		case <-collectTicker.C:
			reportMetrics(metricsCollector, metricsReporter, logger)
		}
	}
}

// reportMetrics 采集并尽力上报一次指标；采集或发送失败不会停止 Agent。
func reportMetrics(metricsCollector collector.Collector, metricsReporter metricsReporter, logger *slog.Logger) {
	metrics, err := metricsCollector.Collect()
	if err != nil {
		logger.Warn("metrics collection failed", "error", err)
		return
	}
	if err := metricsReporter.Report(metrics); err != nil {
		logger.Warn("metrics report failed", "error", err)
	}
}

// newLogger 创建结构化日志器，输出 JSON 到 stdout。
func newLogger(level string) *slog.Logger {
	var lvl slog.Level
	switch level {
	case "debug":
		lvl = slog.LevelDebug
	case "warn":
		lvl = slog.LevelWarn
	case "error":
		lvl = slog.LevelError
	default:
		lvl = slog.LevelInfo
	}
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: lvl})
	return slog.New(handler)
}
