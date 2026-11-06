package pl.kasyno;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kasyno.core.Heist;
import pl.kasyno.games.Rooms;
import pl.kasyno.gui.GuiManager;
import pl.kasyno.gui.T;
import pl.kasyno.gui.screens.RoomGui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sceny: napad etapami, losowanie jackpota, pojedynek, loteria i sceny przed akcjami zarobkowymi. */
class SceneTest {
    static {
        System.setProperty("kasyno.test", "1");
    }

    ServerMock server;
    KasynoPlugin plugin;

    @BeforeEach
    void setUp() {
        GuiManager.clickGapMs = 0;
        server = MockBukkit.mock();
        plugin = MockBukkit.load(KasynoPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock newPlayer(long wallet) {
        PlayerMock p = server.addPlayer();
        PlayerListener.prepare(plugin, p);
        plugin.eco().pd(p.getUniqueId()).wallet = wallet;
        return p;
    }

    private void ticks(int n) {
        server.getScheduler().performTicks(n);
    }

    private String name(Inventory inv, int slot) {
        ItemStack it = inv.getItem(slot);
        return it == null || !it.hasItemMeta() || it.getItemMeta().displayName() == null ? "" : T.plain(it.getItemMeta().displayName());
    }

    // ---------- napad ----------

    @Test
    void heistPaysOnlySurvivorsAndMoneyAddsUp() {
        int successes = 0;
        int failures = 0;
        for (int round = 0; round < 25; round++) {
            int n = 2 + round % 5;
            List<PlayerMock> crew = new ArrayList<>();
            for (int i = 0; i < n; i++) crew.add(newPlayer(10_000));
            assertNull(plugin.rooms().start(crew.get(0), Rooms.Kind.HEIST, 500));
            for (int i = 1; i < n; i++) assertNull(plugin.rooms().join(crew.get(i), Rooms.Kind.HEIST));
            ticks(20 * 46);
            Rooms.Lobby l = plugin.rooms().lobby(Rooms.Kind.HEIST);
            assertNotNull(l);
            assertTrue(l.resolved);
            assertNotNull(l.heist);

            // pieniądze są rozliczone od razu, jeszcze zanim scena się skończy
            long total = 0;
            for (PlayerMock p : crew) total += plugin.eco().wallet(p.getUniqueId());
            long expected = n * 9_500L + (l.success ? l.share * l.heist.survivors().size() : 0);
            assertEquals(expected, total, "napad " + n + " os.: suma portfeli");
            for (UUID u : l.heist.caught()) assertEquals(9_500, plugin.eco().wallet(u), "złapany traci tylko wpisowe");
            for (UUID u : l.heist.survivors()) assertEquals(9_500 + l.share, plugin.eco().wallet(u));
            if (l.success) {
                successes++;
                assertTrue(l.payout >= (long) Math.floor(n * 500 * Heist.BASE_MULT));
                assertTrue(l.share * l.heist.survivors().size() <= l.payout);
            } else {
                failures++;
                assertEquals(0, l.payout);
                assertTrue(l.heist.survivors().isEmpty());
            }
            assertEquals(-1, l.step, "scena jeszcze się nie zaczęła: najpierw wstęp");

            // scena trwa kilkanaście sekund; potem stan jest końcowy
            ticks(20 * 36);
            assertTrue(l.sceneDone);
            assertEquals(l.frames.size() - 1, l.step);
            for (PlayerMock p : crew) {
                String last = null;
                String msg;
                while ((msg = p.nextMessage()) != null) last = msg;
                assertNotNull(last, "każdy z ekipy dostaje podsumowanie na czacie");
            }
        }
        assertTrue(successes > 3 && failures > 0, "napady udane " + successes + ", nieudane " + failures);
    }

    @Test
    void heistWindowOpensByItselfAndPlaysTheMapFilm() {
        PlayerMock a = newPlayer(10_000);
        PlayerMock b = newPlayer(10_000);
        PlayerMock c = newPlayer(10_000);
        assertNull(plugin.rooms().start(a, Rooms.Kind.HEIST, 500));
        plugin.rooms().join(b, Rooms.Kind.HEIST);
        plugin.rooms().join(c, Rooms.Kind.HEIST);
        // a ma otwarte okno lobby, b i c nie mają żadnego okna
        new RoomGui(plugin, a, Rooms.Kind.HEIST).open();
        server.getScheduler().performOneTick();
        assertTrue(name(a.getOpenInventory().getTopInventory(), 4).contains("Napad"));
        ticks(20 * 46); // losowanie
        Rooms.Lobby l = plugin.rooms().lobby(Rooms.Kind.HEIST);
        assertTrue(l.resolved);
        for (PlayerMock p : List.of(a, b, c)) {
            assertEquals("RoomGui", plugin.guis().current(p).getClass().getSimpleName(), "okno napadu otworzyło się samo");
        }
        assertEquals(54, b.getOpenInventory().getTopInventory().getSize());

        // film: okno zmienia się klatka po klatce, a ekipa wędruje po mapie
        Inventory inv;
        java.util.Set<Integer> tokenCells = new java.util.HashSet<>();
        java.util.Set<Integer> steps = new java.util.HashSet<>();
        boolean sawProp = false;
        for (int t = 0; t < 700 && !l.sceneDone; t++) {
            ticks(1);
            steps.add(l.step);
            inv = b.getOpenInventory().getTopInventory();
            for (int slot = 0; slot < 45; slot++) {
                if (slot % 9 == 0 || slot % 9 == 8) continue;
                if (name(inv, slot).contains("Ekipa:")) tokenCells.add(slot);
                if (name(inv, slot).contains("Drzwi banku") || name(inv, slot).contains("Van ekipy")) sawProp = true;
            }
        }
        assertTrue(l.sceneDone);
        // długość filmu zależy od losu napadu (cała ekipa złapana na wczesnym etapie to krótki film), więc porównujemy z jego długością
        assertTrue(steps.size() >= Math.min(15, l.frames.size() - 1), "klatek odtworzonych: " + steps.size() + " z " + l.frames.size());
        assertTrue(tokenCells.size() >= 2, "ekipa przeszła po komórkach mapy: " + tokenCells);
        assertTrue(sawProp, "na mapie stoją drzwi i van");
        // portrety ekipy w skrajnych kolumnach i wynik na końcu
        ticks(5);
        inv = b.getOpenInventory().getTopInventory();
        int portraits = 0;
        for (int slot : new int[]{0, 9, 18}) {
            Material m = inv.getItem(slot) == null ? Material.AIR : inv.getItem(slot).getType();
            if (m == Material.PLAYER_HEAD || m == Material.IRON_BARS) portraits++;
        }
        assertEquals(3, portraits);
        assertTrue(name(inv, 53).contains("Napad"), "wynik: " + name(inv, 53));
        assertTrue(name(inv, 46).contains("Co się dzieje"));
    }

    // ---------- jackpot ----------

    @Test
    void jackpotSpinEndsOnTheWinner() {
        PlayerMock a = newPlayer(10_000);
        PlayerMock b = newPlayer(10_000);
        PlayerMock c = newPlayer(10_000);
        assertNull(plugin.rooms().start(a, Rooms.Kind.JACKPOT, 1_000));
        plugin.rooms().join(b, Rooms.Kind.JACKPOT);
        plugin.rooms().join(c, Rooms.Kind.JACKPOT);
        ticks(20 * 46);
        Rooms.Lobby l = plugin.rooms().lobby(Rooms.Kind.JACKPOT);
        assertTrue(l.resolved);
        assertEquals(l.crew.indexOf(l.winner), l.spin[l.spin.length - 1], "ostatnia klatka stoi na zwycięzcy");
        long total = 0;
        for (PlayerMock p : List.of(a, b, c)) total += plugin.eco().wallet(p.getUniqueId());
        assertEquals(30_000 - 3_000 + l.payout, total, "wygrana jest wypłacona od razu");
        ticks(20 * 15);
        assertTrue(l.sceneDone);
        assertEquals("RoomGui", plugin.guis().current(a).getClass().getSimpleName(), "okno jackpota otworzyło się samo");
        assertTrue(name(a.getOpenInventory().getTopInventory(), 4).contains("Zwycięzca"));
    }

    // ---------- pojedynek i loteria ----------

    @Test
    void duelSceneEndsWithBackButton() {
        PlayerMock a = newPlayer(5_000);
        PlayerMock b = newPlayer(5_000);
        assertNull(plugin.rooms().challenge(a, b, 1_000));
        assertNull(plugin.rooms().accept(b));
        ticks(20);
        assertEquals("DuelGui", plugin.guis().current(a).getClass().getSimpleName());
        assertEquals("DuelGui", plugin.guis().current(b).getClass().getSimpleName());
        ticks(20 * 12);
        Inventory inv = a.getOpenInventory().getTopInventory();
        assertEquals(54, inv.getSize());
        assertTrue(name(inv, 13).contains("Zwycięzca"), name(inv, 13));
        assertTrue(name(inv, 49).contains("Wróć"));
    }

    @Test
    void lotteryDrawPaysImmediatelyAndPlaysScene() {
        PlayerMock a = newPlayer(10_000);
        PlayerMock b = newPlayer(10_000);
        assertNull(plugin.lottery().buy(a.getUniqueId(), 5));
        assertNull(plugin.lottery().buy(b.getUniqueId(), 10));
        long pot = plugin.lottery().pool().pot();
        plugin.lottery().draw();
        long total = plugin.eco().wallet(a.getUniqueId()) + plugin.eco().wallet(b.getUniqueId());
        assertEquals(20_000 - 1_500 + pot, total, "pula wypłacona od razu");
        assertNotNull(plugin.lottery().scene());
        assertTrue(!plugin.lottery().scene().done);
        ticks(2);
        assertEquals("LotteryGui", plugin.guis().current(a).getClass().getSimpleName(), "uczestnik widzi losowanie w oknie");
        ticks(20 * 20);
        assertTrue(plugin.lottery().scene().done);
        assertEquals(plugin.lottery().scene().winner, plugin.store().global().lotteryLastName);
        assertEquals(0, plugin.eco().pd(a.getUniqueId()).lotteryTickets);
    }

    // ---------- sceny przed akcjami zarobkowymi ----------

    @Test
    void earnSceneRunsBeforeTheActionAndClosingCancelsIt() {
        PlayerMock p = newPlayer(5_000);
        UUID id = p.getUniqueId();
        server.dispatchCommand(p, "zarobki");
        server.getScheduler().performOneTick();
        p.simulateInventoryClick(16); // przestępstwo
        ticks(2);
        assertEquals("SceneGui", plugin.guis().current(p).getClass().getSimpleName());
        assertEquals(5_000, plugin.eco().wallet(id), "przed końcem sceny nic się nie dzieje");
        assertEquals(0, plugin.eco().cooldownLeft(id, "crime"));
        ticks(20);
        p.closeInventory();
        ticks(20 * 10);
        assertEquals(0, plugin.eco().cooldownLeft(id, "crime"), "zamknięcie okna anuluje akcję bez kosztów");

        // druga próba do końca
        server.dispatchCommand(p, "zarobki");
        server.getScheduler().performOneTick();
        p.simulateInventoryClick(16);
        ticks(20 * 6);
        assertTrue(plugin.eco().cooldownLeft(id, "crime") > 0, "po scenie akcja się odbyła");
        Inventory inv = p.getOpenInventory().getTopInventory();
        assertTrue(name(inv, 49).contains("Wróć"));
        assertTrue(name(inv, 13).length() > 0, "wynik: " + name(inv, 13));
        p.simulateInventoryClick(49);
        ticks(2);
        assertEquals("EarnGui", plugin.guis().current(p).getClass().getSimpleName());
    }

    @Test
    void everySoloSceneResolvesTheAction() {
        PlayerMock p = newPlayer(50_000);
        UUID id = p.getUniqueId();
        plugin.eco().addItem(id, "wedka", 1);
        plugin.eco().addItem(id, "kilof", 1);
        plugin.eco().addItem(id, "strzelba", 1);
        // slot kafelka → klucz cooldownu
        Object[][] tiles = {{13, "praca"}, {14, "zebraj"}, {15, "uliczny"}, {19, "ryby"}, {20, "kopalnia"}, {21, "polowanie"}, {22, "las"}, {23, "smietnik"}};
        for (Object[] tile : tiles) {
            server.dispatchCommand(p, "zarobki");
            server.getScheduler().performOneTick();
            p.simulateInventoryClick((Integer) tile[0]);
            ticks(2);
            assertEquals("SceneGui", plugin.guis().current(p).getClass().getSimpleName(), "scena dla " + tile[1]);
            ticks(20 * 6);
            assertTrue(plugin.eco().cooldownLeft(id, (String) tile[1]) > 0, "akcja " + tile[1] + " odbyła się po scenie");
            p.closeInventory();
            ticks(2);
        }
        // daily jest natychmiastowe, bez sceny
        server.dispatchCommand(p, "zarobki");
        server.getScheduler().performOneTick();
        p.simulateInventoryClick(10);
        ticks(2);
        assertEquals("EarnGui", plugin.guis().current(p).getClass().getSimpleName());
        assertTrue(plugin.eco().pd(id).lastDaily > 0);
    }

    @Test
    void robbingPlaysSceneThenSettles() {
        PlayerMock thief = newPlayer(5_000);
        PlayerMock victim = newPlayer(5_000);
        server.dispatchCommand(thief, "zarobki");
        server.getScheduler().performOneTick();
        thief.simulateInventoryClick(30); // okradnij gracza
        ticks(2);
        assertEquals("PlayerPicker", plugin.guis().current(thief).getClass().getSimpleName());
        // pierwszy dostępny gracz na liście (poza sobą)
        Inventory inv = thief.getOpenInventory().getTopInventory();
        int slot = -1;
        for (int s = 0; s < inv.getSize() && slot < 0; s++) if (inv.getItem(s) != null && inv.getItem(s).getType() == Material.PLAYER_HEAD) slot = s;
        assertTrue(slot >= 0, "lista graczy");
        thief.simulateInventoryClick(slot);
        ticks(2);
        assertEquals("SceneGui", plugin.guis().current(thief).getClass().getSimpleName());
        long before = plugin.eco().wallet(thief.getUniqueId()) + plugin.eco().wallet(victim.getUniqueId());
        ticks(20 * 6);
        long after = plugin.eco().wallet(thief.getUniqueId()) + plugin.eco().wallet(victim.getUniqueId());
        assertEquals(before, after, "okradanie przenosi pieniądze między graczami, nic nie znika");
        assertTrue(plugin.eco().cooldownLeft(thief.getUniqueId(), "rob") > 0);
    }
}
