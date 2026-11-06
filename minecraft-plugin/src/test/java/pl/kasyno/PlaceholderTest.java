package pl.kasyno;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kasyno.gui.GuiManager;
import pl.kasyno.gui.Screens;
import pl.kasyno.gui.T;
import pl.kasyno.npc.PlaceholderManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Placeholdery: niewidzialne pola kliknięcia, które otwierają grę prawym przyciskiem myszy (bez NPC). */
class PlaceholderTest {
    static {
        System.setProperty("kasyno.test", "1"); // bez wbudowanego serwera HTTP z paczką zasobów
    }

    ServerMock server;
    KasynoPlugin plugin;
    PlayerMock player;
    World world;

    @BeforeEach
    void setUp() {
        GuiManager.clickGapMs = 0;
        server = MockBukkit.mock();
        plugin = MockBukkit.load(KasynoPlugin.class);
        world = server.addSimpleWorld("world");
        player = server.addPlayer();
        PlayerListener.prepare(plugin, player);
        player.teleport(new Location(world, 0, 64, 0));
        plugin.eco().pd(player.getUniqueId()).wallet = 1_000_000;
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlaceholderManager phs() {
        return plugin.placeholders();
    }

    private void rightClick(Entity e, EquipmentSlot hand) {
        server.getPluginManager().callEvent(new PlayerInteractEntityEvent(player, e, hand));
        server.getScheduler().performTicks(2);
    }

    private String openWindow() {
        var g = plugin.guis().current(player);
        return g == null ? null : g.getClass().getSimpleName();
    }

    @Test
    void spawnsAClickAreaOfTheGivenSizeThatIsNotAnNpc() {
        Interaction in = phs().spawn(player.getLocation(), "blackjack", 2f, 1.5f);
        assertNotNull(in);
        assertTrue(phs().isPlaceholder(in));
        assertEquals("blackjack", phs().gameOf(in));
        assertEquals(2f, in.getInteractionWidth());
        assertEquals(1.5f, in.getInteractionHeight());
        assertEquals(1, phs().count());
        assertEquals(0, plugin.npcs().count(), "placeholder to nie NPC");
        assertFalse(plugin.npcs().isNpc(in));

        assertEquals(PlaceholderManager.MAX_SIZE, PlaceholderManager.clamp(100));
        assertEquals(PlaceholderManager.MIN_SIZE, PlaceholderManager.clamp(0));
        assertEquals(PlaceholderManager.DEFAULT_SIZE, PlaceholderManager.clamp(Double.NaN));
        assertNull(phs().spawn(player.getLocation(), "nie_ma_takiej_gry", 1f, 1f), "nieznana gra");
        assertEquals(1, phs().count());
    }

    @Test
    void rightClickOpensTheAssignedGame() {
        Interaction in = phs().spawn(player.getLocation(), "blackjack", 1f, 1f);
        rightClick(in, EquipmentSlot.HAND);
        assertEquals("BlackjackGui", openWindow());
        player.closeInventory();
        server.getScheduler().performTicks(2);

        phs().remove(in);
        assertEquals(0, phs().count());
    }

    /** Każda nazwa, którą da się wpisać w komendzie (ekrany i wszystkie gry), otwiera swoje okno. */
    @Test
    void everyGameAndScreenOpensFromItsPlaceholder() {
        for (String key : Screens.keys()) {
            Interaction in = phs().spawn(player.getLocation(), key, 1f, 1f);
            assertNotNull(in, key);
            rightClick(in, EquipmentSlot.HAND);
            assertNotNull(openWindow(), "placeholder " + key + " otwiera okno");
            player.closeInventory();
            server.getScheduler().performTicks(2);
            phs().remove(in);
        }
        assertEquals(0, phs().count());
    }

    @Test
    void gameWindowsAreTheSameAsFromNpcs() {
        for (String[] pair : new String[][]{{"hilo", "HiloGui"}, {"kasyno", "CasinoGui"}, {"crash", "CrashView"}, {"ruletka", "RouletteView"}, {"karty", "HallGui"}}) {
            Interaction in = phs().spawn(player.getLocation(), pair[0], 1f, 1f);
            rightClick(in, EquipmentSlot.HAND);
            assertEquals(pair[1], openWindow(), pair[0]);
            player.closeInventory();
            server.getScheduler().performTicks(2);
            phs().remove(in);
        }
    }

    @Test
    void offHandAndForeignInteractionsDoNothing() {
        Interaction in = phs().spawn(player.getLocation(), "hilo", 1f, 1f);
        var offHand = new PlayerInteractEntityEvent(player, in, EquipmentSlot.OFF_HAND);
        server.getPluginManager().callEvent(offHand);
        server.getScheduler().performTicks(2);
        assertTrue(offHand.isCancelled(), "kliknięcie placeholdera nie robi nic innego");
        assertNull(openWindow(), "druga ręka nie otwiera okna drugi raz");

        // zwykła encja Interaction (cudza, bez znacznika) nie jest naszym polem
        Interaction foreign = world.spawn(player.getLocation(), Interaction.class);
        var click = new PlayerInteractEntityEvent(player, foreign, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(click);
        server.getScheduler().performTicks(2);
        assertFalse(click.isCancelled());
        assertNull(openWindow());
        assertFalse(phs().isPlaceholder(foreign));
        assertEquals(1, phs().count());
    }

    /** Typ gry siedzi w danych encji, więc nowy menedżer (jak po restarcie serwera) widzi to samo pole. */
    @Test
    void placeholdersSurviveARestart() {
        Interaction in = phs().spawn(player.getLocation(), "wieza", 3f, 2f);
        var fresh = new PlaceholderManager(plugin);
        assertEquals(1, fresh.count());
        assertEquals("wieza", fresh.gameOf(fresh.all().get(0)));
        assertEquals(in.getUniqueId(), fresh.all().get(0).getUniqueId());
    }

    @Test
    void adminCommandsManagePlaceholders() {
        // zwykły gracz nie może stawiać placeholderów
        server.dispatchCommand(player, "kasyno placeholder spawn blackjack");
        assertEquals(0, phs().count());

        player.setOp(true);
        server.dispatchCommand(player, "kasyno placeholder spawn blackjack 2 1,5");
        assertEquals(1, phs().count());
        Interaction a = phs().all().get(0);
        assertEquals("blackjack", phs().gameOf(a));
        assertEquals(2f, a.getInteractionWidth());
        assertEquals(1.5f, a.getInteractionHeight());

        server.dispatchCommand(player, "kasyno ph spawn hilo 3"); // jedna liczba to szerokość i wysokość
        assertEquals(2, phs().count());
        assertEquals(3f, phs().all().stream().filter(i -> phs().gameOf(i).equals("hilo")).findFirst().orElseThrow().getInteractionHeight());

        server.dispatchCommand(player, "kasyno placeholder spawn nie_ma_takiej");
        server.dispatchCommand(player, "kasyno placeholder spawn crash abc");
        server.dispatchCommand(player, "kasyno placeholder spawn crash 2 xyz");
        server.dispatchCommand(player, "kasyno placeholder spawn");
        assertEquals(2, phs().count(), "błędne polecenia nic nie stawiają");

        server.dispatchCommand(player, "kasyno placeholder list");
        server.dispatchCommand(player, "kasyno placeholder types");

        // obrys: rysuje się przez zadany czas i da się go wyłączyć
        server.dispatchCommand(player, "kasyno placeholder pokaz 5");
        assertTrue(phs().isShowing(player));
        server.getScheduler().performTicks(30);
        server.dispatchCommand(player, "kasyno placeholder pokaz off");
        assertFalse(phs().isShowing(player));
        server.dispatchCommand(player, "kasyno placeholder pokaz 1");
        server.getScheduler().performTicks(40);
        assertFalse(phs().isShowing(player), "obrys wyłącza się sam po zadanym czasie");

        server.dispatchCommand(player, "kasyno placeholder remove");
        assertEquals(1, phs().count());
        server.dispatchCommand(player, "kasyno placeholder removeall");
        assertEquals(1, phs().count(), "usuwanie wszystkich wymaga potwierdzenia");
        server.dispatchCommand(player, "kasyno placeholder removeall potwierdz");
        assertEquals(0, phs().count());
        assertEquals(0, plugin.npcs().count());
    }

    private List<TextDisplay> labelsInWorld() {
        return List.copyOf(world.getEntitiesByClass(TextDisplay.class));
    }

    private String textOf(TextDisplay label) {
        return T.plain(label.text());
    }

    /** Nad polem wisi napis z nazwą gry i podpowiedzią, nad górną krawędzią pola, i znika razem z polem. */
    @Test
    void placeholderHasALabelThatGoesAwayWithIt() {
        Interaction in = phs().spawn(player.getLocation(), "blackjack", 2f, 1.5f);
        TextDisplay label = phs().labelOf(in);
        assertNotNull(label, "napis nad polem");
        assertTrue(textOf(label).contains("Blackjack"), textOf(label));
        assertTrue(textOf(label).contains("Kliknij prawym przyciskiem"), textOf(label));
        assertTrue(label.getLocation().getY() >= in.getLocation().getY() + in.getInteractionHeight(), "napis nad górną krawędzią pola");
        assertEquals(1, labelsInWorld().size());

        phs().remove(in);
        assertEquals(0, labelsInWorld().size(), "napis znika razem z polem");
        assertEquals(0, phs().count());
    }

    /** Każda nazwa, którą można przypisać polu, ma czytelny napis (a nie surowy klucz). */
    @Test
    void everyGameAndScreenHasAReadableTitle() {
        assertEquals("Blackjack", phs().titleOf("blackjack"));
        assertEquals("Wyżej czy niżej", phs().titleOf("hilo"));
        assertEquals("Karty i kości", phs().titleOf("karty"));
        assertEquals("Stoły na żywo", phs().titleOf("live"));
        assertEquals("Bank", phs().titleOf("bank"));
        assertEquals("Pokój graczy", phs().titleOf("pokoj"));
        for (String key : Screens.keys()) {
            String title = phs().titleOf(key);
            assertFalse(title.isBlank(), key);
            assertTrue(Character.isUpperCase(title.charAt(0)), key + " ma napis z wielkiej litery, a nie surowy klucz: " + title);
        }
    }

    @Test
    void labelCanBeCustomisedSwitchedOffAndRestored() {
        player.setOp(true);
        Interaction in = phs().spawn(player.getLocation(), "sloty", 1f, 1f);
        assertTrue(textOf(phs().labelOf(in)).contains("Sloty"));

        server.dispatchCommand(player, "kasyno placeholder napis Stół VIP");
        assertEquals("Stół VIP", textOf(phs().labelOf(in)));
        assertEquals(1, labelsInWorld().size(), "stary napis zniknął, jest tylko nowy");
        assertEquals("Stół VIP", phs().customText(in));

        // własny napis zostaje w danych pola, więc przetrwa restart serwera
        var fresh = new PlaceholderManager(plugin);
        assertEquals("Stół VIP", fresh.customText(fresh.all().get(0)));
        assertEquals(phs().labelOf(in).getUniqueId(), fresh.labelOf(fresh.all().get(0)).getUniqueId());

        server.dispatchCommand(player, "kasyno placeholder napis <red>Czerwony<newline>drugi wiersz");
        assertEquals("Czerwonydrugi wiersz", textOf(phs().labelOf(in)).replace("\n", ""));

        server.dispatchCommand(player, "kasyno placeholder napis off");
        assertNull(phs().labelOf(in));
        assertEquals(0, labelsInWorld().size());
        rightClick(in, EquipmentSlot.HAND);
        assertEquals("SlotyGui", openWindow(), "bez napisu pole nadal otwiera grę");
        player.closeInventory();
        server.getScheduler().performTicks(2);

        server.dispatchCommand(player, "kasyno placeholder napis auto");
        assertTrue(textOf(phs().labelOf(in)).contains("Sloty"));
        assertNull(phs().customText(in));
        assertEquals(1, labelsInWorld().size());

        // bez gracza z uprawnieniami napisu nie da się zmienić
        player.setOp(false);
        server.dispatchCommand(player, "kasyno placeholder napis Cos innego");
        assertTrue(textOf(phs().labelOf(in)).contains("Sloty"));
    }

    /** Napis osierocony (pole zniknęło bez naszej wiedzy, np. po /kill) sprząta komenda removeall. */
    @Test
    void removeAllCleansOrphanedLabels() {
        player.setOp(true);
        Interaction a = phs().spawn(player.getLocation(), "hilo", 1f, 1f);
        phs().spawn(player.getLocation(), "miny", 1f, 1f);
        assertEquals(2, labelsInWorld().size());
        a.remove(); // jak /kill: napis zostaje
        assertEquals(2, labelsInWorld().size());
        server.dispatchCommand(player, "kasyno placeholder removeall potwierdz");
        assertEquals(0, phs().count());
        assertEquals(0, labelsInWorld().size(), "nie zostały żadne napisy");
    }

    @Test
    void tabCompletionSuggestsGamesForAdmins() {
        var cmd = server.getCommandMap().getCommand("kasyno");
        assertNotNull(cmd);
        List<String> sub = cmd.tabComplete(player, "kasyno", new String[]{"placeholder", "sp"});
        assertTrue(sub.isEmpty(), "zwykły gracz nie dostaje podpowiedzi komend administratora");

        player.setOp(true);
        assertTrue(cmd.tabComplete(player, "kasyno", new String[]{"pla"}).contains("placeholder"));
        assertTrue(cmd.tabComplete(player, "kasyno", new String[]{"placeholder", "sp"}).contains("spawn"));
        List<String> games = cmd.tabComplete(player, "kasyno", new String[]{"placeholder", "spawn", "black"});
        assertEquals(List.of("blackjack"), games);
        assertEquals(List.of("auto"), cmd.tabComplete(player, "kasyno", new String[]{"placeholder", "napis", "au"}));
        assertTrue(cmd.tabComplete(player, "kasyno", new String[]{"placeholder", "na"}).contains("napis"));
    }
}
