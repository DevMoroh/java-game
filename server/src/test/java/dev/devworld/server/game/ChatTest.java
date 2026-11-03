package dev.devworld.server.game;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ChatTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Chat chat = new Chat(nanos::get, Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));

    @Test
    void postedMessageGoesToHistory() {
        var result = chat.post("a", null, "Roman", "Java", "  Привіт усім!  ");

        assertThat(result.error()).isNull();
        assertThat(result.message().text()).isEqualTo("Привіт усім!");
        assertThat(result.message().name()).isEqualTo("Roman");
        assertThat(result.message().at()).isEqualTo(1_000);
        assertThat(chat.history("Java")).containsExactly(result.message());
    }

    @Test
    void historiesAreSeparatePerGroup() {
        chat.post("a", null, "Roman", "Java", "про джаву");
        chat.post("b", null, "Ira", "Go", "про го");

        assertThat(chat.history("java")).extracting(m -> m.text()).containsExactly("про джаву");
        assertThat(chat.history("Go")).extracting(m -> m.text()).containsExactly("про го");
        assertThat(chat.history(null)).isEmpty(); // загальний чат окремо від групових
    }

    @Test
    void withoutGroupItIsGeneralChat() {
        var result = chat.post("a", null, "Roman", null, "привіт усім");

        assertThat(result.message().group()).isNull();
        assertThat(chat.history(null)).extracting(m -> m.text()).containsExactly("привіт усім");
        assertThat(chat.history("Java")).isEmpty();
    }

    @Test
    void droppedGroupLosesHistory() {
        chat.post("a", null, "Roman", "Java", "зникне");

        chat.dropGroup("Java");

        assertThat(chat.history("Java")).isEmpty();
    }

    @Test
    void emptyMessageIsIgnored() {
        var result = chat.post("a", null, "Roman", "Java", " \n\t ");

        assertThat(result.message()).isNull();
        assertThat(result.error()).isNull();
        assertThat(chat.history("Java")).isEmpty();
    }

    @Test
    void longMessageIsTruncatedAndNewlinesFlattened() {
        var result = chat.post("a", null, "Roman", "Java", "рядок1\nрядок2" + "x".repeat(500));

        assertThat(result.message().text()).startsWith("рядок1 рядок2").hasSize(Chat.MAX_LENGTH);
    }

    @Test
    void rateLimitAllowsBurstThenOnePerSecond() {
        for (int i = 0; i < Chat.BURST; i++) {
            assertThat(chat.post("a", null, "Roman", "Java", "msg " + i).message()).isNotNull();
        }
        var tooFast = chat.post("a", null, "Roman", "Java", "ще одне");
        assertThat(tooFast.message()).isNull();
        assertThat(tooFast.error()).isNotBlank();

        assertThat(chat.post("b", null, "Ira", "Java", "інший гравець — свій ліміт").message()).isNotNull();

        nanos.addAndGet(Chat.REFILL_NANOS);
        assertThat(chat.post("a", null, "Roman", "Java", "через секунду").message()).isNotNull();
    }

    @Test
    void historyKeepsOnlyLatest() {
        for (int i = 0; i < Chat.HISTORY + 10; i++) {
            nanos.addAndGet(Chat.REFILL_NANOS);
            chat.post("a", null, "Roman", "Java", "msg " + i);
        }

        var history = chat.history("Java");
        assertThat(history).hasSize(Chat.HISTORY);
        assertThat(history.getFirst().text()).isEqualTo("msg 10");
        assertThat(history.getLast().text()).isEqualTo("msg " + (Chat.HISTORY + 9));
    }
}
