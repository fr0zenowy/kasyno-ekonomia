package pl.sport;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.sport.arena.Arena;
import pl.sport.core.Team;
import pl.sport.core.V3;
import pl.sport.match.Match;
import pl.sport.volley.VolleyMatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gra solo (1 na 1) w siatkówce: wystawienie sobie piłki, trzy odbicia z rzędu, kliknięcie chwilę przed piłką i odbicie piłki tuż nad głową. */
class VolleySoloTest extends SportTestBase {
    Arena arena;
    PlayerMock recv;
    boolean recvLow;

    @BeforeEach
    void court() {
        arena = build("siatkowka", "v", 50, 50, -90);
    }

    private VolleyMatch match() {
        return (VolleyMatch) plugin.matches().matchFor(arena);
    }

    private void swing(Player p) {
        server.getPluginManager().callEvent(new PlayerArmSwingEvent(p, EquipmentSlot.HAND));
    }

    /** Ustawia gracza w punkcie boiska; pitch w stopniach (ujemny = patrzy w górę), patrzy w stronę siatki. */
    private void place(PlayerMock p, V3 feet, float pitch) {
        Location l = arena.toWorld(world, feet);
        l.setYaw(arena.yawOf(new V3(arena.toLocal(p.getLocation()).x() < arena.length() / 2.0 ? 1 : -1, 0, 0)));
        l.setPitch(pitch);
        p.teleport(l);
    }

    private double towardNet() {
        return recvLow ? 1 : -1;
    }

    /** Serwis, po którym piłka jest w grze, a odbierający (solo) stoi na swojej połowie przed siatką. */
    private VolleyMatch rally() {
        a.setSneaking(false);
        b.setSneaking(false);
        server.dispatchCommand(a, "siatkowka dolacz v a");
        server.dispatchCommand(b, "siatkowka dolacz v b");
        match().seed(99);
        server.dispatchCommand(admin, "siatkowka start natychmiast v");
        assertEquals(Match.Stage.PLAYING, match().stage());
        ticks(60);
        VolleyMatch m = match();
        PlayerMock sv = (PlayerMock) server.getPlayer(m.serverId());
        Team serving = m.game().serving();
        recv = sv == a ? b : a;
        recvLow = m.game().sideOwner(true) == m.game().serving().other();
        swing(sv);
        ticks(4);
        swing(sv);
        assertTrue(m.rallyOn(), "serwis uderzony");
        assertEquals(serving, m.game().touchTeam());
        // odbierający stoi blisko siatki na swojej połowie, a piłka na razie nie nadlatuje
        V3 feet = new V3(arena.length() / 2.0 - towardNet() * 3.0, 0, 4.5);
        place(recv, feet, 0);
        ticks(1);
        return m;
    }

    private V3 feet() {
        return arena.toLocal(recv.getLocation());
    }

    private void ballAt(VolleyMatch m, V3 pos, V3 vel) {
        m.teleportBall(pos, vel);
    }

    @Test
    void aSoloPlayerCanSetTheBallToHimselfHalfASecondAfterTheFirstTouch() {
        VolleyMatch m = rally();
        V3 f = feet();
        place(recv, f, -55); // patrzy mocno w górę
        ballAt(m, f.plus(new V3(towardNet() * 0.6, 1.2, 0)), V3.ZERO);
        swing(recv);
        assertEquals(1, m.game().touches(), "pierwsze odbicie");
        assertTrue(m.ballVelocity().y() > 3, "piłka idzie wysoko do góry (wystawienie sobie): " + m.ballVelocity());
        ticks(10); // pół sekundy, wcześniej drugie kliknięcie było ignorowane do upływu pełnej sekundy
        place(recv, feet(), 0);
        recv.setSneaking(true);
        swing(recv);
        assertEquals(2, m.game().touches(), "drugie odbicie (wystawienie) po pół sekundy");
        assertTrue(m.ballVelocity().y() > 3, "piłka znów wysoko: " + m.ballVelocity());
    }

    @Test
    void theLastAllowedTouchAlwaysGoesOverTheNetEvenWhenLookingUp() {
        VolleyMatch m = rally();
        V3 f = feet();
        place(recv, f, -55);
        ballAt(m, f.plus(new V3(towardNet() * 0.6, 1.2, 0)), V3.ZERO);
        swing(recv); // odbicie 1: do siebie
        ticks(10);
        place(recv, feet(), 0);
        recv.setSneaking(true);
        swing(recv); // odbicie 2: do siebie
        recv.setSneaking(false);
        assertEquals(2, m.game().touches());
        // czeka, aż piłka opadnie w zasięg, i odbija po raz trzeci z patrzeniem w górę
        for (int i = 0; i < 60; i++) {
            ticks(1);
            V3 b = m.ballPosition();
            V3 eye = feet().plus(new V3(0, 1.62, 0));
            if (m.ballVelocity().y() < 0 && b.distanceTo(eye) < 2.0) break;
        }
        place(recv, feet(), -55);
        swing(recv);
        assertEquals(3, m.game().touches(), "trzecie odbicie");
        double along = m.ballVelocity().x() * towardNet();
        assertTrue(along > 3, "ostatnie odbicie leci na drugą stronę siatki, a nie do góry przed gracza: " + m.ballVelocity());
        // piłka kończy w polu rywala albo na aucie, ale nie jako czwarte odbicie
        for (int i = 0; i < 20 * 6 && m.game().points(Team.A) + m.game().points(Team.B) == 0; i++) ticks(1);
        assertEquals(1, m.game().points(Team.A) + m.game().points(Team.B));
    }

    @Test
    void aClickJustBeforeTheBallArrivesIsKeptAndHitsAsSoonAsTheBallIsInReach() {
        VolleyMatch m = rally();
        V3 f = feet();
        place(recv, f, 0);
        // piłka leci z boku w stronę gracza z prędkością 12 m/s, na razie 5,5 m od niego (zasięg to 3 m)
        ballAt(m, new V3(f.x(), 1.4, f.z() + 5.5), new V3(0, 0, -12));
        swing(recv);
        assertEquals(0, m.game().touches(), "w chwili kliknięcia piłka jest jeszcze za daleko");
        for (int i = 0; i < 8 && m.game().touches() == 0; i++) ticks(1);
        assertEquals(1, m.game().touches(), "kliknięcie czekało na piłkę i odbiło ją, gdy weszła w zasięg");
    }

    @Test
    void aClickThatNeverMeetsTheBallExpiresWithoutTouchingIt() {
        VolleyMatch m = rally();
        V3 f = feet();
        place(recv, f, 0);
        ballAt(m, new V3(f.x(), 1.4, f.z() + 7.0), new V3(0, 0, 0.5));
        swing(recv);
        ticks(10);
        assertEquals(0, m.game().touches());
        // po wygaśnięciu kliknięcia piłka, która potem nadleci, nie jest odbijana „sama”
        ballAt(m, new V3(f.x(), 1.4, f.z() + 1.0), V3.ZERO);
        ticks(2);
        assertEquals(0, m.game().touches(), "bez nowego kliknięcia nic się nie dzieje");
        swing(recv);
        assertEquals(1, m.game().touches(), "nowe kliknięcie odbija piłkę z bliska");
    }

    @Test
    void aBallRightAboveTheHeadCanBeHitEvenWhenLookingAtTheNet() {
        VolleyMatch m = rally();
        V3 f = feet();
        place(recv, f, 0);
        ballAt(m, f.plus(new V3(0.1, 2.7, 0)), V3.ZERO);
        swing(recv);
        assertEquals(1, m.game().touches(), "piłka nad głową jest w zasięgu, mimo że gracz patrzy poziomo");
    }

    @Test
    void aBallBehindTheBackIsNotHitButTheClickIsNotSilentlyLost() {
        VolleyMatch m = rally();
        V3 f = feet();
        place(recv, f, 0);
        // 2,6 m za plecami: poza zasięgiem lekkiego „ponad głowę”, a patrzymy w przeciwną stronę
        ballAt(m, f.plus(new V3(-towardNet() * 2.6, 1.3, 0)), V3.ZERO);
        swing(recv);
        assertEquals(0, m.game().touches());
        ticks(10);
        assertEquals(0, m.game().touches(), "piłka za plecami nie jest odbijana");
        assertFalse(m.rallyOn() && m.game().touches() > 0);
    }

    @Test
    void soloPlayersAreToldHowTheirTeamPlays() {
        server.dispatchCommand(a, "siatkowka dolacz v a");
        server.dispatchCommand(b, "siatkowka dolacz v b");
        server.dispatchCommand(admin, "siatkowka start natychmiast v");
        StringBuilder all = new StringBuilder();
        String line;
        while ((line = a.nextMessage()) != null) all.append(line).append(" | ");
        String text = all.toString();
        assertTrue(text.contains("Grasz sam"), text);
        assertTrue(text.contains("Shift"), text);
    }
}
