package pl.kasyno.show;

import pl.kasyno.show.ArenaBackend.Hold;
import pl.kasyno.show.ArenaBackend.Sfx;
import pl.kasyno.show.Stage.P;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Losowanie jackpota na arenie: uczestnicy jako figury ze skórkami stają na okręgu wokół środka areny i patrzą do środka, po czym
 * podświetlenie (obrys i słup cząsteczek) krąży po nich klatka po klatce, z tykaniem, które zwalnia, i staje na zwycięzcy. Zwycięzca
 * dostaje fajerwerki, a na końcu wszyscy znikają w dymie. Klatki losowania (kto jest podświetlony) przychodzą z zewnątrz (te same,
 * które odgrywa okno), więc zwycięzca jest zawsze tym, na kim kończy się ostatnia klatka.
 */
public final class JackpotRunner extends ArenaShow {
    private static final int INTRO = 0;
    private static final int WALK = 1;
    private static final int SPIN = 2;
    private static final int WINNER = 3;
    private static final int END = 4;

    /** Okrąg ekipy: odstęp między figurami co najmniej tyle bloków (dla 20 osób promień wychodzi ok. 4). */
    public static final double SPACING = 1.25;
    public static final double MIN_RADIUS = 4;
    /** Ile bloków za okręgiem figury wchodzą na arenę. */
    private static final double ENTRY = 5;

    private final List<UUID> crew;
    private final List<String> names;
    private final int[] spin;
    private final IntConsumer onFrame;
    private int frame = -1;
    private int nextAt;
    private int vanished;
    private List<Fig> order;

    public JackpotRunner(ArenaBackend backend, Stage stage, List<UUID> crew, List<String> names, int[] spin, Consumer<Caption> captions,
                         IntConsumer onFrame, Runnable onDone) {
        super(backend, stage, captions, onDone);
        this.crew = crew;
        this.names = names;
        this.spin = spin;
        this.onFrame = onFrame;
    }

    /** Ile klatek ma losowanie. */
    public static final int FRAMES = 30;

    /** Klatki losowania: indeksy uczestników po kolei wokół okręgu, a ostatnia wypada na zwycięzcy. */
    public static int[] spinFrames(int crewSize, int winnerIdx) {
        int[] frames = new int[FRAMES];
        int start = Math.floorMod(winnerIdx - (FRAMES - 1), crewSize);
        for (int k = 0; k < frames.length; k++) frames[k] = (start + k) % crewSize;
        return frames;
    }

    /** Promień okręgu dla ekipy tej wielkości. */
    public static double radius(int n) {
        return Math.max(MIN_RADIUS, n * SPACING / (2 * Math.PI));
    }

    /** Ile ticków trwa klatka losowania `k` z `len`: z początku co 2 ticki, na końcu co 10 (hamowanie przed zwycięzcą). */
    public static int delayAfter(int k, int len) {
        double q = len <= 1 ? 1 : k / (double) (len - 1);
        return (int) Math.round(2 + 8 * q * q);
    }

    /** Wysokość tonu tykania w klatce `k` z `len`. */
    public static float pitch(int k, int len) {
        float p = 0.8f + 0.8f * (float) Math.sin(Math.PI * Math.min(1, k / (double) len * 1.6));
        return Math.max(0.6f, p);
    }

    private int winnerIdx() {
        return spin[spin.length - 1];
    }

    private P slot(int i, double r) {
        P c = st.get(Stage.ARENA_CENTER);
        double ang = 2 * Math.PI * i / crew.size();
        return new P(c.x() + Math.sin(ang) * r, c.y(), c.z() + Math.cos(ang) * r);
    }

    @Override
    protected void update() {
        P mid = st.get(Stage.ARENA_CENTER);
        switch (phase) {
            case INTRO -> {
                if (t < 40) return;
                double r = radius(crew.size());
                for (int i = 0; i < crew.size(); i++) {
                    P from = slot(i, r + ENTRY);
                    Fig f = spawn(crew.get(i), names.get(i), from, yawTo(from, mid), Hold.GOLD);
                    f.target = slot(i, r);
                }
                say(Kind.INTRO, "Losowanie... komu spadnie cała pula?", 0, null);
                backend.sound(Sfx.TENSION, mid, 0.8f);
                next(WALK);
            }
            case WALK -> {
                if (allArrived() || t > 240) {
                    for (Fig f : figs) face(f, mid);
                    nextAt = 14;
                    next(SPIN);
                }
            }
            case SPIN -> {
                if (t < nextAt) return;
                frame++;
                show(frame);
                if (frame >= spin.length - 1) {
                    next(WINNER);
                    celebrate(0);
                } else nextAt = t + delayAfter(frame, spin.length);
            }
            case WINNER -> {
                // trzy salwy fajerwerków co pół sekundy
                if (t == 10 || t == 20) celebrate(t / 10);
                if (t >= 70) {
                    order = new ArrayList<>();
                    for (int i = 0; i < figs.size(); i++) if (i != winnerIdx()) order.add(figs.get(i));
                    order.add(figs.get(winnerIdx()));
                    next(END);
                }
            }
            case END -> {
                // przegrani znikają po kolei co 3 ticki, zwycięzca na końcu
                if (t % 3 == 0 && vanished < order.size()) vanish(order.get(vanished++));
                if (vanished >= order.size() && t % 3 == 2) finish(true);
            }
            default -> finish(true);
        }
    }

    /** Klatka losowania: obrys i słup cząsteczek przechodzą na wskazaną figurę. */
    private void show(int k) {
        int idx = spin[k];
        if (k > 0) backend.glow(figs.get(spin[k - 1]).handle, false);
        Fig f = figs.get(idx);
        backend.glow(f.handle, true);
        backend.fx("pick", f.pos);
        backend.sound(Sfx.TICK, f.pos, pitch(k, spin.length));
        say(Kind.SPIN, names.get(idx), k, crew.get(idx));
        onFrame.accept(k);
    }

    /** Salwa na zwycięzcy: za pierwszym razem napis i fanfary, potem same fajerwerki z boków. */
    private void celebrate(int round) {
        Fig w = figs.get(winnerIdx());
        if (round == 0) {
            say(Kind.WINNER, names.get(winnerIdx()), spin.length - 1, crew.get(winnerIdx()));
            backend.sound(Sfx.FANFARE, w.pos, 1f);
            backend.fx("gold", w.pos);
            backend.fx("firework", w.pos.add(0, 2, 0));
        } else {
            double side = round % 2 == 0 ? 1.5 : -1.5;
            backend.fx("firework", w.pos.add(side, 2.5, 0));
            backend.sound(Sfx.WIN, w.pos, 1f + 0.1f * round);
        }
    }
}
