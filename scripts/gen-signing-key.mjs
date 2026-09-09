#!/usr/bin/env node
// Generates an Ed25519 JWK for signing access tokens, using only Node's built-in crypto.
//
// Same output as gen-signing-key.sh, for machines without Python. Store the result in your
// secret manager and inject it as AUTH_SIGNING_KEY. Never commit it: whoever holds this key
// can mint a token for any user with any permission, and no password policy will help.
//
//   node scripts/gen-signing-key.mjs

import { generateKeyPairSync, randomBytes } from 'node:crypto';

const { privateKey } = generateKeyPairSync('ed25519');
const jwk = privateKey.export({ format: 'jwk' }); // { kty, crv, x, d }

// Key order matters only for readability; nimbus reads the members by name.
process.stdout.write(JSON.stringify({
  kty: jwk.kty,
  crv: jwk.crv,
  kid: randomBytes(8).toString('hex'),
  use: 'sig',
  alg: 'EdDSA',
  x: jwk.x,
  d: jwk.d,
}) + '\n');
