package pl.kasyno.show;

/**
 * Wysokość podłoża pod figurą. Pokaz liczy pozycje na płaszczyźnie, a wysokość bierze z punktów sceny, więc na schodach, podeście albo
 * dywanie figura wisiałaby w powietrzu albo tonęła w stopniach (a ekipa idzie gęsiego i wszyscy dostają wysokość przewodnika). Ta klasa
 * znajduje powierzchnię, na której figura naprawdę może stać: pierwszą bryłę kolizji pod punktem, nie wyżej niż półtora bloku nad nim.
 * Same obliczenia, bez Bukkita: świat podaje tylko, gdzie w danym rzędzie bloków kończy się kolizja.
 */
public final class Ground {
    private Ground() {}

    /** Ile figura może wejść w górę względem zadanej wysokości (stopień, dywan, płyta). */
    public static final double STEP_UP = 1.5;
    /** Ile bloków w dół szukamy podłoża, zanim uznamy, że go nie ma. */
    public static final int SEARCH_DOWN = 4;

    /** Powierzchnia świata w jednym punkcie (x, z). */
    @FunctionalInterface
    public interface Surface {
        /** Wysokość (bezwzględna) górnej krawędzi kolizji w rzędzie bloków `blockY` w tym punkcie albo NaN, gdy tam nic nie ma. */
        double top(int blockY);
    }

    /** Wysokość podłoża dla figury, która ma stać na `y`. Gdy nie ma żadnej powierzchni w zasięgu, zostaje `y`. */
    public static double snap(double y, Surface s) {
        double limit = y + STEP_UP + 1e-9;
        int from = (int) Math.floor(y + STEP_UP);
        int to = (int) Math.floor(y) - SEARCH_DOWN;
        for (int by = from; by >= to; by--) {
            double top = s.top(by);
            if (!Double.isNaN(top) && top <= limit) return top;
        }
        return y;
    }
}
