# Testing

## Automated

| Suite | Command | Covers |
| --- | --- | --- |
| Domain | `./gradlew :domain:test` | IP validation, DNS validation, CIDR math and route planning (incl. proof that LAN bypass covers the address space exactly), target ids, traffic rates |
| Android unit | `./gradlew testDebugUnitTest` | profile mappers, profile form validation, profile export/import, privacy checks, statistics aggregation, server search and recommendation, endpoint parsing, directory signature (fixture signed by the Go backend) |
| Backend | `cd backend && go test ./...` | tokens, IPAM, directory, node agent diffing, end-to-end API flow |
| Migrations | backend CI | apply twice on PostgreSQL 16 |

A feature is not done because its screen works. Everything touching the tunnel is verified on devices.

## Device matrix

At least one device or emulator each on API 26, 30, 33 and the current release. One physical phone on mobile data is required for the network-switch tests.

## Manual test plan

Use your own WireGuard server (imported `.conf`) and, if deployed, a Hi VPN node.

### Tunnel
- [ ] First connect shows Android's consent dialog; declining shows "permission not granted" and nothing connects
- [ ] Connect with a wrong peer key: about 20 s later shows "server did not respond", and no tunnel remains (`adb shell ip addr` shows no `tun`)
- [ ] Connect normally: Connected only after the handshake; the notification shows a timer; the tile is active
- [ ] Wi-Fi → mobile → Wi-Fi: stays Connected or recovers via Reconnecting within 3 min
- [ ] Airplane mode 5 min, then off: reconnects or fails clearly; never shows Connected without traffic
- [ ] Start another VPN app: Hi VPN shows "another VPN took over"
- [ ] Notification Disconnect and tile toggle both work with the app closed

### Leaks (on a network with IPv6)
- [ ] Browser leak-test site while connected: only the VPN IP, IPv4 and IPv6
- [ ] DNS leak-test site: only the VPN resolver (or the profile's custom DNS)
- [ ] Server without IPv6: IPv6 test sites fail to load (blackholed), not show the real IPv6
- [ ] Private DNS on: Diagnostics shows the warning
- [ ] Always-on + Block connections without VPN: during a tunnel restart, `curl` from Termux fails rather than going direct

### Profiles
- [ ] Custom DNS applied: Diagnostics shows those servers on the VPN network
- [ ] LAN access on: printer or router admin page reachable; off: unreachable
- [ ] Excluded route reachable directly (traceroute shows no tunnel hop)
- [ ] "Only selected apps": an unselected app shows the real IP; "Bypass": a selected app shows the real IP
- [ ] MTU 1280 applied (Diagnostics MTU on the VPN network)

### Accounts (with backend)
- [ ] Create account, number shown once; sign out and sign in again with it
- [ ] Device limit: a second phone on a free plan gets the device-limit message
- [ ] New device key: the old key disappears from the node (`wg show`), and the new one connects
- [ ] Tampered directory (edit the API response through a proxy) is rejected

### Battery and performance
- [ ] 1 hour connected with the screen off: battery use attributed to Hi VPN is comparable to the official WireGuard app on the same server
- [ ] Throughput within 10% of the official WireGuard app on the same server and profile
- [ ] No ANR or crash in 24 h of always-on use

### Accessibility
- [ ] TalkBack announces the connect button's state; every control has a label
- [ ] Largest font size: no clipped text on the dashboard and settings
