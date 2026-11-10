package auth

import (
	"strings"
	"testing"
	"time"
)

func TestAccountNumberFormat(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 200; i++ {
		n, err := NewAccountNumber()
		if err != nil {
			t.Fatal(err)
		}
		if len(n) != 16 || NormalizeNumber(n) != n {
			t.Fatalf("bad number %q", n)
		}
		seen[n] = true
	}
	if len(seen) < 199 {
		t.Fatal("numbers are not random enough")
	}
}

func TestNormalize(t *testing.T) {
	cases := map[string]string{
		"1234 5678 9012 3456": "1234567890123456",
		"1234-5678-9012-3456": "1234567890123456",
		"123":                 "",
		"1234 5678 9012 345a": "",
		"12345678901234567":   "",
	}
	for in, want := range cases {
		if got := NormalizeNumber(in); got != want {
			t.Errorf("NormalizeNumber(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestHashIsKeyed(t *testing.T) {
	a := HashNumber([]byte(strings.Repeat("a", 32)), "1234567890123456")
	b := HashNumber([]byte(strings.Repeat("b", 32)), "1234567890123456")
	if a == b || len(a) != 64 {
		t.Fatal("hash must depend on the pepper")
	}
}

func TestTokens(t *testing.T) {
	s := NewSigner([]byte(strings.Repeat("k", 32)))
	now := time.Unix(1_700_000_000, 0)
	tok := s.Issue(42, time.Minute, now)

	if id, err := s.Verify(tok, now.Add(30*time.Second)); err != nil || id != 42 {
		t.Fatalf("valid token rejected: %v %d", err, id)
	}
	if _, err := s.Verify(tok, now.Add(2*time.Minute)); err == nil {
		t.Fatal("expired token accepted")
	}
	other := NewSigner([]byte(strings.Repeat("x", 32)))
	if _, err := other.Verify(tok, now); err == nil {
		t.Fatal("token from another key accepted")
	}
	payload, sig, _ := strings.Cut(tok, ".")
	if _, err := s.Verify(payload+"x."+sig, now); err == nil {
		t.Fatal("tampered token accepted")
	}
}

func TestRefreshTokens(t *testing.T) {
	a, ha, _ := NewRefreshToken()
	b, hb, _ := NewRefreshToken()
	if a == b || ha == hb || HashRefresh(a) != ha {
		t.Fatal("refresh tokens must be unique and hash deterministically")
	}
}
