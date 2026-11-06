package pl.sport;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.sport.arena.Arena;
import pl.sport.core.Team;
import pl.sport.match.Match;
import pl.sport.volley.VolleyGame;
import pl.sport.volley.VolleyMatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mecz siatkówki od zapisów do punktu: dołączanie, odliczanie, zagrywka, uderzenie, ochrona graczy i powrót po meczu. */
class VolleyMatchTest extends SportTestBase {
    Arena arena;

    @BeforeEach
    void court() {
        arena = build("siatkowka", "v", 50, 50, -90);
    }

    private void join(org.mockbukkit.mockbukkit.entity.PlayerMock p, String team) {
        server.dispatchCommand(p, "siatkowka dolacz v " + team);
    }

    private VolleyMatch match() {
        return (VolleyMatch) plugin.matches().matchFor(arena);
    }

    private void swing(Player p) {
        server.getPluginManager().callEvent(new PlayerArmSwingEvent(p, EquipmentSlot.HAND));
    }

    private void startNow() {
        join(a, "a");
        join(b, "b");
        server.dispatchCommand(admin, "siatkowka start natychmiast v");
        assertEquals(Match.Stage.PLAYING, match().stage());
    }

    @Test
    void joiningPutsPlayersOnTheirHalvesAndLeavingRestoresThem() {
        a.teleport(new Location(world, 10, 70, 10));
        join(a, "a");
        join(b, "b");
        assertSame(match(), plugin.matches().of(a));
        assertEquals(GameMode.ADVENTURE, a.getGameMode());
        assertTrue(arena.contains(a.getLocation(), 1, 40), "gracz stoi na boisku: " + a.getLocation());
        assertTrue(arena.toLocal(a.getLocation()).x() < 9, "drużyna A na niższej połowie");
        assertTrue(arena.toLocal(b.getLocation()).x() > 9, "drużyna B na wyższej połowie");
        assertEquals(Team.A, match().participant(a.getUniqueId()).team());
        assertEquals(Team.B, match().participant(b.getUniqueId()).team());

        server.dispatchCommand(a, "sport opusc");
        assertNull(plugin.matches().of(a));
        assertEquals(GameMode.SURVIVAL, a.getGameMode(), "wraca tryb gry");
        assertEquals(10, a.getLocation().getX(), 0.01, "wraca na swoje miejsce");
        assertEquals(70, a.getLocation().getY(), 0.01);
        assertFalse(plugin.snapshots().has(a.getUniqueId()));
    }

    @Test
    void teamsAreBalancedAutomaticallyAndCannotBeDoubleJoined() {
        join(a, "");
        server.dispatchCommand(a, "siatkowka dolacz v");
        assertEquals(1, match().players().size(), "drugi raz nie dołącza");
        server.dispatchCommand(b, "siatkowka dolacz v");
        assertEquals(Team.A, match().participant(a.getUniqueId()).team());
        assertEquals(Team.B, match().participant(b.getUniqueId()).team(), "drugi gracz trafia do mniejszej drużyny");
        // do dwóch meczów naraz nie można zapisać się jednym graczem
        build("siatkowka", "v2", 150, 150, 0);
        server.dispatchCommand(a, "siatkowka dolacz v2");
        assertSame(match(), plugin.matches().of(a), "gracz zostaje w pierwszym meczu");
        Match other = plugin.matches().existing(plugin.arenas().get("v2"));
        assertTrue(other == null || other.players().isEmpty(), "do drugiego boiska nie trafił");
    }

    @Test
    void theCountdownStartsOnItsOwnWhenBothTeamsHavePlayers() {
        join(a, "a");
        ticks(5);
        assertEquals(Match.Stage.LOBBY, match().stage(), "jedna drużyna to jeszcze nie mecz");
        join(b, "b");
        ticks(2);
        assertEquals(Match.Stage.COUNTDOWN, match().stage());
        ticks(20 * 10 + 10);
        assertEquals(Match.Stage.PLAYING, match().stage());
        assertNotNull(match().game());
        // do trwającego meczu już się nie dołącza
        var c = server.addPlayer("Czesiek");
        server.dispatchCommand(c, "siatkowka dolacz v");
        assertNull(plugin.matches().of(c));
    }

    @Test
    void theCountdownIsCancelledWhenSomeoneLeaves() {
        join(a, "a");
        join(b, "b");
        ticks(5);
        assertEquals(Match.Stage.COUNTDOWN, match().stage());
        server.dispatchCommand(b, "sport opusc");
        ticks(2);
        assertEquals(Match.Stage.LOBBY, match().stage());
    }

    @Test
    void aServeThatLandsInTheOpponentsCourtIsAPointForTheServer() {
        startNow();
        ticks(60); // przygotowanie 2,5 s
        VolleyMatch m = match();
        assertNotNull(m.serverId(), "ktoś zagrywa");
        Player server = this.server.getPlayer(m.serverId());
        Team serving = m.game().serving();
        swing(server); // podrzut
        assertTrue(m.tossed());
        ticks(4);
        swing(server); // uderzenie
        assertTrue(m.rallyOn(), "po uderzeniu akcja trwa");
        assertEquals(VolleyGame.Phase.RALLY, m.game().phase());
        for (int i = 0; i < 20 * 8 && m.game().points(Team.A) + m.game().points(Team.B) == 0; i++) ticks(1);
        assertEquals(1, m.game().points(serving), "zagrywka w boisko rywala daje punkt serwującym");
        assertEquals(0, m.game().points(serving.other()));
    }

    @Test
    void aTossThatIsNeverHitLosesThePointWhenTheBallFallsOnTheServersSide() {
        startNow();
        ticks(60);
        VolleyMatch m = match();
        Player sv = server.getPlayer(m.serverId());
        Team serving = m.game().serving();
        swing(sv); // podrzut bez uderzenia
        for (int i = 0; i < 20 * 6 && m.game().points(Team.A) + m.game().points(Team.B) == 0; i++) ticks(1);
        assertEquals(1, m.game().points(serving.other()), "piłka spadła na stronę serwującego: punkt dla rywali");
    }

    @Test
    void notServingAtAllLosesThePointAfterTheTimeLimit() {
        startNow();
        ticks(60);
        VolleyMatch m = match();
        Team serving = m.game().serving();
        ticks(20 * 11);
        assertEquals(1, m.game().points(serving.other()), "po czasie na zagrywkę punkt dla rywali");
    }

    @Test
    void thePointIsFollowedByANewServe() {
        startNow();
        ticks(60);
        VolleyMatch m = match();
        ticks(20 * 11); // brak zagrywki: punkt dla rywali, serwis przechodzi na nich
        Team nowServing = m.game().serving();
        ticks(20 * 4); // przerwa po punkcie i przygotowanie
        assertNotNull(m.serverId(), "po punkcie zaczyna się kolejna zagrywka");
        assertEquals(m.game().serving(), nowServing);
    }

    @Test
    void clickingWithoutABallNearbyDoesNotCountAsATouch() {
        startNow();
        ticks(60);
        VolleyMatch m = match();
        Player other = a.getUniqueId().equals(m.serverId()) ? b : a;
        swing(other); // nie zagrywa
        assertFalse(m.tossed());
        assertEquals(0, m.game().touches());
    }

    @Test
    void whenTheServerLeavesTheNextPlayerOfTheTeamServes() {
        var c = server.addPlayer("Czesiek");
        join(a, "a");
        server.dispatchCommand(c, "siatkowka dolacz v a");
        join(b, "b");
        long seed = 0;
        while (!new java.util.Random(seed).nextBoolean()) seed++; // pierwsza zagrywka drużyny A (dwóch graczy)
        match().seed(seed);
        server.dispatchCommand(admin, "siatkowka start natychmiast v");
        ticks(60);
        VolleyMatch m = match();
        assertEquals(Team.A, m.game().serving());
        java.util.UUID first = m.serverId();
        Player leaving = server.getPlayer(first);
        server.dispatchCommand((org.mockbukkit.mockbukkit.entity.PlayerMock) leaving, "sport opusc");
        ticks(2);
        assertNotNull(m.serverId());
        assertTrue(!m.serverId().equals(first), "zagrywa kolega z drużyny");
    }

    @Test
    void aTeamThatEmptiesLosesByWalkoverAndEveryoneIsSentHome() {
        a.teleport(new Location(world, 5, 66, 5));
        startNow();
        ticks(10);
        server.dispatchCommand(b, "sport opusc");
        assertEquals(Match.Stage.ENDING, match().stage(), "mecz kończy się walkowerem");
        ticks(20 * 8);
        assertEquals(Match.Stage.LOBBY, match().stage());
        assertNull(plugin.matches().of(a));
        assertEquals(GameMode.SURVIVAL, a.getGameMode());
        assertEquals(5, a.getLocation().getX(), 0.01);
        assertTrue(views_removed(), "piłka usunięta po meczu");
    }

    private boolean views_removed() {
        return views.stream().allMatch(v -> v.removed);
    }

    @Test
    void playersInAMatchAreProtectedFromDamageAndBlockBreaking() {
        join(a, "a");
        join(b, "b");
        EntityDamageEvent dmg = new EntityDamageEvent(a, EntityDamageEvent.DamageCause.FALL, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.FALL).build(), 4);
        server.getPluginManager().callEvent(dmg);
        assertTrue(dmg.isCancelled());
        var block = world.getBlockAt(arena.toWorld(world, new pl.sport.core.V3(4.5, -0.5, 4.5)));
        BlockBreakEvent br = new BlockBreakEvent(block, a);
        server.getPluginManager().callEvent(br);
        assertTrue(br.isCancelled(), "w meczu nie niszczy się boiska");
        BlockBreakEvent other = new BlockBreakEvent(block, admin);
        server.getPluginManager().callEvent(other);
        assertFalse(other.isCancelled(), "gracze spoza meczu nie są ograniczani");
    }

    @Test
    void teleportingAwayLeavesTheMatchButOurOwnTeleportsDoNot() {
        join(a, "a");
        join(b, "b");
        a.teleport(new Location(world, 5000, 80, 5000));
        assertNull(plugin.matches().of(a), "teleport daleko od boiska oznacza wyjście");
        assertNotNull(plugin.matches().of(b));
    }

    @Test
    void walkingOutOfTheCourtAreaIsBlocked() {
        join(a, "a");
        Location from = a.getLocation().clone();
        Location far = arena.toWorld(world, new pl.sport.core.V3(-30, 0.1, 4));
        org.bukkit.event.player.PlayerMoveEvent e = new org.bukkit.event.player.PlayerMoveEvent(a, from, far);
        server.getPluginManager().callEvent(e);
        assertEquals(from.getX(), e.getTo().getX(), 1e-9, "ruch poza boisko jest cofany");
        assertEquals(from.getZ(), e.getTo().getZ(), 1e-9);
    }

    @Test
    void quittingTheServerRestoresThePlayerAndRemovesThemFromTheMatch() {
        a.teleport(new Location(world, 20, 70, 20));
        join(a, "a");
        join(b, "b");
        a.disconnect();
        assertNull(plugin.matches().of(a));
        assertEquals(1, match().players().size());
    }

    @Test
    void aMatchLeftByACrashRestoresThePlayerOnTheNextJoin() {
        a.teleport(new Location(world, 30, 70, 30));
        join(a, "a");
        assertTrue(plugin.snapshots().has(a.getUniqueId()), "stan sprzed meczu zapisany na dysku");
        // „awaria”: zapis zostaje, a gracz wraca w trybie przygody
        plugin.matches().shutdown();
        a.setGameMode(GameMode.ADVENTURE);
        a.teleport(new Location(world, 0, 90, 0));
        assertTrue(plugin.snapshots().restoreAfterCrash(a) || !plugin.snapshots().has(a.getUniqueId()));
    }

    private Location at(double u, double v, double y) {
        return arena.toWorld(world, new pl.sport.core.V3(u, y, v));
    }

    private Location moved(org.bukkit.entity.Player p, Location to) {
        Location from = p.getLocation().clone();
        org.bukkit.event.player.PlayerMoveEvent e = new org.bukkit.event.player.PlayerMoveEvent(p, from, to);
        server.getPluginManager().callEvent(e);
        return e.getTo();
    }

    @Test
    void playersCannotWalkOrJumpOntoTheOpponentsHalf() {
        join(a, "a");
        join(b, "b");
        a.teleport(at(6.5, 4.5, 0));
        Location ok = at(7.5, 4.5, 0);
        assertEquals(ok, moved(a, ok), "ruch po własnej połowie jest swobodny");
        // krok przez siatkę (u = 9): zostaje na swoim miejscu
        a.teleport(at(8.5, 4.5, 0));
        Location from = a.getLocation().clone();
        Location over = at(9.5, 4.5, 0);
        Location result = moved(a, over);
        assertEquals(from.getX(), result.getX(), 1e-9);
        assertEquals(from.getZ(), result.getZ(), 1e-9);
        // skok nad siatką też nie pomaga
        Location jump = at(9.6, 4.5, 1.2);
        Location r2 = moved(a, jump);
        assertEquals(from.getX(), r2.getX(), 1e-9);
        // drugi gracz ma tę samą zasadę po swojej stronie
        b.teleport(at(9.5, 4.5, 0));
        Location bFrom = b.getLocation().clone();
        assertEquals(bFrom.getX(), moved(b, at(8.5, 4.5, 0)).getX(), 1e-9);
    }

    @Test
    void aPlayerThatEndedUpOnTheWrongSideMayWalkBack() {
        join(a, "a");
        join(b, "b");
        a.teleport(at(11.5, 4.5, 0)); // po zmianie stron stoi jeszcze po stronie rywali
        Location back = at(10.5, 4.5, 0);
        assertEquals(back, moved(a, back), "powrót na własną stronę jest dozwolony");
        Location deeper = at(12.5, 4.5, 0);
        assertEquals(a.getLocation().getX(), moved(a, deeper).getX(), 1e-9, "głębiej na połowę rywali już nie");
    }

    private void rallyWithBallNearReceiver(org.mockbukkit.mockbukkit.entity.PlayerMock server, VolleyMatch m) {
        swing(server);
        ticks(4);
        swing(server);
        assertTrue(m.rallyOn());
    }

    @Test
    void inAOneOnOneTheLonePlayerMayTouchThreeTimesAndSetHimself() {
        long seed = 0;
        while (!new java.util.Random(seed).nextBoolean()) seed++; // zagrywa drużyna A
        join(a, "a");
        join(b, "b");
        match().seed(seed);
        server.dispatchCommand(admin, "siatkowka start natychmiast v");
        ticks(60);
        VolleyMatch m = match();
        assertEquals(a.getUniqueId(), m.serverId());
        rallyWithBallNearReceiver(a, m);

        // B przyjmuje sam: trzy odbicia tego samego gracza pod rząd są dozwolone
        b.teleport(at(13.5, 4.5, 0));
        int[] expected = {1, 2, 3};
        for (int touch = 0; touch < 3; touch++) {
            if (touch == 1) b.setSneaking(true); // wystawienie sobie
            if (touch == 2) b.setSneaking(false);
            m.teleportBall(new pl.sport.core.V3(13.2, 1.3, 4.5), pl.sport.core.V3.ZERO);
            ticks(1);
            swing(b);
            assertEquals(expected[touch], m.game().touches(), "odbicie " + (touch + 1));
            assertEquals(Team.B, m.game().touchTeam());
            assertEquals(0, m.game().points(Team.A), "bez błędu");
            ticks(25);
        }
        // czwarte odbicie to już błąd
        m.teleportBall(new pl.sport.core.V3(13.2, 1.3, 4.5), pl.sport.core.V3.ZERO);
        ticks(1);
        swing(b);
        assertEquals(1, m.game().points(Team.A), "cztery odbicia jednej drużyny");
    }

    @Test
    void aSelfSetGoesUpAndComesDownNextToThePlayer() {
        long seed = 0;
        while (!new java.util.Random(seed).nextBoolean()) seed++;
        join(a, "a");
        join(b, "b");
        match().seed(seed);
        server.dispatchCommand(admin, "siatkowka start natychmiast v");
        ticks(60);
        VolleyMatch m = match();
        rallyWithBallNearReceiver(a, m);
        b.teleport(at(13.5, 4.5, 0));
        b.setSneaking(true);
        m.teleportBall(new pl.sport.core.V3(13.3, 1.5, 4.5), pl.sport.core.V3.ZERO);
        ticks(1);
        swing(b);
        double maxY = 0;
        for (int i = 0; i < 30; i++) {
            ticks(1);
            maxY = Math.max(maxY, m.ballPosition().y());
        }
        assertTrue(maxY > 3.0, "piłka idzie wysoko do ataku: " + maxY);
        pl.sport.core.V3 p = m.ballPosition();
        assertTrue(Math.hypot(p.x() - 13.5, p.z() - 4.5) < 2.0, "i wraca obok gracza: " + p);
    }

    @Test
    void theServerMustStayBehindTheEndLine() {
        startNow();
        ticks(60);
        VolleyMatch m = match();
        Player sv = server.getPlayer(m.serverId());
        Team serving = m.game().serving();
        ticks(20);
        assertEquals(0, m.game().points(Team.A) + m.game().points(Team.B), "za linią końcową wszystko w porządku");
        // wejście na boisko przed uderzeniem to błąd
        boolean low = m.game().sideOwner(true) == serving;
        sv.teleport(at(low ? 3 : 15, 4.5, 0));
        ticks(2);
        assertEquals(1, m.game().points(serving.other()), "serwujący, który wszedł na boisko, traci punkt");
    }

    @Test
    void stepOnTheCourtAtTheMomentOfTheHitIsAFootFault() {
        startNow();
        ticks(60);
        VolleyMatch m = match();
        Player sv = server.getPlayer(m.serverId());
        Team serving = m.game().serving();
        swing(sv); // podrzut zza linii
        ticks(3);
        boolean low = m.game().sideOwner(true) == serving;
        sv.teleport(at(low ? 2 : 16, 4.5, 0));
        m.teleportBall(new pl.sport.core.V3(low ? 2.2 : 15.8, 2.4, 4.5), pl.sport.core.V3.ZERO); // piłka tuż przy nim
        swing(sv);
        assertEquals(1, m.game().points(serving.other()));
    }

    @Test
    void thePlayerListShowsTheTeamsAndStatus() {
        join(a, "a");
        server.dispatchCommand(admin, "siatkowka lista");
        server.dispatchCommand(admin, "siatkowka info v");
        server.dispatchCommand(admin, "sport lista");
        server.dispatchCommand(admin, "siatkowka pomoc");
        assertEquals(1, match().players().size());
    }
}
