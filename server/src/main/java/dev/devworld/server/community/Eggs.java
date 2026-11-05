package dev.devworld.server.community;

import dev.devworld.server.community.Articles.Article;
import dev.devworld.server.community.BoardMessages.BoardInfo;
import dev.devworld.server.game.GameWorld;
import dev.devworld.server.game.Messages.Notice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Золоті яйця: час від часу з'являються в випадкових місцях поля (не більше {@link #MAX_EGGS} одночасно).
 * Хто підійшов і відкрив яйце, читає статтю про розробку й або зберігає її в колекцію (яйце зникає), або викидає —
 * тоді яйце лягає поруч і через {@link #DROPPED_TTL_NANOS} зникає, якщо його ніхто не підбере. Поки яйце відкрите,
 * інші його не бачать.
 */
@Component
public class Eggs {

    public static final int MAX_EGGS = 5;
    /** Очки за знайдене й відкрите яйце (незалежно від того, зберіг чи викинув). */
    public static final int OPEN_POINTS = 1;
    /** З якої відстані можна підібрати яйце. */
    public static final double PICK_RANGE = 1.8;
    static final double PICK_TOLERANCE = 1.5;
    static final long DROPPED_TTL_NANOS = 120_000_000_000L;
    /** Яйця не з'являються біля скриньок і самого краю поля. */
    private static final double BOARD_CLEARANCE = 3;
    private static final double SPAWN_LIMIT = GameWorld.BOUNDARY - 3;

    /** Яйце на полі: {@code dropped} — його викинули, скоро зникне. */
    public record EggView(String id, double x, double z, String color, boolean dropped) {
    }

    public record EggSpawned(String type, EggView egg) {
        public EggSpawned(EggView egg) {
            this("eggSpawn", egg);
        }
    }

    public record EggGone(String type, String id) {
        public EggGone(String id) {
            this("eggGone", id);
        }
    }

    /**
     * Стаття для читання: {@code egg} — яйце, яке гравець зараз тримає (тоді можна зберегти чи викинути),
     * або null — читає зі своєї колекції; {@code collected} — стаття вже в колекції; {@code points} — скільки очок
     * дали за це відкриття ({@link #OPEN_POINTS} за перше відкриття цього яйця цим гравцем, інакше 0).
     */
    public record EggArticle(String type, String egg, Article article, boolean collected, int points) {
        public EggArticle(String egg, Article article, boolean collected, int points) {
            this("eggArticle", egg, article, collected, points);
        }
    }

    public record CollectionItem(String articleId, String title, String topic, String color, long at) {
    }

    public record EggCollection(String type, List<CollectionItem> items, int total) {
        public EggCollection(List<CollectionItem> items, int total) {
            this("eggCollection", items, total);
        }
    }

    private static final class Egg {
        final String id;
        final Article article;
        double x;
        double z;
        String heldBy;
        /** Хто вже отримав очки за це яйце: викинути й підняти знову — без нових очок. */
        final Set<String> rewarded = new HashSet<>();
        /** Коли зникне викинуте яйце (null — лежить, доки не підберуть). */
        Long expiresAtNanos;

        Egg(String id, Article article, double x, double z) {
            this.id = id;
            this.article = article;
            this.x = x;
            this.z = z;
        }

        EggView view() {
            return new EggView(id, x, z, article.color(), expiresAtNanos != null);
        }
    }

    private final Map<String, Egg> eggs = new LinkedHashMap<>();
    private final Articles articles;
    private final EggStore store;
    private final LongSupplier nanos;
    private final Random random;
    private final long spawnIntervalNanos;
    private long nextSpawnNanos;
    /**
     * Скільки яєць покласти одразу після старту (у тіку — коли схема БД уже накочена). Поки статей у БД немає,
     * пробуємо знову раз на {@link #EMPTY_RETRY_NANOS}: статті можуть додати, поки сервер працює.
     */
    private int pendingInitial;
    private long nextInitialTryNanos;
    static final long EMPTY_RETRY_NANOS = 10_000_000_000L;
    private int seq;

    @Autowired
    public Eggs(Articles articles, EggStore store,
                @Value("${game.eggs.spawn-interval-seconds:300}") long spawnIntervalSeconds,
                @Value("${game.eggs.initial:2}") int initial) {
        this(articles, store, System::nanoTime, new Random(), spawnIntervalSeconds * 1_000_000_000L, initial);
    }

    Eggs(Articles articles, EggStore store, LongSupplier nanos, Random random, long spawnIntervalNanos, int initial) {
        this.articles = articles;
        this.store = store;
        this.nanos = nanos;
        this.random = random;
        this.spawnIntervalNanos = spawnIntervalNanos;
        this.pendingInitial = Math.min(initial, MAX_EGGS);
        this.nextInitialTryNanos = nanos.getAsLong();
        this.nextSpawnNanos = nanos.getAsLong() + spawnIntervalNanos;
    }

    /** Яйця, що лежать на полі (для нового гравця). */
    public synchronized List<EggView> snapshot() {
        return eggs.values().stream().filter(e -> e.heldBy == null).map(Egg::view).toList();
    }

    /** Раз на секунду з ігрового циклу: нове яйце за розкладом і зникнення викинутих. */
    public synchronized List<Envelope> tick() {
        long now = nanos.getAsLong();
        var out = new ArrayList<Envelope>();
        var expired = eggs.values().stream()
                .filter(e -> e.heldBy == null && e.expiresAtNanos != null && now - e.expiresAtNanos >= 0)
                .toList();
        for (var egg : expired) {
            eggs.remove(egg.id);
            out.add(Envelope.everyone(new EggGone(egg.id)));
        }
        if (pendingInitial > 0 && now - nextInitialTryNanos >= 0) {
            nextInitialTryNanos = now + EMPTY_RETRY_NANOS;
            while (pendingInitial > 0) {
                var egg = spawn();
                if (egg == null) {
                    if (eggs.size() >= MAX_EGGS) {
                        pendingInitial = 0;
                    }
                    break;
                }
                pendingInitial--;
                out.add(Envelope.everyone(new EggSpawned(egg.view())));
            }
        }
        if (now - nextSpawnNanos >= 0) {
            nextSpawnNanos = now + spawnIntervalNanos;
            var egg = spawn();
            if (egg != null) {
                out.add(Envelope.everyone(new EggSpawned(egg.view())));
            }
        }
        return out;
    }

    /** Підібрати яйце поруч і прочитати статтю. Інші перестають його бачити. */
    public synchronized List<Envelope> open(String playerId, String token, String eggId, double x, double z) {
        var egg = eggs.get(eggId);
        if (egg == null || egg.heldBy != null) {
            return List.of(new Envelope(playerId, new Notice("Someone already took this egg", false)));
        }
        if (Math.hypot(x - egg.x, z - egg.z) > PICK_RANGE + PICK_TOLERANCE) {
            return List.of(new Envelope(playerId, new Notice("Walk up to the egg to open it", false)));
        }
        if (eggs.values().stream().anyMatch(e -> playerId.equals(e.heldBy))) {
            return List.of(new Envelope(playerId, new Notice("Keep or throw away the egg you are holding first", false)));
        }
        egg.heldBy = playerId;
        egg.expiresAtNanos = null;
        boolean collected = store.collection(token).stream().anyMatch(c -> c.articleId().equals(egg.article.id()));
        // Свіжа версія з БД: статтю могли відредагувати через адмін-API, поки яйце лежало на полі.
        var article = articles.find(egg.article.id()).orElse(egg.article);
        int points = egg.rewarded.add(token) ? OPEN_POINTS : 0;
        return List.of(
                Envelope.everyone(new EggGone(egg.id)),
                new Envelope(playerId, new EggArticle(egg.id, article, collected, points)));
    }

    /** Зберегти статтю відкритого яйця в колекцію — яйце зникає з гри. */
    public synchronized List<Envelope> keep(String playerId, String token) {
        var egg = held(playerId);
        if (egg == null) {
            return List.of();
        }
        eggs.remove(egg.id);
        try {
            store.add(token, egg.article.id());
        } catch (RuntimeException e) {
            // Статтю прибрали з БД, поки яйце лежало на полі.
            return List.of(new Envelope(playerId, new Notice("This egg has crumbled — the article is gone", false)));
        }
        return List.of(collection(playerId, token));
    }

    /** Викинути відкрите яйце: лягає біля гравця й скоро зникне, якщо ніхто не підбере. */
    public synchronized List<Envelope> drop(String playerId, double x, double z) {
        var egg = held(playerId);
        if (egg == null) {
            return List.of();
        }
        egg.heldBy = null;
        egg.x = clamp(x + (random.nextDouble() - 0.5));
        egg.z = clamp(z + (random.nextDouble() - 0.5));
        egg.expiresAtNanos = nanos.getAsLong() + DROPPED_TTL_NANOS;
        return List.of(Envelope.everyone(new EggSpawned(egg.view())));
    }

    /** Гравець вийшов з яйцем у руках — воно падає там, де він стояв. */
    public synchronized List<Envelope> disconnect(String playerId, double x, double z) {
        return drop(playerId, x, z);
    }

    /** Колекція гравця (при вході й після збереження). */
    public synchronized Envelope collection(String playerId, String token) {
        var items = store.collection(token).stream()
                .flatMap(c -> articles.find(c.articleId()).stream()
                        .map(a -> new CollectionItem(a.id(), a.title(), a.topic(), a.color(), c.at())))
                .toList();
        return new Envelope(playerId, new EggCollection(items, articles.all().size()));
    }

    /** Перечитати статтю зі своєї колекції. */
    public synchronized List<Envelope> read(String playerId, String token, String articleId) {
        boolean collected = store.collection(token).stream().anyMatch(c -> c.articleId().equals(articleId));
        return collected
                ? articles.find(articleId).map(a -> new Envelope(playerId, new EggArticle(null, a, true, 0))).stream().toList()
                : List.of();
    }

    private Egg held(String playerId) {
        return eggs.values().stream().filter(e -> playerId.equals(e.heldBy)).findFirst().orElse(null);
    }

    /** Нове яйце зі статтею, якої зараз немає на полі; null — уже досить. */
    private Egg spawn() {
        if (eggs.size() >= MAX_EGGS) {
            return null;
        }
        var taken = new HashSet<String>();
        eggs.values().forEach(e -> taken.add(e.article.id()));
        var choices = articles.all().stream().filter(a -> !taken.contains(a.id())).toList();
        if (choices.isEmpty()) {
            return null;
        }
        var article = choices.get(random.nextInt(choices.size()));
        double x = 0;
        double z = 0;
        for (int attempt = 0; attempt < 50; attempt++) {
            x = (random.nextDouble() * 2 - 1) * SPAWN_LIMIT;
            z = (random.nextDouble() * 2 - 1) * SPAWN_LIMIT;
            if (freeSpot(x, z)) {
                break;
            }
        }
        var egg = new Egg("egg-" + (++seq), article, x, z);
        eggs.put(egg.id, egg);
        return egg;
    }

    private boolean freeSpot(double x, double z) {
        for (BoardInfo b : Boards.ALL) {
            if (Math.hypot(x - b.x(), z - b.z()) < BOARD_CLEARANCE) {
                return false;
            }
        }
        return eggs.values().stream().noneMatch(e -> Math.hypot(x - e.x, z - e.z) < BOARD_CLEARANCE);
    }

    private static double clamp(double v) {
        return Math.max(-GameWorld.BOUNDARY, Math.min(GameWorld.BOUNDARY, v));
    }
}
