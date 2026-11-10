# Server deployment

The minimum real setup is two Linux VPS: one control plane, plus one VPN node per location.

| Host | Size | OS |
| --- | --- | --- |
| Control plane | 2 vCPU, 4 GB | Any with Docker |
| VPN node | 1–2 vCPU, 1–2 GB, high-transfer bandwidth | Debian 12 or Ubuntu 24.04 |

You need a domain for the API (e.g. `api.example.com`) pointing to the control plane.

## 1. Control plane

```bash
git clone <repo> hi-vpn && cd hi-vpn/infra/docker
cp .env.example .env
for k in POSTGRES_PASSWORD HIVPN_TOKEN_SECRET HIVPN_ACCOUNT_PEPPER; do
  sed -i "s/^$k=.*/$k=$(openssl rand -hex 32)/" .env
done
chmod 600 .env
mkdir -p secrets certbot-www
```

Back up `.env` offline. **Losing `HIVPN_ACCOUNT_PEPPER` locks every account out.**

**Directory signing key.** Its public half is compiled into the app.

```bash
# Needs Go 1.24 on this machine (or run it anywhere and copy the file over).
(cd ../../backend && go mod tidy && go run ./cmd/api keygen ../infra/docker/secrets/directory.pem)
# prints: hivpn.directoryPublicKey=MFkwEw...  ← keep this for the app build
```

**Node CA and the API's node certificate**, then **TLS for the public API**:

```bash
../scripts/node-ca.sh init api.example.com
cp ../scripts/pki/{node-ca.crt,api-node.crt,api-node.key} secrets/
# Move ../scripts/pki/ca.key offline once node certificates are issued.

# Replace api.example.com in nginx.conf, then:
sudo certbot certonly --standalone -d api.example.com   # before nginx starts
```

**Start, migrate and check.** The API container runs as the distroless `nonroot` user (uid 65532), so it must own the secrets:

```bash
sudo chown -R 65532:65532 secrets && sudo chmod 600 secrets/*
docker compose up -d --build
docker compose exec api hivpn-api migrate
curl -i https://api.example.com/healthz     # 204
```

Firewall: open 80/tcp, 443/tcp, and 8443/tcp (node API; restrict it to node IPs if you can).

## 2. Each VPN node

On the control plane, issue a certificate. The id becomes the server id, e.g. `sg-sin-01`:

```bash
cd infra/scripts && ./node-ca.sh issue sg-sin-01
```

Build the agent (or take it from a release artifact):

```bash
cd backend && go mod tidy && CGO_ENABLED=0 GOOS=linux go build -o hivpn-nodeagent ./cmd/nodeagent
```

Copy to the node: `bootstrap-node.sh`, `pki/node-ca.crt`, `pki/sg-sin-01.crt`, `pki/sg-sin-01.key`, `hivpn-nodeagent`. Then on the node:

```bash
sudo NODE_ID=sg-sin-01 NODE_API_URL=https://api.example.com:8443 ./bootstrap-node.sh
```

The script prints a `server-add` command. Fill in the country and city, then run it on the control plane:

```bash
docker compose exec api hivpn-api server-add -id sg-sin-01 -host 203.0.113.10 -port 51820 \
  -pubkey <printed key> -country-code SG -country Singapore -city Singapore -tier FREE
```

The node turns ONLINE once its agent reports health (about 10 seconds).

## 3. Build the app against it

```properties
# gradle.properties, or -P flags / CI repository variables
hivpn.apiBaseUrl=https://api.example.com
hivpn.directoryPublicKey=MFkwEw...
hivpn.apiPins=sha256/<current>,sha256/<backup>   # optional; needs both
```

Get a pin for the current key:

```bash
openssl s_client -connect api.example.com:443 -servername api.example.com </dev/null 2>/dev/null \
 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der \
 | openssl dgst -sha256 -binary | base64
```

The backup pin must be for a key you hold offline and will switch to.

## Operations

| Task | Command |
| --- | --- |
| Give an account premium for 30 days | `docker compose exec api hivpn-api grant <number> premium 30` |
| Put a node in maintenance | set `NODE_STATUS=MAINTENANCE` in its systemd unit, restart the agent |
| Remove a node | stop the agent; it shows OFFLINE after 2 min. Delete its row to hide it |
| Back up | `pg_dump` daily, encrypted, off-host. Nodes hold no state beyond their key |
| Rotate the directory key | run keygen, ship an app update with the new public key, then switch the API to the new key |
