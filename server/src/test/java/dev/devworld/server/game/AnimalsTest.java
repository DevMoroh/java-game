package dev.devworld.server.game;

import dev.devworld.server.game.Messages.PlayerPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class AnimalsTest {

    private static final double DT = 0.05;

    @Test
    void rabbitsHopAroundAndStayInsideTheField() {
        var animals = new Animals(new Random(1));
        var start = animals.positions();
        boolean sawHop = false;
        for (int i = 0; i < 20 * 120; i++) {
            animals.update(DT, List.of());
            sawHop |= animals.positions().stream().anyMatch(Messages.AnimalPos::hopping);
            assertThat(animals.positions()).allSatisfy(a -> {
                assertThat(Math.abs(a.x())).isLessThanOrEqualTo(GameWorld.BOUNDARY);
                assertThat(Math.abs(a.z())).isLessThanOrEqualTo(GameWorld.BOUNDARY);
            });
        }

        assertThat(sawHop).isTrue();
        assertThat(animals.positions()).hasSize(Animals.RABBITS);
        var end = animals.positions();
        int moved = 0;
        for (int i = 0; i < start.size(); i++) {
            if (Math.hypot(end.get(i).x() - start.get(i).x(), end.get(i).z() - start.get(i).z()) > 1) {
                moved++;
            }
        }
        assertThat(moved).isGreaterThan(Animals.RABBITS / 2);
    }

    @Test
    void rabbitRunsAwayFromAPlayer() {
        var animals = new Animals(new Random(2));
        var rabbit = animals.positions().getFirst();
        var player = new PlayerPos("p", rabbit.x() + 1, rabbit.z(), 0, false, 0);

        animals.update(DT, List.of(player));
        assertThat(animals.positions().getFirst().hopping()).isTrue();
        for (int i = 0; i < 20; i++) {
            animals.update(DT, List.of(player));
        }

        var after = animals.positions().getFirst();
        assertThat(Math.hypot(after.x() - player.x(), after.z() - player.z())).isGreaterThan(2);
    }
}
