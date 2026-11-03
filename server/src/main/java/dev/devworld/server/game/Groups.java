package dev.devworld.server.game;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Групові чати. У кожного гравця свій колір кола; хто підійшов до іншого й приєднався — потрапляє в його групу
 * й отримує колір групи. Хто ні в якій групі — у загальному чаті. Гравець — щонайбільше в одній групі.
 * Учасник — {@link Player#memberKey} (профіль гостя), тож членство переживає перезавантаження.
 * Не потокобезпечний: використовується лише під локом {@link GameWorld}.
 */
final class Groups {

    /** Радіус кола під гравцем; хто в нього зайшов, може приєднатися до чату цього гравця. */
    static final double CIRCLE_RADIUS = 1.6;
    /**
     * Кольори кіл — добре розрізнювані на траві (без зелених). Поки є вільний — беремо його;
     * далі генеруємо відтінки, пропускаючи зелений діапазон.
     */
    static final List<String> PALETTE = List.of(
            "#facc15", "#a855f7", "#3b82f6", "#ef4444", "#f97316", "#ec4899",
            "#06b6d4", "#f8fafc", "#6366f1", "#14b8a6", "#92400e", "#111827");

    static final class Group {
        /** Непрозорий ідентифікатор (назв у груп немає — їх «називають» учасники). */
        final String id;
        /** Колір групи — колір того, до кого приєдналися (господаря). */
        String color;
        String hostKey;
        /** У порядку вступу — якщо господар піде, ним стає найстаріший учасник. */
        final Set<String> members = new LinkedHashSet<>();

        Group(String id) {
            this.id = id;
        }
    }

    /** Наслідки виходу з групи: {@code dissolved} — лишився один, група зникла (його теж треба оновити). */
    record LeaveResult(Group group, boolean dissolved, String lastMember, boolean hostChanged) {
    }

    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, String> groupOfMember = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** Відновити групу з бази при старті (порожні й одноосібні — не потрібні). */
    void load(String id, String color, String hostKey, List<String> members) {
        var group = new Group(id);
        for (String m : members) {
            if (groupOfMember.putIfAbsent(m, key(id)) == null) {
                group.members.add(m);
            }
        }
        if (group.members.size() < 2) {
            group.members.forEach(groupOfMember::remove);
            return;
        }
        group.color = color;
        group.hostKey = hostKey != null && group.members.contains(hostKey) ? hostKey : group.members.iterator().next();
        groups.put(key(id), group);
    }

    Group groupOf(String member) {
        String k = groupOfMember.get(member);
        return k == null ? null : groups.get(k);
    }

    Group find(String id) {
        return id == null ? null : groups.get(key(id));
    }

    Collection<Group> all() {
        return groups.values();
    }

    /** Нова група з господарем {@code host} (його колір стає кольором групи). */
    Group create(String host, String color) {
        var group = new Group("g-" + Long.toString(random.nextLong() & Long.MAX_VALUE, 36));
        group.color = color;
        group.hostKey = host;
        groups.put(key(group.id), group);
        add(group, host);
        return group;
    }

    void add(Group group, String member) {
        group.members.add(member);
        groupOfMember.put(member, key(group.id));
    }

    /**
     * Вийти з групи. Якщо лишився один — група розпадається; якщо пішов господар — ним стає найстаріший
     * учасник, а колір групи — його власний ({@code personalColor}).
     */
    LeaveResult leave(String member, java.util.function.Function<String, String> personalColor) {
        var group = groupOf(member);
        if (group == null) {
            return null;
        }
        groupOfMember.remove(member);
        group.members.remove(member);
        if (group.members.size() < 2) {
            String last = group.members.isEmpty() ? null : group.members.iterator().next();
            group.members.forEach(groupOfMember::remove);
            group.members.clear();
            groups.remove(key(group.id));
            return new LeaveResult(group, true, last, false);
        }
        boolean hostChanged = member.equals(group.hostKey);
        if (hostChanged) {
            group.hostKey = group.members.iterator().next();
            String color = personalColor.apply(group.hostKey);
            if (color != null) {
                group.color = color;
            }
        }
        return new LeaveResult(group, false, null, hostChanged);
    }

    boolean sameGroup(String a, String b) {
        String ga = groupOfMember.get(a);
        return ga != null && ga.equals(groupOfMember.get(b));
    }

    /** Вільний колір, якого немає ні в {@code taken}, ні в груп. */
    String freeColor(Set<String> taken) {
        var used = new HashSet<String>();
        taken.forEach(c -> used.add(c.toLowerCase(Locale.ROOT)));
        groups.values().forEach(g -> used.add(g.color.toLowerCase(Locale.ROOT)));
        for (String c : PALETTE) {
            if (!used.contains(c)) {
                return c;
            }
        }
        // Палітра скінчилась — генеруємо відтінки «золотим кутом», оминаючи зелений (80°–160°).
        for (int i = 0; ; i++) {
            double hue = (i * 137.508) % 360;
            if (hue >= 80 && hue <= 160) {
                continue;
            }
            String c = hslToHex(hue, 0.75, 0.55);
            if (!used.contains(c)) {
                return c;
            }
        }
    }

    /** Чи зайнятий колір групою (тоді особистим кольором гравця він бути не може). */
    boolean colorUsedByGroup(String color) {
        return groups.values().stream().anyMatch(g -> g.color.equalsIgnoreCase(color));
    }

    List<String> membersOf(Group group) {
        return new ArrayList<>(group.members);
    }

    private static String key(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    private static String hslToHex(double h, double s, double l) {
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double x = c * (1 - Math.abs((h / 60) % 2 - 1));
        double m = l - c / 2;
        double[] rgb = h < 60 ? new double[]{c, x, 0} : h < 120 ? new double[]{x, c, 0} : h < 180 ? new double[]{0, c, x}
                : h < 240 ? new double[]{0, x, c} : h < 300 ? new double[]{x, 0, c} : new double[]{c, 0, x};
        return String.format("#%02x%02x%02x",
                Math.round((rgb[0] + m) * 255), Math.round((rgb[1] + m) * 255), Math.round((rgb[2] + m) * 255));
    }
}
