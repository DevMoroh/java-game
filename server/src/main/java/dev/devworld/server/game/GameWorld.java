package dev.devworld.server.game;

import dev.devworld.server.game.Messages.MoveRules;
import dev.devworld.server.game.Messages.PlayerInfo;
import dev.devworld.server.game.Messages.PlayerPos;
import dev.devworld.server.game.Messages.PlayerUpdate;
import dev.devworld.server.store.GameStore;
import dev.devworld.server.store.GameStore.StoredProfile;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Авторитетний стан світу.
 * <p>
 * Клієнт сам передбачає свій рух і надсилає введення з номером {@code seq}: одне введення —
 * один крок тривалістю {@code 1/tickRate}. Сервер застосовує його одразу за тими ж правилами
 * (див. {@code client/src/game/movement.ts} — має збігатися з цим файлом) і повертає
 * {@code lastSeq}, щоб клієнт звірився.
 */
@Component
public class GameWorld implements SmartInitializingSingleton {

    public static final double WORLD_SIZE = 100;
    /** Радіус «тіла» гравця для зіткнень. */
    public static final double PLAYER_RADIUS = 0.45;
    public static final double SPEED = 6; // одиниць за секунду
    public static final double BOUNDARY = WORLD_SIZE / 2 - 1;
    /** Скільки введень гравець може надіслати пачкою (мережевий джитер). */
    static final int INPUT_BURST = 10;
    /** Запас на розбіжність годинників клієнта й сервера. */
    private static final double INPUT_RATE_TOLERANCE = 1.1;
    private static final double MIN_DIST = PLAYER_RADIUS * 2;
    private static final double SPAWN_AREA = 10;
    private static final int RESOLVE_ITERATIONS = 3;
    private static final long MOVING_GRACE_NANOS = 150_000_000;
    /** Дистанція, з якої можна простягнути руку і з якої її можна потиснути. */
    public static final double HANDSHAKE_RANGE = 1.6;
    /** На такій відстані між центрами руки зустрічаються; ближчих не розсуваємо. */
    static final double HANDSHAKE_DISTANCE = 1.0;
    /** Скільки чекає простягнута рука. */
    static final long HANDSHAKE_OFFER_NANOS = 5_000_000_000L;
    /** Тривалість рукостискання; має збігатися з HANDSHAKE_DURATION у client/src/game/handshake.ts. */
    static final long HANDSHAKE_NANOS = 1_600_000_000L;
    /** Тривалість стрибка; має збігатися з JUMP_DURATION у client/src/game/jump.ts. */
    static final long JUMP_NANOS = 750_000_000;
    /** Запас на мережевий джитер: клієнт міг приземлитися трохи раніше, ніж вважає сервер. */
    static final long JUMP_TOLERANCE_NANOS = 150_000_000;
    /** Дистанція між центрами, з якої дістає лящ (контакт — 0.9). */
    public static final double SLAP_RANGE = 1.6;
    static final long SLAP_COOLDOWN_NANOS = 800_000_000;
    static final double SLAP_KNOCKBACK = 0.7;
    /** Затримка від замаху до удару — збігається з анімацією Melee_Hook на клієнті. */
    static final long SLAP_IMPACT_DELAY_NANOS = 200_000_000;
    /**
     * Запас до радіуса кола при вступі: гравець рухається, а клієнт бачить позиції з затримкою й тримає
     * кнопку «Приєднатися» ще мить після виходу з кола — приймаємо вступ до двох радіусів від гравця.
     */
    static final double JOIN_TOLERANCE = Groups.CIRCLE_RADIUS;
    /** З тим самим гравцем рукостискання дає очки не частіше (щоб двоє не накручували одне одному). */
    static final long HANDSHAKE_SCORE_COOLDOWN_NANOS = 3600L * 1_000_000_000L;
    /** Найдовший статус («🎧 Слухаю музику» тощо). */
    public static final int MAX_STATUS = 40;
    /** Статус можна міняти не частіше (кожна зміна розсилається всім). */
    static final long STATUS_COOLDOWN_NANOS = 500_000_000;

    private record Point(double x, double z) {
    }

    /** Результат входу: гравець і токен його профілю (клієнт зберігає, щоб повернутися після перезавантаження). */
    public record JoinResult(PlayerInfo player, String token) {
    }

    /**
     * Профіль гостя в пам'яті — кеш рядка guest_profile у БД (див. {@link GameStore}).
     * Пам'ятає, хто це, скільки очок і де стояв, коли вийшов.
     */
    private static final class GuestProfile {
        final String name;
        final Gender gender;
        final Appearance appearance;
        Point lastPosition;
        double lastRotation;
        int score;
        String status = "";
        /** Власний колір кола (поки гравець не приєднався до чужого чату). */
        String color;
        boolean online;
        long lastSeenNanos;

        GuestProfile(String name, Gender gender, Appearance appearance) {
            this.name = name;
            this.gender = gender;
            this.appearance = appearance;
        }
    }

    /** Скільки тримаємо профіль офлайн-гостя в пам'яті (у БД він лишається; при вході прочитаємо знову). */
    static final long PROFILE_TTL_NANOS = 24L * 3600 * 1_000_000_000L;

    /**
     * Замах: {@code targetId == null} — бити нікого, б'є в повітря; {@code allyId} — тоді найближчий свій
     * (зі спільної команди), якого бити не можна, або null.
     */
    public record SlapResult(String slapperId, String targetId, String allyId) {
    }

    /**
     * Результат ляща в момент удару: {@code dodged} — ціль була в повітрі (стрибок рятує);
     * {@code penalty} — зняте очко (0, якщо ухилився або вже було 0); {@code update} — нові очки цілі або null.
     */
    public record SlapImpact(String slapperId, String targetId, boolean dodged, int penalty, PlayerUpdate update) {
    }

    /**
     * Результат натискання «привітатись»: або простягнута рука ({@code accepted == false}, {@code to} ще не
     * відповів), або рукостискання відбулося ({@code accepted == true}; {@code from} — той, хто простягнув першим).
     */
    public record HandshakeResult(String from, String to, boolean accepted, Handshake details) {
        HandshakeResult(String from, String to, boolean accepted) {
            this(from, to, accepted, null);
        }
    }

    /** Рукостискання відбулося: {@code scored} — обидва отримали +1 ({@code updates} — їхні нові очки). */
    public record Handshake(String from, String to, boolean scored, List<PlayerUpdate> updates) {
    }

    /**
     * Результат дії з груповим чатом: повідомлення тому, хто діяв; {@code updates} — гравці в грі, чий чат чи колір
     * змінився (їм — новий чат); {@code deletedGroups} — групи, що розпалися (їхній чат більше не потрібен).
     */
    public record GroupOutcome(boolean ok, String message, List<PlayerUpdate> updates, List<String> deletedGroups) {
        static GroupOutcome fail(String message) {
            return new GroupOutcome(false, message, List.of(), List.of());
        }
    }

    private record HandshakeOffer(String to, long expiresAtNanos) {
    }

    /** Лящ, що чекає моменту удару; напрям відкидання — від того, хто б'є, на момент замаху. */
    private record PendingSlap(String slapperId, String targetId, double dirX, double dirZ, long impactAtNanos) {
    }

    private final Map<String, Player> players = new LinkedHashMap<>();
    /** Токен → профіль гостя. */
    private final Map<String, GuestProfile> profiles = new HashMap<>();
    private final SecureRandom tokenRandom = new SecureRandom();
    private final List<PendingSlap> pendingSlaps = new ArrayList<>();
    /** Простягнуті руки: хто → кому. У кожного не більше однієї. */
    private final Map<String, HandshakeOffer> handshakeOffers = new LinkedHashMap<>();
    private final Groups groups = new Groups();
    /** Пара профілів (memberKey через «|», за абеткою) → коли востаннє отримали очки за рукостискання. */
    private final Map<String, Long> scoredPairs = new HashMap<>();
    private final int tickRate;
    private final double stepSeconds;
    private final LongSupplier clock;
    private final GameStore store;

    @Autowired
    public GameWorld(@Value("${game.tick-rate:20}") int tickRate, GameStore store) {
        this(tickRate, System::nanoTime, store);
    }

    /** Без бази — для юніт-тестів ігрової логіки. */
    GameWorld(int tickRate, LongSupplier clock) {
        this(tickRate, clock, GameStore.NONE);
    }

    GameWorld(int tickRate, LongSupplier clock, GameStore store) {
        this.tickRate = tickRate;
        this.stepSeconds = 1.0 / tickRate;
        this.clock = clock;
        this.store = store;
    }

    /** Після створення всіх бінів (Flyway вже накотив схему) — піднімаємо команди з БД. */
    @Override
    public synchronized void afterSingletonsInstantiated() {
        for (var g : store.loadGroups()) {
            groups.load(g.id(), g.color() == null ? groups.freeColor(java.util.Set.of()) : g.color(),
                    g.hostToken(), g.memberTokens());
        }
    }

    public int tickRate() {
        return tickRate;
    }

    public MoveRules rules() {
        return new MoveRules(tickRate, SPEED, PLAYER_RADIUS, BOUNDARY);
    }

    /**
     * Вхід у гру. Якщо {@code token} — профіль, що зараз офлайн, повертаємо того самого персонажа на те саме місце.
     * Інакше (новий гість, сервер забув профіль або цей токен уже в грі в іншій вкладці) — новий профіль
     * і новий токен; зовнішність беремо з кешу клієнта, якщо вона валідна, інакше випадкову.
     */
    public JoinResult join(String id, String token, String name, Gender gender, Appearance cachedAppearance) {
        // Читання з БД — поза локом гри, щоб тік не чекав на базу.
        StoredProfile stored = token != null && !hasProfileInMemory(token)
                ? store.loadProfile(token).orElse(null)
                : null;
        return joinLocked(id, token, name, gender, cachedAppearance, stored);
    }

    private synchronized boolean hasProfileInMemory(String token) {
        return profiles.containsKey(token);
    }

    private synchronized JoinResult joinLocked(String id, String token, String name, Gender gender,
                                               Appearance cachedAppearance, StoredProfile stored) {
        long now = clock.getAsLong();
        profiles.values().removeIf(p -> !p.online && now - p.lastSeenNanos > PROFILE_TTL_NANOS);

        var profile = token == null ? null : profiles.get(token);
        if (profile == null && stored != null) {
            profile = new GuestProfile(stored.name(), stored.gender(), stored.appearance());
            profile.score = stored.score();
            profile.lastRotation = stored.lastRotation();
            profile.status = stored.status() == null ? "" : stored.status();
            profile.color = stored.color();
            if (stored.lastX() != null && stored.lastZ() != null) {
                profile.lastPosition = new Point(stored.lastX(), stored.lastZ());
            }
            profiles.put(token, profile);
        }
        Point spawn;
        boolean isNew = false;
        if (profile != null && !profile.online) {
            var last = profile.lastPosition;
            spawn = last != null && !overlapsAnyone(null, last.x, last.z) ? last : findFreeSpot();
        } else {
            token = newToken();
            var appearance = cachedAppearance != null && cachedAppearance.isValidFor(gender)
                    ? cachedAppearance
                    : Appearance.random(gender, ThreadLocalRandom.current());
            profile = new GuestProfile(name, gender, appearance);
            profiles.put(token, profile);
            spawn = findFreeSpot();
            isNew = true;
        }
        profile.online = true;
        // Збережений колір лишаємо, якщо він вільний; інакше (або новому гостю) — видаємо вільний.
        String color = personalColorFor(profile.color, token);
        boolean colorChanged = !color.equals(profile.color);
        profile.color = color;
        if (isNew || colorChanged) {
            // Рядок профілю — одразу: на нього посилаються групи й чат.
            store.saveProfile(new StoredProfile(token, profile.name, profile.gender, profile.appearance, profile.score,
                    spawn.x, spawn.z, profile.lastRotation, profile.status, color));
        }
        var player = addPlayer(new Player(id, token, profile.name, profile.gender, profile.appearance, spawn.x, spawn.z));
        player.rotation = profile.lastRotation;
        player.score = profile.score;
        player.status = profile.status;
        player.color = profile.color;
        return new JoinResult(info(player), token);
    }

    /** Новий гість без профілю у випадковому вільному місці. */
    public synchronized PlayerInfo join(String id, String name, Gender gender) {
        return join(id, null, name, gender, null).player();
    }

    /** Вхід у конкретну точку без профілю — для тестів. */
    synchronized PlayerInfo join(String id, String name, Gender gender, double x, double z) {
        var player = new Player(id, null, name, gender, Appearance.random(gender, ThreadLocalRandom.current()), x, z);
        player.color = personalColorFor(null, null);
        return info(addPlayer(player));
    }

    /** Переставити гравця — для тестів. */
    synchronized void teleport(String id, double x, double z) {
        var p = players.get(id);
        p.x = x;
        p.z = z;
    }

    /** Гравець за id сесії або null. */
    public synchronized PlayerInfo player(String id) {
        var p = players.get(id);
        return p == null ? null : info(p);
    }

    private Player addPlayer(Player player) {
        long now = clock.getAsLong();
        player.inputTokens = INPUT_BURST;
        player.lastRefillNanos = now;
        player.movingUntilNanos = now;
        player.slapReadyAtNanos = now; // nanoTime може бути від'ємним — 0 тут не годиться
        player.airborneUntilNanos = now;
        player.shakingUntilNanos = now;
        player.statusReadyAtNanos = now;
        players.put(player.id, player);
        return player;
    }

    private String newToken() {
        var bytes = new byte[18];
        tokenRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public synchronized boolean leave(String id) {
        handshakeOffers.remove(id);
        handshakeOffers.values().removeIf(o -> o.to.equals(id));
        var player = players.remove(id);
        if (player == null) {
            return false;
        }
        var profile = player.token == null ? null : profiles.get(player.token);
        if (profile != null) {
            profile.lastPosition = new Point(player.x, player.z);
            profile.lastRotation = player.rotation;
            profile.score = player.score;
            profile.status = player.status;
            profile.color = player.color;
            profile.online = false;
            profile.lastSeenNanos = clock.getAsLong();
            store.saveProfile(snapshot(player));
        }
        return true;
    }

    /**
     * Зберегти в БД очки й позиції гравців у грі, що змінилися з минулого разу (щоб падіння сервера
     * не забрало прогрес). Викликається періодично з GameLoop.
     */
    public synchronized void flushProfiles() {
        for (var p : players.values()) {
            if (p.token == null || (p.x == p.savedX && p.z == p.savedZ && p.score == p.savedScore)) {
                continue;
            }
            store.saveProfile(snapshot(p));
            p.savedX = p.x;
            p.savedZ = p.z;
            p.savedScore = p.score;
        }
    }

    /** Зупинка сервера: зберегти всіх, хто в грі. */
    @PreDestroy
    synchronized void saveOnShutdown() {
        flushProfiles();
    }

    private static StoredProfile snapshot(Player p) {
        return new StoredProfile(p.token, p.name, p.gender, p.appearance, p.score, p.x, p.z, p.rotation, p.status,
                p.color);
    }

    /** Токен профілю гравця (для запису автора в чат); null — гравець без профілю. Клієнтам не передається. */
    public synchronized String profileToken(String id) {
        var p = players.get(id);
        return p == null ? null : p.token;
    }

    public synchronized boolean contains(String id) {
        return players.containsKey(id);
    }

    public synchronized List<PlayerInfo> snapshot() {
        return players.values().stream().map(this::info).toList();
    }

    /** Один крок руху в напрямку (dx, dz); |v| ≤ 1, менша довжина — коротший крок. */
    public synchronized void applyInput(String id, long seq, double dx, double dz) {
        var p = players.get(id);
        if (p == null || seq <= p.lastSeq || !Double.isFinite(dx) || !Double.isFinite(dz)) {
            return;
        }
        p.lastSeq = seq;

        long now = clock.getAsLong();
        refillTokens(p, now);
        if (p.inputTokens < 1) {
            // Введення частіше, ніж дозволяє тік-рейт: підтверджуємо seq, але не рухаємо.
            // Клієнт побачить розбіжність і сам повернеться на серверну позицію.
            return;
        }
        p.inputTokens -= 1;

        double len = Math.hypot(dx, dz);
        if (len == 0) {
            return;
        }
        if (len > 1) {
            dx /= len;
            dz /= len;
        }
        double step = SPEED * stepSeconds;
        p.rotation = Math.atan2(dx, dz);
        var next = resolveCollisions(p, clamp(p.x + dx * step), clamp(p.z + dz * step));
        double moved = Math.hypot(next.x - p.x, next.z - p.z);
        p.x = next.x;
        p.z = next.z;
        // «Йде» лише той, хто реально зрушив, а не просто впирається в когось.
        if (moved > step * 0.2) {
            p.movingUntilNanos = now + MOVING_GRACE_NANOS;
        }
    }

    /**
     * «Привітатись»: якщо хтось поруч уже простягнув руку цьому гравцю — рукостискання відбувається
     * (обидва повертаються одне до одного); інакше гравець простягає руку найближчому.
     * Повертає null, якщо поруч нікого або рука вже простягнута тому самому (повтор нічого не розсилає).
     */
    public synchronized HandshakeResult handshake(String id) {
        var p = players.get(id);
        if (p == null || isBusyShakingHands(p)) {
            return null;
        }
        long now = clock.getAsLong();
        handshakeOffers.values().removeIf(o -> now - o.expiresAtNanos >= 0);

        // Спершу — відповісти тому, хто вже простягнув руку (найближчому з таких).
        Player offerer = null;
        double best = HANDSHAKE_RANGE;
        for (var e : handshakeOffers.entrySet()) {
            var q = players.get(e.getKey());
            double d = q == null ? Double.MAX_VALUE : Math.hypot(q.x - p.x, q.z - p.z);
            if (q != null && e.getValue().to.equals(id) && d <= best && !isBusyShakingHands(q)) {
                best = d;
                offerer = q;
            }
        }
        if (offerer != null) {
            handshakeOffers.remove(offerer.id);
            handshakeOffers.remove(id);
            stepTowards(p, offerer);
            p.rotation = Math.atan2(offerer.x - p.x, offerer.z - p.z);
            offerer.rotation = Math.atan2(p.x - offerer.x, p.z - offerer.z);
            p.shakingUntilNanos = offerer.shakingUntilNanos = now + HANDSHAKE_NANOS;
            return new HandshakeResult(offerer.id, id, true, completeHandshake(offerer, p, now));
        }

        var target = nearest(p, HANDSHAKE_RANGE);
        if (target == null) {
            return null;
        }
        var existing = handshakeOffers.get(id);
        if (existing != null && existing.to.equals(target.id)) {
            return null; // уже чекаємо відповіді від цього гравця
        }
        handshakeOffers.put(id, new HandshakeOffer(target.id, now + HANDSHAKE_OFFER_NANOS));
        return new HandshakeResult(id, target.id, false);
    }

    /** Очки за рукостискання: +1 обом, не частіше {@link #HANDSHAKE_SCORE_COOLDOWN_NANOS} для тієї самої пари. */
    private Handshake completeHandshake(Player from, Player to, long now) {
        String pair = from.memberKey.compareTo(to.memberKey) < 0
                ? from.memberKey + "|" + to.memberKey
                : to.memberKey + "|" + from.memberKey;
        var last = scoredPairs.get(pair);
        boolean scored = last == null || now - last >= HANDSHAKE_SCORE_COOLDOWN_NANOS;
        var updates = new ArrayList<PlayerUpdate>();
        if (scored) {
            scoredPairs.put(pair, now);
            from.score++;
            to.score++;
            updates.add(update(from));
            updates.add(update(to));
        }
        return new Handshake(from.id, to.id, scored, updates);
    }

    // ---- Статус ----

    /**
     * Встановити статус (порожній — прибрати). Повертає оновлення для розсилки або null, якщо нічого
     * не змінилося чи статус міняють занадто часто.
     */
    public synchronized PlayerUpdate setStatus(String id, String rawStatus) {
        var p = players.get(id);
        long now = clock.getAsLong();
        if (p == null || now - p.statusReadyAtNanos < 0) {
            return null;
        }
        String status = cleanStatus(rawStatus);
        if (status.equals(p.status)) {
            return null;
        }
        p.status = status;
        p.statusReadyAtNanos = now + STATUS_COOLDOWN_NANOS;
        if (p.token != null) {
            store.saveProfile(snapshot(p)); // рідко — зберігаємо одразу, без очікування періодичного flush
        }
        return update(p);
    }

    static String cleanStatus(String raw) {
        String clean = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}<>]", "").strip().replaceAll("\\s+", " ");
        // Обрізаємо за кодовими точками, щоб не розрізати емодзі навпіл.
        return clean.codePointCount(0, clean.length()) > MAX_STATUS
                ? clean.substring(0, clean.offsetByCodePoints(0, MAX_STATUS)).strip()
                : clean;
    }

    // ---- Групові чати ----

    /**
     * Приєднатися до чату гравця {@code targetId}, стоячи в його колі. Якщо він ще ні з ким — утворюється група
     * з його кольором; якщо вже в групі — приєднуєшся до неї. Ніхто нічого не підтверджує.
     */
    public synchronized GroupOutcome joinPlayer(String id, String targetId) {
        var p = players.get(id);
        var q = players.get(targetId);
        if (p == null || q == null || p == q) {
            return GroupOutcome.fail("Цього гравця вже немає поруч");
        }
        if (Math.hypot(q.x - p.x, q.z - p.z) > Groups.CIRCLE_RADIUS + JOIN_TOLERANCE) {
            return GroupOutcome.fail("Підійди ближче до " + q.name + ", щоб приєднатися до чату");
        }
        if (groups.sameGroup(p.memberKey, q.memberKey)) {
            return GroupOutcome.fail("Ви з " + q.name + " вже в одному чаті");
        }
        var changes = new GroupChanges();
        leaveCurrentGroup(p, changes);
        var group = groups.groupOf(q.memberKey);
        if (group == null) {
            group = groups.create(q.memberKey, q.color);
            if (q.token != null) {
                store.createGroup(group.id, group.color, q.token);
            }
            changes.changed(q);
        }
        groups.add(group, p.memberKey);
        if (p.token != null) {
            store.setGroupMember(p.token, group.id);
        }
        changes.changed(p);
        return changes.outcome("Ти в чаті з " + q.name);
    }

    /** Вийти з групового чату — назад у загальний, зі своїм кольором. */
    public synchronized GroupOutcome leaveGroup(String id) {
        var p = players.get(id);
        if (p == null || groups.groupOf(p.memberKey) == null) {
            return GroupOutcome.fail("Ти і так у загальному чаті");
        }
        var changes = new GroupChanges();
        leaveCurrentGroup(p, changes);
        if (p.token != null) {
            store.setGroupMember(p.token, null);
        }
        changes.changed(p);
        return changes.outcome("Ти повернувся в загальний чат");
    }

    /** Група гравця (для чату) або null — загальний чат. */
    public synchronized String groupOf(String id) {
        var p = players.get(id);
        var group = p == null ? null : groups.groupOf(p.memberKey);
        return group == null ? null : group.id;
    }

    /** Id гравців у грі, що в чаті {@code group} (null — загальний: усі, хто ні в якій групі). */
    public synchronized List<String> chatRecipients(String group) {
        var g = groups.find(group);
        return players.values().stream()
                .filter(q -> group == null ? groups.groupOf(q.memberKey) == null : g != null && g.members.contains(q.memberKey))
                .map(q -> q.id)
                .toList();
    }

    /** Вийти з поточної групи з усіма наслідками: розпад групи з одного, новий господар і колір. */
    private void leaveCurrentGroup(Player p, GroupChanges changes) {
        var left = groups.leave(p.memberKey, this::personalColorOfMember);
        if (left == null) {
            return;
        }
        var group = left.group();
        if (left.dissolved()) {
            store.deleteGroup(group.id);
            changes.deleted.add(group.id);
            // Той, хто лишився сам, повертається в загальний чат зі своїм кольором.
            players.values().stream().filter(q -> q.memberKey.equals(left.lastMember())).forEach(changes::changed);
        } else if (left.hostChanged()) {
            String hostToken = tokenOfMember(group.hostKey);
            if (hostToken != null) {
                store.setGroupHost(group.id, hostToken, group.color);
            }
            // Колір групи змінився — перефарбовуємо всіх її учасників у грі.
            players.values().stream().filter(q -> group.members.contains(q.memberKey)).forEach(changes::changed);
        }
    }

    /** Власний колір гравця за memberKey (з тих, хто в грі, або з профілю). */
    private String personalColorOfMember(String memberKey) {
        for (var q : players.values()) {
            if (q.memberKey.equals(memberKey)) {
                return q.color;
            }
        }
        var profile = profiles.get(memberKey);
        return profile == null ? null : profile.color;
    }

    /**
     * Власний колір при вході: збережений, якщо ним не користується ніхто в грі й жодна група (крім групи,
     * де цей гравець — господар); інакше — новий вільний.
     */
    private String personalColorFor(String preferred, String memberKey) {
        var taken = new java.util.HashSet<String>();
        for (var q : players.values()) {
            if (q.color != null) {
                taken.add(q.color.toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (preferred != null && !taken.contains(preferred.toLowerCase(java.util.Locale.ROOT))) {
            var own = memberKey == null ? null : groups.groupOf(memberKey);
            boolean ownGroupColor = own != null && memberKey.equals(own.hostKey) && own.color.equalsIgnoreCase(preferred);
            if (ownGroupColor || !groups.colorUsedByGroup(preferred)) {
                return preferred;
            }
        }
        return groups.freeColor(taken);
    }

    /** memberKey — це токен профілю, крім гравців без профілю (тести). */
    private static String tokenOfMember(String memberKey) {
        return memberKey.startsWith("id:") ? null : memberKey;
    }

    /** Накопичує, кого зачепила дія з груповим чатом. */
    private final class GroupChanges {
        final Map<String, Player> changed = new LinkedHashMap<>();
        final List<String> deleted = new ArrayList<>();

        void changed(Player p) {
            changed.put(p.id, p);
        }

        GroupOutcome outcome(String message) {
            return new GroupOutcome(true, message, changed.values().stream().map(GameWorld.this::update).toList(),
                    List.copyOf(deleted));
        }
    }

    private PlayerUpdate update(Player p) {
        var group = groups.groupOf(p.memberKey);
        return new PlayerUpdate(p.id, p.score, group == null ? null : group.id, circleColor(p), p.status);
    }

    /** Колір кола: колір групи, якщо гравець у груповому чаті, інакше власний. */
    private String circleColor(Player p) {
        var group = groups.groupOf(p.memberKey);
        return group == null ? p.color : group.color;
    }

    /** Обидва роблять крок назустріч, щоб стояти на {@link #HANDSHAKE_DISTANCE}. */
    private void stepTowards(Player a, Player b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double d = Math.hypot(dx, dz);
        if (d <= HANDSHAKE_DISTANCE) {
            return;
        }
        double half = (d - HANDSHAKE_DISTANCE) / 2 / d;
        var na = resolveCollisions(a, clamp(a.x + dx * half), clamp(a.z + dz * half));
        a.x = na.x;
        a.z = na.z;
        var nb = resolveCollisions(b, clamp(b.x - dx * half), clamp(b.z - dz * half));
        b.x = nb.x;
        b.z = nb.z;
    }

    private boolean isBusyShakingHands(Player p) {
        return clock.getAsLong() - p.shakingUntilNanos < 0;
    }

    private Player nearest(Player p, double range) {
        return nearest(p, range, q -> true);
    }

    private Player nearest(Player p, double range, Predicate<Player> filter) {
        Player result = null;
        double best = range;
        for (var q : players.values()) {
            double d = Math.hypot(q.x - p.x, q.z - p.z);
            if (q != p && d <= best && filter.test(q)) {
                best = d;
                result = q;
            }
        }
        return result;
    }

    /** Стрибок; false — гравець ще в повітрі (тоді нічого не розсилаємо). */
    public synchronized boolean jump(String id) {
        var p = players.get(id);
        long now = clock.getAsLong();
        if (p == null || p.airborneUntilNanos - now > JUMP_TOLERANCE_NANOS) {
            return false;
        }
        p.airborneUntilNanos = now + JUMP_NANOS;
        return true;
    }

    /**
     * Замах на найближчого гравця в радіусі {@link #SLAP_RANGE}; той, хто б'є, розвертається до цілі.
     * Влучив чи ні, вирішується в момент удару — див. {@link #resolveSlaps()}.
     * Повертає null, якщо ще діє пауза між лящами (тоді нічого не розсилаємо).
     */
    public synchronized SlapResult slap(String id) {
        var p = players.get(id);
        long now = clock.getAsLong();
        if (p == null || now - p.slapReadyAtNanos < 0) {
            return null;
        }
        p.slapReadyAtNanos = now + SLAP_COOLDOWN_NANOS;

        // Своїх (спільна команда) не б'ємо — шукаємо найближчого чужого.
        var target = nearest(p, SLAP_RANGE, q -> !groups.sameGroup(p.memberKey, q.memberKey));
        if (target == null) {
            var ally = nearest(p, SLAP_RANGE, q -> true);
            return new SlapResult(id, null, ally == null ? null : ally.id);
        }
        double dx = target.x - p.x;
        double dz = target.z - p.z;
        double len = Math.max(Math.hypot(dx, dz), 1e-9);
        p.rotation = Math.atan2(dx, dz);
        pendingSlaps.add(new PendingSlap(id, target.id, dx / len, dz / len, now + SLAP_IMPACT_DELAY_NANOS));
        return new SlapResult(id, target.id, null);
    }

    /**
     * Лящі, для яких настав момент удару. Якщо ціль у повітрі — ухилилася; інакше її відкидає.
     * Викликається щотіку з GameLoop.
     */
    public synchronized List<SlapImpact> resolveSlaps() {
        if (pendingSlaps.isEmpty()) {
            return List.of();
        }
        long now = clock.getAsLong();
        var impacts = new ArrayList<SlapImpact>();
        for (var it = pendingSlaps.iterator(); it.hasNext(); ) {
            var slap = it.next();
            if (now - slap.impactAtNanos < 0) {
                continue;
            }
            it.remove();
            var target = players.get(slap.targetId);
            if (target == null) {
                continue; // ціль вийшла з гри
            }
            boolean airborne = now - target.airborneUntilNanos < 0;
            int penalty = 0;
            if (!airborne) {
                var next = resolveCollisions(target,
                        clamp(target.x + slap.dirX * SLAP_KNOCKBACK), clamp(target.z + slap.dirZ * SLAP_KNOCKBACK));
                target.x = next.x;
                target.z = next.z;
                // −1 тому, хто отримав ляща, але не нижче нуля; той, хто б'є, нічого не заробляє.
                penalty = target.score > 0 ? 1 : 0;
                target.score -= penalty;
            }
            impacts.add(new SlapImpact(slap.slapperId, slap.targetId, airborne, penalty,
                    penalty > 0 ? update(target) : null));
        }
        return impacts;
    }

    /** Поточні позиції всіх гравців для розсилки. */
    public synchronized List<PlayerPos> positions() {
        long now = clock.getAsLong();
        var result = new ArrayList<PlayerPos>(players.size());
        for (var p : players.values()) {
            result.add(new PlayerPos(p.id, round(p.x), round(p.z), round(p.rotation),
                    now - p.movingUntilNanos < 0, p.lastSeq));
        }
        return result;
    }

    private void refillTokens(Player p, long now) {
        double elapsed = (now - p.lastRefillNanos) / 1e9;
        p.inputTokens = Math.min(INPUT_BURST, p.inputTokens + elapsed * tickRate * INPUT_RATE_TOLERANCE);
        p.lastRefillNanos = now;
    }

    /**
     * Виштовхує бажану позицію з кіл інших гравців. Виштовхування йде вздовж нормалі,
     * тож дотична складова руху зберігається — персонаж «ковзає» навколо перешкоди.
     * Рухається лише {@code p}: інших штовхати не можна.
     */
    private Point resolveCollisions(Player p, double x, double z) {
        for (int i = 0; i < RESOLVE_ITERATIONS; i++) {
            boolean hit = false;
            for (var q : players.values()) {
                if (q == p) {
                    continue;
                }
                double dx = x - q.x;
                double dz = z - q.z;
                double d = Math.hypot(dx, dz);
                if (d >= MIN_DIST) {
                    continue;
                }
                if (d < 1e-9) {
                    // Точно в центрі іншого — виштовхуємо туди, звідки прийшли.
                    dx = p.x - q.x;
                    dz = p.z - q.z;
                    d = Math.hypot(dx, dz);
                    if (d < 1e-9) {
                        dx = 1;
                        dz = 0;
                        d = 1;
                    }
                }
                x = clamp(q.x + dx / d * MIN_DIST);
                z = clamp(q.z + dz / d * MIN_DIST);
                hit = true;
            }
            if (!hit) {
                return new Point(x, z);
            }
        }
        // У натовпі виштовхування може не зійтися — тоді просто стоїмо.
        return overlapsAnyone(p, x, z) ? new Point(p.x, p.z) : new Point(x, z);
    }

    private boolean overlapsAnyone(Player self, double x, double z) {
        for (var q : players.values()) {
            if (q != self && Math.hypot(x - q.x, z - q.z) < MIN_DIST - 1e-6) {
                return true;
            }
        }
        return false;
    }

    private Point findFreeSpot() {
        var rnd = ThreadLocalRandom.current();
        // Якщо центр заповнений, поступово розширюємо зону спавну.
        for (double area = SPAWN_AREA; ; area = Math.min(BOUNDARY, area * 1.5)) {
            for (int attempt = 0; attempt < 30; attempt++) {
                double x = rnd.nextDouble(-area, area);
                double z = rnd.nextDouble(-area, area);
                if (!overlapsAnyone(null, x, z)) {
                    return new Point(x, z);
                }
            }
            if (area >= BOUNDARY) {
                return new Point(rnd.nextDouble(-BOUNDARY, BOUNDARY), rnd.nextDouble(-BOUNDARY, BOUNDARY));
            }
        }
    }

    private PlayerInfo info(Player p) {
        var group = groups.groupOf(p.memberKey);
        return new PlayerInfo(p.id, p.name, p.gender, p.appearance, p.x, p.z, p.rotation, p.score,
                group == null ? null : group.id, circleColor(p), p.status);
    }

    private static double clamp(double v) {
        return Math.max(-BOUNDARY, Math.min(BOUNDARY, v));
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
