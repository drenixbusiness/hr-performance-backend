#!/usr/bin/env bash
# Generates an Ed25519 JWK for signing access tokens.
#
# Store the output in your secret manager and inject it as AUTH_SIGNING_KEY.
# Never commit it: whoever holds this key can mint a token for any user with any
# permission, and no amount of password policy will help.
set -euo pipefail

python3 - <<'PY'
import base64, json, os, secrets

try:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
except ImportError:
    raise SystemExit("pip install cryptography")

def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

private = Ed25519PrivateKey.generate()
public = private.public_key()

jwk = {
    "kty": "OKP",
    "crv": "Ed25519",
    "kid": secrets.token_hex(8),
    "use": "sig",
    "alg": "EdDSA",
    "x": b64u(public.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)),
    "d": b64u(private.private_bytes(
        serialization.Encoding.Raw,
        serialization.PrivateFormat.Raw,
        serialization.NoEncryption())),
}
print(json.dumps(jwk, separators=(",", ":")))
PY
