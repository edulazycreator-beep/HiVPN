#!/usr/bin/env bash
# Turns a fresh Debian 12 / Ubuntu 24.04 VPS into a Hi VPN WireGuard node.
#
# Run as root on the node:
#   NODE_ID=sg-sin-01 NODE_API_URL=https://api.example.com:8443 ./bootstrap-node.sh
# with these files next to the script (from node-ca.sh issue):
#   node-ca.crt  <NODE_ID>.crt  <NODE_ID>.key  hivpn-nodeagent (binary)
#
# What it sets up:
#   - WireGuard wg0 on UDP 51820 (and 443/UDP redirected to it), 10.64.0.1/10 + fd64::1/64
#   - nftables: NAT for tunnel traffic, no peer-to-peer forwarding, SSH + WG + probe only
#   - Unbound on the tunnel addresses, forwarding over DNS-over-TLS, no query logging
#   - hivpn-nodeagent as a systemd service
#   - journald kept in memory only, so nothing about clients survives a reboot
set -euo pipefail

: "${NODE_ID:?set NODE_ID (must equal the certificate CN and the server id)}"
: "${NODE_API_URL:?set NODE_API_URL, e.g. https://api.example.com:8443}"
WAN_IF="${WAN_IF:-$(ip route show default | awk '{print $5; exit}')}"
HERE="$(cd "$(dirname "$0")" && pwd)"

for f in node-ca.crt "$NODE_ID.crt" "$NODE_ID.key" hivpn-nodeagent; do
  [[ -f "$HERE/$f" ]] || { echo "missing $HERE/$f"; exit 1; }
done

export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -yq wireguard-tools nftables unbound ca-certificates

# --- kernel ---
cat > /etc/sysctl.d/90-hivpn.conf <<'EOF'
net.ipv4.ip_forward = 1
net.ipv6.conf.all.forwarding = 1
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
EOF
sysctl --system >/dev/null

# --- WireGuard ---
install -d -m 700 /etc/wireguard
if [[ ! -f /etc/wireguard/wg0.key ]]; then
  (umask 077; wg genkey > /etc/wireguard/wg0.key)
fi
PUBKEY="$(wg pubkey < /etc/wireguard/wg0.key)"
cat > /etc/wireguard/wg0.conf <<EOF
[Interface]
Address = 10.64.0.1/10, fd64::1/64
ListenPort = 51820
PostUp = wg set %i private-key /etc/wireguard/wg0.key
# Peers are managed by hivpn-nodeagent; none are listed here.
EOF
chmod 600 /etc/wireguard/wg0.conf
systemctl enable --now wg-quick@wg0

# --- firewall + NAT ---
cat > /etc/nftables.conf <<EOF
#!/usr/sbin/nft -f
flush ruleset
table inet hivpn {
  chain input {
    type filter hook input priority 0; policy drop;
    ct state established,related accept
    iif lo accept
    meta l4proto { icmp, ipv6-icmp } accept
    tcp dport 22 accept
    udp dport 51820 limit rate 2000/second burst 4000 packets accept
    tcp dport 443 accept                       # latency probe (hivpn-nodeagent)
    iifname "wg0" udp dport 53 accept          # Unbound for tunnel clients
    iifname "wg0" tcp dport 53 accept
  }
  chain forward {
    type filter hook forward priority 0; policy drop;
    ct state established,related accept
    iifname "wg0" oifname "wg0" drop           # clients cannot reach each other
    iifname "wg0" oifname "$WAN_IF" accept
  }
}
table inet hivpn_nat {
  chain prerouting {
    type nat hook prerouting priority dstnat;
    iifname "$WAN_IF" udp dport 443 redirect to :51820   # alternative port for strict networks
  }
  chain postrouting {
    type nat hook postrouting priority srcnat;
    oifname "$WAN_IF" ip saddr 10.64.0.0/10 masquerade
    oifname "$WAN_IF" ip6 saddr fd64::/64 masquerade
  }
}
EOF
systemctl enable --now nftables
nft -f /etc/nftables.conf

# --- DNS resolver for tunnel clients ---
cat > /etc/unbound/unbound.conf.d/hivpn.conf <<'EOF'
server:
  interface: 10.64.0.1
  interface: fd64::1
  access-control: 10.64.0.0/10 allow
  access-control: fd64::/64 allow
  verbosity: 0
  log-queries: no
  log-replies: no
  log-servfail: no
  hide-identity: yes
  hide-version: yes
  qname-minimisation: yes
  tls-cert-bundle: /etc/ssl/certs/ca-certificates.crt
forward-zone:
  name: "."
  forward-tls-upstream: yes
  forward-addr: 9.9.9.9@853#dns.quad9.net
  forward-addr: 149.112.112.112@853#dns.quad9.net
  forward-addr: 1.1.1.1@853#cloudflare-dns.com
  forward-addr: 1.0.0.1@853#cloudflare-dns.com
EOF
systemctl restart unbound
systemctl enable unbound

# --- logs in memory only ---
mkdir -p /etc/systemd/journald.conf.d
printf "[Journal]\nStorage=volatile\nRuntimeMaxUse=32M\n" > /etc/systemd/journald.conf.d/hivpn.conf
systemctl restart systemd-journald

# --- node agent ---
install -d -m 750 /etc/hivpn
install -m 644 "$HERE/node-ca.crt" /etc/hivpn/node-ca.crt
install -m 644 "$HERE/$NODE_ID.crt" /etc/hivpn/node.crt
install -m 600 "$HERE/$NODE_ID.key" /etc/hivpn/node.key
install -m 755 "$HERE/hivpn-nodeagent" /usr/local/bin/hivpn-nodeagent
cat > /etc/systemd/system/hivpn-nodeagent.service <<EOF
[Unit]
Description=Hi VPN node agent
After=network-online.target wg-quick@wg0.service
Wants=network-online.target

[Service]
Environment=NODE_API_URL=$NODE_API_URL
Environment=NODE_CERT=/etc/hivpn/node.crt
Environment=NODE_KEY=/etc/hivpn/node.key
Environment=NODE_CA=/etc/hivpn/node-ca.crt
Environment=NODE_WG_IFACE=wg0
Environment=NODE_CAPACITY=${NODE_CAPACITY:-500}
ExecStart=/usr/local/bin/hivpn-nodeagent
Restart=always
RestartSec=5
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_BIND_SERVICE
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now hivpn-nodeagent

PUBLIC_IP="$(curl -4 -s https://ifconfig.me || echo '<public-ip>')"
cat <<EOF

Node ready. Register it on the control plane:

  docker compose exec api hivpn-api server-add \\
    -id $NODE_ID -host $PUBLIC_IP -port 51820 \\
    -pubkey $PUBKEY \\
    -country-code XX -country "Country" -city "City" -tier FREE

It turns ONLINE in the app once the agent reports (within ~10 s).
EOF
