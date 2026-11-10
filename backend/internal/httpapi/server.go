// Package httpapi serves the public REST API (behind the reverse proxy) and the node
// API (mutual TLS). Request logs contain method, path pattern, status and duration only:
// never client IPs, account numbers, tokens or keys.
package httpapi

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"hivpn/backend/internal/auth"
	"hivpn/backend/internal/directory"
	"hivpn/backend/internal/ipam"
	"hivpn/backend/internal/ratelimit"
	"hivpn/backend/internal/store"
)

type Options struct {
	AccessTTL     time.Duration
	RefreshTTL    time.Duration
	DirectoryTTL  time.Duration
	AccountPepper []byte
	TrustedProxy  string
	BillingReady  bool
}

type Server struct {
	store  store.Store
	tokens *auth.Signer
	dir    *directory.Signer
	opt    Options
	now    func() time.Time
	log    *slog.Logger

	createLimit  *ratelimit.Limiter // account creation per client
	authLimit    *ratelimit.Limiter // sign-in attempts per client
	generalLimit *ratelimit.Limiter // everything else per client
	speedLimit   *ratelimit.Limiter // speed tests per account

	dirCache sync.Map // plan → cachedDirectory
}

type cachedDirectory struct {
	signed directory.Signed
	at     time.Time
}

func New(st store.Store, tokens *auth.Signer, dir *directory.Signer, opt Options, log *slog.Logger) *Server {
	return &Server{
		store: st, tokens: tokens, dir: dir, opt: opt, now: time.Now, log: log,
		createLimit:  ratelimit.New(5.0/60, 3),
		authLimit:    ratelimit.New(10, 10),
		generalLimit: ratelimit.New(120, 60),
		speedLimit:   ratelimit.New(2, 4),
	}
}

// PublicHandler is the client-facing API.
func (s *Server) PublicHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) })
	mux.HandleFunc("POST /v1/accounts", s.createAccount)
	mux.HandleFunc("POST /v1/auth/token", s.token)
	mux.HandleFunc("POST /v1/auth/refresh", s.refresh)
	mux.HandleFunc("GET /v1/servers", s.authed(s.servers))
	mux.HandleFunc("GET /v1/me", s.authed(s.me))
	mux.HandleFunc("GET /v1/devices", s.authed(s.listDevices))
	mux.HandleFunc("POST /v1/devices", s.authed(s.registerDevice))
	mux.HandleFunc("DELETE /v1/devices/{id}", s.authed(s.deleteDevice))
	mux.HandleFunc("GET /v1/ip", s.ip)
	mux.HandleFunc("GET /v1/speedtest/download", s.authed(s.speedDownload))
	mux.HandleFunc("POST /v1/speedtest/upload", s.authed(s.speedUpload))
	mux.HandleFunc("POST /v1/billing/google/verify", s.authed(s.billingVerify))
	return s.middleware(mux)
}

// NodeHandler is served on the mutual-TLS listener only.
func (s *Server) NodeHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/node/peers", s.nodePeers)
	mux.HandleFunc("POST /v1/node/health", s.nodeHealth)
	return s.middleware(mux)
}

// ---------- plumbing ----------

type statusWriter struct {
	http.ResponseWriter
	code int
}

func (w *statusWriter) WriteHeader(c int) { w.code = c; w.ResponseWriter.WriteHeader(c) }

func (s *Server) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, code: 200}
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		if !strings.HasPrefix(r.URL.Path, "/v1/node/") && r.URL.Path != "/healthz" && !s.generalLimit.Allow(s.clientIP(r)) {
			writeErr(sw, http.StatusTooManyRequests, "rate_limited", "")
		} else {
			r.Body = http.MaxBytesReader(sw, r.Body, maxBody(r))
			next.ServeHTTP(sw, r)
		}
		s.log.Info("req", "method", r.Method, "pattern", r.Pattern, "status", sw.code, "ms", time.Since(start).Milliseconds())
	})
}

func maxBody(r *http.Request) int64 {
	if r.URL.Path == "/v1/speedtest/upload" {
		return maxSpeedBytes
	}
	return 16 << 10
}

// clientIP: RemoteAddr, or the last X-Forwarded-For hop when the request comes from the
// configured reverse proxy. Used only for rate limiting and GET /v1/ip; never stored.
func (s *Server) clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if s.opt.TrustedProxy != "" && host == s.opt.TrustedProxy {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			parts := strings.Split(xff, ",")
			return strings.TrimSpace(parts[len(parts)-1])
		}
	}
	return host
}

type ctxKey struct{}

func (s *Server) authed(h func(http.ResponseWriter, *http.Request, store.Account)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok {
			writeErr(w, http.StatusUnauthorized, "unauthorized", "")
			return
		}
		id, err := s.tokens.Verify(tok, s.now())
		if err != nil {
			writeErr(w, http.StatusUnauthorized, "unauthorized", "")
			return
		}
		acct, err := s.store.AccountByID(r.Context(), id)
		if err != nil {
			writeErr(w, http.StatusUnauthorized, "unauthorized", "")
			return
		}
		h(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, id)), acct)
	}
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, kind, msg string) {
	body := map[string]string{"error": kind}
	if msg != "" {
		body["message"] = msg
	}
	writeJSON(w, code, body)
}

func decode(r *http.Request, v any) error {
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	return dec.Decode(v)
}

// ---------- accounts & tokens ----------

func (s *Server) createAccount(w http.ResponseWriter, r *http.Request) {
	if !s.createLimit.Allow(s.clientIP(r)) {
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "")
		return
	}
	for attempt := 0; attempt < 3; attempt++ {
		number, err := auth.NewAccountNumber()
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "")
			return
		}
		_, err = s.store.CreateAccount(r.Context(), auth.HashNumber(s.opt.AccountPepper, number))
		if errors.Is(err, store.ErrConflict) {
			continue
		}
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "")
			return
		}
		writeJSON(w, http.StatusCreated, map[string]string{"accountNumber": number})
		return
	}
	writeErr(w, http.StatusInternalServerError, "internal", "")
}

func (s *Server) issue(w http.ResponseWriter, r *http.Request, accountID int64) {
	refresh, hash, err := auth.NewRefreshToken()
	if err == nil {
		err = s.store.SaveRefreshToken(r.Context(), hash, accountID, s.now().Add(s.opt.RefreshTTL))
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"accessToken":  s.tokens.Issue(accountID, s.opt.AccessTTL, s.now()),
		"refreshToken": refresh,
		"expiresIn":    int64(s.opt.AccessTTL.Seconds()),
	})
}

func (s *Server) token(w http.ResponseWriter, r *http.Request) {
	if !s.authLimit.Allow(s.clientIP(r)) {
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "")
		return
	}
	var body struct {
		AccountNumber string `json:"accountNumber"`
	}
	if decode(r, &body) != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "")
		return
	}
	number := auth.NormalizeNumber(body.AccountNumber)
	if number == "" {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "")
		return
	}
	acct, err := s.store.AccountByNumberHash(r.Context(), auth.HashNumber(s.opt.AccountPepper, number))
	if err != nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "")
		return
	}
	s.issue(w, r, acct.ID)
}

func (s *Server) refresh(w http.ResponseWriter, r *http.Request) {
	var body struct {
		RefreshToken string `json:"refreshToken"`
	}
	if decode(r, &body) != nil || body.RefreshToken == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "")
		return
	}
	id, err := s.store.ConsumeRefreshToken(r.Context(), auth.HashRefresh(body.RefreshToken), s.now())
	if err != nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "")
		return
	}
	s.issue(w, r, id)
}

func (s *Server) me(w http.ResponseWriter, r *http.Request, a store.Account) {
	devices, err := s.store.ListDevices(r.Context(), a.ID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "")
		return
	}
	plan := a.EffectivePlan(s.now())
	resp := map[string]any{"plan": string(plan), "deviceLimit": store.DeviceLimit(plan), "devicesInUse": len(devices)}
	if a.ExpiresAt != nil && plan != store.PlanFree {
		resp["expiresAt"] = a.ExpiresAt.Unix()
	}
	writeJSON(w, http.StatusOK, resp)
}

// ---------- servers ----------

func (s *Server) servers(w http.ResponseWriter, r *http.Request, a store.Account) {
	plan := a.EffectivePlan(s.now())
	if c, ok := s.dirCache.Load(plan); ok && s.now().Sub(c.(cachedDirectory).at) < 30*time.Second {
		writeJSON(w, http.StatusOK, c.(cachedDirectory).signed)
		return
	}
	list, err := s.store.ListServers(r.Context())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "")
		return
	}
	signed, err := s.dir.Sign(directory.Build(list, plan, s.now(), s.opt.DirectoryTTL))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "")
		return
	}
	s.dirCache.Store(plan, cachedDirectory{signed, s.now()})
	writeJSON(w, http.StatusOK, signed)
}

// ---------- devices ----------

type deviceJSON struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	IPv4 string `json:"ipv4"`
	IPv6 string `json:"ipv6"`
}

func toDeviceJSON(d store.Device) (deviceJSON, error) {
	v4, v6, err := ipam.Addresses(d.HostIndex)
	return deviceJSON{d.ID, d.Name, v4, v6}, err
}

// ValidWireGuardKey: base64 of exactly 32 bytes.
func ValidWireGuardKey(k string) bool {
	b, err := base64.StdEncoding.DecodeString(k)
	return err == nil && len(b) == 32
}

func (s *Server) registerDevice(w http.ResponseWriter, r *http.Request, a store.Account) {
	var body struct {
		PublicKey string `json:"publicKey"`
		Name      string `json:"name"`
	}
	if decode(r, &body) != nil || !ValidWireGuardKey(body.PublicKey) {
		writeErr(w, http.StatusBadRequest, "bad_request", "publicKey must be a WireGuard public key")
		return
	}
	name := body.Name
	if len(name) > 40 {
		name = name[:40]
	}
	d, err := s.store.CreateDevice(r.Context(), a.ID, body.PublicKey, name, store.DeviceLimit(a.EffectivePlan(s.now())))
	switch {
	case errors.Is(err, store.ErrDeviceLimit):
		writeErr(w, http.StatusConflict, "device_limit", "")
		return
	case errors.Is(err, store.ErrConflict):
		writeErr(w, http.StatusBadRequest, "key_in_use", "")
		return
	case err != nil:
		writeErr(w, http.StatusInternalServerError, "internal", "")
		return
	}
	dj, err := toDeviceJSON(d)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "")
		return
	}
	writeJSON(w, http.StatusCreated, dj)
}

func (s *Server) listDevices(w http.ResponseWriter, r *http.Request, a store.Account) {
	list, err := s.store.ListDevices(r.Context(), a.ID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "")
		return
	}
	out := make([]deviceJSON, 0, len(list))
	for _, d := range list {
		if dj, err := toDeviceJSON(d); err == nil {
			out = append(out, dj)
		}
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) deleteDevice(w http.ResponseWriter, r *http.Request, a store.Account) {
	err := s.store.DeleteDevice(r.Context(), a.ID, r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---------- diagnostics ----------

func (s *Server) ip(w http.ResponseWriter, r *http.Request) {
	// No GeoIP database is bundled, so country is omitted rather than guessed.
	writeJSON(w, http.StatusOK, map[string]string{"ip": s.clientIP(r)})
}

const maxSpeedBytes = 50 << 20

var zeros = make([]byte, 64<<10)

func (s *Server) speedDownload(w http.ResponseWriter, r *http.Request, a store.Account) {
	if !s.speedLimit.Allow(strconv.FormatInt(a.ID, 10)) {
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "")
		return
	}
	n, err := strconv.ParseInt(r.URL.Query().Get("bytes"), 10, 64)
	if err != nil || n <= 0 || n > maxSpeedBytes {
		n = 25 << 20
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(n, 10))
	for n > 0 {
		chunk := min(n, int64(len(zeros)))
		if _, err := w.Write(zeros[:chunk]); err != nil {
			return
		}
		n -= chunk
	}
}

func (s *Server) speedUpload(w http.ResponseWriter, r *http.Request, a store.Account) {
	if !s.speedLimit.Allow(strconv.FormatInt(a.ID, 10)) {
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "")
		return
	}
	n, _ := io.Copy(io.Discard, r.Body)
	writeJSON(w, http.StatusOK, map[string]int64{"bytes": n})
}

// ---------- billing ----------

// billingVerify stays disabled until a real Google Play subscription setup exists
// (package name, service account, RTDN). No fake purchase flow is provided.
func (s *Server) billingVerify(w http.ResponseWriter, _ *http.Request, _ store.Account) {
	if !s.opt.BillingReady {
		writeErr(w, http.StatusNotImplemented, "billing_not_configured", "")
		return
	}
	writeErr(w, http.StatusNotImplemented, "billing_not_configured", "Google Play verification is not implemented yet")
}

// ---------- node API (mutual TLS) ----------

// nodeID is the verified client certificate's Common Name, which must equal servers.id.
func nodeID(r *http.Request) string {
	if r.TLS == nil || len(r.TLS.PeerCertificates) == 0 {
		return ""
	}
	return r.TLS.PeerCertificates[0].Subject.CommonName
}

func (s *Server) nodePeers(w http.ResponseWriter, r *http.Request) {
	srv, err := s.store.ServerByID(r.Context(), nodeID(r))
	if err != nil {
		writeErr(w, http.StatusForbidden, "unknown_node", "")
		return
	}
	peers, err := s.store.PeersForTier(r.Context(), srv.Tier, s.now())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "")
		return
	}
	type peerJSON struct {
		PublicKey  string   `json:"publicKey"`
		AllowedIPs []string `json:"allowedIps"`
	}
	out := make([]peerJSON, 0, len(peers))
	for _, p := range peers {
		v4, v6, err := ipam.Addresses(p.HostIndex)
		if err != nil {
			continue
		}
		out = append(out, peerJSON{p.PublicKey, []string{v4, v6}})
	}
	writeJSON(w, http.StatusOK, map[string]any{"peers": out})
}

func (s *Server) nodeHealth(w http.ResponseWriter, r *http.Request) {
	var body struct {
		LoadPercent int    `json:"loadPercent"`
		Status      string `json:"status"`
	}
	if decode(r, &body) != nil || body.LoadPercent < 0 || body.LoadPercent > 100 {
		writeErr(w, http.StatusBadRequest, "bad_request", "")
		return
	}
	if body.Status != "ONLINE" && body.Status != "MAINTENANCE" {
		body.Status = "ONLINE"
	}
	if err := s.store.ReportHealth(r.Context(), nodeID(r), body.LoadPercent, body.Status, s.now()); err != nil {
		writeErr(w, http.StatusForbidden, "unknown_node", "")
		return
	}
	s.dirCache.Clear()
	w.WriteHeader(http.StatusNoContent)
}
