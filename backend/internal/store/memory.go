package store

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"sort"
	"sync"
	"time"
)

// Memory is an in-process Store used by tests. It is not used in production.
type Memory struct {
	mu       sync.Mutex
	nextAcct int64
	nextHost int64
	accounts map[int64]Account
	byHash   map[string]int64
	refresh  map[string]refreshRow
	devices  map[string]Device
	servers  map[string]Server
	AuditLog []string
}

type refreshRow struct {
	account int64
	expires time.Time
}

func NewMemory() *Memory {
	return &Memory{
		accounts: map[int64]Account{},
		byHash:   map[string]int64{},
		refresh:  map[string]refreshRow{},
		devices:  map[string]Device{},
		servers:  map[string]Server{},
	}
}

func (m *Memory) CreateAccount(_ context.Context, hash string) (Account, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.byHash[hash]; ok {
		return Account{}, ErrConflict
	}
	m.nextAcct++
	a := Account{ID: m.nextAcct, NumberHash: hash, Plan: PlanFree}
	m.accounts[a.ID] = a
	m.byHash[hash] = a.ID
	return a, nil
}

func (m *Memory) AccountByNumberHash(_ context.Context, hash string) (Account, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	id, ok := m.byHash[hash]
	if !ok {
		return Account{}, ErrNotFound
	}
	return m.accounts[id], nil
}

func (m *Memory) AccountByID(_ context.Context, id int64) (Account, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	a, ok := m.accounts[id]
	if !ok {
		return Account{}, ErrNotFound
	}
	return a, nil
}

func (m *Memory) SetPlan(_ context.Context, id int64, plan Plan, expires *time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	a, ok := m.accounts[id]
	if !ok {
		return ErrNotFound
	}
	a.Plan, a.ExpiresAt = plan, expires
	m.accounts[id] = a
	return nil
}

func (m *Memory) SaveRefreshToken(_ context.Context, hash string, account int64, expires time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.refresh[hash] = refreshRow{account, expires}
	return nil
}

func (m *Memory) ConsumeRefreshToken(_ context.Context, hash string, now time.Time) (int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.refresh[hash]
	delete(m.refresh, hash)
	if !ok || now.After(r.expires) {
		return 0, ErrNotFound
	}
	return r.account, nil
}

func (m *Memory) DeleteRefreshTokens(_ context.Context, account int64) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for k, r := range m.refresh {
		if r.account == account {
			delete(m.refresh, k)
		}
	}
	return nil
}

func (m *Memory) CreateDevice(_ context.Context, account int64, key, name string, limit int) (Device, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	count := 0
	for _, d := range m.devices {
		if d.PublicKey == key {
			if d.AccountID == account {
				return d, nil
			}
			return Device{}, ErrConflict
		}
		if d.AccountID == account {
			count++
		}
	}
	if count >= limit {
		return Device{}, ErrDeviceLimit
	}
	m.nextHost++
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	d := Device{ID: hex.EncodeToString(b), AccountID: account, PublicKey: key, Name: name, HostIndex: m.nextHost}
	m.devices[d.ID] = d
	return d, nil
}

func (m *Memory) ListDevices(_ context.Context, account int64) ([]Device, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []Device
	for _, d := range m.devices {
		if d.AccountID == account {
			out = append(out, d)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].HostIndex < out[j].HostIndex })
	return out, nil
}

func (m *Memory) DeleteDevice(_ context.Context, account int64, id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	d, ok := m.devices[id]
	if !ok || d.AccountID != account {
		return ErrNotFound
	}
	delete(m.devices, id)
	return nil
}

func (m *Memory) ListServers(_ context.Context) ([]Server, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]Server, 0, len(m.servers))
	for _, s := range m.servers {
		out = append(out, s)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}

func (m *Memory) UpsertServer(_ context.Context, s Server) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.servers[s.ID] = s
	return nil
}

func (m *Memory) ReportHealth(_ context.Context, id string, load int, status string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	s, ok := m.servers[id]
	if !ok {
		return ErrNotFound
	}
	s.LoadPercent, s.Status, s.LastSeen = &load, status, &at
	m.servers[id] = s
	return nil
}

func (m *Memory) ServerByID(_ context.Context, id string) (Server, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	s, ok := m.servers[id]
	if !ok {
		return Server{}, ErrNotFound
	}
	return s, nil
}

func (m *Memory) PeersForTier(_ context.Context, tier Tier, now time.Time) ([]Peer, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	allowed := map[Plan]bool{}
	for _, p := range PlansForTier(tier) {
		allowed[p] = true
	}
	var out []Peer
	for _, d := range m.devices {
		if allowed[m.accounts[d.AccountID].EffectivePlan(now)] {
			out = append(out, Peer{d.PublicKey, d.HostIndex})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].HostIndex < out[j].HostIndex })
	return out, nil
}

func (m *Memory) Audit(_ context.Context, actor, action, target string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.AuditLog = append(m.AuditLog, actor+" "+action+" "+target)
	return nil
}
