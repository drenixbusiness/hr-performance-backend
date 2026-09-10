# Bazani alohida deploy qilish

Postgres va Redis endi ilovadan alohida stack'da: `deploy/docker-compose.data.yml`.

---

## Avval halol gap: bu RAM tejamaydi

Postgres (29 MiB) va Redis (5 MiB) baribir o'sha serverda ishlaydi. Jami
~35 MiB — bu ilovaning 3% i. Ajratish xotirani kamaytirmaydi.

**Haqiqiy foydasi boshqa joyda:**

| | Oldin | Endi |
|---|---|---|
| Ilova deploy'i bazaga tegadimi | ha — konfiguratsiya o'zgarsa Postgres qayta yaratiladi | **yo'q** |
| `down -v` bazani o'chira oladimi | **ha** | yo'q — boshqa loyihada |
| Deploy'da bazani kutish | har safar | bir marta, birinchi deploy'da |
| Bazani ilovasiz qayta ishga tushirish | mumkin emas | mumkin |
| Backup / restore | ilova bilan chalkash | mustaqil |

Ya'ni bu **xavfsizlik va barqarorlik** o'zgarishi, tejash emas. Lekin
deploy cho'qqisi biroz pasayadi: baza allaqachon ishlab turgani uchun
`up -d` uni ko'rib chiqmaydi.

---

## Tuzilma

```
drenix-data      (docker-compose.data.yml)     postgres-identity, redis
drenix-backend   (docker-compose.registry.yml) tls-init, user, auth,
                                                performance, notification, gateway
        \                                         /
         \______  drenix-internal (external) ____/
```

Ikkala stack bitta **external** tarmoqda. `external` degani — compose uni
yaratmaydi va o'chirmaydi; u ikkala stack'dan ham uzoq yashaydi.

Ilova bazaga `postgres-identity:5432` va `redis:6379` orqali murojaat
qiladi — nomlar o'zgarmadi, chunki compose har bir servisga o'z nomi bilan
tarmoq aliasi beradi, u boshqa loyihada bo'lsa ham.

---

## Serverda o'rnatish

### 1. Tarmoqni yarating (bir marta)

```bash
docker network create drenix-internal
```

Bu qadam **birinchi** bo'lishi shart. Tarmoq bo'lmasa ikkala stack ham
"network drenix-internal not found" deb to'xtaydi.

### 2. Mavjud ma'lumotni ko'chiring

Bu qadam faqat serveringizda allaqachon baza bo'lsa kerak. Bo'sh bo'lsa
o'tkazib yuboring — yangi stack o'zi yaratadi.

Eski volume'lar `drenix-backend_` prefiksi bilan, yangilari aniq nom bilan.
Avval ilovani va eski bazani to'xtating:

```bash
docker compose -f deploy/docker-compose.registry.yml down
```

Keyin nusxa oling (`-v` YO'Q — eski volume'lar joyida qoladi, zaxira sifatida):

```bash
docker volume create drenix-identity-data
docker volume create drenix-redis-data

docker run --rm -v drenix-backend_identity-data:/from:ro -v drenix-identity-data:/to \
  alpine:3.20 sh -c 'cd /from && tar cf - . | (cd /to && tar xf -)'

docker run --rm -v drenix-backend_redis-data:/from:ro -v drenix-redis-data:/to \
  alpine:3.20 sh -c 'cd /from && tar cf - . | (cd /to && tar xf -)'
```

Eski volume'larni **darhol o'chirmang**. Hammasi ishlayotganiga bir necha
kun ishonch hosil qilgach o'chirasiz.

### 3. Dokploy'da ikkinchi Compose ilovasi

| Maydon | Qiymat |
|---|---|
| Nomi | `drenix-data` |
| Provider | o'sha GitHub repo, `main` |
| Compose Path | `./deploy/docker-compose.data.yml` |
| Domain | **yo'q** — bu tashqariga chiqmaydi |
| Auto Deploy | **OFF** |

Environment (ilova stack'idagi bilan **aynan bir xil** bo'lishi shart):

```
IDENTITY_DB_PASSWORD=...
NOTIFICATION_DB_PASSWORD=...
PERFORMANCE_DB_PASSWORD=...
REDIS_PASSWORD=...
```

Parol mos kelmasa ilova ulanolmaydi va sabab log'da "password
authentication failed" bo'lib chiqadi.

### 4. Tartib

Birinchi `drenix-data` ni deploy qiling, `healthy` bo'lguncha kuting, keyin
ilovani.

Keyinchalik ilovani xohlagancha qayta deploy qilaverasiz — bazaga tegmaydi.

---

## Ilova endi bazani kutmaydi — bu qanday hal qilindi

`depends_on` faqat bitta compose loyihasi ichida ishlaydi. Ajratgandan keyin
ilova "postgres healthy bo'lguncha kut" deya olmaydi.

Agar hech narsa qilinmasa, baza bir soniya kech ko'tarilsa `user-service`
ishga tusholmay yiqilardi va qayta-qayta boot bo'lardi — 2 GiB li serverda
JVM boot'ini takrorlash eng qimmat ish.

Yechim: Hikari'ning o'zi kutadi.

```yaml
initialization-fail-timeout: ${DB_INIT_TIMEOUT:60000}
```

Uchala bazali servisda (`user`, `performance`, `notification`) qo'yildi.
Endi baza 60 soniyagacha kechiksa ham servis kutadi, yiqilmaydi.

Redis uchun bu kerak emas — Spring Data Redis ulanishni birinchi
ishlatilganda ochadi, ishga tushish paytida emas.

---

## Kundalik buyruqlar

```bash
# Faqat ilovani qayta deploy qilish (baza tegilmaydi)
docker compose -f deploy/docker-compose.registry.yml up -d

# Faqat bazani qayta ishga tushirish
docker compose -f deploy/docker-compose.data.yml restart

# Baza holati
docker compose -f deploy/docker-compose.data.yml ps

# Backup
docker exec drenix-data-postgres-identity-1 \
  pg_dumpall -U identity_app > backup-$(date +%F).sql
```

**Hech qachon `down -v` qilmang** ma'lumotlar stack'ida — `-v` volume'ni
o'chiradi, ya'ni bazani. Ajratishning bir sababi ham shu edi: endi ilova
stack'ida `-v` ishlatsangiz ham baza omon qoladi.
