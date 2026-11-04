// Package terminal 管理 Agent 本地交互式终端会话。
package terminal

import (
	"context"
	"errors"
	"time"
)

var (
	// ErrUnsupported 表示当前操作系统不支持 Linux PTY。
	ErrUnsupported = errors.New("terminal is only supported on linux")
	// ErrSessionNotFound 表示请求的终端会话不存在。
	ErrSessionNotFound = errors.New("terminal session not found")
	// ErrSessionLimit 表示 Agent 达到本地会话上限。
	ErrSessionLimit = errors.New("terminal session limit reached")
)

// Config 固定 Agent 本地终端执行边界，远端协议不能覆盖这些字段。
type Config struct {
	Enabled                  bool
	Shell                    string
	MaxSessions              int
	MaxInputBytes            int
	MaxOutputBytes           int
	OutputRateBytesPerSecond int
	OutputBurstBytes         int
	OutputQueueSize          int
	IdleTimeout              time.Duration
	MaxLifetime              time.Duration
}

// Callbacks 将 PTY 状态和输出交给上层协议适配器。
type Callbacks struct {
	Output func(sessionID string, data []byte)
	Closed func(sessionID string, reason string, exitCode *int)
}

// Manager 管理本机 PTY 生命周期。
type Manager interface {
	Open(ctx context.Context, sessionID string, cols uint16, rows uint16) error
	WriteInput(sessionID string, data []byte) error
	Resize(sessionID string, cols uint16, rows uint16) error
	Close(sessionID string, reason string) error
	CloseAll(reason string)
}
