package dev.devworld.server.game;

import dev.devworld.server.game.Messages.NpcPos;
import dev.devworld.server.game.Messages.PlayerPos;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

/**
 * Танцівниця — NPC, що живе на полі сама: гуляє до випадкової точки (іноді перебігає), а дійшовши — танцює.
 * Підійшов гравець, поки вона йде, — зупиняється, повертається до нього й танцює для нього (не частіше,
 * ніж раз на {@link #GREET_COOLDOWN} секунд, щоб гравець не «тримав» її на місці). Як і кролики, проходить крізь
 * гравців і предмети. Анімацію за {@code action} (walk / run / dance) клієнт малює сам.
 */
@Component
public class Dancer {

    static final String ID = "dancer";
    /** Швидкості під кроки анімацій Walking і Running (client/src/game/Dancer.tsx підганяє їх темп). */
    static final double WALK_SPEED = 1.3;
    static final double RUN_SPEED = 3.6;
    /** Один цикл танцю Bass_Beats — 6 с; танцює цілу кількість циклів. */
    static final double DANCE_LOOP = 6;
    static final double GREET_DISTANCE = 4;
    static final double GREET_COOLDOWN = 20;
    private static final double LIMIT = GameWorld.BOUNDARY - 3;
    private static final double ARRIVED = 0.3;

    enum Action { WALK, RUN, DANCE }

    private final Random random;
    private double x;
    private double z;
    private double rotation;
    private Action action = Action.DANCE;
    private double targetX;
    private double targetZ;
    /** Скільки ще танцювати. */
    private double timer;
    private double greetCooldown;

    public Dancer() {
        this(new Random());
    }

    Dancer(Random random) {
        this.random = random;
        x = (random.nextDouble() * 2 - 1) * LIMIT * 0.5;
        z = (random.nextDouble() * 2 - 1) * LIMIT * 0.5;
        rotation = random.nextDouble() * Math.PI * 2;
        timer = DANCE_LOOP;
    }

    public synchronized void update(double dt, List<PlayerPos> players) {
        greetCooldown -= dt;
        if (action == Action.DANCE) {
            timer -= dt;
            if (timer <= 0) {
                pickTarget();
            }
            return;
        }
        var fan = nearest(players);
        if (fan != null && greetCooldown <= 0) {
            // Гравець поруч — зупиняється й танцює для нього.
            rotation = Math.atan2(fan.x() - x, fan.z() - z);
            dance(1);
            greetCooldown = GREET_COOLDOWN;
            return;
        }
        double dx = targetX - x;
        double dz = targetZ - z;
        double distance = Math.hypot(dx, dz);
        double step = (action == Action.RUN ? RUN_SPEED : WALK_SPEED) * dt;
        if (distance <= Math.max(step, ARRIVED)) {
            x = targetX;
            z = targetZ;
            dance(1 + random.nextInt(3));
            return;
        }
        rotation = Math.atan2(dx, dz);
        x += dx / distance * step;
        z += dz / distance * step;
    }

    public synchronized NpcPos position() {
        return new NpcPos(ID, round(x), round(z), round(rotation), action.name().toLowerCase());
    }

    synchronized Action action() {
        return action;
    }

    private void dance(int loops) {
        action = Action.DANCE;
        timer = loops * DANCE_LOOP;
    }

    private void pickTarget() {
        // Нова точка — не надто близько, щоб прогулянка була помітною; далеку іноді перебігає.
        do {
            targetX = (random.nextDouble() * 2 - 1) * LIMIT;
            targetZ = (random.nextDouble() * 2 - 1) * LIMIT;
        } while (Math.hypot(targetX - x, targetZ - z) < 6);
        boolean far = Math.hypot(targetX - x, targetZ - z) > 18;
        action = far && random.nextDouble() < 0.5 ? Action.RUN : Action.WALK;
    }

    private PlayerPos nearest(List<PlayerPos> players) {
        PlayerPos best = null;
        double bestD = GREET_DISTANCE;
        for (var p : players) {
            double d = Math.hypot(p.x() - x, p.z() - z);
            if (d < bestD) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
