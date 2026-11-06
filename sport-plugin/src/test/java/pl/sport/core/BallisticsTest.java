package pl.sport.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rozwiązywanie rzutów: wyznaczona prędkość i kąt naprawdę trafiają w cel, także z oporem powietrza. */
class BallisticsTest {
    private static final BallSpec BASKET = BallSpec.BASKETBALL;
    private static final BallSpec VOLLEY = BallSpec.VOLLEYBALL;

    @Test
    void noDragFlightMatchesTheTextbookRange() {
        BallSpec s = BASKET.withDrag(0);
        double v = 10;
        double range = v * v * Math.sin(Math.toRadians(90)) / 9.81; // 45 stopni: zasięg v²/g
        Ballistics.Flight f = Ballistics.flight(s, 0, v, 45, range);
        assertTrue(f.reached());
        assertEquals(0, f.height(), 0.02);
        assertEquals(2 * v * Math.sin(Math.toRadians(45)) / 9.81, f.time(), 0.01);
    }

    @Test
    void solvedSpeedHitsTheHoopFromAnyDistance() {
        for (double dist = 1.5; dist <= 11; dist += 0.5) {
            for (double angle : new double[]{45, 52, 60, 68}) {
                Ballistics.Aim a = Ballistics.solveSpeed(BASKET, 2.1, dist, 3.05, angle, 30);
                if (a == null) continue;
                Ballistics.Flight f = Ballistics.flight(BASKET, 2.1, a.speed(), a.angleDeg(), dist);
                assertEquals(3.05, f.height(), 0.01, "dist=" + dist + " angle=" + angle);
            }
        }
        // z bliska i średnio da się trafić przy każdym z tych kątów
        assertNotNull(Ballistics.solveSpeed(BASKET, 2.1, 5, 3.05, 55, 30));
    }

    @Test
    void anImpossibleShotIsReportedAsNull() {
        assertNull(Ballistics.solveSpeed(BASKET, 2.1, 40, 3.05, 50, 20), "40 m z prędkością 20 m/s się nie uda");
    }

    @Test
    void solvedAngleHitsTheTargetOnBothTheFlatAndTheHighArc() {
        for (boolean high : new boolean[]{false, true}) {
            Ballistics.Aim a = Ballistics.solveAngle(VOLLEY, 2.6, 9, 0.1, 17, high);
            assertTrue(a.exact(), "cel w zasięgu, high=" + high);
            Ballistics.Flight f = Ballistics.flight(VOLLEY, 2.6, a.speed(), a.angleDeg(), 9);
            assertEquals(0.1, f.height(), 0.02);
        }
        Ballistics.Aim flat = Ballistics.solveAngle(VOLLEY, 2.6, 9, 0.1, 17, false);
        Ballistics.Aim lob = Ballistics.solveAngle(VOLLEY, 2.6, 9, 0.1, 17, true);
        assertTrue(lob.angleDeg() > flat.angleDeg());
    }

    @Test
    void aTargetOutOfRangeLandsAsFarAsPossible() {
        Ballistics.Aim a = Ballistics.solveAngle(VOLLEY, 2.0, 60, 0.1, 10, false);
        assertFalse(a.exact());
        assertTrue(a.angleDeg() > 15 && a.angleDeg() < 60, "kąt maksymalnego zasięgu: " + a.angleDeg());
    }

    @Test
    void aServeIsAimedOverTheNetAndLandsOnTheTarget() {
        // zagrywka z linii końcowej (u=0) na punkt 4 m za siatką (u=13): siatka w u=9 na 2,43 m
        double[] heights = {2.2, 2.6, 3.0};
        for (double h0 : heights) {
            Ballistics.Aim a = Ballistics.solveClearing(VOLLEY, h0, 13, 0.13, 9, 2.43 + 0.3, 11, 24);
            assertTrue(a.exact(), "znaleziono rzut dla h0=" + h0);
            Ballistics.Flight over = Ballistics.flight(VOLLEY, h0, a.speed(), a.angleDeg(), 9);
            assertTrue(over.height() >= 2.43 + 0.3 - 0.01, "nad siatką na " + over.height());
            Ballistics.Flight land = Ballistics.flight(VOLLEY, h0, a.speed(), a.angleDeg(), 13);
            assertEquals(0.13, land.height(), 0.03);
        }
    }

    @Test
    void whenNothingClearsTheObstacleTheBestEffortIsFlaggedAsInexact() {
        // cel tuż za bardzo wysoką przeszkodą, której nie da się przeskoczyć przy tej prędkości
        Ballistics.Aim a = Ballistics.solveClearing(VOLLEY, 1.0, 6, 0.1, 5.5, 9.0, 5, 10);
        assertFalse(a.exact());
    }

    @Test
    void velocityPointsTowardsTheRequestedDirection() {
        Ballistics.Aim a = new Ballistics.Aim(10, 30, true);
        V3 v = a.velocity(new V3(0, 5, 2)); // składowa pionowa kierunku jest ignorowana
        assertEquals(10, v.length(), 1e-9);
        assertEquals(10 * Math.sin(Math.toRadians(30)), v.y(), 1e-9);
        assertEquals(0, v.x(), 1e-9);
        assertTrue(v.z() > 0);
    }
}
