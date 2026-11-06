package pl.sport.volley;

import org.junit.jupiter.api.Test;
import pl.sport.core.Team;
import pl.sport.volley.VolleyGame.HitType;
import pl.sport.volley.VolleyGame.PointResult;
import pl.sport.volley.VolleyGame.Reason;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zasady siatkówki: odbicia, błędy, punktacja, sety, zmiana stron i serwisu. */
class VolleyGameTest {
    private static final UUID P1 = UUID.randomUUID();
    private static final UUID P2 = UUID.randomUUID();
    private static final UUID P3 = UUID.randomUUID();
    private static final UUID Q1 = UUID.randomUUID();
    private static final UUID Q2 = UUID.randomUUID();

    private VolleyGame game() {
        return new VolleyGame(VolleyGame.Config.standard(), Team.A);
    }

    private VolleyGame served(VolleyGame g) {
        g.beginRally();
        assertNull(g.touch(Team.A, P1, HitType.SERVE));
        assertEquals(VolleyGame.Phase.RALLY, g.phase());
        return g;
    }

    @Test
    void onlyTheServingTeamServesAndOnlyWithAServe() {
        VolleyGame g = game();
        g.beginRally();
        assertNull(g.touch(Team.B, Q1, HitType.SERVE), "druga drużyna nie serwuje");
        assertEquals(VolleyGame.Phase.SERVE, g.phase());
        assertNull(g.touch(Team.A, P1, HitType.BUMP), "zwykłe odbicie nie jest zagrywką");
        assertEquals(VolleyGame.Phase.SERVE, g.phase());
        assertNull(g.touch(Team.A, P1, HitType.SERVE));
        assertEquals(VolleyGame.Phase.RALLY, g.phase());
    }

    @Test
    void aTeamMayTouchThreeTimesButNotFour() {
        VolleyGame g = served(game());
        assertNull(g.touch(Team.B, Q1, HitType.BUMP));
        assertNull(g.touch(Team.B, Q2, HitType.SET));
        assertNull(g.touch(Team.B, Q1, HitType.SPIKE));
        assertEquals(3, g.touches());
        PointResult r = g.touch(Team.B, Q2, HitType.BUMP);
        assertNotNull(r);
        assertEquals(Reason.FOUR_TOUCHES, r.reason());
        assertEquals(Team.A, r.winner());
    }

    @Test
    void theTouchCountResetsWhenTheOtherTeamTouches() {
        VolleyGame g = served(game());
        g.touch(Team.B, Q1, HitType.BUMP);
        g.touch(Team.B, Q2, HitType.SET);
        assertNull(g.touch(Team.A, P2, HitType.BUMP));
        assertEquals(1, g.touches());
        assertEquals(Team.A, g.touchTeam());
    }

    @Test
    void thePlayerWhoJustTouchedMayNotTouchAgain() {
        VolleyGame g = served(game());
        g.touch(Team.B, Q1, HitType.BUMP);
        PointResult r = g.touch(Team.B, Q1, HitType.SET);
        assertNotNull(r);
        assertEquals(Reason.DOUBLE_TOUCH, r.reason());
        assertEquals(Team.A, r.winner());
    }

    @Test
    void aBlockDoesNotCountAsATouchAndTheBlockerMayPlayTheBallNext() {
        VolleyGame g = served(game());
        // drużyna B zablokowała zagrywkę/atak: blok nie liczy się do trzech odbić
        assertNull(g.touch(Team.B, Q1, HitType.BLOCK));
        assertEquals(0, g.touches());
        assertEquals(Team.B, g.touchTeam());
        // ten sam gracz może zagrać piłkę po bloku
        assertNull(g.touch(Team.B, Q1, HitType.BUMP));
        assertEquals(1, g.touches());
        // ale po tym dotknięciu już nie dwa razy z rzędu
        PointResult r = g.touch(Team.B, Q1, HitType.SET);
        assertNotNull(r);
        assertEquals(Reason.DOUBLE_TOUCH, r.reason());
    }

    @Test
    void aBallLandingInsideTheCourtIsAPointForTheOtherSide() {
        VolleyGame g = served(game());
        g.touch(Team.B, Q1, HitType.BUMP);
        PointResult r = g.landed(Team.B, true);
        assertEquals(Team.A, r.winner());
        assertEquals(Reason.LANDED_IN, r.reason());
        assertEquals(1, r.pointsA());
        assertEquals(0, r.pointsB());
    }

    @Test
    void anOutBallIsAPointAgainstTheLastToucher() {
        VolleyGame g = served(game());
        g.touch(Team.B, Q1, HitType.SPIKE);
        PointResult r = g.landed(null, false);
        assertEquals(Team.A, r.winner());
        assertEquals(Reason.LANDED_OUT, r.reason());

        VolleyGame g2 = served(game());
        g2.touch(Team.B, Q1, HitType.BUMP);
        g2.touch(Team.A, P2, HitType.SPIKE);
        PointResult r2 = g2.landed(null, false);
        assertEquals(Team.B, r2.winner(), "aut po ataku drużyny A to punkt dla B");
    }

    @Test
    void aServeThatIsNeverHitIsAFault() {
        VolleyGame g = game();
        g.beginRally();
        PointResult r = g.serveTimeout();
        assertEquals(Team.B, r.winner());
        assertEquals(Reason.SERVE_FAULT, r.reason());
    }

    @Test
    void aServeLandingOnTheServersOwnSideLosesThePoint() {
        VolleyGame g = served(game());
        PointResult r = g.landed(Team.A, true);
        assertEquals(Team.B, r.winner());
    }

    @Test
    void theServeChangesHandsWhenTheReceiversWin() {
        VolleyGame g = game();
        assertEquals(Team.A, g.serving());
        g.beginRally();
        g.touch(Team.A, P1, HitType.SERVE);
        g.landed(Team.B, true); // A wygrywa, A dalej serwuje
        assertEquals(Team.A, g.serving());
        g.beginRally();
        g.touch(Team.A, P1, HitType.SERVE);
        g.landed(Team.A, true); // B wygrywa
        assertEquals(Team.B, g.serving());
        assertEquals(1, g.points(Team.A));
        assertEquals(1, g.points(Team.B));
    }

    @Test
    void noPointsAreAwardedOutsideARally() {
        VolleyGame g = game();
        assertNull(g.landed(Team.B, true), "przed pierwszą zagrywką nic się nie liczy");
        g.beginRally();
        g.touch(Team.A, P1, HitType.SERVE);
        assertNotNull(g.landed(Team.B, true));
        assertNull(g.landed(Team.B, true), "ta sama akcja nie przyznaje punktu dwa razy");
        assertEquals(1, g.points(Team.A));
    }

    /** Przyznaje akcję drużynie t przez upadek piłki na połowie przeciwnika. */
    private PointResult rally(VolleyGame g, Team t) {
        g.beginRally();
        g.touch(g.serving(), P1, HitType.SERVE);
        return g.landed(t.other(), true);
    }

    @Test
    void aSetEndsAt25WithATwoPointLead() {
        VolleyGame g = game();
        PointResult last = null;
        for (int i = 0; i < 24; i++) last = rally(g, Team.A);
        assertFalse(last.setFinished());
        assertEquals(24, g.points(Team.A));
        PointResult r = rally(g, Team.A);
        assertTrue(r.setFinished());
        assertEquals(Team.A, r.setWinner());
        assertFalse(r.matchFinished());
        assertEquals(1, g.sets(Team.A));
        assertEquals(0, g.points(Team.A), "punkty nowego seta od zera");
        assertEquals(2, g.setNumber());
    }

    @Test
    void aSetNeedsATwoPointLeadSoItCanGoPast25() {
        VolleyGame g = game();
        for (int i = 0; i < 24; i++) {
            rally(g, Team.A);
            rally(g, Team.B);
        }
        assertEquals(24, g.points(Team.A));
        assertEquals(24, g.points(Team.B));
        assertFalse(rally(g, Team.A).setFinished(), "25:24 jeszcze nie kończy");
        assertFalse(rally(g, Team.B).setFinished(), "25:25");
        assertFalse(rally(g, Team.B).setFinished(), "25:26: przewaga tylko jednego punktu");
        PointResult r = rally(g, Team.B);
        assertTrue(r.setFinished(), "25:27");
        assertEquals(Team.B, r.setWinner());
    }

    @Test
    void sidesSwapAndTheOtherTeamServesFirstInTheNextSet() {
        VolleyGame g = game();
        assertEquals(Team.A, g.lowSideOwner());
        assertEquals(Team.A, g.sideOwner(true));
        for (int i = 0; i < 25; i++) rally(g, Team.A);
        assertTrue(g.swapped());
        assertEquals(Team.B, g.lowSideOwner());
        assertEquals(Team.A, g.sideOwner(false));
        assertEquals(Team.B, g.serving(), "w drugim secie zaczyna zagrywką druga drużyna");
    }

    @Test
    void theMatchEndsWhenATeamWinsTheRequiredSets() {
        VolleyGame g = game();
        PointResult r = null;
        for (int s = 0; s < 2; s++) for (int i = 0; i < 25; i++) r = rally(g, Team.A);
        assertTrue(r.matchFinished());
        assertEquals(Team.A, r.matchWinner());
        assertTrue(g.finished());
        assertEquals(2, g.sets(Team.A));
    }

    @Test
    void theDecidingSetIsPlayedTo15() {
        VolleyGame g = game();
        for (int i = 0; i < 25; i++) rally(g, Team.A); // 1:0
        for (int i = 0; i < 25; i++) rally(g, Team.B); // 1:1
        assertTrue(g.decidingSet());
        assertEquals(15, g.pointsTarget());
        PointResult r = null;
        for (int i = 0; i < 15; i++) r = rally(g, Team.B);
        assertTrue(r.matchFinished());
        assertEquals(Team.B, r.matchWinner());
    }

    @Test
    void aSingleSetMatchIsNotADecidingSet() {
        VolleyGame g = new VolleyGame(new VolleyGame.Config(1, 25, 15, 2, 3, true), Team.A);
        assertFalse(g.decidingSet());
        PointResult r = null;
        for (int i = 0; i < 25; i++) r = rally(g, Team.A);
        assertTrue(r.matchFinished());
    }

    @Test
    void doubleTouchesCanBeSwitchedOff() {
        VolleyGame g = new VolleyGame(new VolleyGame.Config(2, 25, 15, 2, 3, false), Team.A);
        g.beginRally();
        g.touch(Team.A, P1, HitType.SERVE);
        g.touch(Team.B, Q1, HitType.BUMP);
        assertNull(g.touch(Team.B, Q1, HitType.SET), "bez zakazu podwójnego odbicia");
    }

    @Test
    void aLonePlayerMayTouchTheBallSeveralTimesInARowUpToThree() {
        VolleyGame g = game();
        g.setTeamSize(Team.B, 1);
        g.beginRally();
        g.touch(Team.A, P1, HitType.SERVE);
        assertNull(g.touch(Team.B, Q1, HitType.BUMP), "przyjęcie");
        assertNull(g.touch(Team.B, Q1, HitType.SET), "wystawienie sobie: ten sam gracz drugi raz");
        assertNull(g.touch(Team.B, Q1, HitType.SPIKE), "atak: trzecie odbicie tego samego gracza");
        assertEquals(3, g.touches());
        PointResult r = g.touch(Team.B, Q1, HitType.BUMP);
        assertNotNull(r, "czwarte odbicie nadal jest błędem");
        assertEquals(Reason.FOUR_TOUCHES, r.reason());
    }

    @Test
    void aTeamOfTwoStillCannotTouchTwiceInARowButASoloOpponentDoesNotChangeThat() {
        VolleyGame g = game();
        g.setTeamSize(Team.A, 1);
        g.setTeamSize(Team.B, 2);
        g.beginRally();
        g.touch(Team.A, P1, HitType.SERVE);
        g.touch(Team.B, Q1, HitType.BUMP);
        PointResult r = g.touch(Team.B, Q1, HitType.SET);
        assertNotNull(r);
        assertEquals(Reason.DOUBLE_TOUCH, r.reason());
    }

    @Test
    void theServerWhoStepsOntoTheCourtLosesThePoint() {
        VolleyGame g = game();
        g.beginRally();
        PointResult r = g.footFault();
        assertNotNull(r);
        assertEquals(Reason.FOOT_FAULT, r.reason());
        assertEquals(Team.B, r.winner());
        assertNull(g.footFault(), "tylko przed uderzeniem zagrywki");

        VolleyGame g2 = game();
        g2.beginRally();
        g2.touch(Team.A, P1, HitType.SERVE);
        assertNull(g2.footFault(), "po uderzeniu zagrywki nie ma już błędu stopy");
    }

    @Test
    void abortingEndsTheMatchWithoutAWinner() {
        VolleyGame g = game();
        g.abort();
        assertTrue(g.finished());
        assertNull(g.award(Team.A, Reason.LANDED_IN));
    }
}
