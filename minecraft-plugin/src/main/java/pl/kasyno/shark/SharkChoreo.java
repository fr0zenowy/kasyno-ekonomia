package pl.kasyno.shark;

import org.joml.Vector3f;
import pl.kasyno.shark.SharkModel.Orientation;
import pl.kasyno.shark.SharkModel.Pose;

import java.util.random.RandomGenerator;

/**
 * Scenariusz ataku, tick po ticku, bez Bukkita. Rekin najpierw krąży wokół pływaka spiralą (widać tylko płetwę grzbietową), potem
 * rzuca się z głębi, wyskakuje z wody z otwartą paszczą i chwyta gracza, trząsie nim, a na koniec odpływa w głąb. Cel (pozycja gracza)
 * podajemy w każdym kroku; w momencie ataku rekin celuje tam, gdzie gracz jest wtedy.
 */
public final class SharkChoreo {
    public enum Phase { APPROACH, STRIKE, BITE, DIVE, DONE }

    /** Ile ticków trwa rzut, trzęsienie ofiarą i odpłynięcie. */
    public static final int STRIKE_TICKS = 16;
    public static final int BITE_TICKS = 28;
    public static final int DIVE_TICKS = 26;
    /** W którym ticku trzęsienia ofiara ginie. */
    public static final int KILL_TICK = 20;

    /** Obrót wokół osi nosa i pitch w chwili chwytania (nos w górę), w stopniach. */
    private static final double BITE_PITCH = 40;
    private static final double MAX_JAW = 58;
    /** Na jakiej wysokości względem lustra wody pływa korpus w fazie krążenia (płetwa wystaje ponad wodę). */
    static final double CRUISE_DEPTH = 0.55;
    static final double START_RADIUS = 17;
    static final double END_RADIUS = 6;
    /** Wysokość chwytanego punktu ciała nad stopami (pas). */
    static final double TORSO = 0.6;
    /** Prędkość styczna krążenia (bloków na tick), czyli około 7 bloków na sekundę. */
    private static final double CIRCLE_SPEED = 0.34;

    /** Klatka: gdzie stoi rekin (środek korpusu), jak jest obrócony i w jakiej pozie. */
    public record Frame(Phase phase, double x, double y, double z, Orientation orientation, Pose pose) {}

    /** Wynik jednego kroku: klatka i zdarzenia tego ticku (każde następuje dokładnie raz). */
    public record Step(Frame frame, boolean breach, boolean bite, boolean kill, boolean finished) {}

    private final int warningTicks;
    private final double surfaceY;
    private final RandomGenerator rng;

    private Phase phase = Phase.APPROACH;
    private int t;
    private double swim;

    // krążenie
    private double angle;
    private final int dir;
    private double lastX;
    private double lastZ;
    private boolean haveLast;
    private double heading;
    private double x;
    private double z;

    // rzut
    private double sx;
    private double sz;
    private double bx;
    private double by;
    private double bz;
    private double strikeYaw;
    private double px;
    private double pz;

    // chwyt
    private Frame lastFrame;

    public SharkChoreo(int warningTicks, double surfaceY, RandomGenerator rng) {
        this.warningTicks = Math.max(20, warningTicks);
        this.surfaceY = surfaceY;
        this.rng = rng;
        this.angle = rng.nextDouble() * Math.PI * 2;
        this.dir = rng.nextBoolean() ? 1 : -1;
    }

    public Phase phase() {
        return phase;
    }

    /** Ostatnio policzona klatka (null przed pierwszym krokiem). */
    public Frame last() {
        return lastFrame;
    }

    /** Czubek pyska w świecie dla klatki: tam trafia chwytany gracz. */
    public static Vector3f mouth(Frame f) {
        return SharkModel.mouthOffset(f.orientation()).add((float) f.x(), (float) f.y(), (float) f.z());
    }

    /** Jeden tick. (tx, ty, tz) to stopy gracza. */
    public Step step(double tx, double ty, double tz) {
        Step s = switch (phase) {
            case APPROACH -> approach(tx, tz);
            case STRIKE -> strike(tx, ty, tz);
            case BITE -> bite();
            case DIVE -> dive();
            case DONE -> new Step(lastFrame, false, false, false, true);
        };
        lastFrame = s.frame();
        return s;
    }

    /** Przerywa scenariusz (gracz uciekł z wody). */
    public void abort() {
        phase = Phase.DONE;
    }

    // ---------- krążenie ----------

    private Step approach(double tx, double tz) {
        double prog = Math.min(1.0, t / (double) warningTicks);
        double r = START_RADIUS + (END_RADIUS - START_RADIUS) * (prog * prog * (3 - 2 * prog));
        angle += dir * CIRCLE_SPEED / Math.max(r, 3.0);
        x = tx + r * Math.cos(angle);
        z = tz + r * Math.sin(angle);
        if (!haveLast) {
            // styczna do okręgu jako pierwszy kierunek ruchu
            heading = Math.toDegrees(Math.atan2(-Math.sin(angle) * dir, Math.cos(angle) * dir));
            haveLast = true;
        } else if (Math.hypot(x - lastX, z - lastZ) > 1e-6) {
            heading = Math.toDegrees(Math.atan2(x - lastX, z - lastZ));
        }
        lastX = x;
        lastZ = z;
        swim += 0.45;
        Frame f = new Frame(Phase.APPROACH, x, surfaceY - CRUISE_DEPTH, z, new Orientation(heading, 0, 0), new Pose(swim, 16, 0));
        t++;
        if (t >= warningTicks) {
            // od następnego ticku rzut, celowany w miejsce, w którym gracz stoi teraz
            beginStrike(tx, tz);
        }
        return new Step(f, false, false, false, false);
    }

    private void beginStrike(double tx, double tz) {
        phase = Phase.STRIKE;
        t = 0;
        sx = x;
        sz = z;
        px = tx;
        pz = tz;
    }

    // ---------- rzut z głębi ----------

    private Step strike(double tx, double ty, double tz) {
        if (t == 0) {
            // cel to chwila ataku; ostatni znany ruch gracza zostaje uwzględniony przez ciągłe poprawianie punktu docelowego
            strikeYaw = Math.toDegrees(Math.atan2(px - sx, pz - sz));
        }
        // punkt chwytu podąża za graczem, żeby rzut nie chybił, gdy ten zdąży się ruszyć
        px += (tx - px) * 0.35;
        pz += (tz - pz) * 0.35;
        double torsoY = Math.max(ty + TORSO, surfaceY - 0.3);
        Orientation end = new Orientation(strikeYaw, BITE_PITCH, 0);
        Vector3f mouth = SharkModel.mouthOffset(end);
        bx = px - mouth.x;
        bz = pz - mouth.z;
        by = torsoY - mouth.y;
        double u = (t + 1) / (double) STRIKE_TICKS;
        double e = u * u;
        double cy = surfaceY - CRUISE_DEPTH;
        double[] p0 = {sx, cy, sz};
        double[] p3 = {bx, by, bz};
        // punkty kontrolne: najpierw w dół i do przodu, potem łuk w górę pod graczem
        double[] p1 = {sx + (bx - sx) * 0.45, surfaceY - 1.9, sz + (bz - sz) * 0.45};
        double[] p2 = {bx - Math.sin(Math.toRadians(strikeYaw)) * 1.8, by - 0.7, bz - Math.cos(Math.toRadians(strikeYaw)) * 1.8};
        double[] pos = bezier(p0, p1, p2, p3, e);
        double pitch = BITE_PITCH * smooth((u - 0.3) / 0.7);
        double jaw = MAX_JAW * smooth((u - 0.45) / 0.4);
        swim += 0.75;
        boolean breach = t == STRIKE_TICKS / 2;
        t++;
        boolean last = t >= STRIKE_TICKS;
        Frame f = new Frame(Phase.STRIKE, last ? bx : pos[0], last ? by : pos[1], last ? bz : pos[2], new Orientation(strikeYaw, last ? BITE_PITCH : pitch, 0), new Pose(swim, 24, last ? MAX_JAW : jaw));
        if (last) {
            phase = Phase.BITE;
            t = 0;
        }
        return new Step(f, breach, last, false, false);
    }

    // ---------- chwyt i trzęsienie ----------

    private Step bite() {
        double decay = 1 - t / (double) BITE_TICKS;
        double yaw = strikeYaw + 22 * Math.sin(0.85 * t) * decay;
        double roll = 16 * Math.sin(1.1 * t) * decay;
        double pitch = BITE_PITCH - 0.9 * t;
        double sink = 0.045 * t;
        double jitter = 0.1 * Math.sin(2.3 * t) * decay;
        // szczęka zatrzaskuje się w trzech tickach, potem żuje
        double jaw = t < 3 ? MAX_JAW + (6 - MAX_JAW) * (t + 1) / 3.0 : 6 + 4 * Math.sin(1.5 * t);
        swim += 0.9;
        Frame f = new Frame(Phase.BITE, bx, by - sink + jitter, bz, new Orientation(yaw, pitch, roll), new Pose(swim, 28, jaw));
        boolean kill = t == KILL_TICK;
        t++;
        if (t >= BITE_TICKS) {
            phase = Phase.DIVE;
            t = 0;
        }
        return new Step(f, false, false, kill, false);
    }

    // ---------- odpłynięcie ----------

    private Step dive() {
        double fx = Math.sin(Math.toRadians(strikeYaw));
        double fz = Math.cos(Math.toRadians(strikeYaw));
        double k = t + 1;
        double startY = by - 0.045 * BITE_TICKS;
        double pitch = (BITE_PITCH - 0.9 * BITE_TICKS) - 1.4 * k;
        swim += 0.55;
        Frame f = new Frame(Phase.DIVE, bx + fx * 0.35 * k, startY - 0.1 * k, bz + fz * 0.35 * k, new Orientation(strikeYaw, Math.max(pitch, -35), 0), new Pose(swim, 30, 0));
        t++;
        boolean done = t >= DIVE_TICKS;
        if (done) phase = Phase.DONE;
        return new Step(f, false, false, false, done);
    }

    // ---------- pomocnicze ----------

    static double smooth(double v) {
        double c = Math.max(0, Math.min(1, v));
        return c * c * (3 - 2 * c);
    }

    private static double[] bezier(double[] a, double[] b, double[] c, double[] d, double u) {
        double v = 1 - u;
        double w0 = v * v * v;
        double w1 = 3 * v * v * u;
        double w2 = 3 * v * u * u;
        double w3 = u * u * u;
        return new double[]{a[0] * w0 + b[0] * w1 + c[0] * w2 + d[0] * w3, a[1] * w0 + b[1] * w1 + c[1] * w2 + d[1] * w3, a[2] * w0 + b[2] * w1 + c[2] * w2 + d[2] * w3};
    }

    /** Pokaz modelu bez ataku: rekin płynie po okręgu o promieniu `radius` wokół punktu, co 60 ticków otwiera i zamyka paszczę. */
    public static Frame demo(int tick, double cx, double cy, double cz, double radius) {
        double a = tick * (CIRCLE_SPEED * 0.8 / radius);
        double x = cx + radius * Math.cos(a);
        double z = cz + radius * Math.sin(a);
        double yaw = Math.toDegrees(Math.atan2(-Math.sin(a), Math.cos(a)));
        int cycle = tick % 60;
        double jaw = cycle < 30 ? 0 : MAX_JAW * Math.sin(Math.PI * (cycle - 30) / 30.0);
        return new Frame(Phase.APPROACH, x, cy, z, new Orientation(yaw, 0, 0), new Pose(tick * 0.45, 18, jaw));
    }
}
