package pl.kasyno.show;

import pl.kasyno.games.HeistFrames;
import pl.kasyno.show.Stage.P;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Pokaz napadu w prawdziwym świecie: ekipa jako figury ze skórkami graczy (manekiny) podjeżdża pod bank, wchodzi, przechodzi hol
 * z kamerami, wierci skarbiec, pakuje łup i ucieka, a po drodze ochroniarze zakuwają kolejnych uczestników i wyprowadzają ich do
 * radiowozu. Reżyseruje to ten sam film (HeistFrames), który rysuje okno z mapą, więc jedno i drugie opowiada tę samą historię.
 *
 * <p>To maszyna stanów sterowana tickami (tick() co tick serwera), bez Bukkita: efekty w świecie robi Backend, a testy podstawiają
 * atrapę. Film nie idzie sztywnym czasem: klatka z ruchem czeka, aż ekipa dojdzie na miejsce, dopiero potem trwa jej „postój”.
 */
public final class ShowRunner implements Scene {
    /** Wszystko, co pokaz robi w świecie. Uchwyt postaci jest nieprzejrzysty (null = nie udało się jej zrobić; pokaz idzie dalej). */
    public interface Backend {
        Object spawnCrew(UUID skin, String name, P at, float yaw);

        Object spawnGuard(String name, boolean police, P at, float yaw);

        void teleport(Object handle, P at, float yaw);

        void sneak(Object handle, boolean on);

        void swing(Object handle);

        void remove(Object handle);

        /** Dźwięk sceny w danym miejscu. */
        void sound(HeistFrames.Cue cue, P at);

        /** Efekt cząsteczek: "beam", "sparks", "gold", "cuff", "smoke", "alarm". */
        void fx(String kind, P at);

        /** Otwiera albo zamyka drzwi: "bank" albo "vault". */
        void door(String which, boolean open);

        /** Lampy alarmu. */
        void lamps(boolean lit);
    }

    public static final double WALK = 0.21;
    public static final double RUN = 0.34;
    public static final double ESCORT = 0.17;
    private static final double ARRIVE = 0.45;
    private static final double SPACING = 0.95;

    private enum Phase { INTRO, MOVING, DWELL, ENDING, DONE }

    private static final class Actor {
        Object handle;
        P pos;
        P target;
        double speed = WALK;
        float yaw;
        boolean sneak;
        boolean alive = true;
        boolean escorted;
        boolean boarding;
        int leave = -1;
        boolean removed;
        final String name;

        Actor(String name) {
            this.name = name;
        }

        boolean arrived() {
            return target == null || pos.flat(target) < ARRIVE;
        }
    }

    private final Backend backend;
    private final Stage st;
    private final List<UUID> crew;
    private final List<String> names;
    private final List<HeistFrames.Frame> frames;
    private final IntConsumer onFrame;
    private final Runnable onDone;
    private final List<Actor> crewActors = new ArrayList<>();
    private Actor guard;
    private Actor police;

    private Phase phase = Phase.INTRO;
    private int delay = 40;
    private int fi = -1;
    private int waited;
    private int timeout;
    private int dwell;
    private int endWait;
    private long now;
    private boolean spawned;
    private boolean bankOpen;
    private boolean vaultOpen;
    private boolean lampsLit;
    private boolean beams;
    private boolean drill;
    private double dirZ = 1;
    private P lastWaypoint;
    private boolean aborted;

    public ShowRunner(Backend backend, Stage stage, List<UUID> crew, List<String> names, List<HeistFrames.Frame> frames, IntConsumer onFrame, Runnable onDone) {
        this.backend = backend;
        this.st = stage;
        this.crew = crew;
        this.names = names;
        this.frames = frames;
        this.onFrame = onFrame;
        this.onDone = onDone;
    }

    public boolean done() {
        return phase == Phase.DONE;
    }

    public int frameIndex() {
        return fi;
    }

    // ---------- tick ----------

    /** Jeden tick pokazu. Zwraca true, gdy pokaz się skończył. */
    public boolean tick() {
        if (phase == Phase.DONE) return true;
        now++;
        for (Actor a : crewActors) step(a);
        step(guard);
        step(police);
        ambient();
        switch (phase) {
            case INTRO -> {
                if (--delay <= 0) next();
            }
            case MOVING -> {
                if (movingDone() || ++waited > timeout) {
                    phase = Phase.DWELL;
                    HeistFrames.Frame f = frames.get(fi);
                    dwell = Math.max(3, f.ticks()) * (f.flash() > 0 || f.cue() == HeistFrames.Cue.CUFF || f.cue() == HeistFrames.Cue.COIN ? 2 : 1);
                }
            }
            case DWELL -> {
                if (--dwell <= 0) next();
            }
            case ENDING -> {
                if (allGone() || ++endWait > 900) finish(true);
            }
            default -> {
            }
        }
        return phase == Phase.DONE;
    }

    /** Przerywa pokaz (wyłączenie serwera, komenda): usuwa wszystkie postacie, zamyka drzwi, gasi lampy. */
    public void abort() {
        if (phase == Phase.DONE) return;
        aborted = true;
        finish(false);
    }

    // ---------- klatki ----------

    private void next() {
        fi++;
        if (fi >= frames.size()) {
            startEnding();
            return;
        }
        enter(frames.get(fi));
    }

    private static boolean has(HeistFrames.Frame f, HeistFrames.Prop p) {
        return f.props().containsValue(p);
    }

    private P point(int cell) {
        return switch (cell) {
            case HeistFrames.STREET -> st.get("street");
            case HeistFrames.DOOR -> st.get("door");
            case HeistFrames.HALL2 -> st.get("front");
            case HeistFrames.VAULT -> st.get("safe");
            default -> st.get("hall");
        };
    }

    private void enter(HeistFrames.Frame f) {
        onFrame.accept(fi);
        P tok = f.token() >= 0 ? point(f.token()) : st.get("hall");
        backend.sound(f.cue(), tok);

        if (!bankOpen && has(f, HeistFrames.Prop.DOOR_OPEN)) {
            bankOpen = true;
            backend.door("bank", true);
        }
        if (!vaultOpen && has(f, HeistFrames.Prop.VAULT_OPEN)) {
            vaultOpen = true;
            backend.door("vault", true);
        }
        if (f.token() >= 0 && !spawned) spawnCrew();

        // ekipa idzie za przewodnikiem: jedna kolejka wzdłuż korytarza, a wychodząc z banku biegnie
        if (f.token() >= 0) {
            if (lastWaypoint != null && tok.z() > lastWaypoint.z() + 0.1) dirZ = 1;
            else if (lastWaypoint != null && tok.z() < lastWaypoint.z() - 0.1) dirZ = -1;
            lastWaypoint = tok;
            boolean sneaking = has(f, HeistFrames.Prop.BEAM);
            int k = 0;
            for (Actor a : crewActors) {
                if (!a.alive || a.escorted || a.removed) continue;
                a.target = tok.add(0, 0, -dirZ * SPACING * k++);
                a.speed = dirZ < 0 ? RUN : WALK;
                if (a.sneak != sneaking) {
                    a.sneak = sneaking;
                    backend.sneak(a.handle, sneaking);
                }
            }
        }

        // ochroniarz albo policja podchodzą do ekipy
        boolean police = has(f, HeistFrames.Prop.POLICE);
        if (has(f, HeistFrames.Prop.GUARD) || police) {
            Actor g = ensureGuard(police, tok);
            if (g != null && !g.escorted) {
                P from = st.get(police ? "police" : "guard");
                double dx = from.x() - tok.x();
                double dz = from.z() - tok.z();
                double d = Math.max(1e-6, Math.hypot(dx, dz));
                g.target = new P(tok.x() + dx / d * 1.3, tok.y(), tok.z() + dz / d * 1.3);
                g.speed = RUN;
            }
        }

        // złapani: kajdanki i eskorta do radiowozu
        for (int idx : f.newly()) {
            if (idx < 0 || idx >= crewActors.size()) continue;
            Actor a = crewActors.get(idx);
            a.alive = false;
            a.escorted = true;
            a.target = st.get("jail");
            a.speed = ESCORT;
            if (!a.sneak) {
                a.sneak = true;
                backend.sneak(a.handle, true);
            }
            Actor g = ensureGuard(police || f.token() == HeistFrames.STREET, tok);
            if (g != null) {
                g.escorted = true;
                g.target = st.get("jail");
                g.speed = ESCORT + 0.02;
            }
        }
        if (f.alive() == 0) for (Actor a : crewActors) if (a.alive) a.alive = false;

        // efekty
        if (f.cue() == HeistFrames.Cue.CUFF) backend.fx("cuff", tok);
        if (f.token() >= 0 && f.props().get(f.token()) == HeistFrames.Prop.SPARK) backend.fx("gold", tok);
        if (f.cue() == HeistFrames.Cue.BELL) backend.fx("alarm", st.get("hall"));
        boolean lamps = f.flash() == 1;
        if (lamps != lampsLit) {
            lampsLit = lamps;
            backend.lamps(lamps);
        }
        beams = has(f, HeistFrames.Prop.BEAM);
        drill = has(f, HeistFrames.Prop.VAULT_DRILL) || f.props().get(3) == HeistFrames.Prop.SPARK || f.props().get(5) == HeistFrames.Prop.SPARK;

        phase = Phase.MOVING;
        waited = 0;
        timeout = f.ticks() * 10 + 90;
    }

    private Actor ensureGuard(boolean isPolice, P near) {
        Actor existing = isPolice ? police : guard;
        if (existing != null && !existing.removed) return existing;
        P at = st.get(isPolice ? "police" : "guard");
        if (at == null) at = near;
        Actor a = new Actor(isPolice ? "Policja" : "Ochroniarz");
        a.pos = at;
        a.target = at;
        a.speed = RUN;
        a.handle = backend.spawnGuard(a.name, isPolice, at, 0);
        if (isPolice) police = a;
        else guard = a;
        return a;
    }

    private void spawnCrew() {
        spawned = true;
        P start = st.get("start");
        for (int i = 0; i < crew.size(); i++) {
            Actor a = new Actor(names.get(i));
            a.pos = start.add(0, 0, -0.9 * i);
            a.yaw = 90;
            a.handle = backend.spawnCrew(crew.get(i), names.get(i), a.pos, a.yaw);
            crewActors.add(a);
        }
    }

    private boolean movingDone() {
        for (Actor a : crewActors) if (a.alive && !a.escorted && !a.removed && !a.arrived()) return false;
        return true;
    }

    // ---------- ruch i efekty ----------

    private void step(Actor a) {
        if (a == null || a.removed || a.pos == null) return;
        if (a.target != null) {
            double dx = a.target.x() - a.pos.x();
            double dz = a.target.z() - a.pos.z();
            double d = Math.hypot(dx, dz);
            if (d > 1e-6) {
                double s = Math.min(a.speed, d);
                a.pos = new P(a.pos.x() + dx / d * s, a.pos.y() + (a.target.y() - a.pos.y()) * Math.min(1, s / d), a.pos.z() + dz / d * s);
                a.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                backend.teleport(a.handle, a.pos, a.yaw);
            }
        }
        // eskortowani i odjeżdżający znikają po dojściu do celu
        if ((a.escorted || a.boarding) && a.arrived()) {
            if (a.leave < 0) a.leave = a.boarding ? 6 : 24;
            else if (--a.leave == 0) {
                backend.fx("smoke", a.pos);
                backend.remove(a.handle);
                a.removed = true;
            }
        }
    }

    private void ambient() {
        if (beams && now % 3 == 0) backend.fx("beam", st.get("hall"));
        if (drill && now % 3 == 0) {
            P f = st.get("front");
            backend.fx("sparks", f.add(0, 1.2, 0.9));
            if (now % 9 == 0) for (Actor a : crewActors) if (a.alive && !a.escorted && !a.removed) {
                backend.swing(a.handle);
                break;
            }
        }
    }

    // ---------- koniec ----------

    private void startEnding() {
        phase = Phase.ENDING;
        beams = false;
        drill = false;
        if (lampsLit) {
            lampsLit = false;
            backend.lamps(false);
        }
        int k = 0;
        for (Actor a : crewActors) {
            if (!a.alive || a.escorted || a.removed) continue;
            a.boarding = true;
            a.target = st.get("start").add(0, 0, -0.5 * k++);
            a.speed = RUN;
            if (a.sneak) {
                a.sneak = false;
                backend.sneak(a.handle, false);
            }
        }
        // ewentualny ochroniarz bez eskortowanego: odchodzi
        for (Actor g : new Actor[]{guard, police}) {
            if (g != null && !g.removed && !g.escorted) {
                g.escorted = true;
                g.target = st.get(g == police ? "police" : "guard");
                g.speed = WALK;
            }
        }
    }

    private boolean allGone() {
        for (Actor a : crewActors) if (!a.removed) return false;
        return (guard == null || guard.removed) && (police == null || police.removed);
    }

    private void finish(boolean normal) {
        for (Actor a : crewActors) remove(a);
        remove(guard);
        remove(police);
        if (bankOpen) backend.door("bank", false);
        if (vaultOpen) backend.door("vault", false);
        if (lampsLit) backend.lamps(false);
        phase = Phase.DONE;
        if (normal && !aborted) onDone.run();
    }

    private void remove(Actor a) {
        if (a == null || a.removed) return;
        backend.remove(a.handle);
        a.removed = true;
    }
}
