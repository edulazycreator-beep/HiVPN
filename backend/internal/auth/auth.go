// Package auth handles account numbers and tokens.
//
// Account numbers are 16 random digits (~53 bits) shown to the user once. Only
// HMAC-SHA256(pepper, number) is stored: a keyed hash lets the server look an account
// up without being able to reverse it if the database leaks without the pepper.
//
// Access tokens are short-lived HMAC-signed tokens (no database lookup per request).
// Refresh tokens are random, stored hashed, and single-use.
package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math/big"
	"strings"
	"time"
)

var ErrInvalidToken = errors.New("invalid token")

func NewAccountNumber() (string, error) {
	var sb strings.Builder
	ten := big.NewInt(10)
	for i := 0; i < 16; i++ {
		d, err := rand.Int(rand.Reader, ten)
		if err != nil {
			return "", err
		}
		sb.WriteByte(byte('0' + d.Int64()))
	}
	return sb.String(), nil
}

// NormalizeNumber strips spaces/dashes; returns "" if not exactly 16 digits.
func NormalizeNumber(s string) string {
	var sb strings.Builder
	for _, r := range s {
		switch {
		case r >= '0' && r <= '9':
			sb.WriteRune(r)
		case r == ' ' || r == '-':
		default:
			return ""
		}
	}
	if sb.Len() != 16 {
		return ""
	}
	return sb.String()
}

func HashNumber(pepper []byte, number string) string {
	m := hmac.New(sha256.New, pepper)
	m.Write([]byte(number))
	return hex.EncodeToString(m.Sum(nil))
}

func NewRefreshToken() (token, hash string, err error) {
	b := make([]byte, 32)
	if _, err = rand.Read(b); err != nil {
		return "", "", err
	}
	token = base64.RawURLEncoding.EncodeToString(b)
	return token, HashRefresh(token), nil
}

func HashRefresh(token string) string {
	s := sha256.Sum256([]byte(token))
	return hex.EncodeToString(s[:])
}

type claims struct {
	Sub int64  `json:"sub"`
	Exp int64  `json:"exp"`
	Typ string `json:"typ"`
}

type Signer struct{ secret []byte }

func NewSigner(secret []byte) *Signer { return &Signer{secret} }

func (s *Signer) Issue(accountID int64, ttl time.Duration, now time.Time) string {
	body, _ := json.Marshal(claims{Sub: accountID, Exp: now.Add(ttl).Unix(), Typ: "access"})
	payload := base64.RawURLEncoding.EncodeToString(body)
	return payload + "." + s.sign(payload)
}

func (s *Signer) Verify(token string, now time.Time) (int64, error) {
	payload, sig, ok := strings.Cut(token, ".")
	if !ok || !hmac.Equal([]byte(sig), []byte(s.sign(payload))) {
		return 0, ErrInvalidToken
	}
	raw, err := base64.RawURLEncoding.DecodeString(payload)
	if err != nil {
		return 0, ErrInvalidToken
	}
	var c claims
	if json.Unmarshal(raw, &c) != nil || c.Typ != "access" || now.Unix() >= c.Exp || c.Sub <= 0 {
		return 0, ErrInvalidToken
	}
	return c.Sub, nil
}

func (s *Signer) sign(payload string) string {
	m := hmac.New(sha256.New, s.secret)
	m.Write([]byte(payload))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}
