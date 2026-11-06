package pl.kasyno.prison;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.PlayerListener;
import pl.kasyno.core.PlayerData;
import pl.kasyno.games.Rooms;
import pl.kasyno.gui.Actions;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.GuiManager;
import pl.kasyno.gui.Screens;
import pl.kasyno.gui.T;
import pl.kasyno.gui.screens.QuizGui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Więzienie na atrapie serwera: losowa cela, ograniczenia, minigierki, wyjście oraz podpięcie pod przestępstwo, okradanie i napad. */
class PrisonTest {
    static {
        System.setProperty("kasyno.test", "1");
    }

    ServerMock server;
    KasynoPlugin plugin;
    World world;
    PlayerMock player;
    UUID id;
    PrisonService prison;

    @BeforeEach
    void setUp() {
        GuiManager.clickGapMs = 0;
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(KasynoPlugin.class);
        prison = plugin.prison();
        player = server.addPlayer();
        id = player.getUniqueId();
        PlayerListener.prepare(plugin, player);
        player.setGameMode(GameMode.SURVIVAL);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void ticks(int n) {
        server.getScheduler().performTicks(n);
    }

    private void arrestNow() {
        plugin.getConfig().set("prison.arrest-delay-ticks", 0);
    }

    private PlayerMock newPlayer(long wallet) {
        PlayerMock p = server.addPlayer();
        PlayerListener.prepare(plugin, p);
        plugin.eco().pd(p.getUniqueId()).wallet = wallet;
        p.setGameMode(GameMode.SURVIVAL);
        return p;
    }

    private static String name(Inventory inv, int slot) {
        ItemStack it = inv.getItem(slot);
        if (it == null || !it.hasItemMeta()) return "";
        Component n = it.getItemMeta().displayName();
        return n == null ? "" : T.plain(n);
    }

    private Inventory top() {
        return player.getOpenInventory().getTopInventory();
    }

    private void click(int slot) {
        player.simulateInventoryClick(slot);
        ticks(1);
    }

    private boolean inSomeCell(Player p) {
        Location at = p.getLocation();
        for (PrisonRules.Spot s : prison.spots()) {
            if (Math.abs(at.getX() - s.x()) < 0.01 && Math.abs(at.getY() - s.y()) < 0.01 && Math.abs(at.getZ() - s.z()) < 0.01) return true;
        }
        return false;
    }

    // ---------- cele i wyrok ----------

    @Test
    void defaultConfigHasTheThreeCellsFromTheOwner() {
        List<PrisonRules.Spot> spots = prison.spots();
        assertEquals(3, spots.size());
        assertEquals(new PrisonRules.Spot(-78.5, 92, 65.5), spots.get(0));
        assertEquals(new PrisonRules.Spot(-81.5, 92, 65.5), spots.get(1));
        assertEquals(new PrisonRules.Spot(-84.5, 92, 65.5), spots.get(2), "trzecia cela: dopasowana do kroku co 3 bloki, bo X nie został podany");
        assertTrue(prison.enabled());
    }

    @Test
    void jailTakesThePlayerToARandomCellAfterTheArrestDelay() {
        Set<Integer> cells = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            player.teleport(new Location(world, 0, 64, 0));
            assertTrue(prison.jail(id, PrisonRules.Reason.CRIME));
            assertTrue(prison.isJailed(id));
            assertFalse(prison.restricted(id), "przez chwilę oglądasz wynik wpadki, jeszcze bez ograniczeń");
            assertEquals(GameMode.SURVIVAL, player.getGameMode());
            ticks(81);
            assertTrue(prison.restricted(id));
            assertTrue(inSomeCell(player), "gracz stoi w którejś celi");
            assertEquals(GameMode.ADVENTURE, player.getGameMode());
            cells.add(prison.status(id).cell());
            prison.release(id, "test");
        }
        assertEquals(Set.of(0, 1, 2), cells, "losowanie korzysta ze wszystkich trzech cel");
    }

    @Test
    void sentenceSizeDependsOnTheReasonAndComesFromConfig() {
        prison.jail(id, PrisonRules.Reason.HEIST);
        PrisonService.Status st = prison.status(id);
        assertEquals(PrisonRules.Reason.HEIST, st.reason());
        assertEquals(4, st.tasksLeft());
        assertEquals(4, st.tasksTotal());
        assertTrue(st.secondsLeft() > 11 * 60 && st.secondsLeft() <= 12 * 60);
        prison.release(id, null);
        plugin.getConfig().set("prison.sentences.okradanie.tasks", 1);
        plugin.getConfig().set("prison.sentences.okradanie.minutes", 2);
        prison.jail(id, PrisonRules.Reason.ROB);
        assertEquals(1, prison.status(id).tasksLeft());
        assertTrue(prison.status(id).secondsLeft() <= 120);
    }

    @Test
    void theSentenceIsSavedWithThePlayer() {
        prison.jail(id, PrisonRules.Reason.CRIME);
        PlayerData d = plugin.eco().pd(id);
        assertTrue(d.jailUntil > plugin.eco().now());
        assertEquals(3, d.jailTasks);
        assertEquals("przestepstwo", d.jailReason);
        com.google.gson.Gson gson = new com.google.gson.Gson();
        PlayerData back = gson.fromJson(gson.toJson(d), PlayerData.class);
        assertEquals(d.jailUntil, back.jailUntil);
        assertEquals(d.jailCell, back.jailCell);
        PlayerData old = gson.fromJson("{\"name\":\"Stary\",\"wallet\":5}", PlayerData.class);
        assertEquals(0, old.jailUntil, "stary zapis bez więzienia wczytuje się jako wolny gracz");
    }

    @Test
    void anAlreadyJailedPlayerKeepsTheirSentence() {
        prison.jail(id, PrisonRules.Reason.CRIME);
        PlayerData d = plugin.eco().pd(id);
        long until = d.jailUntil;
        int cell = d.jailCell;
        assertTrue(prison.jail(id, PrisonRules.Reason.HEIST));
        assertEquals(until, d.jailUntil);
        assertEquals(cell, d.jailCell);
        assertEquals("przestepstwo", d.jailReason);
    }

    @Test
    void bypassPermissionAndDisabledPrisonMeanNoJail() {
        player.addAttachment(plugin, PrisonService.BYPASS, true);
        assertFalse(prison.canJail(player));
        assertFalse(prison.jail(id, PrisonRules.Reason.CRIME));
        assertFalse(prison.isJailed(id));
        PlayerMock other = newPlayer(100);
        plugin.getConfig().set("prison.enabled", false);
        assertFalse(prison.enabled());
        assertFalse(prison.canJail(other));
        assertFalse(prison.jail(other.getUniqueId(), PrisonRules.Reason.CRIME));
        plugin.getConfig().set("prison.enabled", true);
        plugin.getConfig().set("prison.cells", List.of());
        assertFalse(prison.enabled(), "bez żadnej celi więzienie nie działa");
    }

    // ---------- ograniczenia ----------

    @Test
    void aPrisonerCannotOpenCasinoWindowsOrUseCasinoCommands() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        assertTrue(prison.restricted(id));
        player.closeInventory();
        assertTrue(Screens.open(plugin, player, "sklep"));
        ticks(1);
        assertNull(plugin.guis().current(player), "okno sklepu się nie otwiera");

        PlayerCommandPreprocessEvent shop = new PlayerCommandPreprocessEvent(player, "/sklep");
        server.getPluginManager().callEvent(shop);
        assertTrue(shop.isCancelled());
        PlayerCommandPreprocessEvent list = new PlayerCommandPreprocessEvent(player, "/kasyno wiezienie");
        server.getPluginManager().callEvent(list);
        assertFalse(list.isCancelled());
        PlayerCommandPreprocessEvent roulette = new PlayerCommandPreprocessEvent(player, "/kasyno ruletka");
        server.getPluginManager().callEvent(roulette);
        assertTrue(roulette.isCancelled());
    }

    @Test
    void commandsWorkNormallyForFreePlayers() {
        PlayerCommandPreprocessEvent shop = new PlayerCommandPreprocessEvent(player, "/sklep");
        server.getPluginManager().callEvent(shop);
        assertFalse(shop.isCancelled());
        assertTrue(Screens.open(plugin, player, "sklep"));
        ticks(1);
        assertNotNull(plugin.guis().current(player));
    }

    @Test
    void thePlayerIsInvulnerableAndCannotTeleportOutWithAPearl() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        @SuppressWarnings("deprecation")
        EntityDamageEvent fall = new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL, 5.0);
        server.getPluginManager().callEvent(fall);
        assertTrue(fall.isCancelled());
        PlayerTeleportEvent pearl = new PlayerTeleportEvent(player, player.getLocation(), new Location(world, 50, 64, 50), PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        server.getPluginManager().callEvent(pearl);
        assertTrue(pearl.isCancelled());
        PlayerMock free = newPlayer(0);
        @SuppressWarnings("deprecation")
        EntityDamageEvent hit = new EntityDamageEvent(free, EntityDamageEvent.DamageCause.FALL, 5.0);
        server.getPluginManager().callEvent(hit);
        assertFalse(hit.isCancelled(), "wolny gracz dostaje obrażenia jak zwykle");
    }

    @Test
    void walkingOutOfTheCellSendsThePlayerBack() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        assertTrue(inSomeCell(player));
        player.teleport(new Location(world, 40, 70, 40));
        ticks(21);
        assertTrue(inSomeCell(player), "strażnik zawraca do celi");
        PrisonRules.Spot own = prison.spots().get(prison.status(id).cell());
        assertEquals(own.x(), player.getLocation().getX(), 0.01);
        // krok w obrębie celi nie wywołuje zawracania
        player.teleport(new Location(world, own.x() + 1, own.y(), own.z()));
        ticks(21);
        assertEquals(own.x() + 1, player.getLocation().getX(), 0.01);
    }

    @Test
    void respawnAfterDeathLandsInTheCell() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        PlayerRespawnEvent respawn = new PlayerRespawnEvent(player, new Location(world, 0, 64, 0), false, false, false, PlayerRespawnEvent.RespawnReason.DEATH);
        server.getPluginManager().callEvent(respawn);
        PrisonRules.Spot own = prison.spots().get(prison.status(id).cell());
        assertEquals(own.x(), respawn.getRespawnLocation().getX(), 0.01);
    }

    // ---------- wyjście ----------

    @Test
    void completingAllTasksReleasesAndRestoresTheGameMode() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.ROB);
        assertEquals(GameMode.ADVENTURE, player.getGameMode());
        assertEquals(1, prison.completeTask(id));
        assertTrue(prison.isJailed(id));
        assertEquals(0, prison.completeTask(id));
        assertFalse(prison.isJailed(id));
        assertEquals(GameMode.SURVIVAL, player.getGameMode());
        assertEquals(world.getSpawnLocation().getX(), player.getLocation().getX(), 0.01, "wychodzi na spawn świata, gdy nie ustawiono wyjścia");
        assertEquals(-1, prison.completeTask(id), "wolny gracz nie zalicza zadań");
        assertEquals(0, plugin.eco().pd(id).jailUntil);
    }

    @Test
    void theExitComesFromConfig() {
        plugin.getConfig().set("prison.exit", "100 70 -20");
        arrestNow();
        prison.jail(id, PrisonRules.Reason.ROB);
        prison.release(id, "test");
        assertEquals(100.5, player.getLocation().getX(), 0.01);
        assertEquals(70, player.getLocation().getY(), 0.01);
        assertEquals(-19.5, player.getLocation().getZ(), 0.01);
    }

    @Test
    void theTimeoutReleasesThePlayerByItself() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        plugin.eco().pd(id).jailUntil = plugin.eco().now() - 1;
        ticks(21);
        assertFalse(prison.isJailed(id));
        assertEquals(0, plugin.eco().pd(id).jailUntil);
        assertEquals(GameMode.SURVIVAL, player.getGameMode());
    }

    @Test
    void anAdminCanReleaseSomeoneWhoIsOffline() {
        prison.jail(id, PrisonRules.Reason.CRIME);
        PlayerData d = plugin.eco().pd(id);
        player.disconnect();
        prison.release(id, "admin");
        assertFalse(prison.isJailed(id));
        assertTrue(d.jailUntil > 0, "wyjście dopełnia się przy następnym wejściu");
        assertEquals(0, d.jailTasks);
    }

    @Test
    void joiningWithASentenceInProgressGoesBackToTheCell() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        prison.onQuit(id);
        player.teleport(new Location(world, 30, 64, 30));
        prison.onJoin(player);
        ticks(6);
        assertTrue(inSomeCell(player));
        assertEquals(GameMode.ADVENTURE, player.getGameMode());
    }

    @Test
    void joiningAfterTheSentenceEndedLetsYouOut() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        plugin.eco().pd(id).jailUntil = 1;
        prison.onJoin(player);
        ticks(6);
        assertFalse(prison.isJailed(id));
        assertEquals(0, plugin.eco().pd(id).jailUntil);
        assertEquals(GameMode.SURVIVAL, player.getGameMode());
    }

    // ---------- okna i minigierki ----------

    @Test
    void theTaskListOpensForPrisonersAndShowsFourGames() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        ticks(41);
        Gui g = plugin.guis().current(player);
        assertTrue(g instanceof PrisonGui, "lista zadań otwiera się sama chwilę po wejściu do celi");
        assertTrue(name(top(), 19).contains("Podkop"));
        for (int slot : new int[]{19, 21, 23, 25}) assertFalse(name(top(), slot).isEmpty(), "zadanie w slocie " + slot);
        assertTrue(name(top(), 4).contains("Przestępstwo"));
    }

    @Test
    void theTaskListForAFreePlayerExplainsAndOffersNoGames() {
        new PrisonGui(plugin, player).open();
        ticks(1);
        assertTrue(name(top(), 22).contains("wolny"));
        assertTrue(name(top(), 19).isBlank());
    }

    @Test
    void theGuardNpcTypeOpensTheTaskList() {
        assertTrue(Screens.exists("wiezienie"));
        assertTrue(Screens.allKeys().contains("wiezienie"));
        assertFalse(Screens.keys().contains("wiezienie"), "bez własnego tła w paczce");
        assertEquals("Strażnik więzienny", plugin.npcs().labelOf("wiezienie"));
        assertTrue(Screens.open(plugin, player, "wiezienie"));
        ticks(1);
        assertTrue(plugin.guis().current(player) instanceof PrisonGui);
    }

    @Test
    void mashDigsThroughAfterEnoughClicksAndCountsOneTask() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        player.closeInventory();
        long[] t = {1_000};
        new MashGui(plugin, player, () -> t[0]).open();
        ticks(1);
        for (int i = 0; i < MashGui.NEEDED - 1; i++) click(40);
        assertEquals(3, prison.status(id).tasksLeft());
        click(40);
        assertEquals(2, prison.status(id).tasksLeft());
        click(40);
        assertEquals(2, prison.status(id).tasksLeft(), "zadanie liczy się raz");
    }

    @Test
    void mashFailsWhenTheTimeRunsOutAndCanBeRetried() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        player.closeInventory();
        long[] t = {1_000};
        new MashGui(plugin, player, () -> t[0]).open();
        ticks(1);
        click(40);
        t[0] += MashGui.WINDOW_MS + 100;
        ticks(11);
        assertTrue(name(top(), 13).contains("Strażnik usłyszał"));
        assertEquals(3, prison.status(id).tasksLeft(), "porażka nie zalicza zadania");
        click(40);
        assertTrue(plugin.guis().current(player) instanceof MashGui, "przycisk zaczyna od nowa");
    }

    @Test
    void reflexIsWonByHittingTheLitKeySixTimes() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        player.closeInventory();
        ReflexGui gui = new ReflexGui(plugin, player);
        gui.open();
        ticks(21);
        for (int i = 0; i < ReflexGui.HITS; i++) {
            int target = gui.round().target();
            assertTrue(name(top(), ReflexGui.slotOf(target)).contains("TERAZ"), "świecący klucz jest w polu celu");
            click(ReflexGui.slotOf(target));
            ticks(9);
        }
        assertEquals(ReflexRound.State.WON, gui.round().state());
        assertEquals(2, prison.status(id).tasksLeft());
    }

    @Test
    void reflexLosesWhenTheKeysKeepGoingOut() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        player.closeInventory();
        ReflexGui gui = new ReflexGui(plugin, player);
        gui.open();
        ticks(21);
        ticks(200); // nikt nie klika
        assertEquals(ReflexRound.State.LOST, gui.round().state());
        assertEquals(3, prison.status(id).tasksLeft());
        assertTrue(name(top(), 40).contains("Za wolno"));
    }

    @Test
    void reflexIgnoresClicksWhileNothingIsLit() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        player.closeInventory();
        ReflexGui gui = new ReflexGui(plugin, player);
        gui.open();
        ticks(1); // jeszcze nic nie świeci
        click(ReflexGui.slotOf(0));
        assertEquals(0, gui.round().misses());
    }

    @Test
    void simonIsWonByRepeatingTheSequence() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        player.closeInventory();
        SimonGui gui = new SimonGui(plugin, player);
        gui.open();
        ticks(24 + SimonGui.LENGTH * 18 + 10);
        assertTrue(name(top(), 22).contains("Teraz Ty"));
        int[] slots = {20, 24, 38, 42};
        for (int color : gui.round().sequence()) {
            click(slots[color]);
            ticks(1);
        }
        assertEquals(2, prison.status(id).tasksLeft());
        assertTrue(name(top(), 22).contains("Zamek otwarty"));
    }

    @Test
    void simonIgnoresClicksWhileTheGuardIsShowingTheSequence() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        player.closeInventory();
        SimonGui gui = new SimonGui(plugin, player);
        gui.open();
        ticks(5);
        click(20);
        assertEquals(0, gui.round().entered());
        assertEquals(3, prison.status(id).tasksLeft());
    }

    @Test
    void simonWrongColorEndsTheTryWithoutCountingTheTask() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.CRIME);
        player.closeInventory();
        SimonGui gui = new SimonGui(plugin, player);
        gui.open();
        ticks(24 + SimonGui.LENGTH * 18 + 10);
        int wrong = (gui.round().sequence().get(0) + 1) % 4;
        click(new int[]{20, 24, 38, 42}[wrong]);
        assertTrue(name(top(), 22).contains("Pomyłka"));
        assertEquals(3, prison.status(id).tasksLeft());
    }

    @Test
    void theExamCountsAsATaskOnlyWhenAnsweredWell() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.HEIST);
        int left = prison.status(id).tasksLeft();
        boolean passed = false;
        for (int attempt = 0; attempt < 80 && !passed && prison.isJailed(id); attempt++) {
            player.closeInventory();
            QuizGui.exam(plugin, player).open();
            ticks(1);
            click(new int[]{28, 30, 32, 34}[attempt % 4]);
            int now = prison.isJailed(id) ? prison.status(id).tasksLeft() : 0;
            if (now < left) passed = true;
        }
        assertTrue(passed, "w końcu któraś odpowiedź jest dobra");
        assertEquals(left - 1, prison.status(id).tasksLeft());
    }

    @Test
    void theExamPaysNoMoney() {
        arrestNow();
        prison.jail(id, PrisonRules.Reason.HEIST);
        plugin.eco().pd(id).wallet = 1000;
        for (int attempt = 0; attempt < 12; attempt++) {
            player.closeInventory();
            QuizGui.exam(plugin, player).open();
            ticks(1);
            click(new int[]{28, 30, 32, 34}[attempt % 4]);
        }
        assertEquals(1000, plugin.eco().wallet(id), "egzamin w więzieniu nie daje pieniędzy");
    }

    // ---------- podpięcie pod przestępstwa ----------

    @Test
    void aFailedCrimeSendsYouToPrisonInsteadOfAFine() {
        plugin.eco().pd(id).wallet = 5000;
        boolean jailed = false;
        for (int i = 0; i < 80 && !jailed; i++) {
            plugin.eco().clearCooldown(id, "crime");
            long before = plugin.eco().wallet(id);
            List<String> lines = Actions.claim(plugin, player, "przestepstwo");
            if (prison.isJailed(id)) {
                jailed = true;
                assertEquals(before, plugin.eco().wallet(id), "w więzieniu nie ma grzywny");
                assertTrue(lines.get(0).contains("Wpadłeś"));
                assertEquals(PrisonRules.Reason.CRIME, prison.status(id).reason());
            }
        }
        assertTrue(jailed, "przy szansie 50% w 80 próbach któraś musi się nie udać");
        ticks(81);
        assertTrue(prison.restricted(id));
        assertTrue(inSomeCell(player));
    }

    @Test
    void aFailedCrimeStillCostsAFineWhenThePrisonIsOff() {
        plugin.getConfig().set("prison.enabled", false);
        plugin.eco().pd(id).wallet = 100_000;
        boolean fined = false;
        for (int i = 0; i < 80 && !fined; i++) {
            plugin.eco().clearCooldown(id, "crime");
            List<String> lines = Actions.claim(plugin, player, "przestepstwo");
            if (lines.get(0).contains("Wpadka")) fined = true;
        }
        assertTrue(fined, "bez więzienia zostaje dawna grzywna");
        assertFalse(prison.isJailed(id));
    }

    @Test
    void onlyAFailedCrimeJails() {
        plugin.eco().pd(id).wallet = 100;
        boolean sawSuccess = false;
        boolean sawFailure = false;
        for (int i = 0; i < 60; i++) {
            plugin.eco().clearCooldown(id, "crime");
            List<String> lines = Actions.claim(plugin, player, "przestepstwo");
            boolean success = lines.get(0).contains("Udało się");
            assertEquals(!success, prison.isJailed(id), lines.get(0));
            sawSuccess |= success;
            sawFailure |= !success;
            if (prison.isJailed(id)) prison.release(id, null);
        }
        assertTrue(sawSuccess && sawFailure);
    }

    @Test
    void aCaughtRobberyPaysAndSendsTheThiefToPrison() {
        PlayerMock victim = newPlayer(1_000_000);
        boolean jailed = false;
        for (int i = 0; i < 100 && !jailed; i++) {
            plugin.eco().pd(id).wallet = 100_000;
            plugin.eco().pd(victim.getUniqueId()).wallet = 1_000_000;
            plugin.eco().clearCooldown(id, "rob");
            plugin.eco().clearCooldown(victim.getUniqueId(), "robbed");
            long before = plugin.eco().wallet(victim.getUniqueId());
            List<String> lines = Actions.rob(plugin, player, victim);
            if (prison.isJailed(id)) {
                jailed = true;
                assertEquals(PrisonRules.Reason.ROB, prison.status(id).reason());
                assertTrue(plugin.eco().wallet(victim.getUniqueId()) > before, "ofiara dostaje odszkodowanie");
                assertTrue(lines.stream().anyMatch(l -> l.contains("więzienia")));
            }
        }
        assertTrue(jailed, "przy szansie 45% w 100 próbach któraś musi się nie udać");
    }

    @Test
    void caughtHeistCrewMembersEndUpInPrisonButTheEscapeesDoNot() {
        int jailedTotal = 0;
        for (int round = 0; round < 12 && jailedTotal == 0; round++) {
            List<PlayerMock> crew = new ArrayList<>();
            for (int i = 0; i < 5; i++) crew.add(newPlayer(10_000));
            assertNull(plugin.rooms().start(crew.get(0), Rooms.Kind.HEIST, 500));
            for (int i = 1; i < crew.size(); i++) assertNull(plugin.rooms().join(crew.get(i), Rooms.Kind.HEIST));
            ticks(20 * 46);
            Rooms.Lobby l = plugin.rooms().lobby(Rooms.Kind.HEIST);
            assertNotNull(l);
            assertTrue(l.resolved);
            ticks(20 * 36);
            assertTrue(l.sceneDone);
            for (UUID u : l.heist.caught()) {
                assertTrue(prison.isJailed(u), "złapany idzie za kratki");
                assertEquals(PrisonRules.Reason.HEIST, prison.status(u).reason());
                jailedTotal++;
            }
            for (UUID u : l.heist.survivors()) assertFalse(prison.isJailed(u), "kto uciekł, jest wolny");
        }
        assertTrue(jailedTotal > 0, "w kilku napadach ktoś musiał wpaść");
    }

    // ---------- komendy administratora ----------

    @Test
    void adminCommandsJailReleaseAndShowCells() {
        PlayerMock admin = server.addPlayer();
        admin.addAttachment(plugin, "kasyno.admin", true);
        PlayerMock victim = newPlayer(0);
        admin.performCommand("kasyno wiezienie wsadz " + victim.getName() + " napad");
        assertTrue(prison.isJailed(victim.getUniqueId()));
        assertEquals(PrisonRules.Reason.HEIST, prison.status(victim.getUniqueId()).reason());
        admin.performCommand("kasyno wiezienie uwolnij " + victim.getName());
        assertFalse(prison.isJailed(victim.getUniqueId()));
        admin.performCommand("kasyno wiezienie cele");
        String all = drain(admin);
        assertTrue(all.contains("-79 92 65") && all.contains("-85 92 65"), all);
    }

    @Test
    void normalPlayersCannotUseTheAdminPartOfTheCommand() {
        PlayerMock victim = newPlayer(0);
        player.performCommand("kasyno wiezienie wsadz " + victim.getName());
        assertFalse(prison.isJailed(victim.getUniqueId()));
    }

    @Test
    void adminCanMoveACellAndSetTheExit() {
        PlayerMock admin = server.addPlayer();
        admin.addAttachment(plugin, "kasyno.admin", true);
        admin.teleport(new Location(world, 10.3, 80, 20.7));
        admin.performCommand("kasyno wiezienie cela 2 ustaw");
        assertEquals(new PrisonRules.Spot(10.5, 80, 20.5), prison.spots().get(1));
        admin.performCommand("kasyno wiezienie cela 4 ustaw");
        assertEquals(4, prison.cellCount(), "kolejny numer dopisuje nową celę");
        admin.performCommand("kasyno wiezienie cela 9 ustaw");
        assertEquals(4, prison.cellCount(), "zły numer nic nie psuje");
        admin.teleport(new Location(world, -3, 66, 8));
        admin.performCommand("kasyno wiezienie wyjscie ustaw");
        assertEquals(-2.5, prison.exitLocation().getX(), 0.01);
        assertEquals(66, prison.exitLocation().getY(), 0.01);
    }

    private static String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) sb.append(m).append('\n');
        return sb.toString();
    }
}
