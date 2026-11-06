package pl.sport.basket;

/**
 * Szansa na to, że wsad wpadnie. Wsad nie jest gwarancją punktów: zależy od tego, jak wysoko gracz wyskoczył, jak szybko biegł w stronę kosza,
 * z jakiej odległości zaczął i czy obrońca mu przeszkadza. Trudność z ustawień dzieli całą szansę (2 = dwa razy trudniej, 0 = wsad zawsze wpada).
 */
public final class DunkModel {
    private DunkModel() {}

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Jakość najścia 0-1 z wysokości wyskoku (m nad podłogą w chwili decyzji), prędkości biegu w stronę kosza (m/s) i odległości od obręczy.
     * Maksimum to wyskok ok. 0,8 m, bieg 5,5 m/s i start tuż przy koszu.
     */
    public static double quality(double jump, double toward, double dist, double range) {
        return clamp(0.45 * Math.min(1, jump / 0.8) + 0.35 * Math.min(1, toward / 5.5) + 0.20 * Math.max(0, 1 - dist / range), 0, 1);
    }

    /** Szansa wpadnięcia wsadu (0,05-0,92); przy trudności 0 wsad zawsze wpada. */
    public static double chance(double quality, double contest, double difficulty) {
        if (difficulty <= 0) return 1.0;
        return clamp((0.30 + 0.55 * clamp(quality, 0, 1) - 0.35 * clamp(contest, 0, 1)) / difficulty, 0.05, 0.92);
    }

    /** Szansa zablokowania wsadu przez obrońcę w wyskoku tuż obok (bez wyskoku obrońca wsadu nie zablokuje, tylko utrudni). */
    public static double blockChance(boolean defenderAirborne, double contest) {
        return defenderAirborne ? clamp(0.55 + 0.25 * contest, 0, 0.8) : 0;
    }
}
