package dev.devworld.server.store;

import dev.devworld.server.game.Appearance;
import dev.devworld.server.game.Gender;
import dev.devworld.server.game.Messages.ChatMessage;

import java.util.List;
import java.util.Optional;

/**
 * Довготривале зберігання: профілі гостей, групові чати, повідомлення (групові й загальні).
 * <p>
 * Читання — синхронні (старт сервера, вхід гравця), записи — асинхронні й упорядковані: гра ніколи
 * не чекає на базу під своїм локом. {@link #NONE} — заглушка без бази для юніт-тестів ігрової логіки.
 */
public interface GameStore {

    /** Профіль у тому вигляді, як його треба зберегти / як він прийшов з бази; {@code color} — власний колір кола. */
    record StoredProfile(String token, String name, Gender gender, Appearance appearance, int score,
                         Double lastX, Double lastZ, double lastRotation, String status, String color) {
    }

    /** Груповий чат: колір, господар (до кого приєдналися) і учасники в порядку вступу. */
    record StoredGroup(String id, String color, String hostToken, List<String> memberTokens) {
    }

    Optional<StoredProfile> loadProfile(String token);

    /** Створити або оновити профіль (очки, позицію, статус, колір, час останнього візиту). */
    void saveProfile(StoredProfile profile);

    List<StoredGroup> loadGroups();

    /** Нова група; господар одразу стає її учасником. */
    void createGroup(String id, String color, String hostToken);

    /** Перевести гравця в групу {@code group} (null — у загальний чат). Гравець буває лише в одній групі. */
    void setGroupMember(String token, String group);

    /** Новий господар групи (коли попередній пішов) і новий колір групи. */
    void setGroupHost(String group, String hostToken, String color);

    /** Видалити групу разом з усім її чатом. */
    void deleteGroup(String group);

    /** Останні {@code limit} повідомлень чату ({@code group == null} — загального), від старих до нових. */
    List<ChatMessage> loadRecentChat(String group, int limit);

    /** {@code authorToken} — для зв'язку з профілем; клієнтам не передається. */
    void saveChat(ChatMessage message, String authorToken);

    GameStore NONE = new GameStore() {
        @Override
        public Optional<StoredProfile> loadProfile(String token) {
            return Optional.empty();
        }

        @Override
        public void saveProfile(StoredProfile profile) {
        }

        @Override
        public List<StoredGroup> loadGroups() {
            return List.of();
        }

        @Override
        public void createGroup(String id, String color, String hostToken) {
        }

        @Override
        public void setGroupMember(String token, String group) {
        }

        @Override
        public void setGroupHost(String group, String hostToken, String color) {
        }

        @Override
        public void deleteGroup(String group) {
        }

        @Override
        public List<ChatMessage> loadRecentChat(String group, int limit) {
            return List.of();
        }

        @Override
        public void saveChat(ChatMessage message, String authorToken) {
        }
    };
}
