# Privacy and data handling

This describes what the software does. It is not a "no-logs" certification: such a claim should be published only after an independent audit of the deployed infrastructure.

## On the device

| Data | Where | Leaves the device? |
| --- | --- | --- |
| WireGuard private keys, preshared keys | Keystore-encrypted storage | Never |
| Account number, refresh token | Keystore-encrypted storage | Sent to the API only to sign in / refresh |
| Profiles, preferences, favourites | Room / DataStore | Profile export only, by the user, without secrets |
| Imported config metadata (endpoint, addresses, DNS) | Room | No |
| Daily tunnel traffic totals | Room | No |
| Cached server list | Room | No |

No analytics, crash reporting or advertising SDKs are included.

## On the Hi VPN backend

| Stored | Purpose |
| --- | --- |
| HMAC of the account number | Sign-in |
| Plan and expiry | Which servers and how many devices |
| Device public key, name, address slot | Let nodes accept the device |
| Refresh token hashes | Sessions (expire after 30 days) |
| Server list and node load | Directory |
| Admin action log | Accountability for operators |

**Not stored:** client IP addresses, connection or session times, DNS queries, visited sites, traffic content, traffic volume per user, email or name.

How this is enforced in the shipped configuration:

- **API:** request logs record method, route pattern, status and duration only. Client IPs are used in memory for rate limiting and `GET /v1/ip`, hashed before they enter the limiter, and never written.
- **nginx:** `access_log off`.
- **VPN nodes:** journald is memory-only, Unbound query logging is off, and the agent never reports peer endpoints.
- **WireGuard:** the kernel holds each peer's last endpoint in memory while it is active. That is how WireGuard works, and it is not persisted.

## Third parties

- **DNS:** by default, node resolvers forward over DNS-over-TLS to Quad9 and Cloudflare. Users can choose other resolvers per profile.
- **Hosting providers** see encrypted WireGuard traffic and node metadata. Use providers and jurisdictions you trust.
