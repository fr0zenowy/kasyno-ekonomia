package pl.kasyno.show;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wysokość podłoża pod figurą: schody z płyt, dywan, brak podłoża i figura, której wysokość nie zgadza się z terenem. */
class GroundTest {
    private static Ground.Surface of(Map<Integer, Double> tops) {
        return by -> tops.getOrDefault(by, Double.NaN);
    }

    @Test
    void flatGroundKeepsTheGivenHeight() {
        assertEquals(0.0, Ground.snap(0.0, of(Map.of(-1, 0.0))), 1e-9);
        assertEquals(64.0, Ground.snap(64.0, of(Map.of(63, 64.0))), 1e-9);
    }

    @Test
    void slabStairsGiveHalfBlockSteps() {
        // z=12: płyta (0,5), z=13: blok (1,0), z=14: blok + płyta (1,5), z=15: podest (2,0)
        Map<Integer, Double> z12 = Map.of(-1, 0.0, 0, 0.5);
        Map<Integer, Double> z13 = Map.of(-1, 0.0, 0, 1.0);
        Map<Integer, Double> z14 = Map.of(-1, 0.0, 0, 1.0, 1, 1.5);
        Map<Integer, Double> z15 = Map.of(-1, 0.0, 0, 1.0, 1, 2.0);
        assertEquals(0.5, Ground.snap(0.0, of(z12)), 1e-9);
        assertEquals(1.0, Ground.snap(1.0, of(z13)), 1e-9);
        assertEquals(1.5, Ground.snap(1.5, of(z14)), 1e-9);
        assertEquals(2.0, Ground.snap(2.0, of(z15)), 1e-9);
    }

    @Test
    void followersWithTheLeadersHeightStillLandOnTheirOwnGround() {
        // reszta ekipy dostaje wysokość przewodnika (podest 2.0), ale stoi jeszcze na chodniku
        assertEquals(0.0, Ground.snap(2.0, of(Map.of(-1, 0.0))), 1e-9);
        // albo na środku schodów
        assertEquals(1.0, Ground.snap(2.0, of(Map.of(-1, 0.0, 0, 1.0))), 1e-9);
        // a przewodnik stojący niżej niż reszta wskakuje na podest, jeśli ten jest w zasięgu stopnia
        assertEquals(1.0, Ground.snap(0.0, of(Map.of(-1, 0.0, 0, 1.0))), 1e-9);
    }

    @Test
    void aCarpetIsWalkedOnTopOf() {
        assertEquals(2.0625, Ground.snap(2.0, of(Map.of(1, 2.0, 2, 2.0625))), 1e-9);
    }

    @Test
    void wallsAndCeilingsAboveReachAreIgnored() {
        // sufit trzy bloki nad podłogą i ściana z bokiem wyższym niż stopień nie zmieniają wysokości
        assertEquals(2.0, Ground.snap(2.0, of(Map.of(1, 2.0, 5, 6.0))), 1e-9);
        assertEquals(0.0, Ground.snap(0.0, of(Map.of(1, 2.0))), 1e-9, "powierzchnia na wysokości dwóch bloków to ściana albo strop, nie stopień");
        assertEquals(1.0, Ground.snap(0.0, of(Map.of(-1, 0.0, 0, 1.0, 1, 2.0))), 1e-9, "figura wchodzi na jeden blok, ale nie na dwa");
    }

    @Test
    void noGroundAtAllLeavesTheHeightAlone() {
        assertEquals(5.0, Ground.snap(5.0, of(Map.of())), 1e-9);
        assertEquals(5.0, Ground.snap(5.0, of(Map.of(-20, 0.0))), 1e-9, "podłoże głębiej niż zasięg szukania to dziura, nie grunt");
    }

    @Test
    void theSearchStopsAtTheFirstSurfaceFromAbove() {
        // pod podestem jest jeszcze fundament, ale liczy się pierwsza powierzchnia od góry
        assertEquals(2.0, Ground.snap(2.0, of(Map.of(1, 2.0, 0, 1.0, -1, 0.0))), 1e-9);
    }
}
