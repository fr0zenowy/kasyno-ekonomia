package pl.sport.volley;

import pl.sport.core.Ballistics;
import pl.sport.core.BallSpec;
import pl.sport.core.V3;
import pl.sport.volley.VolleyGame.HitType;

import java.util.random.RandomGenerator;

/**
 * Model uderzenia piłki siatkowej. Gracz nie steruje prędkością piłki wprost: patrzy tam, gdzie chce ją posłać (punkt na podłodze
 * po stronie rywala albo kolega, któremu wystawia), a model dobiera prędkość i kąt tak, żeby piłka przeszła nad siatką i doleciała
 * w to miejsce, z oporem powietrza włącznie. Jakość uderzenia (jak blisko ciała i w jakim momencie piłka została złapana) dokłada
 * losowy błąd, więc dobre ustawienie się do piłki popłaca, a spóźnione odbicie ląduje obok.
 */
public final class HitModel {
    private HitModel() {}

    /** Dane uderzenia, w układzie lokalnym boiska. `passTarget` to kolega, do którego gracz celuje (może być null). */
    public record Request(HitType type, V3 ballPos, V3 ballVel, V3 eye, V3 look, boolean ownSideLow, V3 passTarget, double quality) {}

    public record Hit(V3 velocity, HitType type, double quality, boolean aimedAtTarget) {}

    /** Domyślny zasięg, gdy gracz nie patrzy w podłogę. */
    static double defaultRange(HitType t) {
        return switch (t) {
            case SERVE -> 13;
            case SPIKE -> 9;
            case BUMP -> 6.5;
            case SET -> 4;
            case BLOCK -> 3;
        };
    }

    static double[] rangeLimits(HitType t) {
        return switch (t) {
            case SERVE -> new double[]{7, 20};
            case SPIKE -> new double[]{3, 15};
            case BUMP -> new double[]{1.5, 14};
            case SET -> new double[]{1.5, 9};
            case BLOCK -> new double[]{1, 6};
        };
    }

    /** O ile metrów za siatką ląduje piłka, gdy gracz patrzy w stronę rywali bez wskazania miejsca na podłodze. */
    static double overTheNet(HitType t) {
        return switch (t) {
            case SERVE -> 4;
            case SPIKE -> 4;
            case BUMP -> 5;
            case SET -> 3.5;
            case BLOCK -> 2;
        };
    }

    /** Kąt kąt startu dla podań na własnej połowie (stopnie) i największa siła. */
    static double passAngle(HitType t) {
        return t == HitType.SET ? 63 : 52;
    }

    /** Punkt, w który gracz posyła piłkę (środek piłki w chwili dolotu). */
    static V3 aimPoint(VolleyCourt court, BallSpec spec, Request r) {
        V3 look = r.look().normalized();
        V3 h = look.horizontal().normalized();
        if (h.lengthSq() < 1e-9) h = new V3(r.ownSideLow() ? 1 : -1, 0, 0);
        if (r.passTarget() != null && (r.type() == HitType.BUMP || r.type() == HitType.SET)) {
            double y = r.type() == HitType.SET ? 2.5 : 1.9;
            return new V3(r.passTarget().x(), y, r.passTarget().z());
        }
        double[] lim = rangeLimits(r.type());
        double pitchDown = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -look.y()))));
        double base = defaultRange(r.type());
        // gracz patrzy w stronę siatki, ale nie w podłogę: posyłamy piłkę za siatkę, żeby samo „patrz na rywali” wystarczyło
        double toNet = court.netU() - r.ballPos().x();
        if (Math.abs(h.x()) > 0.2 && toNet * h.x() > 0) {
            double tNet = toNet / h.x();
            if (tNet < 18) base = Math.max(base, tNet + overTheNet(r.type()));
        }
        double dist = pitchDown >= 7
                ? (r.eye().y() - spec.radius()) / Math.tan(Math.toRadians(pitchDown))
                : base;
        dist = Math.max(lim[0], Math.min(lim[1], dist));
        double y = (r.type() == HitType.SERVE || r.type() == HitType.SPIKE) ? spec.radius() : 1.2;
        return new V3(r.ballPos().x() + h.x() * dist, y, r.ballPos().z() + h.z() * dist);
    }

    /** Oblicza prędkość piłki po uderzeniu. */
    public static Hit compute(VolleyCourt court, BallSpec spec, Request r, RandomGenerator rng) {
        if (r.type() == HitType.BLOCK) return block(court, spec, r, rng);

        V3 target = aimPoint(court, spec, r);
        V3 from = r.ballPos();
        V3 delta = target.minus(from);
        double dist = delta.horizontalLength();
        V3 dir = dist < 1e-6 ? new V3(r.ownSideLow() ? 1 : -1, 0, 0) : delta.horizontal().normalized();
        boolean aimedAtTarget = r.passTarget() != null && (r.type() == HitType.BUMP || r.type() == HitType.SET);

        Ballistics.Aim aim;
        double q = Math.max(0, Math.min(1, r.quality()));
        // piłka musi przejść nad siatką, jeśli cel jest po drugiej stronie (dotyczy każdego uderzenia, nie tylko zagrywki i ataku)
        double netU = court.netU();
        boolean crosses = (from.x() - netU) * (target.x() - netU) < 0;
        boolean power = r.type() == HitType.SERVE || r.type() == HitType.SPIKE;
        double minSpeed = r.type() == HitType.SERVE ? 10 : r.type() == HitType.SPIKE ? 13 : 5.5;
        double maxSpeed = switch (r.type()) {
            case SERVE -> 12 + 12 * q;
            case SPIKE -> 14 + 12 * q;
            default -> 15;
        };
        if (crosses) {
            double netDist = dist * Math.abs(netU - from.x()) / Math.max(1e-6, Math.abs(target.x() - from.x()));
            aim = Ballistics.solveClearing(spec, from.y(), dist, target.y(), netDist, court.netHeight() + spec.radius() + 0.15, minSpeed, maxSpeed);
        } else if (power) {
            aim = solvePass(spec, from.y(), dist, target.y(), 40, maxSpeed);
        } else {
            // wystawienie albo odbicie do siebie (cel tuż obok): piłka idzie niemal pionowo w górę i wraca na cel z góry
            double angle = aimedAtTarget && dist < 1.6 ? Ballistics.MAX_ANGLE : passAngle(r.type());
            aim = solvePass(spec, from.y(), dist, target.y(), angle, 15);
        }

        // błąd zależy od jakości uderzenia: idealne trafienie to niemal brak błędu, spóźnione odbicie wyraźnie ucieka
        double err = 0.12 + (1 - q) * 1.15;
        double speed = aim.speed() * (1 + rng.nextGaussian() * 0.02 * err);
        double yaw = Math.toRadians(rng.nextGaussian() * 2.4 * err);
        double angle = aim.angleDeg() + rng.nextGaussian() * 0.9 * err;
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        V3 d = new V3(dir.x() * cos - dir.z() * sin, 0, dir.x() * sin + dir.z() * cos);
        V3 v = new Ballistics.Aim(speed, angle, aim.exact()).velocity(d);
        return new Hit(v, r.type(), q, aimedAtTarget);
    }

    /** Podanie na własnej połowie albo zagrywka w obrębie jednej strony: stały kąt, prędkość dobrana do celu. */
    private static Ballistics.Aim solvePass(BallSpec spec, double h0, double dist, double targetY, double angle, double maxSpeed) {
        Ballistics.Aim a = Ballistics.solveSpeed(spec, h0, dist, targetY, angle, maxSpeed);
        if (a != null) return a;
        return new Ballistics.Aim(maxSpeed, angle, false);
    }

    /** Blok: piłka odbija się w stronę, z której przyleciała, i w dół. */
    private static Hit block(VolleyCourt court, BallSpec spec, Request r, RandomGenerator rng) {
        V3 incoming = r.ballVel().horizontal();
        V3 back = incoming.lengthSq() < 1e-6 ? new V3(r.ownSideLow() ? 1 : -1, 0, 0) : incoming.times(-1).normalized();
        double q = Math.max(0, Math.min(1, r.quality()));
        double speed = 7 + 4 * q;
        double vy = -1.5 - 2.5 * q + rng.nextGaussian() * 0.4;
        double yaw = Math.toRadians(rng.nextGaussian() * 4);
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        V3 d = new V3(back.x() * cos - back.z() * sin, 0, back.x() * sin + back.z() * cos);
        return new Hit(new V3(d.x() * speed, vy, d.z() * speed), HitType.BLOCK, q, false);
    }
}
