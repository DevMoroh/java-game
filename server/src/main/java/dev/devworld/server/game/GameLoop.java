package dev.devworld.server.game;

import dev.devworld.server.community.Eggs;
import dev.devworld.server.game.Messages.State;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Фіксований тік: кролики й розсилка позицій усім гравцям (рух гравців застосовується одразу при отриманні введення).
 * Раз на секунду — золоті яйця (нові за розкладом, зникнення викинутих).
 */
@Component
public class GameLoop {

    private static final Logger log = LoggerFactory.getLogger(GameLoop.class);

    private final GameWorld world;
    private final GameSocketHandler handler;
    private final Animals animals;
    private final Dancer dancer;
    private final Eggs eggs;
    private final int tickRate;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("game-loop").factory());
    private long tick;

    private final long flushIntervalMs;

    public GameLoop(GameWorld world, GameSocketHandler handler, Animals animals, Dancer dancer, Eggs eggs,
                    @Value("${game.store.flush-interval-ms:5000}") long flushIntervalMs) {
        this.world = world;
        this.handler = handler;
        this.animals = animals;
        this.dancer = dancer;
        this.eggs = eggs;
        this.tickRate = world.tickRate();
        this.flushIntervalMs = flushIntervalMs;
    }

    @PostConstruct
    void start() {
        long periodMs = 1000L / tickRate;
        executor.scheduleAtFixedRate(this::tick, periodMs, periodMs, TimeUnit.MILLISECONDS);
        // Очки й позиції — у БД періодично (сам запис асинхронний, тік не гальмує).
        executor.scheduleAtFixedRate(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(this::eggs, 1, 1, TimeUnit.SECONDS);
        log.info("Game loop started at {} ticks/s", tickRate);
    }

    private void tick() {
        try {
            // Удари — до позицій, щоб відкидання потрапило в цей самий state.
            for (var impact : world.resolveSlaps()) {
                handler.broadcast(new Messages.SlapImpact(impact.slapperId(), impact.targetId(), impact.dodged(), impact.penalty()));
                if (impact.update() != null) {
                    handler.broadcast(impact.update());
                }
            }
            var positions = world.positions();
            animals.update(1.0 / tickRate, positions);
            dancer.update(1.0 / tickRate, positions);
            if (!positions.isEmpty()) {
                handler.broadcast(new State(++tick, positions, animals.positions(), List.of(dancer.position())));
            }
        } catch (RuntimeException e) {
            // Виняток у scheduleAtFixedRate зупинив би цикл назавжди.
            log.error("Tick failed", e);
        }
    }

    private void eggs() {
        try {
            handler.deliver(eggs.tick());
        } catch (RuntimeException e) {
            log.error("Eggs tick failed", e);
        }
    }

    private void flush() {
        try {
            world.flushProfiles();
        } catch (RuntimeException e) {
            log.error("Flush failed", e);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
