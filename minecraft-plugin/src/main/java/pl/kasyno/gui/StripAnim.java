package pl.kasyno.gui;

import java.util.function.IntConsumer;

/**
 * Animacja paska w stylu CS:GO: pasek „dojeżdża” do pola docelowego, na początku szybko, pod koniec zwalnia.
 * show(pos) rysuje klatkę dla pozycji środka paska, done kończy animację (pasek stoi na polu docelowym).
 */
public final class StripAnim {
    private static final double[] STEPS = {0.14, 0.33, 0.5, 0.63, 0.75, 0.84, 0.91, 0.96};
    private static final int[] DELAYS = {6, 6, 7, 8, 10, 12, 15, 18};

    private StripAnim() {}

    /** Harmonogram paska: times[i] to tick (od startu), w którym środek paska staje na pozycji positions[i]. Ostatni wpis to pole docelowe. */
    public record Schedule(int[] times, int[] positions) {
        public int total() {
            return times[times.length - 1];
        }

        /** Pozycja środka paska po `elapsed` tickach od startu. */
        public int positionAt(long elapsed, int half) {
            int pos = half;
            for (int i = 0; i < times.length; i++) if (elapsed >= times[i]) pos = positions[i];
            return pos;
        }
    }

    /** Ten sam rytm co run(), ale jako dane: stół odtwarza go dla wszystkich graczy naraz. */
    public static Schedule schedule(int half, int target) {
        java.util.List<int[]> frames = new java.util.ArrayList<>();
        int t = 3;
        int last = half;
        for (int k = 0; k < STEPS.length; k++) {
            int pos = (int) Math.round(half + (target - half) * STEPS[k]);
            if (pos > last && pos < target) {
                last = pos;
                frames.add(new int[]{t, pos});
                t += DELAYS[k];
            }
        }
        frames.add(new int[]{t, target});
        return new Schedule(frames.stream().mapToInt(f -> f[0]).toArray(), frames.stream().mapToInt(f -> f[1]).toArray());
    }

    public static void run(Gui gui, int half, int target, IntConsumer show, Runnable done) {
        int[] k = {0};
        int[] last = {half};
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            while (k[0] < STEPS.length) {
                int pos = (int) Math.round(half + (target - half) * STEPS[k[0]]);
                int delay = DELAYS[k[0]];
                k[0]++;
                if (pos > last[0] && pos < target) {
                    last[0] = pos;
                    show.accept(pos);
                    gui.later(delay, step[0]);
                    return;
                }
            }
            show.accept(target);
            done.run();
        };
        gui.later(3, step[0]);
    }
}
