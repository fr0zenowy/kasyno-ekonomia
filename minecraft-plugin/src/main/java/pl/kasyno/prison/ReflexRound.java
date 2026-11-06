package pl.kasyno.prison;

import java.util.random.RandomGenerator;

/**
 * „Refleks”: na jednym z pól siatki zapala się cel, trzeba go kliknąć, zanim zgaśnie. Trafienie liczy punkt, pudło albo spóźnienie
 * to błąd. Wygrywa, kto zbierze `hitsToWin` trafień, zanim popełni więcej niż `maxMisses` błędów.
 */
public final class ReflexRound {
    public enum State { RUNNING, WON, LOST }

    private final int hitsToWin;
    private final int maxMisses;
    private final int cells;
    private final RandomGenerator rng;
    private int hits;
    private int misses;
    private int target = -1;
    private State state = State.RUNNING;

    public ReflexRound(int hitsToWin, int maxMisses, int cells, RandomGenerator rng) {
        this.hitsToWin = Math.max(1, hitsToWin);
        this.maxMisses = Math.max(0, maxMisses);
        this.cells = Math.max(2, cells);
        this.rng = rng;
        next();
    }

    private void next() {
        int previous = target;
        do {
            target = rng.nextInt(cells);
        } while (target == previous);
    }

    /** Kliknięcie w pole: trafiony cel to punkt, inne pole to błąd. */
    public State hit(int cell) {
        if (state != State.RUNNING) return state;
        if (cell == target) {
            hits++;
            if (hits >= hitsToWin) state = State.WON;
        } else {
            miss();
            return state;
        }
        if (state == State.RUNNING) next();
        return state;
    }

    /** Cel zgasł albo kliknięto obok. */
    public State miss() {
        if (state != State.RUNNING) return state;
        misses++;
        if (misses > maxMisses) state = State.LOST;
        else next();
        return state;
    }

    /** Jak długo cel świeci (w tickach): z każdym trafieniem trochę krócej, nie mniej niż 12. */
    public int windowTicks() {
        return Math.max(12, 30 - 3 * hits);
    }

    public int target() {
        return target;
    }

    public int hits() {
        return hits;
    }

    public int misses() {
        return misses;
    }

    public int hitsToWin() {
        return hitsToWin;
    }

    public int maxMisses() {
        return maxMisses;
    }

    public State state() {
        return state;
    }
}
