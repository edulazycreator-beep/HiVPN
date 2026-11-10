#!/usr/bin/env bash
# Private CA for the node API (mutual TLS between VPN nodes and the control plane).
#
#   ./node-ca.sh init <api-hostname>   create the CA and the API's server certificate
#   ./node-ca.sh issue <node-id>       certificate for a node; CN must equal its server id
#
# Output goes to ./pki. Keep pki/ca.key offline after issuing certificates.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p pki
umask 077

case "${1:-}" in
  init)
    host="${2:?api hostname required}"
    [[ -f pki/ca.key ]] && { echo "pki/ca.key already exists"; exit 1; }
    openssl ecparam -genkey -name prime256v1 -noout -out pki/ca.key
    openssl req -x509 -new -key pki/ca.key -sha256 -days 3650 -subj "/CN=Hi VPN node CA" -out pki/node-ca.crt
    openssl ecparam -genkey -name prime256v1 -noout -out pki/api-node.key
    openssl req -new -key pki/api-node.key -subj "/CN=$host" -out pki/api-node.csr
    printf "subjectAltName=DNS:%s\nextendedKeyUsage=serverAuth\n" "$host" > pki/api.ext
    openssl x509 -req -in pki/api-node.csr -CA pki/node-ca.crt -CAkey pki/ca.key -CAcreateserial \
      -days 825 -sha256 -extfile pki/api.ext -out pki/api-node.crt
    rm pki/api-node.csr pki/api.ext
    echo "Copy pki/node-ca.crt, pki/api-node.crt and pki/api-node.key to infra/docker/secrets/"
    ;;
  issue)
    id="${2:?node id required}"
    [[ "$id" =~ ^[a-z0-9-]{3,32}$ ]] || { echo "node id: lowercase letters, digits, dashes"; exit 1; }
    openssl ecparam -genkey -name prime256v1 -noout -out "pki/$id.key"
    openssl req -new -key "pki/$id.key" -subj "/CN=$id" -out "pki/$id.csr"
    printf "extendedKeyUsage=clientAuth\n" > pki/client.ext
    openssl x509 -req -in "pki/$id.csr" -CA pki/node-ca.crt -CAkey pki/ca.key -CAcreateserial \
      -days 825 -sha256 -extfile pki/client.ext -out "pki/$id.crt"
    rm "pki/$id.csr" pki/client.ext
    echo "Node certificate: pki/$id.crt + pki/$id.key (copy to the node with pki/node-ca.crt)"
    ;;
  *)
    sed -n '2,9p' "$0"; exit 1 ;;
esac
