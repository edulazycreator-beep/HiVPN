-- Hi VPN schema v1. Applied by `hivpn-api migrate`, which records it in schema_migrations.
-- Deliberately absent: client IP addresses, connection times, DNS queries, traffic data.

CREATE TABLE accounts (
    id          BIGSERIAL PRIMARY KEY,
    -- HMAC-SHA256(pepper, account number), hex. The number itself is never stored.
    number_hash TEXT NOT NULL UNIQUE,
    plan        TEXT NOT NULL DEFAULT 'free' CHECK (plan IN ('free', 'premium', 'pro')),
    expires_at  TIMESTAMPTZ,
    created_on  DATE NOT NULL DEFAULT current_date
);

CREATE TABLE refresh_tokens (
    token_hash TEXT PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX refresh_tokens_account ON refresh_tokens(account_id);

-- host_index maps to the device's tunnel addresses (see internal/ipam). Indexes are
-- never reused, so a deleted device's address is never handed to someone else.
CREATE SEQUENCE device_host_index START 1;

CREATE TABLE devices (
    id          TEXT PRIMARY KEY,
    account_id  BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    public_key  TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL DEFAULT '',
    host_index  BIGINT NOT NULL UNIQUE DEFAULT nextval('device_host_index'),
    CHECK (host_index BETWEEN 1 AND 4194047)
);
CREATE INDEX devices_account ON devices(account_id);

CREATE TABLE servers (
    id           TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    country_code TEXT NOT NULL,
    country_name TEXT NOT NULL,
    city         TEXT NOT NULL,
    hostname     TEXT NOT NULL,
    port         INTEGER NOT NULL DEFAULT 51820,
    public_key   TEXT NOT NULL,
    tier         TEXT NOT NULL DEFAULT 'FREE' CHECK (tier IN ('FREE', 'PREMIUM', 'PRO')),
    categories   TEXT NOT NULL DEFAULT 'STANDARD',
    status       TEXT NOT NULL DEFAULT 'OFFLINE' CHECK (status IN ('ONLINE', 'MAINTENANCE', 'OFFLINE')),
    load_percent INTEGER,
    capacity     INTEGER NOT NULL DEFAULT 500,
    last_seen    TIMESTAMPTZ
);

CREATE TABLE admin_audit (
    id     BIGSERIAL PRIMARY KEY,
    at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor  TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT NOT NULL
);
