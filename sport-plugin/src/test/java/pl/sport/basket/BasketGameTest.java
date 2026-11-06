package pl.sport.basket;

import org.junit.jupiter.api.Test;
import pl.sport.basket.BasketGame.Kind;
import pl.sport.basket.BasketGame.Update;
import pl.sport.core.Team;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zasady koszykówki: posiadanie piłki, zegary, punkty, aut, przechwyt, dogrywka. */
class BasketGameTest {
    private static final UUID A1 = UUID.randomUUID();
    private static final UUID A2 = UUID.randomUUID();
    private static final UUID B1 = UUID.randomUUID();
    private static final UUID B2 = UUID.randomUUID();

    /** Mecz na 5 minut, kosz 0 broni drużyna A, a wznawia A. */
    private BasketGame game() {
        return new BasketGame(BasketGame.Config.timed(5), Team.A, Team.A);
    }

    private BasketGame live() {
        BasketGame g = game();
        assertTrue(g.pickup(Team.A, A1));
        return g;
    }

    private static boolean has(List<Update> list, Kind k) {
        return list.stream().anyMatch(u -> u.kind() == k);
    }

    private static Update find(List<Update> list, Kind k) {
        return list.stream().filter(u -> u.kind() == k).findFirst().orElseThrow();
    }

    @Test
    void theGameStartsDeadAndOnlyTheInboundTeamMayPickUpTheBall() {
        BasketGame g = game();
        assertEquals(BasketGame.Phase.DEAD, g.phase());
        assertFalse(g.pickup(Team.B, B1), "piłkę wznawia A");
        assertTrue(g.pickup(Team.A, A1));
        assertEquals(BasketGame.Phase.LIVE, g.phase());
        assertEquals(A1, g.holder());
        assertEquals(Team.A, g.possession());
    }

    @Test
    void aHeldBallCannotBePickedUpButCanBeStolen() {
        BasketGame g = live();
        assertFalse(g.pickup(Team.B, B1), "piłka jest trzymana");
        assertFalse(g.steal(Team.A, A2), "nie można ukraść piłki koledze");
        assertTrue(g.steal(Team.B, B1));
        assertEquals(B1, g.holder());
        assertEquals(Team.B, g.possession());
        assertEquals(24, g.shotClock(), 1e-9, "nowe posiadanie: pełne 24 s");
    }

    @Test
    void aLooseBallAfterAPassCanBePickedUpByTheOtherTeam() {
        BasketGame g = live();
        g.pass();
        assertNull(g.holder());
        assertEquals(Team.A, g.possession(), "piłka w locie podania nadal należy do drużyny");
        assertTrue(g.pickup(Team.B, B1), "przechwyt podania");
        assertEquals(Team.B, g.possession());
    }

    @Test
    void shotsAreWorthTwoOrThreePointsAndTheOtherTeamGetsTheBall() {
        BasketGame g = live();
        g.shoot(1, 3); // A atakuje kosz 1
        List<Update> u = g.basket(1);
        assertEquals(Kind.SCORE, u.get(0).kind());
        assertEquals(Team.A, u.get(0).team());
        assertEquals(3, u.get(0).points());
        assertEquals(3, g.score(Team.A));
        assertEquals(BasketGame.Phase.DEAD, g.phase());
        assertEquals(Team.B, g.inbound(), "po koszu wznawia drużyna, która straciła punkty");

        assertTrue(g.pickup(Team.B, B1));
        g.shoot(0, 2);
        g.basket(0);
        assertEquals(2, g.score(Team.B));
    }

    @Test
    void aBasketWithoutARegisteredShotIsWorthTwoAndGoesToTheOpposingTeamOfTheHoopOwner() {
        BasketGame g = live();
        // piłka wpadła do kosza A (własnego), np. po odbiciu: punkty dla B
        List<Update> u = g.basket(0);
        assertEquals(Team.B, find(u, Kind.SCORE).team());
        assertEquals(2, find(u, Kind.SCORE).points());
    }

    @Test
    void theBallIsDeadAfterABasketAndNoSecondBasketCounts() {
        BasketGame g = live();
        g.shoot(1, 2);
        g.basket(1);
        assertTrue(g.basket(1).isEmpty(), "w martwej piłce kosz się nie liczy");
        assertEquals(2, g.score(Team.A));
    }

    @Test
    void outOfBoundsGivesTheBallToTheTeamThatDidNotTouchItLast() {
        BasketGame g = live();
        g.pass();
        List<Update> u = g.outOfBounds();
        assertEquals(Kind.OUT_OF_BOUNDS, u.get(0).kind());
        assertEquals(Team.B, u.get(0).team());
        assertEquals(Team.B, g.inbound());
        assertEquals(BasketGame.Phase.DEAD, g.phase());
        assertFalse(g.pickup(Team.A, A2));
        assertTrue(g.pickup(Team.B, B1));
    }

    @Test
    void theShotClockExpiresAndTurnsTheBallOver() {
        BasketGame g = live();
        assertTrue(g.tick(23.9).isEmpty());
        List<Update> u = g.tick(0.2);
        assertEquals(Kind.SHOT_CLOCK, u.get(0).kind());
        assertEquals(Team.B, u.get(0).team());
        assertEquals(BasketGame.Phase.DEAD, g.phase());
        assertEquals(Team.B, g.inbound());
    }

    @Test
    void theShotClockStopsWhileAShotIsInTheAirAndResetsToFourteenAfterAnOffensiveRebound() {
        BasketGame g = live();
        g.tick(15); // zostało 9 s
        g.shoot(1, 2);
        g.tick(5);
        assertEquals(9, g.shotClock(), 0.01, "w locie rzutu zegar stoi");
        g.rimTouch();
        assertTrue(g.pickup(Team.A, A2), "zbiórka we własnym ataku");
        assertEquals(14, g.shotClock(), 1e-9, "po trafieniu w obręcz minimum 14 s");

        // zbiórka rywala daje pełne 24 s
        g.shoot(1, 2);
        g.rimTouch();
        g.shotResolved();
        assertTrue(g.pickup(Team.B, B1));
        assertEquals(24, g.shotClock(), 1e-9);
    }

    @Test
    void theClockRunsOnlyWhileTheBallIsLive() {
        BasketGame g = game();
        g.tick(30);
        assertEquals(300, g.clock(), 1e-9, "w martwej piłce czas stoi");
        g.pickup(Team.A, A1);
        g.tick(10);
        assertEquals(290, g.clock(), 1e-6);
        g.shoot(1, 2);
        g.basket(1);
        g.tick(10);
        assertEquals(290, g.clock(), 1e-6, "po koszu czas znów stoi");
    }

    @Test
    void theHigherScoreWinsWhenTimeRunsOut() {
        // zegar 24 s wyłączony (1000 s), żeby test sprawdzał tylko czas meczu
        BasketGame g = new BasketGame(new BasketGame.Config(300, 1000, 14, 60, 0), Team.A, Team.A);
        g.pickup(Team.A, A1);
        g.shoot(1, 2);
        g.basket(1);
        g.pickup(Team.B, B1);
        List<Update> u = null;
        // czas ucieka, ale zegar 24 s odmierza posiadanie, więc przekładamy piłkę podaniem
        for (int i = 0; i < 300 && !g.ended(); i++) {
            g.pass();
            g.pickup(Team.B, B2);
            u = g.tick(1);
        }
        assertTrue(g.ended());
        assertTrue(has(u, Kind.GAME_OVER));
        assertEquals(Team.A, find(u, Kind.GAME_OVER).team());
    }

    @Test
    void aTieGoesToOvertimeAndTheOvertimeWinnerTakesTheGame() {
        BasketGame g = new BasketGame(new BasketGame.Config(10, 24, 14, 5, 0), Team.A, Team.A);
        g.pickup(Team.A, A1);
        List<Update> u = g.tick(10);
        assertTrue(has(u, Kind.OVERTIME));
        assertTrue(g.overtime());
        assertEquals(BasketGame.Phase.LIVE, g.phase());
        g.shoot(1, 2);
        g.basket(1);
        g.pickup(Team.B, B1);
        g.pass();
        g.pickup(Team.B, B2);
        u = g.tick(5.1);
        assertTrue(has(u, Kind.GAME_OVER));
        assertEquals(Team.A, find(u, Kind.GAME_OVER).team());
    }

    @Test
    void withoutOvertimeTimeTheFirstBasketAfterATieWins() {
        BasketGame g = new BasketGame(new BasketGame.Config(10, 24, 14, 0, 0), Team.A, Team.A);
        g.pickup(Team.A, A1);
        g.tick(10);
        assertTrue(g.suddenDeath());
        assertFalse(g.ended());
        g.shoot(0, 2); // własny kosz A: punkty dla B
        List<Update> u = g.basket(0);
        assertEquals(Team.B, find(u, Kind.GAME_OVER).team());
    }

    @Test
    void aShotReleasedBeforeTheBuzzerStillCounts() {
        BasketGame g = new BasketGame(new BasketGame.Config(10, 24, 14, 5, 0), Team.A, Team.A);
        g.pickup(Team.A, A1);
        g.tick(9);
        g.shoot(1, 3);
        List<Update> u = g.tick(2); // syrena w trakcie lotu piłki
        assertFalse(has(u, Kind.GAME_OVER), "czekamy, aż rzut się rozstrzygnie");
        assertFalse(g.ended());
        u = g.basket(1);
        assertTrue(has(u, Kind.GAME_OVER));
        assertEquals(Team.A, find(u, Kind.GAME_OVER).team());
        assertEquals(3, g.score(Team.A));
    }

    @Test
    void aMissedShotAfterTheBuzzerEndsThePeriod() {
        BasketGame g = new BasketGame(new BasketGame.Config(10, 24, 14, 5, 0), Team.A, Team.A);
        g.pickup(Team.A, A1);
        g.tick(9);
        g.shoot(1, 3);
        g.tick(2);
        List<Update> u = g.shotResolved();
        assertTrue(has(u, Kind.OVERTIME), "rzut nie wpadł, wynik 0:0 to dogrywka: " + u);
    }

    @Test
    void pointsModeEndsAtTheTargetAndIgnoresTime() {
        BasketGame g = new BasketGame(BasketGame.Config.toPoints(6), Team.A, Team.A);
        g.pickup(Team.A, A1);
        g.shoot(1, 2);
        g.tick(10_000); // rzut w locie: zegar 24 s stoi, a czas meczu w tym trybie się nie liczy
        assertEquals(BasketGame.Phase.LIVE, g.phase());
        List<Update> u = null;
        for (int i = 0; i < 3; i++) {
            u = g.basket(1);
            if (i < 2) {
                g.pickup(Team.B, B1);
                g.pass();
                g.pickup(Team.A, A2);
                g.shoot(1, 2);
            }
        }
        assertEquals(6, g.score(Team.A));
        assertTrue(has(u, Kind.GAME_OVER));
        assertEquals(Team.A, find(u, Kind.GAME_OVER).team());
        assertTrue(g.ended());
    }

    @Test
    void aBlockedShotIsNoLongerAShot() {
        BasketGame g = live();
        g.shoot(1, 2);
        g.deflect(Team.B);
        assertFalse(g.shotInFlight());
        assertNull(g.possession());
        // kosz po zablokowanym rzucie liczy się jak zwykły (2 pkt), a nie jak rzut za trzy
        List<Update> u = g.basket(1);
        assertEquals(2, find(u, Kind.SCORE).points());
    }

    @Test
    void attackedHoopIsTheOneTheTeamDoesNotDefend() {
        BasketGame g = game();
        assertEquals(1, g.attackedHoop(Team.A));
        assertEquals(0, g.attackedHoop(Team.B));
        assertEquals(Team.A, g.defender(0));
        assertEquals(Team.B, g.defender(1));
    }
}
