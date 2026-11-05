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
 * Золоті яйця: на полі лежать яйця зі статтями (не більше {@link #MAX_EGGS}, кожна стаття — щонайбільше одне яйце).
 * Кожна стаття стає яйцем для гравця лише раз: відкрив яйце (зберіг чи викинув) — стаття для нього «знайдена»
 * (egg_seen), таких яєць він більше не бачить і очок за них не отримує. Тож поки нових для гравця статей немає,
 * яєць для нього теж немає.
 * <p>
 * Нові яйця — лише зі статей, яких ще не знаходив хтось із тих, хто зараз у грі: щойно прийшов гравець — одразу до
 * {@code game.eggs.initial} яєць для нього, далі одне за {@code game.eggs.spawn-interval-seconds}. Викинуте яйце лягає
 * поруч і через {@link #DROPPED_TTL_NANOS} зникає, якщо його не підбере той, хто цієї статті ще не бачив. Поки яйце
 * відкрите, інші його не бачать.
 */
@Component
public class Eggs {

    public static final int MAX_EGGS = 5;
    /** Очки за знайдену нову статтю (незалежно від того, зберіг чи викинув). */
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
     * дали за це відкриття ({@link #OPEN_POINTS}, якщо гравець знайшов цю статтю вперше, інакше 0).
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

    /** Гравець у грі: які статті вже знайшов (спільне для всіх вкладок з тим самим профілем). */
    private record Visitor(String token, Set<String> seen) {
    }

    private final Map<String, Egg> eggs = new LinkedHashMap<>();
    private final Map<String, Visitor> visitors = new LinkedHashMap<>();
    private final Articles articles;
    private final EggStore store;
    private final LongSupplier nanos;
    private final Random random;
    private final long spawnIntervalNanos;
    /** Скільки яєць одразу покласти для гравця, що щойно прийшов. */
    private final int initial;
    private long nextSpawnNanos;
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
        this.initial = Math.min(initial, MAX_EGGS);
        this.nextSpawnNanos = nanos.getAsLong() + spawnIntervalNanos;
    }

    /**
     * Гравець зайшов: запам'ятовуємо, що він уже знайшов, і, якщо для нього є нові статті, одразу кладемо до
     * {@link #initial} яєць, які він побачить. Повертає оголошення цих яєць для інших гравців (сам він отримає їх
     * у welcome через {@link #snapshot(String)}).
     */
    public synchronized List<Envelope> connect(String playerId, String token) {
        visitors.put(playerId, new Visitor(token, store.seen(token)));
        var out = new ArrayList<Envelope>();
        long visible = eggs.values().stream().filter(e -> visibleTo(e, playerId)).count();
        for (long i = visible; i < initial; i++) {
            var egg = spawn(playerId);
            if (egg == null) {
                break;
            }
            out.addAll(announce(egg, playerId));
        }
        return out;
    }

    /** Яйця на полі, які бачить цей гравець (нові для нього статті). */
    public synchronized List<EggView> snapshot(String playerId) {
        return eggs.values().stream().filter(e -> visibleTo(e, playerId)).map(Egg::view).toList();
    }

    /** Раз на секунду з ігрового циклу: зникнення викинутих і нове яйце за розкладом (якщо комусь у грі є що знайти). */
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
        if (now - nextSpawnNanos >= 0) {
            nextSpawnNanos = now + spawnIntervalNanos;
            var egg = spawn(null);
            if (egg != null) {
                out.addAll(announce(egg, null));
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
        if (seen(playerId, token).contains(egg.article.id())) {
            return List.of(new Envelope(playerId, new Notice("You have already found this one", false)));
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
        int points = store.markSeen(token, egg.article.id()) ? OPEN_POINTS : 0;
        // Знайдено — і для всіх вкладок цього профілю.
        visitors.values().stream().filter(v -> v.token().equals(token)).forEach(v -> v.seen().add(egg.article.id()));
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
        // Бачать лише ті, хто цієї статті ще не знаходив (той, хто викинув, — уже знайшов).
        return announce(egg, null);
    }

    /** Гравець вийшов; якщо з яйцем у руках — воно падає там, де він стояв. */
    public synchronized List<Envelope> disconnect(String playerId, double x, double z) {
        var out = drop(playerId, x, z);
        visitors.remove(playerId);
        return out;
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

    /**
     * Нове яйце зі статтею, якої ще немає на полі й якої не знаходив {@code forPlayer} (або, коли null, — хтось у грі;
     * беремо ту, що нова для найбільшої кількості гравців). Поле повне — звільняє місце від яйця, яке нікому в грі вже
     * не потрібне. null — нема чого класти.
     */
    private Egg spawn(String forPlayer) {
        var onField = new HashSet<String>();
        eggs.values().forEach(e -> onField.add(e.article.id()));
        var fresh = articles.all().stream().filter(a -> !onField.contains(a.id())).toList();
        List<Article> choices;
        if (forPlayer != null) {
            var seen = visitors.get(forPlayer).seen();
            choices = fresh.stream().filter(a -> !seen.contains(a.id())).toList();
        } else {
            int best = fresh.stream().mapToInt(this::wantedBy).max().orElse(0);
            choices = best == 0 ? List.of() : fresh.stream().filter(a -> wantedBy(a) == best).toList();
        }
        if (choices.isEmpty() || (eggs.size() >= MAX_EGGS && !evictUnwanted())) {
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

    /** Скільки гравців у грі ще не знаходили цю статтю. */
    private int wantedBy(Article article) {
        return (int) visitors.values().stream().filter(v -> !v.seen().contains(article.id())).count();
    }

    /** Прибирає з повного поля яйце, якого ніхто в грі не бачить (усі вже знайшли); false — такого немає. */
    private boolean evictUnwanted() {
        var unwanted = eggs.values().stream()
                .filter(e -> e.heldBy == null && wantedBy(e.article) == 0)
                .findFirst();
        unwanted.ifPresent(e -> eggs.remove(e.id));
        return unwanted.isPresent();
    }

    /** Нове (або знову покладене) яйце — тим, хто в грі й ще не знаходив цієї статті; крім {@code except}. */
    private List<Envelope> announce(Egg egg, String except) {
        var out = new ArrayList<Envelope>();
        for (var playerId : visitors.keySet()) {
            if (!playerId.equals(except) && visibleTo(egg, playerId)) {
                out.add(new Envelope(playerId, new EggSpawned(egg.view())));
            }
        }
        return out;
    }

    private boolean visibleTo(Egg egg, String playerId) {
        var visitor = visitors.get(playerId);
        return egg.heldBy == null && visitor != null && !visitor.seen().contains(egg.article.id());
    }

    private Set<String> seen(String playerId, String token) {
        var visitor = visitors.get(playerId);
        return visitor != null ? visitor.seen() : store.seen(token);
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
