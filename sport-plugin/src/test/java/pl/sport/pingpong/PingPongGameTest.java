package pl.sport.pingpong;

import org.junit.jupiter.api.Test;
import pl.sport.core.Team;
import pl.sport.pingpong.PingPongGame.PointResult;
import pl.sport.pingpong.PingPongGame.Reason;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zasady tenisa stołowego: serwis, odbicia od stołu, błędy, lety, punktacja, zmiana serwisu i stron. */
class PingPongGameTest {
    private static final UUID A1 = UUID.randomUUID();
    private static final UUID A2 = UUID.randomUUID();
    private static final UUID B1 = UUID.randomUUID();
    private static final UUID B2 = UUID.randomUUID();

    private PingPongGame game(Team first) {
        PingPongGame g = new PingPongGame(PingPongGame.Config.standard(), first);
        g.beginRally();
        return g;
    }

    /** Dobry serwis aktualnego serwującego: odbicie od własnej połowy, potem od połowy rywala. */
    private void goodServe(PingPongGame g) {
        Team s = g.serving();
        UUID id = s == Team.A ? A1 : B1;
        assertNull(g.serveHit(s, id, true));
        assertNull(g.bounce(s));
        assertNull(g.bounce(s.other()));
        assertEquals(PingPongGame.Stage.GOOD, g.stage());
    }

    @Test
    void aLegalServeBouncesOnTheServersHalfThenOnTheReceiversHalf() {
        PingPongGame g = game(Team.A);
        assertEquals(PingPongGame.Phase.SERVE, g.phase());
        assertNull(g.serveHit(Team.A, A1, true));
        assertEquals(PingPongGame.Stage.SERVE_OWN, g.stage());
        assertNull(g.bounce(Team.A));
        assertEquals(PingPongGame.Stage.SERVE_OVER, g.stage());
        assertNull(g.bounce(Team.B));
        assertEquals(PingPongGame.Stage.GOOD, g.stage());
        assertEquals(PingPongGame.Phase.RALLY, g.phase());
    }

    @Test
    void aServeThatBouncesFirstOnTheReceiversHalfIsAFault() {
        PingPongGame g = game(Team.A);
        g.serveHit(Team.A, A1, true);
        PointResult r = g.bounce(Team.B);
        assertEquals(Team.B, r.winner());
        assertEquals(Reason.SERVE_FAULT, r.reason());
    }

    @Test
    void aServeThatBouncesTwiceOnTheServersHalfIsAFault() {
        PingPongGame g = game(Team.A);
        g.serveHit(Team.A, A1, true);
        g.bounce(Team.A);
        PointResult r = g.bounce(Team.A);
        assertEquals(Team.B, r.winner());
        assertEquals(Reason.SERVE_FAULT, r.reason());
    }

    @Test
    void aServeFromInFrontOfTheEndLineLosesThePoint() {
        PingPongGame g = game(Team.A);
        PointResult r = g.serveHit(Team.A, A1, false);
        assertEquals(Team.B, r.winner());
        assertEquals(Reason.SERVE_POSITION, r.reason());
    }

    @Test
    void aServeTouchingTheNetThatIsOtherwiseGoodIsReplayed() {
        PingPongGame g = game(Team.A);
        g.serveHit(Team.A, A1, true);
        g.bounce(Team.A);
        g.netTouch();
        PointResult r = g.bounce(Team.B);
        assertTrue(r.let(), "powtórka serwisu");
        assertNull(r.winner());
        assertEquals(0, g.points(Team.A) + g.points(Team.B));
        assertEquals(PingPongGame.Phase.SERVE, g.phase());
        assertEquals(Team.A, g.serving());
        // po powtórce można serwować ponownie
        assertNull(g.serveHit(Team.A, A1, true));
    }

    @Test
    void aServeTouchingTheNetThatDoesNotLandIsAFault() {
        PingPongGame g = game(Team.A);
        g.serveHit(Team.A, A1, true);
        g.bounce(Team.A);
        g.netTouch();
        PointResult r = g.deadBall();
        assertEquals(Team.B, r.winner());
        assertEquals(Reason.SERVE_FAULT, r.reason());
    }

    @Test
    void netTouchesInARallyDoNotMatter() {
        PingPongGame g = game(Team.A);
        goodServe(g);
        assertNull(g.hit(Team.B, B1));
        g.netTouch();
        assertNull(g.bounce(Team.A));
        assertEquals(PingPongGame.Stage.GOOD, g.stage());
    }

    @Test
    void theReceiverMustLetTheServeBounce() {
        PingPongGame g = game(Team.A);
        g.serveHit(Team.A, A1, true);
        g.bounce(Team.A);
        PointResult r = g.hit(Team.B, B1);
        assertEquals(Team.A, r.winner(), "wolej odbierającego to punkt dla serwującego");
        assertEquals(Reason.VOLLEY, r.reason());
    }

    @Test
    void volleysAreLegalWhenTheVolleyFaultIsSwitchedOff() {
        PingPongGame g = new PingPongGame(new PingPongGame.Config(2, 11, 2, false), Team.A);
        g.beginRally();
        g.serveHit(Team.A, A1, true);
        g.bounce(Team.A);
        assertNull(g.hit(Team.B, B1));
        assertEquals(PingPongGame.Stage.FLIGHT, g.stage());
    }

    @Test
    void aReturnMustBounceOnTheOpponentsHalfFirst() {
        PingPongGame g = game(Team.A);
        goodServe(g);
        assertNull(g.hit(Team.B, B1));
        PointResult r = g.bounce(Team.B);
        assertEquals(Team.A, r.winner());
        assertEquals(Reason.OWN_HALF, r.reason());
    }

    @Test
    void aReturnThatLeavesTheTableIsOut() {
        PingPongGame g = game(Team.A);
        goodServe(g);
        g.hit(Team.B, B1);
        PointResult r = g.deadBall();
        assertEquals(Team.A, r.winner());
        assertEquals(Reason.OUT, r.reason());
    }

    @Test
    void ifTheReceiverDoesNotReturnTheHitterScores() {
        PingPongGame g = game(Team.A);
        goodServe(g);
        PointResult r = g.deadBall();
        assertEquals(Team.A, r.winner());
        assertEquals(Reason.NOT_RETURNED, r.reason());

        PingPongGame h = game(Team.A);
        goodServe(h);
        PointResult second = h.bounce(Team.B);
        assertEquals(Team.A, second.winner(), "drugie odbicie od stołu bez uderzenia: punkt dla uderzającego");
        assertEquals(Reason.NOT_RETURNED, second.reason());
    }

    @Test
    void aRallyGoesBackAndForthUntilSomeoneFails() {
        PingPongGame g = game(Team.A);
        goodServe(g);
        for (int i = 0; i < 5; i++) {
            assertNull(g.hit(Team.B, B1));
            assertNull(g.bounce(Team.A));
            assertNull(g.hit(Team.A, A1));
            assertNull(g.bounce(Team.B));
        }
        PointResult r = g.deadBall();
        assertEquals(Team.A, r.winner());
        assertEquals(1, g.points(Team.A));
    }

    @Test
    void hittingTwiceInARowIsAFault() {
        PingPongGame g = game(Team.A);
        goodServe(g);
        g.hit(Team.B, B1);
        PointResult r = g.hit(Team.B, B1);
        assertEquals(Team.A, r.winner());
        assertEquals(Reason.DOUBLE_HIT, r.reason());
    }

    @Test
    void aServerWhoHitsHisOwnServeAgainLoses() {
        PingPongGame g = game(Team.A);
        g.serveHit(Team.A, A1, true);
        PointResult r = g.hit(Team.A, A1);
        assertEquals(Team.B, r.winner());
        assertEquals(Reason.DOUBLE_HIT, r.reason());
    }

    @Test
    void inDoublesPartnersMustAlternate() {
        PingPongGame g = game(Team.A);
        g.setTeamSize(Team.A, 2);
        g.setTeamSize(Team.B, 2);
        goodServe(g); // A1 serwuje
        assertNull(g.hit(Team.B, B1));
        g.bounce(Team.A);
        PointResult r = g.hit(Team.A, A1);
        assertNotNull(r, "A1 serwował i nie może odbić drugi raz z rzędu");
        assertEquals(Reason.DOUBLE_HIT, r.reason());
        assertEquals(Team.B, r.winner());

        PingPongGame h = game(Team.A);
        h.setTeamSize(Team.A, 2);
        h.setTeamSize(Team.B, 2);
        goodServe(h);
        h.hit(Team.B, B1);
        h.bounce(Team.A);
        assertNull(h.hit(Team.A, A2), "partner odbija");
        h.bounce(Team.B);
        assertNull(h.hit(Team.B, B2), "B1 już odbijał, teraz B2");
        h.bounce(Team.A);
        assertNotNull(h.hit(Team.A, A2), "A2 odbił przed chwilą");
    }

    @Test
    void aServeNotHitInTimeLosesThePoint() {
        PingPongGame g = game(Team.A);
        PointResult r = g.serveTimeout();
        assertEquals(Team.B, r.winner());
        assertEquals(Reason.SERVE_TIMEOUT, r.reason());
    }

    @Test
    void onlyOnePointIsAwardedPerRally() {
        PingPongGame g = game(Team.A);
        goodServe(g);
        assertNotNull(g.deadBall());
        assertNull(g.deadBall());
        assertNull(g.bounce(Team.B));
        assertEquals(1, g.points(Team.A) + g.points(Team.B));
    }

    // ---------- punktacja ----------

    private void win(PingPongGame g, Team t) {
        g.beginRally();
        assertNotNull(g.award(t, Reason.OUT));
    }

    @Test
    void theServeChangesEveryTwoPoints() {
        PingPongGame g = game(Team.A);
        Team[] expected = {Team.A, Team.A, Team.B, Team.B, Team.A, Team.A, Team.B, Team.B};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], g.serving(), "punkt " + (i + 1));
            win(g, i % 2 == 0 ? Team.A : Team.B);
        }
    }

    @Test
    void atTenAllTheServeChangesEveryPoint() {
        PingPongGame g = game(Team.A);
        for (int i = 0; i < 10; i++) {
            win(g, Team.A);
            win(g, Team.B);
        }
        assertEquals(10, g.points(Team.A));
        assertEquals(10, g.points(Team.B));
        Team first = g.serving();
        win(g, Team.A);
        assertEquals(first.other(), g.serving());
        win(g, Team.B);
        assertEquals(first, g.serving());
    }

    @Test
    void aGameGoesToElevenButNeedsTwoClearPoints() {
        PingPongGame g = game(Team.A);
        for (int i = 0; i < 10; i++) win(g, Team.A);
        assertEquals(1, g.gameNumber(), "10:0 jeszcze nie koniec");
        g.beginRally();
        PointResult r = g.award(Team.A, Reason.OUT);
        assertTrue(r.gameFinished());
        assertEquals(1, g.games(Team.A));

        PingPongGame d = game(Team.A);
        for (int i = 0; i < 10; i++) {
            win(d, Team.A);
            win(d, Team.B);
        }
        win(d, Team.A);
        assertEquals(1, d.gameNumber(), "11:10 nie wystarczy");
        d.beginRally();
        PointResult over = d.award(Team.A, Reason.OUT);
        assertTrue(over.gameFinished(), "12:10");
        assertEquals(12, over.pointsA());
        assertEquals(10, over.pointsB());
    }

    @Test
    void afterAGameSidesSwapAndTheOtherTeamServesFirst() {
        PingPongGame g = game(Team.A);
        assertEquals(Team.A, g.lowSideOwner());
        for (int i = 0; i < 11; i++) win(g, Team.A);
        assertEquals(2, g.gameNumber());
        assertEquals(0, g.points(Team.A) + g.points(Team.B));
        assertEquals(Team.B, g.lowSideOwner(), "zmiana stron");
        assertEquals(Team.B, g.serving(), "w drugim gemie zaczyna druga drużyna");
        assertEquals(1, g.games(Team.A));
    }

    @Test
    void theMatchEndsWhenATeamWinsTheRequiredGames() {
        PingPongGame g = game(Team.A);
        PointResult last = null;
        for (int game = 0; game < 2; game++) {
            for (int i = 0; i < 11; i++) {
                g.beginRally();
                last = g.award(Team.B, Reason.OUT);
            }
        }
        assertTrue(last.matchFinished());
        assertEquals(Team.B, last.matchWinner());
        assertTrue(g.finished());
        g.beginRally();
        assertEquals(PingPongGame.Phase.FINISHED, g.phase(), "po meczu nic się nie zmienia");
    }

    @Test
    void theDecidingGameChangesEndsAtFivePoints() {
        PingPongGame g = new PingPongGame(PingPongGame.Config.standard(), Team.A);
        g.beginRally();
        // po jednym gemie na drużynę zaczyna się decydujący
        for (int i = 0; i < 11; i++) win(g, Team.A);
        for (int i = 0; i < 11; i++) win(g, Team.B);
        assertEquals(3, g.gameNumber());
        assertTrue(g.decidingGame());
        boolean swappedBefore = g.swapped();
        PointResult r = null;
        for (int i = 0; i < 5; i++) {
            g.beginRally();
            r = g.award(Team.A, Reason.OUT);
            if (i < 4) assertFalse(r.changeEnds());
        }
        assertTrue(r.changeEnds(), "po 5 punktach zmiana stron");
        assertEquals(!swappedBefore, g.swapped());
        g.beginRally();
        assertFalse(g.award(Team.A, Reason.OUT).changeEnds(), "tylko raz");
    }
}
