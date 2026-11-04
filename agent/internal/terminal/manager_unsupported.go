//go:build !linux

package terminal

import "context"

type unsupportedManager struct{}

// NewManager 在非 Linux 系统创建拒绝所有 PTY 请求的管理器。
func NewManager(config Config, callbacks Callbacks) (Manager, error) {
	return unsupportedManager{}, nil
}
func (unsupportedManager) Open(context.Context, string, uint16, uint16) error { return ErrUnsupported }
func (unsupportedManager) WriteInput(string, []byte) error                    { return ErrUnsupported }
func (unsupportedManager) Resize(string, uint16, uint16) error                { return ErrUnsupported }
func (unsupportedManager) Close(string, string) error                         { return ErrUnsupported }
func (unsupportedManager) CloseAll(string)                                    {}
