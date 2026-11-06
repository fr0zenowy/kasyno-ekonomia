package pl.kasyno.prison;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zasady więzienia i trzy minigierki bez Bukkita: współrzędne cel, filtr komend, podkop, refleks i sekwencja. */
class PrisonLogicTest {
    // ---------- cele ----------

    @Test
    void wholeNumbersAreBlockCoordinatesSoPlayersStandInTheMiddleOfTheBlock() {
        PrisonRules.Spot s = PrisonRules.parseSpot("-79 92 65");
        assertEquals(-78.5, s.x(), 1e-9);
        assertEquals(92, s.y(), 1e-9);
        assertEquals(65.5, s.z(), 1e-9);
    }

    @Test
    void fractionalNumbersStayExactAndSeparatorsAreFlexible() {
        PrisonRules.Spot s = PrisonRules.parseSpot("-79.25, 92, 65.75");
        assertEquals(-79.25, s.x(), 1e-9);
        assertEquals(65.75, s.z(), 1e-9);
        assertEquals(PrisonRules.parseSpot("1 2 3"), PrisonRules.parseSpot("1;2;3"));
    }

    @Test
    void theThirdCellGivenWithoutXIsRejectedNotGuessedByTheParser() {
        // użytkownik podał „92 65” bez X: parser nie zgaduje, dlatego domyślna konfiguracja ma jawne -85 92 65
        assertNull(PrisonRules.parseSpot("92 65"));
        assertNull(PrisonRules.parseSpot("a b c"));
        assertNull(PrisonRules.parseSpot(null));
        assertNull(PrisonRules.parseSpot("1 2 3 4"));
        assertNull(PrisonRules.parseSpot("NaN 2 3"));
        List<PrisonRules.Spot> spots = PrisonRules.parseSpots(List.of("-79 92 65", "-82 92 65", "92 65", "-85 92 65"));
        assertEquals(3, spots.size(), "błędny wpis jest pomijany, reszta zostaje");
        assertTrue(PrisonRules.parseSpots(null).isEmpty());
    }

    @Test
    void formattingRoundTripsBlockAndExactCoordinates() {
        PrisonRules.Spot block = PrisonRules.parseSpot("-79 92 65");
        assertEquals("-79 92 65", PrisonRules.format(block));
        assertEquals(block, PrisonRules.parseSpot(PrisonRules.format(block)));
        PrisonRules.Spot exact = new PrisonRules.Spot(1.25, 70, -3.75);
        assertEquals(exact, PrisonRules.parseSpot(PrisonRules.format(exact)));
    }

    @Test
    void randomCellUsesEveryCellAndStaysInRange() {
        Random rng = new Random(7);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            int c = PrisonRules.pickCell(3, rng);
            assertTrue(c >= 0 && c < 3);
            seen.add(c);
        }
        assertEquals(Set.of(0, 1, 2), seen);
        assertEquals(0, PrisonRules.pickCell(1, rng));
        assertEquals(0, PrisonRules.pickCell(0, rng));
    }

    @Test
    void sentencesDefaultToMoreWorkForAHeistThanForRobbing() {
        assertTrue(PrisonRules.Reason.HEIST.defaultTasks() > PrisonRules.Reason.CRIME.defaultTasks());
        assertTrue(PrisonRules.Reason.CRIME.defaultTasks() > PrisonRules.Reason.ROB.defaultTasks());
        assertEquals(PrisonRules.Reason.ROB, PrisonRules.Reason.byId("OKRADANIE"));
        assertNull(PrisonRules.Reason.byId("nic"));
        assertNull(PrisonRules.Reason.byId(null));
    }

    @Test
    void clockAndSecondsLeft() {
        assertEquals("0:05", PrisonRules.clock(5));
        assertEquals("12:00", PrisonRules.clock(720));
        assertEquals(3, PrisonRules.secondsLeft(10_000, 7_001));
        assertEquals(0, PrisonRules.secondsLeft(5_000, 9_000));
    }

    // ---------- komendy ----------

    @Test
    void onlyTheTaskListAndChatCommandsWorkInACell() {
        List<String> allowed = List.of("kasyno wiezienie", "wiezienie", "msg");
        assertTrue(PrisonRules.commandAllowed("/kasyno wiezienie", allowed));
        assertTrue(PrisonRules.commandAllowed("/KASYNO   Wiezienie", allowed));
        assertTrue(PrisonRules.commandAllowed("/kasyno:wiezienie", allowed), "z prefiksem pluginu to ta sama komenda");
        assertTrue(PrisonRules.commandAllowed("/wiezienie", allowed));
        assertTrue(PrisonRules.commandAllowed("/msg Kuba cześć", allowed));
        assertFalse(PrisonRules.commandAllowed("/kasyno", allowed));
        assertFalse(PrisonRules.commandAllowed("/kasyno sklep", allowed));
        assertFalse(PrisonRules.commandAllowed("/moneta 100", allowed));
        assertFalse(PrisonRules.commandAllowed("/wiezieniex", allowed), "to nie ta sama komenda");
        assertFalse(PrisonRules.commandAllowed("/home", allowed));
    }

    // ---------- Podkop ----------

    @Test
    void mashNeedsEnoughClicksInsideTheWindow() {
        MashRound r = new MashRound(5, 1000);
        assertEquals(MashRound.State.WAITING, r.state());
        assertEquals(1000, r.msLeft(500), "zegar rusza dopiero od pierwszego kliknięcia");
        for (int i = 0; i < 4; i++) assertEquals(MashRound.State.RUNNING, r.click(100 + i * 100L));
        assertEquals(MashRound.State.WON, r.click(600));
        assertEquals(1.0, r.progress(), 1e-9);
        assertEquals(MashRound.State.WON, r.click(5000), "po wygranej nic się nie zmienia");
    }

    @Test
    void mashLosesWhenTooSlowOrWhenTheClockRunsOut() {
        MashRound slow = new MashRound(5, 1000);
        slow.click(0);
        slow.click(400);
        assertEquals(MashRound.State.LOST, slow.click(1500), "spóźnione kliknięcie przegrywa");
        assertEquals(2, slow.clicks(), "spóźnione się nie liczy");
        MashRound idle = new MashRound(5, 1000);
        idle.click(0);
        assertEquals(MashRound.State.RUNNING, idle.poll(900));
        assertEquals(MashRound.State.LOST, idle.poll(1100));
        assertEquals(0, idle.msLeft(5000));
        MashRound never = new MashRound(5, 1000);
        assertEquals(MashRound.State.WAITING, never.poll(99_999), "bez pierwszego kliknięcia czas nie płynie");
    }

    // ---------- Refleks ----------

    @Test
    void reflexWinsAfterEnoughHits() {
        ReflexRound r = new ReflexRound(4, 2, 15, new Random(1));
        for (int i = 0; i < 4; i++) {
            assertEquals(ReflexRound.State.RUNNING, r.state());
            r.hit(r.target());
        }
        assertEquals(ReflexRound.State.WON, r.state());
        assertEquals(4, r.hits());
        assertEquals(ReflexRound.State.WON, r.hit(0), "po końcu nic się nie zmienia");
    }

    @Test
    void reflexAllowsTheConfiguredNumberOfMissesAndThenLoses() {
        ReflexRound r = new ReflexRound(6, 2, 15, new Random(2));
        r.miss();
        r.hit((r.target() + 1) % 15); // pudło też jest błędem
        assertEquals(2, r.misses());
        assertEquals(ReflexRound.State.RUNNING, r.state(), "dwa błędy jeszcze wolno");
        assertEquals(ReflexRound.State.LOST, r.miss());
        assertEquals(ReflexRound.State.LOST, r.hit(r.target()));
    }

    @Test
    void reflexNeverRepeatsTheSameTargetAndSpeedsUp() {
        ReflexRound r = new ReflexRound(40, 100, 15, new Random(3));
        int first = r.windowTicks();
        int prev = r.target();
        for (int i = 0; i < 12; i++) {
            r.hit(r.target());
            assertNotEquals(prev, r.target());
            assertTrue(r.target() >= 0 && r.target() < 15);
            prev = r.target();
        }
        assertTrue(r.windowTicks() < first);
        assertTrue(r.windowTicks() >= 12, "nigdy szybciej niż 0,6 s");
    }

    // ---------- Sekwencja ----------

    @Test
    void simonAcceptsTheRightOrderAndFinishes() {
        SimonRound r = new SimonRound(5, 4, new Random(4));
        List<Integer> seq = r.sequence();
        assertEquals(5, seq.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(SimonRound.Result.OK, r.input(seq.get(i)));
            assertEquals(i + 1, r.entered());
        }
        assertEquals(SimonRound.Result.DONE, r.input(seq.get(4)));
    }

    @Test
    void simonWrongColorResetsEntry() {
        SimonRound r = new SimonRound(5, 4, new Random(5));
        List<Integer> seq = r.sequence();
        r.input(seq.get(0));
        int wrong = (seq.get(1) + 1) % 4;
        assertEquals(SimonRound.Result.WRONG, r.input(wrong));
        assertEquals(0, r.entered());
    }

    @Test
    void simonNeverShowsTheSameColorTwiceInARow() {
        for (long seed = 0; seed < 200; seed++) {
            List<Integer> seq = new SimonRound(8, 4, new Random(seed)).sequence();
            for (int i = 1; i < seq.size(); i++) assertNotEquals(seq.get(i - 1), seq.get(i), "ziarno " + seed);
            for (int c : seq) assertTrue(c >= 0 && c < 4);
        }
    }
}
