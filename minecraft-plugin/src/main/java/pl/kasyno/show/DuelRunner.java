package pl.kasyno.show;

import pl.kasyno.show.ArenaBackend.Hold;
import pl.kasyno.show.ArenaBackend.Pose;
import pl.kasyno.show.ArenaBackend.Sfx;
import pl.kasyno.show.Stage.P;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Pojedynek na arenie: dwie figury ze skórkami obu graczy wchodzą na swoje stanowiska i stają naprzeciw siebie, napięcie (przycupnięte
 * dłonie przy kaburach), odliczanie 3-2-1 jako napisy, losowa chwila ciszy, STRZAŁ i jeden z nich pada (poza leżąca), a na końcu obaj
 * znikają w dymie. Zwycięzcę zna z góry (wynik jest już rozliczony), a pokaz tylko go odgrywa.
 */
public final class DuelRunner extends ArenaShow {
    private static final int INTRO = 0;
    private static final int WALK = 1;
    private static final int STAND = 2;
    private static final int TENSION = 3;
    private static final int COUNT = 4;
    private static final int WAIT = 5;
    private static final int FIRE = 6;
    private static final int FALL = 7;
    private static final int END = 8;

    /** Skąd figury wchodzą: tyle bloków za stanowiskiem, licząc od środka areny. */
    private static final double ENTRY = 6;

    private final UUID winner;
    private final int waitTicks;
    private final String nameA;
    private final String nameB;
    private final UUID idA;
    private final UUID idB;
    private final boolean aWins;
    private Fig a;
    private Fig b;
    private int count;

    public DuelRunner(ArenaBackend backend, Stage stage, UUID idA, String nameA, UUID idB, String nameB, UUID winner, int waitTicks,
                      Consumer<Caption> captions, Runnable onDone) {
        super(backend, stage, captions, onDone);
        this.idA = idA;
        this.idB = idB;
        this.nameA = nameA;
        this.nameB = nameB;
        this.winner = winner;
        this.aWins = !winner.equals(idB);
        this.waitTicks = Math.max(0, waitTicks);
    }

    private Fig winnerFig() {
        return aWins ? a : b;
    }

    private Fig loserFig() {
        return aWins ? b : a;
    }

    /** Punkt wejścia figury: na przedłużeniu prostej od środka areny przez jej stanowisko. */
    private P entry(P mark) {
        P c = st.get(Stage.ARENA_CENTER);
        double dx = mark.x() - c.x();
        double dz = mark.z() - c.z();
        double d = Math.hypot(dx, dz);
        if (d < 1e-6) {
            dx = mark.equals(st.get(Stage.ARENA_A)) ? -1 : 1;
            dz = 0;
            d = 1;
        }
        return new P(mark.x() + dx / d * ENTRY, mark.y(), mark.z() + dz / d * ENTRY);
    }

    @Override
    protected void update() {
        P pa = st.get(Stage.ARENA_A);
        P pb = st.get(Stage.ARENA_B);
        P mid = st.get(Stage.ARENA_CENTER);
        switch (phase) {
            case INTRO -> {
                // chwila na dojście widzów, potem obaj wchodzą na arenę
                if (t < 40) return;
                P ea = entry(pa);
                P eb = entry(pb);
                a = spawn(idA, nameA, ea, yawTo(ea, pa), Hold.GUN);
                b = spawn(idB, nameB, eb, yawTo(eb, pb), Hold.GUN);
                a.target = pa;
                b.target = pb;
                say(Kind.STAND, "Stajecie naprzeciw siebie na pustej ulicy...", 0, null);
                backend.sound(Sfx.TENSION, mid, 0.7f);
                next(WALK);
            }
            case WALK -> {
                if (allArrived() || t > 240) {
                    face(a, pb);
                    face(b, pa);
                    next(STAND);
                }
            }
            case STAND -> {
                if (t < 24) return;
                say(Kind.TENSION, "Dłonie przy kaburach. Nikt nie mruga...", 0, null);
                backend.sound(Sfx.TENSION, mid, 0.5f);
                backend.pose(a.handle, Pose.SNEAK);
                backend.pose(b.handle, Pose.SNEAK);
                backend.fx("dust", pa);
                backend.fx("dust", pb);
                next(TENSION);
            }
            case TENSION -> {
                if (t < 32) return;
                count = 4;
                next(COUNT);
                nextCount(mid);
            }
            case COUNT -> {
                if (t < 18) return;
                if (count > 1) nextCount(mid);
                else {
                    say(Kind.WAIT, "...", 0, null);
                    next(WAIT);
                }
            }
            case WAIT -> {
                if (t >= waitTicks) fire(mid, pa, pb);
            }
            case FIRE -> {
                // chwilę po strzale przegrany pada, zwycięzca zostaje na nogach
                if (t < 18) return;
                backend.pose(loserFig().handle, Pose.LIE);
                backend.sound(Sfx.FALL, loserFig().pos, 1f);
                say(Kind.RESULT, aWins ? nameA : nameB, 0, winner);
                backend.sound(Sfx.WIN, winnerFig().pos, 1f);
                backend.fx("gold", winnerFig().pos);
                next(FALL);
            }
            case FALL -> {
                if (t >= 60) next(END);
            }
            case END -> {
                // przegrany znika pierwszy, po chwili zwycięzca
                if (t == 1) vanish(loserFig());
                if (t == 13) vanish(winnerFig());
                if (t >= 15) finish(true);
            }
            default -> finish(true);
        }
    }

    private void nextCount(P mid) {
        count--;
        t = 0;
        say(Kind.COUNT, String.valueOf(count), count, null);
        backend.sound(Sfx.COUNT, mid, 0.7f + 0.2f * (3 - count));
    }

    private void fire(P mid, P pa, P pb) {
        say(Kind.FIRE, "STRZAŁ!", 0, null);
        backend.pose(a.handle, Pose.STAND);
        backend.pose(b.handle, Pose.STAND);
        backend.swing(a.handle);
        backend.swing(b.handle);
        backend.sound(Sfx.SHOT, mid, 1f);
        backend.fx("shot", pa);
        backend.fx("shot", pb);
        backend.fx("hit", loserFig().pos);
        backend.sound(Sfx.HIT, loserFig().pos, 1f);
        next(FIRE);
    }
}
