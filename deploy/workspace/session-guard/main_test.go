package main

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestRevokedUpgradedConnectionClosesBothSides(t *testing.T) {
	const host = "orders.ide.example.test"
	var revoked atomic.Bool
	end := make(chan struct{})
	auth := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Cloide-Gateway-Key") != "test-key" || r.Header.Get("X-Cloide-Workspace-Host") != host ||
			r.Header.Get("Cookie") != cookieName+"=test-session" || revoked.Load() {
			w.WriteHeader(403)
			return
		}
		w.WriteHeader(204)
	}))
	defer auth.Close()
	ide := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer close(end)
		if r.Host != host || r.Header.Get("Cookie") != "" || r.Header.Get("Authorization") != "" ||
			r.Header.Get("X-Cloide-Gateway-Key") != "" {
			t.Error("credential forwarded to IDE")
			return
		}
		conn, rw, err := w.(http.Hijacker).Hijack()
		if err != nil {
			t.Error(err)
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
		fmt.Fprint(rw, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n")
		if err := rw.Flush(); err != nil {
			t.Error(err)
			return
		}
		if _, err := rw.ReadByte(); err != io.EOF {
			t.Errorf("upstream connection not closed: %v", err)
		}
	}))
	defer ide.Close()
	dest, _ := url.Parse(ide.URL)
	proxy := httptest.NewServer(&guard{host: host, check: auth.URL, key: "test-key", client: auth.Client(),
		proxy: ideProxy(host, dest, ide.Client().Transport), interval: 10 * time.Millisecond})
	defer proxy.Close()
	conn, err := net.DialTimeout("tcp", strings.TrimPrefix(proxy.URL, "http://"), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	fmt.Fprintf(conn, "GET /terminal HTTP/1.1\r\nHost: %s\r\nOrigin: https://%s\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nCookie: %s=test-session; other=hidden\r\nAuthorization: secret\r\nX-Cloide-Gateway-Key: bogus\r\n\r\n", host, host, cookieName)
	reader := bufio.NewReader(conn)
	res, err := http.ReadResponse(reader, nil)
	if err != nil {
		t.Fatal(err)
	}
	if res.StatusCode != 101 {
		t.Fatalf("upgrade: %v", res.Status)
	}
	revoked.Store(true)
	if _, err := reader.ReadByte(); err != io.EOF {
		t.Fatalf("existing browser connection not closed: %v", err)
	}
	select {
	case <-end:
	case <-time.After(time.Second):
		t.Fatal("upstream still active")
	}
}

func TestOriginAndLoopbackBoundaries(t *testing.T) {
	const host = "only.ide.example.test"
	g := &guard{host: host}
	for _, input := range []struct{ host, origin, method, upgrade string }{
		{"other.ide.example.test", "", "GET", ""}, {host, "https://other.ide.example.test", "GET", "websocket"},
		{host, "", "POST", ""}, {host, "", "GET", "websocket"},
	} {
		r := httptest.NewRequest(input.method, "/", nil)
		r.Host = input.host
		r.Header.Set("Origin", input.origin)
		r.Header.Set("Upgrade", input.upgrade)
		w := httptest.NewRecorder()
		g.ServeHTTP(w, r)
		if w.Code != 403 {
			t.Fatalf("unexpected response %d", w.Code)
		}
	}
	for _, addr := range []string{"0.0.0.0:8080", "127.0.0.1:0", "example.test:80", "127.0.0.1:65536"} {
		if loopback(addr) {
			t.Fatal("unexpected endpoint", addr)
		}
	}
	if !loopback("[::1]:8080") {
		t.Fatal("IPv6 loopback rejected")
	}
}
