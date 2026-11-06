package pl.sport;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mecz koszykówki: wznowienie, kozłowanie, rzut do kosza (z fizyką), podanie, przechwyt, aut, zegar akcji i koniec meczu. */
class BasketMatchTest extends SportTestBase {
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

    private PlayerSwapHandItemsEvent swap(Player p) {
        PlayerSwapHandItemsEvent e = new PlayerSwapHandItemsEvent(p, new ItemStack(org.bukkit.Material.AIR), new ItemStack(org.bukkit.Material.AIR));
        server.getPluginManager().callEvent(e);
        return e;
    }

    /** Ustawia ziarno tak, żeby rzut sędziowski wygrała drużyna `first` (losowanie robi mecz przy starcie). */
    private void seedFirst(Team first) {
        long seed = 0;
        while ((new java.util.Random(seed).nextBoolean() ? Team.A : Team.B) != first) seed++;
        match().seed(seed);
    }

    private void startTwoPlayers() {
        join(a, "a");
        join(b, "b");
        server.dispatchCommand(admin, "koszykowka start natychmiast b");
        assertEquals(Match.Stage.PLAYING, match().stage());
        ticks(2);
    }

    private PlayerMock playerOf(Team t) {
        return match().participant(a.getUniqueId()).team() == t ? a : b;
    }

    /** Ustawia gracza w punkcie boiska i każe mu patrzeć w stronę innego punktu (pitch w stopniach, ujemny = w górę). */
    private void place(PlayerMock p, V3 at, V3 lookAt, float pitch) {
        Location l = arena.toWorld(world, at);
        V3 d = new V3(lookAt.x() - at.x(), 0, lookAt.z() - at.z());
        l.setYaw(arena.yawOf(d));
        l.setPitch(pitch);
        p.teleport(l);
    }

    /** Doprowadza do tego, że drużyna `t` ma piłkę w rękach gracza `p` (wznowienie z środka boiska). */
    private void giveBall(Team t, PlayerMock p) {
        assertEquals(t, match().game().inbound(), "ta drużyna wznawia");
        V3 spot = new V3(arena.length() / 2.0, 0, arena.width() / 2.0);
        place(p, spot, spot.plus(new V3(1, 0, 0)), 0);
        ticks(3);
        assertEquals(p.getUniqueId(), match().holder(), "gracz wziął piłkę");
    }

    @Test
    void theGameStartsWithADeadBallForTheInboundTeamAtCenterCourt() {
        startTwoPlayers();
        BasketGame g = match().game();
        assertEquals(BasketGame.Phase.DEAD, g.phase());
        assertNotNull(g.inbound());
        V3 p = match().ballPosition();
        assertEquals(arena.length() / 2.0, p.x(), 0.01);
        assertEquals(arena.width() / 2.0, p.z(), 0.01);
        assertEquals(1.05, p.y(), 0.1, "piłka unosi się na wysokości pasa");
        assertEquals(1, views.size());
    }

    @Test
    void onlyTheInboundTeamCanTakeTheBallAtTheRestart() {
        startTwoPlayers();
        Team in = match().game().inbound();
        PlayerMock inbound = playerOf(in);
        PlayerMock other = playerOf(in.other());
        V3 spot = new V3(arena.length() / 2.0, 0, arena.width() / 2.0);
        place(other, spot, spot.plus(new V3(1, 0, 0)), 0);
        ticks(5);
        assertNull(match().holder(), "rywal nie bierze piłki ze wznowienia");
        place(inbound, spot, spot.plus(new V3(1, 0, 0)), 0);
        ticks(3);
        assertEquals(inbound.getUniqueId(), match().holder());
        assertEquals(BasketGame.Phase.LIVE, match().game().phase());
    }

    @Test
    void theBallFollowsThePlayerWhoDribblesIt() {
        startTwoPlayers();
        Team in = match().game().inbound();
        PlayerMock p = playerOf(in);
        giveBall(in, p);
        place(p, new V3(8, 0, 4), new V3(20, 0, 4), 0);
        ticks(5);
        V3 ball = match().ballPosition();
        V3 feet = arena.toLocal(p.getLocation());
        assertTrue(Math.hypot(ball.x() - feet.x(), ball.z() - feet.z()) < 1.0, "piłka przy graczu: " + ball + " vs " + feet);
        assertTrue(ball.y() > 0.1 && ball.y() < 1.5, "kozłowanie na wysokości: " + ball.y());
        // widok piłki dostaje to samo położenie w świecie
        Location view = lastView().last;
        Location expected = arena.toWorld(world, ball);
        assertEquals(expected.getX(), view.getX(), 0.5);
        assertEquals(expected.getZ(), view.getZ(), 0.5);
    }

    private void aimedShotScores(double fromRim, int expectedPoints) {
        plugin.getConfig().set("koszykowka.celnosc", 100.0);
        startTwoPlayers();
        BasketGame g = match().game();
        Team t = g.inbound();
        PlayerMock shooter = playerOf(t);
        giveBall(t, shooter);
        int hoop = g.attackedHoop(t);
        V3 rim = match().court().rimCenter(hoop);
        double sign = hoop == 0 ? 1 : -1; // z której strony kosza stoi rzucający (w głąb boiska)
        place(shooter, new V3(rim.x() + sign * fromRim, 0, rim.z()), rim, -12);
        ticks(2);
        swing(shooter);
        assertTrue(g.shotInFlight(), "rzut w locie");
        assertNull(match().holder());
        for (int i = 0; i < 20 * 5 && g.score(t) == 0; i++) ticks(1);
        assertEquals(expectedPoints, g.score(t), "rzut z " + fromRim + " m");
        assertEquals(BasketGame.Phase.DEAD, g.phase(), "po koszu gra czeka na wznowienie");
        assertEquals(t.other(), g.inbound());
    }

    @Test
    void anAimedShotFromMidRangeGoesInForTwoPoints() {
        aimedShotScores(4.5, 2);
    }

    @Test
    void aShotFromBeyondTheArcIsWorthThree() {
        aimedShotScores(7.6, 3);
    }

    @Test
    void afterABasketTheOtherTeamRestartsFromItsBaseline() {
        aimedShotScores(3.0, 2);
        BasketMatch m = match();
        ticks(20 * 3); // przerwa po koszu
        V3 ball = m.ballPosition();
        int hoop = m.game().attackedHoop(m.game().inbound().other());
        // piłka stoi tuż przy linii końcowej kosza, do którego wpadła
        double expectedU = hoop == 0 ? 1.0 : arena.length() - 1.0;
        assertEquals(expectedU, ball.x(), 0.5);
        PlayerMock inbound = playerOf(m.game().inbound());
        V3 spot = new V3(expectedU, 0, arena.width() / 2.0);
        place(inbound, spot, new V3(arena.length() / 2.0, 0, arena.width() / 2.0), 0);
        ticks(4);
        assertEquals(inbound.getUniqueId(), m.holder(), "wznawiający bierze piłkę z linii końcowej");
    }

    @Test
    void aShotWithoutAimingAtTheHoopDoesNotScoreAndTheBallIsLoose() {
        startTwoPlayers();
        BasketGame g = match().game();
        Team t = g.inbound();
        PlayerMock shooter = playerOf(t);
        giveBall(t, shooter);
        int hoop = g.attackedHoop(t);
        V3 rim = match().court().rimCenter(hoop);
        // rzucający stoi twarzą od kosza
        place(shooter, new V3(rim.x() + (hoop == 0 ? 6 : -6), 0, rim.z()), new V3(rim.x() + (hoop == 0 ? 20 : -20), 0, rim.z()), -20);
        ticks(2);
        swing(shooter);
        assertTrue(g.shotInFlight());
        ticks(20 * 6);
        assertEquals(0, g.score(Team.A) + g.score(Team.B));
        assertFalse(g.shotInFlight(), "rzut się rozstrzygnął");
    }

    @Test
    void anOpponentCanStealTheBallFromTheDribbler() {
        plugin.getConfig().set("koszykowka.szansa-przechwytu", 10.0);
        startTwoPlayers();
        Team t = match().game().inbound();
        PlayerMock holder = playerOf(t);
        PlayerMock thief = playerOf(t.other());
        giveBall(t, holder);
        place(holder, new V3(8, 0, 6), new V3(20, 0, 6), 0);
        place(thief, new V3(7.2, 0, 6), new V3(20, 0, 6), 0);
        ticks(3);
        swing(thief);
        assertEquals(thief.getUniqueId(), match().holder(), "przechwyt udany");
        assertEquals(t.other(), match().game().possession());
    }

    @Test
    void aStealFromTooFarAwayFails() {
        plugin.getConfig().set("koszykowka.szansa-przechwytu", 10.0);
        startTwoPlayers();
        Team t = match().game().inbound();
        PlayerMock holder = playerOf(t);
        PlayerMock thief = playerOf(t.other());
        giveBall(t, holder);
        place(holder, new V3(8, 0, 6), new V3(20, 0, 6), 0);
        place(thief, new V3(3, 0, 6), new V3(20, 0, 6), 0);
        ticks(3);
        swing(thief);
        assertEquals(holder.getUniqueId(), match().holder());
    }

    @Test
    void passingSendsTheBallToTheTeammateYouLookAt() {
        join(a, "a");
        server.dispatchCommand(c, "koszykowka dolacz b a");
        join(b, "b");
        seedFirst(Team.A);
        server.dispatchCommand(admin, "koszykowka start natychmiast b");
        ticks(2);
        BasketMatch m = match();
        assertEquals(Team.A, m.game().inbound());
        PlayerMock passer = a;
        PlayerMock mate = c;
        giveBall(Team.A, passer);
        place(passer, new V3(8, 0, 4), new V3(16, 0, 4), 0);
        place(mate, new V3(16, 0, 4), new V3(8, 0, 4), 0);
        ticks(2);
        PlayerSwapHandItemsEvent e = swap(passer);
        assertTrue(e.isCancelled(), "klawisz F w meczu nie zamienia przedmiotów");
        assertNull(m.holder(), "piłka w locie");
        for (int i = 0; i < 20 * 3 && m.holder() == null; i++) ticks(1);
        assertEquals(mate.getUniqueId(), m.holder(), "kolega złapał podanie");
        assertEquals(Team.A, m.game().possession());
    }

    @Test
    void aBallThrownOutOfTheCourtGivesTheOtherTeamARestart() {
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock p = playerOf(t);
        giveBall(t, p);
        // stoi przy linii bocznej i podaje na zewnątrz boiska
        place(p, new V3(14, 0, 1.2), new V3(14, 0, -10), 0);
        ticks(2);
        swap(p);
        for (int i = 0; i < 20 * 3 && m.game().phase() == BasketGame.Phase.LIVE; i++) ticks(1);
        assertEquals(BasketGame.Phase.DEAD, m.game().phase(), "aut zatrzymuje grę");
        assertEquals(t.other(), m.game().inbound());
        V3 ball = m.ballPosition();
        assertTrue(ball.z() >= 0 && ball.z() < 2, "piłka czeka tuż przy linii bocznej: " + ball);
    }

    @Test
    void carryingTheBallOutOfTheCourtIsAnOutOfBounds() {
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock p = playerOf(t);
        giveBall(t, p);
        place(p, new V3(14, 0, -1.5), new V3(14, 0, -9), 0); // za linią boczną
        ticks(3);
        assertEquals(BasketGame.Phase.DEAD, m.game().phase());
        assertEquals(t.other(), m.game().inbound());
        assertNull(m.holder());
    }

    @Test
    void theShotClockTurnsTheBallOver() {
        startTwoPlayers();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock p = playerOf(t);
        giveBall(t, p);
        ticks(20 * 25);
        assertEquals(BasketGame.Phase.DEAD, m.game().phase(), "po 24 s naruszenie");
        assertEquals(t.other(), m.game().inbound());
        assertNull(m.holder());
    }

    @Test
    void thePointsModeEndsTheGameAtTheTargetAndSendsEveryoneHome() {
        plugin.getConfig().set("koszykowka.punkty-do-wygrania", 2);
        plugin.getConfig().set("koszykowka.celnosc", 100.0);
        a.teleport(new Location(world, 5, 66, 5));
        startTwoPlayers();
        BasketGame g = match().game();
        Team t = g.inbound();
        PlayerMock shooter = playerOf(t);
        giveBall(t, shooter);
        int hoop = g.attackedHoop(t);
        V3 rim = match().court().rimCenter(hoop);
        place(shooter, new V3(rim.x() + (hoop == 0 ? 3.5 : -3.5), 0, rim.z()), rim, -12);
        ticks(2);
        swing(shooter);
        for (int i = 0; i < 20 * 5 && !g.ended(); i++) ticks(1);
        assertTrue(g.ended(), "do 2 punktów: jeden kosz kończy mecz");
        assertEquals(Match.Stage.ENDING, match().stage());
        ticks(20 * 8);
        assertEquals(Match.Stage.LOBBY, match().stage());
        assertNull(plugin.matches().of(a));
    }

    @Test
    void leavingWithTheBallDropsItForEveryone() {
        join(a, "a");
        server.dispatchCommand(c, "koszykowka dolacz b a");
        join(b, "b");
        seedFirst(Team.A);
        server.dispatchCommand(admin, "koszykowka start natychmiast b");
        ticks(2);
        BasketMatch m = match();
        Team in = m.game().inbound();
        PlayerMock p = a;
        giveBall(in, p);
        server.dispatchCommand(p, "sport opusc");
        assertNull(m.holder());
        assertNull(m.game().possession());
        assertEquals(BasketGame.Phase.LIVE, m.game().phase());
    }

    @Test
    void basketballPlayersAreProtectedLikeVolleyballPlayers() {
        join(a, "a");
        join(b, "b");
        org.bukkit.event.entity.EntityDamageEvent e = new org.bukkit.event.entity.EntityDamageEvent(a, org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.FALL).build(), 3);
        server.getPluginManager().callEvent(e);
        assertTrue(e.isCancelled());
        assertTrue(swap(a).isCancelled(), "w basketballu klawisz F należy do gry");
        assertFalse(swap(admin).isCancelled());
    }
}
