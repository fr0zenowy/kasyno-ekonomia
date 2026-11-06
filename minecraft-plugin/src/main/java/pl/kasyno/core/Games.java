package pl.kasyno.core;

import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Matematyka gier: mnożniki, rozkłady losowań i zasady, bez żadnego Bukkita. Każda gra ma zwrot (RTP) poniżej 100%,
 * a testy jednostkowe liczą to symulacją. Wartości są takie same jak w bocie Discord.
 */
public final class Games {
    private Games() {}

    // ---------- wspólne ----------

    /** Wybór z wagami. */
    public static <T> T weighted(List<T> items, java.util.function.ToDoubleFunction<T> weight, RandomGenerator rng) {
        double total = 0;
        for (T t : items) total += weight.applyAsDouble(t);
        double r = rng.nextDouble() * total;
        for (T t : items) {
            r -= weight.applyAsDouble(t);
            if (r < 0) return t;
        }
        return items.get(items.size() - 1);
    }

    public static double floor2(double x) {
        return Math.floor(x * 100) / 100;
    }

    // ---------- gry krokowe (kurczak, wieża) ----------

    public static final double STEP_HOUSE = 0.96;
    public static final double MAX_MULT = 1000;

    /** Mnożnik po n bezpiecznych krokach, gdy każdy przeżywa się z prawdopodobieństwem pSafe. */
    public static double stepMultiplier(double pSafe, int n) {
        return n <= 0 ? 1 : floor2(Math.min(STEP_HOUSE / Math.pow(pSafe, n), MAX_MULT));
    }

    /** Ile kroków da się zrobić: do limitu konfiguracji albo do osiągnięcia mnożnika x1000. */
    public static int stepsLimit(double pSafe, int limit) {
        int n = 0;
        while (n < limit && STEP_HOUSE / Math.pow(pSafe, n + 1) <= MAX_MULT) n++;
        return Math.max(1, n);
    }

    // ---------- miny ----------

    public static final int MINES_TILES = 20;
    public static final double MINES_HOUSE = 0.97;

    /** Mnożnik po odkryciu k bezpiecznych pól przy m minach. */
    public static double minesMultiplier(int k, int m) {
        double x = MINES_HOUSE;
        for (int i = 0; i < k; i++) x *= (double) (MINES_TILES - i) / (MINES_TILES - m - i);
        return floor2(Math.min(x, MAX_MULT));
    }

    // ---------- hi-lo ----------

    public static final double HILO_HOUSE = 0.95;

    public static double pHigher(int rank) {
        return (14 - rank) / 13.0;
    }

    public static double pLower(int rank) {
        return (rank - 2) / 13.0;
    }

    /** `raw` to iloczyn uczciwych mnożników; prowizja domu liczona raz od całości. */
    public static double hiloMult(double raw) {
        return floor2(HILO_HOUSE * raw);
    }

    // ---------- crash ----------

    public static final double CRASH_HOUSE = 0.97;
    public static final double CRASH_MAX = 10_000;
    public static final double CRASH_GROWTH_PER_SECOND = Math.pow(1.11, 1000.0 / 1400.0);

    public static double crashPoint(RandomGenerator rng) {
        double r = rng.nextDouble();
        double p = Math.floor(100 * CRASH_HOUSE / (1 - r)) / 100;
        return Math.min(Math.max(p, 1), CRASH_MAX);
    }

    /** Mnożnik po `seconds` sekundach (ta sama krzywa co w bocie: +11% co 1,4 s). */
    public static double crashAt(double seconds) {
        return floor2(Math.pow(CRASH_GROWTH_PER_SECOND, seconds));
    }

    /** Szansa, że runda dojdzie do mnożnika x. */
    public static double crashReach(double x) {
        return Math.min(1, CRASH_HOUSE / x);
    }

    // ---------- limbo, szansa, rewolwer ----------

    public static double limboRoll(double u) {
        return Math.min(1_000_000, Math.max(1, Math.floor(96 / (1 - u)) / 100));
    }

    public static double limboRoll(RandomGenerator rng) {
        return limboRoll(rng.nextDouble());
    }

    public static final double LIMBO_MIN = 1.01;
    public static final double LIMBO_MAX = 1000;

    public static double chanceMult(double chancePercent) {
        return floor2(96 / chancePercent);
    }

    public static final double CHANCE_MIN = 0.5;
    public static final double CHANCE_MAX = 95;

    public static double revolverMult(int bullets) {
        return floor2(0.96 * 6 / (6 - bullets));
    }

    // ---------- plinko ----------

    /** Numer koszyka: liczba ruchów w prawo na 12 rzędach. */
    public static int plinkoBucket(boolean[] path) {
        int b = 0;
        for (boolean right : path) if (right) b++;
        return b;
    }

    // ---------- moneta, kości, ruletka, knp ----------

    public static final double COIN_PAYOUT = 1.95;
    public static final double RPS_MULT = 1.9;

    public record DiceBet(String id, String label, double mult) {
        public boolean wins(int sum) {
            return switch (id) {
                case "nizej" -> sum < 7;
                case "wyzej" -> sum > 7;
                default -> sum == 7;
            };
        }
    }

    public static final List<DiceBet> DICE = List.of(new DiceBet("nizej", "Poniżej 7", 2.3), new DiceBet("wyzej", "Powyżej 7", 2.3),
            new DiceBet("siedem", "Dokładnie 7", 5.7));

    public static final Set<Integer> RED = Set.of(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36);

    public static String rouletteColor(int n) {
        return n == 0 ? "green" : RED.contains(n) ? "red" : "black";
    }

    /** Zakłady zewnętrzne ruletki; "num:N" to numer (x36). */
    public record RouletteBet(String id, String label, double mult) {
        public boolean wins(int n) {
            if (id.startsWith("num:")) return n == Integer.parseInt(id.substring(4));
            return switch (id) {
                case "czerwone" -> RED.contains(n);
                case "czarne" -> n != 0 && !RED.contains(n);
                case "parzyste" -> n != 0 && n % 2 == 0;
                case "nieparzyste" -> n % 2 == 1;
                case "niskie" -> n >= 1 && n <= 18;
                case "wysokie" -> n >= 19;
                case "tuzin1" -> n >= 1 && n <= 12;
                case "tuzin2" -> n >= 13 && n <= 24;
                case "tuzin3" -> n >= 25;
                default -> false;
            };
        }
    }

    public static final List<RouletteBet> ROULETTE = List.of(
            new RouletteBet("czerwone", "Czerwone", 2), new RouletteBet("czarne", "Czarne", 2),
            new RouletteBet("parzyste", "Parzyste", 2), new RouletteBet("nieparzyste", "Nieparzyste", 2),
            new RouletteBet("niskie", "Niskie 1-18", 2), new RouletteBet("wysokie", "Wysokie 19-36", 2),
            new RouletteBet("tuzin1", "Tuzin 1-12", 3), new RouletteBet("tuzin2", "Tuzin 13-24", 3), new RouletteBet("tuzin3", "Tuzin 25-36", 3));

    public static RouletteBet rouletteNumber(int n) {
        return new RouletteBet("num:" + n, "Numer " + n, 36);
    }

    // ---------- blackjack ----------

    public record Card(int rank, int suit) {
        /** 1 = As, 11-13 = J, Q, K. */
        public String label() {
            return switch (rank) {
                case 1 -> "A";
                case 11 -> "J";
                case 12 -> "Q";
                case 13 -> "K";
                default -> String.valueOf(rank);
            };
        }

        public String suitSymbol() {
            return switch (suit) {
                case 0 -> "♠";
                case 1 -> "♥";
                case 2 -> "♦";
                default -> "♣";
            };
        }

        public boolean red() {
            return suit == 1 || suit == 2;
        }
    }

    public static int handValue(List<Card> cards) {
        int total = 0;
        int aces = 0;
        for (Card c : cards) {
            if (c.rank() == 1) {
                aces++;
                total += 11;
            } else total += Math.min(10, c.rank());
        }
        while (total > 21 && aces > 0) {
            total -= 10;
            aces--;
        }
        return total;
    }

    public static boolean isBlackjack(List<Card> cards) {
        return cards.size() == 2 && handValue(cards) == 21;
    }
}
