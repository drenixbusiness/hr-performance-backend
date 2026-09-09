# Drenix backend

Java 26 · Maven · Spring Boot 4.1 · gRPC over mTLS · PostgreSQL · Redis

Identity and access management, plus two performance views for the recruiting team: hiring figures
read from a **monday.com** board, and call/SMS figures read from **RingCentral**.

**Admin-created accounts only — there is no registration endpoint anywhere in this system and
there never will be.**

This file is written to be read by a person or by an AI assistant picking the project up cold. It
describes what actually runs, not what is planned. Every number, path and JSON sample in it was
taken from a running stack.

---

## 1. What is running

Seven containers, started by one compose file. Only one of them is reachable from outside.

| Service | Ports | Role | Owns |
|---|---|---|---|
| `edge-gateway` | **8443** (REST, published) | The only public surface. Verifies tokens, decides who may see what, forwards over gRPC. | nothing |
| `auth-service` | 9092 (gRPC), 9443 (JWKS, https) | Login, token issuing, refresh rotation, sessions, revocation. | Redis |
| `user-service` | 9090 (gRPC) | Users, roles, permissions, password hashes, audit log, the activity standard. | PostgreSQL `drenix_identity` |
| `performance-service` | 9094 (gRPC) | Reads monday.com and RingCentral, computes the charts, records every shift, writes the assessment. | PostgreSQL `drenix_performance` |
| `notification-service` | 9096 (gRPC) | Turns audit entries and missed targets into per-person notifications. | PostgreSQL `drenix_notification` |
| `postgres-identity` | 5432 (internal) | One server, three databases, one owner each. | |
| `redis` | 6379 (internal) | Sessions, refresh tokens, rate limits, revocation denylist. | |

```
browser ──REST/http──> edge-gateway ──gRPC/mTLS──> auth-service ──gRPC/mTLS──> user-service
                            │                          │                          │
                            │                          └──── Redis ───┘      PostgreSQL
                            │
                            ├──gRPC/mTLS──> performance-service ──https──> api.monday.com
                            │                        ▲                └────> platform.ringcentral.com
                            │                        │ reads, on a schedule
                            └──gRPC/mTLS──> notification-service ──> PostgreSQL
                                                     │ reads, on a schedule
                                                     └──────> user-service
```

**The frontend talks to `http://localhost:8443` and nothing else.** The internal ports are not
published and require client certificates.

### It is http, not https, locally

`PUBLIC_TLS_ENABLED=false` in compose, because TLS is meant to be terminated by an ingress in
front of the gateway. Requests to `https://localhost:8443` fail at the handshake, not with an HTTP
error.

---

## 2. Getting it running

```bash
mvn clean install -DskipTests
```

```bash
bash scripts/gen-certs.sh ./deploy/tls
```

```bash
node scripts/gen-signing-key.mjs
```

Copy `.env.example` to `.env` and fill it in: **three** database passwords (`IDENTITY_DB_PASSWORD`,
`NOTIFICATION_DB_PASSWORD` and `PERFORMANCE_DB_PASSWORD` — three services own a database each),
the Redis password, the
bootstrap admin password (**minimum 12 characters**), the signing key from the command above, the
monday.com token, and then **per company**: a board id and three RingCentral values for JM
(`MONDAY_BOARD_ID`, `RC_CLIENT_ID`, `RC_CLIENT_SECRET`, `RC_JWT`) and the same four for BP with a
`_BP` suffix. A company whose values are left empty simply cannot be reported on; the rest still
works. `OPENAI_API_KEY` is optional — without it the AI feedback endpoint answers 503 and
everything else is unaffected.

```bash
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
```

`--env-file .env` is **not optional**. Compose takes its project directory from the compose file's
own folder, so without it the `.env` at the repo root is never read and every `${...:?}` variable
fails with "set IDENTITY_DB_PASSWORD".

Values in `.env` that contain `$` must be single-quoted, or Compose refuses to parse the file at
all and every service fails to start with a message about the *variable name*, not the value.

Interactive API documentation: **http://localhost:8443/swagger-ui.html** · raw OpenAPI at
`/v3/api-docs`.

---

## 3. Authentication — read this before anything else

### The flow

1. `POST /api/v1/auth/login` with username and password → token pair.
2. Send `Authorization: Bearer <accessToken>` on every other request.
3. On 401, call `POST /api/v1/auth/refresh` with the refresh token → new pair.

### Five things that will cost you an afternoon if nobody says them

**Access tokens live 10 minutes.** After that every request is 401. Refresh, do not re-login.

**Refresh tokens work exactly once.** Each refresh returns a new one; keep it and discard the old.
Sending the same refresh token twice is read as theft — one of the two holders must be an attacker
— and **every session for that account is revoked immediately**. In a React app this bites when two
components refresh in parallel: serialise refreshes through a single in-flight promise.

**A new account cannot do anything except change its password.** Accounts created by an
administrator, and the first `admin`, come back with `passwordChangeRequired: true`. The token
issued in that state carries exactly one permission, `auth:changeOwnPassword`. Every other endpoint
answers **403** with:

```json
{ "code": "password_change_required",
  "message": "This token can only change your password. Call POST /api/v1/auth/password, then sign in again to get a full token." }
```

Handle that code explicitly and route to a change-password screen. It is not a generic permission
failure.

**`user.permissions` in the login response is what the token grants, not what the account owns.**
While `passwordChangeRequired` is true it is just `["auth:changeOwnPassword"]`. Build menus from
this list and they will be right in both states.

**`user.companies` is the company switcher. Do not compute it yourself.** The login response
carries both the raw grants and the resolved answer:

```json
"user": {
  "permissions": ["user:read", "..."],
  "roles": [ { "roleCode": "OWNER", "entity": null } ],
  "companies": ["BP", "JM"]
}
```

A role granted with **no `entity` covers every company**, and `companies` has already applied that
rule: an `OWNER` comes back as `["BP","JM"]`, an `HR_LEAD@JM` as `["JM"]`. Deriving the list from
`roles` instead means reimplementing the rule in the client, and it fails in exactly one direction
— an unscoped grant reads as *no* companies rather than every company, so **the owner of the
business is shown "you have no access to BP"** while every endpoint on the server serves them
happily. That was a real bug; this field is the fix.

**Changing a password signs the account out everywhere, including the session that changed it.**
After a 204 from `POST /api/v1/auth/password`, the token in hand is dead.

**A refresh does not return `user`.** It renews tokens and nothing else, so `companies`,
`permissions` and `roles` come from the *login* response — keep them. A role change reaches the
token within one access-token lifetime, but the client's copy of `companies` only updates on the
next sign-in.

### Login failures are deliberately identical

A wrong password, an unknown username, a disabled account and a locked account all return the same
401 with the same message. Do not try to distinguish them in the UI — the API will not help you, on
purpose.

Five failed attempts lock the account for 15 minutes, and a locked account looks exactly like a
wrong password. An administrator clears it with `POST /api/v1/admin/users/{id}/unlock`.

---

## 4. The API — 32 endpoints

Base URL `http://localhost:8443`. Everything needs a bearer token except the two marked **public**.

### Health (1)

| Method | Path | Needs |
|---|---|---|
| GET | `/api/health` | **public** — `{"status":"UP"}` |

Liveness only: it answers from the process and consults nothing. A failing database does not make
it fail, on purpose — restarting a healthy gateway because Postgres was briefly busy turns a slow
minute into an outage. Dependency detail lives on the actuator, on the management port, which is
not published.

### Authentication (4)

| Method | Path | Needs |
|---|---|---|
| POST | `/api/v1/auth/login` | **public** |
| POST | `/api/v1/auth/refresh` | **public** — the refresh token is the credential |
| POST | `/api/v1/auth/logout` | any token; ends this session only |
| POST | `/api/v1/auth/password` | `auth:changeOwnPassword`; ends **all** sessions |

### Admin · Users (10)

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/admin/users` | `user:read` |
| GET | `/api/v1/admin/users/{id}` | `user:read` |
| POST | `/api/v1/admin/users` | `user:create` |
| PATCH | `/api/v1/admin/users/{id}` | `user:update` |
| DELETE | `/api/v1/admin/users/{id}` | `user:delete` |
| PUT | `/api/v1/admin/users/{id}/roles` | `user:assignRole` |
| POST | `/api/v1/admin/users/{id}/password` | `user:resetPassword` |
| POST | `/api/v1/admin/users/{id}/unlock` | `user:update` |
| GET | `/api/v1/admin/users/{id}/sessions` | `session:revoke` |
| DELETE | `/api/v1/admin/users/{id}/sessions` | `session:revoke` |

### Admin · Roles (5)

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/admin/roles` | `role:read` |
| GET | `/api/v1/admin/roles/permissions` | `role:read` |
| POST | `/api/v1/admin/roles` | `role:create` |
| PUT | `/api/v1/admin/roles/{code}` | `role:update` |
| DELETE | `/api/v1/admin/roles/{code}` | `role:delete` |

### Admin · Audit (1)

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/admin/audit` | `audit:read` |

### Performance (1) — hiring, from monday.com

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/performance` | `performance:read` |

### Activity (4) — calls and SMS

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/activity` | any of `performance:read` / `readTeam` / `readAll` |
| GET | `/api/v1/activity/history` | the same |
| GET | `/api/v1/activity/standard` | any performance permission |
| PUT | `/api/v1/activity/standard` | `performance:manageStandard` |

`/activity` reads RingCentral live — hour windows and today, **at most 7 days**.
`/activity/history` reads the stored daily snapshots — any range, instantly, no hours and no today.

### Notifications (4)

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/notifications` | `notification:read` |
| GET | `/api/v1/notifications/unread-count` | `notification:read` |
| POST | `/api/v1/notifications/{id}/read` | `notification:read` |
| POST | `/api/v1/notifications/read-all` | `notification:read` |

### Reports (2)

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/reports/monthly` | any of `performance:read` / `readTeam` / `readAll` |
| POST | `/api/v1/reports/monthly/pdf` | the same — returns a PDF |

Both cover **one MS**: `?entity=JM` or `?entity=BP`, defaulting to your own and to JM when you can
see both.

---

## 5. Frontend notes per area

### Users

Cursor-paged, **not** offset-paged:

```json
{ "items": [ ... ], "nextCursor": null, "hasMore": false }
```

Send `nextCursor` back as `?cursor=`; stop when `hasMore` is false. No page numbers, no total count
— do not build a numbered pager. `?limit=` accepts 1–200, default 50. `?search=` filters over
username and full name.

**The directory is scoped to your own companies.** `user:read` says the directory may be read; it
does not make the whole organisation yours to read. An account scoped to JM sees JM accounts and
its own; it does not see BP's staff, and it does not see accounts whose every grant is global —
the administrators and the owner. A global grant (OWNER, SUPER_ADMIN, CEO) sees everybody.

This used to return every account to anybody holding `user:read`, which includes plain `HR`. A JM
recruiter was answered with BP's lead, BP's recruiters and the full administrator roster.

**A page can therefore come back shorter than `?limit=`**, because the scoping is applied after
the page is read. `hasMore` and `nextCursor` still describe the underlying rows correctly. Page
until `hasMore` is false; never stop because a page looked short.

**Deleting.** `reason` goes to the audit log and is required, but it may arrive **either** way:

```
DELETE /api/v1/admin/users/{id}?reason=Left%20the%20company
DELETE /api/v1/admin/users/{id}   {"reason":"Left the company"}
```

The query form exists because a body on a DELETE is a trap — `fetch` forbids one outright and
several HTTP clients drop it silently, and when that happened this endpoint answered 400 and did
nothing, which from the client's side is indistinguishable from a server fault.

Both user and role deletes answer **200 with a small JSON body** (`{"id":"…","deleted":true}`)
rather than an empty 204, so a client that runs every response through `.json()` does not throw on
a call that in fact succeeded.

A user looks like this:

```json
{
  "id": "3d5a399e-2df0-4c06-9e74-098dae3243c9",
  "username": "t.isaac",
  "fullName": "Isaac Taylor",
  "email": null,
  "phone": "+998773302146",
  "ringCentralPhone": "+13313291144",
  "ringCentralPhones": ["+13313291144"],
  "mondayName": "Isaac",
  "status": "ACTIVE",
  "mustChangePassword": false,
  "roles": [ { "roleCode": "HR", "entity": "JM" } ],
  "permissions": [ "user:read", "performance:read", "..." ],
  "createdAt": "2026-08-26T19:50:58Z",
  "updatedAt": "2026-08-27T17:36:03Z",
  "lastLoginAt": "2026-08-27T17:36:03Z"
}
```

Unset optional fields are **null**, not empty strings.

**Three different identifiers, and they are not interchangeable:**

| Field | What it is | Used by |
|---|---|---|
| `phone` | how a colleague reaches this person | nothing; contact detail |
| `ringCentralPhones` | every extension their calls and SMS are logged against | `/api/v1/activity` |
| `mondayName` | what the monday board's Source column calls them | `/api/v1/performance` |

**A person can have several RingCentral numbers.** HR leads work two extensions, and the
activity report adds all of them into a single row for that person. Send them as
`ringCentralPhones: ["+1...", "+1..."]` on create, and on `PATCH` to **replace the whole list** —
send every number they keep, not just the new one; `[]` clears them. The response also carries
`ringCentralPhone`, the first of the list, only so that clients written before this keep working;
read the array.

Every number and every `mondayName` is **unique across accounts** — a duplicate is a 409 naming the
offending value. An empty list and a null `mondayName` are both fine; the account then simply does
not appear in the corresponding chart. `mondayName` is usually *not* the same as `fullName`: the
board uses short names ("Alex") while the account holds the full one ("Alex Chester").

`PATCH` writes only the fields you send; omitting one keeps its current value. There is no way to
blank a field by leaving it out.

`PUT .../roles` **replaces** the whole set — send every role the user should end up with; `[]`
strips them all. The body is a bare JSON array:

```json
[ { "roleCode": "HR_LEAD", "entity": "JM" } ]
```

`DELETE .../users/{id}` **requires a JSON body** with a `reason` — it goes into the audit log.

Deletion is soft. The username and `mondayName` are **not** freed for reuse, so "already taken" can
come back for a value that is nowhere in the list. RingCentral numbers **are** released, so the
extension can be handed to whoever takes over the desk.

### Sessions

`GET .../sessions` returns an array, not a page:

```json
[ { "sessionId": "4822bc2a-…", "clientIp": "172.19.0.1",
    "userAgent": "curl/8.19.0", "openedAt": "2026-08-26T13:50:41Z" } ]
```

`DELETE .../sessions` ends every session; add `?sessionId=` to end one. Revocation is immediate —
the gateway checks a denylist on every request.

### Roles

`GET /api/v1/admin/roles` returns an array. `system: true` roles cannot be deleted.

Before offering a permission picker, call `GET /api/v1/admin/roles/permissions` — those codes are
the only strings the `permissions` array accepts.

`PUT /api/v1/admin/roles/{code}` takes **no `code` in the body**; its permission list replaces the
old one.

### Audit

Cursor-paged, newest first. Optional filters, combined with AND: `actor`, `action`, `outcome`
(`SUCCESS` | `DENIED` | `FAILURE`), `targetId`, `from`, `to` (ISO-8601).

```json
{
  "id": 5, "occurredAt": "2026-08-26T13:50:41Z",
  "actorUsername": "admin", "actorId": "ed7772d3-…",
  "action": "auth.login", "targetType": "user", "targetId": "ed7772d3-…",
  "outcome": "SUCCESS", "clientIp": "172.19.0.1", "userAgent": "curl/8.19.0",
  "correlationId": "74dabe92-…", "detail": {}
}
```

`detail` is a **JSON object**, already parsed — not a string. `correlationId` matches the
`X-Correlation-Id` response header, which is how a user's bug report gets tied to what the system
recorded. Log that header client-side.

### Performance — hiring, from monday.com

```
GET /api/v1/performance?year=2026&recruiter=Alex&entity=BP
```

All three parameters optional. One entry per recruiter, twelve months each.

**Every row carries an `entity`** — `JM` or `BP`. The two companies keep separate boards, and the
same first name appears on both: there is an `Alex`, an `Ethan`, a `Fred` and a `Winston` on each.
**Key your chart on `entity` + `recruiter`, never on `recruiter` alone**, or two different people
will be merged into one line.

Omit `entity` and you get every board **you are allowed to see** — one company for a lead scoped
to it, both for a CEO or an administrator. Asking for a company you are not scoped to is a 403.
`dataIssues` carries an `entity` too.

**The three series mean different things.**

- `hired` — drivers whose hire date falls in that month. A hire belongs to the month it happened
  and stays there: somebody recruited in May who leaves in August is still a May hire.
- `terminated` — drivers whose leaving date falls in that month.
- `active` — how many were on the books when the month **ended**. A point-in-time count.

**Do not compute `active` yourself as `hired − terminated`.** The board contains rows with
impossible dates, so the two do not reconcile.

**Stop drawing at the last month where `future` is false.** Months that have not arrived come back
with `future: true` and zeroes; plotting them draws a cliff that looks like a collapse.

**Quarters are counted from each recruiter's own start** — their earliest hire on the board, not
January. `months[].quarter` therefore differs between recruiters for the same calendar month, and
is `0` outside their measured first year.

| Quarter | Hires/month | Hires in quarter | Active at end |
|---|---|---|---|
| 1 | 4 | 12 | 7 |
| 2 | 6 | 18 | 11 |
| 3 | 8 | 24 | 14 |
| 4 | 10 | 30 | 18 |

After a full year: 50 active drivers. `complete: false` means the quarter is still running — do not
paint `hiresMet: false` red until it ends.

`dataIssues` lists board rows the chart could not fully account for. Read it before trusting a dip:
a bad month may just be a month somebody forgot to fill in. `userId` is currently always null —
linking a monday recruiter to an account is not implemented on this endpoint.

**First call after startup takes ~5 seconds**; later calls ~0.25s from a 10-minute cache.


### Activity — calls and SMS, from RingCentral

```
GET /api/v1/activity?from=2026-08-25&to=2026-08-26&fromTime=18:00&toTime=03:00
```

Everything is optional. `to` defaults to **today** and `from` to **yesterday**, so a bare call
answers "today and yesterday". Ranges longer than **31 days** are a 400.

**Shift windows.** `fromTime` and `toTime` are `HH:mm` and go together or not at all. When `toTime`
is **not after** `fromTime` the window runs past midnight, and the whole night is reported **under
the date it started on** — `18:00`→`03:00` on the 25th is one row dated `2026-08-25`, not two half
rows either side of midnight. This is the point of the feature: the recruiting shift crosses
midnight, and calendar days would show everybody missing every target twice.

Give a clock window and each day also carries `hours`: one entry per clock hour, quiet hours
included, so a bar chart has no gaps. Without a window `hours` is empty — a month of whole days
would be 744 points per person.

`fromTime=00:00&toTime=00:00` is the way to ask for a full 24 hours *with* the hourly breakdown.

```json
{
  "from": "2026-08-25",
  "to": "2026-08-26",
  "fromTime": "18:00",
  "toTime": "03:00",
  "zone": "Asia/Tashkent",
  "windowMinutes": 540,
  "standard":       { "callsPerDay": 125, "talkSecondsPerDay": 3600, "smsSentPerDay": 150, "shiftMinutes": 540 },
  "windowStandard": { "callsPerDay": 125, "talkSecondsPerDay": 3600, "smsSentPerDay": 150, "shiftMinutes": 540 },
  "agents": [ {
    "userId": "dd40cd40-…",
    "username": "a.chester",
    "fullName": "Alex Chester",
    "ringCentralName": "Alex Chester",
    "ringCentralPhone": "+13312648196",
    "ringCentralPhones": ["+13312648196", "+13127962626"],
    "unresolvedPhones": [],
    "days": [ {
      "date": "2026-08-26", "calls": 82, "talkSeconds": 4950, "smsSent": 138,
      "callsMet": false, "talkMet": true, "smsMet": false,
      "hours": [ { "hour": "2026-08-26T18:00", "calls": 9, "talkSeconds": 372, "smsSent": 24 } ]
    } ],
    "totalCalls": 266, "totalTalkSeconds": 15986, "totalSmsSent": 356,
    "activeDays": 2, "extensionUnresolved": false
  } ]
}
```

**Zone.** Every date, window and hour label is in `zone`, set by `RC_ZONE` and currently
`Asia/Tashkent` — the recruiters work 18:00–01:00 local, which is 13:00–20:00 UTC. Set `RC_ZONE=UTC`
to go back to the older cut; note that this changes which day evening work lands on.

**What counts, and what does not:**

| Measure | Default target per shift | Counted as |
|---|---|---|
| `calls` | 125 | every attempt, inbound or outbound, connected or not |
| `talkSeconds` | 3600 | total time on calls in the window, summed |
| `smsSent` | 150 | **outbound messages only** |

Received SMS is excluded on purpose: a reply is not work the recruiter did, and counting it would
let a talkative candidate lift somebody's figures.

**`standard` vs `windowStandard`.** The targets describe one full shift, `shiftMinutes` long (540 =
nine hours). Ask for a shorter window and `windowStandard` is that target prorated — three hours of
a nine-hour shift carries a third of it, so 125 calls becomes 42. `callsMet`, `talkMet` and
`smsMet` are judged against **`windowStandard`**, never against `standard`. Asking for more than a
shift never raises the bar: a shift's work is not a rate per hour.

**Who you see** depends on which permission the token carries — the widest one wins, and the client
never says which to use:

| Permission | Scope | Held by |
|---|---|---|
| `performance:read` | yourself only | HR |
| `performance:readTeam` | everyone in your own company | HR_LEAD |
| `performance:readAll` | the whole organisation | CEO, SUPER_ADMIN |

"Company" is the `entity` on the role grant, so `HR_LEAD@JM` sees the JM recruiters.

**The company also decides which RingCentral account is read.** JM and BP hold separate accounts,
and the gateway looks each person up in the one their role grant names. A single response can mix
both: a CEO asking for everybody gets JM people read from JM and BP people read from BP, in one
call. Nothing in the request says which account to use — moving somebody between companies is a
role change, not an API change.

**Five things to know before charting this:**

- **One row per person, not per number.** Somebody who works two extensions has both in
  `ringCentralPhones` and a single set of totals covering all of them. Match rows by `userId`.
- People with **no RingCentral number at all** are absent from the answer entirely — there is
  nothing to match them by.
- `unresolvedPhones` lists numbers that are **not on that person's own company's RingCentral
  account** — one they do not work, one typed wrong, or one that belongs to the *other* company.
  It does not mean "made no calls": numbers are matched against the account's own directory, so
  somebody who spent a shift on SMS and made no calls still resolves and their messages are
  counted. The rest of the row is real; the listed numbers contribute nothing.
  `extensionUnresolved: true` is the stronger case — *none* of the numbers resolved, so every
  figure is a zero that means "unknown", not "poor performance". **A whole row unresolved usually
  means the person's `entity` is wrong**, not that their numbers are.
- **Which company a person belongs to** is the `entity` on their role grant. It decides which
  RingCentral account their numbers are looked up in, so an account with the wrong `entity` reads
  as somebody who did nothing at all.
- `activeDays` counts shifts on which anything happened. Divide totals by this rather than by the
  length of the range, or leave looks like failure.

**Cost of a range.** RingCentral limits **each account** to **10 requests per rolling 60 seconds** (its
"heavy" group), and two days of call log already fill a 1000-row page, so a wide range is paged
slowly on purpose. Measured cold: the 2-day default ~8s, a 31-day range ~2m40s. Repeat calls come
back in ~0.4s from the 10-minute cache. The two companies have separate budgets and separate
limiters, so a wide JM query does not slow BP down. The gateway allows this endpoint 3 minutes before it gives
up. Keep the default range unless a month is genuinely needed.

If the budget is spent anyway — something else is using the same credentials — the answer is a
**429** naming how long to wait, not a 500.

### The standard, and changing it

```
GET  /api/v1/activity/standard
PUT  /api/v1/activity/standard
```

The four numbers live in the identity database (`activity_standard`, one row), not in
configuration, so a lead can move a target without a release. `GET` is open to anyone with any
performance permission — a recruiter cannot read their own row without knowing the bar. `PUT` needs
**`performance:manageStandard`**, held by `HR_LEAD`, `CEO` and `SUPER_ADMIN`.

`PUT` is a **full replacement**, not a patch: send all four. They are read together and mean
nothing apart — raising the call target without saying how long a shift is leaves a standard that a
part-day question cannot be answered against. `GET` returns exactly the shape `PUT` expects, so
read it, change what you want, send it back.

```json
{ "callsPerDay": 140, "talkSecondsPerDay": 4200, "smsSentPerDay": 150, "shiftMinutes": 540 }
```

The response adds `updatedAt` and `updatedBy` (both null until somebody changes it). Every change
is audited as `performance.updateStandard` with the old and new values. It takes effect on the next
request: the standard is applied when results are read, so even cached counts are re-judged.

`shiftMinutes` is the denominator for part-day questions and nothing else. Out-of-range numbers are
a 400; the database refuses them too.

---

### Notifications

```
GET  /api/v1/notifications?limit=20&unreadOnly=false&kind=PERFORMANCE
GET  /api/v1/notifications/unread-count
POST /api/v1/notifications/{id}/read
POST /api/v1/notifications/read-all
```

Everything here is **yours**. No path names whose inbox to read, and an administrator has no way
to read somebody else's — the audit log answers the question they would be asking.

```json
{
  "items": [ {
    "id": 418,
    "kind": "PERFORMANCE",
    "severity": "WARNING",
    "title": "Isaac Taylor missed the standard on 2026-08-27",
    "body": "58 of 150 messages.",
    "subjectUserId": "3d5a399e-…",
    "subjectName": "Isaac Taylor",
    "occurredAt": "2026-08-27T22:00:00Z",
    "createdAt": "2026-08-28T19:02:52Z",
    "readAt": null,
    "reference": "2026-08-27"
  } ],
  "nextCursor": null, "hasMore": false, "unreadCount": 7
}
```

**Two kinds:**

| `kind` | What it is |
|---|---|
| `ACTIVITY` | an account was created, deleted, unlocked, had its roles changed or its password reset; a role or the activity standard was changed — succeeded **or refused**. Also **"Report generated successfully"**, written when you generate a monthly report |
| `PERFORMANCE` | somebody missed the daily standard on a **finished** shift |

**Only those actions.** Successful sign-ins, self-service password changes and routine edits to a
name or phone number produce nothing at all. An earlier version notified on every audit entry and
inboxes filled with sign-ins nobody reads, which is how a notification list teaches people to
ignore it. The audit log remains the place to go for everything else.

**Failed sign-ins produce nothing either.** They used to: the rule was "a listed action, *or*
anything that failed", so a mistyped password wrote a notification to every lead. One afternoon of
testing produced sixty of them for a username that does not exist — meaning anybody who can reach
the login form can fill every inbox in the company. A refused `user.delete` still notifies, because
`user.delete` is a listed action; a wrong password is in the audit log, and a repeated one locks
the account, which shows on the account.

**Who receives one — and this is the whole rule:**

| Recipient | Gets |
|---|---|
| the person the notification is **about** | their own missed standard, and changes to their own account (roles, password reset, unlock) |
| **HR_LEAD** | everything above for people in **their own company**, and nothing from the other |
| **CEO**, **SUPER_ADMIN**, **OWNER** | the same for every company, plus role and standard changes, plus anything whose subject cannot be resolved |

**A notification is addressed by who was *affected*, not by who acted.** An administrator creating
a BP recruiter is BP's news: it reaches the BP lead and no JM inbox. Addressing by the actor was
the earlier bug — an administrator belongs to no company, so their work landed in every lead's
inbox in both companies at once.

**JM and BP never see each other's notifications.** The only accounts that see both are the ones
whose remit is both: `CEO` and `SUPER_ADMIN`.

**Nobody is notified of their own actions.** `PERFORMANCE` is the deliberate exception: a lead
belongs to their own company, so a lead who misses the standard is told about themselves —
otherwise they are the one person nobody ever tells.

**Severity:**

| `severity` | Meaning |
|---|---|
| `INFO` | a record; something happened and it worked |
| `WARNING` | one or two of the three daily targets missed |
| `ALERT` | an action was refused or failed, or all three targets missed |

`unreadCount` is across everything, not just the page — it is the number for the badge. Poll
`/unread-count` rather than the list when that is all you need. `reference` is the audit action for
`ACTIVITY` and the shift date for `PERFORMANCE`, so a client can group without parsing the title.
`title` and `body` are already written for a human: show them as they are.

Marking an id that is not yours answers **404**, exactly as one that does not exist would — an id
cannot be used to find out whether somebody else was notified about something.

**Timing.** Actions appear within about a minute. A missed standard appears once the shift has
finished, so the 18:00–03:00 shift of the 27th is judged after 03:00 on the 28th. Judging a
running shift would report everybody as failing until its last hour, and a warning that is usually
wrong is one people learn to close without reading.

---

### Reports — the monthly PDF

```
GET  /api/v1/reports/monthly?month=2026-08&entity=JM&userId=3d5a399e-…
POST /api/v1/reports/monthly/pdf?month=2026-08&entity=JM&userId=3d5a399e-…&question=Focus%20on%20retention
```

`month` is `YYYY-MM`. Omit it for the month that has just finished — a report for a month still
running is half a report.

**One MS per report. `?entity=JM` or `?entity=BP` chooses.** Omit it and you get your own company;
JM if you can see both, so an owner or the CEO gets one report each rather than one report of both.
Asking for a company you have no access to answers **403**.

There is no separate BP endpoint and there should not be: it is the same report, and a second URL
would be a second copy of the scoping, the standard and the PDF to keep in step. BP is
`?entity=BP`:

```
GET  /api/v1/reports/monthly?entity=BP&month=2026-08
POST /api/v1/reports/monthly/pdf?entity=BP&month=2026-08
```

The PDF names the company in its reference (`REF BP-2026-08`) and its filename
(`drenix-bp-recruiting-report-2026-08.pdf`), so two reports on a desk cannot be confused.

This is not a convenience. The document ranks people against each other, scores them, names the
strongest and the weakest and concludes something about the state of one MS. JM and BP share no
recruiting board, no phone system and no manager, so a table spanning both would compare figures
that are not comparable — and would show each lead the other company's team. **Nothing in the
document mixes the two.**

**Everybody in that MS appears, the HR lead included.** A lead is a recruiter as well as a manager
and the report treats them as both: their own hires and calls sit in the table with everybody
else's, and the written analysis has a separate section on them as a leader.

A person whose role grant carries **no company** belongs to no MS and appears in no report. Give
an HR their role *with* a company — the same grant already decides which RingCentral account their
calls are read from.

**`userId` reports on one recruiter.** Omit it and the report covers the whole MS. This is how a
lead produces a report about a single HR: pick the person, pick the month. The id is filtered from
what you may already see, so asking for somebody in the other company answers **404** — the same as
an id that does not exist, so an id cannot be used to find out who works there.

A single-recruiter PDF names them on the cover, in the reference and in the filename
(`drenix-jm-report-t-isaac-2026-08.pdf`).

The `GET` returns the figures as JSON, for rendering on screen. The `POST` returns the document.

#### The document

`application/pdf`, with the filename in `Content-Disposition`
(`drenix-jm-recruiting-report-2026-08.pdf`). A browser pointed at it downloads; a client can read
the name from the header.

It is laid out as a document of record — a navy title band with a quotable reference
(`REF JM-2026-08`), a provenance line naming the MS, who it was prepared for and when it was
generated, and four numbered sections:

| | |
|---|---|
| **1. Summary** | six cards: recruiters in scope, calls, talk time and messages against target, drivers hired and lost |
| **2. By recruiter** | one table row each, every figure with its percentage of target beside it |
| **3. Against the standard** | a horizontal bar per metric per person: calls, talk time and messages, then **the recruiting standard** — hires required this month, hires required across the quarter with the pace so far, and active drivers required. The track is the target and the fill is the actual, so a bar that reaches the end is a target met; green at or above, red below |
| **4. Assessment** | the written management report — see below |

The header and footer repeat on every page, the table header repeats when the table breaks, and a
recruiter's block is never split across a page. Page numbers are written last, when there is a
total to write.

#### What the numbers mean

**Phone figures** come from this system's own database — every shift is written down as it
finishes — so a month is one indexed query rather than minutes of throttled RingCentral paging.
`recordedDays` is how many shifts actually have a stored row; fewer than the month means the
snapshot was not running then, **not** that nobody worked.

**Targets are per recorded shift**, not per calendar day. Fourteen stored shifts are measured
against fourteen shifts' worth, which is the only comparison that survives somebody being on leave.

**Hiring is counted twice.** `hiredInPeriod`, `stillActiveFromPeriod` and `terminatedInPeriod` are
for the month reported — that is what the report is about. `hiredToDate`, `activeNow` and
`terminatedToDate` sit beside them, because "4 hired this month" reads differently next to "62
ever" than next to "5 ever".

**The recruiting standard is per quarter of the recruiter's own tenure**, counted from their first
ever hire rather than from January, so two people who started in different months are measured
against the same shape of target at the same point in their own tenure.

| Quarter | Months | Hires per month | Hires per quarter | Active by quarter end |
|---|---|---|---|---|
| 1 | 1–3 | 4 | 12 | 7 |
| 2 | 4–6 | 6 | 18 | 11 |
| 3 | 7–9 | 8 | 24 | 14 |
| 4 | 10–12 | 10 | 30 | 18 |

**Past twelve months the fourth quarter's standard keeps applying**, and keeps applying for as long
as the person stays. `recruitingQuarter` goes on counting — 5, 6, 9 — and the targets stop at
quarter 4's. It used to return `0` past the first year, which left the longest-serving recruiters
as the only people in the company measured against nothing.

| field | |
|---|---|
| `recruitingQuarter` | which quarter of their own tenure the month fell in; `0` only when the board matched nobody |
| `quarterMonth` | 1, 2 or 3 — where the month sits inside that quarter |
| `tenureStartMonth`, `tenureMonths` | their first ever hire, and how long ago |
| `requiredHiresInMonth`, `requiredActive` | what the standard asked for |
| `hiredInQuarter`, `requiredHiresInQuarter` | the quarter so far, against its three-month target |
| `activeAtMonthEnd` | what they actually held when the month ended |

**A quarter still running is not a failed quarter.** `quarterMonth` is what says so: 6 hires
against a quarterly target of 18 is a miss in month 3 and exactly on pace in month 1. The PDF draws
the quarter bar against the full target with the expected pace in the caption, and the written
analysis is told the same thing in the same words.

**Tenure comes from `employmentStartDate` when somebody has recorded it, and is inferred when they
have not.** `tenureSource` says which: `RECORDED` or `INFERRED`.

Set it on the account — `POST /api/v1/admin/users` or `PUT /api/v1/admin/users/{id}` — as
`"employmentStartDate": "2026-06-01"`. Send `""` to clear it and go back to the inference.

**Fill it in.** The inference is the month of the person's first hire on the board, and it can only
ever be **later** than the truth: it cannot see the months before somebody made their first hire,
nor anything before the board existed. On the BP board it read a lead of two years as fifteen
months, and a recruiter in his fourth month as his fifth. The first costs nothing — everything past
twelve months is held to the same quarter 4 target either way. The second moved him out of quarter
1 and into quarter 2, raising his monthly requirement from 4 hires to 6 and his active-driver
minimum from 7 to 11, and shifted him from month 1 of a quarter to month 2, which nearly doubles
the pace he is expected to have reached. That is a failing scorecard produced from a guess.

Where the date is inferred, the PDF says so beside the person's name and the written analysis is
told to mention it once under VALIDATION.

**A dash is not a zero.** `boardMatched: false` means the board held no record under that name, so
every hiring figure is *unknown*. The PDF prints a dash and a footnote; never render a nought.

#### The assessment

Section 4 is a full management report, written by the language model from exactly the figures
above and nothing else — no driver names, no phone numbers, no message content. It is briefed as a
senior HR performance and business operations analyst for a US trucking company, given one MS, and
asked for ten sections:

| | |
|---|---|
| **VALIDATION** | what is missing or inconsistent in the input, and what that prevents it concluding |
| **A. Executive summary** | Healthy / Needs Attention / Critical, a 0–100 score for the MS, the biggest strength, the biggest risk, what to do first |
| **B. HR scorecard** | one table: quarter, quarter month, hire target and actual, active target and actual, calls / SMS / talk percentages, score, status |
| **C. Individual HR analysis** | per person: status, results, activity, efficiency, 2–4 strengths and 2–4 weaknesses each tied to a figure, the single most likely bottleneck, three actions for next month, a score and a status |
| **D. Financial contribution** | see below |
| **E. Activity vs result** | each person placed in high/low activity against high/low results, and what that means operationally |
| **F. Retention and quality** | hires against active drivers, and whose drivers are staying |
| **G. HR leader** | the lead as an individual recruiter, and as a leader — whether the team's results show coaching and KPI control |
| **H. MS condition** | hiring capacity, active base, productivity, efficiency, retention, leadership, risk, growth |
| **I. Management action plan** | five actions for the next 30 days as a table: priority, problem, action, owner, KPI, target, expected effect |
| **J. Final conclusion** | strongest, weakest, is the lead managing, what is limiting growth, what happens in three months, the single next action |

**Scoring is weighted, and stated in the brief rather than left to taste**: results 60% (active
drivers 35, hires 25), activity 30% (calls, SMS, talk time 10 each), efficiency and quality 10%.
Statuses are GREEN, YELLOW and RED — written as words because the PDF fonts have no emoji and every
one would print as `?`; the renderer colours the word instead.

**The arithmetic is done in Java, not by the model.** Working days in the month (six-day week,
Sundays excluded), the required monthly totals, every achievement percentage, the quarter, the
month within it and the expected pace are all computed and written into the prompt as facts, and
the brief tells it to use them rather than recompute. A model asked to divide will occasionally
divide wrong, and a report that is confidently 8% out is worse than no report.

**Daily targets come from the editable standard**, not from the brief. Change calls/SMS/talk time
in `PUT /activity/standard` and the next report judges everybody against the new bar and says so.

**Financial impact is not available and the report says exactly that.** Salary, payroll, lead cost
and profit per active driver are held nowhere in this system, so the prompt states they are absent
and requires the sentence *"Financial impact cannot be calculated accurately with the available
data."* followed by the list of what would be needed. Leaving the fields blank and hoping produces
a plausible invented salary; this is the fix for that.

`?question=` is appended as a note from the manager: "focus on retention", "who should I speak to
first".

##### Rendering it

The model is restricted to a small markdown grammar — `##` and `###` headings, pipe tables, `- `
bullets, `**bold**`, paragraphs — and `ReportMarkdown` is the renderer that draws exactly that
grammar into the PDF. Tables get a header band, zebra rows, columns sized to their contents and a
type size chosen from the column count; the fifteen-column scorecard fits on A4 at 6pt with long
cells clipped. Anything outside the grammar falls through to being drawn as a paragraph.

**If the model is unavailable the PDF is still produced.** Section 4 says why it is missing
instead. Losing a whole document because a third party is down would be the wrong trade.

#### Notification

A successful generation writes **"Report generated successfully"** to the caller's own
notifications, and to nobody else's. It is the one notification in the system that is pushed
rather than derived — generating a report leaves no audit entry and no finished shift to poll for,
so there is nothing else to notice it. Failing to write it never fails the download.
---

## 6. Roles and permissions as they exist right now

18 permissions, 7 roles. Every role holds `notification:read` — there is no inbox but your own.

| Role | Kind | Permissions |
|---|---|---|
| `OWNER` | system | **every permission, every company.** The business owner |
| `SUPER_ADMIN` | system | all except `performance:readTeam` (it holds `readAll`) |
| `USER_ADMIN` | system | `user:*`, `role:read`, `auth:changeOwnPassword` |
| `AUDITOR` | system | `user:read`, `role:read`, `audit:read`, `auth:changeOwnPassword` |
| `CEO` | custom | `user:read`, `role:read`, `audit:read`, `performance:read`, `performance:readAll`, `performance:manageStandard`, `auth:changeOwnPassword` |
| `HR_LEAD` | custom | `user:create/read/update/delete/resetPassword/assignRole`, `role:read`, `audit:read`, `performance:read`, `performance:readTeam`, `performance:manageStandard`, `auth:changeOwnPassword` |
| `HR` | custom | `user:read`, `role:read`, `performance:read`, `auth:changeOwnPassword` |

The vocabulary: `user:create`, `user:read`, `user:update`, `user:delete`, `user:assignRole`,
`user:resetPassword`, `role:create`, `role:read`, `role:update`, `role:delete`, `audit:read`,
`session:revoke`, `notification:read`, `performance:read`, `performance:readTeam`, `performance:readAll`, `performance:manageStandard`,
`auth:changeOwnPassword`.

### OWNER

Grant it with **no entity** and it reaches every company:

```json
[ { "roleCode": "OWNER" } ]
```

It is distinct from `SUPER_ADMIN` on purpose. `SUPER_ADMIN` is the *operator* of the system and
deliberately holds `performance:readAll` instead of `readTeam`; `OWNER` is the person who owns the
business and holds **everything without exception**, both visibility permissions included, so no
check anywhere can find a gap in what they are allowed to see.

`OWNER` is a system role, so it cannot be deleted, and nobody can remove `OWNER` from their own
account — the same guard `SUPER_ADMIN` has, and for the same reason: an owner who dropped it would
need another owner or a database to get it back, and there may be neither.

notification-service treats `OWNER`, `CEO` and `SUPER_ADMIN` alike as organisation-wide, so an
owner receives notifications from both companies.

> **When you add a permission, grant it to `OWNER` too.** Permissions are read from
> `role_permissions` everywhere in this system, so "every permission" is a list written at
> migration time, not a rule evaluated at runtime. A new permission is not retroactively included.
> `V13__owner_role.sql` says the same thing where somebody writing the next migration will see it.

A grant of `OWNER@JM` is a contradiction and nothing stops it — the `entity` column cannot express
"must be null". It would work, and it would silently narrow the owner to one company. Grant it
unscoped.

**`HR_LEAD` can now delete accounts.** It deliberately could not until 2026-08-28 — a lead could
hire but not fire — and that was wrong for how the team works: the lead who adds a recruiter is
the one who removes them when they leave. Deletion is soft and audited, and nobody can delete
their own account. Role grants can be narrowed to one company via
`entity`: **`JM` or `BP`**. Any other value is a 400; `entity: null` means every company.

### Why visibility is three permissions rather than one

The access token carries **permissions, not role names**. `HR@JM` and `HR_LEAD@JM` have identical
`entityScopes`, so a single `performance:read` plus the entity could not tell them apart — every
recruiter would have seen every colleague's figures. The breadth of what you can see therefore has
to be its own permission.

---

## 7. Error shape

Every error, from every endpoint, has the same body:

```json
{ "code": "invalid_request",
  "message": "username: must not be blank",
  "timestamp": "2026-08-26T13:50:21Z" }
```

Switch on `code`, show `message`. Codes in use: `invalid_credentials`, `too_many_attempts`,
`invalid_refresh_token`, `password_change_required`, `forbidden`, `unauthenticated`,
`invalid_request`, `not_found`, `conflict`, `method_not_allowed`, `unsupported_media_type`,
`unavailable`, `internal_error`.

| Status | When |
|---|---|
| 400 | Validation failure, malformed JSON, bad enum, `limit` or date range out of bounds |
| 401 | No token, expired token, revoked session, wrong credentials |
| 403 | Token lacks the permission — **or** it is password-change-scoped (`password_change_required`) |
| 404 | No such record, or an unmapped path |
| 409 | Username, role code, a RingCentral number or `mondayName` already taken; deleting a system role |
| 429 | Login rate limit (a `Retry-After` header says how many seconds), or RingCentral throttling `/activity` — the message says how long to wait |
| 503 | A downstream service, monday.com or RingCentral is unreachable |

Messages never contain stack traces, SQL or internal hostnames.

**`message` is specific when this service wrote it.** "month must look like 2026-08", "entity must
be JM or BP", "reason is required: send ?reason=... or a body of {"reason":"..."}", and the one
naming `/api/v1/activity/history` when a live range is too long — all reach the caller verbatim.
Show it.

Every rejection used to be flattened to the same four words, *"Request is not valid."*, on the
grounds that an exception message can name internal types. It can — but only when it came from a
library. A message this service wrote deliberately says which of five query parameters is wrong,
and hiding it left an integrator with nothing to go on. The two cases are now separate types, and
only the deliberate one is passed through; anything else still answers the generic text.

---

## 8. CORS

Allowed origins are an explicit list, default `http://localhost:3005`, set through
`ALLOWED_ORIGINS`. There is no wildcard — a frontend on any other port gets a CORS failure with no
useful error in the browser console.

Methods: `GET, POST, PUT, PATCH, DELETE, OPTIONS`. Headers: `Authorization`, `Content-Type`,
`X-Correlation-Id`. Credentials are allowed, but the API uses no cookies — keep the access token in
memory, not in `localStorage`.

**Exposed: `X-Correlation-Id`, `Content-Disposition`, `Retry-After`.** A header the browser
receives is invisible to JavaScript unless it is on this list. `Content-Disposition` carries the
monthly report's filename — without it the PDF saves itself as `pdf` — and `Retry-After` says how
long to wait after a 429.

**If you are using Next.js, prefer a rewrite** so the browser talks to its own origin and CORS
stops being a consideration at all:

```js
async rewrites() {
  return [{ source: '/api/:path*', destination: 'http://localhost:8443/api/:path*' }];
}
```

Without it, a relative `fetch('/api/v1/admin/users')` hits Next.js itself and returns its **404
HTML page** — which looks like a backend fault and is not one. The giveaway is
`X-Powered-By: Next.js` and `Content-Type: text/html`; this API always answers JSON.

---

## 9. External services

### Two companies, two of everything

**JM and BP are separate tenants of every external system.** Separate RingCentral accounts with
separate credentials and disjoint call logs; separate monday boards. A number that exists in JM
does not exist in BP, and looking one up in the wrong account does not fail — it finds nothing,
and the person comes back with every figure at zero and no sign of the mistake. That is the whole
reason the entity is carried on every request rather than inferred downstream.

**Which company a person belongs to** is the `entity` on their role grant — `HR@BP`, `HR_LEAD@JM`.
The gateway reads it from the account and sends it with the numbers. An account whose grants name
no company at all (a `SUPER_ADMIN`) falls back to `RC_DEFAULT_ENTITY`, which is `JM`.

| | JM | BP |
|---|---|---|
| RingCentral | `RC_CLIENT_ID` / `RC_CLIENT_SECRET` / `RC_JWT` | `RC_CLIENT_ID_BP` / `RC_CLIENT_SECRET_BP` / `RC_JWT_BP` |
| Numbers seen | `+1331…`, `+1312…` (Illinois) | `+1601…` (Mississippi) |
| monday board | `MONDAY_BOARD_ID` | `MONDAY_BOARD_ID_BP` (`1975563073`, "HR Process BP") |

Each RingCentral account has its **own rate limiter and its own cached token**, because the
ten-requests-a-minute budget is counted per account — one shared limiter would throttle JM for
traffic BP generated.

**Column ids are per board, not per account.** BP's board was copied from JM's, so `Source` and
`Date` kept their ids — but the termination column did not. A column id that is not on a board
reads as *empty* rather than as an error, so getting this wrong shows every BP driver as never
terminated. Overrides live under `drenix.monday.columns.<ENTITY>`:

| Column | JM | BP |
|---|---|---|
| Source (recruiter) | `color_mkra3nmw` | same |
| Hire date | `date_mkrbh5r0` | same |
| Termination date | `date_mm5gztc7` | **`date_mm5znbd9`** |

Adding a third company is configuration, not code: an entry under `drenix.ringcentral.accounts`,
one under `drenix.monday.boards`, column overrides if its board differs, and the entity added to
the `Entity` enum in `common.proto`.

### monday.com

One board per company and a personal API token, in `.env` — one token reads both boards today,
though `drenix.monday.tokens.<ENTITY>` can override it. Group titles and column ids are
configurable, and they are column **ids**, not titles: renaming a column in monday leaves its id
alone.

Only two groups are read: `Loaded` (currently working) and `Terminated` (left). The other six —
Rejected, Notifications, the priority buckets — are candidates and noise.

The board has rows the chart cannot fully account for: missing hire dates, missing termination
dates, and leaving dates that fall *before* the hire date. They are listed in `dataIssues` rather
than hidden. `MONDAY_UNKNOWN_TERMINATION` decides how a termination with no usable date is handled:

| Value | Effect |
|---|---|
| `USE_UPDATED_AT` (default) | the row's last-edit date. Honest but lumpy — a bulk tidy-up stamps every affected row with the same month, which is why one month can show an implausible spike of terminations. |
| `ASSUME_STILL_ACTIVE` | count the hire, leave them on the active line |
| `EXCLUDE_FROM_ACTIVE` | count the hire, keep them off the active line |

### The daily snapshot — why there is a database

RingCentral holds the call log, but it is not a source you can query. Ten "heavy" requests a
minute and a thousand rows a page mean a month takes minutes of throttled paging — acceptable once
a night, useless when somebody is waiting for a report.

So **every shift is written down as it happens**, into `daily_activity` in performance-service's
own database, and every question about a past range is answered from there. A month is one indexed
query.

| | |
|---|---|
| Runs | hourly, not on a nightly cron — one that fires while the service is restarting simply does not run that night, and the day is lost |
| Records | today's shift (refreshed each pass, so the evening is visible as it happens) and yesterday's |
| Backfills | up to `SNAPSHOT_DAYS` (7) days on a fresh deployment, then stops: a day already stored as `final` is never re-read |
| Stores | **counts only** — no verdicts |

**No target is stored with the counts.** The standard is editable, and a stored verdict would
freeze last month against a bar that has since moved; anybody comparing two months would be
comparing two different rulers. Judging happens at read time, so changing the standard re-judges
history.

**`GET /api/v1/activity/history` is how a client reads it.** The table existed and the monthly
report used it, but nothing exposed it over HTTP, so a dashboard asking for a month had only the
live endpoint — which read twenty thousand records through a ten-per-minute limit, overran the
three-minute call deadline and answered `503 RingCentral could not be reached` about a service
that was working. The live endpoint now refuses more than 7 days immediately and names this one.

Seven is measured, not guessed: a fortnight of **one** company was 11,551 records and 64 seconds
of throttled paging, so both companies took two minutes — inside the deadline and nowhere near
acceptable for a screen somebody is waiting on. A week finishes in about a minute.

| | `/activity` | `/activity/history` |
|---|---|---|
| Source | RingCentral, live | this system's database |
| Range | at most 7 days | any |
| Speed | seconds to minutes | milliseconds |
| Hour breakdown, clock windows | yes | no |
| Today | yes | only once the shift has finished and been recorded |

A person whose extension could not be resolved is **skipped, not stored as zero** — otherwise a
lookup failure becomes a permanent record of somebody having done nothing.

### The language model

`OPENAI_API_KEY` and nothing else is required; `OPENAI_MODEL` (default `gpt-4o-mini`) and
`OPENAI_BASE_URL` exist for pointing at a different or self-hosted endpoint. Without a key the
feedback endpoint answers 503 and says so, and nothing else on the service is affected.

Only totals and targets are sent. The prompt is built in `FeedbackWriter`, and the percentages are
computed in Java before it is assembled.

### RingCentral

Client id, client secret and a JWT credential **per company**, in `.env`. Each JWT is exchanged
for a one-hour access token, cached per account and renewed automatically. Both accounts need the
same scopes.

The app needs **`ReadCallLog`**, **`ReadMessages`** and **`ReadAccounts`**.

`ReadAccounts` is not optional. It is what `/restapi/v1.0/account/~/phone-number` needs, and that
call is how a number is matched to the extension that owns it. Without it the only way to identify
anybody is to find them in the call log, which means a recruiter who spent a shift sending messages
and made no calls cannot be found at all — their SMS is reported as zero rather than as unknown.
That was a real loss: one lead's second extension had 296 outbound messages counted as none.
If the scope is ever revoked the service logs a warning and falls back to call-log discovery, so
the report degrades rather than fails.

Two shapes to be aware of when changing this code: the **call log is account-wide** and paged, so
one query covers everybody; **messages are per extension only** — RingCentral exposes no
account-level message store — so SMS is fetched one extension at a time.

Calls are recognised **two ways**, and a call found by both is counted once. `extension` on a call
log row is the account extension it belongs to; `phoneNumber` is the caller id, and an extension
that dials under a shared company caller id shows a number nobody owns. Matching on both catches
that. Two numbers on one extension share a message store, so SMS is fetched once per *extension*,
never once per number — otherwise every message would be counted twice.

The number directory is cached for an hour, longer than the ten minutes the traffic caches get:
extensions are handed out when somebody joins, not through the day, and it competes for the same
ten-requests-a-minute budget as the call log.

Both endpoints sit in RingCentral's **"heavy"** rate-limit group: 10 requests per rolling 60
seconds, reported on every response as `x-rate-limit-limit` / `x-rate-limit-window`. `RateLimiter`
holds the client inside that budget, bursting freely until it is spent and only then waiting. A 429
that arrives anyway is retried once honouring `Retry-After`, then surfaces as `RESOURCE_EXHAUSTED`
and reaches the caller as HTTP 429. The budget is per *account*, so anything else using the same
credentials spends from the same allowance.

**Environment.** JM uses `RC_CLIENT_ID` / `RC_CLIENT_SECRET` / `RC_JWT`; BP uses the same three
with a `_BP` suffix. Neither is required to start — a deployment that has only brought one company
online serves that one, and asking for the other answers with a clear message rather than a
mystery. `RC_DEFAULT_ENTITY` (default `JM`) is the account used for anybody whose grants name no
company.

`RC_ZONE` (default `Asia/Tashkent`) decides which zone dates, shift windows and hour labels are
in; `RC_SHIFT` (default `PT9H`) and `RC_CALLS_PER_DAY` / `RC_TALK_PER_DAY` / `RC_SMS_PER_DAY` are
only a fallback standard for callers that reach performance-service directly — the gateway always
sends the database one, so changing these will not move what the API reports.

---

### Notifications — no external service

Everything notification-service reports on, it reads from inside: the audit log from user-service,
the activity report from performance-service.

**It pulls; nothing pushes to it.** user-service could call it every time it audits something, and
then a notification outage would fail user creation. Polling inverts that — the worst a broken
notification-service can do is fall behind, and it catches up by itself. The audit log is already
the durable record, so no queue is needed.

**Which actions produce a notification is an allowlist**, `AuditNotifier.NOTIFIED`. Anything not in
it produces nothing, whatever the audit log records — including a failed sign-in.

**A deletion names the account it deleted.** It used not to: the subject is looked up in the
directory, a deleted account is no longer in the directory, and the fallback was "then it must be
the actor" — so every deletion read *"IT Department deleted the account IT Department"*. The
fallback is gone. The name now comes from the username the audit entry recorded, and an
unresolvable subject routes to the people who see the whole organisation rather than being guessed
at from the actor's company.

**Company isolation lives in `Directory.supervisorsOf`.** The subject's companies decide who hears
about them, and an empty set — an administrator, who belongs to no company — resolves to *only* the
people who see the whole organisation, never to the leads. Returning true for the empty set was the
bug that put BP activity into JM inboxes; that line is where to look if it ever happens again.

**How it authenticates.** The generators run on a schedule, not inside anybody's request, so there
is no user token to forward. They authenticate as a *service*, with the mTLS certificate, and the
four RPCs they use are allowlisted for the `notification-service` peer specifically:

| RPC | On | Why |
|---|---|---|
| `UserService/ListUsers` | user-service | who supervises whom |
| `UserService/ListAudit` | user-service | the actions to report |
| `SettingsService/GetActivityStandard` | user-service | the bar to judge against |
| `PerformanceService/GetHrActivity` | performance-service | what people actually did |

All four are **reads**. A peer-authenticated call carries no user, so there is nobody whose
permissions could bound a write and nobody to name in the audit log for it — which is why this
service writes nothing anywhere but its own database.

**Two schedules**, both idempotent:

| Generator | Runs | Watermark |
|---|---|---|
| audit poller | every 60s | the highest audit id already seen |
| performance check | every hour, judging only shifts that have finished | the last shift date judged |

Hourly rather than a daily cron: a daily cron that fires while the service happens to be
restarting simply does not run that day. Checking hourly and skipping shifts already judged
produces the same one notification per shift and recovers on its own. Catch-up after an outage is
capped at `NOTIFY_MAX_CATCH_UP_DAYS` (3), so a service off for a month does not fill every inbox on
the morning it returns.

Every notification carries a `dedupe_key` — the audit id, or the shift date plus the person —
unique per recipient. Both generators are therefore free to run again over ground they have
covered; a restart mid-batch replays and writes nothing twice.

**Environment.** `NOTIFICATION_DB_PASSWORD` is required. `RC_ZONE` must match performance-service,
or the shift window judges hours nobody worked and everybody fails. `NOTIFY_AUDIT_INTERVAL`,
`NOTIFY_PERFORMANCE_INTERVAL`, `NOTIFY_SHIFT_START`, `NOTIFY_SHIFT_END` and
`NOTIFY_MAX_CATCH_UP_DAYS` tune the rest.

**Service databases are created on a fresh volume only.** `deploy/postgres-init/` runs when
Postgres initialises and never afterwards, so an installation that already had a volume needs the
roles and databases created by hand — once each:

```bash
docker compose --env-file .env -f deploy/docker-compose.yml exec postgres-identity psql -U identity_app -d drenix_identity -c "CREATE ROLE notification_app LOGIN PASSWORD 'FROM-YOUR-ENV';" -c "CREATE DATABASE drenix_notification OWNER notification_app;" -c "CREATE ROLE performance_app LOGIN PASSWORD 'FROM-YOUR-ENV';" -c "CREATE DATABASE drenix_performance OWNER performance_app;"
```

---

## 10. Known local issues

**The Postgres volume resets by itself.** This has happened several times: `admin` returns to its
bootstrap password with `mustChangePassword: true` and any accounts created since are gone. It
coincides with Docker Desktop restarting. If credentials suddenly stop working:

```bash
docker exec drenix-backend-postgres-identity-1 psql -U identity_app -d drenix_identity -c "SELECT username, must_change_password, failed_attempts FROM users;"
```

Sign in with `BOOTSTRAP_ADMIN_PASSWORD` from `.env` again. Not diagnosed; not a code fault.

**Swagger UI is reachable without a token**, which also means it publishes every endpoint and the
permission each one needs. Set `API_DOCS_ENABLED=false` on anything internet-facing; the paths then
return 404.

**There are no tests.** Every change is verified by hand against a running stack.

---

## 11. Repository layout

| Module | Contains |
|---|---|
| `contracts` | The `.proto` files. The only contract between services. No hand-written Java. |
| `platform-security` | Token verification, gRPC interceptors, TLS setup, permission constants. |
| `user-service` | Users, roles, permissions, audit, the activity standard. PostgreSQL + Flyway (V1–V13). |
| `auth-service` | Login, token issuing, refresh rotation, sessions. Redis. |
| `performance-service` | monday.com, RingCentral and OpenAI clients, the chart arithmetic, the daily snapshot. Its own PostgreSQL database + Flyway (V1). |
| `notification-service` | The two notification generators and the inbox. Its own PostgreSQL database + Flyway (V1). |
| `edge-gateway` | REST controllers, Swagger, the public security chain, visibility rules, the PDF renderer and the markdown-to-PDF formatter the written report goes through. |

**Deployment lives in [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md)** — the server runbook, nginx,
TLS, CI/CD, backups and the firewall. Read its first section before anything else: it explains
why `https://performance.drenix.uz` needs public ports 80 and 443, and what happens if only 8443
is forwarded.

| File | What |
|---|---|
| `deploy/docker-compose.yml` | the topology. Development and production both use it. |
| `deploy/docker-compose.dokploy.yml` | **what the server deploys.** Self-contained: Traefik reaches the gateway over `dokploy-network`, nothing publishes a host port, and a one-shot `tls-init` service creates the internal mTLS material into a volume — `deploy/tls/` is gitignored, so a git clone arrives without it. |
| `deploy/docker-compose.prod.yml` | the host-nginx overlay: restart policy, health checks, bounded logs, docs off. Applied *on top of* the base file. Not used under Dokploy. |
| `deploy/nginx/performance.drenix.uz.conf` | the site. `/` to the frontend, `/api/` to the gateway. |
| `deploy/nginx/drenix-limits.conf` | upstreams and rate-limit zones — must sit in `conf.d`, because these directives are only valid in `http{}`. |
| `deploy/nginx/security-headers.conf` | the security headers, as a snippet. nginx *replaces* rather than merges `add_header` across levels, so any location setting one of its own must include this or it silently loses HSTS and the rest. |
| `scripts/deploy.sh` | build, tag a rollback point, start, wait for `/api/health`, roll back if it never answers. |
| `scripts/rollback.sh` | put the previous images back. Does not touch the database. |
| `scripts/backup-db.sh` | dump all three databases, verify each dump, prune by age. |
| `.github/workflows/deploy.yml` | build and verify on every push; deploy only from `main`. |

Build order is `contracts` → `platform-security` → the services; Maven works it out. To rebuild one
service alone:

```bash
mvn -pl user-service -am -DskipTests package
```

### Things that will trip you up in this codebase

**Adding a module means editing `deploy/Dockerfile` too.** It copies modules one by one, and a
module in the root `pom.xml` that is missing there breaks the Docker build of *every* service —
while `mvn install` on the host still passes.

**Generated protobuf sources live in `target/generated-sources/protobuf-java` and
`protobuf-grpc-java`**, flattened on purpose. The plugin's default nests them one level deeper than
IDEs expect, which leaves every `uz.drenix.identity.grpc.v1` import red in the editor while Maven
compiles fine. Do not "tidy" that back.

**Internal TLS is pinned to the JDK provider** (`InternalTls`), not the BoringSSL build bundled
with grpc-netty. The native path cannot find an EC key through the JDK key manager on this JDK and
aborts every handshake with a bare `TLSV1_ALERT_INTERNAL_ERROR`.

**Certificates are ECDSA P-256, not Ed25519.** Netty reads PEM private keys as RSA, DSA or EC only.
Access tokens are still Ed25519 — that path goes through nimbus and Tink, which have no such limit.

**Spring Boot 4 split auto-configuration into per-technology modules.** `flyway-core` without
`spring-boot-flyway` silently runs no migrations; `RestClient.Builder` has no bean unless its module
is present, which is why the RingCentral and monday clients call `RestClient.builder()` directly. If
a library seems present but inert, look for the missing `spring-boot-*` companion.

**A token always wins over the tokenless allowlist** in `AuthServerInterceptor`. A few RPCs may be
called without a user token — login, and the permission re-read during a refresh — authorised by the
mTLS peer certificate instead. That list is a *fallback*, checked only when no token was sent.
Reading it first made the gateway's forwarded token invisible and refused it for not being
auth-service.

**Role assignment diffs rather than clearing.** Hibernate orders INSERTs before DELETEs in one
flush, so removing every grant and re-adding collides with `user_roles_unique_idx` whenever a role
is being kept. It also preserves `granted_at` on unchanged grants.

---

## 12. Not built

MFA, signing-key rotation automation, linking monday recruiter names to accounts on the
`/performance` endpoint (`userId` is always null there), scoping `/performance` the way `/activity`
is scoped, and the business domain beyond these two charts.
