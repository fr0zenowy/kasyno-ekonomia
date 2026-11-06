package pl.sport.basket;

import org.junit.jupiter.api.Test;
import pl.sport.basket.BasketGame.FoulType;
import pl.sport.basket.BasketGame.Kind;
import pl.sport.basket.BasketGame.Update;
import pl.sport.core.Team;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faule, rzuty osobiste, premia i limit fauli w silniku zasad koszykówki. */
class BasketFoulTest {
    private static final UUID A1 = UUID.randomUUID();
    private static final UUID A2 = UUID.randomUUID();
    private static final UUID B1 = UUID.randomUUID();
    private static final UUID B2 = UUID.randomUUID();

    private BasketGame game(int bonus, int limit) {
        BasketGame g = new BasketGame(new BasketGame.Config(300, 1000, 14, 60, 0, bonus, limit), Team.A, Team.A);
        assertTrue(g.pickup(Team.A, A1));
        return g;
    }

    private static boolean has(List<Update> l, Kind k) {
        return l.stream().anyMatch(u -> u.kind() == k);
    }

    private static Update find(List<Update> l, Kind k) {
        return l.stream().filter(u -> u.kind() == k).findFirst().orElseThrow();
    }

    @Test
    void aShootingFoulBeforeTheReleaseGivesTwoFreeThrows() {
        BasketGame g = game(5, 0);
        List<Update> u = g.foul(FoulType.SHOOTING, B1, Team.B, A1, Team.A);
        assertTrue(has(u, Kind.FOUL));
        assertEquals(Team.B, find(u, Kind.FOUL).team());
        assertEquals(BasketGame.Phase.FREE_THROW, g.phase());
        assertEquals(Team.A, g.freeThrowTeam());
        assertEquals(A1, g.freeThrowShooter());
        assertEquals(2, g.freeThrowsLeft());
        assertEquals(1, g.freeThrowNumber());
        assertEquals(1, g.teamFouls(Team.B));
        assertEquals(1, g.personalFouls(B1));
        assertNull(g.holder(), "piłka nie jest już trzymana");
    }

    @Test
    void aFouledMissedThreePointShotGivesThreeFreeThrows() {
        BasketGame g = game(5, 0);
        g.shoot(1, 3);
        List<Update> u = g.foul(FoulType.SHOOTING, B1, Team.B, A1, Team.A);
        assertEquals(3, find(u, Kind.FOUL).points());
        assertEquals(BasketGame.Phase.LIVE, g.phase(), "piłka jeszcze leci");
        assertTrue(g.pendingShotFoul());
        assertFalse(g.pickup(Team.B, B2), "nikt nie łapie piłki z rzutu z faulem");
        List<Update> after = g.shotResolved();
        assertTrue(has(after, Kind.FREE_THROWS));
        assertEquals(BasketGame.Phase.FREE_THROW, g.phase());
        assertEquals(3, g.freeThrowsLeft());
        assertEquals(3, g.freeThrowsTotal());
    }

    @Test
    void aMadeShotDespiteAFoulCountsAndGivesOneFreeThrow() {
        BasketGame g = game(5, 0);
        g.shoot(1, 2);
        g.foul(FoulType.SHOOTING, B1, Team.B, A1, Team.A);
        List<Update> u = g.basket(1);
        assertEquals(2, find(u, Kind.SCORE).points());
        assertEquals(2, g.score(Team.A));
        assertEquals(BasketGame.Phase.FREE_THROW, g.phase());
        assertEquals(1, g.freeThrowsLeft(), "dodatkowy rzut osobisty");
        List<Update> ft = g.freeThrowResult(true);
        assertEquals(3, g.score(Team.A));
        assertEquals(BasketGame.Phase.DEAD, g.phase());
        assertEquals(Team.B, g.inbound(), "po celnym ostatnim rzucie piłkę wznawia rywal");
        assertTrue(has(ft, Kind.SCORE));
    }

    @Test
    void freeThrowsAreOnePointEachAndTheLastMissIsAReboundForEveryone() {
        BasketGame g = game(5, 0);
        g.foul(FoulType.SHOOTING, B1, Team.B, A1, Team.A);
        List<Update> first = g.freeThrowResult(true);
        assertEquals(1, g.score(Team.A));
        assertEquals(BasketGame.Phase.FREE_THROW, g.phase());
        assertEquals(1, g.freeThrowsLeft());
        assertEquals(2, g.freeThrowNumber());
        assertTrue(has(first, Kind.FREE_THROWS));
        g.freeThrowResult(false);
        assertEquals(1, g.score(Team.A));
        assertEquals(BasketGame.Phase.LIVE, g.phase(), "po pudle w ostatnim rzucie piłka jest w grze");
        assertNull(g.possession());
        assertTrue(g.pickup(Team.B, B2), "zbiórka rywala");
        assertEquals(24 > 0 ? 1000 : 0, g.shotClock(), 1e-9);
    }

    @Test
    void aMissedFirstFreeThrowDoesNotEndTheTrip() {
        BasketGame g = game(5, 0);
        g.foul(FoulType.SHOOTING, B1, Team.B, A1, Team.A);
        g.freeThrowResult(false);
        assertEquals(BasketGame.Phase.FREE_THROW, g.phase());
        g.freeThrowResult(true);
        assertEquals(1, g.score(Team.A));
        assertEquals(BasketGame.Phase.DEAD, g.phase());
        assertEquals(Team.B, g.inbound());
    }

    @Test
    void ordinaryFoulsGiveTheBallUntilTheTeamReachesTheBonus() {
        BasketGame g = game(3, 0);
        for (int i = 1; i <= 2; i++) {
            if (g.phase() == BasketGame.Phase.DEAD) assertTrue(g.pickup(Team.A, A1));
            List<Update> u = g.foul(FoulType.PERSONAL, B1, Team.B, A1, Team.A);
            assertEquals(0, find(u, Kind.FOUL).points(), "foul " + i + " bez rzutów");
            assertEquals(BasketGame.Phase.DEAD, g.phase());
            assertEquals(Team.A, g.inbound(), "piłka dla poszkodowanych");
        }
        assertTrue(g.pickup(Team.A, A1));
        List<Update> bonus = g.foul(FoulType.PERSONAL, B2, Team.B, A1, Team.A);
        assertEquals(2, find(bonus, Kind.FOUL).points(), "trzeci faul drużyny to premia: dwa rzuty");
        assertEquals(BasketGame.Phase.FREE_THROW, g.phase());
        assertEquals(3, g.teamFouls(Team.B));
    }

    @Test
    void anOffensiveFoulIsATurnoverWithoutShotsAndDoesNotCountForTheTeam() {
        BasketGame g = game(5, 0);
        List<Update> u = g.foul(FoulType.OFFENSIVE, A1, Team.A, B1, Team.B);
        assertEquals(0, find(u, Kind.FOUL).points());
        assertEquals(BasketGame.Phase.DEAD, g.phase());
        assertEquals(Team.B, g.inbound());
        assertEquals(0, g.teamFouls(Team.A));
        assertEquals(1, g.personalFouls(A1));
    }

    @Test
    void passingTheFoulLimitDisqualifiesThePlayer() {
        BasketGame g = game(99, 3);
        UUID last = null;
        for (int i = 0; i < 3; i++) {
            if (g.phase() == BasketGame.Phase.DEAD) assertTrue(g.pickup(Team.A, A1));
            List<Update> u = g.foul(FoulType.PERSONAL, B1, Team.B, A1, Team.A);
            if (i < 2) {
                assertFalse(has(u, Kind.FOUL_OUT));
                assertNull(g.foulOutPlayer());
            } else {
                assertTrue(has(u, Kind.FOUL_OUT));
                last = g.foulOutPlayer();
            }
        }
        assertEquals(B1, last);
        assertEquals(3, g.personalFouls(B1));
    }

    @Test
    void foulsOnlyCountDuringLivePlay() {
        BasketGame g = new BasketGame(BasketGame.Config.timed(5), Team.A, Team.A);
        assertTrue(g.foul(FoulType.PERSONAL, B1, Team.B, A1, Team.A).isEmpty(), "piłka martwa: nie ma faulu");
        g.pickup(Team.A, A1);
        g.foul(FoulType.SHOOTING, B1, Team.B, A1, Team.A);
        assertTrue(g.foul(FoulType.PERSONAL, B2, Team.B, A2, Team.A).isEmpty(), "w trakcie rzutów osobistych też nie");
        assertEquals(1, g.teamFouls(Team.B));
    }

    @Test
    void clocksStandStillDuringFreeThrows() {
        BasketGame g = game(5, 0);
        g.tick(10);
        double clock = g.clock();
        g.foul(FoulType.SHOOTING, B1, Team.B, A1, Team.A);
        assertTrue(g.tick(60).isEmpty());
        assertEquals(clock, g.clock(), 1e-9);
        assertFalse(g.steal(Team.B, B1), "w trakcie rzutów osobistych nie ma przechwytu");
        assertFalse(g.pickup(Team.B, B2));
    }

    @Test
    void aFreeThrowCanWinThePointsGame() {
        BasketGame g = new BasketGame(new BasketGame.Config(0, 24, 14, 0, 2, 5, 0), Team.A, Team.A);
        g.pickup(Team.A, A1);
        g.shoot(1, 2);
        g.basket(1); // 2:0 kończy mecz do 2 punktów
        assertTrue(g.ended());

        BasketGame h = new BasketGame(new BasketGame.Config(0, 24, 14, 0, 2, 5, 0), Team.A, Team.A);
        h.pickup(Team.A, A1);
        h.foul(FoulType.SHOOTING, B1, Team.B, A1, Team.A);
        h.freeThrowResult(true);
        assertFalse(h.ended());
        List<Update> u = h.freeThrowResult(true);
        assertTrue(has(u, Kind.GAME_OVER));
        assertEquals(Team.A, find(u, Kind.GAME_OVER).team());
    }

    @Test
    void aBuzzerThatSoundedBeforeTheLastFreeThrowEndsTheGameAfterIt() {
        BasketGame g = new BasketGame(new BasketGame.Config(10, 1000, 14, 5, 0, 5, 0), Team.A, Team.A);
        g.pickup(Team.A, A1);
        g.tick(9.5);
        g.foul(FoulType.SHOOTING, B1, Team.B, A1, Team.A);
        g.tick(5); // czas stoi w rzutach osobistych
        assertEquals(0.5, g.clock(), 1e-6);
        g.freeThrowResult(true);
        List<Update> u = g.freeThrowResult(true);
        assertEquals(BasketGame.Phase.DEAD, g.phase());
        assertFalse(has(u, Kind.GAME_OVER), "jeszcze zostało pół sekundy");
        g.pickup(Team.B, B1);
        List<Update> end = g.tick(1);
        assertTrue(has(end, Kind.GAME_OVER));
        assertEquals(Team.A, find(end, Kind.GAME_OVER).team());
    }

    @Test
    void aFouledShotThatLeavesTheCourtStillGivesFreeThrows() {
        BasketGame g = game(5, 0);
        g.shoot(1, 3);
        g.foul(FoulType.SHOOTING, B1, Team.B, A1, Team.A);
        List<Update> u = g.outOfBounds();
        assertTrue(has(u, Kind.FREE_THROWS));
        assertEquals(BasketGame.Phase.FREE_THROW, g.phase());
        assertEquals(3, g.freeThrowsLeft());
    }

    @Test
    void teamFoulsResetInOvertime() {
        BasketGame g = new BasketGame(new BasketGame.Config(10, 1000, 14, 5, 0, 5, 0), Team.A, Team.A);
        g.pickup(Team.A, A1);
        g.foul(FoulType.PERSONAL, B1, Team.B, A1, Team.A);
        g.pickup(Team.A, A1);
        g.tick(10);
        assertTrue(g.overtime());
        assertEquals(0, g.teamFouls(Team.B));
    }
}
