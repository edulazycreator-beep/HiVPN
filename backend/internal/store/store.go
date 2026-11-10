// Package store defines persistence for accounts, devices and servers. It stores no
// traffic data, no client IP addresses and no connection timestamps.
package store

import (
	"context"
	"errors"
	"time"
)

var (
	ErrNotFound    = errors.New("not found")
	ErrConflict    = errors.New("conflict")
	ErrDeviceLimit = errors.New("device limit reached")
)

type Plan string

const (
	PlanFree    Plan = "free"
	PlanPremium Plan = "premium"
	PlanPro     Plan = "pro"
)

type Tier string

const (
	TierFree    Tier = "FREE"
	TierPremium Tier = "PREMIUM"
	TierPro     Tier = "PRO"
)

type Account struct {
	ID         int64
	NumberHash string
	Plan       Plan
	ExpiresAt  *time.Time // nil = no expiry (free plan)
}

// EffectivePlan is the plan in force now: an expired paid plan falls back to free.
func (a Account) EffectivePlan(now time.Time) Plan {
	if a.Plan != PlanFree && a.ExpiresAt != nil && now.After(*a.ExpiresAt) {
		return PlanFree
	}
	return a.Plan
}

func DeviceLimit(p Plan) int {
	switch p {
	case PlanPro:
		return 10
	case PlanPremium:
		return 5
	default:
		return 1
	}
}

// AllowedTiers lists the server tiers a plan may use.
func AllowedTiers(p Plan) []Tier {
	switch p {
	case PlanPro:
		return []Tier{TierFree, TierPremium, TierPro}
	case PlanPremium:
		return []Tier{TierFree, TierPremium}
	default:
		return []Tier{TierFree}
	}
}

// PlansForTier is the inverse of AllowedTiers: which plans may use a node of this tier.
func PlansForTier(t Tier) []Plan {
	switch t {
	case TierPro:
		return []Plan{PlanPro}
	case TierPremium:
		return []Plan{PlanPremium, PlanPro}
	default:
		return []Plan{PlanFree, PlanPremium, PlanPro}
	}
}

type Device struct {
	ID        string
	AccountID int64
	PublicKey string
	Name      string
	HostIndex int64 // tunnel address slot; see package ipam
}

type Server struct {
	ID          string
	Name        string
	CountryCode string
	CountryName string
	City        string
	Hostname    string
	Port        int
	PublicKey   string
	Tier        Tier
	Categories  []string
	Status      string // ONLINE, MAINTENANCE, OFFLINE
	LoadPercent *int
	Capacity    int
	LastSeen    *time.Time
}

// Peer is what a VPN node needs to accept a device.
type Peer struct {
	PublicKey string
	HostIndex int64
}

type Store interface {
	CreateAccount(ctx context.Context, numberHash string) (Account, error)
	AccountByNumberHash(ctx context.Context, hash string) (Account, error)
	AccountByID(ctx context.Context, id int64) (Account, error)
	SetPlan(ctx context.Context, id int64, plan Plan, expires *time.Time) error

	SaveRefreshToken(ctx context.Context, hash string, accountID int64, expires time.Time) error
	// ConsumeRefreshToken deletes the token and returns its account (single use).
	ConsumeRefreshToken(ctx context.Context, hash string, now time.Time) (int64, error)
	DeleteRefreshTokens(ctx context.Context, accountID int64) error

	// CreateDevice registers a key unless the account already has [limit] devices.
	// Re-registering the same key for the same account returns the existing device.
	CreateDevice(ctx context.Context, accountID int64, publicKey, name string, limit int) (Device, error)
	ListDevices(ctx context.Context, accountID int64) ([]Device, error)
	DeleteDevice(ctx context.Context, accountID int64, deviceID string) error

	ListServers(ctx context.Context) ([]Server, error)
	UpsertServer(ctx context.Context, s Server) error
	ReportHealth(ctx context.Context, serverID string, loadPercent int, status string, at time.Time) error
	ServerByID(ctx context.Context, id string) (Server, error)

	// PeersForTier returns devices of accounts whose effective plan may use [tier].
	PeersForTier(ctx context.Context, tier Tier, now time.Time) ([]Peer, error)

	Audit(ctx context.Context, actor, action, target string) error
}
