package dev.devworld.server.game;

import dev.devworld.server.game.Messages.AnimalPos;
import dev.devworld.server.game.Messages.PlayerPos;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Кролики: живуть самі по собі. Сидять, крутять головою (повертаються), потім стрибають серією коротких стрибків
 * у якийсь бік; якщо поруч гравець — тікають від нього. Через гравців і одне одного проходять (як і через дерева).
 * Позиції розсилаються разом зі state; висоту стрибка й анімацію клієнт малює сам за прапорцем {@code hopping}.
 */
@Component
public class Animals {

    static final int RABBITS = 8;
    /** Один стрибок: тривалість і швидкість; має збігатися з HOP_DURATION у client/src/game/Rabbits.tsx. */
    static final double HOP_SECONDS = 0.4;
    static final double HOP_SPEED = 3.2;
    static final double FLEE_SPEED = 5;
    /** Ближче цього до гравця кролик лякається. */
    static final double SCARE_DISTANCE = 3;
    private static final double LIMIT = GameWorld.BOUNDARY - 2;

    private enum Mode { REST, HOP }

    private static final class Rabbit {
        final String id;
        double x;
        double z;
        double rotation;
        Mode mode = Mode.REST;
        /** Скільки ще секунд у поточному стані (відпочинок або поточний стрибок). */
        double timer;
        int hopsLeft;
        double speed = HOP_SPEED;
        /** Під час відпочинку кролик іноді повертається: куди дивитись. */
        double lookAt;

        Rabbit(String id, double x, double z, double rotation) {
            this.id = id;
            this.x = x;
            this.z = z;
            this.rotation = rotation;
            this.lookAt = rotation;
        }
    }

    private final List<Rabbit> rabbits = new ArrayList<>();
    private final Random random;

    public Animals() {
        this(new Random());
    }

    Animals(Random random) {
        this.random = random;
        for (int i = 0; i < RABBITS; i++) {
            var r = new Rabbit("rabbit-" + i, (random.nextDouble() * 2 - 1) * LIMIT,
                    (random.nextDouble() * 2 - 1) * LIMIT, random.nextDouble() * Math.PI * 2);
            r.timer = random.nextDouble() * 4;
            rabbits.add(r);
        }
    }

    /** Крок симуляції на {@code dt} секунд; {@code players} — щоб тікати від тих, хто підійшов. */
    public synchronized void update(double dt, List<PlayerPos> players) {
        for (var r : rabbits) {
            var threat = nearest(r, players);
            if (threat != null && r.speed != FLEE_SPEED) {
                // Злякався: розвертається від гравця і тікає кількома швидкими стрибками.
                r.rotation = Math.atan2(r.x - threat.x(), r.z - threat.z()) + (random.nextDouble() - 0.5) * 0.8;
                r.mode = Mode.HOP;
                r.speed = FLEE_SPEED;
                r.hopsLeft = 3 + random.nextInt(3);
                r.timer = HOP_SECONDS;
            }
            r.timer -= dt;
            if (r.mode == Mode.REST) {
                r.rotation += shortestAngle(r.rotation, r.lookAt) * Math.min(1, dt * 6);
                if (r.timer <= 0) {
                    if (random.nextDouble() < 0.4) {
                        // Ще посидить, але подивиться в інший бік.
                        r.lookAt = r.rotation + (random.nextDouble() - 0.5) * Math.PI * 1.5;
                        r.timer = 0.8 + random.nextDouble() * 2;
                    } else {
                        r.mode = Mode.HOP;
                        r.speed = HOP_SPEED;
                        r.rotation = r.lookAt + (random.nextDouble() - 0.5) * 1.2;
                        r.hopsLeft = 1 + random.nextInt(5);
                        r.timer = HOP_SECONDS;
                    }
                }
            } else {
                double step = r.speed * dt;
                double nx = r.x + Math.sin(r.rotation) * step;
                double nz = r.z + Math.cos(r.rotation) * step;
                if (Math.abs(nx) > LIMIT || Math.abs(nz) > LIMIT) {
                    // Край поля — розвертаємось до центру.
                    r.rotation = Math.atan2(-r.x, -r.z) + (random.nextDouble() - 0.5);
                } else {
                    r.x = nx;
                    r.z = nz;
                }
                if (r.timer <= 0) {
                    if (--r.hopsLeft > 0) {
                        r.timer = HOP_SECONDS;
                        r.rotation += (random.nextDouble() - 0.5) * 0.5;
                    } else {
                        r.mode = Mode.REST;
                        r.speed = HOP_SPEED;
                        r.lookAt = r.rotation;
                        r.timer = 1.5 + random.nextDouble() * 6;
                    }
                }
            }
        }
    }

    public synchronized List<AnimalPos> positions() {
        var result = new ArrayList<AnimalPos>(rabbits.size());
        for (var r : rabbits) {
            result.add(new AnimalPos(r.id, round(r.x), round(r.z), round(r.rotation), r.mode == Mode.HOP));
        }
        return result;
    }

    private static PlayerPos nearest(Rabbit r, List<PlayerPos> players) {
        PlayerPos best = null;
        double bestD = SCARE_DISTANCE;
        for (var p : players) {
            double d = Math.hypot(p.x() - r.x, p.z() - r.z);
            if (d < bestD) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    private static double shortestAngle(double from, double to) {
        double d = (to - from) % (Math.PI * 2);
        if (d > Math.PI) {
            d -= Math.PI * 2;
        }
        if (d < -Math.PI) {
            d += Math.PI * 2;
        }
        return d;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
