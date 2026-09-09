# Deploying to performance.drenix.uz

Read section 1 before anything else. There is a networking prerequisite that no amount of
configuration on this side can work around, and it applies to both paths below.

## Which path are you on?

**Dokploy** — what this server actually runs. Section 1, then **section 1a**. Dokploy owns
Traefik on 80/443, obtains the certificates, and deploys from GitHub. Sections 3.7 to 3.10 and 7
do **not** apply: do not install nginx, do not run certbot, do not run `scripts/deploy.sh`.

**Host nginx + compose** — the fallback, kept in the repository in case Dokploy is dropped later.
Sections 3 through 10 in order. Ignore section 1a.

Nothing below mixes the two. Two reverse proxies competing for port 443 is the failure that looks
like DNS.

---

## 1. The port problem — read this first

You have been given **93.188.81.14:8443**. That is not enough for `https://performance.drenix.uz`.

A browser asked for `https://performance.drenix.uz` connects to **TCP 443**. There is no DNS
record type that carries a port; an `A` record maps a name to an address and nothing more. If 443
is not reachable, the only URL that will ever work is `https://performance.drenix.uz:8443`, with
the port typed by hand — which is what you said you do not want.

**What has to be true:**

| Public | must reach | why |
|---|---|---|
| `93.188.81.14:443` | the server's port 443 | so the URL works without a port |
| `93.188.81.14:80` | the server's port 80 | Let's Encrypt's HTTP-01 challenge, at issue and at every renewal |

If 8443 is the only port forwarded to this machine, ask whoever controls the firewall or NAT for
**80 and 443**. The mapping may be either of these:

```
public 80  -> server 80     (nginx)
public 443 -> server 443    (nginx)
```

or, if the outer device insists on a different internal port:

```
public 443 -> server 8443
```

— but in that second case **nginx** must be the thing listening on 8443, not the gateway, and the
gateway must move to another loopback port. Everything in this repository assumes the first
mapping, which is the normal one. Say the word if you get the second and I will adjust the config.

**If port 80 genuinely cannot be opened**, HTTP-01 validation is impossible and you need DNS-01
instead — a TXT record written by an API token for the `drenix.uz` zone. That works and is not
harder, but it needs credentials for your DNS provider, which I do not have. Section 5 covers the
normal path; ask and I will write the DNS-01 variant.

**Do not run certbot before 80 and 443 reach this machine.** It will fail, and Let's Encrypt rate
limits failed authorisations — five per hostname per hour.

---

## 1a. Dokploy

```
Internet ─80/443─> Traefik (Dokploy) ─┬─ /api ─> edge-gateway:8443   ─┐
                                      └─ /    ─> frontend, added later │ dokploy-network
                                                                       │
   drenix-internal ── user, auth, performance, notification ── postgres, redis
```

The gateway is the only container on both networks. Nothing publishes a host port at all, so the
firewall has nothing to get wrong: Postgres, Redis and the four gRPC services are unreachable
even from Traefik.

### Step 1 — GitHub

Dokploy deploys from a git clone. There is no other way in.

```bash
git init && git add -A
git status --short | grep -E '\.env|deploy/tls' && echo "STOP: a secret is staged" || echo "clean"
```

The grep must find nothing. If it does, stop: a secret that reaches a remote is public from that
moment and has to be **rotated**, not deleted.

```bash
git commit -m "Initial commit"
git branch -M main
git remote add origin git@github.com:<owner>/<repo>.git
git push -u origin main
```

Make the repository **private**.

### Step 2 — the application in Dokploy

Create an application of type **Compose**, connected to the repository, branch `main`.

| Field | Value |
|---|---|
| Compose path | `deploy/docker-compose.dokploy.yml` |
| Auto Deploy | **off** — GitHub Actions triggers the deployment after the build passes |

### Step 3 — environment

Paste every variable from `.env.example` into Dokploy's **Environment** tab. Dokploy stores them
encrypted; they never enter the repository.

Generate the passwords rather than inventing them:

```bash
openssl rand -base64 36            # each database password, and REDIS_PASSWORD
node scripts/gen-signing-key.mjs   # AUTH_SIGNING_KEY
```

Set `ALLOWED_ORIGINS=https://performance.drenix.uz`. Leave `API_DOCS_ENABLED` unset — the compose
file defaults it to false.

You do **not** need to generate the internal mTLS certificates by hand. `deploy/tls/` is
gitignored, so a git clone arrives without them; the `tls-init` service in the compose file
creates them into a Docker volume on the first deployment and skips the work on every one after.

### Step 4 — the domain

| Field | Value |
|---|---|
| Host | `performance.drenix.uz` |
| Path | `/api` |
| Service | `edge-gateway` |
| Container port | `8443` |
| HTTPS | on, Let's Encrypt |

**Strip Path must be OFF.** This is the single most likely thing to go wrong. The backend already
serves its routes under `/api` — the real paths are `/api/v1/auth/login` and `/api/health`.
Stripping the prefix turns `/api/v1/auth/login` into `/v1/auth/login` and every request 404s.

### Step 5 — deploy and check

Deploy from the Dokploy UI the first time, then:

```
https://performance.drenix.uz/api/health     ->  {"status":"UP"}
```

A 404 means Strip Path is on. A 502 means the gateway has not finished starting — the first build
compiles five Java modules and takes several minutes; read the deployment log.

### Step 6 — the first administrator

The bootstrap admin is created on first start from `BOOTSTRAP_ADMIN_PASSWORD`, with
`mustChangePassword: true`, so that password survives exactly one login. There is no `admin/admin`
anywhere in this system.

1. Sign in as `admin`, change the password immediately.
2. Create the real accounts; grant `OWNER` to the business owner, unscoped.
3. **Delete `BOOTSTRAP_ADMIN_PASSWORD` from the Dokploy environment** and redeploy. It is read
   only when no user exists; leaving it is a credential on disk with no purpose.

### Step 7 — CI

Section 6. One secret, `DOKPLOY_WEBHOOK_URL`.

### Backups under Dokploy

`scripts/backup-db.sh` still works — it talks to the Postgres container by name. The container is
named by Dokploy rather than by the base compose file, so find it and pass it in:

```bash
docker ps --format '{{.Names}}' | grep postgres
PG_CONTAINER=<that-name> BACKUP_DIR=/srv/backups ./scripts/backup-db.sh
```

Dokploy also has its own scheduled-backup feature for a database service; either is fine, but do
one of them, and copy the dumps off the machine.

---

## 2. What runs where — the host-nginx path

> Sections 2 to 10 describe the **fallback**, not what this server runs. On Dokploy, stop at 1a.

```
Internet
   │
   ├─ 80  ──> nginx ── redirect to 443, and the ACME challenge
   └─ 443 ──> nginx ──┬── /       ──> frontend   127.0.0.1:3000
                      └── /api/   ──> gateway    127.0.0.1:8443
                                          │
                                          │ gRPC over mTLS, private docker network
                                          ├──> auth-service        9092
                                          ├──> user-service        9090
                                          ├──> performance-service 9094
                                          └──> notification-service 9096
                                                      │
                                          postgres 5432   redis 6379
                                          (neither published at all)
```

Only nginx accepts a connection from off the machine. The gateway binds `127.0.0.1:8443`, so even
a firewall mistake cannot expose it. Postgres and Redis are on the compose network with no host
port whatsoever.

**nginx runs on the host, not in a container.** Two reasons. Certbot's nginx plugin edits the site
file and reloads the service in place, which is the least error-prone renewal there is; in a
container it becomes a volume-mount and reload-signal problem to re-solve on every server. And
nginx has to serve the frontend, which is built by a different repository — the host is where the
two meet.

---

## 3. First deployment on a fresh server

Everything below is run as a normal user with sudo, not as root.

### 3.1 Base packages

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y ca-certificates curl git nginx ufw fail2ban unattended-upgrades
```

### 3.2 Docker

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker      # survives reboot; containers restart with it
sudo usermod -aG docker "$USER"         # log out and back in for this to take effect
docker compose version                  # expect v2.x
```

`systemctl enable docker` plus `restart: unless-stopped` in the compose overlay is what makes the
application come back after a reboot. Verify it before you trust it — section 9.

### 3.3 The firewall — SSH first, always

**Allow SSH before enabling ufw.** Enabling it first locks you out of the machine, and there is no
way back in without console access.

```bash
sudo ufw allow OpenSSH               # or: sudo ufw allow 22/tcp
# If sshd listens on a non-standard port, allow THAT port, not 22:
#   sudo ufw allow 2222/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable
sudo ufw status verbose
```

Check `sudo ss -tlnp | grep sshd` first to see which port sshd is actually on. Do not allow 8443:
nothing outside the machine needs it, and the gateway is bound to loopback regardless.

> Docker publishes ports by writing iptables rules that bypass ufw. That is why every published
> port in this deployment is bound to `127.0.0.1` explicitly — a `0.0.0.0` binding would be
> reachable from the internet even with ufw denying it.

### 3.4 The code

```bash
sudo mkdir -p /srv/drenix && sudo chown "$USER":"$USER" /srv/drenix
git clone <your-repository-url> /srv/drenix/backend
cd /srv/drenix/backend
```

### 3.5 Secrets and internal certificates

```bash
cp .env.example .env
chmod 600 .env
$EDITOR .env
```

Fill in every blank. Generate the passwords rather than inventing them:

```bash
openssl rand -base64 36        # once per database password and for REDIS_PASSWORD
node scripts/gen-signing-key.mjs   # AUTH_SIGNING_KEY
```

Set `ALLOWED_ORIGINS=https://performance.drenix.uz` and leave `API_DOCS_ENABLED` unset (the
production overlay defaults it to false).

Then the internal mTLS mesh — the services refuse to talk to each other without it:

```bash
./scripts/gen-certs.sh
chmod 700 deploy/tls && chmod 600 deploy/tls/*.key
```

### 3.6 Start the backend

```bash
chmod +x scripts/*.sh
./scripts/deploy.sh
curl -s http://127.0.0.1:8443/api/health     # expect {"status":"UP"}
```

### 3.7 nginx, before the certificate exists

The site file references certificate paths that do not exist yet, so nginx will refuse to start
if you enable it now. Bring up port 80 alone first, get the certificate, then enable the full
site.

```bash
sudo mkdir -p /var/www/certbot /etc/nginx/snippets
sudo cp deploy/nginx/drenix-limits.conf    /etc/nginx/conf.d/drenix-limits.conf
sudo cp deploy/nginx/security-headers.conf /etc/nginx/snippets/drenix-security-headers.conf

# A temporary port-80-only site, just for the challenge.
sudo tee /etc/nginx/sites-available/performance-bootstrap >/dev/null <<'EOF'
server {
    listen 80;
    server_name performance.drenix.uz;
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / { return 200 "bootstrap\n"; default_type text/plain; }
}
EOF
sudo rm -f /etc/nginx/sites-enabled/default
sudo ln -sf /etc/nginx/sites-available/performance-bootstrap /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl restart nginx
```

Prove it is reachable **from outside** before going further:

```bash
curl -I http://performance.drenix.uz/          # from your laptop, not the server
```

If that does not return 200, port 80 is not forwarded and certbot will fail. Stop here and fix
the NAT — section 1.

### 3.8 The certificate

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot certonly --webroot -w /var/www/certbot \
     -d performance.drenix.uz \
     --email <your-email> --agree-tos --no-eff-email
```

`certonly --webroot` rather than `--nginx`: it writes only the certificate and leaves the config
alone, so the site file in this repository stays the source of truth.

### 3.9 The real site

```bash
sudo rm -f /etc/nginx/sites-enabled/performance-bootstrap
sudo cp deploy/nginx/performance.drenix.uz.conf /etc/nginx/sites-available/performance.drenix.uz
sudo ln -sf /etc/nginx/sites-available/performance.drenix.uz /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

`nginx -t` must print `syntax is ok` and `test is successful`. It will not if the frontend
upstream in `drenix-limits.conf` points at a port nothing is listening on — that is fine at this
stage, nginx only fails on a bad upstream at request time, not at config test.

### 3.10 Renewal

Certbot installs a systemd timer on Debian and Ubuntu. Confirm it, and make nginx pick up the new
certificate without a manual reload:

```bash
systemctl list-timers | grep certbot          # expect certbot.timer
sudo mkdir -p /etc/letsencrypt/renewal-hooks/deploy
sudo tee /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh >/dev/null <<'EOF'
#!/bin/sh
systemctl reload nginx
EOF
sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh

# Rehearse it. --dry-run talks to the staging service and changes nothing.
sudo certbot renew --dry-run
```

A certificate is renewed at 30 days remaining. If `--dry-run` passes, renewal will work; if it
fails, it will fail silently in 60 days, which is why you rehearse it now.

---

## 4. The frontend

The frontend is not in this repository, so this configuration cannot build it. Two shapes are
supported; pick one and tell me which, because `drenix-limits.conf` currently assumes the first.

**A process on 127.0.0.1:3000** — Next.js `next start`, or any node server. Nothing to change:
`upstream drenix_frontend` already points there. Run it under systemd or as another compose
service; do not leave it in a terminal.

**A static build** — Vite, CRA, `next export`. Then nginx serves the files itself and the proxy
is wrong. Replace `location /` in the site file with:

```nginx
location / {
    root /srv/drenix/frontend/dist;
    try_files $uri $uri/ /index.html;      # SPA routes: /login and /admin are not files
}
```

and delete the `upstream drenix_frontend` block. `try_files` is what stops `/admin` returning 404;
`/api/` is matched by a longer prefix, so it never falls through to `index.html`.

---

## 5. Routing — why the trailing slash matters

The backend serves its routes **under `/api` already**: `/api/v1/auth/login`, `/api/health`. So
nginx must pass the path through untouched.

```nginx
proxy_pass http://drenix_backend;      # correct   /api/v1/x -> /api/v1/x
proxy_pass http://drenix_backend/;     # WRONG     /api/v1/x -> /v1/x   (404)
```

There is no trailing slash in the site file. Do not add one.

---

## 6. CI/CD

**This repository is not under git yet.** Nothing in `.github/` runs until:

```bash
git init && git add -A && git commit -m "Initial commit"
git branch -M main
git remote add origin git@github.com:<owner>/<repo>.git
git push -u origin main
```

Before that first push, confirm `git status --short` does **not** list `.env`, `.env.backup` or
anything under `deploy/tls/`. `.gitignore` covers them now, but check — a secret pushed to a
remote is public from that moment and must be rotated, not deleted.

### Required GitHub secret — one

Settings → Secrets and variables → Actions:

| Secret | What |
|---|---|
| `DOKPLOY_WEBHOOK_URL` | the whole deploy URL from Dokploy, token and all |

Find it in Dokploy on the application, under **Deployments** — it is the URL labelled for
webhooks or auto-deploy. The token is in the URL, so the entire string is the secret.

No SSH key, no server host, no deploy path. CI never touches the server: it asks Dokploy to
deploy, and Dokploy does the rest from its own clone of the repository. One credential to rotate
instead of five, and a leaked webhook can only trigger a deployment of code that is already in
`main` — it cannot run a command.

**Turn Dokploy's own "Auto Deploy" OFF** on the application. Left on, Dokploy deploys on every
push whether the build passed or not, and this workflow becomes decoration.

### What happens on a push to `main`

1. **Build job.** `mvn verify`; both compose files validated; the nginx fallback validated in a
   container; the shell scripts parsed; a committed `.env` or private key refused.
2. **Deploy job** — main only, never a pull request — POSTs to the Dokploy webhook.
3. **Dokploy** clones, builds the five Java images, and replaces the containers. It keeps the
   previous ones, so a build that fails there leaves the running release alone.
4. CI waits two minutes, then polls `https://performance.drenix.uz/api/health` for up to ten more.

`concurrency: production-deploy` means a second push waits for the first.

> **What the health check proves, and what it does not.** It proves the API is reachable and
> alive through DNS, Traefik and TLS. It does **not** prove the containers are built from this
> commit — `/api/health` reveals no version on purpose, so a pass against the previous release
> looks identical. Dokploy's deployment log is the authority on what is live. If you want CI to
> assert the commit instead, `/api/health` can carry a short build id and this step can compare
> it against `github.sha`; ask for it. It was left out because this endpoint is the one thing on
> the internet that answers without a token, so anything it says is said to everybody.

---

## 7. Updating, and rolling back

```bash
# Normal update: push to main, or on the server:
cd /srv/drenix/backend && git pull && ./scripts/deploy.sh

# Undo the last release:
./scripts/rollback.sh
```

`rollback.sh` restores the previous images. **It does not touch the database.** Flyway migrations
that have committed stay committed — which is harmless for an additive migration and is not
harmless for one that drops or renames. Before deploying anything destructive:

```bash
./scripts/backup-db.sh
```

and restore with the command that script prints.

Nightly backups:

```bash
crontab -e
# 03:15 every day, log where you will look for it
15 3 * * * cd /srv/drenix/backend && ./scripts/backup-db.sh >> /var/log/drenix-backup.log 2>&1
```

Dumps go to `./backups`, mode 600, kept 14 days. **Copy them off this machine** — a backup that
only exists on the server it protects is not a backup.

---

## 8. Logs

| What | Where |
|---|---|
| One service | `docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml logs -f edge-gateway` |
| Everything | the same without a service name |
| nginx access | `/var/log/nginx/performance.access.log` |
| nginx errors | `/var/log/nginx/performance.error.log` |
| Deployments | the GitHub Actions run, or the terminal you ran `deploy.sh` in |
| Backups | `/var/log/drenix-backup.log` |

Container logs are capped at 10 files × 20MB each by the production overlay. nginx logs are
rotated by the `logrotate` config Debian ships with the package — verify with
`cat /etc/logrotate.d/nginx`.

The application never logs a password, a token or an `Authorization` header. Audit entries record
who did what and from which IP, by design.

---

## 9. Verify the server actually survives a reboot

Do this once, on purpose, before you rely on it:

```bash
sudo reboot
# wait, then from your laptop:
curl -s https://performance.drenix.uz/api/health     # {"status":"UP"}
```

If it does not come back: `systemctl is-enabled docker` should say `enabled`, and
`docker compose ... ps` should show every service `Up`.

---

## 10. Hardening beyond the firewall

```bash
# SSH: keys only, no root login. Confirm your key works in a SECOND terminal before you
# disconnect the first — a typo here locks you out.
sudo sed -i 's/^#*PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo sed -i 's/^#*PermitRootLogin.*/PermitRootLogin no/'                /etc/ssh/sshd_config
sudo sshd -t && sudo systemctl reload ssh

# fail2ban: bans an address after repeated SSH failures.
sudo systemctl enable --now fail2ban
sudo fail2ban-client status sshd

# Unattended security updates.
sudo dpkg-reconfigure -plow unattended-upgrades

# File permissions on the deployment.
chmod 600 /srv/drenix/backend/.env
chmod 700 /srv/drenix/backend/deploy/tls
chmod 600 /srv/drenix/backend/deploy/tls/*.key
```

The Docker socket is not mounted into any container in this deployment, and every application
container runs as uid 10001 — both already true in the Dockerfile and compose files.

---

## 11. After the first deployment

The bootstrap admin is created on first start from `BOOTSTRAP_ADMIN_PASSWORD`, with
`mustChangePassword: true`, so the password you typed survives exactly one login. There is no
`admin/admin` and no default password anywhere in this system.

1. Sign in as `admin`, change the password immediately.
2. Create the real accounts and grant `OWNER` to the business owner.
3. **Remove `BOOTSTRAP_ADMIN_PASSWORD` from `.env`** and redeploy. It is only read when no admin
   exists; leaving it is a credential on disk with no purpose.
