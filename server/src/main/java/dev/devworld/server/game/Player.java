package dev.devworld.server.game;

/**
 * Стан гравця на сервері. Змінюється лише всередині {@link GameWorld} під його локом.
 * Координати світу: площина XZ, (0,0) — центр поля.
 */
final class Player {

    final String id;
    /** Токен гостьового профілю, з яким гравець зайшов (null — у тестах без профілю). */
    final String token;
    /** Ключ учасника груп і очок: токен профілю, а без профілю (тести) — id сесії. */
    final String memberKey;
    final String name;
    final Gender gender;
    final Appearance appearance;

    double x;
    double z;
    /** Власний колір кола (унікальний серед гравців у грі); у груповому чаті коло — кольору групи. */
    String color;
    /** Що гравець зараз робить («🎧 Слухаю музику»); порожньо — без статусу. */
    String status = "";
    /** Коли можна змінити статус наступного разу (захист від спаму розсилками). */
    long statusReadyAtNanos;
    /** Особисті очки: +1 за рукостискання, −1 за отриманого ляща (не нижче 0). */
    int score;
    /** Що востаннє записали в БД — щоб періодично зберігати лише змінене. */
    double savedX = Double.NaN;
    double savedZ = Double.NaN;
    int savedScore = -1;
    /** Кут повороту навколо осі Y (радіани), куди дивиться персонаж. */
    double rotation;

    /** Номер останнього обробленого введення — клієнт по ньому звіряє передбачення. */
    long lastSeq;
    /** Token bucket проти спідхаку: одне введення = один токен. */
    double inputTokens;
    long lastRefillNanos;
    /** До цього моменту (System.nanoTime) персонаж вважається таким, що йде, — для анімації. */
    long movingUntilNanos;
    /** До цього моменту гравець тисне руку (поки що — лише щоб не почати друге рукостискання). */
    long shakingUntilNanos;
    /** До цього моменту гравець у повітрі (стрибок). */
    long airborneUntilNanos;
    /** Коли можна дати наступного ляща. */
    long slapReadyAtNanos;

    Player(String id, String token, String name, Gender gender, Appearance appearance, double x, double z) {
        this.id = id;
        this.token = token;
        this.memberKey = token != null ? token : "id:" + id;
        this.name = name;
        this.gender = gender;
        this.appearance = appearance;
        this.x = x;
        this.z = z;
    }
}
