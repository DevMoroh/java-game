# Dev World

Багатокористувацька 3D-гра для розробників: поле з травою, персонажі, real-time рух.

- `server/` — Java 25 + Spring Boot 4, сирий WebSocket (`/ws`), авторитетна симуляція 20 тік/с, PostgreSQL 18
- `client/` — React + Vite + Three.js (react-three-fiber), RTS-камера згори під кутом

## Запуск

Потрібен запущений Docker: `bootRun` сам піднімає PostgreSQL з `server/compose.yaml` (порт 55432),
схему накочує Flyway (`server/src/main/resources/db/migration`).

```bash
cd server && ./gradlew bootRun     # http://localhost:8080
cd client && npm install && npm run dev   # http://localhost:5173 (проксі /ws → 8080)
```

Відкрий кілька вкладок — побачиш інших гравців. Керування: WASD/стрілки або клік по траві, колесо — зум.

## Протокол (JSON)

Клієнт → сервер:
- `{"type":"join","name":"Roman","gender":"MALE"|"FEMALE","token":"…","appearance":{…}}` — token і appearance
  з минулого входу (необов'язкові): сервер поверне того самого персонажа на те саме місце
- `{"type":"input","seq":42,"dx":0,"dz":-1}` — один крок руху (1/tickRate с), |v| ≤ 1; seq зростає
- `{"type":"slap"}` — лящ найближчому в радіусі 1.6 (пауза 0.8 с)
- `{"type":"jump"}` — стрибок (0.75 с; повторний у повітрі ігнорується)
- `{"type":"handshake"}` — простягнути руку найближчому або потиснути руку, простягнуту тобі (5 с на відповідь)
- `{"type":"groupJoin","player":"<id>"}` — приєднатися до чату гравця, у чиєму колі стоїш; `{"type":"groupLeave"}`
- `{"type":"chat","text":"Привіт!"}` — повідомлення у свій чат: груповий або загальний (до 300 символів; 5 підряд, далі 1/с)
- `{"type":"status","text":"🎧 Слухаю музику"}` — статус над головою (до 40 символів, `""` — прибрати); зберігається в профілі

Сервер → клієнт: `welcome` (selfId, token профілю, правила руху, усі гравці), `joined`, `left`,
`state` (позиції всіх щотік + `lastSeq` — останнє оброблене введення),
`slap` — замах (`from`, `to` або `null`, якщо поруч нікого; розсилається одразу),
`slapImpact` — результат через 0.2 с (`from`, `to`, `dodged`: ціль у повітрі — стрибок рятує від ляща),
`jump` (`id`), `handshakeOffer` (`from`, `to`, `expiresInMs`), `handshake` (`from`, `to`, `scored`),
`playerUpdate` (`id`, `score`, `group`, `color` — колір кола, `status`); тим, хто в цьому чаті, — `chat` (`group` або
`null` для загального, `from`, `name`, `text`, `at`); гравцю — `chatHistory` (`group`, останні 50) при вході й зміні чату,
`notice`, `chatError`.

## Очки, кольори й чати

- Рукостискання: **+1 обом**; з тим самим гравцем — не частіше разу на годину (`HANDSHAKE_SCORE_COOLDOWN_NANOS`).
- Лящ, що влучив: **−1** тому, хто отримав (не нижче 0); той, хто б'є, нічого не отримує; ухилився стрибком — 0.
- **У кожного гравця своє коло** (радіус 1.6 м) свого кольору — кольори на полі не повторюються (зелених немає);
  колір зберігається в профілі.
- Підійди до когось (зайди в його коло) → «Приєднатися до чату»: ви в одному груповому чаті, а твоє коло стає кольору
  того, до кого ти приєднався. Хто підійде до будь-кого з групи — теж приєднається. Ніхто нічого не підтверджує;
  гравець — лише в одній групі.
- Хто ні до кого не приєднався — у **загальному чаті**. «Вийти з чату» → назад у загальний, зі своїм кольором.
- Лишився в групі сам — група розпадається разом з її чатом. Пішов той, чий колір у групи, — група бере колір
  найстаршого з тих, хто лишився.
- Своїх (одна група) бити не можна: лящ іде найближчому чужому, а якщо поруч лише свої — «Свої!».

## Рух і передбачення

Клієнт рухає свого персонажа одразу (`client/src/game/LocalPlayer.ts`) і шле кожен крок серверу.
Сервер застосовує ті ж правила (`GameWorld.applyInput`) і повертає `lastSeq`; клієнт бере серверну
позицію й переграє непідтверджені кроки, розбіжність гаситься плавно.

**Правила руху продубльовані:** `server/.../GameWorld.java` і `client/src/game/movement.ts` мають збігатися.

Перевірка на повільній мережі: `http://localhost:5173/?lag=200` (штучна затримка 200 мс туди й назад).
Сервер на іншому порту: `GAME_SERVER=ws://localhost:8081 npm run dev`.

## 3D-моделі

Персонажі й анімації — [Quaternius](https://quaternius.itch.io) (CC0): Universal Base Characters,
Universal Animation Library 1 і 2. Сирі паки (~130 МБ) лежать у `client/assets-src/quaternius/` (не в git),
у гру йдуть стиснуті файли з `client/public/models/` (~2 МБ):

```bash
cd client && npm run build:models   # scripts/build-models.mjs
```

Одяг намальований шейдером за вагами кісток (`client/src/game/avatar/avatar.ts`), кольори — з `Appearance` на сервері.

## Збереження (PostgreSQL)

Що лежить у базі (`JdbcGameStore`): профілі гостей (ім'я, стать, зовнішність, колір, очки, статус, де стояв), групи
(групові чати: колір, господар, учасники), повідомлення групових і загального чату (зберігаються 30 днів — `game.chat.retention-days`). Гра працює з даними в пам'яті, а в базу пише
у фоні одним потоком (`db-writer`): нові профілі й зміни груп — одразу, очки й позиції гравців у грі — кожні 5 с
(`game.store.flush-interval-ms`), усе — при виході гравця й зупинці сервера. Читання — лише при старті (групи) і при першому зверненні до чату
і при вході гравця, поза ігровим локом.

Обробка WebSocket-повідомлень — у віртуальних потоках (`spring.threads.virtual.enabled=true`): Tomcat створює віртуальний
потік на кожне повідомлення замість пулу платформних. На 200 клієнтах це 40 платформних потоків замість 219 при тій самій
затримці. Власні потоки гри (`game-loop`, `db-writer`) лишаються платформними.

Що поки не зберігається: ліміт «очки за рукостискання з тим самим гравцем раз на годину» (після перезапуску скидається).

Підключитися до бази вручну: `psql postgresql://devworld:devworld@localhost:55432/devworld`.
Тести з базою (`PersistenceTest`, `RestartTest`) самі піднімають PostgreSQL через Testcontainers.

## Повернення після перезавантаження

Сервер видає гостю токен; клієнт тримає його в `sessionStorage` (`client/src/game/session.ts`): після перезавантаження
одразу заходить тим самим персонажем; кожна вкладка — окремий персонаж. Профіль за токеном береться з пам'яті або з БД.

## Деплой (GitHub Actions → VPS)

Push у `main` → `.github/workflows/deploy.yml`: тести сервера (Testcontainers) і перевірка/збірка клієнта →
Docker-образ (`Dockerfile`: клієнт вбудований у Spring Boot) у GitHub Container Registry → по SSH на VPS:
`docker compose pull && up -d` (`deploy/compose.yaml`: застосунок + PostgreSQL + Caddy з HTTPS від Let's Encrypt)
→ перевірка `https://DOMAIN/actuator/health`. Pull request'и лише перевіряються.

### Одноразово на VPS (Ubuntu/Debian)

```bash
# 1. DNS: A-запис домену → IP сервера. Відкрити порти 80/tcp, 443/tcp, 443/udp.
# 2. Docker
curl -fsSL https://get.docker.com | sudo sh
# 3. Користувач для деплою з доступом до Docker і SSH-ключем (публічний ключ — у ~/.ssh/authorized_keys)
sudo adduser --disabled-password --gecos "" deploy && sudo usermod -aG docker deploy
sudo mkdir -p /opt/devworld && sudo chown deploy:deploy /opt/devworld
# 4. Секрети — лише на сервері
sudo -u deploy tee /opt/devworld/.env > /dev/null <<ENV
DOMAIN=game.example.com
POSTGRES_PASSWORD=$(openssl rand -base64 32 | tr -d '/+=')
ENV
```

### Секрети GitHub

```bash
ssh-keygen -t ed25519 -f devworld_deploy -N ""          # пара ключів лише для деплою
# devworld_deploy.pub → /home/deploy/.ssh/authorized_keys на VPS
gh secret set VPS_HOST --body "1.2.3.4"
gh secret set VPS_USER --body "deploy"
gh secret set VPS_SSH_KEY < devworld_deploy
gh secret set VPS_KNOWN_HOSTS --body "$(ssh-keyscan 1.2.3.4)"   # необов'язково, але безпечніше
gh variable set DOMAIN --body "game.example.com"
# необов'язково: gh secret set VPS_PORT --body 2222; gh variable set DEPLOY_PATH --body /opt/devworld
```

Бекап бази: `docker compose exec db pg_dump -U devworld devworld > backup.sql` (у `/opt/devworld`).
