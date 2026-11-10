// hivpn-nodeagent runs on every VPN node. Every interval it:
//  1. pulls the peer list from the control plane over mutual TLS,
//  2. adds, updates or removes WireGuard peers to match it,
//  3. reports load (peers with a recent handshake / capacity) and status.
//
// It also accepts TCP connections on the probe address (default :443) and closes them
// immediately, so the app can measure latency to the node; WireGuard itself is UDP.
//
// It never logs or reports peer endpoints (client IP addresses).
package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"hivpn/backend/internal/nodeagent"
)

type cfg struct {
	apiURL   string
	cert     string
	key      string
	ca       string
	iface    string
	interval time.Duration
	capacity int
	probe    string
	status   string
}

func load() (cfg, error) {
	c := cfg{
		apiURL:   os.Getenv("NODE_API_URL"), // e.g. https://api.example.com:8443
		cert:     os.Getenv("NODE_CERT"),
		key:      os.Getenv("NODE_KEY"),
		ca:       os.Getenv("NODE_CA"),
		iface:    envOr("NODE_WG_IFACE", "wg0"),
		probe:    envOr("NODE_PROBE_ADDR", ":443"),
		status:   envOr("NODE_STATUS", "ONLINE"),
		interval: 10 * time.Second,
		capacity: 500,
	}
	if v, err := time.ParseDuration(os.Getenv("NODE_INTERVAL")); err == nil {
		c.interval = v
	}
	if v, err := strconv.Atoi(os.Getenv("NODE_CAPACITY")); err == nil && v > 0 {
		c.capacity = v
	}
	if c.apiURL == "" || c.cert == "" || c.key == "" || c.ca == "" {
		return c, errors.New("NODE_API_URL, NODE_CERT, NODE_KEY and NODE_CA are required")
	}
	return c, nil
}

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	c, err := load()
	if err != nil {
		log.Error("config", "err", err)
		os.Exit(1)
	}
	client, err := mtlsClient(c)
	if err != nil {
		log.Error("tls", "err", err)
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go probe(ctx, c.probe, log)

	wg := nodeagent.WG{Iface: c.iface}
	tick := time.NewTicker(c.interval)
	defer tick.Stop()
	for {
		if err := syncOnce(ctx, client, c, wg, log); err != nil {
			log.Warn("sync failed", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}

func mtlsClient(c cfg) (*http.Client, error) {
	pair, err := tls.LoadX509KeyPair(c.cert, c.key)
	if err != nil {
		return nil, err
	}
	caPEM, err := os.ReadFile(c.ca)
	if err != nil {
		return nil, err
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEM) {
		return nil, errors.New("no CA certificates")
	}
	return &http.Client{
		Timeout: 15 * time.Second,
		Transport: &http.Transport{TLSClientConfig: &tls.Config{
			MinVersion:   tls.VersionTLS13,
			Certificates: []tls.Certificate{pair},
			RootCAs:      pool,
		}},
	}, nil
}

func syncOnce(ctx context.Context, client *http.Client, c cfg, wg nodeagent.WG, log *slog.Logger) error {
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, c.apiURL+"/v1/node/peers", nil)
	res, err := client.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("peers: HTTP %d", res.StatusCode)
	}
	var body struct {
		Peers []struct {
			PublicKey  string   `json:"publicKey"`
			AllowedIPs []string `json:"allowedIps"`
		} `json:"peers"`
	}
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		return err
	}
	desired := make(map[string][]string, len(body.Peers))
	for _, p := range body.Peers {
		desired[p.PublicKey] = p.AllowedIPs
	}

	current, err := wg.Dump()
	if err != nil {
		return err
	}
	changes := nodeagent.Diff(current, desired)
	applied := 0
	for _, ch := range changes {
		if err := wg.Apply(ch); err != nil {
			log.Warn("apply", "err", err)
			continue
		}
		applied++
	}
	if applied > 0 {
		log.Info("peers synced", "changes", applied, "total", len(desired))
		if current, err = wg.Dump(); err != nil {
			return err
		}
	}

	active := nodeagent.ActiveCount(current, time.Now(), 3*time.Minute)
	health, _ := json.Marshal(map[string]any{"loadPercent": nodeagent.LoadPercent(active, c.capacity), "status": c.status})
	hreq, _ := http.NewRequestWithContext(ctx, http.MethodPost, c.apiURL+"/v1/node/health", bytes.NewReader(health))
	hreq.Header.Set("Content-Type", "application/json")
	hres, err := client.Do(hreq)
	if err != nil {
		return err
	}
	hres.Body.Close()
	if hres.StatusCode != http.StatusNoContent {
		return fmt.Errorf("health: HTTP %d", hres.StatusCode)
	}
	return nil
}

// probe accepts and immediately closes TCP connections: the app times the handshake.
func probe(ctx context.Context, addr string, log *slog.Logger) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		log.Warn("latency probe disabled", "addr", addr, "err", err)
		return
	}
	go func() { <-ctx.Done(); ln.Close() }()
	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			continue
		}
		conn.Close()
	}
}
