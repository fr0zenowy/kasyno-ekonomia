package pl.sport.volley;

import org.junit.jupiter.api.Test;
import pl.sport.core.BallBody;
import pl.sport.core.BallSpec;
import pl.sport.core.Ballistics;
import pl.sport.core.Contact;
import pl.sport.core.PhysicsWorld;
import pl.sport.core.V3;
import pl.sport.volley.VolleyGame.HitType;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uderzenia siatkarskie: przejście nad siatką, lądowanie w celu, wystawienie do partnera, blok. */
class HitModelTest {
    private static final BallSpec SPEC = BallSpec.VOLLEYBALL;
    private final VolleyCourt court = new VolleyCourt(18, 9, 2.43);

    /** Wynik lotu piłki: gdzie pierwszy raz dotknęła podłogi i czy po drodze trafiła w siatkę. */
    private record Landing(V3 pos, boolean touchedNet, boolean crossedNet) {}

    private Landing fly(V3 pos, V3 vel) {
        PhysicsWorld w = court.physics(SPEC);
        BallBody b = new BallBody(pos, vel);
        boolean[] net = {false};
        boolean side0 = court.lowSide(pos);
        boolean crossed = false;
        for (int i = 0; i < 400; i++) {
            final boolean[] floor = {false};
            w.step(b, 0.025, (c, impact, p) -> {
                if (c.kind() == Contact.Kind.NET || c.kind() == Contact.Kind.POST) net[0] = true;
                if (c.kind() == Contact.Kind.FLOOR) floor[0] = true;
            });
            if (court.lowSide(b.pos()) != side0) crossed = true;
            if (floor[0]) return new Landing(b.pos(), net[0], crossed);
        }
        return new Landing(b.pos(), net[0], crossed);
    }

    private HitModel.Request serve(V3 eye, V3 ball, double yaw, double pitch, double quality) {
        return new HitModel.Request(HitType.SERVE, ball, V3.ZERO, eye, V3.fromLook(yaw, pitch), true, null, quality);
    }

    @Test
    void servesAimedAtTheOpponentsFloorClearTheNetAndLandInside() {
        // serwujący za linią końcową, patrzy w stronę boiska (yaw -90 = +X) i w dół, w punkt ok. 4 m za siatką
        V3 ball = new V3(-1.2, 2.6, 4.5);
        V3 eye = new V3(-1.2, 1.62, 4.5);
        Random rng = new Random(7);
        int good = 0;
        int n = 300;
        for (int i = 0; i < n; i++) {
            // pitch dodatni = w dół: oko 1,62 m, cel na podłodze 14,2 m dalej => ok. 6,5 stopnia w dół
            HitModel.Hit hit = HitModel.compute(court, SPEC, serve(eye, ball, -90, 7.0, 0.85), rng);
            Landing l = fly(ball, hit.velocity());
            if (l.crossedNet() && !l.touchedNet() && court.inCourt(l.pos(), SPEC.radius()) && l.pos().x() > court.netU()) good++;
        }
        double pct = 100.0 * good / n;
        System.out.printf("ZAGRYWKI dobre: %.0f%%%n", pct);
        assertTrue(pct >= 80, "dobra zagrywka powinna zwykle wejść w boisko: " + pct);
    }

    @Test
    void aSloppyHitScattersMuchMoreThanAGoodOne() {
        V3 ball = new V3(-1.2, 2.6, 4.5);
        V3 eye = new V3(-1.2, 1.62, 4.5);
        double[] spread = new double[2];
        double[] qualities = {0.95, 0.15};
        for (int k = 0; k < 2; k++) {
            Random rng = new Random(3);
            double sum = 0;
            double sumSq = 0;
            int n = 200;
            for (int i = 0; i < n; i++) {
                HitModel.Hit hit = HitModel.compute(court, SPEC, serve(eye, ball, -90, 7.0, qualities[k]), rng);
                Landing l = fly(ball, hit.velocity());
                sum += l.pos().z();
                sumSq += l.pos().z() * l.pos().z();
            }
            double mean = sum / n;
            spread[k] = Math.sqrt(sumSq / n - mean * mean);
        }
        System.out.printf("ROZRZUT zagrywki: dobra %.2f m, słaba %.2f m%n", spread[0], spread[1]);
        assertTrue(spread[1] > spread[0] * 2.5, "słabe odbicie rozrzuca piłkę wyraźnie bardziej");
    }

    @Test
    void aSpikeAimedAtTheFloorLandsNearTheAimedSpot() {
        // atak z wyskoku przy siatce: piłka 3,0 m wysoko, oko 2,6 m, patrzy w dół na punkt 6 m dalej
        V3 ball = new V3(8.0, 3.0, 4.5);
        V3 eye = new V3(7.6, 2.6, 4.5);
        double pitch = Math.toDegrees(Math.atan((2.6 - 0.13) / 6.0)); // patrzy w punkt 6 m dalej
        HitModel.Request req = new HitModel.Request(HitType.SPIKE, ball, V3.ZERO, eye, V3.fromLook(-90, pitch), true, null, 0.95);
        Random rng = new Random(11);
        int near = 0;
        int n = 100;
        for (int i = 0; i < n; i++) {
            HitModel.Hit hit = HitModel.compute(court, SPEC, req, rng);
            Landing l = fly(ball, hit.velocity());
            double dx = l.pos().x() - (ball.x() + 6.0);
            double dz = l.pos().z() - 4.5;
            if (l.crossedNet() && Math.hypot(dx, dz) < 2.0) near++;
        }
        System.out.printf("ATAKI blisko celu: %d%%%n", near);
        assertTrue(near >= 70, "atak lądował w pobliżu wskazanego miejsca: " + near);
    }

    @Test
    void aSetArrivesAboveTheTeammateAtSettingHeight() {
        V3 ball = new V3(5.0, 1.0, 3.0);
        V3 eye = new V3(5.0, 1.62, 3.0);
        V3 mate = new V3(8.0, 0, 5.0);
        HitModel.Request req = new HitModel.Request(HitType.SET, ball, V3.ZERO, eye, V3.fromLook(-110, -10), true, mate, 0.95);
        Random rng = new Random(5);
        HitModel.Hit hit = HitModel.compute(court, SPEC, req, rng);
        assertTrue(hit.aimedAtTarget());
        double dist = Math.hypot(mate.x() - ball.x(), mate.z() - ball.z());
        V3 dir = new V3(mate.x() - ball.x(), 0, mate.z() - ball.z()).normalized();
        // horyzontalna składowa prędkości jest skierowana na partnera
        assertTrue(hit.velocity().horizontal().normalized().dot(dir) > 0.98);
        Ballistics.Flight f = Ballistics.flight(SPEC, ball.y(), hit.velocity().length(), Math.toDegrees(Math.atan2(hit.velocity().y(), hit.velocity().horizontalLength())), dist);
        assertTrue(f.reached());
        assertEquals(2.5, f.height(), 0.5, "wystawiona piłka dociera nad partnera na wysokości do ataku");
    }

    @Test
    void aBumpToATeammateGoesUpHighEnoughToBePlayed() {
        V3 ball = new V3(2.0, 0.9, 2.0);
        V3 eye = new V3(2.0, 1.62, 2.0);
        V3 mate = new V3(6.0, 0, 4.0);
        HitModel.Request req = new HitModel.Request(HitType.BUMP, ball, V3.ZERO, eye, V3.fromLook(-110, 0), true, mate, 0.9);
        HitModel.Hit hit = HitModel.compute(court, SPEC, req, new Random(2));
        assertTrue(hit.velocity().y() > 4, "odbicie bagerem leci wysoko: " + hit.velocity());
        Landing l = fly(ball, hit.velocity());
        assertFalse(l.crossedNet(), "podanie do kolegi zostaje po własnej stronie");
    }

    @Test
    void aBlockSendsTheBallBackAndDown() {
        V3 ball = new V3(8.8, 2.6, 4.5);
        V3 incoming = new V3(14, -1, 0.5); // atak z drugiej strony (w stronę +X)... piłka leci w +X
        HitModel.Request req = new HitModel.Request(HitType.BLOCK, ball, new V3(-14, -1, 0.5), new V3(8.6, 2.5, 4.5), V3.fromLook(-90, 0), true, null, 0.9);
        HitModel.Hit hit = HitModel.compute(court, SPEC, req, new Random(1));
        assertTrue(hit.velocity().x() > 4, "blok odbija piłkę z powrotem: " + hit.velocity());
        assertTrue(hit.velocity().y() < 0, "w dół");
        assertEquals(HitType.BLOCK, hit.type());
    }

    @Test
    void aBumpAimedOverTheNetClearsItAndLandsOnTheOtherSide() {
        // gracz 3 m od siatki odbija piłkę patrząc na rywali (poziomo): ma przejść nad siatką
        Random rng = new Random(9);
        int over = 0;
        int n = 100;
        for (int i = 0; i < n; i++) {
            V3 ball = new V3(6.0, 1.0, 4.5);
            V3 eye = new V3(6.0, 1.62, 4.5);
            HitModel.Request req = new HitModel.Request(HitType.BUMP, ball, V3.ZERO, eye, V3.fromLook(-90, 0), true, null, 0.9);
            HitModel.Hit hit = HitModel.compute(court, SPEC, req, rng);
            Landing l = fly(ball, hit.velocity());
            if (l.crossedNet() && !l.touchedNet() && l.pos().x() > court.netU()) over++;
        }
        System.out.printf("ODBICIA nad siatką: %d%%%n", over);
        assertTrue(over >= 85, "odbicie w stronę rywali przechodzi nad siatką: " + over);
    }

    @Test
    void aSetAimedAtTheOpponentsAlsoClearsTheNet() {
        Random rng = new Random(4);
        int over = 0;
        for (int i = 0; i < 100; i++) {
            V3 ball = new V3(8.0, 2.0, 4.5);
            V3 eye = new V3(8.0, 1.62, 4.5);
            HitModel.Request req = new HitModel.Request(HitType.SET, ball, V3.ZERO, eye, V3.fromLook(-90, 0), true, null, 0.9);
            Landing l = fly(ball, HitModel.compute(court, SPEC, req, rng).velocity());
            if (l.crossedNet() && !l.touchedNet()) over++;
        }
        assertTrue(over >= 85, "wystawienie na stronę rywali przechodzi nad siatką: " + over);
    }

    @Test
    void aimingFlatUsesTheDefaultRange() {
        V3 ball = new V3(-1.2, 2.6, 4.5);
        V3 eye = new V3(-1.2, 1.62, 4.5);
        HitModel.Request req = serve(eye, ball, -90, 0, 1.0);
        V3 target = HitModel.aimPoint(court, SPEC, req);
        // siatka jest 10,2 m dalej, a piłka ma wylądować 4 m za nią (dłużej niż domyślne 13 m)
        assertEquals(9.0 + 4.0, target.x(), 1e-6);
        assertEquals(4.5, target.z(), 1e-6);
    }
}
