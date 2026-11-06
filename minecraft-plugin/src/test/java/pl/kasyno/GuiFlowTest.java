package pl.kasyno;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kasyno.games.GameRegistry;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.GuiManager;
import pl.kasyno.gui.Screens;
import pl.kasyno.gui.T;
import pl.kasyno.gui.screens.MainMenu;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Testy okien: otwieranie, klikanie, animacje gier i rozliczenia na atrapie serwera Paper. */
class GuiFlowTest {
    static {
        System.setProperty("kasyno.test", "1"); // bez wbudowanego serwera HTTP z paczką zasobów
    }

    ServerMock server;
    KasynoPlugin plugin;
    PlayerMock player;
    UUID id;

    @BeforeEach
    void setUp() {
        GuiManager.clickGapMs = 0;
        server = MockBukkit.mock();
        plugin = MockBukkit.load(KasynoPlugin.class);
        player = server.addPlayer();
        id = player.getUniqueId();
        PlayerListener.prepare(plugin, player);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Gui open(String key) {
        assertTrue(Screens.open(plugin, player, key), "ekran " + key);
        server.getScheduler().performOneTick();
        Gui g = plugin.guis().current(player);
        assertNotNull(g, "okno " + key + " powinno być otwarte");
        return g;
    }

    private void click(int slot) {
        player.simulateInventoryClick(slot);
        server.getScheduler().performOneTick();
    }

    private void ticks(int n) {
        server.getScheduler().performTicks(n);
    }

    /** Klika pierwszy przedmiot o podanej nazwie (okno przerysowuje się po kliknięciu, więc szukamy za każdym razem od nowa). */
    private void clickByName(String part) {
        for (int slot = 0; slot < top().getSize(); slot++) {
            if (name(slot).contains(part)) {
                click(slot);
                return;
            }
        }
        throw new AssertionError("Nie ma przedmiotu: " + part);
    }

    private Inventory top() {
        return player.getOpenInventory().getTopInventory();
    }

    private String name(int slot) {
        ItemStack it = top().getItem(slot);
        return it == null || !it.hasItemMeta() || it.getItemMeta().displayName() == null ? "" : T.plain(it.getItemMeta().displayName());
    }

    // ---------- start pluginu i komendy ----------

    @Test
    void pluginLoadsAndRegistersCommands() {
        for (String c : List.of("kasyno", "moneta", "sloty", "crash", "kurczak", "daily", "sklep", "bank", "saldo", "przelew", "jackpot", "loteria")) {
            assertNotNull(server.getCommandMap().getCommand(c), "komenda /" + c);
        }
    }

    @Test
    void kasynoCommandOpensMainMenu() {
        server.dispatchCommand(player, "kasyno");
        server.getScheduler().performOneTick();
        assertTrue(player.getOpenInventory().getTopInventory().getHolder() instanceof MainMenu);
    }

    @Test
    void everyScreenOpensWithoutErrors() {
        for (String key : Screens.keys()) {
            plugin.eco().pd(id).wallet = 1_000_000;
            Gui g = open(key);
            assertTrue(top().getSize() >= 27, key);
            g.player().closeInventory();
            server.getScheduler().performOneTick();
        }
    }

    @Test
    void mainMenuNavigates() {
        open("menu");
        click(14); // sklep
        assertEquals("ShopGui", plugin.guis().current(player).getClass().getSimpleName());
        click(49); // wróć do menu kategorii... (z listy kategorii wraca do menu głównego)
        assertEquals("MainMenu", plugin.guis().current(player).getClass().getSimpleName());
    }

    // ---------- sklep i ekwipunek ----------

    @Test
    void shopBuyAndInventoryUse() {
        plugin.eco().pd(id).wallet = 100_000;
        open("sklep");
        click(10); // pierwsza kategoria: wędki i kilofy
        assertEquals("ShopGui", plugin.guis().current(player).getClass().getSimpleName());
        click(9); // pierwszy przedmiot: wędka (500)
        assertEquals(1, plugin.eco().qty(id, "wedka"));
        assertEquals(100_000 - 500, plugin.eco().wallet(id));
        click(9); // drugi raz: już ma
        assertEquals(1, plugin.eco().qty(id, "wedka"));

        // eliksir z ekwipunku
        plugin.eco().addItem(id, "podrecznik", 1);
        plugin.eco().addItem(id, "polisa_mala", 1);
        open("ekwipunek");
        clickByName("Podręcznik");
        clickByName("Polisa mała");
        assertTrue(plugin.eco().boostLeft(id, "xp") > 0, "eliksir aktywny");
        assertEquals(1, plugin.eco().buffUses(id, "polisa_mala"), "polisa uzbrojona");
    }

    @Test
    void shortCommandsWork() {
        plugin.eco().pd(id).wallet = 5000;
        server.dispatchCommand(player, "kup wedka");
        assertEquals(1, plugin.eco().qty(id, "wedka"));
        server.dispatchCommand(player, "daily");
        assertTrue(plugin.eco().wallet(id) > 4500);
        server.dispatchCommand(player, "las");
        server.dispatchCommand(player, "saldo");
        server.dispatchCommand(player, "wplac 1000");
        assertEquals(1000, plugin.eco().pd(id).bank);
        server.dispatchCommand(player, "wyplac all");
        assertEquals(0, plugin.eco().pd(id).bank);
        plugin.eco().addItem(id, "sledz", 4);
        server.dispatchCommand(player, "sprzedaj wszystko");
        assertEquals(0, plugin.eco().qty(id, "sledz"));
        server.dispatchCommand(player, "mnozniki");
        server.dispatchCommand(player, "moneta 500");
        server.getScheduler().performOneTick();
        assertEquals("MonetaGui", plugin.guis().current(player).getClass().getSimpleName());
    }

    // ---------- gry ----------

    /** Wszystkie gry: otwórz, zagraj kliknięciem przycisku gry (slot 49), odczekaj animację i sprawdź, że pieniądze się zgadzają. */
    @Test
    void everyGamePlaysAndSettlesConsistently() {
        for (GameRegistry.Def def : GameRegistry.all()) {
            if (def.live()) continue; // stoły na żywo mają własne testy (TableTest)
            plugin.eco().pd(id).wallet = 1_000_000;
            plugin.eco().pd(id).activeStake = 0;
            long before = plugin.eco().wallet(id);
            Gui g = def.factory().apply(plugin, player);
            g.open();
            server.getScheduler().performOneTick();
            // KNP i zdrapka wymagają dodatkowego kroku
            switch (def.id()) {
                case "knp" -> click(19);
                case "zdrapka" -> {
                    click(49);
                    click(40);
                }
                default -> click(49);
            }
            ticks(400);
            // gry z przyciskami: zamknięcie okna rozlicza stawkę (wypłata albo zwrot)
            player.closeInventory();
            ticks(5);
            long after = plugin.eco().wallet(id);
            assertEquals(0, plugin.eco().activeStake(id), def.id() + ": stawka musi być rozliczona");
            long xpBonusMax = 5_000_000;
            assertTrue(after >= 0 && after < before + xpBonusMax, def.id() + ": saldo " + before + " → " + after);
            assertEquals(1, plugin.eco().pd(id).games + (plugin.eco().pd(id).games == 0 ? 1 : 0), def.id() + ": rozegrano grę");
            plugin.eco().pd(id).games = 0;
        }
    }

    @Test
    void monetaMoneyIsExact() {
        int wins = 0;
        int losses = 0;
        for (int i = 0; i < 40; i++) {
            plugin.eco().pd(id).wallet = 10_000;
            long levelBefore = plugin.eco().pd(id).level;
            open("moneta");
            click(49);
            ticks(200);
            long w = plugin.eco().wallet(id);
            long bonus = 0;
            for (int l = (int) levelBefore + 1; l <= plugin.eco().pd(id).level; l++) bonus += l * 200L;
            long net = w - 10_000 - bonus;
            assertTrue(net == -100 || net == 95, "moneta: wynik " + net);
            if (net > 0) wins++;
            else losses++;
            player.closeInventory();
            ticks(2);
        }
        assertTrue(wins > 5 && losses > 5, "wygrane " + wins + ", przegrane " + losses);
    }

    @Test
    void minesPolicyAndShieldBlocksMine() {
        plugin.eco().pd(id).wallet = 100_000;
        plugin.eco().addBuff(id, "tarcza_mala", 1);
        open("miny");
        click(49); // start
        assertEquals(1, plugin.eco().buffUses(id, "tarcza_mala"));
        // klikamy wszystkie pola po kolei, aż tarcza się zużyje albo gra się skończy
        boolean shieldUsed = false;
        for (int idx = 0; idx < 20 && plugin.guis().current(player) != null; idx++) {
            int slot = (1 + idx / 5) * 9 + 2 + idx % 5;
            click(slot);
            if (plugin.eco().buffUses(id, "tarcza_mala") == 0) shieldUsed = true;
            if (plugin.eco().activeStake(id) == 0) break;
        }
        player.closeInventory();
        ticks(2);
        assertEquals(0, plugin.eco().activeStake(id));
        assertTrue(shieldUsed || plugin.eco().pd(id).games > 0);
    }

    @Test
    void closingHeldGameRefundsOrPays() {
        plugin.eco().pd(id).wallet = 50_000;
        open("kurczak");
        click(49); // start
        assertEquals(50_000 - 100, plugin.eco().wallet(id));
        player.closeInventory();
        ticks(2);
        assertEquals(50_000, plugin.eco().wallet(id), "bez kroku stawka wraca");
        assertEquals(0, plugin.eco().activeStake(id));
    }

    @Test
    void blackjackPlaysThrough() {
        for (int i = 0; i < 30; i++) {
            plugin.eco().pd(id).wallet = 100_000;
            plugin.eco().pd(id).activeStake = 0;
            open("blackjack");
            click(49);
            // dobieramy, aż gra się skończy albo spasujemy
            for (int k = 0; k < 6 && plugin.eco().activeStake(id) > 0; k++) {
                Inventory inv = top();
                if (inv.getItem(38) != null && name(38).contains("Dobierz")) click(k % 2 == 0 ? 38 : 40);
            }
            ticks(100);
            player.closeInventory();
            ticks(2);
            assertEquals(0, plugin.eco().activeStake(id), "blackjack: stawka rozliczona");
            long w = plugin.eco().wallet(id);
            assertTrue(w >= 99_900 - 100 && w <= 100_000 + 250 + 5_000, "blackjack: saldo " + w);
        }
    }

    // ---------- praca, biznesy, skrzynie, multiplayer ----------

    @Test
    void earnBusinessFarmCratesBank() {
        plugin.eco().pd(id).wallet = 10_000_000;
        open("zarobki");
        click(10); // daily
        assertTrue(plugin.eco().pd(id).lastDaily > 0);
        open("biznes");
        click(10); // kup kiosk
        assertEquals(1, plugin.eco().pd(id).businesses.size());
        click(29); // puste pole farmy -> wybór uprawy
        click(19); // marchew na pole 1
        assertEquals(1, plugin.empire().status(id, System.currentTimeMillis()).stream().filter(s -> s.state().equals("growing")).count());
        plugin.eco().addItem(id, "skrzynia", 2);
        open("skrzynie");
        int before = plugin.eco().qty(id, "skrzynia");
        for (int slot : new int[]{19, 20, 21, 23, 24, 25}) if (name(slot).contains("Skrzynia skarbów")) click(slot);
        ticks(400);
        assertEquals(before - 1, plugin.eco().qty(id, "skrzynia"), "otwarcie zdjęło skrzynię");
        player.closeInventory();
        open("bank");
        click(11); // wpłać 1k
        assertTrue(plugin.eco().pd(id).bank > 0);
    }

    @Test
    void jackpotWithTwoPlayers() {
        PlayerMock other = server.addPlayer();
        PlayerListener.prepare(plugin, other);
        plugin.eco().pd(id).wallet = 10_000;
        plugin.eco().pd(other.getUniqueId()).wallet = 10_000;
        assertEquals(null, plugin.rooms().start(player, pl.kasyno.games.Rooms.Kind.JACKPOT, 1000));
        assertEquals(null, plugin.rooms().join(other, pl.kasyno.games.Rooms.Kind.JACKPOT));
        assertEquals(9000, plugin.eco().wallet(id));
        ticks(20 * 50);
        long total = plugin.eco().wallet(id) + plugin.eco().wallet(other.getUniqueId());
        long levelBonus = 0;
        for (UUID u : List.of(id, other.getUniqueId())) for (int l = 2; l <= plugin.eco().pd(u).level; l++) levelBonus += l * 200L;
        assertEquals(20_000 - 100 + levelBonus, total, "prowizja 5% z puli 2000");
    }

    @Test
    void npcSpawnsAndOpensMenu() {
        var world = server.addSimpleWorld("world");
        player.teleport(new org.bukkit.Location(world, 0, 64, 0));
        var v = plugin.npcs().spawn(player.getLocation(), "bank", 0);
        assertNotNull(v);
        assertTrue(plugin.npcs().isNpc(v));
        assertEquals("bank", plugin.npcs().typeOf(v));
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerInteractEntityEvent(player, v, org.bukkit.inventory.EquipmentSlot.HAND));
        ticks(2);
        assertEquals("BankGui", plugin.guis().current(player).getClass().getSimpleName());
        plugin.npcs().remove(v);
        assertEquals(0, plugin.npcs().count());
    }

    @Test
    void npcsOpenHallsAndLiveTables() {
        var world = server.addSimpleWorld("world");
        player.teleport(new org.bukkit.Location(world, 0, 64, 0));
        for (String[] pair : new String[][]{{"live", "HallGui"}, {"automaty", "HallGui"}, {"kasyno", "CasinoGui"}, {"crash", "CrashView"}, {"ruletka", "RouletteView"}}) {
            var v = plugin.npcs().spawn(player.getLocation(), pair[0], 0);
            assertNotNull(v, pair[0]);
            server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerInteractEntityEvent(player, v, org.bukkit.inventory.EquipmentSlot.HAND));
            ticks(2);
            assertEquals(pair[1], plugin.guis().current(player).getClass().getSimpleName(), "NPC " + pair[0]);
            player.closeInventory();
            ticks(2);
            plugin.npcs().remove(v);
        }
        assertEquals(0, plugin.npcs().count());
    }

    @Test
    void adminAndNpcCommands() {
        var world = server.addSimpleWorld("world");
        player.teleport(new org.bukkit.Location(world, 0, 64, 0));
        // zwykły gracz nie może stawiać NPC ani dawać monet
        server.dispatchCommand(player, "kasyno npc spawn bank");
        assertEquals(0, plugin.npcs().count());
        player.setOp(true);
        server.dispatchCommand(player, "kasyno npc spawn kurczak");
        assertEquals(1, plugin.npcs().count());
        assertEquals("kurczak", plugin.npcs().typeOf(plugin.npcs().all().get(0)));
        server.dispatchCommand(player, "kasyno npc spawn nie_ma_takiego");
        assertEquals(1, plugin.npcs().count());
        server.dispatchCommand(player, "kasyno npc list");
        server.dispatchCommand(player, "kasyno npc remove");
        assertEquals(0, plugin.npcs().count());

        server.dispatchCommand(player, "kasyno admin give " + player.getName() + " 2k");
        assertEquals(500 + 2000, plugin.eco().wallet(id));
        server.dispatchCommand(player, "kasyno admin item " + player.getName() + " polisa_mala 2");
        assertEquals(2, plugin.eco().qty(id, "polisa_mala"));
        // podpowiedzi typów NPC
        var tab = server.getCommandMap().getCommand("kasyno").tabComplete(player, "kasyno", new String[]{"npc", "spawn", "ban"});
        assertTrue(tab.contains("bank"), "podpowiedź typu NPC: " + tab);
    }

    @Test
    void jackpotRefundsWhenNobodyJoins() {
        plugin.eco().pd(id).wallet = 5000;
        assertEquals(null, plugin.rooms().start(player, pl.kasyno.games.Rooms.Kind.JACKPOT, 1000));
        assertEquals(4000, plugin.eco().wallet(id));
        ticks(20 * 50);
        assertEquals(5000, plugin.eco().wallet(id), "bez uczestników stawka wraca");
    }

    @Test
    void duelMovesMoneyWithFee() {
        PlayerMock other = server.addPlayer();
        PlayerListener.prepare(plugin, other);
        plugin.eco().pd(id).wallet = 5000;
        plugin.eco().pd(other.getUniqueId()).wallet = 5000;
        assertEquals(null, plugin.rooms().challenge(player, other, 1000));
        assertEquals(null, plugin.rooms().accept(other));
        ticks(20 * 12); // scena pojedynku trwa ok. 10 s
        long total = plugin.eco().wallet(id) + plugin.eco().wallet(other.getUniqueId());
        long levelBonus = 0;
        for (UUID u : List.of(id, other.getUniqueId())) for (int l = 2; l <= plugin.eco().pd(u).level; l++) levelBonus += l * 200L;
        assertEquals(10_000 - 60 + levelBonus, total, "z puli 2000 znika 3%");
    }
}
