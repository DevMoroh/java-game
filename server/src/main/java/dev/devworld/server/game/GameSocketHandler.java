package dev.devworld.server.game;

import dev.devworld.server.community.Boards;
import dev.devworld.server.community.Eggs;
import dev.devworld.server.community.Envelope;
import dev.devworld.server.game.Messages.Joined;
import dev.devworld.server.game.Messages.Left;
import dev.devworld.server.game.Messages.Welcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Протокол (клієнт → сервер, JSON):
 * <pre>
 * {"type":"join",  "name":"Roman", "gender":"MALE", "token":"…", "appearance":{…}}  // token/appearance — з минулого входу
 * {"type":"input", "seq":42, "dx":0, "dz":-1}   // один крок руху, seq зростає
 * {"type":"slap"}                               // лящ найближчому поруч
 * {"type":"jump"}                               // стрибок
 * {"type":"handshake"}                          // простягнути руку / потиснути простягнуту
 * {"type":"groupJoin",  "player":"<id>"}       // приєднатися до чату гравця, у чиєму колі стоїш
 * {"type":"groupLeave"}                         // назад у загальний чат
 * {"type":"chat",       "text":"Привіт!"}       // повідомлення в свій чат (груповий або загальний)
 * {"type":"status",     "text":"🎧 Слухаю музику"}                      // статус ("" — прибрати)
 * {"type":"boardOpen",  "board":"reviews"}     // відкрити скриньку, біля якої стоїш
 * {"type":"boardClose"}                         // закрити її
 * {"type":"boardSubscribe", "board":"reviews", "on":true}  // приєднатися до скриньки / вийти з неї
 * {"type":"boardMute",  "board":"reviews", "muted":true}  // вимкнути / увімкнути для себе звук нових повідомлень
 * {"type":"boardPost",  "text":"…"}             // пост у відкриту скриньку
 * {"type":"boardThread", "post":12}             // коментарі до поста
 * {"type":"boardComment", "post":12, "text":"…"}  // коментар до поста
 * {"type":"eggOpen",    "egg":"egg-3"}          // підібрати яйце поруч і прочитати
 * {"type":"eggKeep"} / {"type":"eggDrop"}       // зберегти в колекцію / викинути
 * {"type":"eggRead",    "article":"…"}          // перечитати статтю зі своєї колекції
 * </pre>
 */
@Component
public class GameSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameSocketHandler.class);
    private static final int MAX_NAME = 20;

    private final GameWorld world;
    private final Chat chat;
    private final Boards boards;
    private final Eggs eggs;
    private final ObjectMapper json;
    /** Лише сесії, що вже виконали join. Ключ — id гравця (= id сесії). */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public GameSocketHandler(GameWorld world, Chat chat, Boards boards, Eggs eggs, ObjectMapper json) {
        this.world = world;
        this.chat = chat;
        this.boards = boards;
        this.eggs = eggs;
        this.json = json;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode msg;
        try {
            msg = json.readTree(message.getPayload());
        } catch (JacksonException e) {
            return;
        }
        String id = session.getId();
        try {
            handle(session, id, msg);
        } catch (RuntimeException e) {
            // Напр. база недоступна: гравця не відключаємо, лише кажемо, що не вийшло.
            log.error("Failed to handle {} from {}", msg.path("type").asString(""), id, e);
            sendTo(id, new Messages.Notice("Something went wrong, try again", false));
        }
    }

    private void handle(WebSocketSession session, String id, JsonNode msg) {
        switch (msg.path("type").asString("")) {
            case "join" -> join(session, msg);
            case "input" -> world.applyInput(id, msg.path("seq").asLong(0),
                    msg.path("dx").asDouble(0), msg.path("dz").asDouble(0));
            case "handshake" -> handshake(id);
            case "status" -> {
                var update = world.setStatus(id, msg.path("text").asString(""));
                if (update != null) {
                    broadcast(update);
                }
            }
            case "chat" -> {
                var player = world.player(id);
                if (player == null) {
                    break;
                }
                var result = chat.post(id, world.profileToken(id), player.name(), world.groupOf(id),
                        msg.path("text").asString(""));
                if (result.message() != null) {
                    var out = new Messages.Chat(result.message());
                    world.chatRecipients(result.message().group()).forEach(member -> sendTo(member, out));
                } else if (result.error() != null) {
                    sendTo(id, new Messages.ChatError(result.error()));
                }
            }
            case "groupJoin" -> groupOutcome(id, world.joinPlayer(id, msg.path("player").asString("")));
            case "groupLeave" -> groupOutcome(id, world.leaveGroup(id));
            case "jump" -> {
                if (world.jump(id)) {
                    broadcast(new Messages.Jump(id));
                }
            }
            case "slap" -> {
                var result = world.slap(id);
                if (result != null) {
                    broadcast(new Messages.Slap(result.slapperId(), result.targetId(), result.allyId()));
                }
            }
            case "boardOpen" -> {
                var p = world.player(id);
                if (p != null) {
                    deliver(boards.open(id, msg.path("board").asString(""), p.x(), p.z()));
                }
            }
            case "boardClose" -> boards.close(id);
            case "boardSubscribe" -> deliver(boards.subscribe(id, msg.path("board").asString(""), msg.path("on").asBoolean(true)));
            case "boardMute" -> deliver(boards.mute(id, msg.path("board").asString(""), msg.path("muted").asBoolean(true)));
            case "boardPost" -> {
                var p = world.player(id);
                if (p != null) {
                    var out = boards.post(id, p.name(), msg.path("text").asString(""));
                    deliver(out);
                    reward(id, Boards.published(out) ? Boards.MESSAGE_POINTS : 0);
                }
            }
            case "boardThread" -> deliver(boards.thread(id, msg.path("post").asLong(0)));
            case "boardComment" -> {
                var p = world.player(id);
                if (p != null) {
                    var out = boards.comment(id, p.name(), msg.path("post").asLong(0), msg.path("text").asString(""));
                    deliver(out);
                    reward(id, Boards.published(out) ? Boards.MESSAGE_POINTS : 0);
                }
            }
            case "eggOpen" -> {
                var p = world.player(id);
                if (p != null) {
                    var out = eggs.open(id, world.profileToken(id), msg.path("egg").asString(""), p.x(), p.z());
                    deliver(out);
                    reward(id, out.stream().mapToInt(e -> e.message() instanceof Eggs.EggArticle a ? a.points() : 0).sum());
                }
            }
            case "eggKeep" -> {
                String token = world.profileToken(id);
                if (token != null) {
                    deliver(eggs.keep(id, token));
                }
            }
            case "eggDrop" -> {
                var p = world.player(id);
                if (p != null) {
                    deliver(eggs.drop(id, p.x(), p.z()));
                }
            }
            case "eggRead" -> {
                String token = world.profileToken(id);
                if (token != null) {
                    deliver(eggs.read(id, token, msg.path("article").asString("")));
                }
            }
            default -> { /* невідомі повідомлення ігноруємо */ }
        }
    }

    private void join(WebSocketSession raw, JsonNode msg) {
        String id = raw.getId();
        if (world.contains(id)) {
            return;
        }
        var session = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 512 * 1024);
        var gender = "FEMALE".equals(msg.path("gender").asString("")) ? Gender.FEMALE : Gender.MALE;
        String token = msg.path("token").asString("");
        var result = world.join(id, token.isEmpty() ? null : token,
                sanitizeName(msg.path("name").asString(""), id), gender, readAppearance(msg.path("appearance")));
        var info = result.player();

        // Нові для гравця яйця кладемо до welcome, щоб він отримав їх разом з ним; іншим — оголошення нижче.
        var newEggs = eggs.connect(id, result.token());
        send(session, new Welcome(id, result.token(), GameWorld.WORLD_SIZE, world.rules(), world.snapshot(),
                Boards.ALL, eggs.snapshot(id)));
        String group = world.groupOf(id);
        send(session, new Messages.ChatHistory(group, chat.history(group)));
        send(session, boards.connect(id, result.token()).message());
        send(session, eggs.collection(id, result.token()).message());
        broadcast(new Joined(info));
        sessions.put(id, session);
        deliver(newEggs);
        log.info("Player joined: {} ({}), online={}", info.name(), id, sessions.size());
    }

    private void handshake(String id) {
        var result = world.handshake(id);
        if (result == null) {
            return;
        }
        if (!result.accepted()) {
            broadcast(new Messages.HandshakeOffer(result.from(), result.to(), GameWorld.HANDSHAKE_OFFER_NANOS / 1_000_000));
            return;
        }
        var hs = result.details();
        broadcast(new Messages.Handshake(hs.from(), hs.to(), hs.scored()));
        hs.updates().forEach(this::broadcast);
    }

    /**
     * Результат дії з груповим чатом: повідомлення тому, хто діяв; усім — нові кольори кіл гравців;
     * кожному, чий чат змінився, — історія його нового чату (групового або загального).
     */
    private void groupOutcome(String id, GameWorld.GroupOutcome outcome) {
        sendTo(id, new Messages.Notice(outcome.message(), outcome.ok()));
        if (!outcome.ok()) {
            return;
        }
        outcome.deletedGroups().forEach(chat::dropGroup);
        for (var update : outcome.updates()) {
            broadcast(update);
            sendTo(update.id(), new Messages.ChatHistory(update.group(), chat.history(update.group())));
        }
    }

    /** Нарахувати очки й показати всім новий рахунок (0 — нічого не робить). */
    private void reward(String id, int points) {
        if (points > 0) {
            var update = world.addScore(id, points);
            if (update != null) {
                broadcast(update);
            }
        }
    }

    /** Розіслати повідомлення адресатам ({@link Envelope#to()} == null — усім). */
    public void deliver(List<Envelope> envelopes) {
        for (var e : envelopes) {
            if (e.to() == null) {
                broadcast(e.message());
            } else {
                sendTo(e.to(), e.message());
            }
        }
    }

    private void sendTo(String id, Object message) {
        var session = sessions.get(id);
        if (session != null) {
            send(session, message);
        }
    }

    /** Зовнішність із кешу клієнта; null, якщо немає або не розбирається (валідує GameWorld). */
    private Appearance readAppearance(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        try {
            return json.treeToValue(node, Appearance.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String id = session.getId();
        sessions.remove(id);
        chat.forget(id);
        boards.disconnect(id);
        var player = world.player(id);
        if (player != null) {
            deliver(eggs.disconnect(id, player.x(), player.z()));
        }
        if (world.leave(id)) {
            broadcast(new Left(id));
            log.info("Player left: {}, online={}", id, sessions.size());
        }
    }

    public void broadcast(Object message) {
        if (sessions.isEmpty()) {
            return;
        }
        var text = new TextMessage(json.writeValueAsString(message));
        sessions.values().forEach(s -> sendRaw(s, text));
    }

    private void send(WebSocketSession session, Object message) {
        sendRaw(session, new TextMessage(json.writeValueAsString(message)));
    }

    private void sendRaw(WebSocketSession session, TextMessage text) {
        try {
            if (session.isOpen()) {
                session.sendMessage(text);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Send failed for {}: {}", session.getId(), e.getMessage());
        }
    }

    private static String sanitizeName(String name, String id) {
        String clean = name.replaceAll("[\\p{Cntrl}<>]", "").strip();
        if (clean.isEmpty()) {
            return "Guest-" + id.substring(0, Math.min(4, id.length()));
        }
        return clean.length() > MAX_NAME ? clean.substring(0, MAX_NAME) : clean;
    }
}
