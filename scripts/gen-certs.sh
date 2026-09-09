#!/usr/bin/env bash
# Creates a private CA and one certificate per service for internal mTLS.
# Development only: in production these come from your PKI or from cert-manager,
# with short lifetimes and automated rotation.
set -euo pipefail

# Git Bash / MSYS on Windows rewrites any argument that looks like an absolute path, which turns
# an openssl subject like /O=Drenix/CN=x into C:/Program Files/Git/O=Drenix/CN=x and makes openssl
# reject it. These two variables switch that rewriting off; they are ignored everywhere else.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

OUT="${1:-./deploy/tls}"
DAYS="${DAYS:-365}"

# ECDSA P-256, not Ed25519. grpc-java's shaded Netty reads PEM private keys by trying RSA, DSA
# and EC only — an Ed25519 key fails with "File does not contain valid private key ... Neither
# RSA, DSA nor EC worked" and no service can build its SSL context. P-256 is supported
# everywhere in this stack and is no weaker for TLS.
#
# This is only the transport PKI. Access tokens are still signed with Ed25519, which goes
# through nimbus and Tink rather than Netty and has no such limitation.
KEY_ALGO_ARGS=(-algorithm EC -pkeyopt ec_paramgen_curve:P-256)
mkdir -p "$OUT"
cd "$OUT"

# Both halves, not just the key: a run that died between the two left a key with no certificate,
# and checking only the key made every later run skip the CA and then fail on the missing cert.
if [[ ! -f internal-ca.key || ! -f internal-ca.crt ]]; then
  rm -f internal-ca.key internal-ca.crt
  echo "==> Creating internal CA"
  openssl genpkey "${KEY_ALGO_ARGS[@]}" -out internal-ca.key
  openssl req -x509 -new -key internal-ca.key -sha256 -days "$DAYS" \
    -subj "/O=Drenix/CN=Drenix Internal CA" -out internal-ca.crt
  chmod 600 internal-ca.key
fi

issue() {
  local name="$1"
  echo "==> Issuing certificate for $name"
  openssl genpkey "${KEY_ALGO_ARGS[@]}" -out "${name}.key"
  openssl req -new -key "${name}.key" -subj "/O=Drenix/CN=${name}" -out "${name}.csr"

  # The SAN must contain the name the client uses to dial, or TLS verification fails
  # even though the certificate is otherwise valid.
  cat > "${name}.ext" <<EXT
subjectAltName = DNS:${name}, DNS:localhost, IP:127.0.0.1
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth, clientAuth
EXT

  openssl x509 -req -in "${name}.csr" -CA internal-ca.crt -CAkey internal-ca.key \
    -CAcreateserial -days "$DAYS" -sha256 -extfile "${name}.ext" -out "${name}.crt"
  rm -f "${name}.csr" "${name}.ext"
  chmod 600 "${name}.key"
}

for service in user-service auth-service performance-service notification-service edge-gateway; do
  issue "$service"
done

echo
echo "Certificates written to $(pwd)"
echo "The CN of each certificate is the service name; PeerIdentityInterceptor checks it against"
echo "drenix.security.allowed-peers, so adding a service means updating that list too."
