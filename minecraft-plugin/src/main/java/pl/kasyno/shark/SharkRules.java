package pl.kasyno.shark;

/** Zasady ataku rekina bez Bukkita: jak rośnie szansa wraz z odległością od lądu i ile gotówki rekin zabiera. */
public final class SharkRules {
    private SharkRules() {}

    /**
     * Ustawienia (z config.yml, sekcja shark).
     *
     * @param minDistance    do tylu bloków od ostatniego lądu pływasz bezpiecznie
     * @param maxDistance    od tylu bloków szansa na próbę jest największa
     * @param maxChance      szansa na atak przy jednym losowaniu, gdy jesteś w maxDistance albo dalej (0-1)
     * @param checkSeconds   co ile sekund pływania następuje losowanie
     * @param minDepth       ile bloków wody musi być pod pływakiem, żeby rekin mógł podpłynąć
     * @param warningTicks   ile ticków mija od pojawienia się płetwy do ataku
     * @param cooldownSeconds po ataku (i po ucieczce) tyle sekund rekin daje spokój
     * @param lossFraction   jaką część gotówki z portfela zabiera pożarcie (0-1)
     */
    public record Config(boolean enabled, double minDistance, double maxDistance, double maxChance, int checkSeconds, int minDepth, int warningTicks, int cooldownSeconds, double lossFraction) {
        public static Config defaults() {
            return new Config(true, 20, 150, 0.2, 4, 3, 100, 120, 0.5);
        }

        /** Odcina wartości do rozsądnych granic, żeby zła konfiguracja niczego nie psuła. */
        public Config sane() {
            double min = clamp(minDistance, 0, 2000);
            double max = clamp(maxDistance, min + 1, 5000);
            return new Config(enabled, min, max, clamp(maxChance, 0, 1), (int) clamp(checkSeconds, 1, 120), (int) clamp(minDepth, 1, 20),
                    (int) clamp(warningTicks, 40, 600), (int) clamp(cooldownSeconds, 0, 36000), clamp(lossFraction, 0, 1));
        }
    }

    /** Szansa na atak przy jednym losowaniu dla gracza oddalonego o `distance` bloków od ostatniego lądu: 0 do minDistance, potem rośnie liniowo do maxChance. */
    public static double chance(double distance, Config c) {
        if (distance <= c.minDistance()) return 0;
        double f = Math.min(1.0, (distance - c.minDistance()) / (c.maxDistance() - c.minDistance()));
        return f * c.maxChance();
    }

    /** Ile monet zabiera rekin z portfela o podanej zawartości. */
    public static long loss(long wallet, double fraction) {
        if (wallet <= 0) return 0;
        return (long) Math.floor(wallet * clamp(fraction, 0, 1));
    }

    /** Szansa, że w trakcie pływania (kolejne losowania co checkSeconds) co najmniej raz dojdzie do ataku, dla stałej odległości. */
    public static double chanceOver(double distance, int rolls, Config c) {
        double p = chance(distance, c);
        return 1 - Math.pow(1 - p, Math.max(0, rolls));
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
