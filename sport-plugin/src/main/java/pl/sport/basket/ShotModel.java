package pl.sport.basket;

import pl.sport.core.Ballistics;
import pl.sport.core.BallSpec;
import pl.sport.core.V3;

import java.util.random.RandomGenerator;

/**
 * Rzut do kosza: gracz celuje mniej więcej w stronę kosza, a model wylicza fizycznie poprawny rzut (prędkość i kąt, razem z oporem
 * powietrza) i dokłada do niego błąd. Wielkość błędu rośnie z odległością i z obrońcą przy rzucającym, a maleje przy rzucie w wyskoku
 * zwolnionym w najwyższym punkcie. Wynik zależy więc od umiejętności i sytuacji, ale zawsze podlega prawdziwej fizyce (obręcz, tablica,
 * wejście „na raz” i odbicia).
 */
public final class ShotModel {
    private ShotModel() {}

    /** Okoliczności rzutu. */
    public record Context(V3 release, V3 rim, double contest, boolean jumping, boolean perfect, boolean sprinting, double accuracy) {
        public static Context standing(V3 release, V3 rim) {
            return new Context(release, rim, 0, false, false, false, 1);
        }
    }

    /** Wynik: wektor prędkości piłki i czy rzut był celowany w kosz. */
    public record Shot(V3 velocity, double speed, double angleDeg, boolean aimed) {}

    /** Największa prędkość, z jaką gracz potrafi rzucić (m/s). Dalsze rzuty nie dolatują do kosza. */
    public static final double MAX_SPEED = 15.5;

    /** Kąt wejścia dobrany do odległości: z bliska stromo (kosz „z góry”), z daleka coraz płyczej. */
    static double angleFor(double dist) {
        if (dist <= 1.2) return 72;
        if (dist <= 4) return 72 - (dist - 1.2) / 2.8 * 14;
        if (dist <= 7) return 58 - (dist - 4) / 3 * 6;
        return Math.max(46, 52 - (dist - 7) * 1.2);
    }

    /** Odchylenie standardowe względnego błędu prędkości dla danego dystansu. */
    static double speedSigma(double dist) {
        return 0.009 + 0.0030 * dist;
    }

    /** Odchylenie standardowe błędu kierunku w stopniach. */
    static double aimSigma(double dist) {
        return 0.40 + 0.082 * dist;
    }

    /** Mnożnik błędu: obrońca przy rzucającym psuje rzut, wyskok i idealne zwolnienie go poprawiają, bieg pogarsza. */
    static double errorFactor(Context c) {
        double f = 1 + 1.25 * Math.max(0, Math.min(1, c.contest()));
        if (c.perfect()) f *= 0.35;
        else if (c.jumping()) f *= 0.85;
        if (c.sprinting()) f *= 1.3;
        return f / Math.max(0.2, c.accuracy());
    }

    /** Rzut wycelowany w kosz. Dla zbyt dalekiego rzutu (poza zasięgiem) piłka leci tak daleko, jak się da, i nie dolatuje. */
    public static Shot aimed(BallSpec spec, Context c, RandomGenerator rng) {
        V3 delta = c.rim().minus(c.release());
        double dist = delta.horizontalLength();
        V3 dir = dist < 1e-6 ? new V3(1, 0, 0) : delta.horizontal().normalized();
        double angle = angleFor(dist);
        Ballistics.Aim aim = Ballistics.solveSpeed(spec, c.release().y(), dist, c.rim().y(), angle, MAX_SPEED);
        if (aim == null) aim = Ballistics.solveSpeed(spec, c.release().y(), dist, c.rim().y(), 45, MAX_SPEED);
        boolean ok = aim != null;
        if (aim == null) aim = new Ballistics.Aim(MAX_SPEED, 45, false);

        double f = errorFactor(c);
        double speed = aim.speed() * (1 + rng.nextGaussian() * speedSigma(dist) * f);
        double yawErr = Math.toRadians(rng.nextGaussian() * aimSigma(dist) * f);
        double angleErr = rng.nextGaussian() * 0.35 * f;
        double cos = Math.cos(yawErr);
        double sin = Math.sin(yawErr);
        V3 rotated = new V3(dir.x() * cos - dir.z() * sin, 0, dir.x() * sin + dir.z() * cos);
        Ballistics.Aim shot = new Ballistics.Aim(speed, aim.angleDeg() + angleErr, ok);
        V3 v = shot.velocity(rotated);
        return new Shot(v, speed, shot.angleDeg(), ok);
    }

    /** Rzut w dowolnym kierunku (gracz nie patrzy na kosz): prędkość z pitcha, lekki rozrzut. */
    public static Shot free(V3 lookDir, double powerSpeed, RandomGenerator rng) {
        V3 dir = lookDir.normalized();
        double yawJitter = Math.toRadians(rng.nextGaussian() * 1.5);
        double cos = Math.cos(yawJitter);
        double sin = Math.sin(yawJitter);
        V3 d = new V3(dir.x() * cos - dir.z() * sin, dir.y(), dir.x() * sin + dir.z() * cos);
        V3 v = d.times(powerSpeed);
        double angle = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, d.y()))));
        return new Shot(v, powerSpeed, angle, false);
    }
}
