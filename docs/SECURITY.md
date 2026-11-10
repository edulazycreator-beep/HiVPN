# Security

## Rules for every change

1. **No secrets in git.** No keystores, keys, tokens, WireGuard configs, `.env` or PKI files.
2. **WireGuard private keys never leave the device.** The backend receives public keys only.
3. **No simulated state.** `Connected` requires a completed handshake. No screen may show a speed, IP or status it did not measure.
4. **No invented servers.** Servers come only from the signed directory. Test fixtures use `*.test.invalid`.
5. **Every permission has a reason in the table below.**
6. **Logs are redacted** through `HiLog` / `LogRedactor` (keys, tokens, IPs, hostnames). Debug logs are off in release builds.
7. **No cloud backup** (`allowBackup=false`, `data_extraction_rules.xml` excludes everything).

## Android permissions

| Permission | Declared in | Reason |
| --- | --- | --- |
| `INTERNET` | vpn:service | Tunnel traffic and API calls |
| `ACCESS_NETWORK_STATE` | vpn:service | Diagnostics and network snapshot |
| `POST_NOTIFICATIONS` | vpn:service | Ongoing tunnel notification (Android 13+, runtime; declining hides the notification only) |
| `BIND_VPN_SERVICE` (service permission) | WireGuard AAR | Lets only the system bind the VPN service |
| `BIND_QUICK_SETTINGS_TILE` (service permission) | vpn:service | Lets only the system bind the tile |
| `<queries>` launcher intent | feature:profiles | App list for split tunneling. Used instead of `QUERY_ALL_PACKAGES` |

Not requested: location, contacts, storage, phone state, `QUERY_ALL_PACKAGES`.

## Key and secret storage (app)

`KeystoreSecretStore`: AES-256-GCM under a non-exportable Android Keystore key (hardware-backed where available). The alias is bound as AAD, values are base64(iv ‖ ciphertext) in private prefs, and secrets are excluded from backup.

## Transport (app)

- HTTPS only. `isConfigured` requires an `https://` base URL.
- Optional certificate pinning (`hivpn.apiPins`). It is enabled only when **at least two** pins are given (current + backup), so a key rotation can never lock installed apps out.
- The server directory is accepted only with a valid ECDSA P-256 signature from the key compiled into the app, and only before its `expiresAt`.

## Backend

| Control | Implementation |
| --- | --- |
| Account numbers | 16 random digits; stored as HMAC-SHA256(pepper) |
| Access tokens | HMAC-signed, 15 min |
| Refresh tokens | Random 256-bit, stored as SHA-256, single use |
| Rate limits | Per client (hashed key, in memory): account creation 5/h, sign-in 10/min, general 120/min; speed test 2/min per account |
| Input | JSON with unknown fields rejected; 16 KB body limit (50 MB for speed upload); WireGuard keys validated as 32-byte base64 |
| Node API | Separate listener; TLS 1.3 mutual auth; node identity = certificate CN = server id |
| Admin | CLI only (`server-add`, `grant`), every action written to `admin_audit` |
| Runtime | Distroless, non-root container |

## Checklist before each release

- [ ] `./gradlew lintDebug testDebugUnitTest` and `go test ./...` green
- [ ] Device tests in TESTING.md passed on the device matrix
- [ ] Leak tests (DNS, IPv6, WebRTC guidance) done on a real network
- [ ] No new permission without a row above
- [ ] Release APK decompiled: no keys, no test servers, no debug logging
- [ ] `hivpn.apiPins` has ≥ 2 pins, and the backup key is stored offline
