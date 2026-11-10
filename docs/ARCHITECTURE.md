# Architecture

## Dependency rules

```
feature:*      ──► domain, core:designsystem, core:common
app            ──► everything (composition root)
vpn:service    ──► vpn:wireguard, vpn:api, core:network, core:security, core:database, domain
vpn:wireguard  ──► vpn:api, core:security, core:database
data           ──► domain, core:database, core:datastore, core:network
core:network   ──► core:security
domain         ──► nothing Android
```

Features only see domain interfaces. Each interface has exactly one implementation, bound with Hilt:

| Interface (domain) | Implementation |
| --- | --- |
| `VpnConnectionRepository` | `vpn:service` `TunnelManager` |
| `ImportedConfigRepository` | `vpn:wireguard` `WgImportedConfigRepository` |
| `AccountRepository` | `vpn:service` `AccountRepositoryImpl` |
| `ServerRepository`, `ProfileRepository`, `SettingsRepository`, `TrafficHistoryRepository`, `DiagnosticsRepository` | `data` |

## Connect path

```
Dashboard ─ VpnService.prepare() consent ─► ConnectUseCase
  └► TunnelManager.connect(profile, target)
       └► TunnelConfigResolver
            ├ Imported target: non-secret fields from Room, key alias in SecretStore
            └ Directory target: server from cache + DeviceProvisioner (key + addresses)
            then Routes.plan(): ::/0 blackhole, LAN bypass, exclusions
       └► WireGuardEngine.connect(TunnelConfig)
            reads keys from SecretStore → GoBackend.setState(UP)
            Connected only after the first handshake
```

`TunnelState` is published only by the engine, mirrored by `TunnelManager`. The dashboard, notification and Quick Settings tile all read the same `StateFlow`.

## Targets

`TargetId` is either `Directory(serverId)` or `Imported(configId)`, stored as `dir:<id>` / `imp:<id>`. Imported configurations work without any backend.

## Storage

| Store | Holds |
| --- | --- |
| Room v2 | servers cache, favourites, profiles, imported configs (non-secret), daily traffic |
| DataStore | theme, onboarding, selected target, active profile, auto-connect, app lock |
| SecretStore | WireGuard private keys, preshared keys, account number, refresh token, device id/addresses |

Room migrations: `MIGRATION_1_2` adds imported configs, daily traffic, and two profile columns. Schemas export to `core/database/schemas/` on build; commit them.

## Backend

See [BACKEND.md](BACKEND.md). Control plane (API + PostgreSQL) is separate from VPN nodes; nodes pull peers over mutual TLS and never receive account data.

## Decisions changed from the Phase 1 document

| Phase 1 said | Implemented | Why |
| --- | --- | --- |
| Ed25519 directory signature | ECDSA P-256 / SHA-256 | Available in Android's standard provider on API 26+; Ed25519 is not |
| argon2id for account numbers | HMAC-SHA256 with a server pepper | Accounts must be found by number; a salted slow hash cannot be looked up. A keyed hash keeps leaked rows useless without the pepper |
| Redis for rate limits | In-memory limiter | One API instance at launch; swap in a shared store when scaling out |
| Fork GoBackend for soft kill switch | Not forked | Kill switch relies on Android lockdown; reconnects restart the tunnel (brief gap closed only by lockdown) |
