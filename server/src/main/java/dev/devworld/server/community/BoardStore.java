package dev.devworld.server.community;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Пости й коментарі скриньок і хто до яких скриньок приєднався (див. V6__boards_and_eggs.sql).
 * Виклики синхронні: пост потрібно одразу показати з його id, а викликають їх з віртуальних потоків WebSocket.
 */
public interface BoardStore {

    /** Пост ({@code postId == null}) або коментар до поста {@code postId}; {@code authorName == null} — анонімно. */
    record Entry(long id, String board, Long postId, String authorToken, String authorName, String text, long at) {
        public boolean isPost() {
            return postId == null;
        }
    }

    /** Пост для списку: скільки коментарів і id найсвіжішого запису (пост або коментар) — щоб позначити нове. */
    record PostSummary(Entry post, int comments, long lastActivityId) {
    }

    /** Пости скриньки, від найсвіжішої активності (новий коментар піднімає пост угору). */
    List<PostSummary> posts(String board, int limit);

    /** Коментарі до поста, від старих до нових. */
    List<Entry> comments(long postId);

    Optional<Entry> entry(long id);

    /** Новий пост ({@code postId == null}) або коментар; повертає збережений запис з id і часом. */
    Entry add(String board, Long postId, String authorToken, String authorName, String text);

    /** Id найсвіжішого запису скриньки (0 — порожня). */
    long latestId(String board);

    /** Скриньки, до яких приєднався гравець → до якого id прочитав. */
    Map<String, Long> memberships(String token);

    /** Приєднати (або оновити «прочитано до»). Позначка прочитаного лише зростає. */
    void markRead(String token, String board, long lastReadId);

    void leave(String token, String board);

    /** Скриньки, у яких гравець вимкнув звук нових повідомлень. */
    Set<String> muted(String token);

    /** Вимкнути / увімкнути звук (лише для скриньки, до якої гравець приєднаний). */
    void setMuted(String token, String board, boolean muted);

    /** Скільки в скриньці записів інших гравців після {@code afterId}. */
    int unreadSince(String board, long afterId, String token);
}
