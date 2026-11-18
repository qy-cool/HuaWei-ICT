// One process per workspace. All three socket addresses are operator-supplied loopback endpoints.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

const cookieName = "__Host-cloide-workspace"

type guard struct {
	host, check, key string
	client           *http.Client
	proxy            http.Handler
	interval         time.Duration
}

func loopback(addr string) bool {
	host, port, err := net.SplitHostPort(addr)
	n, parseErr := strconv.Atoi(port)
	ip := net.ParseIP(host)
	return err == nil && parseErr == nil && n > 0 && n <= 65535 && ip != nil && ip.IsLoopback()
}

func (g *guard) allowed(ctx context.Context, cookie *http.Cookie) bool {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, g.check, nil)
	if err != nil {
		return false
	}
	req.Header.Set("X-Cloide-Gateway-Key", g.key)
	req.Header.Set("X-Cloide-Workspace-Host", g.host)
	req.AddCookie(cookie)
	res, err := g.client.Do(req)
	if err != nil {
		return false
	}
	defer res.Body.Close()
	return res.StatusCode == http.StatusNoContent
}

func (g *guard) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	origin := r.Header.Get("Origin")
	requiresOrigin := r.Header.Get("Upgrade") != "" || (r.Method != http.MethodGet && r.Method != http.MethodHead)
	if r.Host != g.host || (requiresOrigin || origin != "") && origin != "https://"+g.host {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	cookie, err := r.Cookie(cookieName)
	if err != nil || !g.allowed(r.Context(), cookie) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	// ReverseProxy closes the upstream upgraded connection when the request context is cancelled.
	// Revalidate long HTTP responses and active WebSockets as well as new handshakes.
	go func() {
		ticker := time.NewTicker(g.interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if !g.allowed(ctx, cookie) {
					cancel()
					return
				}
			}
		}
	}()
	g.proxy.ServeHTTP(w, r.WithContext(ctx))
}

func main() {
	host := flag.String("host", "", "exact public workspace DNS name")
	listen := flag.String("listen", "127.0.0.1:16400", "loopback address for Nginx")
	ide := flag.String("ide", "127.0.0.1:16401", "fixed private code-server loopback endpoint")
	control := flag.String("control", "127.0.0.1:18080", "private control API loopback endpoint")
	flag.Parse()
	key := os.Getenv("WORKSPACE_GATEWAY_KEY")
	if !loopback(*listen) || !loopback(*ide) || !loopback(*control) || len(key) < 32 || *host == "" ||
		strings.ContainsAny(*host, "/:# ?@\\") {
		log.Fatal("invalid workspace configuration: check the DNS name, gateway key and three loopback endpoints")
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	g := &guard{host: *host, check: "http://" + *control + "/api/workspace-gateway/authorize", key: key,
		client: &http.Client{Transport: transport, Timeout: 3 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
		proxy:  ideProxy(*host, &url.URL{Scheme: "http", Host: *ide}, transport), interval: 10 * time.Second}
	server := &http.Server{Addr: *listen, Handler: g, ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second}
	fmt.Fprintln(os.Stderr, "workspace session guard listening on", *listen)
	log.Fatal(server.ListenAndServe())
}

func ideProxy(host string, destination *url.URL, transport http.RoundTripper) *httputil.ReverseProxy {
	return &httputil.ReverseProxy{
		Transport: transport,
		Rewrite: func(req *httputil.ProxyRequest) {
			req.SetURL(destination)
			req.Out.Host = host
			req.Out.Header.Del("Cookie")
			req.Out.Header.Del("Authorization")
			req.Out.Header.Del("X-Cloide-Gateway-Key")
			req.Out.Header.Del("X-Cloide-Workspace-Host")
			req.Out.Header.Del("X-Cloide-Browser-Origin")
			req.Out.Header.Set("X-Forwarded-Proto", "https")
		},
		ModifyResponse: func(res *http.Response) error {
			res.Header.Del("Set-Cookie")
			return nil
		},
	}
}
