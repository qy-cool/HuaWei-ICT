# cloide-shell agent

Go 1.23 采集 Agent：gopsutil 指标采集、WebSocket 上报 `/ws/agent`、离线 FIFO 缓冲、
心跳/重连、Linux PTY 终端与受限命令执行（默认关闭）。

## 目录

```text
cmd/cloide-agent/                  # 生产入口
cmd/terminal-integration/          # Linux PTY 联调工具（开发用）
cmd/terminal-flow-control-integration/
internal/{collector,reporter,metricbuffer,wsclient,terminal,command,config}/
config/agent.example.yml
```

## 构建与测试

```bash
go vet ./...
go test ./...
go build -o bin/cloide-agent ./cmd/cloide-agent
```

## 配置

全部通过环境变量注入（`config/agent.example.yml` 仅为示例）：

| 变量 | 说明 |
|---|---|
| `CLOIDE_BACKEND_URL` | 后端 WebSocket，生产必须 `wss://` |
| `CLOIDE_SERVER_ID` | 后端服务器记录 ID |
| `CLOIDE_AGENT_TOKEN` | 一次性发放的 Agent Token |
| `CLOIDE_METRICS_BUFFER_PATH` | 离线缓冲路径，默认 `/var/lib/cloide/metrics-buffer.json` |
| `CLOIDE_TERMINAL_ENABLED` / `CLOIDE_COMMAND_ENABLED` | PTY 终端/命令域，默认 `false`（仅 Linux） |

## 安装

生产用 systemd：`deploy/agent/install-agent.sh`。脚本强制要求
`AGENT_BASE_URL`（例如 `https://SERVER_IP_OR_DOMAIN`），不再内置任何默认域名。

## 文档

- WS 协议：`docs/protocol/websocket-protocol.md`
- 命令协议：`docs/protocol/command-protocol-v1.md`
- 部署：`docs/ops-manual/Go-Agent 部署使用手册.md`
