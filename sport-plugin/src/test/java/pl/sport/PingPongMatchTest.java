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
import pl.sport.core.Team;
import pl.sport.core.V3;
import pl.sport.match.Match;
import pl.sport.pingpong.PingPongGame;
import pl.sport.pingpong.PingPongMatch;
import pl.sport.pingpong.PingPongTable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mecz ping-ponga: serwis zza linii końcowej, odbicia, błędy, punkty, ograniczenia ruchu i wyjścia graczy (na atrapie serwera). */
class PingPongMatchTest extends SportTestBase {
    Arena arena;
    PingPongTable table;

    @BeforeEach
    void hall() {
        // bardzo wysoka celność: błędy losowe zniknęłyby z testów, a tu sprawdzamy zasady i przepływ gry
        plugin.getConfig().set("pingpong.celnosc", 100.0);
        arena = build("pingpong", "p", 50, 50, -90);
        table = new PingPongTable(arena.length(), arena.width(), PingPongTable.scaleOfNet(arena.netHeight()));
    }

    private PingPongMatch match() {
        return (PingPongMatch) plugin.matches().matchFor(arena);
    }

    private void join(PlayerMock p, String team) {
        server.dispatchCommand(p, "pingpong dolacz p " + team);
    }

    private void swing(Player p) {
        server.getPluginManager().callEvent(new PlayerArmSwingEvent(p, EquipmentSlot.HAND));
    }

    private void startSingles() {
        join(a, "a");
        join(b, "b");
        match().seed(777);
        server.dispatchCommand(admin, "pingpong start natychmiast p");
        assertEquals(Match.Stage.PLAYING, match().stage());
        ticks(52); // przygotowanie przed pierwszym serwisem
    }

    private PlayerMock playerOf(Team t) {
        return match().participant(a.getUniqueId()).team() == t ? a : b;
    }

    private Team teamOf(PlayerMock p) {
        return match().participant(p.getUniqueId()).team();
    }

    private boolean lowSide(Team t) {
        return match().game().sideOwner(true) == t;
    }

    private V3 feetOf(Player p) {
        return arena.toLocal(p.getLocation());
    }

    /** Ustawia gracza w punkcie hali i każe mu patrzeć w punkt `at` (oczy 1,62 m nad stopami). */
    private void placeAndAim(PlayerMock p, V3 feet, V3 at) {
        Location l = arena.toWorld(world, feet);
        V3 eye = feet.plus(new V3(0, 1.62, 0));
        V3 d = at.minus(eye);
        l.setYaw(arena.yawOf(d.horizontal()));
        l.setPitch((float) Math.toDegrees(Math.atan2(-d.y(), d.horizontalLength())));
        p.teleport(l);
    }

    private void aim(PlayerMock p, V3 at) {
        V3 feet = feetOf(p);
        Location l = p.getLocation();
        V3 d = at.minus(feet.plus(new V3(0, 1.62, 0)));
        l.setYaw(arena.yawOf(d.horizontal()));
        l.setPitch((float) Math.toDegrees(Math.atan2(-d.y(), d.horizontalLength())));
        p.teleport(l);
    }

    /** Punkt na blacie po stronie rywala, w który gracz celuje. */
    private V3 opponentSpot(boolean ownLow, double v) {
        double u = ownLow ? table.netU() + 1.2 : table.netU() - 1.2;
        return new V3(u, table.topY(), v);
    }

    private double dist(Player p, V3 ball) {
        return arena.toLocal(p.getEyeLocation()).distanceTo(ball);
    }

    /** Serwuje poprawnie: podrzut, uderzenie, gdy piłeczka opada nad stołem. */
    private void serve(PlayerMock sv) {
        PingPongMatch m = match();
        assertTrue(m.serving(), "trwa serwis");
        assertEquals(sv.getUniqueId(), m.serverId());
        aim(sv, opponentSpot(lowSide(teamOf(sv)), arena.width() / 2.0));
        swing(sv);
        assertTrue(m.tossed(), "piłeczka podrzucona");
        for (int i = 0; i < 40; i++) {
            ticks(1);
            V3 b = m.ballPosition();
            if (b.y() >= table.topY() + 0.3 && m.ballVelocity().y() < 0) break;
        }
        aim(sv, opponentSpot(lowSide(teamOf(sv)), arena.width() / 2.0));
        swing(sv);
        assertTrue(m.rallyOn(), "uderzony serwis zaczyna akcję");
    }

    // ---------- przebieg ----------

    @Test
    void theServerStandsBehindTheEndLineWithTheBallInHand() {
        startSingles();
        PingPongMatch m = match();
        assertTrue(m.serving());
        PlayerMock sv = playerOf(m.game().serving());
        assertEquals(sv.getUniqueId(), m.serverId());
        V3 feet = feetOf(sv);
        boolean low = lowSide(teamOf(sv));
        if (low) assertTrue(feet.x() < table.u0() - 0.5, "za linią końcową: " + feet);
        else assertTrue(feet.x() > table.u1() + 0.5, "za linią końcową: " + feet);
        V3 ball = m.ballPosition();
        assertEquals(1.1, ball.y(), 0.2, "piłeczka w dłoni na wysokości pasa");
        assertTrue(low ? ball.x() < table.u0() : ball.x() > table.u1(), "piłeczka też za linią: " + ball);
        assertFalse(m.tossed());
        assertEquals(1, views.size());
    }

    @Test
    void aTossedAndHitServeBouncesOnTheServersHalfThenOnTheReceiversHalf() {
        startSingles();
        PingPongMatch m = match();
        Team st = m.game().serving();
        PlayerMock sv = playerOf(st);
        serve(sv);
        assertEquals(PingPongGame.Stage.SERVE_OWN, m.game().stage());
        for (int i = 0; i < 60 && m.game().stage() != PingPongGame.Stage.GOOD && m.rallyOn(); i++) ticks(1);
        assertEquals(PingPongGame.Stage.GOOD, m.game().stage(), "serwis dobry: odbił się od obu połówek");
        assertEquals(0, m.game().points(Team.A) + m.game().points(Team.B));
    }

    @Test
    void theReceiverReturnsTheServeAndTheRallyContinues() {
        startSingles();
        PingPongMatch m = match();
        Team st = m.game().serving();
        PlayerMock sv = playerOf(st);
        PlayerMock rc = playerOf(st.other());
        serve(sv);
        int returns = 0;
        for (int i = 0; i < 400 && m.rallyOn() && returns < 4; i++) {
            ticks(1);
            if (m.game().stage() != PingPongGame.Stage.GOOD) continue;
            Team last = m.game().lastHitTeam();
            PlayerMock hitter = playerOf(last.other());
            V3 b = m.ballPosition();
            boolean lowHitter = lowSide(teamOf(hitter));
            // bot staje przy linii końcowej na wysokości piłeczki i odbija, gdy ta jest w zasięgu
            V3 spot = new V3(lowHitter ? table.u0() - 1.0 : table.u1() + 1.0, 0, Math.max(1.0, Math.min(arena.width() - 1.0, b.z())));
            placeAndAim(hitter, spot, opponentSpot(lowHitter, arena.width() / 2.0));
            if (dist(hitter, b) < 2.2 && table.lowSide(b) == lowHitter) {
                swing(hitter);
                if (m.game().lastHitTeam() == teamOf(hitter)) returns++;
            }
        }
        assertEquals(0, m.game().points(Team.A) + m.game().points(Team.B), "do tej pory nikt nie zepsuł akcji: " + m.game().points(Team.A) + ":" + m.game().points(Team.B));
        assertTrue(returns >= 3, "kilka odbić z rzędu: " + returns);
        assertNotNull(rc);
    }

    @Test
    void hittingTheServeBeforeItBouncesOnYourHalfLosesThePoint() {
        startSingles();
        PingPongMatch m = match();
        Team st = m.game().serving();
        PlayerMock sv = playerOf(st);
        PlayerMock rc = playerOf(st.other());
        serve(sv);
        boolean recvLow = lowSide(st.other());
        for (int i = 0; i < 40; i++) {
            ticks(1);
            V3 b = m.ballPosition();
            boolean over = recvLow ? b.x() < table.netU() - 0.1 : b.x() > table.netU() + 0.1;
            if (over && m.game().stage() == PingPongGame.Stage.SERVE_OVER) break;
        }
        assertEquals(PingPongGame.Stage.SERVE_OVER, m.game().stage(), "serwis przeszedł nad siatką i czeka na odbicie");
        V3 b = m.ballPosition();
        placeAndAim(rc, new V3(recvLow ? b.x() - 1.2 : b.x() + 1.2, 0, b.z()), b);
        swing(rc);
        assertEquals(1, m.game().points(st), "wolej odbierającego: punkt dla serwującego");
        assertEquals(0, m.game().points(st.other()));
    }

    @Test
    void servingFromInFrontOfTheEndLineLosesThePoint() {
        startSingles();
        PingPongMatch m = match();
        Team st = m.game().serving();
        PlayerMock sv = playerOf(st);
        boolean low = lowSide(st);
        // zagrywający stoi przy samej krawędzi stołu: piłeczka w dłoni jest już nad blatem
        V3 feet = new V3(low ? table.u0() + 0.1 : table.u1() - 0.1, 0, arena.width() / 2.0);
        placeAndAim(sv, feet, opponentSpot(low, arena.width() / 2.0));
        ticks(1); // piłeczka w dłoni podąża za graczem
        swing(sv);
        assertTrue(m.tossed());
        for (int i = 0; i < 40; i++) {
            ticks(1);
            if (m.ballPosition().y() >= table.topY() + 0.3 && m.ballVelocity().y() < 0) break;
        }
        placeAndAim(sv, feet, opponentSpot(low, arena.width() / 2.0));
        swing(sv);
        assertEquals(1, m.game().points(st.other()), "serwis sprzed linii końcowej to punkt dla odbierającego");
    }

    @Test
    void aServeNotHitInTimeLosesThePoint() {
        plugin.getConfig().set("pingpong.czas-na-serwis-sekundy", 4.0);
        startSingles();
        PingPongMatch m = match();
        Team st = m.game().serving();
        ticks(20 * 5);
        assertEquals(1, m.game().points(st.other()));
    }

    @Test
    void aTossThatIsNotHitCanBeRepeated() {
        startSingles();
        PingPongMatch m = match();
        Team st = m.game().serving();
        PlayerMock sv = playerOf(st);
        aim(sv, opponentSpot(lowSide(st), arena.width() / 2.0));
        swing(sv);
        assertTrue(m.tossed());
        ticks(20 * 3); // piłeczka spada
        assertFalse(m.tossed(), "po upadku podrzut można powtórzyć");
        assertEquals(0, m.game().points(Team.A) + m.game().points(Team.B));
        assertTrue(m.serving());
        swing(sv);
        assertTrue(m.tossed());
    }

    @Test
    void theBallThatMissesEveryoneEndsTheRallyAndThePointGoesToTheServer() {
        startSingles();
        PingPongMatch m = match();
        Team st = m.game().serving();
        serve(playerOf(st));
        ticks(20 * 6); // nikt nie odbija
        assertEquals(1, m.game().points(st), "odbierający nie odbił dobrego serwisu");
        assertFalse(m.rallyOn());
    }

    @Test
    void afterAPointTheNextServeComesAndTheServerChangesEveryTwoPoints() {
        startSingles();
        PingPongMatch m = match();
        Team first = m.game().serving();
        serve(playerOf(first));
        ticks(20 * 6);
        assertEquals(1, m.game().points(first));
        // po krótkiej przerwie następny serwis, nadal ten sam gracz (zmiana dopiero po dwóch punktach)
        ticks(20 * 4);
        assertTrue(m.serving());
        assertEquals(first, m.game().serving());
        serve(playerOf(first));
        ticks(20 * 6);
        assertEquals(2, m.game().points(first));
        ticks(20 * 4);
        assertTrue(m.serving());
        assertEquals(first.other(), m.game().serving(), "po dwóch punktach serwis przechodzi");
    }

    // ---------- ruch graczy ----------

    private Location moved(Player p, Location to) {
        Location from = p.getLocation().clone();
        PlayerMoveEvent e = new PlayerMoveEvent(p, from, to);
        server.getPluginManager().callEvent(e);
        return e.getTo();
    }

    private Location at(double u, double v) {
        return arena.toWorld(world, new V3(u, 0, v));
    }

    @Test
    void playersCannotWalkIntoTheTableOrOntoTheOpponentsSide() {
        startSingles();
        PlayerMock p = a;
        boolean low = lowSide(teamOf(p));
        double sign = low ? 1 : -1;
        double endU = low ? table.u0() : table.u1();
        // swobodny ruch za linią końcową
        p.teleport(at(endU - sign * 1.5, 1.0));
        Location ok = at(endU - sign * 1.8, 1.4);
        assertEquals(ok, moved(p, ok), "ruch za końcem stołu jest swobodny");
        // wejście w stół zatrzymane
        p.teleport(at(endU - sign * 0.8, 4.0));
        Location from = p.getLocation().clone();
        Location in = at(endU + sign * 0.2, 4.0);
        Location result = moved(p, in);
        assertEquals(from.getX(), result.getX(), 1e-9, "stół jest przeszkodą");
        assertEquals(from.getZ(), result.getZ(), 1e-9);
        // krok przez linię siatki obok stołu też
        p.teleport(at(table.netU() - sign * 0.1, 1.0));
        Location sideFrom = p.getLocation().clone();
        Location over = at(table.netU() + sign * 0.6, 1.0);
        Location res2 = moved(p, over);
        assertEquals(sideFrom.getX(), res2.getX(), 1e-9, "druga połowa jest zablokowana");
    }

    // ---------- gracze ----------

    @Test
    void aTeamHasOnePlayerInSinglesAndTwoInDoublesAndNoMore() {
        PlayerMock c = server.addPlayer("Czesiek");
        PlayerMock d = server.addPlayer("Darek");
        PlayerMock e = server.addPlayer("Edek");
        join(a, "a");
        join(c, "a");
        join(b, "b");
        join(d, "b");
        join(e, "a");
        assertEquals(2, match().teamSize(Team.A));
        assertEquals(2, match().teamSize(Team.B));
        assertNull(plugin.matches().of(e), "trzeci gracz w drużynie się nie mieści");
        // dwaj gracze jednej drużyny stoją obok siebie
        V3 pa = feetOf(a);
        V3 pc = feetOf(c);
        assertTrue(Math.abs(pa.z() - pc.z()) > 1.5, "obok siebie: " + pa + " " + pc);
    }

    @Test
    void leavingTheOnlyOpponentEndsTheMatchByWalkover() {
        startSingles();
        PingPongMatch m = match();
        server.dispatchCommand(b, "sport opusc");
        assertEquals(Match.Stage.ENDING, m.stage());
        ticks(20 * 8);
        assertEquals(Match.Stage.LOBBY, m.stage());
        assertNull(plugin.matches().of(a));
        assertTrue(views.stream().allMatch(v -> v.removed), "piłeczka usunięta");
    }

    @Test
    void theBallShadowLiesOnTheTableWhenTheBallIsAboveIt() {
        startSingles();
        PingPongMatch m = match();
        Team st = m.game().serving();
        serve(playerOf(st));
        boolean checked = false;
        for (int i = 0; i < 40 && m.rallyOn(); i++) {
            ticks(1);
            V3 b = m.ballPosition();
            if (table.overTable(b, 0) && b.y() > table.topY() + 0.1) {
                assertEquals(arena.originY() + table.topY(), lastView().floorY, 1e-6, "cień na blacie");
                checked = true;
                break;
            }
        }
        assertTrue(checked, "piłeczka była nad stołem");
    }

    @Test
    void theHudShowsGamesPointsAndTheServer() {
        startSingles();
        PingPongMatch m = match();
        var lines = m.scoreboard();
        String all = String.join("|", lines);
        assertTrue(all.contains("Gem"), all);
        assertTrue(all.contains("11"), "cel gemu: " + all);
    }
}
