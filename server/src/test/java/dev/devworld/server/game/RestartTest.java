package dev.devworld.server.game;

import dev.devworld.server.TestcontainersConfiguration;
import dev.devworld.server.store.JdbcGameStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * «Перезапуск сервера»: перший світ грає й зберігає в БД, другий (новий об'єкт, порожня пам'ять)
 * піднімається з тієї самої бази — гравці повертаються з тими самими кольорами, групою й очками, чати на місці.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RestartTest {

    @Autowired
    JdbcGameStore store;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("truncate chat_message, team_member, team, guest_profile cascade").update();
    }

    @Test
    void colorsGroupStatusAndChatsSurviveRestart() {
        var before = new GameWorld(20, store);
        before.afterSingletonsInstantiated();
        var roman = before.join("s1", null, "Roman", Gender.MALE, null);
        var ira = before.join("s2", null, "Ira", Gender.FEMALE, null);
        before.teleport("s2", roman.player().x() + 1, roman.player().z());
        before.joinPlayer("s2", "s1");
        before.setStatus("s1", "🎧 Слухаю музику");
        String group = before.groupOf("s1");
        String color = before.player("s1").color();
        var chat = new Chat(store);
        chat.post("s1", roman.token(), "Roman", group, "Привіт з групи");
        chat.post("s1", roman.token(), "Roman", null, "Привіт усім");
        var romanPos = before.player("s1");
        before.leave("s1");
        before.leave("s2");
        store.awaitWrites();

        var after = new GameWorld(20, store);
        after.afterSingletonsInstantiated();
        var chatAfter = new Chat(store);
        var back = after.join("s3", roman.token(), "інше ім'я", Gender.FEMALE, null);
        var iraBack = after.join("s4", ira.token(), "Ira", Gender.FEMALE, null);

        assertThat(back.token()).isEqualTo(roman.token());
        assertThat(back.player().name()).isEqualTo("Roman");
        assertThat(back.player().appearance()).isEqualTo(roman.player().appearance());
        assertThat(back.player().status()).isEqualTo("🎧 Слухаю музику");
        assertThat(back.player().x()).isEqualTo(romanPos.x());
        assertThat(back.player().group()).isEqualTo(group);
        assertThat(back.player().color()).isEqualTo(color);
        assertThat(iraBack.player().group()).isEqualTo(group);
        assertThat(iraBack.player().color()).isEqualTo(color); // колір групи — колір господаря
        assertThat(chatAfter.history(group)).extracting(m -> m.text()).containsExactly("Привіт з групи");
        assertThat(chatAfter.history(null)).extracting(m -> m.text()).containsExactly("Привіт усім");
    }

    @Test
    void dissolvedGroupIsGoneAfterRestart() {
        var before = new GameWorld(20, store);
        before.join("s1", null, "Roman", Gender.MALE, null);
        before.join("s2", null, "Ira", Gender.FEMALE, null);
        before.teleport("s2", before.player("s1").x() + 1, before.player("s1").z());
        before.joinPlayer("s2", "s1");
        String group = before.groupOf("s1");
        new Chat(store).post("s1", before.profileToken("s1"), "Roman", group, "буде видалено");
        before.leaveGroup("s2"); // лишився сам — група розпалася
        store.awaitWrites();

        var after = new GameWorld(20, store);
        after.afterSingletonsInstantiated();

        assertThat(store.loadGroups()).isEmpty();
        assertThat(new Chat(store).history(group)).isEmpty();
    }

    @Test
    void scoreIsFlushedWhileOnline() {
        var world = new GameWorld(20, store);
        var a = world.join("s1", null, "A", Gender.MALE, null);
        var b = world.join("s2", null, "B", Gender.MALE, null);
        world.teleport("s2", a.player().x() + 1, a.player().z());
        world.handshake("s1");
        world.handshake("s2");

        world.flushProfiles(); // як щоп'ять секунд у GameLoop — без виходу з гри
        store.awaitWrites();

        assertThat(store.loadProfile(a.token()).orElseThrow().score()).isEqualTo(1);
        assertThat(store.loadProfile(b.token()).orElseThrow().score()).isEqualTo(1);
    }
}
