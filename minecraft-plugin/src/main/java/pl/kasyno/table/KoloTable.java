package pl.kasyno.table;

import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Games;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.StripAnim;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Koło fortuny na żywo: jeden obrót dla całego stołu, a mnożnik wylosowanego segmentu dotyczy wszystkich zakładów (od x0 do x200). */
public final class KoloTable extends Table {
    public static final String PICK = "kolo";
    public static final int HALF = 4;
    public static final int TARGET = 20;
    public static final int HISTORY = 9;

    private int[] strip = new int[0];
    private int winner = -1;
    private StripAnim.Schedule schedule;
    private int lastPos = HALF;
    private final List<Integer> history = new ArrayList<>();

    public KoloTable(KasynoPlugin plugin) {
        super(plugin, "kolo", "Koło fortuny", 15 * 20, 7 * 20);
    }

    public List<Catalog.WheelSegment> segments() {
        return plugin.catalog().games.kolo();
    }

    /** Indeksy segmentów ostatnich obrotów (najnowszy pierwszy). */
    public List<Integer> history() {
        return history;
    }

    public int winner() {
        return phase == Phase.RESULT ? winner : -1;
    }

    /** Dziewięć segmentów widocznych na pasku. */
    public int[] cells() {
        int[] out = new int[9];
        if ((phase == Phase.RUNNING || phase == Phase.RESULT) && strip.length > 0) {
            int pos = phase == Phase.RESULT ? TARGET : lastPos;
            for (int i = 0; i < 9; i++) out[i] = strip[Math.min(strip.length - 1, pos - HALF + i)];
            return out;
        }
        for (int i = 0; i < 9; i++) out[i] = i % segments().size();
        return out;
    }

    @Override
    protected String validate(UUID player, String pick, long stake, double auto) {
        return PICK.equals(pick) ? null : "Nie ma takiego zakładu.";
    }

    @Override
    protected void begin() {
        List<Catalog.WheelSegment> segs = segments();
        winner = segs.indexOf(Games.weighted(segs, Catalog.WheelSegment::w, eco().rng));
        strip = new int[TARGET + HALF + 2];
        for (int i = 0; i < strip.length; i++) strip[i] = segs.indexOf(Games.weighted(segs, Catalog.WheelSegment::w, eco().rng));
        strip[TARGET] = winner;
        schedule = StripAnim.schedule(HALF, TARGET);
        lastPos = HALF;
        double mult = segs.get(winner).mult();
        for (Seat s : seats.values()) settle(s, (long) Math.floor(s.stake() * mult));
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
            history.add(0, winner);
            while (history.size() > HISTORY) history.remove(history.size() - 1);
            finishRound();
        }
    }

    @Override
    protected void summary(Seat seat) {
        Catalog.WheelSegment seg = segments().get(winner);
        deliver(seat, "Wypadło: " + seg.label() + " (" + Fmt.mult(seg.mult()) + ").");
    }
}
