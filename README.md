# Hi VPN

**Your Connection. Your Privacy.**

Android VPN client on WireGuard with advanced network controls, plus its own control-plane backend and VPN node tooling.

## What works

| Area | State |
| --- | --- |
| WireGuard tunnel (official `GoBackend`), handshake-verified state, auto-reconnect | Implemented |
| Import your own WireGuard `.conf` (file or paste); keys go to Keystore-encrypted storage | Implemented |
| Profiles: DNS (auto, Cloudflare, Google, Quad9, custom), MTU, keepalive, LAN bypass, route exclusions, per-app split tunnel, export/import | Implemented |
| Kill switch: Android Always-on + "Block connections without VPN" (guided), Always-on start hook | Implemented |
| Dashboard: real state, live speed graph, session data, public IP, latency | Implemented |
| Diagnostics: routing/DNS/Private DNS/IPv6 checks, IP, latency, jitter, loss, speed test | Implemented |
| Statistics: per-day tunnel traffic (local only), device-wide traffic shown separately | Implemented |
| Persistent notification with Disconnect, Quick Settings tile, biometric app lock | Implemented |
| Anonymous accounts, device keys, signed server directory, server latency + recommendation | Implemented (needs your backend) |
| Backend API (Go + PostgreSQL), node agent, node bootstrap, mTLS node CA | Implemented |
| CI: lint, tests, debug APK; signed APK + AAB on tags; backend tests + image | Implemented |
| OpenVPN, IKEv2, SOCKS/HTTP proxy | **Not included** — licence/platform decision pending (see architecture doc) |
| Google Play Billing | **Not included** — endpoint returns 501 until a real subscription setup exists |

Nothing here has been run on a physical device yet. The pure logic (routing math, validation, privacy checks, statistics, directory signatures, backend API, node sync) is unit-tested; the VPN path must be verified on devices per [docs/TESTING.md](docs/TESTING.md) before release.

## Two ways to use it

1. **Your own WireGuard server, no backend.** Build with the default `gradle.properties`, install, and import a `.conf` from any WireGuard server in *Servers → My WireGuard servers*.
2. **Full Hi VPN service.** Deploy the control plane and nodes ([docs/SERVER_DEPLOYMENT.md](docs/SERVER_DEPLOYMENT.md)), then build the app with `hivpn.apiBaseUrl` and `hivpn.directoryPublicKey` set.

## Build

```bash
./gradlew :app:assembleDebug             # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest :domain:test # Android unit tests
cd backend && go test ./...              # backend tests
```

## Layout

```
app/                Application, MainActivity (lock, auto-connect), navigation
core/common         Dispatchers, app scope
core/designsystem   Theme, components, charts, formatting
core/database       Room (servers, favourites, profiles, imported configs, daily traffic)
core/datastore      Preferences
core/security       Keystore-backed SecretStore, log redaction
core/network        API client, signed-directory verification, certificate pinning
domain/             Models, repository interfaces, use cases, routing math (pure Kotlin)
data/               Repository implementations, diagnostics
vpn/api             TunnelEngine contract
vpn/wireguard       WireGuard engine, .conf import, key generation
vpn/service         TunnelManager, config resolution, device provisioning, notification, QS tile
feature/*           onboarding, dashboard, servers, profiles, diagnostics, statistics, settings
backend/            Go API, node agent, migrations
infra/              Docker Compose + nginx, node CA, node bootstrap
docs/               Everything else
```

## Documentation

[ARCHITECTURE](docs/ARCHITECTURE.md) · [VPN_ENGINE](docs/VPN_ENGINE.md) · [SECURITY](docs/SECURITY.md) · [PRIVACY](docs/PRIVACY.md) · [BACKEND](docs/BACKEND.md) · [API_DOCUMENTATION](docs/API_DOCUMENTATION.md) · [SERVER_DEPLOYMENT](docs/SERVER_DEPLOYMENT.md) · [BUILD_GUIDE](docs/BUILD_GUIDE.md) · [TESTING](docs/TESTING.md) · [TROUBLESHOOTING](docs/TROUBLESHOOTING.md) · [RELEASE](docs/RELEASE.md)
