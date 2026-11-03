package dev.devworld.server.game;

import dev.devworld.server.game.Messages.ChatMessage;
import dev.devworld.server.store.GameStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Чати: груповий (хто приєднався один до одного) або загальний (хто ні в якій групі). Повідомлення зберігаються
 * в БД ({@link GameStore}); у пам'яті — останні {@link #HISTORY} на чат (підтягуються з БД при першому запиті).
 * Обмеження частоти на кожного гравця — token bucket: {@link #BURST} підряд, далі одне на {@link #REFILL_NANOS}.
 */
@Component
public class Chat {

    static final int MAX_LENGTH = 300;
    static final int HISTORY = 50;
    static final int BURST = 5;
    static final long REFILL_NANOS = 1_000_000_000L;

    /** Результат відправки: {@code message} — що розіслати в чат, або {@code error} — що сказати автору. */
    public record PostResult(ChatMessage message, String error) {
    }

    private record Bucket(double tokens, long atNanos) {
    }

    /** Чат (id групи; {@link #GENERAL} — загальний) → останні повідомлення. */
    private static final String GENERAL = "";
    private final Map<String, ArrayDeque<ChatMessage>> histories = new HashMap<>();
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final LongSupplier nanos;
    private final Clock wallClock;
    private final GameStore store;

    @Autowired
    public Chat(GameStore store) {
        this(System::nanoTime, Clock.systemUTC(), store);
    }

    /** Без бази — для юніт-тестів. */
    Chat(LongSupplier nanos, Clock wallClock) {
        this(nanos, wallClock, GameStore.NONE);
    }

    Chat(LongSupplier nanos, Clock wallClock, GameStore store) {
        this.nanos = nanos;
        this.wallClock = wallClock;
        this.store = store;
    }

    /** {@code group} — груповий чат автора (null — загальний); {@code authorToken} — профіль для БД. */
    public synchronized PostResult post(String playerId, String authorToken, String name, String group, String rawText) {
        String text = clean(rawText);
        if (text.isEmpty()) {
            return new PostResult(null, null); // порожнє — просто ігноруємо
        }
        long now = nanos.getAsLong();
        var bucket = buckets.getOrDefault(playerId, new Bucket(BURST, now));
        double tokens = Math.min(BURST, bucket.tokens + (double) (now - bucket.atNanos) / REFILL_NANOS);
        if (tokens < 1) {
            buckets.put(playerId, new Bucket(tokens, now));
            return new PostResult(null, "Slow down — wait a second");
        }
        buckets.put(playerId, new Bucket(tokens - 1, now));

        var message = new ChatMessage(group, playerId, name, text, wallClock.millis());
        store.saveChat(message, authorToken);
        var history = history0(group);
        history.addLast(message);
        while (history.size() > HISTORY) {
            history.removeFirst();
        }
        return new PostResult(message, null);
    }

    /** Останні повідомлення чату ({@code group == null} — загального). */
    public synchronized List<ChatMessage> history(String group) {
        return List.copyOf(history0(group));
    }

    /** Група розпалася — її переписка зникає (у БД — каскадом разом з групою). */
    public synchronized void dropGroup(String group) {
        histories.remove(key(group));
    }

    /** Сесія закрилася — її ліміт більше не потрібен. */
    public synchronized void forget(String playerId) {
        buckets.remove(playerId);
    }

    private ArrayDeque<ChatMessage> history0(String group) {
        return histories.computeIfAbsent(key(group), k -> new ArrayDeque<>(store.loadRecentChat(group, HISTORY)));
    }

    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        // Керівні символи (крім пробілів) прибираємо, переноси рядків — у пробіли.
        String text = raw.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\p{Cntrl}", "").strip();
        return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH).strip() : text;
    }

    private static String key(String group) {
        return group == null ? GENERAL : group.toLowerCase(Locale.ROOT);
    }
}
