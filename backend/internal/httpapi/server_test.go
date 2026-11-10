package httpapi

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"hivpn/backend/internal/auth"
	"hivpn/backend/internal/directory"
	"hivpn/backend/internal/store"
)

type harness struct {
	t      *testing.T
	st     *store.Memory
	srv    *Server
	h      http.Handler
	signer *directory.Signer
}

func newHarness(t *testing.T) *harness {
	st := store.NewMemory()
	_, signer, err := directory.GenerateKeyPEM()
	if err != nil {
		t.Fatal(err)
	}
	s := New(st, auth.NewSigner([]byte(strings.Repeat("s", 32))), signer, Options{
		AccessTTL: time.Minute, RefreshTTL: time.Hour, DirectoryTTL: time.Hour,
		AccountPepper: []byte(strings.Repeat("p", 32)),
	}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	return &harness{t, st, s, s.PublicHandler(), signer}
}

func (h *harness) do(method, path, token string, body any) (int, map[string]any) {
	var r io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		r = bytes.NewReader(b)
	}
	req := httptest.NewRequest(method, path, r)
	req.RemoteAddr = "198.51.100.7:5555"
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	h.h.ServeHTTP(rec, req)
	var out map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	return rec.Code, out
}

const key1 = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
const key2 = "HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw="

func TestAccountDeviceDirectoryFlow(t *testing.T) {
	h := newHarness(t)

	code, created := h.do("POST", "/v1/accounts", "", nil)
	if code != 201 {
		t.Fatalf("create: %d", code)
	}
	number := created["accountNumber"].(string)

	if code, _ := h.do("POST", "/v1/auth/token", "", map[string]string{"accountNumber": "0000000000000000"}); code != 401 {
		t.Fatalf("unknown account should be 401, got %d", code)
	}
	code, tok := h.do("POST", "/v1/auth/token", "", map[string]string{"accountNumber": number[:4] + " " + number[4:]})
	if code != 200 {
		t.Fatalf("token: %d", code)
	}
	access := tok["accessToken"].(string)
	refresh := tok["refreshToken"].(string)

	// Refresh tokens are single use.
	if code, _ := h.do("POST", "/v1/auth/refresh", "", map[string]string{"refreshToken": refresh}); code != 200 {
		t.Fatalf("refresh: %d", code)
	}
	if code, _ := h.do("POST", "/v1/auth/refresh", "", map[string]string{"refreshToken": refresh}); code != 401 {
		t.Fatalf("reused refresh token should be 401, got %d", code)
	}

	if code, _ := h.do("POST", "/v1/devices", access, map[string]string{"publicKey": "not-a-key", "name": "x"}); code != 400 {
		t.Fatalf("bad key should be 400, got %d", code)
	}
	code, dev := h.do("POST", "/v1/devices", access, map[string]string{"publicKey": key1, "name": "Pixel"})
	if code != 201 || dev["ipv4"] != "10.64.1.1/32" || dev["ipv6"] != "fd64::101/128" {
		t.Fatalf("register: %d %v", code, dev)
	}
	// Same key again is idempotent; a second key exceeds the free plan's limit of 1.
	if code, again := h.do("POST", "/v1/devices", access, map[string]string{"publicKey": key1, "name": "Pixel"}); code != 201 || again["id"] != dev["id"] {
		t.Fatalf("re-register: %d", code)
	}
	if code, _ := h.do("POST", "/v1/devices", access, map[string]string{"publicKey": key2, "name": "Tablet"}); code != 409 {
		t.Fatalf("device limit should be 409, got %d", code)
	}

	code, me := h.do("GET", "/v1/me", access, nil)
	if code != 200 || me["plan"] != "free" || me["devicesInUse"].(float64) != 1 {
		t.Fatalf("me: %d %v", code, me)
	}

	// Signed directory: free accounts see only FREE-tier servers.
	ctx := t.Context()
	now := time.Now()
	_ = h.st.UpsertServer(ctx, store.Server{ID: "sg-1", Tier: store.TierFree, Status: "ONLINE", LastSeen: &now, Hostname: "sg-1.test.invalid", Port: 51820, PublicKey: key2})
	_ = h.st.UpsertServer(ctx, store.Server{ID: "jp-1", Tier: store.TierPremium, Status: "ONLINE", LastSeen: &now, Hostname: "jp-1.test.invalid", Port: 51820, PublicKey: key2})
	code, raw := h.do("GET", "/v1/servers", access, nil)
	if code != 200 {
		t.Fatalf("servers: %d", code)
	}
	payload, err := directory.Verify(h.signer.Public(), directory.Signed{
		Payload: raw["payload"].(string), Signature: raw["signature"].(string),
	})
	if err != nil || len(payload.Servers) != 1 || payload.Servers[0].ID != "sg-1" {
		t.Fatalf("directory: %v %+v", err, payload)
	}

	if code, _ := h.do("GET", "/v1/servers", "", nil); code != 401 {
		t.Fatalf("unauthenticated directory should be 401, got %d", code)
	}
	if code, _ := h.do("DELETE", "/v1/devices/"+dev["id"].(string), access, nil); code != 204 {
		t.Fatalf("delete device: %d", code)
	}
}

func TestNodeAPIRequiresKnownCertificate(t *testing.T) {
	h := newHarness(t)
	ctx := t.Context()
	_ = h.st.UpsertServer(ctx, store.Server{ID: "sg-1", Tier: store.TierFree, Status: "OFFLINE"})
	acct, _ := h.st.CreateAccount(ctx, "hash")
	_, _ = h.st.CreateDevice(ctx, acct.ID, key1, "p", 1)

	node := h.srv.NodeHandler()
	call := func(cn, method, path string, body string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, strings.NewReader(body))
		if cn != "" {
			req.TLS = &tls.ConnectionState{PeerCertificates: []*x509.Certificate{{Subject: pkix.Name{CommonName: cn}}}}
		}
		rec := httptest.NewRecorder()
		node.ServeHTTP(rec, req)
		return rec
	}

	if rec := call("unknown", "GET", "/v1/node/peers", ""); rec.Code != 403 {
		t.Fatalf("unknown node should be 403, got %d", rec.Code)
	}
	rec := call("sg-1", "GET", "/v1/node/peers", "")
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), key1) || !strings.Contains(rec.Body.String(), "10.64.1.1/32") {
		t.Fatalf("peers: %d %s", rec.Code, rec.Body.String())
	}
	if rec := call("sg-1", "POST", "/v1/node/health", `{"loadPercent":12,"status":"ONLINE"}`); rec.Code != 204 {
		t.Fatalf("health: %d", rec.Code)
	}
	s, _ := h.st.ServerByID(ctx, "sg-1")
	if s.Status != "ONLINE" || *s.LoadPercent != 12 {
		t.Fatalf("health not stored: %+v", s)
	}
}

func TestClientIPTrustsOnlyConfiguredProxy(t *testing.T) {
	h := newHarness(t)
	h.srv.opt.TrustedProxy = "10.0.0.2"
	req := httptest.NewRequest("GET", "/v1/ip", nil)
	req.Header.Set("X-Forwarded-For", "1.1.1.1, 203.0.113.9")

	req.RemoteAddr = "198.51.100.7:1"
	if ip := h.srv.clientIP(req); ip != "198.51.100.7" {
		t.Fatalf("spoofed XFF accepted: %s", ip)
	}
	req.RemoteAddr = "10.0.0.2:1"
	if ip := h.srv.clientIP(req); ip != "203.0.113.9" {
		t.Fatalf("proxy XFF ignored: %s", ip)
	}
}

func TestBillingIsDisabledUntilConfigured(t *testing.T) {
	h := newHarness(t)
	_, created := h.do("POST", "/v1/accounts", "", nil)
	_, tok := h.do("POST", "/v1/auth/token", "", map[string]string{"accountNumber": created["accountNumber"].(string)})
	if code, body := h.do("POST", "/v1/billing/google/verify", tok["accessToken"].(string), map[string]string{}); code != 501 || body["error"] != "billing_not_configured" {
		t.Fatalf("billing: %d %v", code, body)
	}
}
