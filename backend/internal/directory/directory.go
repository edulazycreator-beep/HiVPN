// Package directory builds and signs the server list the app downloads.
//
// The app verifies the ECDSA P-256 / SHA-256 signature with a public key compiled into
// it, so a compromised proxy or cache cannot inject servers. P-256 is used because
// Android's standard crypto provider supports it on every version back to API 26.
package directory

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"os"
	"time"

	"hivpn/backend/internal/store"
)

type ServerJSON struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	CountryCode string   `json:"countryCode"`
	CountryName string   `json:"countryName"`
	City        string   `json:"city"`
	Hostname    string   `json:"hostname"`
	Port        int      `json:"port"`
	PublicKey   string   `json:"publicKey"`
	Protocols   []string `json:"protocols"`
	Tier        string   `json:"tier"`
	Categories  []string `json:"categories"`
	LoadPercent *int     `json:"loadPercent,omitempty"`
	Status      string   `json:"status"`
}

type Payload struct {
	Version   int          `json:"version"`
	IssuedAt  int64        `json:"issuedAt"`
	ExpiresAt int64        `json:"expiresAt"`
	Servers   []ServerJSON `json:"servers"`
}

type Signed struct {
	Payload   string `json:"payload"`
	Signature string `json:"signature"`
	KeyID     string `json:"keyId"`
}

type Signer struct {
	key   *ecdsa.PrivateKey
	keyID string
}

// StaleAfter: a node that has not reported for this long is shown as OFFLINE.
const StaleAfter = 2 * time.Minute

func LoadSigner(path string) (*Signer, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(raw)
	if block == nil {
		return nil, errors.New("directory key: no PEM block")
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	ec, ok := k.(*ecdsa.PrivateKey)
	if !ok || ec.Curve != elliptic.P256() {
		return nil, errors.New("directory key must be ECDSA P-256")
	}
	return NewSigner(ec), nil
}

func NewSigner(k *ecdsa.PrivateKey) *Signer {
	pub, _ := x509.MarshalPKIXPublicKey(&k.PublicKey)
	sum := sha256.Sum256(pub)
	return &Signer{key: k, keyID: base64.RawURLEncoding.EncodeToString(sum[:8])}
}

// PublicKeyBase64 is the value for the app's `hivpn.directoryPublicKey` Gradle property.
func (s *Signer) PublicKeyBase64() string {
	pub, _ := x509.MarshalPKIXPublicKey(&s.key.PublicKey)
	return base64.StdEncoding.EncodeToString(pub)
}

// GenerateKeyPEM creates a new P-256 key as PKCS#8 PEM (for `hivpn-api keygen`).
func GenerateKeyPEM() ([]byte, *Signer, error) {
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	der, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		return nil, nil, err
	}
	return pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), NewSigner(k), nil
}

// Build lists the servers a plan may use. Nodes that stopped reporting are OFFLINE.
func Build(servers []store.Server, plan store.Plan, now time.Time, ttl time.Duration) Payload {
	allowed := map[store.Tier]bool{}
	for _, t := range store.AllowedTiers(plan) {
		allowed[t] = true
	}
	out := Payload{Version: 1, IssuedAt: now.Unix(), ExpiresAt: now.Add(ttl).Unix(), Servers: []ServerJSON{}}
	for _, s := range servers {
		if !allowed[s.Tier] {
			continue
		}
		status := s.Status
		if status == "ONLINE" && (s.LastSeen == nil || now.Sub(*s.LastSeen) > StaleAfter) {
			status = "OFFLINE"
		}
		cats := s.Categories
		if len(cats) == 0 {
			cats = []string{"STANDARD"}
		}
		out.Servers = append(out.Servers, ServerJSON{
			ID: s.ID, Name: s.Name, CountryCode: s.CountryCode, CountryName: s.CountryName, City: s.City,
			Hostname: s.Hostname, Port: s.Port, PublicKey: s.PublicKey, Protocols: []string{"WIREGUARD"},
			Tier: string(s.Tier), Categories: cats, LoadPercent: s.LoadPercent, Status: status,
		})
	}
	return out
}

func (s *Signer) Sign(p Payload) (Signed, error) {
	body, err := json.Marshal(p)
	if err != nil {
		return Signed{}, err
	}
	digest := sha256.Sum256(body)
	sig, err := ecdsa.SignASN1(rand.Reader, s.key, digest[:])
	if err != nil {
		return Signed{}, err
	}
	return Signed{
		Payload:   base64.StdEncoding.EncodeToString(body),
		Signature: base64.StdEncoding.EncodeToString(sig),
		KeyID:     s.keyID,
	}, nil
}

// Verify mirrors the app's check; used in tests and by `hivpn-api verify`.
func Verify(pub *ecdsa.PublicKey, signed Signed) (Payload, error) {
	body, err := base64.StdEncoding.DecodeString(signed.Payload)
	if err != nil {
		return Payload{}, err
	}
	sig, err := base64.StdEncoding.DecodeString(signed.Signature)
	if err != nil {
		return Payload{}, err
	}
	digest := sha256.Sum256(body)
	if !ecdsa.VerifyASN1(pub, digest[:], sig) {
		return Payload{}, errors.New("bad signature")
	}
	var p Payload
	return p, json.Unmarshal(body, &p)
}

func (s *Signer) Public() *ecdsa.PublicKey { return &s.key.PublicKey }
