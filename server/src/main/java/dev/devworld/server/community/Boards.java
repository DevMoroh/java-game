package dev.devworld.server.community;

import dev.devworld.server.community.BoardMessages.BoardCommentAdded;
import dev.devworld.server.community.BoardMessages.BoardInfo;
import dev.devworld.server.community.BoardMessages.BoardOpened;
import dev.devworld.server.community.BoardMessages.BoardPostAdded;
import dev.devworld.server.community.BoardMessages.BoardStatus;
import dev.devworld.server.community.BoardMessages.BoardThread;
import dev.devworld.server.community.BoardMessages.CommentView;
import dev.devworld.server.community.BoardMessages.PostView;
import dev.devworld.server.community.BoardStore.Entry;
import dev.devworld.server.game.Messages.Notice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Скриньки на полі: тематичні дошки з постами й коментарями.
 * <p>
 * Відкрити скриньку можна, підійшовши до неї. Хто приєднався до скриньки (кнопкою або першим постом), бачить над нею
 * на карті лічильник непрочитаного — свій у кожного: записи інших гравців після того, що він уже бачив. Поки скринька
 * відкрита, нове одразу вважається прочитаним. Хто вийшов зі скриньки, лічильника більше не бачить.
 */
@Component
public class Boards {

    /** З якої відстані до скриньки її можна відкрити. */
    public static final double OPEN_RANGE = 2.5;
    /** Запас на затримку: клієнт бачить свою позицію трохи раніше за сервер. */
    static final double OPEN_TOLERANCE = 1.5;
    static final int POST_MAX = 1000;
    static final int COMMENT_MAX = 500;
    static final int POSTS_LIMIT = 100;
    static final String ANONYMOUS_ONLY = "reviews";
    /** Очки за кожен пост чи коментар у скриньці (частоту обмежує {@link #limiter}). */
    public static final int MESSAGE_POINTS = 1;

    public static final List<BoardInfo> ALL = List.of(
            new BoardInfo(ANONYMOUS_ONLY, "Company reviews",
                    "Had a bad experience with a company? Leave a review here — every post and comment in this box "
                            + "is anonymous, nobody sees who wrote it.", true, 4.1, -11.3),
            new BoardInfo("interviews", "Interview feedback",
                    "Just had a technical interview? Tell others which company it was, what they asked and how it went.",
                    false, 15.2, -5.1),
            new BoardInfo("offers", "Got an offer",
                    "Got an offer? Share your experience, give advice to those who are still searching.",
                    false, 10.1, 6.5),
            new BoardInfo("advice", "Interview advice",
                    "Ask anything about technical interviews — people who have been through many of them answer here.",
                    false, 1.6, 15.9),
            new BoardInfo("vacancies", "Vacancies",
                    "Discuss job openings: drop a link to a vacancy, ask what others know about the team and the company.",
                    false, -8.6, 8.4),
            new BoardInfo("job-search", "Looking for a job",
                    "Looking for a job in IT? Tell what you do and what you are looking for — someone may know a team.",
                    false, -15.9, -2.0),
            new BoardInfo("miltech", "MilTech / DefTech jobs",
                    "Looking for a job in MilTech or DefTech, or hiring for it? Everything about defence tech jobs here.",
                    false, -6.3, -10.2),
            new BoardInfo("embedded", "Embedded Development",
                    "Firmware, microcontrollers, RTOS, drivers and hardware bring-up — ask questions, share projects "
                            + "and jobs in embedded development.",
                    false, 8.2, 13.0));

    private static final Map<String, BoardInfo> BY_ID = new LinkedHashMap<>();

    static {
        ALL.forEach(b -> BY_ID.put(b.id(), b));
    }

    /** Гравець онлайн: його профіль, яку скриньку зараз дивиться, до яких приєднаний і скільки там непрочитаного. */
    private static final class Visitor {
        final String token;
        String viewing;
        /** Скринька → до якого id прочитано (лише ті, до яких приєднався). */
        final Map<String, Long> lastRead;
        final Map<String, Integer> unread = new HashMap<>();
        /** Скриньки, де звук нових повідомлень вимкнено. */
        final Set<String> muted;

        Visitor(String token, Map<String, Long> lastRead, Set<String> muted) {
            this.token = token;
            this.lastRead = new HashMap<>(lastRead);
            this.muted = new HashSet<>(muted);
        }
    }

    private final Map<String, Visitor> visitors = new HashMap<>();
    private final BoardStore store;
    private final RateLimiter limiter;

    @Autowired
    public Boards(BoardStore store) {
        this(store, System::nanoTime);
    }

    Boards(BoardStore store, LongSupplier nanos) {
        this.store = store;
        // Пости й коментарі разом: 5 підряд, далі один на 5 с.
        this.limiter = new RateLimiter(5, 5_000_000_000L, nanos);
    }

    /** Гравець увійшов: його скриньки й непрочитане з БД. */
    public synchronized Envelope connect(String playerId, String token) {
        var visitor = new Visitor(token, store.memberships(token), store.muted(token));
        visitor.lastRead.keySet().removeIf(board -> !BY_ID.containsKey(board));
        visitor.lastRead.forEach((board, read) -> visitor.unread.put(board, store.unreadSince(board, read, token)));
        visitors.put(playerId, visitor);
        return status(playerId, visitor);
    }

    public synchronized void disconnect(String playerId) {
        visitors.remove(playerId);
        limiter.forget(playerId);
    }

    /** Відкрити скриньку, стоячи біля неї ({@code x, z} — позиція гравця). */
    public synchronized List<Envelope> open(String playerId, String boardId, double x, double z) {
        var visitor = visitors.get(playerId);
        var board = BY_ID.get(boardId);
        if (visitor == null || board == null) {
            return List.of();
        }
        if (Math.hypot(x - board.x(), z - board.z()) > OPEN_RANGE + OPEN_TOLERANCE) {
            return List.of(notice(playerId, "Walk up to the box to open it"));
        }
        visitor.viewing = boardId;
        Long lastRead = visitor.lastRead.get(boardId);
        var posts = store.posts(boardId, POSTS_LIMIT).stream()
                .map(s -> postView(s.post(), visitor, s.comments(), s.lastActivityId()))
                .toList();
        var out = new ArrayList<Envelope>();
        out.add(new Envelope(playerId, new BoardOpened(boardId, lastRead != null, lastRead, posts)));
        if (lastRead != null) {
            markAllRead(visitor, boardId);
            out.add(status(playerId, visitor));
        }
        return out;
    }

    /** Гравець закрив скриньку — нове знову рахується як непрочитане. */
    public synchronized void close(String playerId) {
        var visitor = visitors.get(playerId);
        if (visitor != null) {
            visitor.viewing = null;
        }
    }

    /** Приєднатися до скриньки ({@code on}) — бачити над нею лічильник нового; або вийти з неї. */
    public synchronized List<Envelope> subscribe(String playerId, String boardId, boolean on) {
        var visitor = visitors.get(playerId);
        if (visitor == null || !BY_ID.containsKey(boardId)) {
            return List.of();
        }
        if (on) {
            markAllRead(visitor, boardId);
        } else {
            store.leave(visitor.token, boardId);
            visitor.lastRead.remove(boardId);
            visitor.unread.remove(boardId);
            visitor.muted.remove(boardId);
        }
        return List.of(status(playerId, visitor));
    }

    /** Чи серед результату є новий пост або коментар (тоді автор отримує {@link #MESSAGE_POINTS}). */
    public static boolean published(List<Envelope> out) {
        return out.stream().anyMatch(e -> e.message() instanceof BoardPostAdded || e.message() instanceof BoardCommentAdded);
    }

    /** Вимкнути ({@code muted}) або увімкнути для себе звук нових повідомлень скриньки, до якої приєднаний. */
    public synchronized List<Envelope> mute(String playerId, String boardId, boolean muted) {
        var visitor = visitors.get(playerId);
        if (visitor == null || !visitor.lastRead.containsKey(boardId)) {
            return List.of();
        }
        store.setMuted(visitor.token, boardId, muted);
        if (muted) {
            visitor.muted.add(boardId);
        } else {
            visitor.muted.remove(boardId);
        }
        return List.of(status(playerId, visitor));
    }

    /** Новий пост у відкриту скриньку. Автор приєднується до скриньки. */
    public synchronized List<Envelope> post(String playerId, String name, String rawText) {
        var visitor = visitors.get(playerId);
        if (visitor == null || visitor.viewing == null) {
            return List.of();
        }
        String text = clean(rawText, POST_MAX);
        if (text.isEmpty()) {
            return List.of();
        }
        if (!limiter.tryAcquire(playerId)) {
            return List.of(notice(playerId, "Slow down — wait a few seconds"));
        }
        var board = BY_ID.get(visitor.viewing);
        var entry = store.add(board.id(), null, visitor.token, board.anonymous() ? null : name, text);
        return delivered(entry, (v) -> new BoardPostAdded(board.id(), postView(entry, v, 0, entry.id())));
    }

    /** Коментар до поста у відкритій скриньці. Автор приєднується до скриньки. */
    public synchronized List<Envelope> comment(String playerId, String name, long postId, String rawText) {
        var visitor = visitors.get(playerId);
        if (visitor == null || visitor.viewing == null) {
            return List.of();
        }
        var post = store.entry(postId).filter(e -> e.isPost() && e.board().equals(visitor.viewing)).orElse(null);
        String text = clean(rawText, COMMENT_MAX);
        if (post == null || text.isEmpty()) {
            return List.of();
        }
        if (!limiter.tryAcquire(playerId)) {
            return List.of(notice(playerId, "Slow down — wait a few seconds"));
        }
        var board = BY_ID.get(post.board());
        var entry = store.add(board.id(), postId, visitor.token, board.anonymous() ? null : name, text);
        int count = store.comments(postId).size();
        return delivered(entry, (v) -> new BoardCommentAdded(board.id(), postId, commentView(entry, v), count));
    }

    /** Коментарі до поста відкритої скриньки. */
    public synchronized List<Envelope> thread(String playerId, long postId) {
        var visitor = visitors.get(playerId);
        if (visitor == null || visitor.viewing == null) {
            return List.of();
        }
        var post = store.entry(postId).filter(e -> e.isPost() && e.board().equals(visitor.viewing)).orElse(null);
        if (post == null) {
            return List.of();
        }
        var comments = store.comments(postId).stream().map(c -> commentView(c, visitor)).toList();
        return List.of(new Envelope(playerId, new BoardThread(post.board(), postId, comments)));
    }

    /**
     * Розіслати новий запис: тим, хто дивиться скриньку, — сам запис (і для них він одразу прочитаний);
     * іншим приєднаним — новий лічильник. Автор приєднується до скриньки.
     */
    private List<Envelope> delivered(Entry entry, java.util.function.Function<Visitor, Object> view) {
        var out = new ArrayList<Envelope>();
        for (var e : visitors.entrySet()) {
            String id = e.getKey();
            var v = e.getValue();
            boolean author = Objects.equals(v.token, entry.authorToken());
            boolean watching = entry.board().equals(v.viewing);
            if (watching) {
                out.add(new Envelope(id, view.apply(v)));
            }
            if (author || (watching && v.lastRead.containsKey(entry.board()))) {
                markAllRead(v, entry.board());
                out.add(status(id, v));
            } else if (v.lastRead.containsKey(entry.board())) {
                v.unread.merge(entry.board(), 1, Integer::sum);
                out.add(status(id, v));
            }
        }
        return out;
    }

    private void markAllRead(Visitor visitor, String board) {
        long latest = store.latestId(board);
        store.markRead(visitor.token, board, latest);
        visitor.lastRead.merge(board, latest, Math::max);
        visitor.unread.put(board, 0);
    }

    private static Envelope status(String playerId, Visitor visitor) {
        var joined = ALL.stream().map(BoardInfo::id).filter(visitor.lastRead::containsKey).toList();
        var muted = joined.stream().filter(visitor.muted::contains).toList();
        return new Envelope(playerId, new BoardStatus(joined, Map.copyOf(visitor.unread), muted));
    }

    private static Envelope notice(String playerId, String text) {
        return new Envelope(playerId, new Notice(text, false));
    }

    private static PostView postView(Entry e, Visitor viewer, int comments, long lastActivity) {
        return new PostView(e.id(), e.authorName(), Objects.equals(e.authorToken(), viewer.token), e.text(), e.at(),
                comments, lastActivity);
    }

    private static CommentView commentView(Entry e, Visitor viewer) {
        return new CommentView(e.id(), e.authorName(), Objects.equals(e.authorToken(), viewer.token), e.text(), e.at());
    }

    /** Пробіли по краях геть, керівні символи геть (переноси рядків лишаємо, але не більше одного порожнього рядка). */
    static String clean(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n")
                .replaceAll("[\\p{Cntrl}&&[^\\n]]", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        return text.length() > max ? text.substring(0, max).strip() : text;
    }
}
