package pl.sport;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.sport.arena.Arena;
import pl.sport.basket.BasketGame;
import pl.sport.basket.BasketMatch;
import pl.sport.core.Team;
import pl.sport.core.V3;
import pl.sport.match.Match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Koszykówka: dwutakt, wsad, faule, rzuty osobiste i przechwyty w prawdziwym meczu (na atrapie serwera). */
class BasketTechniqueTest extends SportTestBase {
    Arena arena;
    PlayerMock c;

    @BeforeEach
    void court() {
        arena = build("koszykowka", "b", 50, 50, -90);
        c = server.addPlayer("Czesiek");
    }

    private BasketMatch match() {
        return (BasketMatch) plugin.matches().matchFor(arena);
    }

    private void join(PlayerMock p, String team) {
        server.dispatchCommand(p, "koszykowka dolacz b " + team);
    }

    private void swing(Player p) {
        server.getPluginManager().callEvent(new PlayerArmSwingEvent(p, EquipmentSlot.HAND));
    }

    private void seedFirst(Team first) {
        long seed = 0;
        while ((new java.util.Random(seed).nextBoolean() ? Team.A : Team.B) != first) seed++;
        match().seed(seed);
    }

    private void startTwoPlayers() {
        join(a, "a");
        join(b, "b");
        match().seed(4242); // losowość meczu (faule, przechwyty) ma być w testach powtarzalna
        server.dispatchCommand(admin, "koszykowka start natychmiast b");
        assertEquals(Match.Stage.PLAYING, match().stage());
        ticks(2);
    }

    private PlayerMock playerOf(Team t) {
        return match().participant(a.getUniqueId()).team() == t ? a : b;
    }

    private void place(PlayerMock p, V3 at, V3 lookAt, float pitch) {
        Location l = arena.toWorld(world, at);
        V3 d = new V3(lookAt.x() - at.x(), 0, lookAt.z() - at.z());
        l.setYaw(arena.yawOf(d));
        l.setPitch(pitch);
        p.teleport(l);
    }

    private void giveBall(Team t, PlayerMock p) {
        assertEquals(t, match().game().inbound(), "ta drużyna wznawia");
        V3 spot = new V3(arena.length() / 2.0, 0, arena.width() / 2.0);
        place(p, spot, spot.plus(new V3(1, 0, 0)), 0);
        ticks(3);
        assertEquals(p.getUniqueId(), match().holder(), "gracz wziął piłkę");
    }

    private Location moved(Player p, Location to) {
        Location from = p.getLocation().clone();
        PlayerMoveEvent e = new PlayerMoveEvent(p, from, to);
        server.getPluginManager().callEvent(e);
        return e.getTo();
    }

    /** Gracz z piłką biegnie do kosza ze stałą prędkością ok. 6 m/s (0,3 bloku na tick), od `fromDist` do `toDist` metrów od obręczy. */
    private void runAtHoop(PlayerMock p, int hoop, double fromDist, double toDist) {
        V3 rim = match().court().rimCenter(hoop);
        double sign = hoop == 0 ? 1 : -1;
        for (double d = fromDist; d >= toDist - 1e-9; d -= 0.3) {
            place(p, new V3(rim.x() + sign * d, 0, rim.z()), rim, -12);
            ticks(1);
        }
    }

    /** Odsuwa rywala w róg boiska, żeby nie stał na drodze biegnącego (inaczej bieg w niego bywa faulem). */
    private void parkOpponent(Team t) {
        place(playerOf(t.other()), new V3(arena.length() / 2.0, 0, 0.6), new V3(arena.length() / 2.0, 0, 5), 0);
        ticks(3);
    }

    /** Jak runAtHoop, ale gracz jest w powietrzu (skacze) na wysokości `y` nad podłogą. */
    private void jumpAtHoop(PlayerMock p, int hoop, double fromDist, double toDist, double y) {
        V3 rim = match().court().rimCenter(hoop);
        double sign = hoop == 0 ? 1 : -1;
        p.setOnGround(false);
        for (double d = fromDist; d >= toDist - 1e-9; d -= 0.3) {
            place(p, new V3(rim.x() + sign * d, y, rim.z()), rim, -12);
            ticks(1);
        }
    }

    private void waitForScore(Team t, int maxTicks) {
        BasketGame g = match().game();
        for (int i = 0; i < maxTicks && g.score(t) == 0; i++) ticks(1);
    }

    // ---------- dwutakt i wsad ----------

    @Test
    void aRunningShotNearTheHoopIsATwoStepLayupThatGoesIn() {
        plugin.getConfig().set("koszykowka.celnosc", 100.0);
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock p = playerOf(t);
        giveBall(t, p);
        int hoop = m.game().attackedHoop(t);
        runAtHoop(p, hoop, 7.0, 4.0);
        swing(p);
        assertTrue(m.hasPendingMove(p.getUniqueId()), "dwutakt rusza dopiero po krótkiej akcji");
        assertEquals(p.getUniqueId(), m.holder(), "piłka jeszcze w rękach");
        ticks(10);
        assertFalse(m.hasPendingMove(p.getUniqueId()));
        assertEquals("Dwutakt", m.technique());
        assertNull(m.holder());
        waitForScore(t, 20 * 5);
        assertEquals(2, m.game().score(t), "dwutakt jest za 2 punkty");
        assertEquals("Dwutakt", m.scoredTechnique());
    }

    @Test
    void aJumpingRunUnderTheHoopIsADunkThatGoesInWhenItIsEasyEnough() {
        plugin.getConfig().set("koszykowka.wsad-trudnosc", 0.0); // wsad zawsze wpada: sprawdzamy tylko, że rozpoznaje wsad
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock p = playerOf(t);
        parkOpponent(t);
        giveBall(t, p);
        int hoop = m.game().attackedHoop(t);
        jumpAtHoop(p, hoop, 5.5, 2.3, 0.6);
        swing(p);
        assertTrue(m.hasPendingMove(p.getUniqueId()));
        ticks(10);
        waitForScore(t, 20 * 3);
        assertEquals(2, m.game().score(t), "wsad bez przeszkód wpada");
        assertEquals("Wsad", m.scoredTechnique());
    }

    @Test
    void aRunWithoutAJumpUnderTheHoopIsOnlyALayup() {
        plugin.getConfig().set("koszykowka.wsad-trudnosc", 0.0);
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock p = playerOf(t);
        parkOpponent(t);
        giveBall(t, p);
        int hoop = m.game().attackedHoop(t);
        runAtHoop(p, hoop, 5.5, 2.3);
        swing(p);
        assertTrue(m.hasPendingMove(p.getUniqueId()));
        ticks(10);
        assertEquals("Dwutakt", m.technique(), "bez wyskoku nie ma wsadu, nawet tuż pod koszem");
    }

    @Test
    void jumpingOnTheSpotUnderTheHoopIsNotADunkEither() {
        plugin.getConfig().set("koszykowka.wsad-trudnosc", 0.0);
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock p = playerOf(t);
        parkOpponent(t);
        giveBall(t, p);
        int hoop = m.game().attackedHoop(t);
        V3 rim = m.court().rimCenter(hoop);
        double sign = hoop == 0 ? 1 : -1;
        p.setOnGround(false);
        place(p, new V3(rim.x() + sign * 1.8, 0.6, rim.z()), rim, -12);
        ticks(3);
        swing(p);
        assertFalse(m.hasPendingMove(p.getUniqueId()), "bez rozbiegu wyskok to zwykły rzut");
        assertTrue(m.game().shotInFlight());
    }

    @Test
    void aHardDunkOftenMissesBouncesOffTheRimAndTheDunkerMustRest() {
        plugin.getConfig().set("koszykowka.wsad-trudnosc", 10.0); // szansa 5%
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock p = playerOf(t);
        parkOpponent(t);
        giveBall(t, p);
        int hoop = m.game().attackedHoop(t);
        jumpAtHoop(p, hoop, 5.5, 2.3, 0.6);
        swing(p);
        ticks(10);
        assertNull(m.technique(), "wsad się nie udał");
        assertEquals(0, m.game().score(t), "niecelny wsad nie daje punktów");
        // kolejna próba w czasie odpoczynku jest zwykłym dwutaktem
        for (int i = 0; i < 20 * 8 && m.holder() == null; i++) ticks(1); // piłka wraca do gry (zbiórka)
        if (m.holder() == null || !m.holder().equals(p.getUniqueId())) {
            m.teleportBall(new V3(arena.length() / 2.0, 1.0, arena.width() / 2.0), V3.ZERO);
            place(p, new V3(arena.length() / 2.0, 0, arena.width() / 2.0), new V3(arena.length() / 2.0 + 1, 0, arena.width() / 2.0), 0);
            ticks(3);
        }
        assertEquals(p.getUniqueId(), m.holder(), "gracz znów ma piłkę");
        jumpAtHoop(p, hoop, 5.5, 2.3, 0.6);
        swing(p);
        assertTrue(m.hasPendingMove(p.getUniqueId()));
        ticks(10);
        assertEquals("Dwutakt", m.technique(), "wsad odpoczywa: wyskok pod koszem to dwutakt");
    }

    @Test
    void shootingFromStandingStillNearTheHoopIsAnOrdinaryShot() {
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock p = playerOf(t);
        giveBall(t, p);
        int hoop = m.game().attackedHoop(t);
        V3 rim = m.court().rimCenter(hoop);
        double sign = hoop == 0 ? 1 : -1;
        place(p, new V3(rim.x() + sign * 3.5, 0, rim.z()), rim, -12);
        ticks(3);
        swing(p);
        assertFalse(m.hasPendingMove(p.getUniqueId()), "bez rozbiegu nie ma dwutaktu");
        assertTrue(m.game().shotInFlight(), "zwykły rzut leci od razu");
        assertNull(m.technique());
    }

    @Test
    void runningFromTooFarAwayIsNotALayup() {
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock p = playerOf(t);
        parkOpponent(t);
        giveBall(t, p);
        int hoop = m.game().attackedHoop(t);
        runAtHoop(p, hoop, 11.0, 8.0);
        swing(p);
        assertFalse(m.hasPendingMove(p.getUniqueId()));
        assertTrue(m.game().shotInFlight());
    }

    @Test
    void techniquesCanBeSwitchedOffInTheConfig() {
        plugin.getConfig().set("koszykowka.techniki", false);
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock p = playerOf(t);
        giveBall(t, p);
        int hoop = m.game().attackedHoop(t);
        runAtHoop(p, hoop, 6.0, 3.0);
        swing(p);
        assertFalse(m.hasPendingMove(p.getUniqueId()));
        assertTrue(m.game().shotInFlight());
    }

    @Test
    void aMoveIsCancelledWhenTheBallIsStolenDuringIt() {
        plugin.getConfig().set("koszykowka.szansa-przechwytu", 10.0);
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock p = playerOf(t);
        PlayerMock thief = playerOf(t.other());
        giveBall(t, p);
        int hoop = m.game().attackedHoop(t);
        V3 rim = m.court().rimCenter(hoop);
        double sign = hoop == 0 ? 1 : -1;
        place(thief, new V3(rim.x() + sign * 7.2, 0, rim.z() + 3), rim, 0);
        runAtHoop(p, hoop, 7.0, 4.0);
        swing(p);
        assertTrue(m.hasPendingMove(p.getUniqueId()));
        place(thief, new V3(rim.x() + sign * 4.8, 0, rim.z()), new V3(rim.x() + sign * 4.0, 0, rim.z()), 0);
        ticks(1);
        swing(thief); // w tej chwili dwutakt jeszcze trwa
        assertEquals(thief.getUniqueId(), m.holder(), "piłkę da się wybić w trakcie najścia");
        ticks(10);
        assertFalse(m.hasPendingMove(p.getUniqueId()));
        assertEquals(0, m.game().score(t));
    }

    // ---------- faule ----------

    @Test
    void aFailedStealWithContactIsAFoulAndTheBallGoesToTheFouledTeam() {
        plugin.getConfig().set("koszykowka.szansa-przechwytu", 0.0);
        plugin.getConfig().set("koszykowka.szansa-faulu", 100.0);
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock holder = playerOf(t);
        PlayerMock thief = playerOf(t.other());
        giveBall(t, holder);
        place(holder, new V3(8, 0, 6), new V3(20, 0, 6), 0);
        place(thief, new V3(7.2, 0, 6), new V3(20, 0, 6), 0);
        ticks(3);
        for (int i = 0; i < 30 && m.game().teamFouls(t.other()) == 0 && m.holder() != null && m.holder().equals(holder.getUniqueId()); i++) {
            swing(thief);
            ticks(25);
        }
        BasketGame g = m.game();
        assertNotEquals(thief.getUniqueId(), m.holder(), "przechwyt nie powinien się udać przy szansie 0");
        assertEquals(1, g.teamFouls(t.other()), "faul zapisany drużynie przechwytującego");
        assertEquals(1, g.personalFouls(thief.getUniqueId()));
        assertEquals(BasketGame.Phase.DEAD, g.phase());
        assertEquals(t, g.inbound(), "piłka dla sfaulowanych");
        assertNull(m.holder());
        V3 ball = m.ballPosition();
        assertTrue(ball.z() < 2.0 || ball.z() > arena.width() - 2.0, "wznowienie przy linii bocznej: " + ball);
    }

    @Test
    void runningStraightIntoAStandingDefenderIsAnOffensiveFoul() {
        plugin.getConfig().set("koszykowka.szansa-faulu", 100.0);
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock holder = playerOf(t);
        PlayerMock def = playerOf(t.other());
        place(def, new V3(17.5, 0, 7.5), new V3(5, 0, 7.5), 0);
        ticks(3);
        giveBall(t, holder);
        for (double x = 14.0; x <= 17.05 && m.holder() != null; x += 0.3) {
            place(holder, new V3(x, 0, 7.5), new V3(30, 0, 7.5), 0);
            ticks(1);
        }
        BasketGame g = m.game();
        assertEquals(1, g.personalFouls(holder.getUniqueId()), "faul w ataku");
        assertEquals(0, g.teamFouls(t), "faule w ataku nie liczą się do premii drużyny");
        assertEquals(BasketGame.Phase.DEAD, g.phase());
        assertEquals(t.other(), g.inbound(), "strata piłki");
    }

    @Test
    void walkingIntoADefenderSlowlyIsNotAFoul() {
        plugin.getConfig().set("koszykowka.szansa-faulu", 100.0);
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock holder = playerOf(t);
        PlayerMock def = playerOf(t.other());
        place(def, new V3(16.0, 0, 7.5), new V3(5, 0, 7.5), 0);
        ticks(3);
        giveBall(t, holder);
        for (double x = 14.5; x <= 15.7; x += 0.05) {
            place(holder, new V3(x, 0, 7.5), new V3(30, 0, 7.5), 0);
            ticks(1);
        }
        assertEquals(0, m.game().personalFouls(holder.getUniqueId()));
        assertEquals(holder.getUniqueId(), m.holder());
    }

    @Test
    void foulsCanBeSwitchedOff() {
        plugin.getConfig().set("koszykowka.faule", false);
        plugin.getConfig().set("koszykowka.szansa-faulu", 100.0);
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock holder = playerOf(t);
        PlayerMock def = playerOf(t.other());
        place(def, new V3(17.5, 0, 7.5), new V3(5, 0, 7.5), 0);
        ticks(3);
        giveBall(t, holder);
        for (double x = 14.0; x <= 17.05; x += 0.3) {
            place(holder, new V3(x, 0, 7.5), new V3(30, 0, 7.5), 0);
            ticks(1);
        }
        assertEquals(0, m.game().personalFouls(holder.getUniqueId()));
    }

    @Test
    void passingTheFoulLimitSendsThePlayerOffTheCourt() {
        plugin.getConfig().set("koszykowka.szansa-przechwytu", 0.0);
        plugin.getConfig().set("koszykowka.szansa-faulu", 100.0);
        plugin.getConfig().set("koszykowka.faule-limit", 1);
        join(a, "a");
        join(b, "b");
        join(c, "b");
        seedFirst(Team.A);
        server.dispatchCommand(admin, "koszykowka start natychmiast b");
        ticks(2);
        BasketMatch m = match();
        assertEquals(Team.A, m.game().inbound());
        giveBall(Team.A, a);
        place(a, new V3(8, 0, 6), new V3(20, 0, 6), 0);
        place(b, new V3(7.2, 0, 6), new V3(20, 0, 6), 0);
        place(c, new V3(3, 0, 12), new V3(20, 0, 6), 0);
        ticks(3);
        for (int i = 0; i < 40 && m.participant(b.getUniqueId()) != null && m.holder() != null && m.holder().equals(a.getUniqueId()); i++) {
            swing(b);
            ticks(25);
        }
        assertNull(m.participant(b.getUniqueId()), "pierwszy faul przy limicie 1 kończy grę zawodnika");
        assertNull(plugin.matches().of(b));
        assertNotNull(m.participant(c.getUniqueId()));
        assertEquals(Match.Stage.PLAYING, m.stage(), "mecz toczy się dalej");
    }

    // ---------- rzuty osobiste ----------

    /** Wywołuje faul na koszykarzu z piłką (nieudany przechwyt, premia od pierwszego faulu = dwa rzuty osobiste). */
    private PlayerMock foulSetup() {
        plugin.getConfig().set("koszykowka.szansa-przechwytu", 0.0);
        plugin.getConfig().set("koszykowka.szansa-faulu", 100.0);
        plugin.getConfig().set("koszykowka.faule-premia", 1);
        plugin.getConfig().set("koszykowka.celnosc", 100.0);
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock holder = playerOf(t);
        PlayerMock thief = playerOf(t.other());
        giveBall(t, holder);
        place(holder, new V3(8, 0, 6), new V3(20, 0, 6), 0);
        place(thief, new V3(7.2, 0, 6), new V3(20, 0, 6), 0);
        ticks(3);
        for (int i = 0; i < 30 && !m.inFreeThrows() && m.holder() != null && m.holder().equals(holder.getUniqueId()); i++) {
            swing(thief);
            ticks(25);
        }
        return holder;
    }

    @Test
    void aFoulInTheBonusSendsTheVictimToTheFreeThrowLine() {
        PlayerMock shooter = foulSetup();
        BasketMatch m = match();
        assertTrue(m.inFreeThrows(), "faul w premii kończy się rzutami osobistymi");
        BasketGame g = m.game();
        assertEquals(BasketGame.Phase.FREE_THROW, g.phase());
        assertEquals(shooter.getUniqueId(), g.freeThrowShooter());
        assertEquals(2, g.freeThrowsTotal());
        int hoop = g.attackedHoop(g.freeThrowTeam());
        V3 at = arena.toLocal(shooter.getLocation());
        double expectedU = hoop == 0 ? 5.8 : arena.length() - 5.8;
        assertEquals(expectedU, at.x(), 0.2, "linia rzutów osobistych");
        assertEquals(arena.width() / 2.0, at.z(), 0.6);
        V3 ball = m.ballPosition();
        assertTrue(Math.hypot(ball.x() - at.x(), ball.z() - at.z()) < 1.0, "piłka w rękach wykonawcy");
    }

    @Test
    void playersCannotMoveDuringFreeThrows() {
        foulSetup();
        BasketMatch m = match();
        assertTrue(m.inFreeThrows());
        PlayerMock other = playerOf(m.game().freeThrowTeam().other());
        Location from = other.getLocation().clone();
        Location to = from.clone().add(2, 0, 0);
        Location result = moved(other, to);
        assertEquals(from.getX(), result.getX(), 1e-9, "w czasie rzutów osobistych nikt się nie rusza");
        assertEquals(from.getZ(), result.getZ(), 1e-9);
    }

    @Test
    void bothFreeThrowsAreShotAndThePointsCountThenTheOtherTeamRestarts() {
        PlayerMock shooter = foulSetup();
        BasketMatch m = match();
        assertTrue(m.inFreeThrows());
        BasketGame g = m.game();
        Team t = g.freeThrowTeam();
        for (int i = 0; i < 20 * 60 && m.inFreeThrows(); i++) {
            swing(shooter);
            ticks(1);
        }
        assertFalse(m.inFreeThrows(), "rzuty osobiste się skończyły");
        int hits = g.score(t);
        assertTrue(hits >= 1 && hits <= 2, "przy bardzo wysokiej celności rzuty wpadają: " + hits);
        if (hits == 2) {
            assertEquals(BasketGame.Phase.DEAD, g.phase());
            assertEquals(t.other(), g.inbound(), "po celnym ostatnim rzucie piłkę wznawia rywal");
        }
    }

    @Test
    void anUnshotFreeThrowIsReleasedAutomaticallyAfterTheTimeLimit() {
        foulSetup();
        BasketMatch m = match();
        assertTrue(m.inFreeThrows());
        BasketGame g = m.game();
        for (int i = 0; i < 20 * 90 && m.inFreeThrows(); i++) ticks(1);
        assertFalse(m.inFreeThrows(), "mecz nie może stać w miejscu, gdy wykonawca nie rzuca");
        assertTrue(g.freeThrowsLeft() == 0);
    }

    @Test
    void theShooterLeavingDuringFreeThrowsHandsThemToATeammate() {
        plugin.getConfig().set("koszykowka.szansa-przechwytu", 0.0);
        plugin.getConfig().set("koszykowka.szansa-faulu", 100.0);
        plugin.getConfig().set("koszykowka.faule-premia", 1);
        join(a, "a");
        join(c, "a");
        join(b, "b");
        seedFirst(Team.A);
        server.dispatchCommand(admin, "koszykowka start natychmiast b");
        ticks(2);
        BasketMatch m = match();
        giveBall(Team.A, a);
        place(a, new V3(8, 0, 6), new V3(20, 0, 6), 0);
        place(b, new V3(7.2, 0, 6), new V3(20, 0, 6), 0);
        place(c, new V3(3, 0, 12), new V3(20, 0, 6), 0);
        ticks(3);
        for (int i = 0; i < 30 && !m.inFreeThrows() && m.holder() != null && m.holder().equals(a.getUniqueId()); i++) {
            swing(b);
            ticks(25);
        }
        assertTrue(m.inFreeThrows());
        assertEquals(a.getUniqueId(), m.game().freeThrowShooter());
        server.dispatchCommand(a, "sport opusc");
        assertTrue(m.inFreeThrows(), "rzuty osobiste trwają dalej");
        assertEquals(c.getUniqueId(), m.game().freeThrowShooter(), "kolega z drużyny przejmuje rzuty");
    }
}
