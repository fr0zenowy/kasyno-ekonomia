package pl.kasyno.table;

import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Games;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.StripAnim;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ruletka europejska na żywo: wszyscy kładą żetony na dowolne pola (kolor, parzystość, tuzin albo konkretny numer), jedno koło
 * kręci się dla całego stołu. Kliknięcie pola stawia aktualną stawkę, a ten sam zakład stawiany ponownie zwiększa żeton.
 * Zwrot 97,3% (jedno zero), tak jak w bocie.
 */
public final class RouletteTable extends Table {
    /** Kolejność numerów na kole europejskim. */
    public static final int[] WHEEL = {0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26};
    public static final int HALF = 4;
    public static final int TARGET = 22;
    public static final int MAX_FIELDS = 12;
    public static final int HISTORY = 9;

    private int result = -1;
    private int[] strip = new int[0];
    private StripAnim.Schedule schedule;
    private int lastPos = HALF;
    private final List<Integer> history = new ArrayList<>();

    public RouletteTable(KasynoPlugin plugin) {
        super(plugin, "ruletka", "Ruletka", 20 * 20, 8 * 20);
    }

    public int result() {
        return phase == Phase.RESULT ? result : -1;
    }

    public List<Integer> history() {
        return history;
    }

    /** Zakład po jego identyfikatorze (kolor, tuzin albo "num:N"); null, gdy nieznany. */
    public static Games.RouletteBet field(String pick) {
        if (pick.startsWith("num:")) {
            try {
                int n = Integer.parseInt(pick.substring(4));
                return n >= 0 && n <= 36 ? Games.rouletteNumber(n) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        for (Games.RouletteBet b : Games.ROULETTE) if (b.id().equals(pick)) return b;
        return null;
    }

    /** Dziewięć numerów widocznych na pasku dla pozycji środka `pos`. */
    public int[] cells() {
        int[] out = new int[9];
        if (phase == Phase.RUNNING || phase == Phase.RESULT) {
            int pos = phase == Phase.RESULT ? TARGET : lastPos;
            for (int i = 0; i < 9; i++) out[i] = strip[Math.min(strip.length - 1, pos - HALF + i)];
            return out;
        }
        int center = 0;
        if (!history.isEmpty()) for (int i = 0; i < WHEEL.length; i++) if (WHEEL[i] == history.get(0)) center = i;
        for (int i = 0; i < 9; i++) out[i] = WHEEL[Math.floorMod(center - HALF + i, WHEEL.length)];
        return out;
    }

    @Override
    protected String validate(UUID player, String pick, long stake, double auto) {
        if (field(pick) == null) return "Nie ma takiego pola.";
        Seat s = seats.get(player);
        if (s != null && s.bet(pick) == null && s.bets.size() >= MAX_FIELDS) return "Możesz mieć najwyżej " + MAX_FIELDS + " pól naraz.";
        return null;
    }

    @Override
    protected void begin() {
        result = eco().randInt(0, 36);
        // pasek: kolejne numery z koła, a na polu docelowym leży wylosowany
        int wheelIdx = 0;
        for (int i = 0; i < WHEEL.length; i++) if (WHEEL[i] == result) wheelIdx = i;
        strip = new int[TARGET + HALF + 2];
        int first = Math.floorMod(wheelIdx - TARGET, WHEEL.length);
        for (int i = 0; i < strip.length; i++) strip[i] = WHEEL[(first + i) % WHEEL.length];
        schedule = StripAnim.schedule(HALF, TARGET);
        lastPos = HALF;
        for (Seat s : seats.values()) {
            long payout = 0;
            for (Bet b : s.bets) {
                Games.RouletteBet f = field(b.pick);
                if (f != null && f.wins(result)) payout += (long) Math.floor(b.stake * f.mult());
            }
            settle(s, payout);
        }
        startRunning();
    }

    @Override
    protected void run() {
        long t = now - phaseStart;
        int pos = schedule.positionAt(t, HALF);
        if (pos != lastPos) {
            lastPos = pos;
            touch();
            float pitch = 1.0f + 0.03f * pos;
            eachViewer(p -> Fx.tick(p, pitch));
        }
        if (t >= schedule.total() + 10) {
            history.add(0, result);
            while (history.size() > HISTORY) history.remove(history.size() - 1);
            finishRound();
        }
    }

    public static String colorName(int n) {
        return switch (Games.rouletteColor(n)) {
            case "red" -> "czerwone";
            case "black" -> "czarne";
            default -> "zielone";
        };
    }

    @Override
    protected void summary(Seat seat) {
        deliver(seat, "Wypadło " + result + " (" + colorName(result) + ").");
    }
}
