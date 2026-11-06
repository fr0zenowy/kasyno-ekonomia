package pl.kasyno.shark;

import org.bukkit.Material;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import pl.kasyno.shark.SharkModel.Orientation;
import pl.kasyno.shark.SharkModel.Part;
import pl.kasyno.shark.SharkModel.Pose;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zasady, model i scenariusz ataku rekina bez serwera. */
class SharkLogicTest {
    private static final Orientation FLAT = new Orientation(0, 0, 0);
    private static final Pose REST = new Pose(0, 0, 0);

    private static Part part(String id) {
        return SharkModel.PARTS.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    private static Vector3f center(String id, Pose pose, Orientation o) {
        return SharkModel.centerOf(SharkModel.xform(part(id), pose, o));
    }

    // ---------- zasady ----------

    @Test
    void chanceIsZeroNearLandAndGrowsLinearlyWithDistance() {
        SharkRules.Config c = new SharkRules.Config(true, 20, 120, 0.4, 4, 3, 100, 120, 0.5);
        assertEquals(0, SharkRules.chance(0, c));
        assertEquals(0, SharkRules.chance(20, c));
        assertEquals(0.2, SharkRules.chance(70, c), 1e-9);
        assertEquals(0.4, SharkRules.chance(120, c), 1e-9);
        assertEquals(0.4, SharkRules.chance(5000, c), 1e-9, "dalej szansa już nie rośnie");
        double prev = -1;
        for (int d = 0; d <= 200; d += 5) {
            double p = SharkRules.chance(d, c);
            assertTrue(p >= prev, "szansa nie maleje z odległością");
            prev = p;
        }
    }

    @Test
    void theLongerYouSwimTheMoreLikelyAnAttackBecomes() {
        SharkRules.Config c = SharkRules.Config.defaults();
        double near = SharkRules.chanceOver(40, 8, c);
        double far = SharkRules.chanceOver(120, 8, c);
        assertTrue(far > near);
        assertTrue(SharkRules.chanceOver(120, 16, c) > far, "dłuższe pływanie to więcej losowań");
        assertEquals(0, SharkRules.chanceOver(10, 100, c), 1e-12);
    }

    @Test
    void theSharkTakesTheConfiguredShareOfTheWalletRoundedDown() {
        assertEquals(500, SharkRules.loss(1000, 0.5));
        assertEquals(499, SharkRules.loss(999, 0.5));
        assertEquals(0, SharkRules.loss(0, 0.5));
        assertEquals(0, SharkRules.loss(-10, 0.5));
        assertEquals(1000, SharkRules.loss(1000, 5), "ułamek większy niż 1 zabiera najwyżej wszystko");
        assertEquals(0, SharkRules.loss(1000, -1));
    }

    @Test
    void badConfigurationIsClampedToSaneValues() {
        SharkRules.Config c = new SharkRules.Config(true, -5, -10, 7, 0, 0, 1, -3, 9).sane();
        assertEquals(0, c.minDistance());
        assertTrue(c.maxDistance() > c.minDistance());
        assertEquals(1.0, c.maxChance());
        assertEquals(1, c.checkSeconds());
        assertEquals(1, c.minDepth());
        assertEquals(40, c.warningTicks());
        assertEquals(0, c.cooldownSeconds());
        assertEquals(1.0, c.lossFraction());
    }

    // ---------- model ----------

    @Test
    void theModelHasUniqueNamedPartsWithRealBlocks() {
        Set<String> ids = new HashSet<>();
        for (Part p : SharkModel.PARTS) {
            assertTrue(ids.add(p.id()), "powtórzona nazwa " + p.id());
            assertNotNull(Material.matchMaterial(p.block()), p.block());
            assertTrue(p.sx() > 0 && p.sy() > 0 && p.sz() > 0, p.id());
        }
        assertTrue(SharkModel.PARTS.size() >= 14);
    }

    @Test
    void atRestEveryPartStandsExactlyWhereItWasDescribed() {
        for (Part p : SharkModel.PARTS) {
            Vector3f c = center(p.id(), REST, FLAT);
            assertEquals(p.cx(), c.x, 1e-4, p.id());
            assertEquals(p.cy(), c.y, 1e-4, p.id());
            assertEquals(p.cz(), c.z, 1e-4, p.id());
        }
    }

    @Test
    void turningTheSharkMovesTheNoseTheRightWay() {
        Vector3f south = SharkModel.mouthOffset(new Orientation(0, 0, 0));
        assertEquals(2.75, south.z, 1e-4, "yaw 0 to nos na południe (+z)");
        Vector3f east = SharkModel.mouthOffset(new Orientation(90, 0, 0));
        assertEquals(2.75, east.x, 1e-4, "yaw 90 kieruje nos na +x");
        assertEquals(0, east.z, 1e-4);
        Vector3f up = SharkModel.mouthOffset(new Orientation(0, 40, 0));
        assertTrue(up.y > 1.5, "dodatnie nachylenie unosi nos: " + up.y);
        Vector3f down = SharkModel.mouthOffset(new Orientation(0, -40, 0));
        assertTrue(down.y < -1.5);
    }

    @Test
    void theWholeSharkRotatesRigidly() {
        Orientation o = new Orientation(137, 25, 10);
        Vector3f tail = center("ster_gorny", REST, o);
        Vector3f nose = center("pysk", REST, o);
        Vector3f restTail = center("ster_gorny", REST, FLAT);
        Vector3f restNose = center("pysk", REST, FLAT);
        assertEquals(restTail.distance(restNose), tail.distance(nose), 1e-4, "odległość nos-ogon się nie zmienia");
        assertEquals(restTail.length(), tail.length(), 1e-4);
    }

    @Test
    void theTailWavesFromSideToSideAndOtherPartsStayPut() {
        Pose left = new Pose(Math.PI / 2, 20, 0);
        Pose right = new Pose(-Math.PI / 2, 20, 0);
        Vector3f a = center("ster_gorny", left, FLAT);
        Vector3f b = center("ster_gorny", right, FLAT);
        assertTrue(Math.abs(a.x) > 0.3, "ogon wychyla się: " + a.x);
        assertEquals(-a.x, b.x, 1e-3, "w przeciwną stronę o tyle samo");
        assertEquals(0, center("ster_gorny", new Pose(1.3, 0, 0), FLAT).x, 1e-4, "bez rozmachu nie macha");
        for (String stays : new String[]{"grzbiet", "brzuch", "glowa", "pletwa_grzbietowa"}) {
            Vector3f rest = center(stays, REST, FLAT);
            Vector3f moved = center(stays, left, FLAT);
            assertEquals(0, rest.distance(moved), 1e-4, stays + " nie rusza się razem z ogonem");
        }
        Vector3f t1 = center("ogon1", left, FLAT);
        Vector3f t2 = center("ogon2", left, FLAT);
        Vector3f fin = center("ster_gorny", left, FLAT);
        assertTrue(Math.abs(fin.x) > Math.abs(t2.x) && Math.abs(t2.x) > Math.abs(t1.x), "im dalej od ciała, tym większe wychylenie");
    }

    @Test
    void theJawOpensDownwardAndDragsTheMouthWithIt() {
        Vector3f closed = center("szczeka", REST, FLAT);
        Vector3f open = center("szczeka", new Pose(0, 0, 55), FLAT);
        assertTrue(open.y < closed.y - 0.3, "szczęka opada: " + closed.y + " -> " + open.y);
        assertTrue(open.z < closed.z, "i cofa się na zawiasie");
        Vector3f redClosed = center("paszcza", REST, FLAT);
        Vector3f redOpen = center("paszcza", new Pose(0, 0, 55), FLAT);
        assertTrue(redOpen.y < redClosed.y - 0.3, "czerwone wnętrze porusza się ze szczęką");
        assertEquals(0, center("glowa", REST, FLAT).distance(center("glowa", new Pose(0, 0, 55), FLAT)), 1e-4, "górna część głowy stoi");
    }

    // ---------- scenariusz ----------

    private record Run(int finishedAt, int breachAt, int biteAt, int killAt, int breaches, int bites, int kills) {}

    private static Run play(SharkChoreo ch, double[] target) {
        int finishedAt = -1;
        int breachAt = -1;
        int biteAt = -1;
        int killAt = -1;
        int breaches = 0;
        int bites = 0;
        int kills = 0;
        for (int i = 1; i <= 400 && finishedAt < 0; i++) {
            SharkChoreo.Step s = ch.step(target[0], target[1], target[2]);
            if (s.breach()) {
                breaches++;
                breachAt = i;
            }
            if (s.bite()) {
                bites++;
                biteAt = i;
            }
            if (s.kill()) {
                kills++;
                killAt = i;
            }
            if (s.finished()) finishedAt = i;
        }
        return new Run(finishedAt, breachAt, biteAt, killAt, breaches, bites, kills);
    }

    @Test
    void theAttackRunsApproachStrikeBiteAndDiveEachEventOnce() {
        SharkChoreo ch = new SharkChoreo(100, 64.89, new Random(1));
        Run r = play(ch, new double[]{0, 64, 0});
        int total = 100 + SharkChoreo.STRIKE_TICKS + SharkChoreo.BITE_TICKS + SharkChoreo.DIVE_TICKS;
        assertEquals(total, r.finishedAt(), "łącznie tyle ticków");
        assertEquals(1, r.breaches());
        assertEquals(1, r.bites());
        assertEquals(1, r.kills());
        assertEquals(100 + SharkChoreo.STRIKE_TICKS, r.biteAt(), "chwyt dokładnie na końcu rzutu");
        assertEquals(100 + SharkChoreo.STRIKE_TICKS + SharkChoreo.KILL_TICK + 1, r.killAt());
        assertTrue(r.breachAt() > 100 && r.breachAt() < r.biteAt());
        assertEquals(SharkChoreo.Phase.DONE, ch.phase());
    }

    @Test
    void theFinCirclesInwardAtTheSurfaceWithoutJumping() {
        SharkChoreo ch = new SharkChoreo(100, 64.89, new Random(2));
        double[] target = {10, 64, -5};
        SharkChoreo.Frame prev = null;
        double firstDist = -1;
        double lastDist = -1;
        for (int i = 0; i < 100; i++) {
            SharkChoreo.Step s = ch.step(target[0], target[1], target[2]);
            SharkChoreo.Frame f = s.frame();
            assertEquals(SharkChoreo.Phase.APPROACH, f.phase());
            assertEquals(64.89 - SharkChoreo.CRUISE_DEPTH, f.y(), 1e-9, "korpus pływa tuż pod lustrem wody");
            assertEquals(0, f.pose().jawOpenDeg(), "paszcza zamknięta");
            double dist = Math.hypot(f.x() - target[0], f.z() - target[2]);
            if (i == 0) firstDist = dist;
            lastDist = dist;
            if (prev != null) assertTrue(Math.hypot(f.x() - prev.x(), f.z() - prev.z()) < 0.6, "ruch jest ciągły, tick " + i);
            prev = f;
        }
        assertTrue(firstDist > 14 && firstDist < 18, "zaczyna daleko: " + firstDist);
        assertTrue(lastDist > 4.5 && lastDist < 8, "kończy blisko gracza: " + lastDist);
    }

    @Test
    void theNoseFollowsTheDirectionOfSwimming() {
        SharkChoreo ch = new SharkChoreo(100, 64.89, new Random(3));
        SharkChoreo.Frame prev = ch.step(0, 64, 0).frame();
        for (int i = 0; i < 60; i++) {
            SharkChoreo.Frame f = ch.step(0, 64, 0).frame();
            double dx = f.x() - prev.x();
            double dz = f.z() - prev.z();
            double yaw = Math.toRadians(f.orientation().yawDeg());
            double dot = (Math.sin(yaw) * dx + Math.cos(yaw) * dz) / Math.max(1e-9, Math.hypot(dx, dz));
            assertTrue(dot > 0.95, "rekin płynie nosem do przodu, tick " + i + " (" + dot + ")");
            prev = f;
        }
    }

    @Test
    void theStrikeEndsWithTheMouthOnThePlayerAndTheJawOpen() {
        for (long seed = 0; seed < 25; seed++) {
            SharkChoreo ch = new SharkChoreo(100, 64.89, new Random(seed));
            double[] target = {20, 64, 30};
            SharkChoreo.Frame atBite = null;
            for (int i = 0; i < 100 + SharkChoreo.STRIKE_TICKS; i++) {
                SharkChoreo.Step s = ch.step(target[0], target[1], target[2]);
                if (s.bite()) atBite = s.frame();
            }
            assertNotNull(atBite);
            Vector3f mouth = SharkChoreo.mouth(atBite);
            assertEquals(target[0], mouth.x, 0.3, "ziarno " + seed);
            assertEquals(target[2], mouth.z, 0.3, "ziarno " + seed);
            assertEquals(64 + SharkChoreo.TORSO, mouth.y, 0.3, "pysk jest na wysokości pasa gracza");
            assertTrue(atBite.pose().jawOpenDeg() > 50, "paszcza otwarta przy chwycie");
            assertTrue(atBite.orientation().pitchDeg() > 30, "nos w górę");
        }
    }

    @Test
    void theStrikeIsContinuousAndCatchesAMovingTarget() {
        SharkChoreo ch = new SharkChoreo(100, 64.89, new Random(5));
        double x = 0;
        SharkChoreo.Frame prev = null;
        SharkChoreo.Frame atBite = null;
        for (int i = 0; i < 100 + SharkChoreo.STRIKE_TICKS; i++) {
            if (i >= 100) x += 0.2; // gracz próbuje uciec w trakcie rzutu
            SharkChoreo.Step s = ch.step(x, 64, 0);
            if (prev != null) {
                double jump = Math.sqrt(Math.pow(s.frame().x() - prev.x(), 2) + Math.pow(s.frame().y() - prev.y(), 2) + Math.pow(s.frame().z() - prev.z(), 2));
                assertTrue(jump < 2.6, "rekin nie teleportuje się: " + jump + " w ticku " + i);
            }
            prev = s.frame();
            if (s.bite()) atBite = s.frame();
        }
        Vector3f mouth = SharkChoreo.mouth(atBite);
        assertEquals(x, mouth.x, 1.4, "rzut dogania uciekającego gracza");
    }

    @Test
    void theBiteHoldsTheVictimShakesAndThenTheSharkDives() {
        SharkChoreo ch = new SharkChoreo(100, 64.89, new Random(7));
        double[] target = {0, 64, 0};
        for (int i = 0; i < 100 + SharkChoreo.STRIKE_TICKS; i++) ch.step(target[0], target[1], target[2]);
        double minRoll = 0;
        double maxRoll = 0;
        double yStart = ch.last().y();
        for (int i = 0; i < SharkChoreo.BITE_TICKS; i++) {
            SharkChoreo.Step s = ch.step(target[0], target[1], target[2]);
            assertEquals(SharkChoreo.Phase.BITE, s.frame().phase());
            if (i >= 3) assertTrue(s.frame().pose().jawOpenDeg() < 14, "zacisnięta szczęka w chwili " + i);
            minRoll = Math.min(minRoll, s.frame().orientation().rollDeg());
            maxRoll = Math.max(maxRoll, s.frame().orientation().rollDeg());
        }
        assertTrue(maxRoll - minRoll > 10, "rekin trzęsie ofiarą");
        assertTrue(ch.last().y() < yStart, "i powoli opada w głąb");
        double depthAtEnd = ch.last().y();
        for (int i = 0; i < SharkChoreo.DIVE_TICKS; i++) {
            SharkChoreo.Step s = ch.step(target[0], target[1], target[2]);
            assertEquals(0, s.frame().pose().jawOpenDeg());
            if (i == SharkChoreo.DIVE_TICKS - 1) {
                assertTrue(s.finished());
                assertTrue(s.frame().y() < depthAtEnd - 1.5, "odpływa w dół");
                assertTrue(Math.hypot(s.frame().x(), s.frame().z()) > 5, "i od miejsca ataku");
            }
        }
    }

    @Test
    void abortingStopsTheShowAtOnce() {
        SharkChoreo ch = new SharkChoreo(100, 64.89, new Random(9));
        for (int i = 0; i < 30; i++) ch.step(0, 64, 0);
        ch.abort();
        assertEquals(SharkChoreo.Phase.DONE, ch.phase());
        SharkChoreo.Step s = ch.step(0, 64, 0);
        assertTrue(s.finished());
        assertFalse(s.kill() || s.bite() || s.breach());
    }

    @Test
    void thePreviewOrbitsAtAFixedRadiusAndOpensTheJawInTurns() {
        boolean closed = false;
        boolean open = false;
        for (int t = 0; t < 120; t++) {
            SharkChoreo.Frame f = SharkChoreo.demo(t, 5, 70, -3, 4);
            assertEquals(4, Math.hypot(f.x() - 5, f.z() + 3), 1e-6);
            assertEquals(70, f.y(), 1e-9);
            if (f.pose().jawOpenDeg() == 0) closed = true;
            if (f.pose().jawOpenDeg() > 40) open = true;
        }
        assertTrue(closed && open);
    }
}
