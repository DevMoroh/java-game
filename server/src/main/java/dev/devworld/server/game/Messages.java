package dev.devworld.server.game;

import java.util.List;

/** Повідомлення сервер → клієнт. Поле {@code type} визначає тип повідомлення. */
public final class Messages {

    private Messages() {
    }

    public record PlayerInfo(String id, String name, Gender gender, Appearance appearance,
                             double x, double z, double rotation, int score, String group, String color,
                             String status) {
    }

    /**
     * Змінилися очки, груповий чат ({@code group} — id групи або null, якщо гравець у загальному чаті),
     * колір кола ({@code color} — колір групи або власний) чи статус гравця. Розсилається всім.
     */
    public record PlayerUpdate(String type, String id, int score, String group, String color, String status) {
        public PlayerUpdate(String id, int score, String group, String color, String status) {
            this("playerUpdate", id, score, group, color, status);
        }
    }

    /** Коротке повідомлення одному гравцю (результат дії з груповим чатом або помилка). */
    public record Notice(String type, String text, boolean ok) {
        public Notice(String text, boolean ok) {
            this("notice", text, ok);
        }
    }

    /** {@code lastSeq} — останнє оброблене введення цього гравця (потрібне лише йому самому). */
    public record PlayerPos(String id, double x, double z, double rotation, boolean moving, long lastSeq) {
    }

    /**
     * Параметри руху, щоб клієнт симулював свій рух рівно так само, як сервер.
     * {@code boundary} — гравець ходить у межах [-boundary, boundary] по X і Z.
     */
    public record MoveRules(int tickRate, double speed, double playerRadius, double boundary) {
    }

    /** {@code token} — токен профілю: клієнт зберігає його й передає в наступному join, щоб повернутися тим самим персонажем. */
    public record Welcome(String type, String selfId, String token, double worldSize, MoveRules rules, List<PlayerInfo> players) {
        public Welcome(String selfId, String token, double worldSize, MoveRules rules, List<PlayerInfo> players) {
            this("welcome", selfId, token, worldSize, rules, players);
        }
    }

    public record Joined(String type, PlayerInfo player) {
        public Joined(PlayerInfo player) {
            this("joined", player);
        }
    }

    public record Left(String type, String id) {
        public Left(String id) {
            this("left", id);
        }
    }

    /**
     * Замах: {@code to == null} — бити нікого; тоді {@code ally} — найближчий свій (з команди), якого бити не можна.
     * Розсилається всім одразу.
     */
    public record Slap(String type, String from, String to, String ally) {
        public Slap(String from, String to, String ally) {
            this("slap", from, to, ally);
        }
    }

    /** Момент удару: {@code dodged} — ціль ухилилася стрибком; {@code penalty} — скільки очок зняли (0 або 1). */
    public record SlapImpact(String type, String from, String to, boolean dodged, int penalty) {
        public SlapImpact(String from, String to, boolean dodged, int penalty) {
            this("slapImpact", from, to, dodged, penalty);
        }
    }

    /** {@code from} простягнув руку {@code to}; рука чекає {@code expiresInMs}. */
    public record HandshakeOffer(String type, String from, String to, long expiresInMs) {
        public HandshakeOffer(String from, String to, long expiresInMs) {
            this("handshakeOffer", from, to, expiresInMs);
        }
    }

    /** Рукостискання відбулося: {@code from} простягнув руку, {@code to} потиснув; {@code scored} — обидва отримали +1. */
    public record Handshake(String type, String from, String to, boolean scored) {
        public Handshake(String from, String to, boolean scored) {
            this("handshake", from, to, scored);
        }
    }

    /**
     * Повідомлення чату: {@code group} — id групи або null (загальний чат); {@code name} — щоб показати автора,
     * навіть якщо він уже вийшов; {@code at} — epoch мс.
     */
    public record ChatMessage(String group, String from, String name, String text, long at) {
    }

    /** Нове повідомлення — тим, хто в цьому чаті (учасникам групи або всім без групи). */
    public record Chat(String type, String group, String from, String name, String text, long at) {
        public Chat(ChatMessage m) {
            this("chat", m.group(), m.from(), m.name(), m.text(), m.at());
        }
    }

    /** Чат гравця (при вході й при зміні чату): {@code group == null} — загальний чат. */
    public record ChatHistory(String type, String group, List<ChatMessage> messages) {
        public ChatHistory(String group, List<ChatMessage> messages) {
            this("chatHistory", group, messages);
        }
    }

    /** Повідомлення не відправлено (наприклад, занадто часто) — лише автору. */
    public record ChatError(String type, String text) {
        public ChatError(String text) {
            this("chatError", text);
        }
    }

    /** Гравець стрибнув. Висоту й анімацію кожен клієнт рахує сам. */
    public record Jump(String type, String id) {
        public Jump(String id) {
            this("jump", id);
        }
    }

    public record State(String type, long tick, List<PlayerPos> players) {
        public State(long tick, List<PlayerPos> players) {
            this("state", tick, players);
        }
    }
}
