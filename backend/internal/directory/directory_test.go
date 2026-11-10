package directory

import (
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/base64"
	"testing"
	"time"

	"hivpn/backend/internal/store"
)

func srv(id string, tier store.Tier, status string, seen *time.Time) store.Server {
	return store.Server{ID: id, Hostname: id + ".test.invalid", Port: 51820, PublicKey: "k", Tier: tier, Status: status, LastSeen: seen, CountryCode: "SG"}
}

func TestBuildFiltersByPlanAndMarksStale(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	fresh := now.Add(-30 * time.Second)
	stale := now.Add(-10 * time.Minute)
	servers := []store.Server{
		srv("free-1", store.TierFree, "ONLINE", &fresh),
		srv("free-2", store.TierFree, "ONLINE", &stale),
		srv("prem-1", store.TierPremium, "ONLINE", &fresh),
		srv("pro-1", store.TierPro, "MAINTENANCE", &fresh),
	}

	free := Build(servers, store.PlanFree, now, time.Hour)
	if len(free.Servers) != 2 {
		t.Fatalf("free plan sees %d servers, want 2", len(free.Servers))
	}
	if free.Servers[1].Status != "OFFLINE" {
		t.Fatal("a node that stopped reporting must be OFFLINE")
	}
	if got := len(Build(servers, store.PlanPremium, now, time.Hour).Servers); got != 3 {
		t.Fatalf("premium sees %d, want 3", got)
	}
	pro := Build(servers, store.PlanPro, now, time.Hour)
	if len(pro.Servers) != 4 || pro.Servers[3].Status != "MAINTENANCE" {
		t.Fatal("pro must see all servers with their reported status")
	}
	if free.ExpiresAt != now.Add(time.Hour).Unix() {
		t.Fatal("expiry not set")
	}
}

func TestSignVerifyRoundTrip(t *testing.T) {
	_, s, err := GenerateKeyPEM()
	if err != nil {
		t.Fatal(err)
	}
	p := Build([]store.Server{srv("a", store.TierFree, "ONLINE", nil)}, store.PlanFree, time.Now(), time.Hour)
	signed, err := s.Sign(p)
	if err != nil {
		t.Fatal(err)
	}
	got, err := Verify(s.Public(), signed)
	if err != nil || len(got.Servers) != 1 {
		t.Fatalf("verify failed: %v", err)
	}

	// The published public key must parse back to the same key (what the app compiles in).
	der, _ := base64.StdEncoding.DecodeString(s.PublicKeyBase64())
	pub, err := x509.ParsePKIXPublicKey(der)
	if err != nil || !pub.(*ecdsa.PublicKey).Equal(s.Public()) {
		t.Fatal("PublicKeyBase64 does not round-trip")
	}

	// Any change to the payload must break the signature.
	body, _ := base64.StdEncoding.DecodeString(signed.Payload)
	body[len(body)-2] ^= 1
	signed.Payload = base64.StdEncoding.EncodeToString(body)
	if _, err := Verify(s.Public(), signed); err == nil {
		t.Fatal("tampered directory verified")
	}
}
