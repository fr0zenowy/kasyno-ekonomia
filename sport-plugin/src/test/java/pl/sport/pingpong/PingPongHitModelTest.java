package pl.sport.pingpong;

import org.junit.jupiter.api.Test;
import pl.sport.core.BallBody;
import pl.sport.core.BallSpec;
import pl.sport.core.Contact;
import pl.sport.core.PhysicsWorld;
import pl.sport.core.V3;
import pl.sport.pingpong.PingPongHitModel.Request;
import pl.sport.pingpong.PingPongHitModel.Stroke;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stół i model uderzenia: zgodność z fizyką piłeczki, przechodzenie nad siatką, lądowanie na połowie rywala i legalność serwisu. */
class PingPongHitModelTest {
    private static final BallSpec SPEC = BallSpec.PINGPONG;
    private final PingPongTable table = new PingPongTable(12, 8, 1.5);

    private record Flight(boolean net, boolean firstOnOpponent, V3 landing, double peak) {}

    /** Symuluje lot po uderzeniu: czy dotknęła siatki, czy pierwszy raz spadła na połowę rywala i jak wysoko doleciała. */
    private Flight fly(V3 from, V3 vel, boolean ownLow) {
        PhysicsWorld w = table.physics(SPEC);
        BallBody b = new BallBody(from, vel);
        boolean[] net = {false};
        V3[] first = {null};
        boolean[] opp = {false};
        double[] peak = {from.y()};
        PhysicsWorld.Listener l = (Contact c, double impact, V3 pos) -> {
            if (c.kind() == Contact.Kind.NET) net[0] = true;
            if (c.kind() == Contact.Kind.TABLE && c.normal().y() > 0.5 && first[0] == null) {
                first[0] = pos;
                opp[0] = table.lowSide(pos) != ownLow;
            }
            if (c.kind() == Contact.Kind.FLOOR && first[0] == null) first[0] = pos;
        };
        for (int i = 0; i < 100 && first[0] == null; i++) {
            w.step(b, 0.02, l);
            peak[0] = Math.max(peak[0], b.pos().y());
        }
        return new Flight(net[0], opp[0], first[0], peak[0]);
    }

    private Request req(Stroke s, V3 ball, V3 eye, V3 look, double q, double acc) {
        return new Request(s, ball, eye, look, true, q, acc);
    }

    /** Spojrzenie gracza z oczu `eye` w punkt `at`. */
    private V3 lookAt(V3 eye, V3 at) {
        return at.minus(eye).normalized();
    }

    private double successRate(Stroke stroke, double quality, double accuracy, int n, long seed) {
        Random rng = new Random(seed);
        int ok = 0;
        for (int i = 0; i < n; i++) {
            double v = 3.0 + rng.nextDouble() * 2.2;
            V3 ball = new V3(3.4 + rng.nextDouble() * 0.9, 1.1 + rng.nextDouble() * 0.5, v);
            V3 eye = new V3(ball.x() - 0.8, 1.62, ball.z());
            V3 look = lookAt(eye, new V3(7.0, table.topY(), 3.2 + rng.nextDouble() * 1.6));
            PingPongHitModel.Hit h = PingPongHitModel.compute(table, SPEC, req(stroke, ball, eye, look, quality, accuracy), rng);
            Flight f = fly(ball, h.velocity(), true);
            if (!f.net() && f.firstOnOpponent()) ok++;
        }
        return 100.0 * ok / n;
    }

    @Test
    void theTableHasTheRealProportionsTimesTheScale() {
        assertEquals(2.74 * 1.5, table.tableLength(), 1e-9);
        assertEquals(1.525 * 1.5, table.tableWidth(), 1e-9);
        assertEquals(0.76 * 1.5, table.topY(), 1e-9);
        assertEquals(0.1525 * 1.5, table.netHeight(), 1e-9);
        assertEquals(6.0, table.netU(), 1e-9);
        assertEquals((12 - 4.11) / 2, table.u0(), 1e-9);
        assertEquals(1.5, PingPongTable.scaleOfNet(0.1525 * 1.5), 1e-9);
        assertTrue(table.insideTableFootprint(new V3(6, 0, 4)));
        assertTrue(!table.insideTableFootprint(new V3(1, 0, 4)));
    }

    @Test
    void aBallDroppedOnTheTableBouncesBackAboutAsHighAsRealOnes() {
        PhysicsWorld w = table.physics(SPEC);
        BallBody b = new BallBody(new V3(5, table.topY() + 0.3, 4), V3.ZERO);
        boolean[] bounced = {false};
        double peak = 0;
        for (int i = 0; i < 100; i++) {
            w.step(b, 0.01, (c, impact, pos) -> {
                if (c.kind() == Contact.Kind.TABLE && c.normal().y() > 0.5) bounced[0] = true;
            });
            if (bounced[0]) peak = Math.max(peak, b.pos().y() - table.topY());
            if (b.vel().y() < 0 && bounced[0] && peak > 0.05) break;
        }
        assertTrue(bounced[0]);
        assertTrue(peak > 0.18 && peak < 0.29, "odbicie z 30 cm: " + peak);
    }

    @Test
    void aBallRollingIntoTheNetIsStoppedByIt() {
        PhysicsWorld w = table.physics(SPEC);
        BallBody b = new BallBody(new V3(5.5, table.topY() + 0.12, 4), new V3(3, 0, 0));
        boolean[] net = {false};
        for (int i = 0; i < 40; i++) {
            w.step(b, 0.02, (c, impact, pos) -> {
                if (c.kind() == Contact.Kind.NET) net[0] = true;
            });
        }
        assertTrue(net[0]);
        assertTrue(b.pos().x() < 6.0, "piłeczka nie przeszła przez siatkę: " + b.pos());
    }

    @Test
    void aimingAtASpotOnTheTableSendsTheBallThere() {
        V3 eye = new V3(2.9, 1.62, 4.0);
        V3 ball = new V3(3.7, 1.3, 4.0);
        V3 spot = new V3(7.2, table.topY(), 3.4);
        V3 aim = PingPongHitModel.aimPoint(table, SPEC, req(Stroke.DRIVE, ball, eye, lookAt(eye, spot), 1, 1));
        assertEquals(7.2, aim.x(), 0.05);
        assertEquals(3.4, aim.z(), 0.05);
        assertEquals(table.topY() + SPEC.radius(), aim.y(), 1e-9);
    }

    @Test
    void lookingAtTheOpponentWithoutAimingAtTheTableUsesTheDefaultDepth() {
        V3 eye = new V3(2.9, 1.62, 4.0);
        V3 ball = new V3(3.7, 1.3, 4.0);
        V3 aim = PingPongHitModel.aimPoint(table, SPEC, req(Stroke.DRIVE, ball, eye, new V3(1, 0, 0), 1, 1));
        assertTrue(aim.x() > table.netU() + 0.5 && aim.x() < table.u1(), "w połowie rywala: " + aim);
        assertEquals(4.0, aim.z(), 0.01);
        // patrzenie w tył też kończy się strzałem na połowę rywala
        V3 back = PingPongHitModel.aimPoint(table, SPEC, req(Stroke.DRIVE, ball, eye, new V3(-1, 0, 0), 1, 1));
        assertTrue(back.x() > table.netU());
        // cel poza stołem jest ściągany na blat
        V3 wide = PingPongHitModel.aimPoint(table, SPEC, req(Stroke.DRIVE, ball, eye, new V3(0.3, 0, 1).normalized(), 1, 1));
        assertTrue(wide.z() <= table.v1() && wide.z() >= table.v0(), "cel na blacie: " + wide);
    }

    @Test
    void goodDrivesClearTheNetAndLandOnTheOpponentsHalf() {
        double good = successRate(Stroke.DRIVE, 0.85, 1.0, 400, 1);
        double poor = successRate(Stroke.DRIVE, 0.0, 1.0, 400, 2);
        System.out.printf("PING-PONG odbicia: dobre %.0f%%, słabe %.0f%%%n", good, poor);
        assertTrue(good > 88, "dobre odbicie: " + good);
        assertTrue(poor > 45 && poor < good - 8, "słabe odbicie jest wyraźnie gorsze, ale nie beznadziejne: " + poor);
    }

    @Test
    void accuracyFromTheConfigMakesShotsMoreOrLessReliable() {
        double sloppy = successRate(Stroke.DRIVE, 0.3, 0.5, 400, 3);
        double precise = successRate(Stroke.DRIVE, 0.3, 2.0, 400, 3);
        System.out.printf("PING-PONG celność: 0,5 -> %.0f%%, 2,0 -> %.0f%%%n", sloppy, precise);
        assertTrue(precise > sloppy + 10, precise + " vs " + sloppy);
    }

    @Test
    void pushesArePlayedHigherAndSlowerThanDrivesAndSmashesFaster() {
        Random rng = new Random(7);
        V3 eye = new V3(2.9, 1.62, 4.0);
        V3 ball = new V3(3.7, 1.4, 4.0);
        V3 look = lookAt(eye, new V3(7.2, table.topY(), 4.0));
        double driveSpeed = 0;
        double pushSpeed = 0;
        double smashSpeed = 0;
        double drivePeak = 0;
        double pushPeak = 0;
        int n = 60;
        for (int i = 0; i < n; i++) {
            PingPongHitModel.Hit d = PingPongHitModel.compute(table, SPEC, req(Stroke.DRIVE, ball, eye, look, 1, 1), rng);
            PingPongHitModel.Hit p = PingPongHitModel.compute(table, SPEC, req(Stroke.PUSH, ball, eye, look, 1, 1), rng);
            PingPongHitModel.Hit s = PingPongHitModel.compute(table, SPEC, req(Stroke.SMASH, new V3(3.7, 1.9, 4.0), eye, look, 1, 1), rng);
            driveSpeed += d.velocity().length();
            pushSpeed += p.velocity().length();
            smashSpeed += s.velocity().length();
            drivePeak += fly(ball, d.velocity(), true).peak();
            pushPeak += fly(ball, p.velocity(), true).peak();
        }
        System.out.printf("PING-PONG uderzenia: odbicie %.1f m/s (zenit %.2f), podcięcie %.1f m/s (zenit %.2f), smecz %.1f m/s%n", driveSpeed / n, drivePeak / n, pushSpeed / n, pushPeak / n, smashSpeed / n);
        assertTrue(pushSpeed < driveSpeed, "podcięcie wolniejsze");
        assertTrue(pushPeak > drivePeak + 0.05, "podcięcie wyżej");
        assertTrue(smashSpeed > driveSpeed + 2, "smecz szybszy");
    }

    @Test
    void servesBounceOnTheServersHalfFirstAndAlmostAlwaysArriveOnTheOpponentsHalf() {
        Random rng = new Random(11);
        int ok = 0;
        int n = 120;
        for (int i = 0; i < n; i++) {
            double v = 3.2 + rng.nextDouble() * 1.6;
            V3 ball = new V3(table.u0() - 0.4, 1.4 + rng.nextDouble() * 0.3, v); // serwis uderzany co najmniej 20 cm nad blatem
            V3 eye = new V3(table.u0() - 0.9, 1.62, v);
            V3 look = lookAt(eye, new V3(7.3, table.topY(), 3.2 + rng.nextDouble() * 1.6));
            PingPongHitModel.Hit h = PingPongHitModel.compute(table, SPEC, req(Stroke.SERVE, ball, eye, look, 0.85, 1.0), rng);
            PhysicsWorld w = table.physics(SPEC);
            BallBody b = new BallBody(ball, h.velocity());
            int[] bounces = {0};
            boolean[] legal = {true};
            boolean[] net = {false};
            for (int s = 0; s < 150 && bounces[0] < 2 && legal[0]; s++) {
                w.step(b, 0.02, (c, impact, pos) -> {
                    if (c.kind() == Contact.Kind.NET) net[0] = true;
                    if (c.kind() == Contact.Kind.FLOOR) legal[0] = false;
                    if (c.kind() == Contact.Kind.TABLE && c.normal().y() > 0.5) {
                        bounces[0]++;
                        boolean onLow = table.lowSide(pos);
                        if (bounces[0] == 1 && !onLow) legal[0] = false;
                        if (bounces[0] == 2 && onLow) legal[0] = false;
                    }
                });
            }
            if (legal[0] && bounces[0] == 2 && !net[0]) ok++;
        }
        double rate = 100.0 * ok / n;
        System.out.printf("PING-PONG serwisy: %.0f%% poprawnych%n", rate);
        assertTrue(rate > 90, "serwisy: " + rate);
    }
}
