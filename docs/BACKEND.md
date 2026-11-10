# Backend

Go 1.24, standard library plus the `pgx` PostgreSQL driver. Two binaries:

| Binary | Runs on | Does |
| --- | --- | --- |
| `hivpn-api` | Control-plane host (Docker) | Public REST API on :8080 behind nginx; node API on :8443 with mutual TLS; admin CLI |
| `hivpn-nodeagent` | Every VPN node (systemd) | Syncs WireGuard peers, reports load, serves the TCP latency probe |

```
backend/
  cmd/api/            serve, migrate, keygen, server-add, grant
  cmd/nodeagent/      node agent
  internal/auth       account numbers, token signing
  internal/config     environment settings
  internal/directory  directory building and ECDSA signing
  internal/httpapi    handlers (public + node)
  internal/ipam       host index → 10.64.0.0/10 + fd64::/64 addresses
  internal/nodeagent  wg dump parsing, peer diffing, load
  internal/ratelimit  token buckets
  internal/store      Store interface, PostgreSQL, in-memory (tests)
  migrations/         SQL, embedded into the binary
```

## Plans

| Plan | Server tiers | Devices |
| --- | --- | --- |
| free | FREE | 1 |
| premium | FREE, PREMIUM | 5 |
| pro | FREE, PREMIUM, PRO | 10 |

An expired paid plan behaves as free. Without billing, operators grant plans with `hivpn-api grant <number> premium 30`.

## Peer distribution

A device's key goes to every node whose tier its plan allows, with the same tunnel addresses everywhere. Switching servers therefore needs no API call. Each node agent pulls `GET /v1/node/peers` every 10 s and applies only the difference with `wg set`.

## Addressing

`host_index` comes from a sequence and is never reused:

- IPv4: `10.64.0.0/10`, with `10.64.0.0/24` reserved (`10.64.0.1` = node gateway/DNS). That leaves 4,194,047 device slots.
- IPv6: `fd64::/64`, `fd64::1` = gateway/DNS, device = `fd64::<256+index>`.

## Configuration (environment)

| Variable | Required | Meaning |
| --- | --- | --- |
| `HIVPN_DATABASE_URL` | yes | PostgreSQL URL |
| `HIVPN_TOKEN_SECRET` | yes | ≥ 32 bytes hex |
| `HIVPN_ACCOUNT_PEPPER` | yes | ≥ 32 bytes hex. **Never change it**: existing accounts would stop matching |
| `HIVPN_DIRECTORY_KEY` | yes | PEM path from `hivpn-api keygen` |
| `HIVPN_TRUSTED_PROXY` | no | IP of nginx; only it may set X-Forwarded-For |
| `HIVPN_NODE_CA`, `HIVPN_NODE_CERT`, `HIVPN_NODE_KEY` | for nodes | Node API mTLS |
| `HIVPN_ACCESS_TTL`, `HIVPN_REFRESH_TTL`, `HIVPN_DIRECTORY_TTL` | no | Defaults 15m, 720h, 24h |
| `HIVPN_PLAY_PACKAGE` | no | Reserved for Google Play billing (not implemented) |

## Billing

`POST /v1/billing/google/verify` returns `501 billing_not_configured`. To implement it:

1. Create subscription products in Play Console.
2. Create a service account with Android Publisher API access.
3. Verify purchase tokens server-side with `purchases.subscriptionsv2.get`.
4. Subscribe to Real-time Developer Notifications (Pub/Sub) for renewals and cancellations.
5. Map products to plans and call `SetPlan`.

There is no client purchase flow until then.

## Tests

```bash
cd backend && go test ./...
```

Unit tests cover tokens, numbers, IPAM, directory filtering/signing/tamper detection, wg dump parsing and diffing, and an end-to-end API flow on the in-memory store (accounts, refresh rotation, device limits, signed directory, node API identity, proxy IP trust, billing gate). CI also applies the migrations to a real PostgreSQL twice.
