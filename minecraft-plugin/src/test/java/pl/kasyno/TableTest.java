package pl.kasyno;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kasyno.core.Games;
import pl.kasyno.games.GameRegistry;
import pl.kasyno.gui.GuiManager;
import pl.kasyno.gui.Screens;
import pl.kasyno.table.CrashTable;
import pl.kasyno.table.KoloTable;
import pl.kasyno.table.RaceTable;
import pl.kasyno.table.RouletteTable;
import pl.kasyno.table.Table;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wspólne stoły: kilku graczy w jednej rundzie, rozliczenie dokładnie raz, zwroty stawek i odwołane rundy. */
class TableTest {
    static {
        System.setProperty("kasyno.test", "1");
    }

    ServerMock server;
    KasynoPlugin plugin;
    PlayerMock a;
    PlayerMock b;

    @BeforeEach
    void setUp() {
        GuiManager.clickGapMs = 0;
        server = MockBukkit.mock();
        plugin = MockBukkit.load(KasynoPlugin.class);
        a = server.addPlayer();
        b = server.addPlayer();
        for (PlayerMock p : List.of(a, b)) {
            PlayerListener.prepare(plugin, p);
            plugin.eco().pd(p.getUniqueId()).wallet = 10_000;
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void open(PlayerMock p, String key) {
        assertTrue(Screens.open(plugin, p, key), key);
        server.getScheduler().performOneTick();
    }

    private void click(PlayerMock p, int slot) {
        p.simulateInventoryClick(slot);
        server.getScheduler().performOneTick();
    }

    private void ticks(int n) {
        server.getScheduler().performTicks(n);
    }

    /** Tika do spełnienia warunku (albo błąd po `max` tikach). */
    private void until(BooleanSupplier cond, int max) {
        for (int i = 0; i < max && !cond.getAsBoolean(); i += 2) ticks(2);
        assertTrue(cond.getAsBoolean(), "warunek nie został spełniony");
    }

    private Inventory top(PlayerMock p) {
        return p.getOpenInventory().getTopInventory();
    }

    private long wallet(PlayerMock p) {
        return plugin.eco().wallet(p.getUniqueId());
    }

    private long held(PlayerMock p, String table) {
        return plugin.eco().held(p.getUniqueId(), table);
    }

    // ---------- rejestr i sale ----------

    @Test
    void liveGamesAreTablesAndHallsListThem() {
        List<String> live = GameRegistry.inHall("live").stream().map(GameRegistry.Def::id).toList();
        assertEquals(List.of("crash", "ruletka", "kolo", "wyscigi"), live);
        for (String id : live) assertNotNull(plugin.tables().get(id), id);
        int games = 0;
        for (GameRegistry.Hall h : GameRegistry.HALLS) {
            assertTrue(GameRegistry.inHall(h.id()).size() >= 4, "sala " + h.id());
            games += GameRegistry.inHall(h.id()).size();
        }
        assertEquals(GameRegistry.all().size(), games, "każda gra jest w jakiejś sali");
    }

    @Test
    void hallsOpenGamesAndGoBack() {
        for (GameRegistry.Hall h : GameRegistry.HALLS) {
            open(a, h.id());
            assertEquals("HallGui", plugin.guis().current(a).getClass().getSimpleName());
            click(a, GameRegistry.inHall(h.id()).size() == 4 ? 19 : 11); // pierwsza gra w sali
            ticks(2);
            assertTrue(!plugin.guis().current(a).getClass().getSimpleName().equals("HallGui"), "sala " + h.id() + " otwiera grę");
            click(a, 45); // z gry wracamy do sali
            assertEquals("HallGui", plugin.guis().current(a).getClass().getSimpleName());
            a.closeInventory();
            ticks(2);
        }
        open(a, "kasyno");
        click(a, 19); // pierwsza sala
        assertEquals("HallGui", plugin.guis().current(a).getClass().getSimpleName());
        click(a, 49);
        assertEquals("CasinoGui", plugin.guis().current(a).getClass().getSimpleName());
    }

    // ---------- crash ----------

    @Test
    void crashRoundIsSharedAndSettlesOnce() {
        CrashTable crash = plugin.tables().crash();
        open(a, "crash");
        open(b, "crash");
        click(a, 49); // A stawia 100, wypłaca ręcznie
        assertEquals(9_900, wallet(a));
        assertEquals(100, held(a, "crash"));
        assertEquals(Table.Phase.BETTING, crash.phase());
        ticks(6);
        assertEquals(Material.PLAYER_HEAD, top(b).getItem(10).getType(), "B widzi A przy stole");

        click(b, 38); // auto-wypłata x2
        click(b, 49);
        assertEquals(9_900, wallet(b));
        assertEquals(2, crash.seats().size());

        until(() -> crash.phase() == Table.Phase.RESULT, 20 * 200);
        until(() -> crash.phase() == Table.Phase.WAITING, 20 * 10);
        assertEquals(0, held(a, "crash"));
        assertEquals(0, held(b, "crash"));
        // A nie wypłacał i miał okno otwarte, więc przegrał; B z auto x2 wygrał albo przegrał, zależnie od punktu crashu
        assertEquals(9_900, wallet(a));
        assertTrue(wallet(b) == 9_900 || wallet(b) == 10_100, "B: " + wallet(b));
        assertEquals(1, plugin.eco().pd(a.getUniqueId()).games);
        assertEquals(1, plugin.eco().pd(b.getUniqueId()).games);
    }

    @Test
    void crashManualCashOutPaysTheShownMultiplier() {
        CrashTable crash = plugin.tables().crash();
        open(a, "crash");
        click(a, 49);
        until(() -> crash.phase() == Table.Phase.RUNNING, 20 * 20);
        until(() -> crash.phase() != Table.Phase.RUNNING || crash.mult() >= 1.2, 20 * 60);
        Table.Seat seat = crash.seat(a.getUniqueId());
        if (crash.phase() == Table.Phase.RUNNING) {
            double m = crash.mult();
            click(a, 49);
            assertTrue(seat.settled());
            assertEquals((long) Math.floor(100 * seat.bets.get(0).cashedAt), seat.result.payout());
            assertTrue(seat.bets.get(0).cashedAt >= m - 1e-9);
            assertEquals(9_900 + seat.result.payout(), wallet(a));
        } else {
            assertTrue(seat.settled(), "rundę zakończył crash, a stawka jest rozliczona");
        }
        until(() -> crash.phase() == Table.Phase.WAITING, 20 * 200);
        assertEquals(0, held(a, "crash"));
    }

    @Test
    void crashClosingWindowDuringRoundCashesOut() {
        CrashTable crash = plugin.tables().crash();
        open(a, "crash");
        click(a, 49);
        until(() -> crash.phase() == Table.Phase.RUNNING, 20 * 20);
        until(() -> crash.phase() != Table.Phase.RUNNING || crash.mult() >= 1.1, 20 * 60);
        Table.Seat seat = crash.seat(a.getUniqueId());
        a.closeInventory();
        ticks(2);
        assertTrue(seat.settled(), "zamknięcie okna w trakcie rundy rozlicza zakład");
        if (crash.phase() == Table.Phase.RUNNING || seat.bets.get(0).cashedAt > 0) assertTrue(seat.result.payout() >= 100);
        assertEquals(0, held(a, "crash"));
    }

    @Test
    void crashAbsentPlayerWithoutAutoGetsRefundBeforeStart() {
        CrashTable crash = plugin.tables().crash();
        open(a, "crash");
        open(b, "crash");
        click(a, 49);
        a.closeInventory(); // A wychodzi bez auto-wypłaty
        ticks(2);
        click(b, 38);
        click(b, 49);
        until(() -> crash.phase() != Table.Phase.BETTING, 20 * 20);
        assertEquals(10_000, wallet(a), "stawka A wróciła przed startem");
        assertEquals(0, held(a, "crash"));
        assertNull(crash.seat(a.getUniqueId()));
        until(() -> crash.phase() == Table.Phase.WAITING, 20 * 200);
        assertEquals(0, held(b, "crash"));
    }

    @Test
    void crashAutoCashOutWorksWithoutWindow() {
        CrashTable crash = plugin.tables().crash();
        open(a, "crash");
        click(a, 38); // auto x2
        click(a, 49);
        a.closeInventory();
        until(() -> crash.phase() == Table.Phase.RESULT, 20 * 250);
        until(() -> crash.phase() == Table.Phase.WAITING, 20 * 10);
        assertTrue(wallet(a) == 9_900 || wallet(a) == 10_100, "A: " + wallet(a));
        assertEquals(0, held(a, "crash"));
    }

    @Test
    void cancelBetBeforeStartRefundsAndStopsCountdown() {
        CrashTable crash = plugin.tables().crash();
        open(a, "crash");
        click(a, 49);
        assertEquals(9_900, wallet(a));
        click(a, 49); // w tym samym slocie jest teraz „Cofnij zakład”
        assertEquals(10_000, wallet(a));
        assertEquals(Table.Phase.WAITING, crash.phase());
        assertTrue(crash.seats().isEmpty());
    }

    // ---------- ruletka ----------

    @Test
    void rouletteSharedWheelPaysEachPlayerOnce() {
        RouletteTable roulette = plugin.tables().roulette();
        open(a, "ruletka");
        open(b, "ruletka");
        click(a, 36); // czerwone
        click(b, 37); // czarne x2 (200)
        click(b, 37);
        assertEquals(9_900, wallet(a));
        assertEquals(9_800, wallet(b));
        // własny numer przez API (okno pyta o niego na czacie)
        assertNull(roulette.bet(a, "num:17", 100, 0));
        assertEquals(9_800, wallet(a));
        until(() -> roulette.phase() == Table.Phase.RESULT, 20 * 60);
        int n = roulette.result();
        long expectA = (Games.RED.contains(n) ? 200 : 0) + (n == 17 ? 3_600 : 0);
        long expectB = (n != 0 && !Games.RED.contains(n)) ? 400 : 0;
        assertEquals(10_000 - 200 + expectA, wallet(a));
        assertEquals(10_000 - 200 + expectB, wallet(b));
        until(() -> roulette.phase() == Table.Phase.WAITING, 20 * 20);
        assertEquals(n, roulette.history().get(0));
        assertEquals(0, held(a, "ruletka"));
        assertEquals(0, held(b, "ruletka"));
    }

    @Test
    void rouletteRejectsBadFieldsAndTooManyFields() {
        RouletteTable roulette = plugin.tables().roulette();
        assertNotNull(roulette.bet(a, "num:99", 100, 0));
        assertNotNull(roulette.bet(a, "cokolwiek", 100, 0));
        assertEquals(10_000, wallet(a));
        for (int i = 0; i < RouletteTable.MAX_FIELDS; i++) assertNull(roulette.bet(a, "num:" + i, 10, 0));
        assertNotNull(roulette.bet(a, "num:30", 10, 0), "limit pól");
        assertNull(roulette.bet(a, "num:3", 10, 0), "ten sam numer można podbić");
        assertNotNull(roulette.bet(b, "czerwone", 5, 0), "stawka poniżej minimum");
        assertEquals(10_000, wallet(b));
    }

    // ---------- koło i wyścigi ----------

    @Test
    void wheelOneSpinForEveryone() {
        KoloTable wheel = plugin.tables().wheel();
        open(a, "kolo");
        open(b, "kolo");
        click(a, 49);
        click(b, 49);
        click(b, 49); // dołożenie stawki
        assertEquals(9_900, wallet(a));
        assertEquals(9_800, wallet(b));
        until(() -> wheel.phase() == Table.Phase.RESULT, 20 * 60);
        double mult = wheel.segments().get(wheel.winner()).mult();
        assertEquals(9_900 + (long) Math.floor(100 * mult), wallet(a));
        assertEquals(9_800 + (long) Math.floor(200 * mult), wallet(b));
        until(() -> wheel.phase() == Table.Phase.WAITING, 20 * 20);
        assertEquals(0, held(a, "kolo") + held(b, "kolo"));
    }

    @Test
    void racesPayTheWinningHorse() {
        RaceTable races = plugin.tables().races();
        open(a, "wyscigi");
        open(b, "wyscigi");
        click(a, 18); // koń nr 3
        click(b, 0);  // koń nr 1
        click(b, 36); // koń nr 5
        assertEquals(9_900, wallet(a));
        assertEquals(9_800, wallet(b));
        until(() -> races.phase() == Table.Phase.RESULT, 20 * 60);
        int w = races.winner();
        double mult = races.horses().get(w).mult();
        assertEquals(9_900 + (w == 2 ? (long) Math.floor(100 * mult) : 0), wallet(a));
        assertEquals(9_800 + (w == 0 || w == 4 ? (long) Math.floor(100 * mult) : 0), wallet(b));
        until(() -> races.phase() == Table.Phase.WAITING, 20 * 20);
    }

    // ---------- bezpieczeństwo pieniędzy ----------

    @Test
    void shutdownRefundsEveryOpenBet() {
        open(a, "ruletka");
        open(b, "crash");
        click(a, 36);
        click(b, 49);
        assertEquals(9_900, wallet(a));
        assertEquals(9_900, wallet(b));
        plugin.tables().shutdown();
        assertEquals(10_000, wallet(a));
        assertEquals(10_000, wallet(b));
        assertEquals(0, held(a, "ruletka"));
        assertEquals(0, held(b, "crash"));
    }

    @Test
    void shutdownDuringCrashRoundRefundsUncashedBets() {
        CrashTable crash = plugin.tables().crash();
        open(a, "crash");
        click(a, 49);
        until(() -> crash.phase() == Table.Phase.RUNNING, 20 * 20);
        if (crash.phase() == Table.Phase.RUNNING) {
            plugin.tables().shutdown();
            assertEquals(10_000, wallet(a), "nierozliczony zakład wraca w całości");
        }
        assertEquals(0, held(a, "crash"));
    }

    @Test
    void heldStakesDoNotTouchSoloGameStake() {
        // gra solo trzyma activeStake, a zakład przy stole ma własną księgę: rozliczenie jednego nie rusza drugiego
        plugin.eco().pd(a.getUniqueId()).wallet = 5_000;
        assertEquals(pl.kasyno.core.Eco.BetError.NONE, plugin.eco().takeBet(a.getUniqueId(), 500, "miny"));
        assertNull(plugin.tables().roulette().bet(a, "czerwone", 100, 0));
        assertEquals(500, plugin.eco().activeStake(a.getUniqueId()));
        assertEquals(100, held(a, "ruletka"));
        plugin.tables().shutdown();
        assertEquals(500, plugin.eco().activeStake(a.getUniqueId()), "zwrot ze stołu nie kasuje stawki gry solo");
        assertEquals(4_500, wallet(a));
    }

    @Test
    void recoverHeldReturnsOrphanedStakesAfterCrash() {
        var d = plugin.eco().pd(a.getUniqueId());
        d.wallet = 0;
        d.held.put("crash", 700L);
        d.held.put("ruletka", 300L);
        assertEquals(1_000, plugin.eco().recoverHeld());
        assertEquals(1_000, d.wallet);
        assertTrue(d.held.isEmpty());
        assertEquals(0, plugin.eco().recoverHeld());
    }

    @Test
    void openingBetsAnnouncesTableToOthers() {
        a.nextMessage(); // wyczyść powitanie
        b.nextMessage();
        plugin.tables().roulette().bet(a, "czerwone", 100, 0);
        String msg = b.nextMessage();
        assertNotNull(msg, "B dostaje zaproszenie do stołu");
        assertTrue(msg.contains("Ruletka"), msg);
    }
}
