package dev.devworld.server.store;

import dev.devworld.server.TestcontainersConfiguration;
import dev.devworld.server.game.Appearance;
import dev.devworld.server.game.Gender;
import dev.devworld.server.game.Messages.ChatMessage;
import dev.devworld.server.store.GameStore.StoredGroup;
import dev.devworld.server.store.GameStore.StoredProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** JdbcGameStore на справжньому PostgreSQL (Testcontainers) зі схемою з Flyway-міграцій. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PersistenceTest {

    private static final Appearance LOOK = new Appearance("#a86b45", "#8c2f1b", "#f97316", "#111827", 4, true);

    @Autowired
    JdbcGameStore store;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("truncate chat_message, team_member, team, guest_profile cascade").update();
    }

    @Test
    void profileRoundTripAndUpdate() {
        store.saveProfile(new StoredProfile("t1", "Roman", Gender.MALE, LOOK, 0, 1.5, -2.0, 0.3, "", "#facc15"));
        store.saveProfile(new StoredProfile("t1", "Roman", Gender.MALE, LOOK, 7, 3.0, 4.0, 1.0, "🎧 Слухаю музику", "#a855f7"));
        store.awaitWrites();

        var loaded = store.loadProfile("t1").orElseThrow();

        assertThat(loaded.name()).isEqualTo("Roman");
        assertThat(loaded.appearance()).isEqualTo(LOOK);
        assertThat(loaded.score()).isEqualTo(7);
        assertThat(loaded.lastX()).isEqualTo(3.0);
        assertThat(loaded.lastZ()).isEqualTo(4.0);
        assertThat(loaded.status()).isEqualTo("🎧 Слухаю музику");
        assertThat(loaded.color()).isEqualTo("#a855f7");
        assertThat(store.loadProfile("unknown")).isEmpty();
    }

    @Test
    void groupWithHostAndMembers() {
        profile("a");
        profile("b");
        store.createGroup("g-1", "#facc15", "a");
        store.setGroupMember("b", "g-1");
        store.awaitWrites();

        assertThat(store.loadGroups()).containsExactly(new StoredGroup("g-1", "#facc15", "a", List.of("a", "b")));
    }

    @Test
    void memberIsInOnlyOneGroupOrGeneral() {
        profile("a");
        profile("b");
        profile("c");
        store.createGroup("g-1", "#facc15", "a");
        store.createGroup("g-2", "#a855f7", "b");
        store.setGroupMember("c", "g-1");
        store.setGroupMember("c", "g-2");
        store.awaitWrites();
        assertThat(store.loadGroups()).extracting(StoredGroup::memberTokens)
                .containsExactly(List.of("a"), List.of("b", "c"));

        store.setGroupMember("c", null); // у загальний чат
        store.awaitWrites();
        assertThat(jdbc.sql("select count(*) from team_member where token = 'c'").query(Integer.class).single()).isZero();
    }

    @Test
    void hostChangeUpdatesColor() {
        profile("a");
        profile("b");
        store.createGroup("g-1", "#facc15", "a");
        store.setGroupMember("b", "g-1");
        store.setGroupHost("g-1", "b", "#3b82f6");
        store.awaitWrites();

        var group = store.loadGroups().getFirst();
        assertThat(group.hostToken()).isEqualTo("b");
        assertThat(group.color()).isEqualTo("#3b82f6");
    }

    @Test
    void groupAndGeneralChatsAreSeparate() {
        profile("a");
        store.createGroup("g-1", "#facc15", "a");
        for (int i = 0; i < 5; i++) {
            store.saveChat(new ChatMessage("g-1", "session", "Roman", "msg " + i, 1_000L + i), "a");
        }
        store.saveChat(new ChatMessage(null, "session", "Roman", "усім привіт", 2_000L), "a");
        store.awaitWrites();

        var recent = store.loadRecentChat("g-1", 3);

        assertThat(recent).extracting(ChatMessage::text).containsExactly("msg 2", "msg 3", "msg 4");
        assertThat(recent.getFirst().group()).isEqualTo("g-1");
        assertThat(recent.getFirst().at()).isEqualTo(1_002L);
        var general = store.loadRecentChat(null, 10);
        assertThat(general).extracting(ChatMessage::text).containsExactly("усім привіт");
        assertThat(general.getFirst().group()).isNull();
    }

    @Test
    void deletingGroupDeletesItsChatButNotGeneral() {
        profile("a");
        store.createGroup("g-1", "#facc15", "a");
        store.saveChat(new ChatMessage("g-1", "s", "Roman", "зникне разом з групою", 1_000L), "a");
        store.saveChat(new ChatMessage(null, "s", "Roman", "лишиться", 1_001L), "a");
        store.deleteGroup("g-1");
        store.awaitWrites();

        assertThat(store.loadGroups()).isEmpty();
        assertThat(store.loadRecentChat(null, 10)).extracting(ChatMessage::text).containsExactly("лишиться");
        assertThat(jdbc.sql("select count(*) from team_member").query(Integer.class).single()).isZero();
    }

    @Test
    void deletingProfileKeepsChatReadable() {
        profile("a");
        store.saveChat(new ChatMessage(null, "s", "Roman", "привіт", 1_000L), "a");
        store.awaitWrites();

        jdbc.sql("delete from guest_profile where token = 'a'").update();

        assertThat(store.loadRecentChat(null, 10)).extracting(ChatMessage::name).containsExactly("Roman");
    }

    private void profile(String token) {
        store.saveProfile(new StoredProfile(token, token, Gender.MALE, LOOK, 0, null, null, 0, "", null));
    }
}
