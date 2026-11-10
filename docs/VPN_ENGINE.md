# VPN engine

Hi VPN uses the official WireGuard Android tunnel library (`com.wireguard.android:tunnel`, `GoBackend` with wireguard-go). Its AAR declares `GoBackend$VpnService` with `BIND_VPN_SERVICE`, so the app declares no VpnService of its own.

## State machine

| From | Event | To |
| --- | --- | --- |
| Disconnected | connect | Connecting |
| Connecting | first handshake within 20 s | Connected |
| Connecting | no handshake in 20 s | Failed(HandshakeTimeout), tunnel torn down |
| Connecting | consent missing (`VPN_NOT_AUTHORIZED`) | Failed(PermissionDenied) |
| Connected | latest handshake older than 180 s | Reconnecting(n), tunnel restarted |
| Reconnecting | fresh handshake | Connected |
| Reconnecting | 3 failed restarts | Failed(HandshakeTimeout) |
| any | tunnel goes DOWN without a user action (revoked, other VPN) | Failed(RevokedBySystem) |
| any | disconnect | Disconnecting → Disconnected |

WireGuard re-keys every 2 minutes on an active path, so a handshake older than 3 minutes means the path is dead. A restart also re-resolves the endpoint hostname.

## Building the tunnel config

`TunnelConfigResolver` combines target and profile:

| Field | Imported config | Directory server |
| --- | --- | --- |
| Endpoint, peer key | from the `.conf` | from the signed directory |
| Private key | `imported.<id>.private` | `device.wg.private` (generated on device) |
| Addresses | from the `.conf` | assigned by the API at registration |
| DNS | profile custom, else `.conf` DNS | profile custom, else node resolver 10.64.0.1 / fd64::1 |
| MTU | profile, else `.conf`, else 1420 | profile, else 1420 |
| Keepalive | profile, else `.conf` | profile, else 25 s |
| AllowedIPs | `Routes.plan(.conf AllowedIPs)` | `Routes.plan(0.0.0.0/0, ::/0)` |

`Routes.plan` (domain, unit-tested):

- adds `::/0` whenever `0.0.0.0/0` is routed, so IPv6 is blackholed instead of leaking when the server has no IPv6;
- subtracts LAN ranges (RFC 1918, link-local, ULA) when LAN access is on;
- subtracts user exclusions. It computes the complementary CIDR set itself, so exclusions work on every Android version, not only API 33+ with `excludeRoute()`.

## Split tunneling

`VpnService.Builder` takes either an allowlist (`addAllowedApplication`) or a denylist (`addDisallowedApplication`) per tunnel, never both. The profile stores one mode, and the engine maps it to `Interface.Builder.includeApplications` / `excludeApplications`.

## Always-on

`AlwaysOnHook` registers `GoBackend.setAlwaysOnCallback`. When Android starts the VPN service for Always-on (at boot, or after the process is killed), the hook connects to the selected target with the active profile.

## Limitations (stated in the UI)

- **Kill switch.** Only Android's lockdown mode blocks traffic while the tunnel restarts. The app links to it and never claims more.
- **Private DNS.** When Private DNS (DoT) is on, the Private DNS provider answers queries. They still travel inside the tunnel. Diagnostics warns about this.
- **WebRTC.** Leaks happen inside browsers; the app can only explain them.
- **Directory server latency.** Measured as TCP connect time to the node's probe port 443, because WireGuard (UDP) does not answer TCP.
