package dev.devworld.server.community;

import dev.devworld.server.TestcontainersConfiguration;
import dev.devworld.server.community.BoardMessages.BoardCommentAdded;
import dev.devworld.server.community.BoardMessages.BoardOpened;
import dev.devworld.server.community.BoardMessages.BoardPostAdded;
import dev.devworld.server.community.BoardMessages.BoardStatus;
import dev.devworld.server.community.BoardMessages.BoardThread;
import dev.devworld.server.game.Messages.Notice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Скриньки на справжньому PostgreSQL: пости, коментарі, анонімність і лічильники непрочитаного. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BoardsTest {

    private static final double[] INTERVIEWS = {15.2, -5.1};
    private static final double[] REVIEWS = {4.1, -11.3};

    @Autowired
    JdbcBoardStore store;
    @Autowired
    JdbcClient jdbc;

    private final AtomicLong clock = new AtomicLong();
    private Boards boards;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate board_entry, board_member").update();
        boards = new Boards(store, clock::get);
    }

    @Test
    void openRequiresStandingNearTheBox() {
        boards.connect("a", "ta");

        var far = boards.open("a", "interviews", 0, 0);
        var near = boards.open("a", "interviews", INTERVIEWS[0] + 1, INTERVIEWS[1]);

        assertThat(far).singleElement().extracting(Envelope::message).isInstanceOf(Notice.class);
        assertThat(near).extracting(Envelope::message).first().isInstanceOf(BoardOpened.class);
    }

    @Test
    void postIsDeliveredToViewersAndCountedAsUnreadForMembers() {
        boards.connect("author", "t-author");
        boards.connect("viewer", "t-viewer");
        boards.connect("member", "t-member");
        boards.connect("stranger", "t-stranger");
        boards.subscribe("member", "interviews", true);
        open("author", "interviews");
        open("viewer", "interviews");

        var out = boards.post("author", "Ann", "Asked about virtual threads");

        assertThat(messagesTo(out, "viewer")).anySatisfy(m -> {
            assertThat(m).isInstanceOf(BoardPostAdded.class);
            var post = ((BoardPostAdded) m).post();
            assertThat(post.author()).isEqualTo("Ann");
            assertThat(post.mine()).isFalse();
        });
        assertThat(messagesTo(out, "author")).anySatisfy(m ->
                assertThat(((BoardPostAdded) m).post().mine()).isTrue());
        assertThat(lastStatus(out, "member").unread()).containsEntry("interviews", 1);
        assertThat(lastStatus(out, "author").joined()).contains("interviews");
        assertThat(messagesTo(out, "stranger")).isEmpty();
    }

    @Test
    void openingTheBoxMarksEverythingReadAndRemembersWhatWasNew() {
        boards.connect("author", "t-author");
        boards.connect("member", "t-member");
        boards.subscribe("member", "interviews", true);
        open("author", "interviews");
        boards.post("author", "Ann", "first");
        boards.post("author", "Ann", "second");

        var opened = open("member", "interviews");

        var box = (BoardOpened) opened.getFirst().message();
        assertThat(box.posts()).hasSize(2);
        assertThat(box.posts()).allSatisfy(p -> assertThat(p.lastActivity()).isGreaterThan(box.lastRead()));
        assertThat(lastStatus(opened, "member").unread()).containsEntry("interviews", 0);

        // Після перезаходу непрочитаного немає — позначка зберігається в БД.
        boards.disconnect("member");
        var status = (BoardStatus) boards.connect("member", "t-member").message();
        assertThat(status.unread()).containsEntry("interviews", 0);
    }

    @Test
    void unreadSurvivesReconnect() {
        boards.connect("author", "t-author");
        boards.connect("member", "t-member");
        boards.subscribe("member", "offers", true);
        boards.disconnect("member");
        open("author", "offers");
        boards.post("author", "Ann", "Got an offer!");

        var status = (BoardStatus) boards.connect("member", "t-member").message();

        assertThat(status.joined()).containsExactly("offers");
        assertThat(status.unread()).containsEntry("offers", 1);
    }

    @Test
    void leavingTheBoxStopsTheCounter() {
        boards.connect("author", "t-author");
        boards.connect("member", "t-member");
        boards.subscribe("member", "interviews", true);
        boards.subscribe("member", "interviews", false);
        open("author", "interviews");

        var out = boards.post("author", "Ann", "hello");

        assertThat(messagesTo(out, "member")).isEmpty();
    }

    @Test
    void commentsBelongToAPostAndAreCounted() {
        boards.connect("a", "ta");
        boards.connect("b", "tb");
        open("a", "interviews");
        open("b", "interviews");
        var post = ((BoardPostAdded) messagesTo(boards.post("a", "Ann", "What did they ask?"), "a").getFirst()).post();

        var out = boards.comment("b", "Bob", post.id(), "System design");
        var thread = boards.thread("a", post.id());

        assertThat(messagesTo(out, "a")).anySatisfy(m -> {
            var c = (BoardCommentAdded) m;
            assertThat(c.comments()).isEqualTo(1);
            assertThat(c.comment().author()).isEqualTo("Bob");
        });
        assertThat(((BoardThread) thread.getFirst().message()).comments())
                .singleElement().satisfies(c -> assertThat(c.text()).isEqualTo("System design"));
        var reopened = (BoardOpened) open("a", "interviews").getFirst().message();
        assertThat(reopened.posts().getFirst().comments()).isEqualTo(1);
    }

    @Test
    void reviewsAreAnonymous() {
        boards.connect("a", "ta");
        boards.connect("b", "tb");
        open("a", "reviews");
        open("b", "reviews");

        var out = boards.post("a", "Ann", "Never paid overtime");

        assertThat(messagesTo(out, "b")).anySatisfy(m -> assertThat(((BoardPostAdded) m).post().author()).isNull());
        assertThat(messagesTo(out, "a")).anySatisfy(m -> assertThat(((BoardPostAdded) m).post().mine()).isTrue());
        assertThat(jdbc.sql("select author_name from board_entry").query(String.class).optional()).isEmpty();
    }

    @Test
    void cannotPostWithoutAnOpenBoxOrTooOften() {
        boards.connect("a", "ta");
        assertThat(boards.post("a", "Ann", "hello")).isEmpty();

        open("a", "interviews");
        for (int i = 0; i < 5; i++) {
            assertThat(boards.post("a", "Ann", "post " + i)).isNotEmpty();
        }
        assertThat(boards.post("a", "Ann", "spam")).singleElement()
                .extracting(Envelope::message).isInstanceOf(Notice.class);
    }

    @Test
    void publishedDetectsNewPostsAndComments() {
        boards.connect("a", "ta");
        open("a", "interviews");

        var post = boards.post("a", "Ann", "hello");
        var postId = ((BoardPostAdded) messagesTo(post, "a").getFirst()).post().id();

        assertThat(Boards.published(post)).isTrue();
        assertThat(Boards.published(boards.comment("a", "Ann", postId, "reply"))).isTrue();
        assertThat(Boards.published(boards.post("a", "Ann", "   "))).isFalse();
        assertThat(Boards.published(boards.thread("a", postId))).isFalse();
    }

    @Test
    void muteIsPerPlayerAndSurvivesReconnect() {
        boards.connect("a", "ta");
        boards.connect("b", "tb");
        boards.subscribe("a", "offers", true);
        boards.subscribe("b", "offers", true);

        var status = (BoardStatus) boards.mute("a", "offers", true).getFirst().message();
        assertThat(status.muted()).containsExactly("offers");
        assertThat(boards.mute("a", "vacancies", true)).isEmpty(); // не приєднаний — нічого

        boards.disconnect("a");
        assertThat(((BoardStatus) boards.connect("a", "ta").message()).muted()).containsExactly("offers");
        assertThat(((BoardStatus) boards.connect("b2", "tb").message()).muted()).isEmpty();

        // Вийшов зі скриньки й приєднався знову — звук знову увімкнений.
        boards.subscribe("a", "offers", false);
        var rejoined = (BoardStatus) boards.subscribe("a", "offers", true).getFirst().message();
        assertThat(rejoined.muted()).isEmpty();
    }

    @Test
    void cleanKeepsLineBreaksButNotControlCharacters() {
        assertThat(Boards.clean("  line one\r\n\n\n\nline\u0007 two  ", 100)).isEqualTo("line one\n\nline two");
        assertThat(Boards.clean("x".repeat(20), 10)).hasSize(10);
    }

    private List<Envelope> open(String player, String board) {
        double[] at = board.equals("reviews") ? REVIEWS : board.equals("offers") ? new double[] {10.1, 6.5} : INTERVIEWS;
        return boards.open(player, board, at[0], at[1]);
    }

    private static List<Object> messagesTo(List<Envelope> out, String player) {
        return out.stream().filter(e -> player.equals(e.to()))
                .map(Envelope::message)
                .filter(m -> !(m instanceof BoardStatus))
                .toList();
    }

    private static BoardStatus lastStatus(List<Envelope> out, String player) {
        return out.stream().filter(e -> player.equals(e.to()) && e.message() instanceof BoardStatus)
                .map(e -> (BoardStatus) e.message())
                .reduce((a, b) -> b)
                .orElseThrow();
    }
}
