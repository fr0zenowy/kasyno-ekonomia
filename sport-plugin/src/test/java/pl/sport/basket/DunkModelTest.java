package pl.sport.basket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Szansa wsadu: zależy od wyskoku, biegu, odległości i obrońcy, a trudność z ustawień ją dzieli. */
class DunkModelTest {
    private static final double RANGE = 2.6;

    @Test
    void theRunUpMakesTheDunkBetter() {
        double weak = DunkModel.quality(0.25, 2.2, 2.5, RANGE);
        double typical = DunkModel.quality(0.5, 4.5, 2.0, RANGE);
        double perfect = DunkModel.quality(0.9, 6.0, 0.0, RANGE);
        assertTrue(weak < typical && typical < perfect, weak + " < " + typical + " < " + perfect);
        assertEquals(1.0, perfect, 1e-9);
        assertTrue(weak > 0 && weak < 0.45, "najsłabszy dopuszczony wsad: " + weak);
    }

    @Test
    void aDunkIsNeverACertaintyUnlessTheDifficultyIsZero() {
        for (double q = 0; q <= 1.0; q += 0.1) {
            for (double c = 0; c <= 1.0; c += 0.25) {
                double p = DunkModel.chance(q, c, 1.0);
                assertTrue(p >= 0.05 && p <= 0.92, "granice: " + p);
            }
        }
        assertEquals(1.0, DunkModel.chance(0, 1, 0), 1e-9, "trudność 0: zawsze wpada");
    }

    @Test
    void aTypicalDunkIsAboutAsLikelyAsNotAndADefenderHurts() {
        double typical = DunkModel.chance(DunkModel.quality(0.5, 4.5, 2.0, RANGE), 0, 1.0);
        double contested = DunkModel.chance(DunkModel.quality(0.5, 4.5, 2.0, RANGE), 0.8, 1.0);
        System.out.printf("WSAD: typowy %.0f%%, z obrońcą %.0f%%%n", 100 * typical, 100 * contested);
        assertTrue(typical > 0.45 && typical < 0.8, "typowy wsad: " + typical);
        assertTrue(contested < typical - 0.15, "obrońca przeszkadza: " + contested + " vs " + typical);
    }

    @Test
    void theDifficultyDividesTheChance() {
        double q = DunkModel.quality(0.5, 4.5, 2.0, RANGE);
        double easy = DunkModel.chance(q, 0, 0.5);
        double normal = DunkModel.chance(q, 0, 1.0);
        double hard = DunkModel.chance(q, 0, 2.0);
        assertTrue(easy > normal && normal > hard, easy + " " + normal + " " + hard);
        assertEquals(normal / 2, hard, 1e-9);
    }

    @Test
    void onlyAnAirborneDefenderCanBlockADunk() {
        assertEquals(0.0, DunkModel.blockChance(false, 1.0), 1e-9);
        assertTrue(DunkModel.blockChance(true, 0.0) >= 0.5);
        assertTrue(DunkModel.blockChance(true, 1.0) <= 0.8);
    }
}
