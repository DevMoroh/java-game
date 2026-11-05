package dev.devworld.server.community;

import dev.devworld.server.community.Articles.Article;
import dev.devworld.server.community.Eggs.EggArticle;
import dev.devworld.server.community.Eggs.EggCollection;
import dev.devworld.server.community.Eggs.EggGone;
import dev.devworld.server.community.Eggs.EggSpawned;
import dev.devworld.server.game.Messages.Notice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class EggsTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long INTERVAL = 300 * SECOND;

    private final AtomicLong clock = new AtomicLong(1_000 * SECOND);
    private final List<Article> library = new ArrayList<>();
    private final FakeStore store = new FakeStore();

    @Test
    void noArticlesNoEggs() {
        var eggs = eggs(2);

        assertThat(eggs.tick()).isEmpty();
        clock.addAndGet(INTERVAL);
        assertThat(eggs.tick()).isEmpty();
        assertThat(eggs.snapshot()).isEmpty();
    }

    @Test
    void initialEggsAppearSoonAfterArticlesAreAdded() {
        var eggs = eggs(2);
        assertThat(eggs.tick()).isEmpty();

        articles(3);
        clock.addAndGet(Eggs.EMPTY_RETRY_NANOS);

        assertThat(eggs.tick()).hasSize(2);
        clock.addAndGet(Eggs.EMPTY_RETRY_NANOS);
        assertThat(eggs.tick()).isEmpty();
    }

    @Test
    void initialEggsThenOneEveryIntervalUpToTheLimit() {
        articles(10);
        var eggs = eggs(2);

        assertThat(eggs.tick()).hasSize(2);
        assertThat(eggs.tick()).isEmpty();
        for (int i = 0; i < 10; i++) {
            clock.addAndGet(INTERVAL);
            eggs.tick();
        }

        assertThat(eggs.snapshot()).hasSize(Eggs.MAX_EGGS);
        assertThat(eggs.snapshot()).extracting(Eggs.EggView::color).allMatch(c -> c.startsWith("#"));
    }

    @Test
    void openingHidesTheEggAndShowsTheArticle() {
        articles(3);
        var eggs = eggs(1);
        eggs.tick();
        var egg = eggs.snapshot().getFirst();

        var far = eggs.open("p", "t", egg.id(), egg.x() + 10, egg.z());
        var near = eggs.open("p", "t", egg.id(), egg.x() + 1, egg.z());

        assertThat(far).singleElement().extracting(Envelope::message).isInstanceOf(Notice.class);
        assertThat(near).extracting(Envelope::message)
                .anySatisfy(m -> assertThat(m).isInstanceOf(EggGone.class))
                .anySatisfy(m -> assertThat(m).isInstanceOfSatisfying(EggArticle.class, a -> assertThat(a.egg()).isEqualTo(egg.id())));
        assertThat(eggs.snapshot()).isEmpty();
        assertThat(eggs.open("q", "tq", egg.id(), egg.x(), egg.z())).singleElement()
                .extracting(Envelope::message).isInstanceOf(Notice.class);
    }

    @Test
    void keepAddsToCollectionAndRemovesTheEgg() {
        articles(3);
        var eggs = eggs(1);
        eggs.tick();
        var egg = eggs.snapshot().getFirst();
        eggs.open("p", "t", egg.id(), egg.x(), egg.z());

        var out = eggs.keep("p", "t");

        var collection = (EggCollection) out.getFirst().message();
        assertThat(collection.items()).hasSize(1);
        assertThat(collection.total()).isEqualTo(3);
        assertThat(eggs.snapshot()).isEmpty();
        assertThat(eggs.read("p", "t", collection.items().getFirst().articleId())).hasSize(1);
        assertThat(eggs.read("p", "t", "not-mine")).isEmpty();
    }

    @Test
    void droppedEggLiesNearbyAndExpiresUnlessPickedUp() {
        articles(3);
        var eggs = eggs(1);
        eggs.tick();
        var egg = eggs.snapshot().getFirst();
        eggs.open("p", "t", egg.id(), egg.x(), egg.z());

        var dropped = eggs.drop("p", 5, 5);

        var view = ((EggSpawned) dropped.getFirst().message()).egg();
        assertThat(view.dropped()).isTrue();
        assertThat(Math.hypot(view.x() - 5, view.z() - 5)).isLessThan(1);
        clock.addAndGet(Eggs.DROPPED_TTL_NANOS - SECOND);
        assertThat(eggs.tick()).isEmpty();
        clock.addAndGet(SECOND);
        assertThat(eggs.tick()).extracting(Envelope::message).singleElement().isInstanceOf(EggGone.class);
        assertThat(eggs.snapshot()).isEmpty();
    }

    @Test
    void pickingUpADroppedEggCancelsItsExpiry() {
        articles(3);
        var eggs = eggs(1);
        eggs.tick();
        var egg = eggs.snapshot().getFirst();
        eggs.open("p", "t", egg.id(), egg.x(), egg.z());
        var view = ((EggSpawned) eggs.drop("p", 5, 5).getFirst().message()).egg();

        eggs.open("q", "tq", view.id(), view.x(), view.z());
        clock.addAndGet(Eggs.DROPPED_TTL_NANOS * 2);
        eggs.tick();
        var reDropped = ((EggSpawned) eggs.drop("q", 0, 0).getFirst().message()).egg();

        assertThat(reDropped.id()).isEqualTo(egg.id());
    }

    @Test
    void openingGivesAPointOncePerPlayerPerEgg() {
        articles(3);
        var eggs = eggs(1);
        eggs.tick();
        var egg = eggs.snapshot().getFirst();

        assertThat(points(eggs.open("p", "t", egg.id(), egg.x(), egg.z()))).isEqualTo(Eggs.OPEN_POINTS);
        var dropped = ((EggSpawned) eggs.drop("p", egg.x(), egg.z()).getFirst().message()).egg();
        // Викинув і підняв знову — очок більше немає; інший гравець — отримує.
        assertThat(points(eggs.open("p", "t", dropped.id(), dropped.x(), dropped.z()))).isZero();
        eggs.drop("p", egg.x(), egg.z());
        assertThat(points(eggs.open("q", "tq", dropped.id(), dropped.x(), dropped.z()))).isEqualTo(Eggs.OPEN_POINTS);
    }

    private static int points(List<Envelope> out) {
        return out.stream().mapToInt(e -> e.message() instanceof EggArticle a ? a.points() : 0).sum();
    }

    @Test
    void holdingOnlyOneEggAtATime() {
        articles(5);
        var eggs = eggs(2);
        eggs.tick();
        var first = eggs.snapshot().get(0);
        var second = eggs.snapshot().get(1);
        eggs.open("p", "t", first.id(), first.x(), first.z());

        assertThat(eggs.open("p", "t", second.id(), second.x(), second.z())).singleElement()
                .extracting(Envelope::message).isInstanceOf(Notice.class);
    }

    @Test
    void articleColorFallsBackToTopic() {
        assertThat(Articles.colorFor("Java", null)).isEqualTo("#f97316");
        assertThat(Articles.colorFor("Java", "#123456")).isEqualTo("#123456");
        assertThat(Articles.colorFor("Something new", "")).isIn(Articles.FALLBACK_COLORS);
    }

    private Eggs eggs(int initial) {
        Articles articles = new Articles() {
            @Override
            public List<Article> all() {
                return List.copyOf(library);
            }

            @Override
            public Optional<Article> find(String id) {
                return library.stream().filter(a -> a.id().equals(id)).findFirst();
            }
        };
        return new Eggs(articles, store, clock::get, new Random(7), INTERVAL, initial);
    }

    private void articles(int n) {
        for (int i = 0; i < n; i++) {
            library.add(new Article("a" + i, "Java", "#f97316", "Title " + i, "Body " + i));
        }
    }

    private static final class FakeStore implements EggStore {
        final List<String[]> rows = new ArrayList<>();

        @Override
        public List<Collected> collection(String token) {
            return rows.stream().filter(r -> r[0].equals(token)).map(r -> new Collected(r[1], 0)).toList();
        }

        @Override
        public boolean add(String token, String articleId) {
            if (rows.stream().anyMatch(r -> r[0].equals(token) && r[1].equals(articleId))) {
                return false;
            }
            return rows.add(new String[] {token, articleId});
        }
    }
}
