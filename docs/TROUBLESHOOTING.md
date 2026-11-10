# Troubleshooting

## App

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| "The server did not respond" | Wrong peer key or endpoint, UDP blocked, server down | Check the `.conf`; try port 443 (nodes redirect 443/UDP); on the server run `wg show` and look for a handshake |
| Connects, but some sites stall | MTU too high on this network | Profile → MTU 1380, then 1280 |
| "Another VPN app took over" | Another VPN started, or VPN access was revoked | Close the other VPN; check Settings → Network → VPN |
| Disconnects with the screen off | OEM battery optimisation | Exempt Hi VPN from battery optimisation; enable Always-on VPN |
| No notification | Android 13+ permission declined | Allow notifications for Hi VPN in system settings |
| "Sign in or create an account" | Directory server chosen without an account | Settings → Account, or use an imported config |
| Server list empty | No API configured, signed out, or the directory failed verification | Check `hivpn.apiBaseUrl` / `hivpn.directoryPublicKey` match the backend; Servers → Refresh |
| DNS not the one set | Private DNS is on | Set Private DNS to Off/Automatic (Diagnostics warns about this) |
| Imported config: "only one [Peer]" | Multi-peer configs are not supported | Split into one config per peer |

## Backend

| Symptom | Check |
| --- | --- |
| API won't start: missing settings | All required variables in BACKEND.md are set |
| Node stays OFFLINE | `journalctl -u hivpn-nodeagent`; certificate CN = server id; port 8443 reachable; `server-add` was run |
| `403 unknown_node` | CN in the node certificate does not match any `servers.id` |
| Devices can't connect to a node | `wg show wg0` lists the peer; `nft list ruleset` has masquerade; `sysctl net.ipv4.ip_forward` = 1 |
| DNS fails in the tunnel | `systemctl status unbound`; Unbound listens on 10.64.0.1 (wg0 must be up first) |
| Every sign-in fails after a redeploy | `HIVPN_ACCOUNT_PEPPER` changed. Restore the original value |
