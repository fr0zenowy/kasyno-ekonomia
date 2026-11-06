package pl.sport.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fizyka piłki: zgodność z wzorami, odbicia, toczenie, brak przenikania przez cienkie przeszkody. */
class PhysicsTest {
    private static PhysicsWorld world(BallSpec spec) {
        return new PhysicsWorld(spec).add(new Floor(0, spec.floorRestitution(), spec.tangentialLoss()));
    }

    @Test
    void freeFallMatchesTheFormulaWithoutDrag() {
        BallSpec s = BallSpec.BASKETBALL.withDrag(0);
        PhysicsWorld w = new PhysicsWorld(s);
        BallBody b = new BallBody(new V3(0, 5, 0), V3.ZERO);
        w.run(b, 0.5, 0.05, null);
        assertEquals(5 - 0.5 * 9.81 * 0.25, b.pos().y(), 0.005);
        assertEquals(-9.81 * 0.5, b.vel().y(), 0.005);
    }

    @Test
    void dragSlowsALightBallMoreThanAHeavyOne() {
        BallBody v = new BallBody(new V3(0, 10, 0), new V3(18, 0, 0));
        BallBody k = new BallBody(new V3(0, 10, 0), new V3(18, 0, 0));
        new PhysicsWorld(BallSpec.VOLLEYBALL).run(v, 0.6, 0.05, null);
        new PhysicsWorld(BallSpec.BASKETBALL).run(k, 0.6, 0.05, null);
        assertTrue(v.vel().x() < k.vel().x(), "siatkówka hamuje bardziej: " + v.vel().x() + " vs " + k.vel().x());
        assertTrue(v.vel().x() > 8 && v.vel().x() < 18);
    }

    @Test
    void flightIntegratorAgreesWithThePhysicsWorld() {
        // te same wzory w rozwiązywaniu rzutów i w symulacji, więc rzut policzony z wyprzedzeniem leci dokładnie tak, jak przewidziano
        BallSpec s = BallSpec.BASKETBALL;
        double angle = 50;
        double speed = 9;
        Ballistics.Flight f = Ballistics.flight(s, 2.2, speed, angle, 5);
        BallBody b = new BallBody(new V3(0, 2.2, 0), new V3(speed * Math.cos(Math.toRadians(angle)), speed * Math.sin(Math.toRadians(angle)), 0));
        PhysicsWorld w = new PhysicsWorld(s);
        double t = 0;
        while (b.pos().x() < 5 && t < 5) {
            w.step(b, 0.01, null);
            t += 0.01;
        }
        assertTrue(f.reached());
        assertEquals(f.height(), b.pos().y(), 0.03);
        assertEquals(f.time(), t, 0.02);
    }

    @Test
    void basketballBouncesBackToRestitutionSquaredOfTheDropHeight() {
        BallSpec s = BallSpec.BASKETBALL.withDrag(0);
        PhysicsWorld w = world(s);
        double r = s.radius();
        BallBody b = new BallBody(new V3(0, 2.0 + r, 0), V3.ZERO);
        double peak = 0;
        boolean bounced = false;
        for (int i = 0; i < 400; i++) {
            w.step(b, 0.01, null);
            if (b.vel().y() > 0.1) bounced = true;
            if (bounced) peak = Math.max(peak, b.pos().y() - r);
            if (bounced && b.vel().y() < 0) break;
        }
        assertEquals(2.0 * s.floorRestitution() * s.floorRestitution(), peak, 0.06);
    }

    @Test
    void ballComesToRestOnTheFloorAndStopsRolling() {
        BallSpec s = BallSpec.VOLLEYBALL;
        PhysicsWorld w = world(s);
        BallBody b = new BallBody(new V3(0, 3, 0), new V3(4, 0, 2));
        w.run(b, 25, 0.05, null);
        assertEquals(s.radius(), b.pos().y(), 0.005);
        assertTrue(b.vel().length() < 0.02, "piłka stoi: " + b.vel());
        assertTrue(b.onFloor());
    }

    @Test
    void floorContactsAreReportedWithTheImpactSpeed() {
        PhysicsWorld w = world(BallSpec.BASKETBALL);
        BallBody b = new BallBody(new V3(0, 3, 0), V3.ZERO);
        List<Double> impacts = new ArrayList<>();
        w.run(b, 3, 0.05, (c, impact, pos) -> {
            if (c.kind() == Contact.Kind.FLOOR) impacts.add(impact);
        });
        assertFalse(impacts.isEmpty());
        assertEquals(Math.sqrt(2 * 9.81 * 2.85), impacts.get(0), 0.5, "pierwsze uderzenie z wysokości ok. 2,85 m");
        for (int i = 1; i < impacts.size(); i++) assertTrue(impacts.get(i) < impacts.get(i - 1), "każde kolejne uderzenie jest słabsze");
    }

    @Test
    void anExtremelyFastBallNeverTunnelsThroughTheNet() {
        BallSpec s = BallSpec.VOLLEYBALL;
        double netU = 9;
        PhysicsWorld w = world(s).add(new Box(Contact.Kind.NET, new V3(netU - 0.02, 1.43, -0.5), new V3(netU + 0.02, 2.43, 9.5), 0.1, 0.5));
        for (double speed : new double[]{12, 20, 30, 45, 70}) {
            BallBody b = new BallBody(new V3(7, 2.0, 4.5), new V3(speed, 0, 0));
            boolean touched = false;
            for (int i = 0; i < 200; i++) {
                final boolean[] t = {false};
                w.step(b, 0.05, (c, impact, pos) -> t[0] = c.kind() == Contact.Kind.NET);
                touched |= t[0];
                assertTrue(b.pos().x() < netU, "piłka z prędkością " + speed + " przeniknęła przez siatkę (x=" + b.pos().x() + ")");
            }
            assertTrue(touched, "siatka powinna zgłosić uderzenie przy " + speed);
        }
    }

    @Test
    void aBallAboveTheNetPassesOver() {
        BallSpec s = BallSpec.VOLLEYBALL;
        double netU = 9;
        PhysicsWorld w = world(s).add(new Box(Contact.Kind.NET, new V3(netU - 0.02, 1.43, -0.5), new V3(netU + 0.02, 2.43, 9.5), 0.1, 0.5));
        BallBody b = new BallBody(new V3(6, 3.2, 4.5), new V3(9, 3, 0));
        w.run(b, 1.2, 0.05, null);
        assertTrue(b.pos().x() > netU + 2, "przeleciała nad siatką: " + b.pos());
    }

    @Test
    void ringLetsACenteredBallThroughAndBouncesAnOffCenterOne() {
        BallSpec s = BallSpec.BASKETBALL;
        double rimY = 3.05;
        Ring ring = new Ring(0, rimY, 0, 0.28, 0.02, 0.55, 0.15);
        // idealnie w środku: spada przez obręcz
        PhysicsWorld w = world(s).add(ring);
        BallBody center = new BallBody(new V3(0.02, rimY + 1.0, 0), V3.ZERO);
        boolean rim = false;
        double minY = 99;
        for (int i = 0; i < 100; i++) {
            final boolean[] hit = {false};
            w.step(center, 0.02, (c, im, p) -> hit[0] = c.kind() == Contact.Kind.RIM);
            rim |= hit[0];
            minY = Math.min(minY, center.pos().y());
        }
        assertFalse(rim, "piłka w środku nie dotyka obręczy");
        assertTrue(minY < rimY - 1, "spadła poniżej obręczy");

        // na krawędzi: odbija się od rurki i nie przechodzi przez środek
        BallBody edge = new BallBody(new V3(0.30, rimY + 0.8, 0), V3.ZERO);
        boolean edgeHit = false;
        for (int i = 0; i < 60; i++) {
            final boolean[] hit = {false};
            w.step(edge, 0.02, (c, im, p) -> hit[0] = c.kind() == Contact.Kind.RIM);
            edgeHit |= hit[0];
        }
        assertTrue(edgeHit, "uderzenie w obręcz");
        assertTrue(Math.abs(edge.vel().x()) > 0.3 || Math.abs(edge.pos().x()) > 0.4, "obręcz odrzuciła piłkę na bok: " + edge.pos() + " " + edge.vel());
    }

    @Test
    void backboardReflectsABallThatHitsIt() {
        BallSpec s = BallSpec.BASKETBALL;
        PhysicsWorld w = world(s).add(new Box(Contact.Kind.BOARD, new V3(-0.025, 2.9, -0.9), new V3(0.025, 3.95, 0.9), 0.65, 0.1));
        BallBody b = new BallBody(new V3(3, 3.4, 0), new V3(-12, 1.5, 0));
        w.run(b, 0.6, 0.02, null);
        assertTrue(b.vel().x() > 0, "po odbiciu od tablicy leci w drugą stronę: " + b.vel());
        assertTrue(b.pos().x() > 0, "nie przeszła przez tablicę");
    }

    @Test
    void aBallInsideABoxIsPushedOutThroughTheNearestFace() {
        Box box = new Box(Contact.Kind.WALL, new V3(0, 0, 0), new V3(1, 1, 1), 0.5, 0);
        Contact c = box.test(new V3(0.9, 0.5, 0.5), 0.1);
        assertEquals(new V3(1, 0, 0), c.normal());
        assertTrue(c.depth() > 0.1);
    }
}
