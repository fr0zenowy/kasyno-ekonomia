package pl.kasyno.work;

/** Podpowiedzi dla gracza na pasku akcji: strzałka do celu, „ciepło/zimno” i zegar. Same obliczenia, bez Bukkita. */
public final class Compass {
    private Compass() {}

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    /**
     * Strzałka względem tego, w którą stronę patrzy gracz. Yaw w Bukkicie: 0 = południe (+z), 90 = zachód (-x), rośnie zgodnie
     * z ruchem wskazówek zegara, więc cel po prawej ręce ma dodatni kąt względny.
     */
    public static String arrow(double playerYaw, double dx, double dz) {
        if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) return "•";
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((target - playerYaw) % 360 + 540) % 360 - 180; // (-180, 180]
        int idx = (int) Math.round(rel / 45.0);
        return ARROWS[Math.floorMod(idx, 8)];
    }

    /** Stopień „ciepła” 0 (lodowato) do 5 (parzy) dla odległości od skarbu w blokach. */
    public static int warmth(double distance) {
        if (distance >= 55) return 0;
        if (distance >= 38) return 1;
        if (distance >= 24) return 2;
        if (distance >= 13) return 3;
        if (distance >= 6) return 4;
        return 5;
    }

    public static String warmthLabel(int warmth) {
        return switch (warmth) {
            case 0 -> "<blue>Lodowato";
            case 1 -> "<aqua>Zimno";
            case 2 -> "<green>Chłodno";
            case 3 -> "<yellow>Ciepło";
            case 4 -> "<gold>Gorąco";
            default -> "<red><bold>Parzy!";
        };
    }

    /** Zegar „m:ss” z liczby ticków (20 na sekundę), zaokrąglany w górę, żeby nie pokazać 0:00 na ostatnim kroku. */
    public static String clock(long ticks) {
        long secs = Math.max(0, (ticks + 19) / 20);
        return (secs / 60) + ":" + String.format("%02d", secs % 60);
    }
}
