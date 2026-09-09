# Dokploy'da ishga tushirish — qadamlar

Tartib bo'yicha bajaring. Har qadamning oxirida tekshiruv bor: o'tmasa keyingisiga o'tmang,
chunki keyingi qadam avvalgisiga tayanadi.

To'liq ma'lumot `DEPLOYMENT.md` da. Bu — nima qilish kerakligi.

---

## 0. Portlarni oching — busiz qolgani behuda

**Bu eng birinchi ish.** Qolgan hammasini qilib qo'yib, oxirida domen ishlamasligini bilib
olishdan ko'ra, hozir hal qilgan yaxshi.

Brauzer `https://performance.drenix.uz` so'raganda **443**-portga ulanadi. DNS `A` yozuvi port
tashimaydi — bunday yozuv turi umuman yo'q. Sizda faqat 8443 ochiq bo'lsa, yagona ishlaydigan
manzil `https://performance.drenix.uz:8443` bo'lib qoladi.

Kerak:

| Port | Nima uchun |
|---|---|
| **443** | domen portsiz ochilishi uchun |
| **80** | Let's Encrypt tekshiruvi — birinchi olishda **va har 60 kunda yangilashda** |

Firewall/NAT'ni boshqaradigan odamdan shu ikkitasini so'rang.

**Tekshirish** — o'z kompyuteringizdan, serverdan emas:

```bash
curl -I --max-time 10 http://93.188.81.14/
curl -I --max-time 10 https://93.188.81.14/ -k
```

Ikkalasi ham javob bersa (xato sahifasi bo'lsa ham — muhimi *ulanish* bo'lsa) portlar ochiq.
`Connection timed out` chiqsa — hali yopiq.

Serverda ufw bo'lsa:

```bash
sudo ss -tlnp | grep sshd          # SSH qaysi portda ekanini ko'ring
sudo ufw allow <o'sha SSH porti>/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable
```

> **SSH'ni birinchi ruxsat bering.** Avval ufw'ni yoqsangiz serverdan chiqib ketasiz va
> konsolsiz qaytib kira olmaysiz.

---

## 1. DNS

`performance.drenix.uz → 93.188.81.14` (A yozuvi).

```bash
nslookup performance.drenix.uz
```

`93.188.81.14` qaytishi kerak. Yangi qo'shgan bo'lsangiz tarqalishi bir necha soat olishi mumkin.

---

## 2. Credentiallarni almashtiring

Quyidagilar suhbatda ochiq yozilgan edi, ya'ni ular endi ishonchli emas. **Yangisini oling:**

- monday.com API token
- RingCentral JM: Client ID, Client Secret, JWT
- RingCentral BP: Client ID, Client Secret, JWT
- OpenAI API key

Faylni o'chirish yetarli emas — token bir marta ko'ringandan keyin almashtirilishi kerak.

Bazalar va Redis uchun **yangi parollar** yarating (eskisi lokal edi, serverda boshqa bo'lsin):

```bash
openssl rand -base64 36     # IDENTITY_DB_PASSWORD
openssl rand -base64 36     # PERFORMANCE_DB_PASSWORD
openssl rand -base64 36     # NOTIFICATION_DB_PASSWORD
openssl rand -base64 36     # REDIS_PASSWORD
openssl rand -base64 24     # BOOTSTRAP_ADMIN_PASSWORD (kamida 12 belgi)
```

Token imzolash kaliti:

```bash
node scripts/gen-signing-key.mjs     # AUTH_SIGNING_KEY — bitta qatorli JSON
```

Hammasini vaqtincha bir joyga yozib qo'ying — 4-qadamda Dokploy'ga kiritasiz. **`.env` fayliga
yozmang va hech qayerga commit qilmang.**

---

## 3. GitHub

Dokploy kodni faqat git klon orqali oladi.

```bash
cd C:\Users\recruit5\IntelliJProjects\drenix-backend
git init
git add -A
```

**Push'dan oldin tekshiring:**

```bash
git status --short | grep -E "\.env|deploy/tls|\.pdf"
```

Bu buyruq **hech narsa chiqarmasligi kerak**. Nimadir chiqsa — to'xtang va menga ayting.

```bash
git commit -m "Initial commit"
git branch -M main
git remote add origin git@github.com:<foydalanuvchi>/<repo>.git
git push -u origin main
```

Repozitoriyani GitHub'da **Private** qiling.

---

## 4. Dokploy — application yaratish

Dokploy panelida:

1. **Create Application** → turi **Compose**
2. **Provider: GitHub** → repozitoriyani ulang → branch **`main`**
3. **Compose Path:**
   ```
   deploy/docker-compose.dokploy.yml
   ```
4. **Auto Deploy: OFF** ← muhim. Yoqiq qolsa, Dokploy build o'tgan-o'tmaganiga qaramay har
   push'da deploy qiladi va GitHub Actions'dagi tekshiruv ma'nosiz bo'ladi.

---

## 5. Dokploy — Environment

**Environment** tabiga 22 ta o'zgaruvchini kiriting. Dokploy ularni shifrlab saqlaydi.

```
IDENTITY_DB_PASSWORD=<2-qadamdan>
PERFORMANCE_DB_PASSWORD=<2-qadamdan>
NOTIFICATION_DB_PASSWORD=<2-qadamdan>
REDIS_PASSWORD=<2-qadamdan>
BOOTSTRAP_ADMIN_PASSWORD=<2-qadamdan>
AUTH_SIGNING_KEY=<2-qadamdan, bitta qator>

ALLOWED_ORIGINS=https://performance.drenix.uz
API_DOCS_ENABLED=false

MONDAY_TOKEN=<yangi token>
MONDAY_BOARD_ID=2046464283
MONDAY_BOARD_ID_BP=1975563073

RC_CLIENT_ID=<yangi>
RC_CLIENT_SECRET=<yangi>
RC_JWT=<yangi>
RC_CLIENT_ID_BP=<yangi>
RC_CLIENT_SECRET_BP=<yangi>
RC_JWT_BP=<yangi>

RC_DEFAULT_ENTITY=JM
RC_ZONE=Asia/Tashkent
SNAPSHOT_DAYS=7

OPENAI_API_KEY=<yangi>
OPENAI_MODEL=gpt-4o-mini
```

Ichki mTLS sertifikatlarini **qo'lda yaratish shart emas** — compose ichidagi `tls-init` servisi
birinchi deploy'da o'zi yaratadi.

---

## 6. Dokploy — Domain

**Domains** tabida qo'shing:

| Maydon | Qiymat |
|---|---|
| Host | `performance.drenix.uz` |
| Path | `/api` |
| Service | `edge-gateway` |
| Container Port | `8443` |
| HTTPS | yoqilgan |
| Certificate | Let's Encrypt |
| **Strip Path** | **O'CHIQ** |

> **Strip Path — eng ehtimolli xato.** Backend yo'llarini allaqachon `/api` ostida beradi:
> haqiqiy manzillar `/api/v1/auth/login`, `/api/health`. Prefiks kesilsa `/v1/auth/login` bo'ladi
> va **hamma so'rov 404 qaytaradi**.

---

## 7. Birinchi deploy

Dokploy'da **Deploy** tugmasini bosing va **deployment log**ini oching.

Birinchi build uzoq: beshta Java moduli manbadan kompilyatsiya qilinadi — **5–15 daqiqa**.
Sabr qiling, log'ni kuzating.

**Tekshirish:**

```bash
curl https://performance.drenix.uz/api/health
```

Kutilayotgan javob:

```json
{"status":"UP"}
```

Xato bo'lsa:

| Ko'ringan narsa | Sabab |
|---|---|
| **404** | Strip Path yoqilgan. 6-qadamga qayting, o'chiring. |
| **502 / 503** | Gateway hali ko'tarilmagan yoki yiqilgan. Dokploy → Logs → `edge-gateway`. |
| Sertifikat xatosi | 80-port yopiq, Let's Encrypt sertifikat bera olmagan. 0-qadam. |
| Ulanmadi | DNS yoki 443-port. 0 va 1-qadam. |

---

## 8. Birinchi administrator

Tizimda `admin/admin` degan narsa yo'q. Bootstrap admin faqat birinchi ishga tushishda,
`BOOTSTRAP_ADMIN_PASSWORD` dan yaratiladi va **parolni majburan almashtiradi**.

```bash
curl -X POST https://performance.drenix.uz/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<BOOTSTRAP_ADMIN_PASSWORD>"}'
```

Javobda `"passwordChangeRequired": true` bo'ladi. Keyin:

1. Parolni almashtiring (`POST /api/v1/auth/password`) yoki frontend orqali kiring.
2. Haqiqiy akkauntlarni yarating. Biznes egasiga **`OWNER`** rolini **entity'siz** bering.
3. **`BOOTSTRAP_ADMIN_PASSWORD` ni Dokploy Environment'idan o'chiring** va qayta deploy qiling.
   U faqat foydalanuvchi yo'q bo'lganda o'qiladi — qoldirish keraksiz credential.

---

## 9. GitHub Actions

Dokploy'da application → **Deployments** → webhook URL'ini nusxalang (token URL ichida).

GitHub → repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Nomi | Qiymati |
|---|---|
| `DOKPLOY_WEBHOOK_URL` | o'sha to'liq URL |

Boshqa secret kerak emas. Bundan keyin `main`ga push qilsangiz:

```
push → mvn verify + validatsiya → (o'tsa) Dokploy webhook → Dokploy build → health tekshiruvi
```

Build o'tmasa webhook chaqirilmaydi va serverga hech narsa yetmaydi.

---

## 10. Backup

Postgres konteyner nomini toping:

```bash
docker ps --format '{{.Names}}' | grep postgres
```

Har kecha uchun cron:

```bash
crontab -e
15 3 * * * cd <repo yo'li> && PG_CONTAINER=<nom> BACKUP_DIR=/srv/backups ./scripts/backup-db.sh >> /var/log/drenix-backup.log 2>&1
```

Dump'larni **serverdan tashqariga** ko'chiring. O'zi himoya qilayotgan serverda turgan backup —
backup emas.

---

## 11. Keyin: frontend

Alohida Dokploy application. Domain: `performance.drenix.uz`, **Path: `/`**.

**3000-portni tanlamang** — Dokploy paneli odatda o'sha portda.

`/api` yo'li uzunroq bo'lgani uchun Traefik uni birinchi tekshiradi, ya'ni frontend'ning `/`
qoidasi API so'rovlarini o'g'irlab ketmaydi.

---

## Tez tekshiruv ro'yxati

- [ ] 80 va 443 tashqaridan ochiq
- [ ] DNS → 93.188.81.14
- [ ] Credentiallar almashtirildi
- [ ] `git status` da `.env` va `deploy/tls` yo'q
- [ ] GitHub'da **private** repo
- [ ] Dokploy Compose app, Auto Deploy **o'chiq**
- [ ] 22 ta environment o'zgaruvchi
- [ ] Domain: path `/api`, port `8443`, Strip Path **o'chiq**
- [ ] `/api/health` → `{"status":"UP"}`
- [ ] Admin paroli almashtirildi
- [ ] `BOOTSTRAP_ADMIN_PASSWORD` o'chirildi
- [ ] `DOKPLOY_WEBHOOK_URL` secret qo'shildi
- [ ] Backup cron ishlayapti
