package dev.devworld.server.game;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Зовнішність персонажа. Генерується випадково для нового гостя і далі живе в його профілі.
 * {@code hairStyle} — індекс зачіски; клієнт бере його за модулем списку зачісок для статі.
 */
public record Appearance(String skin, String hair, String shirt, String pants, int hairStyle, boolean beard) {

    /** Має збігатися з таблицею SKIN_TONES у client/src/game/avatar/avatar.ts. */
    private static final List<String> SKINS = List.of("#f5d0b0", "#e8b48f", "#c98e66", "#a86b45", "#7a4a2c");
    private static final List<String> HAIRS = List.of("#2b1d14", "#5a3a22", "#a0662f", "#d9b26a", "#1c1c1c", "#8c2f1b");
    private static final List<String> SHIRTS = List.of("#3b82f6", "#ef4444", "#22c55e", "#eab308", "#a855f7", "#f97316", "#14b8a6", "#ec4899", "#f5f5f5", "#1f2937");
    private static final List<String> PANTS = List.of("#1e3a8a", "#374151", "#3f2d20", "#111827", "#4b5563", "#6b7280");
    private static final int HAIR_STYLES = 6;

    public static Appearance random(Gender gender, RandomGenerator rnd) {
        boolean beard = gender == Gender.MALE && rnd.nextDouble() < 0.35;
        return new Appearance(pick(SKINS, rnd), pick(HAIRS, rnd), pick(SHIRTS, rnd), pick(PANTS, rnd),
                rnd.nextInt(HAIR_STYLES), beard);
    }

    /**
     * Чи це зовнішність, яку міг видати сервер (кольори з палітр, борода — лише в чоловіків).
     * Клієнт присилає її з кешу браузера, коли сервер уже забув профіль (наприклад, після перезапуску).
     */
    public boolean isValidFor(Gender gender) {
        return SKINS.contains(skin) && HAIRS.contains(hair) && SHIRTS.contains(shirt) && PANTS.contains(pants)
                && hairStyle >= 0 && hairStyle < HAIR_STYLES && (!beard || gender == Gender.MALE);
    }

    private static String pick(List<String> list, RandomGenerator rnd) {
        return list.get(rnd.nextInt(list.size()));
    }
}
