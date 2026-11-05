package dev.devworld.server.game;

import dev.devworld.server.game.Messages.PlayerInfo;
import dev.devworld.server.game.Messages.PlayerPos;
import dev.devworld.server.game.Messages.PlayerUpdate;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.assertj.core.api.Assertions.tuple;

class GameWorldTest {

    private static final int TICK_RATE = 20;
    private static final long TICK_NANOS = 1_000_000_000L / TICK_RATE;
    private static final double STEP = GameWorld.SPEED / TICK_RATE;
    private static final double MIN_DIST = GameWorld.PLAYER_RADIUS * 2;

    private final AtomicLong clock = new AtomicLong();
    private final GameWorld world = new GameWorld(TICK_RATE, clock::get);
    private long seq;

    @Test
    void inputMovesOneStep() {
        world.join("a", "A", Gender.MALE, 0, 0);

        input("a", 1, 0);

        assertThat(get("a").x()).isCloseTo(STEP, offset(1e-9));
        assertThat(pos("a").lastSeq()).isEqualTo(seq);
    }

    @Test
    void walkingIntoPlayerStopsAtContact() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 3, 0);

        walk("a", 1, 0, 40);

        var a = get("a");
        assertThat(distance(a, get("b"))).isGreaterThanOrEqualTo(MIN_DIST - 1e-6);
        assertThat(a.x()).isCloseTo(3 - MIN_DIST, offset(0.01));
        assertThat(get("b").x()).isEqualTo(3); // нікого не штовхаємо
    }

    @Test
    void glancingHitSlidesAround() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 3, 0.3);

        for (int i = 0; i < 40; i++) {
            walk("a", 1, 0, 1);
            assertThat(distance(get("a"), get("b"))).isGreaterThanOrEqualTo(MIN_DIST - 1e-6);
        }

        assertThat(get("a").x()).isGreaterThan(4); // обійшов перешкоду
    }

    @Test
    void blockedPlayerIsNotMoving() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, MIN_DIST, 0);
        clock.addAndGet(1_000_000_000L);

        walk("a", 1, 0, 5);

        assertThat(pos("a").moving()).isFalse();
    }

    @Test
    void movingFlagExpiresAfterStop() {
        world.join("a", "A", Gender.MALE, 0, 0);

        walk("a", 1, 0, 3);
        assertThat(pos("a").moving()).isTrue();

        clock.addAndGet(200_000_000L);
        assertThat(pos("a").moving()).isFalse();
    }

    @Test
    void inputsFasterThanTickRateAreThrottled() {
        world.join("a", "A", Gender.MALE, 0, 0);

        for (int i = 0; i < 100; i++) {
            input("a", 1, 0); // без просування годинника — спідхак
        }

        assertThat(get("a").x()).isCloseTo(GameWorld.INPUT_BURST * STEP, offset(1e-9));
        assertThat(pos("a").lastSeq()).isEqualTo(100); // але всі підтверджені
    }

    @Test
    void staleOrDuplicateSeqIsIgnored() {
        world.join("a", "A", Gender.MALE, 0, 0);

        world.applyInput("a", 5, 1, 0);
        world.applyInput("a", 5, 1, 0);
        world.applyInput("a", 3, 1, 0);

        assertThat(get("a").x()).isCloseTo(STEP, offset(1e-9));
        assertThat(pos("a").lastSeq()).isEqualTo(5);
    }

    @Test
    void oversizedInputIsNormalized() {
        world.join("a", "A", Gender.MALE, 0, 0);

        input("a", 50, 0);

        assertThat(get("a").x()).isCloseTo(STEP, offset(1e-9));
    }

    @Test
    void spawnsDoNotOverlap() {
        for (int i = 0; i < 50; i++) {
            world.join("p" + i, "P", Gender.MALE);
        }
        var all = world.snapshot();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                assertThat(distance(all.get(i), all.get(j))).isGreaterThanOrEqualTo(MIN_DIST - 1e-6);
            }
        }
    }

    @Test
    void slapHitsNearestPlayerInRange() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("near", "N", Gender.MALE, 1.2, 0);
        world.join("far", "F", Gender.MALE, -1.5, 0);

        var result = world.slap("a");

        assertThat(result).isEqualTo(new GameWorld.SlapResult("a", "near", null));
        assertThat(get("a").rotation()).isCloseTo(Math.PI / 2, offset(1e-9)); // розвернувся до цілі
    }

    @Test
    void slapMissesWhenNobodyInRange() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, GameWorld.SLAP_RANGE + 0.1, 0);

        assertThat(world.slap("a")).isEqualTo(new GameWorld.SlapResult("a", null, null));
    }

    @Test
    void slapHasCooldown() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);

        assertThat(world.slap("a")).isNotNull();
        assertThat(world.slap("a")).isNull();
        clock.addAndGet(GameWorld.SLAP_COOLDOWN_NANOS);
        assertThat(world.slap("a")).isNotNull();
    }

    @Test
    void slapKnocksTargetBackAtImpact() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);

        world.slap("a");
        assertThat(world.resolveSlaps()).isEmpty(); // ще замах
        assertThat(get("b").x()).isEqualTo(1);

        clock.addAndGet(GameWorld.SLAP_IMPACT_DELAY_NANOS);
        assertThat(world.resolveSlaps()).extracting(GameWorld.SlapImpact::slapperId, GameWorld.SlapImpact::targetId, GameWorld.SlapImpact::dodged).containsExactly(tuple("a", "b", false));
        assertThat(get("b").x()).isCloseTo(1 + GameWorld.SLAP_KNOCKBACK, offset(1e-9));
        assertThat(world.resolveSlaps()).isEmpty(); // кожен лящ — один раз
    }

    @Test
    void knockbackDoesNotPushIntoOthers() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);
        world.join("wall", "W", Gender.MALE, 1 + MIN_DIST, 0);

        world.slap("a");
        clock.addAndGet(GameWorld.SLAP_IMPACT_DELAY_NANOS);
        world.resolveSlaps();

        assertThat(distance(get("b"), get("wall"))).isGreaterThanOrEqualTo(MIN_DIST - 1e-6);
    }

    @Test
    void jumpingTargetDodges() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);
        world.jump("b");

        world.slap("a");
        clock.addAndGet(GameWorld.SLAP_IMPACT_DELAY_NANOS);

        assertThat(world.resolveSlaps()).extracting(GameWorld.SlapImpact::slapperId, GameWorld.SlapImpact::targetId, GameWorld.SlapImpact::dodged).containsExactly(tuple("a", "b", true));
        assertThat(get("b").x()).isEqualTo(1); // не відкинуло
    }

    @Test
    void jumpDuringWindUpDodges() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);

        world.slap("a");
        clock.addAndGet(GameWorld.SLAP_IMPACT_DELAY_NANOS / 2);
        world.jump("b"); // побачив замах і встиг стрибнути
        clock.addAndGet(GameWorld.SLAP_IMPACT_DELAY_NANOS / 2);

        assertThat(world.resolveSlaps()).extracting(GameWorld.SlapImpact::slapperId, GameWorld.SlapImpact::targetId, GameWorld.SlapImpact::dodged).containsExactly(tuple("a", "b", true));
    }

    @Test
    void landedTargetIsHitAgain() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);
        world.jump("b");
        clock.addAndGet(GameWorld.JUMP_NANOS);

        world.slap("a");
        clock.addAndGet(GameWorld.SLAP_IMPACT_DELAY_NANOS);

        assertThat(world.resolveSlaps()).extracting(GameWorld.SlapImpact::slapperId, GameWorld.SlapImpact::targetId, GameWorld.SlapImpact::dodged).containsExactly(tuple("a", "b", false));
    }

    @Test
    void slapOnPlayerWhoLeftIsDropped() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);

        world.slap("a");
        world.leave("b");
        clock.addAndGet(GameWorld.SLAP_IMPACT_DELAY_NANOS);

        assertThat(world.resolveSlaps()).isEmpty();
    }

    @Test
    void cannotJumpWhileAirborne() {
        world.join("a", "A", Gender.MALE, 0, 0);

        assertThat(world.jump("a")).isTrue();
        assertThat(world.jump("a")).isFalse();
        clock.addAndGet(GameWorld.JUMP_NANOS - GameWorld.JUMP_TOLERANCE_NANOS);
        assertThat(world.jump("a")).isTrue(); // приземлився (з урахуванням джитера)
    }

    @Test
    void handshakeNeedsBothSides() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);

        assertHandshake(world.handshake("a"), "a", "b", false);
        assertHandshake(world.handshake("b"), "a", "b", true);
        // Обидва повернулися одне до одного.
        assertThat(get("a").rotation()).isCloseTo(Math.PI / 2, offset(1e-9));
        assertThat(get("b").rotation()).isCloseTo(-Math.PI / 2, offset(1e-9));
    }

    @Test
    void handshakeBringsPlayersTogether() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1.6, 0);

        world.handshake("a");
        world.handshake("b");

        assertThat(distance(get("a"), get("b"))).isCloseTo(GameWorld.HANDSHAKE_DISTANCE, offset(1e-9));
        assertThat(get("a").x()).isCloseTo(0.3, offset(1e-9)); // обидва зробили по пів кроку
    }

    @Test
    void repeatedOfferIsSilent() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);

        world.handshake("a");
        assertThat(world.handshake("a")).isNull();
    }

    @Test
    void offerExpires() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);

        world.handshake("a");
        clock.addAndGet(GameWorld.HANDSHAKE_OFFER_NANOS);

        // Рука вже опущена — тепер b сам простягає руку a.
        assertHandshake(world.handshake("b"), "b", "a", false);
    }

    @Test
    void cannotAcceptFromTooFar() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);
        world.handshake("a");

        walk("b", 1, 0, 10); // відійшов на 3 м

        assertThat(world.handshake("b")).isNull(); // поруч нікого
    }

    @Test
    void acceptPrefersPlayerWhoOffered() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1.4, 0);
        world.join("c", "C", Gender.MALE, -1, 0); // ближче до a, але руки не простягав
        world.handshake("b");

        assertHandshake(world.handshake("a"), "b", "a", true);
    }

    @Test
    void nobodyNearbyMeansNoOffer() {
        world.join("a", "A", Gender.MALE, 0, 0);

        assertThat(world.handshake("a")).isNull();
    }

    @Test
    void cannotStartSecondHandshakeWhileShaking() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);
        world.handshake("a");
        world.handshake("b");

        assertThat(world.handshake("a")).isNull();
        clock.addAndGet(GameWorld.HANDSHAKE_NANOS);
        assertHandshake(world.handshake("a"), "a", "b", false);
    }

    @Test
    void rejoinWithTokenRestoresCharacterAndPosition() {
        var first = world.join("s1", null, "Roman", Gender.MALE, null);
        walk("s1", 1, 0, 10);
        var before = get("s1");
        world.leave("s1");

        var again = world.join("s2", first.token(), "ігнорується", Gender.FEMALE, null);

        assertThat(again.token()).isEqualTo(first.token());
        assertThat(again.player().name()).isEqualTo("Roman");
        assertThat(again.player().gender()).isEqualTo(Gender.MALE);
        assertThat(again.player().appearance()).isEqualTo(first.player().appearance());
        assertThat(again.player().x()).isEqualTo(before.x());
        assertThat(again.player().z()).isEqualTo(before.z());
        assertThat(again.player().rotation()).isEqualTo(before.rotation());
    }

    @Test
    void tokenAlreadyInGameGetsNewProfile() {
        var first = world.join("s1", null, "Roman", Gender.MALE, null);

        var second = world.join("s2", first.token(), "Roman", Gender.MALE, first.player().appearance());

        assertThat(second.token()).isNotEqualTo(first.token()); // друга вкладка — інший персонаж
        assertThat(second.player().appearance()).isEqualTo(first.player().appearance());
    }

    @Test
    void unknownTokenUsesValidCachedAppearance() {
        var cached = new Appearance("#a86b45", "#8c2f1b", "#f97316", "#111827", 4, true);

        var result = world.join("s1", "forgotten-after-restart", "Roman", Gender.MALE, cached);

        assertThat(result.token()).isNotEqualTo("forgotten-after-restart");
        assertThat(result.player().appearance()).isEqualTo(cached);
    }

    @Test
    void invalidCachedAppearanceIsIgnored() {
        var notFromPalette = new Appearance("#ff00ff", "#8c2f1b", "#f97316", "#111827", 4, false);
        var beardOnFemale = new Appearance("#a86b45", "#8c2f1b", "#f97316", "#111827", 4, true);

        assertThat(world.join("s1", null, "A", Gender.MALE, notFromPalette).player().appearance()).isNotEqualTo(notFromPalette);
        assertThat(world.join("s2", null, "B", Gender.FEMALE, beardOnFemale).player().appearance().beard()).isFalse();
    }

    @Test
    void occupiedLastPositionMovesToFreeSpot() {
        var first = world.join("s1", null, "Roman", Gender.MALE, null);
        var spot = first.player();
        world.leave("s1");
        world.join("blocker", "B", Gender.MALE, spot.x(), spot.z());

        var again = world.join("s2", first.token(), "Roman", Gender.MALE, null);

        assertThat(distance(again.player(), get("blocker"))).isGreaterThanOrEqualTo(MIN_DIST - 1e-6);
    }

    @Test
    void profilesExpire() {
        var first = world.join("s1", null, "Roman", Gender.MALE, null);
        world.leave("s1");
        clock.addAndGet(GameWorld.PROFILE_TTL_NANOS + 1);

        assertThat(world.join("s2", first.token(), "Roman", Gender.MALE, null).token()).isNotEqualTo(first.token());
    }

    // ---- Очки ----

    @Test
    void handshakeGivesBothOnePoint() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);

        shakeHands("a", "b");

        assertThat(world.player("a").score()).isEqualTo(1);
        assertThat(world.player("b").score()).isEqualTo(1);
    }

    @Test
    void samePairScoresOncePerCooldown() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);

        shakeHands("a", "b");
        clock.addAndGet(GameWorld.HANDSHAKE_NANOS);
        var second = shakeHands("b", "a"); // хто ініціює — не важливо, пара та сама
        assertThat(second.details().scored()).isFalse();
        assertThat(world.player("a").score()).isEqualTo(1);

        clock.addAndGet(GameWorld.HANDSHAKE_SCORE_COOLDOWN_NANOS);
        assertThat(shakeHands("a", "b").details().scored()).isTrue();
        assertThat(world.player("a").score()).isEqualTo(2);
    }

    @Test
    void slapTakesPointFromTargetOnly() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);
        shakeHands("a", "b"); // обидва по 1
        clock.addAndGet(GameWorld.HANDSHAKE_NANOS);

        world.slap("a");
        clock.addAndGet(GameWorld.SLAP_IMPACT_DELAY_NANOS);
        var impact = world.resolveSlaps().getFirst();

        assertThat(impact.penalty()).isEqualTo(1);
        assertThat(world.player("b").score()).isZero();
        assertThat(world.player("a").score()).isEqualTo(1); // той, хто б'є, нічого не заробляє
    }

    @Test
    void scoreNeverGoesBelowZero() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);

        world.slap("a");
        clock.addAndGet(GameWorld.SLAP_IMPACT_DELAY_NANOS);
        var impact = world.resolveSlaps().getFirst();

        assertThat(impact.penalty()).isZero();
        assertThat(impact.update()).isNull();
        assertThat(world.player("b").score()).isZero();
    }

    @Test
    void dodgedSlapCostsNothing() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);
        shakeHands("a", "b");
        clock.addAndGet(GameWorld.HANDSHAKE_NANOS);
        world.jump("b");

        world.slap("a");
        clock.addAndGet(GameWorld.SLAP_IMPACT_DELAY_NANOS);

        assertThat(world.resolveSlaps().getFirst().penalty()).isZero();
        assertThat(world.player("b").score()).isEqualTo(1);
    }

    // ---- Кольори й групові чати ----

    @Test
    void everyoneGetsOwnUniqueColor() {
        for (int i = 0; i < Groups.PALETTE.size() + 5; i++) {
            world.join("p" + i, "P", Gender.MALE, i * 3, 0);
        }

        var colors = world.snapshot().stream().map(p -> p.color().toLowerCase()).toList();
        assertThat(colors).doesNotHaveDuplicates().hasSize(Groups.PALETTE.size() + 5);
        assertThat(world.player("p0").color()).isEqualTo(Groups.PALETTE.getFirst());
        assertThat(world.player("p0").group()).isNull(); // загальний чат
    }

    @Test
    void joiningTakesHostColorAndSharesChat() {
        world.join("host", "Roman", Gender.MALE, 0, 0);
        world.join("guest", "Ira", Gender.FEMALE, 1.2, 0);
        String hostColor = world.player("host").color();

        var outcome = world.joinPlayer("guest", "host");

        assertThat(outcome.ok()).isTrue();
        var host = world.player("host");
        var guest = world.player("guest");
        assertThat(guest.color()).isEqualTo(hostColor);
        assertThat(host.color()).isEqualTo(hostColor);
        assertThat(guest.group()).isNotNull().isEqualTo(host.group());
        assertThat(outcome.updates()).extracting(PlayerUpdate::id).containsExactlyInAnyOrder("host", "guest");
        assertThat(world.chatRecipients(host.group())).containsExactlyInAnyOrder("host", "guest");
    }

    @Test
    void joinRequiresStandingNearThePlayer() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, Groups.CIRCLE_RADIUS * 2 + 0.1, 0);

        assertThat(world.joinPlayer("b", "a").ok()).isFalse(); // задалеко навіть із запасом

        world.teleport("b", Groups.CIRCLE_RADIUS - 0.3, 0);
        assertThat(world.joinPlayer("b", "a").ok()).isTrue();
    }

    @Test
    void joiningAnyMemberJoinsTheirGroup() {
        world.join("host", "H", Gender.MALE, 0, 0);
        world.join("first", "F", Gender.MALE, 1, 0);
        world.join("late", "L", Gender.MALE, 30, 0);
        world.joinPlayer("first", "host");
        world.teleport("first", 29, 0); // учасник пішов далеко від господаря

        world.joinPlayer("late", "first");

        assertThat(world.player("late").group()).isEqualTo(world.player("host").group());
        assertThat(world.player("late").color()).isEqualTo(world.player("host").color());
    }

    @Test
    void generalChatIsEveryoneWithoutGroup() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);
        world.join("solo", "S", Gender.MALE, 30, 0);
        world.joinPlayer("b", "a");

        assertThat(world.chatRecipients(null)).containsExactly("solo");
        assertThat(world.groupOf("solo")).isNull();
    }

    @Test
    void leavingRestoresOwnColorAndGeneralChat() {
        world.join("host", "H", Gender.MALE, 0, 0);
        world.join("a", "A", Gender.MALE, 1, 0);
        world.join("b", "B", Gender.MALE, -1, 0);
        String ownA = world.player("a").color();
        world.joinPlayer("a", "host");
        world.joinPlayer("b", "host");

        var outcome = world.leaveGroup("a");

        assertThat(world.player("a").group()).isNull();
        assertThat(world.player("a").color()).isEqualTo(ownA);
        assertThat(world.player("b").group()).isEqualTo(world.player("host").group()); // інші лишились
        assertThat(outcome.deletedGroups()).isEmpty();
    }

    @Test
    void groupOfOneDissolves() {
        world.join("host", "H", Gender.MALE, 0, 0);
        world.join("a", "A", Gender.MALE, 1, 0);
        world.joinPlayer("a", "host");
        String group = world.player("host").group();

        var outcome = world.leaveGroup("a");

        assertThat(outcome.deletedGroups()).containsExactly(group);
        assertThat(world.player("host").group()).isNull(); // лишився сам — назад у загальний
        assertThat(outcome.updates()).extracting(PlayerUpdate::id).containsExactlyInAnyOrder("a", "host");
    }

    @Test
    void hostLeavingRecolorsGroupToNextOldest() {
        world.join("host", "H", Gender.MALE, 0, 0);
        world.join("first", "F", Gender.MALE, 1, 0);
        world.join("second", "S", Gender.MALE, -1, 0);
        String firstOwn = world.player("first").color();
        world.joinPlayer("first", "host");
        world.joinPlayer("second", "host");

        var outcome = world.leaveGroup("host");

        assertThat(world.player("first").color()).isEqualTo(firstOwn);
        assertThat(world.player("second").color()).isEqualTo(firstOwn);
        assertThat(world.player("second").group()).isEqualTo(world.player("first").group());
        assertThat(outcome.updates()).extracting(PlayerUpdate::id).contains("first", "second", "host");
    }

    @Test
    void joiningAnotherGroupLeavesPrevious() {
        world.join("x", "X", Gender.MALE, 0, 0);
        world.join("y", "Y", Gender.MALE, 30, 0);
        world.join("me", "M", Gender.MALE, 1, 0);
        world.join("other", "O", Gender.MALE, 31, 0);
        world.joinPlayer("me", "x");
        world.joinPlayer("other", "y");
        String oldGroup = world.player("x").group();

        world.teleport("me", 29, 0);
        var outcome = world.joinPlayer("me", "y");

        assertThat(world.player("me").group()).isEqualTo(world.player("y").group());
        assertThat(outcome.deletedGroups()).containsExactly(oldGroup); // x лишився сам
        assertThat(world.player("x").group()).isNull();
    }

    @Test
    void alreadyTogetherIsRejected() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);
        world.joinPlayer("b", "a");

        assertThat(world.joinPlayer("a", "b").ok()).isFalse();
    }

    @Test
    void groupmatesCannotSlapEachOther() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("b", "B", Gender.MALE, 1, 0);
        world.joinPlayer("b", "a");

        var result = world.slap("a");

        assertThat(result.targetId()).isNull();
        assertThat(result.allyId()).isEqualTo("b"); // клієнт покаже «Свої!»
    }

    @Test
    void slapSkipsGroupmateForNearestStranger() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.join("mate", "M", Gender.MALE, 1, 0);
        world.join("stranger", "S", Gender.MALE, -1.5, 0);
        world.joinPlayer("mate", "a");

        assertThat(world.slap("a").targetId()).isEqualTo("stranger");
    }

    @Test
    void colorScoreAndGroupSurviveRejoin() {
        var host = world.join("s1", null, "Roman", Gender.MALE, null);
        world.join("b", "B", Gender.MALE, host.player().x() + 1, host.player().z());
        world.joinPlayer("b", "s1");
        shakeHands("s1", "b");
        String color = world.player("s1").color();
        world.leave("s1");

        var again = world.join("s2", host.token(), "Roman", Gender.MALE, null);

        assertThat(again.player().score()).isEqualTo(1);
        assertThat(again.player().group()).isEqualTo(world.player("b").group());
        assertThat(again.player().color()).isEqualTo(color);
    }

    // ---- Статус ----

    @Test
    void setStatusIsVisibleToOthers() {
        world.join("a", "A", Gender.MALE, 0, 0);

        var update = world.setStatus("a", "  🎧 Слухаю   музику ");

        assertThat(update.status()).isEqualTo("🎧 Слухаю музику");
        assertThat(world.player("a").status()).isEqualTo("🎧 Слухаю музику");
    }

    @Test
    void emptyStatusClearsIt() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.setStatus("a", "😎 Чілю");
        clock.addAndGet(GameWorld.STATUS_COOLDOWN_NANOS);

        assertThat(world.setStatus("a", "   ").status()).isEmpty();
    }

    @Test
    void statusIsSanitizedAndTruncatedByCodePoints() {
        world.join("a", "A", Gender.MALE, 0, 0);

        var status = world.setStatus("a", "<b>📚" + "я".repeat(100)).status();

        assertThat(status).doesNotContain("<", ">").startsWith("b📚");
        assertThat(status.codePointCount(0, status.length())).isEqualTo(GameWorld.MAX_STATUS);
    }

    @Test
    void sameStatusOrTooFrequentChangeIsSilent() {
        world.join("a", "A", Gender.MALE, 0, 0);
        world.setStatus("a", "😎 Чілю");
        clock.addAndGet(GameWorld.STATUS_COOLDOWN_NANOS);

        assertThat(world.setStatus("a", "😎 Чілю")).isNull(); // не змінився — не розсилаємо
        assertThat(world.setStatus("a", "🎬 Дивлюсь фільм")).isNotNull();
        assertThat(world.setStatus("a", "💻 Пишу код")).isNull(); // занадто часто
        assertThat(world.player("a").status()).isEqualTo("🎬 Дивлюсь фільм");
    }

    @Test
    void statusSurvivesRejoin() {
        var first = world.join("s1", null, "Roman", Gender.MALE, null);
        world.setStatus("s1", "📚 Готуюсь до тех. співбесіди");
        world.leave("s1");

        var again = world.join("s2", first.token(), "Roman", Gender.MALE, null);

        assertThat(again.player().status()).isEqualTo("📚 Готуюсь до тех. співбесіди");
    }

    /** a простягає руку, b тисне. */
    private GameWorld.HandshakeResult shakeHands(String a, String b) {
        world.handshake(a);
        var result = world.handshake(b);
        assertThat(result.accepted()).isTrue();
        return result;
    }

    private static void assertHandshake(GameWorld.HandshakeResult r, String from, String to, boolean accepted) {
        assertThat(r).isNotNull();
        assertThat(r.from()).isEqualTo(from);
        assertThat(r.to()).isEqualTo(to);
        assertThat(r.accepted()).isEqualTo(accepted);
    }

    private void input(String id, double dx, double dz) {
        world.applyInput(id, ++seq, dx, dz);
    }

    /** Введення з реальною частотою — по одному на тік. */
    private void walk(String id, double dx, double dz, int ticks) {
        for (int i = 0; i < ticks; i++) {
            clock.addAndGet(TICK_NANOS);
            input(id, dx, dz);
        }
    }

    private PlayerInfo get(String id) {
        return world.snapshot().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    private PlayerPos pos(String id) {
        return world.positions().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    private static double distance(PlayerInfo a, PlayerInfo b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    @Test
    void addScoreUpdatesThePlayerAndNeverGoesBelowZero() {
        var world = new GameWorld(20, () -> 0L);
        world.join("a", "Ann", Gender.FEMALE);

        assertThat(world.addScore("a", 1).score()).isEqualTo(1);
        assertThat(world.addScore("a", -5).score()).isZero();
        assertThat(world.addScore("missing", 1)).isNull();
    }
}
