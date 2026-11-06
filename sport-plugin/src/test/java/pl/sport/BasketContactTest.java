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

/**
 * Koszykówka: faule i odbieranie piłki. Faul wymaga kontaktu (zahaczenie od tyłu, uderzenie po rękach rzucającego, najechanie na stojącego
 * obrońcę), a nigdy nie wynika z samego stania obok rywala albo z machnięcia w pustkę. LPM przy piłce, której nikt nie trzyma, ją łapie.
 */
class BasketContactTest extends SportTestBase {
    Arena arena;
    PlayerMock c;

    @BeforeEach
    void court() {
        // faule bez losowości: przy tej wartości każda sytuacja, która MOŻE być faulem, jest faulem, a reszta nie
        plugin.getConfig().set("koszykowka.szansa-faulu", 100.0);
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

    private void swap(Player p) {
        server.getPluginManager().callEvent(new PlayerSwapHandItemsEvent(p, new ItemStack(org.bukkit.Material.AIR), new ItemStack(org.bukkit.Material.AIR)));
    }

    private void startTwo() {
        join(a, "a");
        join(b, "b");
        match().seed(4242);
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
        assertEquals(t, match().game().inbound());
        V3 spot = new V3(arena.length() / 2.0, 0, arena.width() / 2.0);
        place(p, spot, spot.plus(new V3(1, 0, 0)), 0);
        ticks(3);
        assertEquals(p.getUniqueId(), match().holder());
    }

    /** Rzucający z piłką stoi 4,5 m od kosza i patrzy na obręcz; zwraca punkt jego stóp. */
    private V3 shooterAtTheHoop(PlayerMock shooter, Team t) {
        BasketMatch m = match();
        int hoop = m.game().attackedHoop(t);
        V3 rim = m.court().rimCenter(hoop);
        double sign = hoop == 0 ? 1 : -1;
        V3 at = new V3(rim.x() + sign * 4.5, 0, rim.z());
        place(shooter, at, rim, -12);
        ticks(2);
        return at;
    }

    private void pushUnderTheRoof(BasketMatch m) {
        m.teleportBall(new V3(14, 30, 7.5), V3.ZERO); // piłka wysoko nad boiskiem: luźna i daleko od wszystkich
    }

    // ---------- brak losowych fauli ----------

    @Test
    void swingingAtThinAirNextToAnOpponentNeverWhistlesAFoul() {
        startTwo();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock holder = playerOf(t);
        PlayerMock other = playerOf(t.other());
        giveBall(t, holder);
        place(holder, new V3(8, 0, 6), new V3(20, 0, 6), 0);
        place(other, new V3(7.2, 0, 6), new V3(20, 0, 6), 0);
        ticks(2);
        swap(holder); // podanie w pustkę: piłka jest luźna
        assertNull(m.holder());
        pushUnderTheRoof(m);
        for (int i = 0; i < 6; i++) {
            place(other, new V3(7.2, 0, 6), new V3(8, 0, 6), 0); // patrzy prosto na stojącego obok rywala
            swing(other);
            ticks(6);
        }
        assertEquals(0, m.game().personalFouls(other.getUniqueId()), "machnięcie w pustkę nie jest faulem");
        assertEquals(0, m.game().teamFouls(Team.A) + m.game().teamFouls(Team.B));
    }

    @Test
    void aDefenderJustStandingNextToTheShooterIsNotAFoul() {
        startTwo();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock shooter = playerOf(t);
        PlayerMock def = playerOf(t.other());
        giveBall(t, shooter);
        V3 at = shooterAtTheHoop(shooter, t);
        place(def, new V3(at.x() + (m.game().attackedHoop(t) == 0 ? -0.6 : 0.6), 0, at.z()), at, 0);
        ticks(3);
        swing(shooter);
        assertTrue(m.game().shotInFlight(), "rzut puszczony");
        ticks(20 * 8);
        assertEquals(0, m.game().personalFouls(def.getUniqueId()), "samo stanie przy rzucającym to nie faul");
        assertFalse(m.inFreeThrows());
    }

    @Test
    void aFailedStealFromTheFrontIsAFairAttempt() {
        plugin.getConfig().set("koszykowka.szansa-przechwytu", 0.0);
        startTwo();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock holder = playerOf(t);
        PlayerMock thief = playerOf(t.other());
        giveBall(t, holder);
        // kozłujący patrzy na złodzieja i go widzi: to czysta próba
        place(holder, new V3(8, 0, 6), new V3(7, 0, 6), 0);
        place(thief, new V3(7.2, 0, 6), new V3(8, 0, 6), 0);
        ticks(3);
        for (int i = 0; i < 4; i++) {
            swing(thief);
            ticks(20);
        }
        assertEquals(0, m.game().personalFouls(thief.getUniqueId()), "z przodu nie ma kontaktu");
        assertEquals(0, m.game().teamFouls(Team.A) + m.game().teamFouls(Team.B));
    }

    // ---------- faule z kontaktem ----------

    @Test
    void aFailedStealFromBehindIsAFoulAndTheMessageSaysWhy() {
        plugin.getConfig().set("koszykowka.szansa-przechwytu", 0.0);
        startTwo();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock holder = playerOf(t);
        PlayerMock thief = playerOf(t.other());
        giveBall(t, holder);
        place(holder, new V3(8, 0, 6), new V3(20, 0, 6), 0);
        place(thief, new V3(7.2, 0, 6), new V3(20, 0, 6), 0);
        ticks(3);
        while (a.nextMessage() != null) { /* czyścimy */ }
        while (b.nextMessage() != null) { /* czyścimy */ }
        for (int i = 0; i < 6 && m.game().teamFouls(t.other()) == 0; i++) {
            swing(thief);
            ticks(20);
        }
        assertEquals(1, m.game().teamFouls(t.other()));
        assertEquals(BasketGame.FoulType.PERSONAL, m.game().lastFoul().type());
        StringBuilder all = new StringBuilder();
        String line;
        while ((line = holder.nextMessage()) != null) all.append(line).append(" | ");
        assertTrue(all.toString().contains("zahaczenie od tyłu"), all.toString());
    }

    @Test
    void aLateSwingAtAShooterWhoAlreadyReleasedTheBallIsAFoulAndGivesFreeThrows() {
        plugin.getConfig().set("koszykowka.celnosc", 0.3); // rzut ma szansę nie wpaść, ale bez względu na wynik dostaje rzuty osobiste
        startTwo();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock shooter = playerOf(t);
        PlayerMock def = playerOf(t.other());
        giveBall(t, shooter);
        V3 at = shooterAtTheHoop(shooter, t);
        double side = m.game().attackedHoop(t) == 0 ? -0.8 : 0.8;
        place(def, new V3(at.x() + side, 0, at.z()), at, 0); // stoi tuż za rzucającym i patrzy na niego
        ticks(3);
        swing(shooter);
        assertTrue(m.game().shotInFlight());
        ticks(8); // piłka jest już poza zasięgiem obrońcy
        place(def, new V3(at.x() + side, 0, at.z()), at, 0);
        swing(def);
        assertEquals(1, m.game().personalFouls(def.getUniqueId()), "uderzenie po rękach: faul obrońcy");
        assertEquals(BasketGame.FoulType.SHOOTING, m.game().lastFoul().type());
        assertTrue(m.game().pendingShotFoul() || m.inFreeThrows());
        for (int i = 0; i < 20 * 12 && !m.inFreeThrows(); i++) ticks(1);
        assertTrue(m.inFreeThrows(), "po rzucie z faulem rzuty osobiste");
        assertEquals(shooter.getUniqueId(), m.game().freeThrowShooter());
    }

    @Test
    void anEarlySwingThatReachesTheBallIsACleanBlock() {
        startTwo();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock shooter = playerOf(t);
        PlayerMock def = playerOf(t.other());
        giveBall(t, shooter);
        V3 at = shooterAtTheHoop(shooter, t);
        double side = m.game().attackedHoop(t) == 0 ? -0.8 : 0.8;
        place(def, new V3(at.x() + side, 0, at.z()), at, 0);
        ticks(3);
        swing(shooter);
        swing(def); // piłka dopiero co wyleciała: jest w zasięgu, to blok
        assertFalse(m.game().shotInFlight(), "rzut zablokowany");
        assertEquals(0, m.game().personalFouls(def.getUniqueId()), "czysty blok nie jest faulem");
        assertNull(m.game().lastFoul());
    }

    @Test
    void aFailedStealOnAPlayerGoingUpForALayupIsAShootingFoul() {
        plugin.getConfig().set("koszykowka.szansa-przechwytu", 0.0);
        startTwo();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock shooter = playerOf(t);
        PlayerMock def = playerOf(t.other());
        giveBall(t, shooter);
        int hoop = m.game().attackedHoop(t);
        V3 rim = m.court().rimCenter(hoop);
        double sign = hoop == 0 ? 1 : -1;
        // obrońca stoi z dala od drogi, a potem dopada do akcji
        place(def, new V3(arena.length() / 2.0, 0, 0.8), rim, 0);
        ticks(3);
        for (double d = 7.0; d >= 4.0 - 1e-9; d -= 0.3) {
            place(shooter, new V3(rim.x() + sign * d, 0, rim.z()), rim, -12);
            ticks(1);
        }
        swing(shooter);
        assertTrue(m.hasPendingMove(shooter.getUniqueId()), "dwutakt trwa");
        place(def, new V3(rim.x() + sign * 4.8, 0, rim.z() + 0.2), new V3(rim.x() + sign * 4.0, 0, rim.z()), 0);
        ticks(1);
        swing(def);
        assertEquals(BasketGame.FoulType.SHOOTING, m.game().lastFoul().type(), "uderzenie w ręce rzucającego");
        assertTrue(m.inFreeThrows());
        assertEquals(2, m.game().freeThrowsTotal());
    }

    @Test
    void chargingNeedsAPlantedDefenderWhoFacesTheAttacker() {
        startTwo();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock holder = playerOf(t);
        PlayerMock def = playerOf(t.other());
        // obrońca odwrócony plecami do nadbiegającego: to on wszedł pod atakującego, więc faulu w ataku nie ma
        place(def, new V3(17.5, 0, 7.5), new V3(30, 0, 7.5), 0);
        ticks(3);
        giveBall(t, holder);
        for (double x = 14.0; x <= 17.05 && m.holder() != null; x += 0.3) {
            place(holder, new V3(x, 0, 7.5), new V3(30, 0, 7.5), 0);
            ticks(1);
        }
        assertEquals(0, m.game().personalFouls(holder.getUniqueId()));
    }

    // ---------- odbieranie piłki ----------

    @Test
    void clickingNearAPassedBallCatchesItInsteadOfBattingItAway() {
        join(a, "a");
        join(c, "a");
        join(b, "b");
        long seed = 0;
        while (!new java.util.Random(seed).nextBoolean()) seed++;
        match().seed(seed);
        server.dispatchCommand(admin, "koszykowka start natychmiast b");
        ticks(2);
        BasketMatch m = match();
        assertEquals(Team.A, m.game().inbound());
        giveBall(Team.A, a);
        place(a, new V3(8, 0, 4), new V3(16, 0, 4), 0);
        place(c, new V3(16, 0, 4), new V3(8, 0, 4), 0);
        ticks(2);
        swap(a); // podanie do kolegi
        assertNull(m.holder());
        // piłka zatrzymuje się 2 m od kolegi, czyli dalej niż odległość, z której podnosi się ją chodząc, ale w zasięgu ręki
        V3 target = new V3(c.getLocation().getX(), 0, 0);
        V3 cf = arena.toLocal(c.getLocation());
        m.teleportBall(new V3(cf.x() - 2.0, 1.3, cf.z()), V3.ZERO);
        place(c, new V3(cf.x(), 0, cf.z()), new V3(cf.x() - 2.0, 0, cf.z()), 0);
        swing(c);
        assertEquals(c.getUniqueId(), m.holder(), "LPM łapie podanie");
        assertNotNull(target);
    }

    @Test
    void aShotInFlightCannotBeCaughtMidAirButCanBeBlocked() {
        startTwo();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock shooter = playerOf(t);
        PlayerMock def = playerOf(t.other());
        giveBall(t, shooter);
        V3 at = shooterAtTheHoop(shooter, t);
        swing(shooter);
        assertTrue(m.game().shotInFlight());
        // piłka leci w górę tuż obok obrońcy: przedtem „wpadała” mu w ręce
        V3 df = new V3(at.x() + (m.game().attackedHoop(t) == 0 ? 1.5 : -1.5), 0, at.z() + 1.2);
        place(def, df, at, 0);
        ticks(1);
        m.teleportBall(df.plus(new V3(0.3, 1.8, 0)), new V3(0, 3, 0));
        ticks(2);
        assertNull(m.holder(), "rzut w locie nie jest łapany automatycznie");
        assertTrue(m.game().shotInFlight());
        // LPM przy takiej piłce to blok, a nie złapanie
        m.teleportBall(df.plus(new V3(0.3, 1.8, 0)), new V3(0, 3, 0));
        place(def, df, df.plus(new V3(0.3, 0, 0)), 0);
        swing(def);
        assertNull(m.holder());
        assertFalse(m.game().shotInFlight(), "zablokowany rzut przestaje być rzutem");
    }

    @Test
    void aMissedShotThatFallsLowCanBeCaughtAsARebound() {
        startTwo();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock shooter = playerOf(t);
        PlayerMock def = playerOf(t.other());
        giveBall(t, shooter);
        V3 at = shooterAtTheHoop(shooter, t);
        swing(shooter);
        assertTrue(m.game().shotInFlight());
        V3 df = new V3(at.x() + (m.game().attackedHoop(t) == 0 ? 4.0 : -4.0), 0, at.z() + 2.0);
        place(def, df, at, 0);
        ticks(1);
        m.teleportBall(df.plus(new V3(0.2, 1.8, 0)), new V3(0, -2, 0)); // opada nisko po pudle
        ticks(1);
        assertEquals(def.getUniqueId(), m.holder(), "zbiórka po pudle");
    }

    @Test
    void anAttemptFromTooFarAwayDoesNotLockTheNextOneForAWholeSecond() {
        plugin.getConfig().set("koszykowka.szansa-przechwytu", 10.0);
        startTwo();
        BasketMatch m = match();
        Team t = m.game().inbound();
        PlayerMock holder = playerOf(t);
        PlayerMock thief = playerOf(t.other());
        giveBall(t, holder);
        place(holder, new V3(8, 0, 6), new V3(20, 0, 6), 0);
        place(thief, new V3(4.5, 0, 6), new V3(20, 0, 6), 0); // 3,5 m: za daleko
        ticks(3);
        swing(thief);
        assertEquals(holder.getUniqueId(), m.holder(), "z daleka nic się nie dzieje");
        place(thief, new V3(7.2, 0, 6), new V3(20, 0, 6), 0);
        ticks(6); // 0,3 s: pudło z daleka nie blokuje długo
        swing(thief);
        assertEquals(thief.getUniqueId(), m.holder(), "kolejna próba z bliska działa od razu");
    }

    @Test
    void thePassReceiverCatchesFromFurtherAwayThanAnInterceptor() {
        join(a, "a");
        join(c, "a");
        join(b, "b");
        long seed = 0;
        while (!new java.util.Random(seed).nextBoolean()) seed++;
        match().seed(seed);
        server.dispatchCommand(admin, "koszykowka start natychmiast b");
        ticks(2);
        BasketMatch m = match();
        giveBall(Team.A, a);
        place(a, new V3(8, 0, 4), new V3(16, 0, 4), 0);
        place(c, new V3(16, 0, 4), new V3(8, 0, 4), 0);
        ticks(2);
        swap(a);
        // piłka zawisa w punkcie P: kolega jest od niej 1,5 m, rywal 1,25 m (dalej niż 1,1 m, z którego podnosi się piłkę, i bliżej niż kolega)
        V3 pt = new V3(12, 1.3, 8);
        m.teleportBall(pt, V3.ZERO);
        place(c, new V3(12, 0, 9.5), pt, 0);
        place(b, new V3(12, 0, 6.75), pt, 0);
        ticks(1);
        assertEquals(c.getUniqueId(), m.holder(), "adresat podania łapie z 1,5 m, a rywal z 1,25 m jeszcze nie sięga");
    }

    @Test
    void theInboundTeamCanTakeTheRestartBallWithAClickFromArmsLength() {
        startTwo();
        BasketMatch m = match();
        Team in = m.game().inbound();
        PlayerMock inbound = playerOf(in);
        PlayerMock other = playerOf(in.other());
        V3 spot = new V3(arena.length() / 2.0, 0, arena.width() / 2.0);
        place(other, spot.plus(new V3(-2.0, 0, 0)), spot, 0);
        place(inbound, spot.plus(new V3(2.2, 0, 0)), spot, 0); // 2,2 m od piłki: za daleko na wejście w nią, ale w zasięgu ręki
        ticks(2);
        assertNull(m.holder());
        swing(other);
        assertNull(m.holder(), "rywal nie wznawia");
        swing(inbound);
        assertEquals(inbound.getUniqueId(), m.holder(), "wznawiający bierze piłkę kliknięciem");
        assertEquals(BasketGame.Phase.LIVE, m.game().phase());
    }
}
