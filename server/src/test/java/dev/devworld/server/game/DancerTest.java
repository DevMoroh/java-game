package dev.devworld.server.game;

import dev.devworld.server.game.Dancer.Action;
import dev.devworld.server.game.Messages.PlayerPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class DancerTest {

    private static final double DT = 0.05;

    @Test
    void walksAroundDancesAndStaysInsideTheField() {
        var dancer = new Dancer(new Random(3));
        var start = dancer.position();
        var seen = new HashSet<String>();
        double travelled = 0;
        var last = start;
        for (int i = 0; i < 20 * 300; i++) {
            dancer.update(DT, List.of());
            var pos = dancer.position();
            seen.add(pos.action());
            travelled += Math.hypot(pos.x() - last.x(), pos.z() - last.z());
            last = pos;
            assertThat(Math.abs(pos.x())).isLessThanOrEqualTo(GameWorld.BOUNDARY);
            assertThat(Math.abs(pos.z())).isLessThanOrEqualTo(GameWorld.BOUNDARY);
        }

        assertThat(seen).contains("walk", "dance");
        assertThat(travelled).isGreaterThan(30);
    }

    @Test
    void stopsToDanceForANearbyPlayerButNotOverAndOver() {
        var dancer = new Dancer(new Random(3));
        while (dancer.action() == Action.DANCE) {
            dancer.update(DT, List.of());
        }
        var pos = dancer.position();
        var fan = new PlayerPos("p", pos.x() + 2, pos.z(), 0, false, 0);

        dancer.update(DT, List.of(fan));

        assertThat(dancer.action()).isEqualTo(Action.DANCE);
        // Обернулася до гравця (він на +X від неї).
        assertThat(dancer.position().rotation()).isCloseTo(Math.PI / 2, org.assertj.core.data.Offset.offset(0.01));

        // Відтанцювала цикл — іде далі, хоч гравець і досі поруч (затримка між «привітаннями»).
        for (int i = 0; i < (Dancer.DANCE_LOOP + 0.5) / DT; i++) {
            dancer.update(DT, List.of(fan));
        }
        assertThat(dancer.action()).isNotEqualTo(Action.DANCE);
    }
}
