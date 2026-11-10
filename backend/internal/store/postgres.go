package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"strings"
	"time"
)

// Postgres implements Store on database/sql. The driver is registered in
// postgres_driver.go (pgx), kept separate so the rest builds with the standard library.
type Postgres struct{ db *sql.DB }

func OpenPostgres(url string) (*Postgres, error) {
	db, err := sql.Open(driverName, url)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(20)
	db.SetConnMaxIdleTime(5 * time.Minute)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		return nil, err
	}
	return &Postgres{db}, nil
}

func (p *Postgres) DB() *sql.DB { return p.db }

func notFound(err error) error {
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	return err
}

func isUnique(err error) bool {
	return err != nil && strings.Contains(err.Error(), "SQLSTATE 23505")
}

func (p *Postgres) CreateAccount(ctx context.Context, hash string) (Account, error) {
	a := Account{NumberHash: hash, Plan: PlanFree}
	err := p.db.QueryRowContext(ctx,
		`INSERT INTO accounts(number_hash, plan) VALUES ($1, 'free') RETURNING id`, hash).Scan(&a.ID)
	if isUnique(err) {
		return Account{}, ErrConflict
	}
	return a, err
}

func (p *Postgres) scanAccount(row *sql.Row) (Account, error) {
	var a Account
	var plan string
	var exp sql.NullTime
	if err := row.Scan(&a.ID, &a.NumberHash, &plan, &exp); err != nil {
		return Account{}, notFound(err)
	}
	a.Plan = Plan(plan)
	if exp.Valid {
		t := exp.Time
		a.ExpiresAt = &t
	}
	return a, nil
}

func (p *Postgres) AccountByNumberHash(ctx context.Context, hash string) (Account, error) {
	return p.scanAccount(p.db.QueryRowContext(ctx,
		`SELECT id, number_hash, plan, expires_at FROM accounts WHERE number_hash = $1`, hash))
}

func (p *Postgres) AccountByID(ctx context.Context, id int64) (Account, error) {
	return p.scanAccount(p.db.QueryRowContext(ctx,
		`SELECT id, number_hash, plan, expires_at FROM accounts WHERE id = $1`, id))
}

func (p *Postgres) SetPlan(ctx context.Context, id int64, plan Plan, expires *time.Time) error {
	res, err := p.db.ExecContext(ctx, `UPDATE accounts SET plan = $2, expires_at = $3 WHERE id = $1`, id, string(plan), expires)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *Postgres) SaveRefreshToken(ctx context.Context, hash string, account int64, expires time.Time) error {
	_, err := p.db.ExecContext(ctx,
		`INSERT INTO refresh_tokens(token_hash, account_id, expires_at) VALUES ($1, $2, $3)`, hash, account, expires)
	return err
}

func (p *Postgres) ConsumeRefreshToken(ctx context.Context, hash string, now time.Time) (int64, error) {
	var account int64
	var exp time.Time
	err := p.db.QueryRowContext(ctx,
		`DELETE FROM refresh_tokens WHERE token_hash = $1 RETURNING account_id, expires_at`, hash).Scan(&account, &exp)
	if err != nil {
		return 0, notFound(err)
	}
	if now.After(exp) {
		return 0, ErrNotFound
	}
	return account, nil
}

func (p *Postgres) DeleteRefreshTokens(ctx context.Context, account int64) error {
	_, err := p.db.ExecContext(ctx, `DELETE FROM refresh_tokens WHERE account_id = $1`, account)
	return err
}

func (p *Postgres) CreateDevice(ctx context.Context, account int64, key, name string, limit int) (Device, error) {
	tx, err := p.db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelSerializable})
	if err != nil {
		return Device{}, err
	}
	defer func() { _ = tx.Rollback() }()

	var d Device
	err = tx.QueryRowContext(ctx,
		`SELECT id, account_id, public_key, name, host_index FROM devices WHERE public_key = $1`, key).
		Scan(&d.ID, &d.AccountID, &d.PublicKey, &d.Name, &d.HostIndex)
	switch {
	case err == nil && d.AccountID == account:
		return d, tx.Commit()
	case err == nil:
		return Device{}, ErrConflict
	case !errors.Is(err, sql.ErrNoRows):
		return Device{}, err
	}

	var count int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM devices WHERE account_id = $1`, account).Scan(&count); err != nil {
		return Device{}, err
	}
	if count >= limit {
		return Device{}, ErrDeviceLimit
	}
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	d = Device{ID: hex.EncodeToString(b), AccountID: account, PublicKey: key, Name: name}
	err = tx.QueryRowContext(ctx,
		`INSERT INTO devices(id, account_id, public_key, name) VALUES ($1, $2, $3, $4) RETURNING host_index`,
		d.ID, account, key, name).Scan(&d.HostIndex)
	if isUnique(err) {
		return Device{}, ErrConflict
	}
	if err != nil {
		return Device{}, err
	}
	return d, tx.Commit()
}

func (p *Postgres) ListDevices(ctx context.Context, account int64) ([]Device, error) {
	rows, err := p.db.QueryContext(ctx,
		`SELECT id, account_id, public_key, name, host_index FROM devices WHERE account_id = $1 ORDER BY host_index`, account)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		var d Device
		if err := rows.Scan(&d.ID, &d.AccountID, &d.PublicKey, &d.Name, &d.HostIndex); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func (p *Postgres) DeleteDevice(ctx context.Context, account int64, id string) error {
	res, err := p.db.ExecContext(ctx, `DELETE FROM devices WHERE id = $1 AND account_id = $2`, id, account)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

const serverCols = `id, name, country_code, country_name, city, hostname, port, public_key, tier, categories, status, load_percent, capacity, last_seen`

func scanServer(scan func(...any) error) (Server, error) {
	var s Server
	var tier, cats string
	var load sql.NullInt64
	var seen sql.NullTime
	if err := scan(&s.ID, &s.Name, &s.CountryCode, &s.CountryName, &s.City, &s.Hostname, &s.Port,
		&s.PublicKey, &tier, &cats, &s.Status, &load, &s.Capacity, &seen); err != nil {
		return Server{}, err
	}
	s.Tier = Tier(tier)
	if cats != "" {
		s.Categories = strings.Split(cats, ",")
	}
	if load.Valid {
		v := int(load.Int64)
		s.LoadPercent = &v
	}
	if seen.Valid {
		t := seen.Time
		s.LastSeen = &t
	}
	return s, nil
}

func (p *Postgres) ListServers(ctx context.Context) ([]Server, error) {
	rows, err := p.db.QueryContext(ctx, `SELECT `+serverCols+` FROM servers ORDER BY country_name, city, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Server
	for rows.Next() {
		s, err := scanServer(rows.Scan)
		if err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (p *Postgres) ServerByID(ctx context.Context, id string) (Server, error) {
	s, err := scanServer(p.db.QueryRowContext(ctx, `SELECT `+serverCols+` FROM servers WHERE id = $1`, id).Scan)
	return s, notFound(err)
}

func (p *Postgres) UpsertServer(ctx context.Context, s Server) error {
	_, err := p.db.ExecContext(ctx, `
		INSERT INTO servers(id, name, country_code, country_name, city, hostname, port, public_key, tier, categories, status, capacity)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
		ON CONFLICT (id) DO UPDATE SET name=$2, country_code=$3, country_name=$4, city=$5, hostname=$6, port=$7,
			public_key=$8, tier=$9, categories=$10, status=$11, capacity=$12`,
		s.ID, s.Name, s.CountryCode, s.CountryName, s.City, s.Hostname, s.Port, s.PublicKey,
		string(s.Tier), strings.Join(s.Categories, ","), s.Status, s.Capacity)
	return err
}

func (p *Postgres) ReportHealth(ctx context.Context, id string, load int, status string, at time.Time) error {
	res, err := p.db.ExecContext(ctx,
		`UPDATE servers SET load_percent = $2, status = $3, last_seen = $4 WHERE id = $1`, id, load, status, at)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *Postgres) PeersForTier(ctx context.Context, tier Tier, now time.Time) ([]Peer, error) {
	plans := PlansForTier(tier)
	names := make([]string, len(plans))
	for i, pl := range plans {
		names[i] = string(pl)
	}
	// Effective plan: an expired paid plan counts as free.
	rows, err := p.db.QueryContext(ctx, `
		SELECT d.public_key, d.host_index FROM devices d JOIN accounts a ON a.id = d.account_id
		WHERE (CASE WHEN a.plan <> 'free' AND a.expires_at IS NOT NULL AND a.expires_at < $1 THEN 'free' ELSE a.plan END)
		      = ANY(string_to_array($2, ','))
		ORDER BY d.host_index`, now, strings.Join(names, ","))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Peer
	for rows.Next() {
		var pr Peer
		if err := rows.Scan(&pr.PublicKey, &pr.HostIndex); err != nil {
			return nil, err
		}
		out = append(out, pr)
	}
	return out, rows.Err()
}

func (p *Postgres) Audit(ctx context.Context, actor, action, target string) error {
	_, err := p.db.ExecContext(ctx, `INSERT INTO admin_audit(actor, action, target) VALUES ($1, $2, $3)`, actor, action, target)
	return err
}
