// Package config reads all settings from the environment. Secrets are never logged.
package config

import (
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	// Public API listener (plain HTTP behind the TLS-terminating reverse proxy).
	ListenAddr string
	// Node API listener with mutual TLS (VPN nodes connect here directly).
	NodeListenAddr string
	NodeCertFile   string
	NodeKeyFile    string
	NodeCAFile     string

	DatabaseURL string

	// 32+ random bytes, hex. Signs access tokens.
	TokenSecret []byte
	// 32+ random bytes, hex. HMAC pepper for account-number hashes.
	AccountPepper []byte
	// PEM (PKCS#8) ECDSA P-256 private key that signs the server directory.
	DirectoryKeyFile string

	// Only requests from this proxy address may set X-Forwarded-For.
	TrustedProxy string

	AccessTokenTTL  time.Duration
	RefreshTokenTTL time.Duration
	DirectoryTTL    time.Duration

	// Google Play billing is disabled unless this is set (see BACKEND.md).
	GooglePlayPackage string
}

func FromEnv() (*Config, error) {
	c := &Config{
		ListenAddr:        env("HIVPN_LISTEN", ":8080"),
		NodeListenAddr:    env("HIVPN_NODE_LISTEN", ":8443"),
		NodeCertFile:      os.Getenv("HIVPN_NODE_CERT"),
		NodeKeyFile:       os.Getenv("HIVPN_NODE_KEY"),
		NodeCAFile:        os.Getenv("HIVPN_NODE_CA"),
		DatabaseURL:       os.Getenv("HIVPN_DATABASE_URL"),
		DirectoryKeyFile:  os.Getenv("HIVPN_DIRECTORY_KEY"),
		TrustedProxy:      os.Getenv("HIVPN_TRUSTED_PROXY"),
		AccessTokenTTL:    durationEnv("HIVPN_ACCESS_TTL", 15*time.Minute),
		RefreshTokenTTL:   durationEnv("HIVPN_REFRESH_TTL", 30*24*time.Hour),
		DirectoryTTL:      durationEnv("HIVPN_DIRECTORY_TTL", 24*time.Hour),
		GooglePlayPackage: os.Getenv("HIVPN_PLAY_PACKAGE"),
	}
	var err error
	if c.TokenSecret, err = hexSecret("HIVPN_TOKEN_SECRET"); err != nil {
		return nil, err
	}
	if c.AccountPepper, err = hexSecret("HIVPN_ACCOUNT_PEPPER"); err != nil {
		return nil, err
	}
	var missing []string
	if c.DatabaseURL == "" {
		missing = append(missing, "HIVPN_DATABASE_URL")
	}
	if c.DirectoryKeyFile == "" {
		missing = append(missing, "HIVPN_DIRECTORY_KEY")
	}
	if len(missing) > 0 {
		return nil, fmt.Errorf("missing required settings: %s", strings.Join(missing, ", "))
	}
	return c, nil
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func durationEnv(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
		if n, err := strconv.Atoi(v); err == nil {
			return time.Duration(n) * time.Second
		}
	}
	return def
}

func hexSecret(key string) ([]byte, error) {
	v := os.Getenv(key)
	if v == "" {
		return nil, fmt.Errorf("%s is required (32 random bytes, hex: `openssl rand -hex 32`)", key)
	}
	b, err := hex.DecodeString(v)
	if err != nil {
		return nil, fmt.Errorf("%s must be hex", key)
	}
	if len(b) < 32 {
		return nil, errors.New(key + " must be at least 32 bytes")
	}
	return b, nil
}
