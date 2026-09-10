# 2 GiB / 4 CPU serverda ishlatish

Maqsad: deploy paytida ham, ishlash paytida ham server yiqilmasin.
Byudjet yo'q — bori bilan ishlash kerak.

---

## 1. Serverni yiqitayotgan asosiy sabab

Dokploy hozir `./deploy/docker-compose.dokploy.yml` ni ishlatyapti.
**Bu faylda 5 ta `build:` bor.**

Ya'ni har deploy'da serveringizda:

- JDK 26 va Maven yuklanadi
- 5 ta modul kompilyatsiya qilinadi (`contracts`, `platform-security` ham har safar)
- Maven'ning o'zi ~1 GiB heap so'raydi
- javac 4 CPU'ni ham egallaydi

2 GiB da bu **ilova ishga tushishidan oldin** OOM va swap thrashing beradi.
CPU 100%, RAM 98%, swap 100% — aynan siz ko'rgan manzara.

**Yechim:** Dokploy'da Compose Path ni almashtiring:

```
./deploy/docker-compose.registry.yml
```

Bu faylda `build:` **umuman yo'q**. GitHub Actions image'ni GitHub'ning
serverida (bepul, 4 CPU / 16 GiB) build qiladi, sizning serveringiz faqat
tayyor image'ni yuklab oladi. Serverdagi ish: `docker pull` + `docker run`.

**Bu bitta o'zgarish deploy paytidagi CPU va RAM cho'qqisining eng katta
qismini olib tashlaydi.**

---

## 2. CPU limitlari

Ilgari hech bir konteynerda CPU limiti yo'q edi — bitta JVM boot paytida
4 CPU'ni ham egallashi mumkin edi.

| Servis | CPU limiti |
|---|---|
| user-service | 0.50 |
| performance-service | 0.50 |
| edge-gateway | 0.50 |
| auth-service | 0.30 |
| notification-service | 0.30 |
| postgres | 0.40 |
| redis | 0.15 |
| **Jami** | **2.65 / 4 = 66%** |

Hammasi bir vaqtda cho'qqiga chiqsa ham 4 CPU'ning 66% idan oshmaydi.
1.35 CPU OS, Dokploy va SSH uchun qoladi.

O'zgartirish kerak bo'lsa Dokploy environment'idan: `CPU_USER`, `CPU_GATEWAY` va hokazo.

---

## 3. Ketma-ket ishga tushirish

Ilgari `depends_on` da `condition: service_started` edi. Bu "konteyner
ishga tushdi" degani, "tayyor" degani emas — natijada 5 ta JVM deyarli bir
vaqtda boot bo'lardi. Boot — JVM'ning eng qimmat payti (klass yuklash, JIT).

Endi hamma joyda `condition: service_healthy`. Tartib:

```
postgres → user-service → auth-service ─┐
                        → performance-service → notification-service ─┴→ edge-gateway
```

Bir vaqtda ko'pi bilan 2 ta JVM boot bo'ladi. Deploy uzoqroq davom etadi
(~4-6 daqiqa), lekin server yiqilmaydi. Sekin, lekin tirik.

`start_period` 120s dan **240s** ga ko'tarildi — 0.5 CPU da Spring Boot
sekinroq ko'tariladi, healthcheck uni erta o'lik deb belgilamasligi kerak.

---

## 4. Postgres ulanishlari

Izohda "four is more than this service needs" deb yozilgan edi, lekin
qiymat 16 bo'lib qolgan edi. Har bir pool ulanishi — alohida Postgres
backend jarayoni, o'z `work_mem` i bilan.

| Servis | Oldin | Endi |
|---|---|---|
| user-service | 16 | 4 |
| performance-service | 10 | 3 |
| notification-service | 10 | 2 |
| **Jami** | **36** | **9** |

`max_connections` 40 dan 20 ga tushirildi.

---

## 5. O'lchangan natija

Sovuq deploy lokal muhitda to'liq o'lchandi (7 konteyner, `restarts=0`,
hammasi `healthy`):

| | Oldin | Endi |
|---|---|---|
| Deploy paytida CPU cho'qqisi | 4 CPU'ning 100% i (Maven build) | 4 CPU'ning **20%** i |
| CPU qattiq shifti | yo'q | **66%** (2.65 / 4) |
| Ilova RAM (haqiqiy) | 4.2 GiB (limitsiz) | **1.20-1.37 GiB = 59-67%** |
| RAM shiftlari yig'indisi | yo'q | 1860 MiB (ilova 1700 + baza 160) |
| Postgres ulanishlari | 36 | 9 |

Har bir servis (barqaror holat):

| Servis | Haqiqiy | Shift | Foiz |
|---|---|---|---|
| user-service | 307-358 MiB | 440m | ~75% |
| performance-service | 247-300 MiB | 380m | ~72% |
| notification-service | 236-260 MiB | 320m | ~76% |
| edge-gateway | 179-226 MiB | 320m | ~64% |
| auth-service | 161-186 MiB | 240m | ~72% |
| postgres | 29-37 MiB | 120m | ~28% |
| redis | 5 MiB | 40m | 13% |

Raqamlar oraliq bilan berilgan: bir necha o'lchovda RSS ±25 MiB tebranadi.
Shiftlar eng yuqori o'lchovga ~20% zaxira qo'shib qo'yilgan.

Bir o'lchovda shiftlar juda tor qo'yilgan edi va servislar 88-90% ga
chiqdi — bitta burst OOM-kill qilishi mumkin edi. Shiftlar ko'tarildi.

Shiftlar baribir haqiqiy qiymatga yaqin turadi. Sababi: keng shift bilan
RAM tugasa, kernel **tasodifiy** konteynerni o'ldiradi — Postgres yoki
Dokploy bo'lishi mumkin, va butun server yotadi. Tor shift bilan esa faqat
aybdor servisning o'zi o'ladi va darhol qayta ko'tariladi.

---

## 5a. Halol hisob: 60% ga nima to'sqinlik qilyapti

Sizning maqsadingiz: RAM 2 GiB ning 60% i = **~1.2 GiB**.

Ilovaning o'zi **1.20-1.37 GiB** — ya'ni maqsad chegarasida. Muammo
ilovada emas:

| | |
|---|---|
| OS va kernel | ~150 MiB |
| **Dokploy** (Node + Traefik + o'z Postgres va Redis'i) | **~500 MiB** |
| Ilova (o'lchangan, yuqori chegara) | 1371 MiB |
| **Jami** | **~2.0 GiB = 98%** |

Dokploy'siz: 150 + 1371 = **1.49 GiB = 73%**. Eng past o'lchovda 1.28 GiB = 64%.

**Dokploy sizning RAM'ingizning to'rtdan birini yeyapti** - deploy tugmasi
va UI uchun. Dokploy qolsa 60% ga sig'ish imkonsiz; olib tashlansa ilova
byudjetga sig'adi.

### Dokploy'ni cheklash (tezkor, ~200 MiB tejaydi)

Dokploy o'z konteynerlarida limit qo'ymaydi. Serverda:

```bash
docker update --memory 220m --memory-swap 220m --cpus 0.5 dokploy
docker update --memory 90m  --memory-swap 90m  --cpus 0.3 dokploy-postgres
docker update --memory 40m  --memory-swap 40m  --cpus 0.2 dokploy-redis
docker update --memory 60m  --memory-swap 60m  --cpus 0.3 dokploy-traefik
```

Bundan keyin ~1.6 GiB = **80%**. Traefik nomi sizda boshqacha bo'lishi mumkin —
`docker ps --format '{{.Names}}' | grep dokploy` bilan tekshiring.

### Dokploy'ni butunlay olib tashlash (~500 MiB tejaydi)

66% ga tushadi — ya'ni maqsadingizga. GitHub Actions to'g'ridan-to'g'ri SSH orqali deploy qiladi,
Traefik o'rniga Nginx (allaqachon `deploy/nginx/` da tayyor). Dokploy UI
yo'qoladi, deploy `git push` bilan bo'ladi.

### 60% ga tushirish

Dokploy qolsa, 5 ta Spring Boot servisi bilan 60% **arifmetik jihatdan imkonsiz**.
Har bir JVM ~170-300 MiB yeydi va buning katta qismi — heap emas, JVM'ning
o'zi (metaspace, code cache, GC tuzilmalari, thread stack'lari). Heap'ni
qanchalik kichraytirmang, bu qism qolaveradi.

60% ga tushishning yagona yo'li — **JVM sonini kamaytirish**. Eng mos
nomzod: `notification-service` (daqiqada bir poll uchun 241 MiB) ni
`performance-service` ichiga birlashtirish. Bu ~200 MiB tejaydi, lekin
gRPC wiring, mTLS peer ro'yxati va alohida Flyway bazasini qayta yozishni
talab qiladi — katta ish. Xohlasangiz alohida qilamiz.

---

## 6. Deploy paytida cho'qqini yanada pasaytirish

Dokploy `up -d` ni ishga tushirishdan oldin image'lar allaqachon serverda
bo'lsa, `pull` va `run` bir-biriga qo'shilmaydi:

```bash
docker compose -f deploy/docker-compose.registry.yml pull
docker compose -f deploy/docker-compose.registry.yml up -d
```

Dokploy buni o'zi shunday qiladi, lekin qo'lda deploy qilsangiz shu tartib.

---

## 7. Swap

Swap 100% to'lgani — RAM tugagani belgisi, sababi emas. Limitlar qo'yilgach
swap deyarli ishlatilmasligi kerak. Kerneldan swap'ga erta yugurmaslikni
so'rang:

```bash
sudo sysctl -w vm.swappiness=10
echo 'vm.swappiness=10' | sudo tee -a /etc/sysctl.conf
```

Swap'ni **o'chirmang** — 2 GiB da u oxirgi himoya chizig'i.

---

## 8. Tekshirish

```bash
docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}'
free -h
swapon --show
```

Kutilayotgani: har bir servis limitining 60-80% ida, CPU tinch holatda 5% dan past.

Konteyner o'lgan bo'lsa:

```bash
docker inspect <konteyner> --format '{{.State.OOMKilled}} {{.RestartCount}}'
docker logs <konteyner> 2>&1 | grep 'Terminating due to'
```

---

## 9. Diqqat: deploy endi uzoqroq davom etadi

`condition: service_healthy` tufayli `docker compose up -d` hamma
healthcheck o'tguncha **bloklanadi** — bu ~4-6 daqiqa.

Agar Dokploy deploy'ni timeout bilan uzsa, oxirgi servislar
(`notification-service`, `edge-gateway`) ishga tushmay qolishi mumkin.
Deploy log'ida shu ikkitasi yo'q bo'lsa — sabab shu, xato emas.

Dokploy sozlamalarida deploy timeout'ini kamida **10 daqiqa** qiling.

Bu kompromis ataylab qilingan: tez, lekin serverni yiqitadigan deploy
o'rniga — sekin, lekin server tirik qoladigan deploy.

---

## 10. Baza alohida stack'da

Postgres va Redis endi `deploy/docker-compose.data.yml` da — ilovadan
alohida deploy qilinadi. Bu RAM tejamaydi (~35 MiB baribir shu serverda),
lekin ilovani qayta deploy qilish endi bazaga umuman tegmaydi.

To'liq qo'llanma: `docs/BAZANI-AJRATISH.md`.

Birinchi o'rnatishda tarmoqni qo'lda yaratish esdan chiqmasin:

```bash
docker network create drenix-internal
```
