package pl.sport.core;

/**
 * Wyznaczanie rzutów: jaką prędkość i jaki kąt nadać piłce, żeby trafiła w cel (kosz, punkt na boisku, partner do wystawienia).
 * Lot odbywa się w jednej pionowej płaszczyźnie (nie ma wiatru ani rotacji), więc zadanie jest dwuwymiarowe: x to odległość poziomo
 * w stronę celu, y to wysokość. Całkowanie jest takie samo jak w {@link PhysicsWorld}, razem z oporem powietrza, więc rozwiązanie
 * przewidziane tutaj zgadza się z tym, co zrobi później fizyka.
 */
public final class Ballistics {
    private Ballistics() {}

    private static final double DT = 0.004;
    private static final double MAX_TIME = 10;
    public static final double MIN_ANGLE = -80;
    public static final double MAX_ANGLE = 84;

    /** Wynik lotu do zadanej odległości poziomej. Gdy piłka spadnie wcześniej, `distance` to odległość, w której opadła do poziomu celu. */
    public record Flight(boolean reached, double height, double time, double distance) {}

    /** Rozwiązanie: prędkość początkowa (m/s) i kąt wzniesienia (stopnie). */
    public record Aim(double speed, double angleDeg, boolean exact) {
        /** Wektor prędkości początkowej w kierunku poziomym `dir` (jednostkowym). */
        public V3 velocity(V3 dir) {
            double a = Math.toRadians(angleDeg);
            V3 h = dir.horizontal().normalized();
            return new V3(h.x() * speed * Math.cos(a), speed * Math.sin(a), h.z() * speed * Math.cos(a));
        }
    }

    /** Lot piłki wystrzelonej z wysokości h0 pod kątem angleDeg z prędkością speed, aż przebędzie poziomo `dist`. */
    public static Flight flight(BallSpec s, double h0, double speed, double angleDeg, double dist) {
        return flight(s, h0, speed, angleDeg, dist, h0 - 60);
    }

    /** Jak wyżej, ale lot kończy się też wtedy, gdy piłka opada poniżej wysokości stopY (np. poziomu celu). */
    public static Flight flight(BallSpec s, double h0, double speed, double angleDeg, double dist, double stopY) {
        double a = Math.toRadians(angleDeg);
        double vx = speed * Math.cos(a);
        double vy = speed * Math.sin(a);
        if (dist < 1e-9) return new Flight(true, h0, 0, 0);
        if (vx <= 1e-9) return new Flight(false, Double.NEGATIVE_INFINITY, 0, 0);
        double x = 0;
        double y = h0;
        double k = s.dragK();
        double g = s.gravity();
        for (double t = 0; t < MAX_TIME; t += DT) {
            double sp = Math.sqrt(vx * vx + vy * vy);
            double ax = -k * sp * vx;
            double ay = -g - k * sp * vy;
            double vxm = vx + ax * DT / 2;
            double vym = vy + ay * DT / 2;
            double spm = Math.sqrt(vxm * vxm + vym * vym);
            double nx = x + vxm * DT;
            double ny = y + vym * DT;
            double nvx = vx + (-k * spm * vxm) * DT;
            double nvy = vy + (-g - k * spm * vym) * DT;
            if (nx >= dist) {
                double f = (dist - x) / (nx - x);
                return new Flight(true, y + (ny - y) * f, t + DT * f, dist);
            }
            x = nx;
            y = ny;
            vx = nvx;
            vy = nvy;
            if (y < stopY && vy < 0) return new Flight(false, Double.NEGATIVE_INFINITY, t, x);
        }
        return new Flight(false, Double.NEGATIVE_INFINITY, MAX_TIME, x);
    }

    private static double heightOrFloor(Flight f) {
        return f.reached() ? f.height() : -1e9;
    }

    /** Cel optymalizacji kąta: dolot do celu (wyżej = lepiej), a gdy piłka nie dolatuje, jak najdalszy upadek. */
    private static double reachScore(Flight f) {
        return f.reached() ? 1000 + f.height() : f.distance();
    }

    /**
     * Prędkość, z jaką trzeba rzucić pod kątem angleDeg, żeby piłka znalazła się na wysokości targetY w odległości dist.
     * Zwraca null, gdy nawet maksymalna prędkość (maxSpeed) nie wystarcza.
     */
    public static Aim solveSpeed(BallSpec s, double h0, double dist, double targetY, double angleDeg, double maxSpeed) {
        double lo = 0.5;
        double hi = maxSpeed;
        if (heightOrFloor(flight(s, h0, hi, angleDeg, dist)) < targetY) return null;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (heightOrFloor(flight(s, h0, mid, angleDeg, dist)) >= targetY) hi = mid;
            else lo = mid;
        }
        return new Aim(hi, angleDeg, true);
    }

    /** Kąt wzniesienia, przy którym piłka wystrzelona z zadaną prędkością dociera najwyżej w odległości dist (obwiednia zasięgu). */
    public static double bestAngle(BallSpec s, double h0, double dist, double targetY, double speed) {
        double lo = MIN_ANGLE;
        double hi = MAX_ANGLE;
        double phi = (Math.sqrt(5) - 1) / 2;
        double c = hi - phi * (hi - lo);
        double d = lo + phi * (hi - lo);
        double fc = reachScore(flight(s, h0, speed, c, dist, targetY));
        double fd = reachScore(flight(s, h0, speed, d, dist, targetY));
        for (int i = 0; i < 40; i++) {
            if (fc > fd) {
                hi = d;
                d = c;
                fd = fc;
                c = hi - phi * (hi - lo);
                fc = reachScore(flight(s, h0, speed, c, dist, targetY));
            } else {
                lo = c;
                c = d;
                fc = fd;
                d = lo + phi * (hi - lo);
                fd = reachScore(flight(s, h0, speed, d, dist, targetY));
            }
        }
        return (lo + hi) / 2;
    }

    /**
     * Kąt, przy którym piłka o zadanej prędkości trafia w punkt (dist, targetY). `high` wybiera lot po wysokim łuku, w przeciwnym razie
     * po płaskim. Gdy cel jest poza zasięgiem, zwraca kąt maksymalnego zasięgu (exact = false), czyli piłka doleci jak najdalej.
     */
    public static Aim solveAngle(BallSpec s, double h0, double dist, double targetY, double speed, boolean high) {
        double opt = bestAngle(s, h0, dist, targetY, speed);
        if (heightOrFloor(flight(s, h0, speed, opt, dist)) < targetY) return new Aim(speed, opt, false);
        double lo = high ? opt : MIN_ANGLE;
        double hi = high ? MAX_ANGLE : opt;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            double y = heightOrFloor(flight(s, h0, speed, mid, dist));
            // po stronie płaskiej wysokość rośnie z kątem, po stronie wysokiej maleje
            boolean tooHigh = y > targetY;
            if (high == tooHigh) lo = mid;
            else hi = mid;
        }
        return new Aim(speed, (lo + hi) / 2, true);
    }

    /**
     * Rzut w punkt (dist, targetY), który przechodzi nad przeszkodą (np. siatką) w odległości obstacleDist na wysokości co najmniej
     * obstacleY. Szuka od najsłabszego rzutu: dla rosnącej prędkości sprawdza łuk płaski, potem wysoki. Gdy nic nie przejdzie nad
     * przeszkodą, zwraca najwyższy łuk przy maksymalnej prędkości (exact = false).
     */
    public static Aim solveClearing(BallSpec s, double h0, double dist, double targetY, double obstacleDist, double obstacleY, double minSpeed, double maxSpeed) {
        Aim fallback = null;
        int steps = 28;
        for (int i = 0; i <= steps; i++) {
            double speed = minSpeed + (maxSpeed - minSpeed) * i / steps;
            for (boolean high : new boolean[]{false, true}) {
                Aim a = solveAngle(s, h0, dist, targetY, speed, high);
                if (!a.exact()) continue;
                Flight f = flight(s, h0, a.speed(), a.angleDeg(), obstacleDist);
                if (f.reached() && f.height() >= obstacleY) return a;
                fallback = a;
            }
        }
        if (fallback != null) return new Aim(fallback.speed(), fallback.angleDeg(), false);
        return new Aim(maxSpeed, bestAngle(s, h0, dist, targetY, maxSpeed), false);
    }
}
