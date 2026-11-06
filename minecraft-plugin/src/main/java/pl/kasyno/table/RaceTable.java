package pl.kasyno.table;

import org.bukkit.Sound;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Games;
import pl.kasyno.gui.Fx;

import java.util.List;
import java.util.UUID;

/**
 * Wyścigi konne na żywo: pięć koni, każdy obstawia jednego albo kilku (im mniejszy faworyt, tym większa wypłata). Wyścig jest jeden
 * dla całego stołu, a klatki animacji losuje się z góry, żeby wszyscy widzieli to samo.
 */
public final class RaceTable extends Table {
    public static final int HORSES = 5;
    public static final int TRACK = 12;
    private static final int FRAMES = 14;
    private static final int FRAME_TICKS = 6;

    private int winner = -1;
    private int[][] frames = new int[0][];
    private int lastFrame = -1;
    private int lastWinner = -1;

    public RaceTable(KasynoPlugin plugin) {
        super(plugin, "wyscigi", "Wyścigi konne", 15 * 20, 7 * 20);
    }

    public List<Catalog.Horse> horses() {
        return plugin.catalog().games.wyscigi();
    }

    public int winner() {
        return phase == Phase.RESULT ? winner : -1;
    }

    /** Zwycięzca poprzedniego wyścigu (-1, gdy jeszcze nie było). */
    public int lastWinner() {
        return lastWinner;
    }

    /** Pozycje koni na torze (0 do TRACK) w tej chwili. */
    public int[] positions() {
        if ((phase == Phase.RUNNING || phase == Phase.RESULT) && frames.length > 0) {
            int k = phase == Phase.RESULT ? FRAMES - 1 : Math.max(0, lastFrame);
            return frames[Math.min(FRAMES - 1, k)];
        }
        return new int[HORSES];
    }

    @Override
    protected String validate(UUID player, String pick, long stake, double auto) {
        try {
            int h = Integer.parseInt(pick);
            return h >= 0 && h < HORSES ? null : "Nie ma takiego konia.";
        } catch (NumberFormatException e) {
            return "Nie ma takiego konia.";
        }
    }

    @Override
    protected void begin() {
        List<Catalog.Horse> horses = horses();
        winner = horses.indexOf(Games.weighted(horses, Catalog.Horse::p, eco().rng));
        int[] finals = new int[HORSES];
        for (int i = 0; i < HORSES; i++) finals[i] = i == winner ? TRACK : eco().randInt(6, TRACK - 1);
        frames = new int[FRAMES][HORSES];
        for (int k = 0; k < FRAMES; k++) {
            double t = (k + 1) / (double) FRAMES;
            for (int i = 0; i < HORSES; i++) {
                frames[k][i] = k == FRAMES - 1 ? finals[i] : Math.max(0, Math.min(finals[i] - 1, (int) Math.floor(finals[i] * t * (0.75 + eco().rng.nextDouble() * 0.3))));
            }
        }
        lastFrame = -1;
        for (Seat s : seats.values()) {
            long payout = 0;
            for (Bet b : s.bets) if (Integer.parseInt(b.pick) == winner) payout += (long) Math.floor(b.stake * horses.get(winner).mult());
            settle(s, payout);
        }
        startRunning();
    }

    @Override
    protected void run() {
        long t = now - phaseStart;
        int k = (int) Math.min(FRAMES - 1, t / FRAME_TICKS);
        if (k != lastFrame) {
            lastFrame = k;
            touch();
            eachViewer(p -> Fx.sound(p, Sound.ENTITY_HORSE_GALLOP, 0.4f, 1f));
        }
        if (t >= (long) FRAMES * FRAME_TICKS + 10) {
            lastWinner = winner;
            finishRound();
        }
    }

    @Override
    protected void summary(Seat seat) {
        Catalog.Horse h = horses().get(winner);
        deliver(seat, "Wygrywa " + (winner + 1) + ". " + h.name() + "!");
    }
}
