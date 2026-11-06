package pl.kasyno.waypoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Punkt odniesienia napisów waypointów: nie skacze ze skokami gracza, wygładza schodki i nierówny ruch, a po zatrzymaniu zbiega łagodnie. Bez serwera. */
class ViewAnchorTest {
    private static final double EPS = 1e-9;

    /** Stałe opóźnienie punktu za graczem w marszu ze stałą prędkością (w tickach): opóźnienie filtra minus wyprzedzenie. */
    private static final double LAG_TICKS = (1 - ViewAnchor.POSITION_SMOOTHING) / ViewAnchor.POSITION_SMOOTHING - ViewAnchor.LOOKAHEAD;

    @Test
    void theFirstSampleIsJustThePlayersFeetWithStandingEyeHeight() {
        ViewAnchor a = new ViewAnchor();
        a.update("world", 10, 64, 20, true);
        assertEquals(10, a.x(), EPS);
        assertEquals(64, a.feetY(), EPS);
        assertEquals(20, a.z(), EPS);
        assertEquals(64 + ViewAnchor.EYE_HEIGHT, a.eyeY(), EPS);
    }

    @Test
    void aJumpDoesNotMoveTheAnchorAndLandingBringsNothingBack() {
        ViewAnchor a = new ViewAnchor();
        a.update("world", 0, 64, 0, true);
        for (double up : new double[]{0.42, 0.75, 1.0, 1.25, 1.1, 0.8, 0.4}) {
            a.update("world", 0, 64 + up, 0, false);
            assertEquals(64, a.feetY(), EPS, "w skoku (" + up + ") poziom stoi");
        }
        a.update("world", 0, 64, 0, true);
        assertEquals(64, a.feetY(), EPS);
        // kucanie i podskok w miejscu przez wiele ticków: nic się nie rusza
        for (int i = 0; i < 40; i++) a.update("world", 0, 64 + (i % 5) * 0.3, 0, i % 5 == 0);
        assertEquals(64, a.feetY(), 0.02);
    }

    @Test
    void aStepOntoAHigherBlockSlidesTheAnchorSmoothlyInsteadOfJumping() {
        ViewAnchor a = new ViewAnchor();
        a.update("world", 0, 64, 0, true);
        a.update("world", 0, 65, 0, true); // schodek
        assertEquals(64 + ViewAnchor.LEVEL_SMOOTHING, a.feetY(), EPS, "pierwszy krok to tylko część różnicy");
        double prev = a.feetY();
        for (int i = 0; i < 40; i++) {
            a.update("world", 0, 65, 0, true);
            assertTrue(a.feetY() >= prev - EPS && a.feetY() <= 65 + EPS, "bez cofania i przestrzelenia");
            prev = a.feetY();
        }
        assertEquals(65, a.feetY(), EPS, "w końcu dokładnie na nowym poziomie");
    }

    @Test
    void bigLevelChangesSnapAtOnce() {
        ViewAnchor a = new ViewAnchor();
        a.update("world", 0, 64, 0, true);
        a.update("world", 0, 70, 0, false); // spadek albo winda: ponad 3 bloki, nawet w powietrzu
        assertEquals(70, a.feetY(), EPS);
        a.update("world", 0, 20, 0, true); // długi spadek
        assertEquals(20, a.feetY(), EPS);
        a.update("world", 0, 23.5, 0, false);
        assertEquals(23.5, a.feetY(), EPS);
        a.update("world", 0, 25, 0, false);
        assertEquals(23.5, a.feetY(), EPS, "poniżej progu i bez podłoża nic się nie dzieje");
    }

    @Test
    void walkingAtASteadySpeedKeepsAConstantTinyDistanceBehindThePlayer() {
        ViewAnchor a = new ViewAnchor();
        double speed = 0.22;
        double z = 0;
        a.update("world", 0, 64, z, true);
        for (int i = 0; i < 150; i++) {
            z += speed;
            a.update("world", 0, 64, z, true);
        }
        assertEquals(z - speed * LAG_TICKS, a.z(), 1e-3, "po rozbiegu stały, niewielki odstęp");
        assertTrue(speed * LAG_TICKS < 0.3, "odstęp jest mały: " + speed * LAG_TICKS + " bloku przy 4,4 bloku na sekundę");
        assertEquals(0, a.x(), EPS);
        double before = a.z();
        z += speed;
        a.update("world", 0, 64, z, true);
        assertEquals(speed, a.z() - before, 5e-3, "i porusza się równo z graczem");
    }

    @Test
    void whenYouStopTheAnchorSettlesOnYouWithoutOvershootingOrSnapping() {
        ViewAnchor a = new ViewAnchor();
        double z = 0;
        a.update("world", 0, 64, z, true);
        for (int i = 0; i < 80; i++) {
            z += 0.25;
            a.update("world", 0, 64, z, true);
        }
        double gap = z - a.z();
        assertTrue(gap > 0 && gap < 0.4, "w biegu punkt jest tuż za graczem: " + gap);
        double prevGap = gap;
        for (int i = 0; i < 80; i++) {
            a.update("world", 0, 64, z, true);
            double now = z - a.z();
            assertTrue(now >= -0.02, "bez przestrzelenia przed gracza: " + now);
            assertTrue(Math.abs(now) <= prevGap + 2e-3, "i tylko się zbliża (" + prevGap + " -> " + now + "; przestrzelenie poniżej 2 mm to szum)");
            assertTrue(Math.abs(prevGap - now) <= gap + 1e-6, "bez skoku większego niż cały odstęp");
            prevGap = Math.abs(now);
        }
        assertEquals(z, a.z(), 1e-3, "po zatrzymaniu punkt jest dokładnie pod graczem");
    }

    @Test
    void unevenStepsAreSmoothedSoTheAnchorJerksAboutHalfAsMuch() {
        double[] steps = {0.10, 0.30, 0.12, 0.28, 0.15, 0.25, 0.10, 0.30, 0.18, 0.22, 0.30, 0.10};
        double mean = 0;
        for (double s : steps) mean += s / steps.length;
        ViewAnchor a = new ViewAnchor();
        double z = 0;
        a.update("world", 0, 64, z, true);
        double prevAnchor = a.z();
        double maxRaw = 0;
        double maxAnchor = 0;
        for (int round = 0; round < 8; round++) {
            for (double s : steps) {
                z += s;
                a.update("world", 0, 64, z, true);
                double step = a.z() - prevAnchor;
                prevAnchor = a.z();
                if (round >= 2) {
                    maxRaw = Math.max(maxRaw, Math.abs(s - mean));
                    maxAnchor = Math.max(maxAnchor, Math.abs(step - mean));
                }
            }
        }
        assertTrue(maxAnchor < maxRaw * 0.75, "punkt odniesienia drga wyraźnie mniej niż surowe kroki: " + maxAnchor + " kontra " + maxRaw);
    }

    @Test
    void missedAndDoubledTicksAreSmoothedToo() {
        // serwer czasem dostaje dwa pakiety ruchu w jednym ticku, a czasem żadnego: kroki 0 i 0,4 zamiast 0,2
        ViewAnchor a = new ViewAnchor();
        double z = 0;
        a.update("world", 0, 64, z, true);
        double prev = a.z();
        double worst = 0;
        for (int i = 1; i <= 200; i++) {
            z += (i % 5 == 0) ? 0 : (i % 5 == 1 ? 0.4 : 0.2);
            a.update("world", 0, 64, z, true);
            if (i > 60) worst = Math.max(worst, Math.abs((a.z() - prev) - 0.2));
            prev = a.z();
        }
        assertTrue(worst < 0.2 * 0.75, "największe odchylenie kroku punktu to " + worst + ", a surowe kroki odchylają się o 0,2");
    }

    @Test
    void aTeleportIsNotAStep() {
        ViewAnchor a = new ViewAnchor();
        a.update("world", 0, 64, 0, true);
        a.update("world", 0, 64, 1, true);
        a.update("world", 0, 64, 101, true);
        assertEquals(101, a.z(), EPS, "skok o 100 bloków to teleport: punkt jest od razu przy graczu");
        a.update("world", 0, 64, 101, true);
        assertEquals(101, a.z(), EPS, "i nic się po nim nie ślizga");
    }

    @Test
    void anotherWorldStartsFromScratch() {
        ViewAnchor a = new ViewAnchor();
        a.update("world", 0, 64, 0, true);
        a.update("world", 0, 64, 2, true);
        a.update("nether", 50, 100, 50, false);
        assertEquals(50, a.x(), EPS);
        assertEquals(100, a.feetY(), EPS, "w nowym świecie poziom od razu jest prawdziwy, nawet w skoku");
        assertEquals(50, a.z(), EPS, "i bez śladu po poprzednim ruchu");
    }

    @Test
    void standingStillNeverDrifts() {
        ViewAnchor a = new ViewAnchor();
        for (int i = 0; i < 50; i++) a.update("world", 12.5, 64, -7.5, i % 7 == 0);
        assertEquals(12.5, a.x(), EPS);
        assertEquals(-7.5, a.z(), EPS);
        assertEquals(64, a.feetY(), EPS);
    }
}
