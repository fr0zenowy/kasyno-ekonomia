package pl.kasyno.show;

import pl.kasyno.show.Stage.P;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Wspólna baza pokazów areny: figury ze skórkami graczy, ruch po prostej (teleport co tick o krok), obracanie twarzą do celu,
 * znikanie w dymie i sprzątanie. To maszyna stanów sterowana tickami, bez Bukkita: efekty w świecie robi ArenaBackend, a napisy
 * (odliczanie, wynik) wychodzą jako Caption do kogoś, kto wie, komu je pokazać. Pokaz nie zna pieniędzy: wynik dostaje gotowy.
 */
public abstract class ArenaShow implements Scene {
    /** Rodzaj napisu sceny (styl i dźwięk dobiera ten, kto go wyświetla). */
    public enum Kind { INTRO, STAND, TENSION, COUNT, WAIT, FIRE, RESULT, SPIN, WINNER }

    /** Napis sceny: `text` to zwykły tekst po polsku, `n` numer odliczania albo klatki, `who` bohater napisu (zwycięzca, wskazany gracz). */
    public record Caption(Kind kind, String text, int n, UUID who) {}

    public static final double WALK = 0.21;
    /** Twardy limit czasu pokazu (ticki): żadna scena nie może wisieć w nieskończoność. */
    public static final int MAX_TICKS = 20 * 60;

    /** Figura na arenie. */
    protected static final class Fig {
        final UUID id;
        final String name;
        Object handle;
        P pos;
        P target;
        double speed = WALK;
        float yaw;
        boolean removed;
        Fig(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        boolean arrived() {
            return target == null || pos.flat(target) < 0.05;
        }
    }

    protected final ArenaBackend backend;
    protected final Stage st;
    private final Consumer<Caption> captions;
    private final Runnable onDone;
    protected final List<Fig> figs = new ArrayList<>();

    /** Ticki od początku pokazu i od początku bieżącej fazy. */
    protected int total;
    protected int t;
    protected int phase;
    private boolean done;
    private boolean aborted;

    protected ArenaShow(ArenaBackend backend, Stage stage, Consumer<Caption> captions, Runnable onDone) {
        this.backend = backend;
        this.st = stage;
        this.captions = captions;
        this.onDone = onDone;
    }

    @Override
    public final boolean done() {
        return done;
    }

    public final boolean aborted() {
        return aborted;
    }

    /** Logika fazy; woływana co tick, dopóki pokaz trwa. */
    protected abstract void update();

    @Override
    public final boolean tick() {
        if (done) return true;
        total++;
        t++;
        moveAll();
        update();
        if (!done && total > MAX_TICKS) finish(true);
        return done;
    }

    @Override
    public final void abort() {
        if (done) return;
        aborted = true;
        finish(false);
    }

    // ---------- fazy i napisy ----------

    protected final void next(int newPhase) {
        phase = newPhase;
        t = 0;
    }

    protected final void say(Kind kind, String text, int n, UUID who) {
        captions.accept(new Caption(kind, text, n, who));
    }

    // ---------- figury ----------

    protected final Fig spawn(UUID id, String name, P at, float yaw, ArenaBackend.Hold hold) {
        Fig f = new Fig(id, name);
        f.pos = at;
        f.yaw = yaw;
        f.handle = backend.spawn(id, name, at, yaw, hold);
        figs.add(f);
        return f;
    }

    protected static float yawTo(P from, P to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x() - from.x()), to.z() - from.z()));
    }

    /** Obraca figurę twarzą do punktu (bez ruchu). */
    protected final void face(Fig f, P toward) {
        if (f.removed) return;
        f.yaw = yawTo(f.pos, toward);
        backend.teleport(f.handle, f.pos, f.yaw);
    }

    protected final boolean allArrived() {
        for (Fig f : figs) if (!f.removed && !f.arrived()) return false;
        return true;
    }

    private void moveAll() {
        boolean stepped = false;
        for (Fig f : figs) {
            if (f.removed || f.target == null) continue;
            double dx = f.target.x() - f.pos.x();
            double dz = f.target.z() - f.pos.z();
            double d = Math.hypot(dx, dz);
            if (d <= 1e-6) continue;
            double s = Math.min(f.speed, d);
            f.pos = new P(f.pos.x() + dx / d * s, f.pos.y() + (f.target.y() - f.pos.y()) * Math.min(1, s / d), f.pos.z() + dz / d * s);
            f.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            backend.teleport(f.handle, f.pos, f.yaw);
            // kroki słychać raz na kilka ticków, bez względu na liczbę figur
            if (!stepped && total % 7 == 0) {
                stepped = true;
                backend.sound(ArenaBackend.Sfx.STEP, f.pos, 1f);
            }
        }
    }

    /** Figura znika w kłębie dymu. */
    protected final void vanish(Fig f) {
        if (f.removed) return;
        backend.fx("smoke", f.pos);
        backend.remove(f.handle);
        f.removed = true;
    }

    protected final boolean allGone() {
        for (Fig f : figs) if (!f.removed) return false;
        return true;
    }

    /** Kończy pokaz: usuwa wszystkie figury, które jeszcze stoją, i (przy normalnym końcu) woła zakończenie dokładnie raz. */
    protected final void finish(boolean normal) {
        if (done) return;
        for (Fig f : figs) {
            if (f.removed) continue;
            backend.remove(f.handle);
            f.removed = true;
        }
        done = true;
        if (normal && !aborted) onDone.run();
    }
}
