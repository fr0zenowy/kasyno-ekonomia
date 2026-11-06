package pl.sport.basket;

import org.junit.jupiter.api.Test;
import pl.sport.core.BallBody;
import pl.sport.core.BallSpec;
import pl.sport.core.HoopSensor;
import pl.sport.core.PhysicsWorld;
import pl.sport.core.V3;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rzuty do kosza: prawdziwa fizyka (obręcz, tablica) i model błędu dają sensowną skuteczność z różnych miejsc. */
class ShotModelTest {
    private static final BallSpec SPEC = BallSpec.BASKETBALL;
    private final BasketCourt court = new BasketCourt(28, 15);

    /** Symuluje lot piłki do upadku na podłogę. Zwraca true, gdy wpadła do kosza o indeksie hoop. */
    private boolean flies(V3 release, V3 velocity, int hoop) {
        PhysicsWorld w = court.physics(SPEC);
        HoopSensor sensor = court.sensor(hoop);
        BallBody b = new BallBody(release, velocity);
        for (int i = 0; i < 160; i++) {
            V3 before = b.pos();
            w.step(b, 0.025, null);
            if (sensor.update(before, b.pos())) return true;
            if (b.pos().y() < SPEC.radius() + 0.05 && b.vel().y() < 0 && i > 5) return false;
        }
        return false;
    }

    private double makePercent(double dist, ShotModel.Context base, int shots, long seed) {
        Random rng = new Random(seed);
        V3 rim = court.rimCenter(0);
        // rzucający stoi na osi kosza, `dist` od środka obręczy
        V3 release = new V3(rim.x() + dist, 2.1, rim.z());
        ShotModel.Context c = new ShotModel.Context(release, rim, base.contest(), base.jumping(), base.perfect(), base.sprinting(), base.accuracy());
        int made = 0;
        for (int i = 0; i < shots; i++) {
            ShotModel.Shot s = ShotModel.aimed(SPEC, c, rng);
            if (flies(release, s.velocity(), 0)) made++;
        }
        return 100.0 * made / shots;
    }

    @Test
    void aPerfectlyAimedShotAlwaysGoesIn() {
        // bez błędu rozwiązanie z Ballistics trafia dokładnie w środek obręczy z każdej odległości
        V3 rim = court.rimCenter(0);
        for (double dist = 1.5; dist <= 9.5; dist += 0.5) {
            V3 release = new V3(rim.x() + dist, 2.1, rim.z());
            ShotModel.Context c = new ShotModel.Context(release, rim, 0, false, true, false, 1e9);
            // ogromna celność zeruje błąd
            ShotModel.Shot s = ShotModel.aimed(SPEC, c, new Random(1));
            assertTrue(s.aimed(), "rzut z " + dist + " m powinien być wykonalny");
            assertTrue(flies(release, s.velocity(), 0), "idealny rzut z " + dist + " m wpada do kosza");
        }
    }

    @Test
    void successRateFallsWithDistanceAndIsInARealisticRange() {
        ShotModel.Context base = ShotModel.Context.standing(V3.ZERO, V3.ZERO);
        double layup = makePercent(1.5, base, 400, 11);
        double mid = makePercent(4.5, base, 400, 12);
        double three = makePercent(7.2, base, 400, 13);
        double deep = makePercent(10.5, base, 400, 14);
        System.out.printf("RZUTY: 1,5 m %.0f%%  4,5 m %.0f%%  7,2 m %.0f%%  10,5 m %.0f%%%n", layup, mid, three, deep);
        assertTrue(layup > 75, "pod koszem: " + layup);
        assertTrue(mid > 45 && mid < 85, "średni dystans: " + mid);
        assertTrue(three > 25 && three < 65, "za trzy punkty: " + three);
        assertTrue(deep < three, "z bardzo daleka trudniej: " + deep + " vs " + three);
        assertTrue(layup > mid && mid > three, "skuteczność maleje z odległością");
    }

    @Test
    void aDefenderInYourFaceAndSprintingHurtWhileAPerfectJumpHelps() {
        double d = 4.5;
        double clean = makePercent(d, ShotModel.Context.standing(V3.ZERO, V3.ZERO), 400, 21);
        double contested = makePercent(d, new ShotModel.Context(V3.ZERO, V3.ZERO, 1, false, false, false, 1), 400, 21);
        double sprint = makePercent(d, new ShotModel.Context(V3.ZERO, V3.ZERO, 0, false, false, true, 1), 400, 21);
        double perfect = makePercent(d, new ShotModel.Context(V3.ZERO, V3.ZERO, 0, true, true, false, 1), 400, 21);
        System.out.printf("RZUTY 4,5 m: czysty %.0f%%  z obrońcą %.0f%%  w biegu %.0f%%  idealny wyskok %.0f%%%n", clean, contested, sprint, perfect);
        assertTrue(contested < clean - 8, "obrońca: " + contested + " vs " + clean);
        assertTrue(sprint < clean, "bieg: " + sprint + " vs " + clean);
        assertTrue(perfect > clean + 8, "idealny wyskok: " + perfect + " vs " + clean);
        assertTrue(perfect > 85, "idealny wyskok prawie zawsze wpada: " + perfect);
    }

    @Test
    void anImpossiblyFarShotIsNotAimedAndFallsShort() {
        V3 rim = court.rimCenter(0);
        V3 release = new V3(rim.x() + 24, 2.1, rim.z());
        ShotModel.Shot s = ShotModel.aimed(SPEC, ShotModel.Context.standing(release, rim), new Random(5));
        assertFalse(s.aimed());
        assertFalse(flies(release, s.velocity(), 0));
    }

    @Test
    void shotsAreAimedAtTheRimDirection() {
        V3 rim = court.rimCenter(0);
        V3 release = new V3(rim.x() + 5, 2.1, rim.z() + 3);
        ShotModel.Shot s = ShotModel.aimed(SPEC, ShotModel.Context.standing(release, rim), new Random(3));
        V3 toRim = rim.minus(release).horizontal().normalized();
        V3 heading = s.velocity().horizontal().normalized();
        assertTrue(toRim.dot(heading) > 0.995, "kierunek poziomy zgodny z kierunkiem do kosza: " + toRim.dot(heading));
        assertTrue(s.velocity().y() > 0, "rzut leci w górę");
    }

    @Test
    void freeShotsFollowTheLookDirection() {
        V3 look = V3.fromLook(90, -40); // patrzy w górę, na -X... (yaw 90 = -X)
        ShotModel.Shot s = ShotModel.free(look, 8, new Random(2));
        assertEquals(8, s.velocity().length(), 1e-6);
        assertTrue(s.velocity().x() < 0 && s.velocity().y() > 0);
        assertFalse(s.aimed());
    }

    @Test
    void freeThrowsAreMoreAccurateThanARegularShotFromTheSameSpot() {
        double dist = 4.2; // linia rzutów osobistych jest ok. 4,2 m od środka obręczy
        double regular = makePercent(dist, new ShotModel.Context(V3.ZERO, V3.ZERO, 0, false, false, false, 1.1), 600, 31);
        double free = makePercent(dist, new ShotModel.Context(V3.ZERO, V3.ZERO, 0, false, false, false, 1.1 * 1.35), 600, 31);
        System.out.printf("RZUTY OSOBISTE 4,2 m: zwykły %.0f%%  osobisty %.0f%%%n", regular, free);
        assertTrue(free > regular + 3, "rzut osobisty celniejszy: " + free + " vs " + regular);
        assertTrue(free > 60 && free < 97, "realistyczna skuteczność: " + free);
    }

    @Test
    void aLayupIsNearlyCertainWhenUncontestedAndDefenderStillHurts() {
        double clean = makePercent(4.2, new ShotModel.Context(V3.ZERO, V3.ZERO, 0, true, false, false, 1.1 * 1.7), 600, 41);
        double contested = makePercent(4.2, new ShotModel.Context(V3.ZERO, V3.ZERO, 1, true, false, false, 1.1 * 1.7), 600, 41);
        double jumpShot = makePercent(4.2, new ShotModel.Context(V3.ZERO, V3.ZERO, 0, false, false, false, 1.1), 600, 41);
        System.out.printf("DWUTAKT 4,2 m: czysty %.0f%%  z obrońcą %.0f%%  zwykły rzut %.0f%%%n", clean, contested, jumpShot);
        assertTrue(clean > 80, "dwutakt bez obrony: " + clean);
        assertTrue(clean > jumpShot + 10, "dwutakt celniejszy niż zwykły rzut z tego miejsca: " + clean + " vs " + jumpShot);
        assertTrue(contested < clean - 5, "obrońca przeszkadza: " + contested + " vs " + clean);
    }
}
