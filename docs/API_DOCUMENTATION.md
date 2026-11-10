# API v1

Base URL: `https://<api-host>/`. JSON bodies; unknown fields are rejected. Errors return `{"error": "<code>", "message"?: "…"}`.

Authenticated endpoints need `Authorization: Bearer <accessToken>`.

## Accounts and tokens

### `POST /v1/accounts` → 201
Creates an anonymous account. Rate limited to 5 per hour per client.
```json
{"accountNumber": "4821093375160248"}
```
The number is shown to the user once and stored by the server only as a keyed hash.

### `POST /v1/auth/token` → 200
```json
// request
{"accountNumber": "4821 0933 7516 0248"}
// response
{"accessToken": "…", "refreshToken": "…", "expiresIn": 900}
```
`401 unauthorized` for unknown numbers.

### `POST /v1/auth/refresh` → 200
`{"refreshToken": "…"}` → a new token pair. Refresh tokens are single use; reuse returns 401.

### `GET /v1/me` → 200 (auth)
```json
{"plan": "premium", "expiresAt": 1797000000, "deviceLimit": 5, "devicesInUse": 2}
```

## Servers

### `GET /v1/servers` → 200 (auth)
```json
{"payload": "<base64 JSON>", "signature": "<base64 DER>", "keyId": "…"}
```
`signature` is ECDSA P-256 over SHA-256 of the decoded payload bytes. Payload:
```json
{
  "version": 1, "issuedAt": 1794222019, "expiresAt": 1794308419,
  "servers": [{
    "id": "sg-sin-01", "name": "Singapore 01", "countryCode": "SG", "countryName": "Singapore",
    "city": "Singapore", "hostname": "203.0.113.10", "port": 51820,
    "publicKey": "<wireguard key>", "protocols": ["WIREGUARD"], "tier": "FREE",
    "categories": ["STANDARD"], "loadPercent": 37, "status": "ONLINE"
  }]
}
```
Only tiers the account's plan allows are listed. Nodes silent for more than 2 minutes are `OFFLINE`.

## Devices

### `POST /v1/devices` → 201 (auth)
```json
// request
{"publicKey": "<base64 32 bytes>", "name": "Pixel 9"}
// response
{"id": "9f2c…", "name": "Pixel 9", "ipv4": "10.64.1.1/32", "ipv6": "fd64::101/128"}
```
Re-registering the same key is idempotent. `409 device_limit` when the plan's limit is reached; `400 key_in_use` if another account holds the key.

### `GET /v1/devices` → 200 (auth)
Array of devices.

### `DELETE /v1/devices/{id}` → 204 (auth)

## Diagnostics

| Endpoint | Auth | Result |
| --- | --- | --- |
| `GET /v1/ip` | no | `{"ip": "…"}` — the address the API sees |
| `GET /v1/speedtest/download?bytes=N` | yes | N bytes (≤ 50 MB, default 25 MB); 2/min per account |
| `POST /v1/speedtest/upload` | yes | Discards up to 50 MB; `{"bytes": N}` |

## Billing

`POST /v1/billing/google/verify` → `501 billing_not_configured` (see BACKEND.md).

## Node API (port 8443, mutual TLS)

The client certificate's CN must equal a server id.

| Endpoint | Body / result |
| --- | --- |
| `GET /v1/node/peers` | `{"peers": [{"publicKey": "…", "allowedIps": ["10.64.1.1/32", "fd64::101/128"]}]}` |
| `POST /v1/node/health` | `{"loadPercent": 0-100, "status": "ONLINE"|"MAINTENANCE"}` → 204 |

Unknown CN → `403 unknown_node`.
