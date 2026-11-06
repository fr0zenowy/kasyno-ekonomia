package pl.sport.pingpong;

import pl.sport.core.BallBody;
import pl.sport.core.BallSpec;
import pl.sport.core.Ballistics;
import pl.sport.core.Contact;
import pl.sport.core.PhysicsWorld;
import pl.sport.core.V3;

import java.util.random.RandomGenerator;

/**
 * Model uderzenia piłeczki. Gracz nie steruje prędkością piłeczki wprost: patrzy tam, gdzie chce ją posłać (punkt na blacie po stronie
 * rywala albo po prostu w jego stronę), a model dobiera prędkość i kąt tak, żeby piłeczka przeszła nad siatką i spadła w to miejsce, z oporem
 * powietrza włącznie. Jakość uderzenia (jak blisko idealnego punktu kontaktu była piłeczka) dokłada losowy błąd, więc spóźnione odbicie
 * ląduje obok. Serwis ma dodatkowy warunek, że piłeczka musi najpierw odbić się od własnej połowy, więc tor serwisu jest wyszukiwany symulacją.
 */
public final class PingPongHitModel {
    private PingPongHitModel() {}

    public enum Stroke {
        SERVE("Serwis"),
        /** Zwykłe odbicie: szybkie i dość płaskie. */
        DRIVE("Odbicie"),
        /** Podcięcie (Shift): wolna, wysoka piłeczka wpadająca krótko. */
        PUSH("Podcięcie"),
        /** Smecz: bardzo szybkie uderzenie z góry, gdy piłeczka jest wysoko nad siatką. */
        SMASH("Smecz");

        private final String label;

        Stroke(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Dane uderzenia w układzie lokalnym hali. `accuracy` to mnożnik celności z ustawień (1 = normalnie). */
    public record Request(Stroke stroke, V3 ballPos, V3 eye, V3 look, boolean ownLow, double quality, double accuracy) {}

    public record Hit(V3 velocity, Stroke stroke, V3 aim, boolean exact) {}

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Jak głęboko za siatką (jako ułamek połowy stołu) ląduje piłeczka, gdy gracz patrzy w stronę rywala, ale nie w blat. */
    private static double defaultDepth(Stroke s) {
        return switch (s) {
            case SERVE -> 0.6;
            case DRIVE -> 0.55;
            case PUSH -> 0.4;
            case SMASH -> 0.7;
        };
    }

    /** Punkt na blacie (środek piłeczki w chwili odbicia), w który gracz posyła piłeczkę. */
    public static V3 aimPoint(PingPongTable t, BallSpec spec, Request r) {
        V3 look = r.look().normalized();
        double toward = r.ownLow() ? 1 : -1;
        double half = t.tableLength() / 2;
        double y = t.topY() + spec.radius();
        double margin = 0.12;
        double uLo = r.ownLow() ? t.netU() + 0.15 : t.u0() + margin;
        double uHi = r.ownLow() ? t.u1() - margin : t.netU() - 0.15;
        if (look.y() < -0.05) {
            // gracz patrzy w blat: bierzemy punkt, w który trafia jego wzrok
            double s = (r.eye().y() - t.topY()) / -look.y();
            if (s > 0 && s < 14) {
                V3 p = r.eye().plus(look.times(s));
                boolean onOpponentHalf = (p.x() - t.netU()) * toward > 0.15 && p.x() >= t.u0() && p.x() <= t.u1() && p.z() >= t.v0() && p.z() <= t.v1();
                if (onOpponentHalf) return new V3(clamp(p.x(), uLo, uHi), y, clamp(p.z(), t.v0() + margin, t.v1() - margin));
            }
        }
        V3 lh = look.horizontal().normalized();
        if (lh.lengthSq() < 1e-9 || lh.x() * toward < 0.2) lh = new V3(toward, 0, 0);
        double targetU = t.netU() + toward * defaultDepth(r.stroke()) * half;
        double k = (targetU - r.ballPos().x()) / lh.x();
        double targetV = r.ballPos().z() + lh.z() * k;
        return new V3(clamp(targetU, uLo, uHi), y, clamp(targetV, t.v0() + margin, t.v1() - margin));
    }

    /** Oblicza prędkość piłeczki po uderzeniu. */
    public static Hit compute(PingPongTable t, BallSpec spec, Request r, RandomGenerator rng) {
        if (r.stroke() == Stroke.SERVE) return serve(t, spec, r, rng);
        V3 target = aimPoint(t, spec, r);
        V3 from = r.ballPos();
        V3 delta = target.minus(from);
        double dist = delta.horizontalLength();
        double toward = r.ownLow() ? 1 : -1;
        V3 dir = dist < 1e-6 ? new V3(toward, 0, 0) : delta.horizontal().normalized();
        double netDist = dist * Math.abs(t.netU() - from.x()) / Math.max(1e-6, Math.abs(target.x() - from.x()));
        double margin = switch (r.stroke()) {
            case PUSH -> 0.12;
            case SMASH -> 0.03;
            default -> 0.05;
        };
        double clearY = t.netTopY() + spec.radius() + margin;
        Ballistics.Aim aim = switch (r.stroke()) {
            case PUSH -> lob(spec, from.y(), dist, target.y(), netDist, clearY);
            case SMASH -> Ballistics.solveClearing(spec, from.y(), dist, target.y(), netDist, clearY, 9, 15);
            default -> Ballistics.solveClearing(spec, from.y(), dist, target.y(), netDist, clearY, 4.5, 11);
        };

        double err = 1 - clamp(r.quality(), 0, 1);
        double acc = Math.max(0.3, r.accuracy());
        double power = r.stroke() == Stroke.SMASH ? 1.5 : 1.0;
        double speed = aim.speed() * (1 + rng.nextGaussian() * (0.012 + 0.11 * err) * power / acc);
        double angle = aim.angleDeg() + rng.nextGaussian() * (0.3 + 2.6 * err) * power / acc;
        double yaw = Math.toRadians(rng.nextGaussian() * (0.8 + 6.0 * err) * power / acc);
        return new Hit(new Ballistics.Aim(speed, angle, aim.exact()).velocity(rotate(dir, yaw)), r.stroke(), target, aim.exact());
    }

    private static V3 rotate(V3 dir, double yaw) {
        double c = Math.cos(yaw);
        double s = Math.sin(yaw);
        return new V3(dir.x() * c - dir.z() * s, 0, dir.x() * s + dir.z() * c);
    }

    /** Wysoki łuk: od najwolniejszego rzutu szukamy takiego, który wysokim łukiem przejdzie nad siatką. */
    private static Ballistics.Aim lob(BallSpec spec, double h0, double dist, double targetY, double netDist, double clearY) {
        for (double speed = 3.0; speed <= 9.0; speed += 0.25) {
            Ballistics.Aim a = Ballistics.solveAngle(spec, h0, dist, targetY, speed, true);
            if (!a.exact()) continue;
            Ballistics.Flight f = Ballistics.flight(spec, h0, a.speed(), a.angleDeg(), netDist);
            if (f.reached() && f.height() >= clearY) return a;
        }
        return Ballistics.solveClearing(spec, h0, dist, targetY, netDist, clearY, 3, 11);
    }

    // ---------- serwis ----------

    /** Wynik próbnego lotu serwisu: czy spełnia zasady i gdzie spadł drugi raz. */
    private record Trial(boolean legal, V3 secondBounce) {}

    private static Trial trial(PhysicsWorld w, PingPongTable t, V3 from, V3 dir, double speed, double angleDeg, boolean ownLow) {
        double a = Math.toRadians(angleDeg);
        BallBody b = new BallBody(from, new V3(dir.x() * speed * Math.cos(a), speed * Math.sin(a), dir.z() * speed * Math.cos(a)));
        V3[] bounces = new V3[2];
        int[] n = {0};
        boolean[] bad = {false};
        PhysicsWorld.Listener l = (Contact c, double impact, V3 pos) -> {
            switch (c.kind()) {
                case NET, FLOOR -> bad[0] = true;
                case TABLE -> {
                    if (c.normal().y() < 0.5) bad[0] = true;
                    else {
                        if (n[0] < 2) bounces[n[0]] = pos;
                        n[0]++;
                    }
                }
                default -> { }
            }
        };
        for (int i = 0; i < 150 && n[0] < 2 && !bad[0]; i++) w.step(b, 0.02, l);
        if (bad[0] || n[0] < 2) return new Trial(false, null);
        // odbicia tuż przy krawędzi blatu są niepewne (piłeczka może odskoczyć na bok), więc takich serwisów nie wybieramy
        double m = 0.1;
        boolean inside = bounces[0].z() > t.v0() + m && bounces[0].z() < t.v1() - m && bounces[1].z() > t.v0() + m && bounces[1].z() < t.v1() - m
                && bounces[0].x() > t.u0() + m && bounces[0].x() < t.u1() - m && bounces[1].x() > t.u0() + m && bounces[1].x() < t.u1() - m;
        boolean ok = inside && t.lowSide(bounces[0]) == ownLow && t.lowSide(bounces[1]) != ownLow;
        return new Trial(ok, bounces[1]);
    }

    /** Serwis: wyszukuje prędkość i kąt, przy których piłeczka odbije się od własnej połowy, przejdzie nad siatką i spadnie jak najbliżej celu. */
    private static Hit serve(PingPongTable t, BallSpec spec, Request r, RandomGenerator rng) {
        V3 target = aimPoint(t, spec, r);
        V3 from = r.ballPos();
        V3 delta = target.minus(from);
        double toward = r.ownLow() ? 1 : -1;
        V3 dir = delta.horizontalLength() < 1e-6 ? new V3(toward, 0, 0) : delta.horizontal().normalized();
        PhysicsWorld w = t.physics(spec);
        double bestScore = Double.MAX_VALUE;
        double bestSpeed = 4.5;
        double bestAngle = -14;
        boolean exact = false;
        for (double speed = 2.5; speed <= 8.0; speed += 0.5) {
            for (double angle = -45; angle <= 8; angle += 2) {
                Trial tr = trial(w, t, from, dir, speed, angle, r.ownLow());
                if (!tr.legal()) continue;
                double score = tr.secondBounce().horizontalDistanceTo(target) + 0.01 * speed;
                if (score < bestScore) {
                    bestScore = score;
                    bestSpeed = speed;
                    bestAngle = angle;
                    exact = true;
                }
            }
        }
        double err = 1 - clamp(r.quality(), 0, 1);
        double acc = Math.max(0.3, r.accuracy());
        double speed = bestSpeed * (1 + rng.nextGaussian() * (0.008 + 0.06 * err) / acc);
        double angle = bestAngle + rng.nextGaussian() * (0.2 + 1.2 * err) / acc;
        double yaw = Math.toRadians(rng.nextGaussian() * (0.5 + 3.0 * err) / acc);
        return new Hit(new Ballistics.Aim(speed, angle, exact).velocity(rotate(dir, yaw)), Stroke.SERVE, target, exact);
    }
}
