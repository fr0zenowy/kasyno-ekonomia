package pl.kasyno.prison;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** „Sekwencja”: strażnik pokazuje ciąg kolorów, a Ty powtarzasz go w tej samej kolejności. */
public final class SimonRound {
    public enum Result { OK, DONE, WRONG }

    private final List<Integer> sequence = new ArrayList<>();
    private int pos;

    public SimonRound(int length, int colors, RandomGenerator rng) {
        int n = Math.max(1, length);
        int c = Math.max(2, colors);
        for (int i = 0; i < n; i++) {
            int next = rng.nextInt(c);
            // dwa takie same kolory pod rząd trudno odróżnić w migotaniu, więc je rozdzielamy
            if (i > 0 && next == sequence.get(i - 1)) next = (next + 1 + rng.nextInt(c - 1)) % c;
            sequence.add(next);
        }
    }

    public List<Integer> sequence() {
        return List.copyOf(sequence);
    }

    /** Kolejny wciśnięty kolor. WRONG zeruje wpisywanie (trzeba zacząć od początku), DONE oznacza cały ciąg. */
    public Result input(int color) {
        if (color != sequence.get(pos)) {
            pos = 0;
            return Result.WRONG;
        }
        pos++;
        if (pos >= sequence.size()) {
            pos = 0;
            return Result.DONE;
        }
        return Result.OK;
    }

    /** Ile kolorów już wpisano. */
    public int entered() {
        return pos;
    }

    public int length() {
        return sequence.size();
    }
}
