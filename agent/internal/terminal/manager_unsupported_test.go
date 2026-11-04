//go:build !linux

package terminal

import (
	"context"
	"errors"
	"testing"
)

func TestUnsupportedManagerRejectsTerminalOperations(t *testing.T) {
	manager, err := NewManager(Config{Enabled: true}, Callbacks{})
	if err != nil {
		t.Fatalf("NewManager() error = %v", err)
	}
	if !errors.Is(manager.Open(context.Background(), "session-1", 80, 24), ErrUnsupported) {
		t.Fatal("Open() should reject non-Linux terminal")
	}
	if !errors.Is(manager.WriteInput("session-1", []byte("ls\n")), ErrUnsupported) {
		t.Fatal("WriteInput() should reject non-Linux terminal")
	}
	if !errors.Is(manager.Resize("session-1", 80, 24), ErrUnsupported) {
		t.Fatal("Resize() should reject non-Linux terminal")
	}
	if !errors.Is(manager.Close("session-1", "test"), ErrUnsupported) {
		t.Fatal("Close() should reject non-Linux terminal")
	}
}
