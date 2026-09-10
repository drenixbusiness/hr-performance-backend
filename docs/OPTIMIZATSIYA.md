# Server optimizatsiyasi — 2 GiB RAM uchun

## Muammo nima edi

Serveringiz: **4 CPU, 2 GiB RAM, 2 GiB swap**. Rasmda: RAM 98.86%, SWAP 99.99%, CPU 92%.

Bu tasodif emas edi. Lokal serverda o'lchadim — konteynerlar shuncha yeyayotgan edi:

| Konteyner | RAM |
|---|---|
| notification-service | 909 MiB |
| user-service | 904 MiB |
| performance-service | 805 MiB |
| edge-gateway | 802 MiB |
| auth-service | 690 MiB |
| postgres | 99 MiB |
| redis | 8 MiB |
| **Jami** | **4.2 GiB** |

Postgres va Redis halol ishlayapti. Muammo beshta JVM'da.

### Sababi

`deploy/Dockerfile` da shu qator bor edi:

```
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
```

va konteynerlarda **hech qanday memory limit yo'q** edi.

Limit bo'lmasa, JVM *hostning* xotirasini o'qiydi. 2 GiB serverda har bir JVM "menga 75% — 1.5 GiB mumkin" deb hisoblaydi. Beshtasi ham. Ya'ni **7.5 GiB da'vo qilinadi, mavjudi 2 GiB.**

JVM darrov shuncha olmaydi, lekin o'sishga to'siq yo'q — va o'sadi. Swap to'ladi, tizim thrashing'ga tushadi, CPU esa xotira almashtirishga sarflanadi. 92% CPU aynan shundan, foydali ishdan emas.

---

## Nima o'zgardi

### 1. Har bir konteynerga limit va aniq heap

Limit qo'yilishi bilan `MaxRAMPercentage` **limit**ni o'qiy boshlaydi, host'ni emas. Endi har biriga aniq `-Xmx` ham berilgan:

Raqamlar taxmin emas — har bir servis keng limit bilan ishga tushirilib, to'liq boot'dan
5 daqiqa keyin **haqiqiy RSS o'lchandi**, so'ng ustiga ~25-30% zaxira qo'shildi.

| Servis | O'lchangan RSS | Limit | Heap | Nega shuncha |
|---|---|---|---|---|
| user-service | 320 MiB | 420m | 150m | Argon2id har loginda xotira ajratadi; klass yuklashi eng ko'p |
| auth-service | 168 MiB | 240m | 120m | token imzolaydi, deyarli hech narsa ajratmaydi |
| notification-service | 237 MiB | 310m | 130m | daqiqada bir audit sahifasi |
| performance-service | 247 MiB | 380m | 200m | RingCentral'dan bir yo'la 11-20 ming yozuv - portlashli |
| edge-gateway | 191 MiB | 310m | 180m | PDF butunlay xotirada yig'iladi - portlashli |
| postgres | 32 MiB | 160m | | |
| redis | 6 MiB | 48m | | |
| **Jami** | **1201 MiB** | **1868m** | | **1.82 GiB** |

`performance-service` va `edge-gateway` limitlari o'lchangan RSS'dan ancha yuqori - chunki
ular tinch holatda kam yeydi, lekin hisobot yaratish paytida keskin ko'tariladi.

Hammasi Dokploy environment'idan o'zgartiriladi: `MEM_GATEWAY`, `HEAP_PERFORMANCE`,
`META_LIMIT` va hokazo. Faylga tegish shart emas.

### 1a. Topilgan haqiqiy bug: Metaspace juda kichik edi

Dastlab har bir servisga `-XX:MaxMetaspaceSize=96m` qo'ygan edim. O'lchash paytida
`user-service` **crash-loop**da ekani chiqdi:

```
Terminating due to java.lang.OutOfMemoryError: Metaspace
```

8 marta qayta ishga tushgan. `docker stats` uni "ishlayapti" deb ko'rsatardi - chunki har
safar yangi JVM ko'tarilardi. Bu productionda `user-service`ni, ya'ni login'ni butunlay
yiqitardi.

Sababi: Spring Boot 4 + Hibernate + gRPC juda ko'p klass yuklaydi, 96m yetmaydi.
Metaspace endi `${META_LIMIT:-256m}`. Bu **shift**, ajratilgan xotira emas - JVM faqat
kerak bo'lganicha oladi, shuning uchun RSS'ga ta'sir qilmaydi, lekin cheksiz o'sishdan
saqlaydi.

### 2. JVM sozlamalari — kichik heap uchun

Har bir servisga:

```
-XX:+UseSerialGC  -XX:TieredStopAtLevel=1
-XX:MaxMetaspaceSize=256m  -XX:ReservedCodeCacheSize=32m
-Xss512k  -XX:+ExitOnOutOfMemoryError
MALLOC_ARENA_MAX=2
```

| Sozlama | Nima beradi |
|---|---|
| `UseSerialGC` | G1 region jadvallari va fon oqimlarini saqlaydi. 256 MB dan kichik heap'da bu mashinasozlik foydasidan ko'p turadi |
| `TieredStopAtLevel=1` | Faqat C1 kompilyator. C2 va uning code cache'i tushib qoladi. **Cheklangan tezlik pasayadi** — 10 foydalanuvchida sezilmaydi |
| `MaxMetaspaceSize` | Spring minglab klass yuklaydi va metaspace standart holda cheksiz o'sadi. Boot ilovasida eng katta heap'dan tashqari iste'molchi |
| `MALLOC_ARENA_MAX=2` | glibc har oqimga alohida arena beradi; virtual thread'lar bilan bu RSS'ni bekorga shishiradi |

### 3. Postgres kichik mashinaga moslandi

```
shared_buffers=48MB         (standart 128MB)
max_connections=40          (standart 100; bizga ~14 kerak)
work_mem=2MB
max_parallel_workers_per_gather=0
```

### 4. Connection pool'lar kichraytirildi

Har bir pooldagi ulanish — Postgres'da alohida jarayon. Avval: user-service 16 + performance 10 + notification 10 = **36 ta**. O'n kishiga xizmat qiladigan tizim uchun bu yuzlab megabayt.

Endi har biriga 4 ta (`DB_POOL_SIZE` bilan o'zgartiriladi), va bo'sh turgani qaytariladi.

### 5. Log hajmi

Har servisga 200 MB → 50 MB. 48 GB diskda Dokploy image'lari va build cache bilan birga bu sezilarli.

### 6. Build serverdan olib tashlandi ← eng katta yutuq

Bu hozirgi 92% CPU'ning bevosita sababi.

Beshta Java modulini kompilyatsiya qilish uchun Maven'ning o'ziga qariyb bir gigabayt kerak. 2 GiB serverda, Dokploy va ishlab turgan ilova bilan birga — bu server uchun og'ir.

Endi:

```
push main → GitHub Actions build qiladi (4 CPU, 16 GB, bepul)
          → image'larni GHCR'ga yuboradi
          → Dokploy webhook
          → server faqat pull qiladi
```

Server endi build toolchain, Maven cache va har build qoldiradigan ~2 GB qatlamlarni saqlamaydi. Deploy 15 daqiqadan **1–2 daqiqaga** tushadi.

Buning uchun Dokploy'da **Compose Path**ni o'zgartiring:

```
./deploy/docker-compose.registry.yml
```

---

## Halol xulosa: 2 GiB yetmaydi

Optimizatsiyadan keyin ilova **1.82 GiB** limitga sig'adi. Lekin:

| | |
|---|---|
| Ilova (limitlar yig'indisi) | 1868 MiB |
| Dokploy (Node + Traefik + o'z Postgres va Redis'i) | ~500 MiB |
| OS va kernel | ~150 MiB |
| **Jami** | **~2.5 GiB** |
| **Mavjud** | **2.0 GiB** |

**~500 MB yetishmaydi.** Bu yerdagi hamma optimizatsiya — siqib chiqarish mumkin bo'lgan maksimum. Undan pastga tushirsam:

- `performance-service` heap'ini 160m dan pasaytirsam, `/activity` haqiqiy RingCentral ma'lumotida **OutOfMemoryError** beradi
- `edge-gateway` heap'ini pasaytirsam, oylik PDF yaratilmaydi
- `ExitOnOutOfMemoryError` yoqilgani uchun konteyner o'ladi — jim qotib qolmaydi, lekin xizmat uziladi

### Tavsiya

**4 GiB ga ko'taring.** Bu 2 GiB dan arzon farq, va u holda:

- 1.82 GiB ilova + 0.65 GiB Dokploy/OS = 2.5 GiB ishlatiladi
- 1.5 GiB zaxira qoladi — PDF yaratish, oylik hisobot, frontend uchun
- Swap umuman ishlatilmaydi

**Agar 2 GiB da qolsangiz** — ishlashi mumkin, lekin:
- Frontend'ni ham shu serverga qo'ysangiz (Next.js ~150–250 MiB) sig'maydi
- Oylik PDF yaratish paytida OOM xavfi bor
- Swap ishlatiladi, ya'ni javob vaqti oldindan aytib bo'lmaydigan bo'ladi

**Frontend'ni qo'shishdan oldin RAM ko'taring** — hozirgi holatda joy yo'q.

---

## Tekshirish

Deploy'dan keyin serverda:

```bash
# Har bir konteyner qancha yeyapti va limitiga nisbatan qancha
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}'

# Umumiy holat
free -h

# Swap ishlatilyaptimi — 0 ga yaqin bo'lishi kerak
swapon --show
```

Kutilayotgani: har bir JVM limitining **60–80%** ida turadi. 95% dan yuqori bo'lsa — o'sha servisga `MEM_*` va `HEAP_*` ni oshiring.

Konteyner qayta-qayta o'chib yonayotgan bo'lsa:

```bash
docker inspect <konteyner> --format '{{.State.OOMKilled}}'
```

`true` chiqsa — limit kichik, oshiring.

---

## 10 foydalanuvchi uchun yetadimi?

**Xotira bo'yicha** — ha, 4 GiB da bemalol. Cheklov foydalanuvchi sonida emas: beshta JVM ishga tushishi uchun kerak bo'lgan minimal xotirada. O'ninchi foydalanuvchi birinchisidan deyarli farq qilmaydi.

**CPU bo'yicha** — 4 CPU ortig'i bilan yetadi. Ikkita joy og'ir:

| Nima | Qancha | Nega |
|---|---|---|
| Login | ~150 ms | Argon2id ataylab sekin — parol o'g'irlansa buzish qimmat bo'lsin uchun |
| Oylik PDF | 15–20 soniya | OpenAI'ni kutadi, CPU emas |

`TieredStopAtLevel=1` sababli login biroz sekinlashadi (~50 ms → ~150 ms). 10 kishiga sezilmaydi. Agar sezilsa — o'sha flagni `HEAP_USER` yonidan olib tashlash mumkin, ~40 MiB evaziga.

**Haqiqiy cheklov** — RingCentral: kompaniyaga daqiqasiga 10 so'rov. Bu bizning serverimizga bog'liq emas, va `/activity` uchun 7 kunlik chegara aynan shuning uchun qo'yilgan.
