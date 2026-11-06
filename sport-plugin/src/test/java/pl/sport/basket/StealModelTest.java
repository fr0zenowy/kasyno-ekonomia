package pl.sport.basket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Szansa przechwytu i faulu: kierunek zmian zgodny z prawdziwą koszykówką. */
class StealModelTest {
    private static StealModel.Situation base() {
        return new StealModel.Situation(1.0, false, false, false, false, false, false);
    }

    private static StealModel.Situation with(double dist, boolean low, boolean behind, boolean protect, boolean sprint, boolean holderAir, boolean stealerAir) {
        return new StealModel.Situation(dist, low, behind, protect, sprint, holderAir, stealerAir);
    }

    @Test
    void aLowBallIsEasierToStealThanOneAtTheHand() {
        double high = StealModel.chance(0.35, with(1.0, false, false, false, false, false, false));
        double low = StealModel.chance(0.35, with(1.0, true, false, false, false, false, false));
        assertTrue(low > high * 2, "piłka nisko: " + low + " vs " + high);
    }

    @Test
    void stealingFromBehindIsEasier() {
        assertTrue(StealModel.chance(0.35, with(1.0, true, true, false, false, false, false)) > StealModel.chance(0.35, with(1.0, true, false, false, false, false, false)));
    }

    @Test
    void shieldingTheBallSprintingAndJumpingAllMakeItHarder() {
        double open = StealModel.chance(0.35, with(1.0, true, false, false, false, false, false));
        assertTrue(StealModel.chance(0.35, with(1.0, true, false, true, false, false, false)) < open * 0.5, "osłona");
        assertTrue(StealModel.chance(0.35, with(1.0, true, false, false, true, false, false)) < open, "sprint");
        assertTrue(StealModel.chance(0.35, with(1.0, true, false, false, false, true, false)) < open * 0.6, "wyskok kozłującego");
    }

    @Test
    void closerIsBetterAndTheChanceStaysBetweenTheLimits() {
        double near = StealModel.chance(0.35, with(0.5, true, false, false, false, false, false));
        double far = StealModel.chance(0.35, with(1.8, true, false, false, false, false, false));
        assertTrue(near > far, near + " vs " + far);
        for (double d = 0; d <= 3; d += 0.25) {
            for (boolean low : new boolean[]{false, true}) {
                double c = StealModel.chance(0.35, with(d, low, true, false, false, false, true));
                assertTrue(c >= 0.02 && c <= 0.9, "granice: " + c);
            }
        }
    }

    @Test
    void aBaseOfTenAlwaysSucceedsAndZeroBarelyEver() {
        assertEquals(1.0, StealModel.chance(10, base()), 1e-9);
        assertTrue(StealModel.chance(0, base()) <= 0.02 + 1e-9);
    }

    @Test
    void anOpenStealFromTheFrontIsNeverAFoul() {
        for (double d = 0; d <= 3; d += 0.25) {
            assertEquals(0.0, StealModel.foulChance(with(d, true, false, false, true, false, false)), 1e-9, "z przodu na otwartą piłkę: czysta próba, odległość " + d);
            assertEquals(0.0, StealModel.foulChance(with(d, false, false, false, false, false, true)), 1e-9);
        }
    }

    @Test
    void contactMakesFoulsLikelyFromBehindAgainstAShieldedBallAndAgainstAShooter() {
        double behindClose = StealModel.foulChance(with(0.8, false, true, false, false, false, false));
        double behindFar = StealModel.foulChance(with(1.8, false, true, false, false, false, false));
        assertTrue(behindClose >= 0.85 && behindFar > 0 && behindFar < behindClose, "od tyłu z bliska: " + behindClose + ", z daleka: " + behindFar);
        double shield = StealModel.foulChance(with(0.8, false, false, true, false, false, false));
        assertTrue(shield > 0.3 && shield < behindClose, "wejście w osłoniętą piłkę: " + shield);
        assertTrue(StealModel.foulChance(with(1.5, false, false, true, false, false, false)) < shield, "z daleka od osłoniętej piłki rzadziej");
        double shooter = StealModel.foulChance(with(1.0, false, false, false, false, true, false));
        assertTrue(shooter >= 0.85, "uderzenie w ręce rzucającego/skaczącego: " + shooter);
        for (double d = 0; d <= 3; d += 0.25) {
            double f = StealModel.foulChance(with(d, false, true, true, false, true, false));
            assertTrue(f >= 0 && f <= 0.95, "granice faulu: " + f);
        }
    }
}
