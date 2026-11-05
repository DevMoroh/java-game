package dev.devworld.server.community;

import java.util.List;
import java.util.Map;

/** Повідомлення сервер → клієнт про скриньки. {@code author == null} — анонімно; {@code mine} — це написав сам отримувач. */
public final class BoardMessages {

    private BoardMessages() {
    }

    /** Скринька на полі (приходить у welcome). */
    public record BoardInfo(String id, String title, String description, boolean anonymous, double x, double z) {
    }

    /** Пост у списку: {@code lastActivity} — id найсвіжішого запису (сам пост або коментар до нього). */
    public record PostView(long id, String author, boolean mine, String text, long at, int comments, long lastActivity) {
    }

    public record CommentView(long id, String author, boolean mine, String text, long at) {
    }

    /**
     * До яких скриньок гравець приєднався, скільки в кожній непрочитаного і де він вимкнув звук нових повідомлень.
     * Лише цьому гравцю.
     */
    public record BoardStatus(String type, List<String> joined, Map<String, Integer> unread, List<String> muted) {
        public BoardStatus(List<String> joined, Map<String, Integer> unread, List<String> muted) {
            this("boardStatus", joined, unread, muted);
        }
    }

    /**
     * Відкрита скринька: пости й {@code lastRead} — до якого id гравець прочитав до відкриття
     * (новіші позначаються як нові; null — гравець не приєднаний, нічого не позначаємо).
     */
    public record BoardOpened(String type, String board, boolean joined, Long lastRead, List<PostView> posts) {
        public BoardOpened(String board, boolean joined, Long lastRead, List<PostView> posts) {
            this("boardOpened", board, joined, lastRead, posts);
        }
    }

    /** Коментарі до поста (коли гравець розгорнув пост). */
    public record BoardThread(String type, String board, long post, List<CommentView> comments) {
        public BoardThread(String board, long post, List<CommentView> comments) {
            this("boardThread", board, post, comments);
        }
    }

    /** Новий пост — усім, хто зараз дивиться цю скриньку. */
    public record BoardPostAdded(String type, String board, PostView post) {
        public BoardPostAdded(String board, PostView post) {
            this("boardPost", board, post);
        }
    }

    /** Новий коментар — усім, хто зараз дивиться цю скриньку; {@code comments} — скільки їх тепер у пості. */
    public record BoardCommentAdded(String type, String board, long post, CommentView comment, int comments) {
        public BoardCommentAdded(String board, long post, CommentView comment, int comments) {
            this("boardComment", board, post, comment, comments);
        }
    }
}
