package main

import (
	"encoding/base64"
	"encoding/json"
	"testing"

	"cloide/agent/internal/wsclient"
)

func TestDecodeTerminalData(t *testing.T) {
	payload := wsclient.TerminalInputPayload{
		SessionID: "session-1",
		Data:      base64.StdEncoding.EncodeToString([]byte("echo test\n")),
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal payload: %v", err)
	}
	var actual wsclient.TerminalInputPayload
	data, err := decodeTerminalData(raw, &actual, 16)
	if err != nil {
		t.Fatalf("decodeTerminalData() error = %v", err)
	}
	if string(data) != "echo test\n" || actual.SessionID != payload.SessionID {
		t.Fatalf("decoded payload mismatch: data=%q session=%q", data, actual.SessionID)
	}
}

func TestDecodeTerminalDataRejectsInvalidPayload(t *testing.T) {
	tests := []json.RawMessage{
		json.RawMessage(`{"session_id":"","data":"YQ=="}`),
		json.RawMessage(`{"session_id":"session-1","data":"%%%"}`),
		json.RawMessage(`{"session_id":"session-1","data":""}`),
		json.RawMessage(`not-json`),
	}
	for _, raw := range tests {
		var payload wsclient.TerminalInputPayload
		if _, err := decodeTerminalData(raw, &payload, 4); err == nil {
			t.Fatalf("decodeTerminalData(%s) error = nil", raw)
		}
	}

	overlong := wsclient.TerminalInputPayload{SessionID: "session-1", Data: base64.StdEncoding.EncodeToString([]byte("12345"))}
	raw, err := json.Marshal(overlong)
	if err != nil {
		t.Fatalf("marshal overlong payload: %v", err)
	}
	var payload wsclient.TerminalInputPayload
	if _, err := decodeTerminalData(raw, &payload, 4); err == nil {
		t.Fatal("decodeTerminalData() error = nil for overlong data")
	}
}

func TestTerminalMessagesPreserveProtocolFields(t *testing.T) {
	message := wsclient.NewMessageWithID("terminal.closed", "request-1", wsclient.TerminalClosedPayload{
		SessionID: "session-1",
		Reason:    "remote_close",
	})
	if message.MessageID != "request-1" || message.Type != "terminal.closed" {
		t.Fatalf("message metadata mismatch: %+v", message)
	}
	var payload wsclient.TerminalClosedPayload
	if err := json.Unmarshal(message.Payload, &payload); err != nil {
		t.Fatalf("unmarshal closed payload: %v", err)
	}
	if payload.SessionID != "session-1" || payload.Reason != "remote_close" {
		t.Fatalf("closed payload mismatch: %+v", payload)
	}
}

func TestShellIdentifierDoesNotExposeLocalPath(t *testing.T) {
	if actual := shellIdentifier("/bin/bash"); actual != "bash" {
		t.Fatalf("shellIdentifier() = %q, want bash", actual)
	}
}
