# Security model

Every decision below has a reason. Where a choice trades something away, the trade is named.

## 1. There is no registration

The only way an account comes into existence is an administrator calling
`POST /api/v1/admin/users`. There is no public sign-up route, no invitation flow, no
"first user becomes admin" shortcut. The very first administrator is created once, at
startup, by `BootstrapAdminInitializer` from `DRENIX_BOOTSTRAP_ADMIN_PASSWORD`, and only
while the `users` table is empty.

Every account, including that one, starts with `must_change_password = true`. The admin who
types a password never retains the ability to sign in as that person.

## 2. Passwords

| Decision | Why |
|---|---|
| Argon2id, 19 MiB / 2 iterations / 1 lane | OWASP baseline. Memory hardness is what makes GPU cracking expensive. bcrypt silently truncates at 72 bytes. |
| Minimum 12 characters, blocklist, no composition rules | NIST SP 800-63B. Length and a blocklist stop real attacks; "one upper, one digit" mostly produces `Password1!`. |
| No forced expiry | Rotation drives people to write passwords down and increment a trailing digit. Rotate on evidence of compromise instead. |
| Dummy hash for unknown usernames | Without it, response time reveals which usernames exist, turning spraying into targeting. |
| Re-hash on login when parameters change | Raising the cost later does not leave old accounts behind. |

Hashes never leave user-service. auth-service sends the candidate password over mTLS and
receives a verdict.

## 3. Tokens

**Access token** — EdDSA (Ed25519) JWT, 10 minutes, `typ: at+jwt`, carrying `sub`, `sid`,
`jti`, `perm` and `ent`. Verified locally by every service against the JWKS, so a request
never depends on auth-service being up.

Pinned algorithm and pinned type are not decoration: accepting whatever the header claims is
exactly how `alg: none` and RS256/HS256 confusion attacks work.

**Refresh token** — 256 random bits, not a JWT. Stored as SHA-256 in Redis, so a Redis dump
is not a set of working credentials. Every use rotates it.

**Reuse detection** — a refresh token is single-use. If one comes back after rotation, two
parties hold it and we cannot tell which is the thief, so the whole family dies and both must
sign in again. An inconvenienced user beats a silent intruder.

## 4. Revocation

A self-contained token stays valid until it expires — unless something says otherwise. That
something is the Redis denylist, checked by the gateway on every request:

- Logout denylists the session id.
- A password change kills every session for that user, including the one that changed it.
- Refresh-token reuse kills every session and every family for that user.

Entries expire after one access-token lifetime; keeping them longer grows the set forever.

## 5. Service-to-service

Two independent gates on every internal RPC:

1. **mTLS + peer allowlist.** TLS proves the caller holds a key signed by the internal CA;
   `PeerIdentityInterceptor` then checks the certificate CN against an explicit list. Without
   the second half, any internal certificate opens every service.
2. **Token re-verification.** The caller's own access token is forwarded and verified again at
   the destination. Services do not trust the gateway to have done authorization — a
   compromised gateway cannot mint authority it was never given.

The gateway holds no service account of its own. It acts *as* the user, so it can never do
more than that user can.

## 6. Authorization

Permissions are `resource:action` strings. Roles group them; users hold roles, each optionally
narrowed to one company (JM / BP). A grant with no company applies to every company.

The permission vocabulary lives in migrations, not in the application, so the set of possible
actions is reviewable in git rather than editable at runtime.

Three guards worth knowing about:

- **No privilege escalation through roles.** You cannot put a permission into a role that you
  do not hold yourself. Otherwise anyone with `role:update` becomes a super admin in two calls.
- **No self-lockout.** You cannot disable your own account or drop your own `SUPER_ADMIN`.
- **No orphaned access.** A role still assigned to users cannot be deleted; reassign first, so
  the change is deliberate and visible.

Every mutating RPC opens with one visible `GrpcAuthContext.require(...)` line. An unguarded
method shows up in review as a missing line rather than as a mistyped annotation that silently
did nothing.

## 7. Rate limiting and lockout

Two counters, because they catch different attacks: per username (someone hammering one
account) and per client IP (spraying one attempt across a thousand usernames, which never
trips a per-account lock). Both run before Argon2id, which also makes the login endpoint a
poor memory-exhaustion lever.

Account lockout is 5 failures, 15 minutes, and the lock is *not* announced on the attempt that
causes it — that would confirm the username exists.

## 8. Audit

Append-only. The table carries rules that turn UPDATE and DELETE into no-ops, so reaching the
application role is not enough to rewrite history. Writes run in their own transaction, so a
denied or failed operation still leaves a record after its own transaction rolls back — the
failures are exactly the rows worth keeping.

## 9. Where the security code actually lives

| Concern | File |
|---|---|
| Token minting | `auth-service/.../token/AccessTokenIssuer.java` |
| Token verification | `platform-security/.../AccessTokenVerifier.java` |
| Refresh rotation and theft detection | `auth-service/.../session/RefreshTokenStore.java` |
| Revocation denylist | `auth-service/.../session/SessionRegistry.java` |
| Login throttling | `auth-service/.../session/LoginRateLimiter.java` |
| Password hashing and lockout | `user-service/.../service/{PasswordHasher,CredentialService}.java` |
| Permission checks | `platform-security/.../GrpcAuthContext.java` (`require(...)`) |
| Service-to-service identity | `platform-security/.../PeerIdentityInterceptor.java` |
| TLS for the gRPC servers | `platform-security/.../GrpcServerRunner.java` |
| Public edge hardening | `edge-gateway/.../security/GatewaySecurityConfig.java` |

## 10. What is deliberately not here yet

- **MFA / TOTP.** The schema has room; the flow is not written. Worth doing before this is
  exposed to the public internet.
- **Secret management.** Everything reads from the environment. That is correct for containers
  but assumes a real secret store (Vault, SOPS, cloud KMS) in front of it.
- **Signing-key rotation automation.** The code supports multiple keys; the rotation schedule
  is still manual.
- **Per-record entity filtering.** `canActOn` exists and is enforced where identity data is
  concerned, but the business tables it will guard do not exist yet.
