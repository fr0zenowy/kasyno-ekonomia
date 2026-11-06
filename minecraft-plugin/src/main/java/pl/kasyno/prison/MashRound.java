package pl.kasyno.prison;

/** „Podkop”: trzeba kliknąć `needed` razy w ciągu `windowMs` od pierwszego kliknięcia. Czas podaje wołający, więc to samo liczy testy. */
public final class MashRound {
    public enum State { WAITING, RUNNING, WON, LOST }

    private final int needed;
    private final long windowMs;
    private long startedAt;
    private int clicks;
    private State state = State.WAITING;

    public MashRound(int needed, long windowMs) {
        this.needed = Math.max(1, needed);
        this.windowMs = Math.max(1, windowMs);
    }

    /** Kliknięcie w chwili `now`. Pierwsze uruchamia zegar, spóźnione przegrywa. */
    public State click(long now) {
        if (state == State.WON || state == State.LOST) return state;
        if (state == State.WAITING) {
            startedAt = now;
            state = State.RUNNING;
        }
        if (now - startedAt > windowMs) {
            state = State.LOST;
            return state;
        }
        clicks++;
        if (clicks >= needed) state = State.WON;
        return state;
    }

    /** Sprawdza, czy czas już minął (bez kliknięcia). */
    public State poll(long now) {
        if (state == State.RUNNING && now - startedAt > windowMs) state = State.LOST;
        return state;
    }

    public State state() {
        return state;
    }

    public int clicks() {
        return clicks;
    }

    public int needed() {
        return needed;
    }

    /** Postęp 0..1. */
    public double progress() {
        return Math.min(1.0, clicks / (double) needed);
    }

    /** Ile milisekund zostało (przed pierwszym kliknięciem: całe okno). */
    public long msLeft(long now) {
        if (state == State.WAITING) return windowMs;
        return Math.max(0, windowMs - (now - startedAt));
    }
}
