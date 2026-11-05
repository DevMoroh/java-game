package dev.devworld.server.community;

/** Повідомлення конкретному гравцю ({@code to} — id гравця); {@code to == null} — усім. Розсилає GameSocketHandler. */
public record Envelope(String to, Object message) {

    public static Envelope everyone(Object message) {
        return new Envelope(null, message);
    }
}
