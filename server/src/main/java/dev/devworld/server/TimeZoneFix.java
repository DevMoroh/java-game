package dev.devworld.server;

import java.util.Map;
import java.util.TimeZone;

/**
 * macOS досі віддає Java застарілу назву поясу «Europe/Kiev», а драйвер PostgreSQL передає її базі
 * при підключенні — PostgreSQL 18 такої назви не знає й відмовляє в з'єднанні. Замінюємо застарілі
 * назви на сучасні; для решти поясів нічого не змінюється.
 */
public final class TimeZoneFix {

    private static final Map<String, String> RENAMED = Map.of(
            "Europe/Kiev", "Europe/Kyiv",
            "Europe/Uzhgorod", "Europe/Kyiv",
            "Europe/Zaporozhye", "Europe/Kyiv");

    private TimeZoneFix() {
    }

    public static void apply() {
        String current = TimeZone.getDefault().getID();
        String replacement = RENAMED.get(current);
        if (replacement != null) {
            TimeZone.setDefault(TimeZone.getTimeZone(replacement));
        }
    }
}
