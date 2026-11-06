package pl.kasyno;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Mannequin;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kasyno.games.Rooms;
import pl.kasyno.gui.GuiManager;
import pl.kasyno.show.BukkitBackend;
import pl.kasyno.show.Stage;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pojedynek i jackpot na arenie w prawdziwym świecie: arena stawiana komendą, figury ze skórkami graczy, pieniądze rozliczone przed
 * sceną, okno jako zapas i sprzątanie po pokazie.
 */
class ArenaIntegrationTest {
    static {
        System.setProperty("kasyno.test", "1");
    }

    ServerMock server;
    KasynoPlugin plugin;
    World world;
    PlayerMock admin;

    @BeforeEach
    void setUp() {
        GuiManager.clickGapMs = 0;
        server = MockBukkit.mock();
        plugin = MockBukkit.load(KasynoPlugin.class);
        world = server.addSimpleWorld("world");
        admin = server.addPlayer();
        PlayerListener.prepare(plugin, admin);
        admin.setOp(true);
        admin.teleport(new Location(world, 0, 64, 0));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock newPlayer() {
        PlayerMock p = server.addPlayer();
        PlayerListener.prepare(plugin, p);
        plugin.eco().pd(p.getUniqueId()).wallet = 10_000;
        p.teleport(new Location(world, 8, 64, -5));
        return p;
    }

    private void ticks(int n) {
        server.getScheduler().performTicks(n);
    }

    private int mannequins() {
        return world.getEntitiesByClass(Mannequin.class).size();
    }

    /** Fajerwerki pokazu (z naszym znacznikiem); fajerwerk z podsumowania wygranej (Fx.jackpot) nie jest częścią sceny. */
    private int showFireworks() {
        return (int) world.getEntitiesByClass(Firework.class).stream().filter(f -> f.getPersistentDataContainer().has(BukkitBackend.TAG, PersistentDataType.STRING)).count();
    }

    private long wallets(PlayerMock... ps) {
        long total = 0;
        for (PlayerMock p : ps) total += plugin.eco().wallet(p.getUniqueId());
        return total;
    }

    private List<String> messages(PlayerMock p) {
        List<String> out = new java.util.ArrayList<>();
        String m;
        while ((m = p.nextMessage()) != null) out.add(m);
        return out;
    }

    private boolean anyContains(List<String> msgs, String part) {
        return msgs.stream().anyMatch(m -> m.contains(part));
    }

    // ---------- budowa areny ----------

    @Test
    void arenaBuildsFromOneCommandAndSceneBecomesReady() {
        assertFalse(plugin.show().arenaReady());
        server.dispatchCommand(admin, "kasyno arena zbuduj");
        assertTrue(plugin.show().arenaReady(), "komplet punktów areny");
        assertFalse(plugin.show().ready(), "arena nie jest bankiem: napad nadal bez sceny");
        assertTrue(plugin.show().stage().arenaMissing().isEmpty());
        // bryła areny stoi tam, gdzie obiecuje prefabrykat
        assertEquals(Material.GOLD_BLOCK, world.getBlockAt(15, 63, 15).getType(), "złoty środek");
        assertEquals(Material.ORANGE_TERRACOTTA, world.getBlockAt(11, 63, 15).getType(), "stanowisko A");
        assertEquals(Material.ORANGE_TERRACOTTA, world.getBlockAt(19, 63, 15).getType(), "stanowisko B");
        assertEquals(Material.SAND, world.getBlockAt(15, 63, 12).getType(), "piasek na placu");
        assertEquals(Material.STONE_BRICK_SLAB, world.getBlockAt(16, 64, 26).getType(), "murek wokół placu");
        assertEquals(Material.DARK_OAK_PLANKS, world.getBlockAt(15, 66, 29).getType(), "ostatni rząd trybuny");
        assertEquals(Material.AIR, world.getBlockAt(15, 67, 29).getType(), "nad trybuną miejsce dla widza");
        // punkty
        Stage st = plugin.show().stage();
        assertEquals(new Stage.P(11.5, 64, 15.5), st.get(Stage.ARENA_A));
        assertEquals(new Stage.P(19.5, 64, 15.5), st.get(Stage.ARENA_B));
        assertEquals(new Stage.P(15.5, 64, 15.5), st.get(Stage.ARENA_CENTER));
        assertEquals(180f, st.arenaViewYaw());
        // plik sceny przeżywa restart
        File f = new File(plugin.getDataFolder(), "stage.yml");
        assertTrue(f.isFile());
        Stage back = Stage.load(f);
        assertEquals("world", back.arenaWorld());
        assertTrue(back.arenaComplete());
    }

    @Test
    void bankAndArenaCoexistAndAreRemovedSeparately() {
        server.dispatchCommand(admin, "kasyno bank zbuduj");
        admin.teleport(new Location(world, 100, 64, 100));
        server.dispatchCommand(admin, "kasyno arena zbuduj");
        assertTrue(plugin.show().ready(), "bank stoi");
        assertTrue(plugin.show().arenaReady(), "arena stoi");
        assertEquals(new Stage.P(111.5, 64, 115.5), plugin.show().stage().get(Stage.ARENA_A));
        server.dispatchCommand(admin, "kasyno arena usun");
        assertFalse(plugin.show().arenaReady());
        assertTrue(plugin.show().ready(), "usunięcie areny nie rusza banku");
        server.dispatchCommand(admin, "kasyno arena zbuduj");
        server.dispatchCommand(admin, "kasyno bank usun");
        assertFalse(plugin.show().ready());
        assertTrue(plugin.show().arenaReady(), "usunięcie banku nie rusza areny");
        // budowa banku po arenie też jej nie kasuje
        admin.teleport(new Location(world, 300, 64, 300));
        server.dispatchCommand(admin, "kasyno bank zbuduj");
        assertTrue(plugin.show().ready() && plugin.show().arenaReady());
    }

    @Test
    void customArenaPointsFromCommandsAndOnlyAdminsBuild() {
        PlayerMock p = newPlayer();
        server.dispatchCommand(p, "kasyno arena zbuduj");
        assertFalse(plugin.show().arenaReady(), "zwykły gracz nie stawia areny");
        server.dispatchCommand(p, "kasyno arena punkt a");
        assertNull(plugin.show().stage().get(Stage.ARENA_A));
        server.dispatchCommand(p, "kasyno arena tp");
        assertTrue(messages(p).stream().anyMatch(m -> m.contains("nie jest jeszcze ustawiona")));

        admin.teleport(new Location(world, 10, 64, 10, 0, 0));
        server.dispatchCommand(admin, "kasyno arena punkt a");
        admin.teleport(new Location(world, 20, 64, 10, 0, 0));
        server.dispatchCommand(admin, "kasyno arena punkt b");
        admin.teleport(new Location(world, 15, 64, 10, 0, 0));
        server.dispatchCommand(admin, "kasyno arena punkt center");
        assertFalse(plugin.show().arenaReady(), "brakuje jeszcze trybuny");
        admin.teleport(new Location(world, 15, 66, 25, 180, 0));
        server.dispatchCommand(admin, "kasyno arena punkt arena-view");
        assertTrue(plugin.show().arenaReady());
        assertEquals(180f, plugin.show().stage().arenaViewYaw());

        p.teleport(new Location(world, 100, 64, 100));
        server.dispatchCommand(p, "kasyno arena tp");
        assertEquals(15.0, p.getLocation().getX(), 0.01);
        assertEquals(25.0, p.getLocation().getZ(), 0.01);
    }

    // ---------- pojedynek ----------

    @Test
    void worldDuelPlaysOnTheArenaSettlesFirstAndCleansUp() {
        server.dispatchCommand(admin, "kasyno arena zbuduj");
        PlayerMock a = newPlayer();
        PlayerMock b = newPlayer();
        PlayerMock watcher = newPlayer();
        messages(a);
        messages(b);
        messages(watcher);
        assertNull(plugin.rooms().challenge(a, b, 1_000));
        assertNull(plugin.rooms().accept(b));

        // pieniądze rozliczone od razu, a pojedynek idzie w świecie, nie w oknie
        long ma = plugin.eco().wallet(a.getUniqueId());
        long mb = plugin.eco().wallet(b.getUniqueId());
        assertTrue(plugin.show().running());
        assertTrue(plugin.show().arenaBusy());
        ticks(2);
        assertNull(plugin.guis().current(a), "okno pojedynku nie otwiera się, gdy arena stoi w świecie");
        assertNull(plugin.guis().current(b));
        // zaproszenie: pojedynkujący się i widz dostają link
        for (PlayerMock p : List.of(a, b, watcher)) assertTrue(anyContains(messages(p), "ZOBACZ"), "link [ZOBACZ NA ŻYWO]");

        int maxFigures = 0;
        int guard = 0;
        boolean earlyResult = false;
        while (plugin.show().running() && guard++ < 6_000) {
            ticks(5);
            maxFigures = Math.max(maxFigures, mannequins());
            if (guard * 5 < 200) {
                for (PlayerMock p : List.of(a, b)) if (anyContains(messages(p), "pojedynek")) earlyResult = true;
            }
            assertEquals(ma + mb, wallets(a, b), "scena nie rusza pieniędzy");
        }
        assertFalse(plugin.show().running());
        assertFalse(earlyResult, "wynik na czacie dopiero po scenie");
        assertEquals(2, maxFigures, "dwie figury na arenie");
        assertEquals(0, mannequins(), "po pokazie nie zostaje żadna figura");
        assertEquals(0, showFireworks());
        assertEquals(ma, plugin.eco().wallet(a.getUniqueId()));
        assertEquals(mb, plugin.eco().wallet(b.getUniqueId()));

        // podsumowanie po scenie: jeden wygrywa, drugi przegrywa
        List<String> ra = messages(a);
        List<String> rb = messages(b);
        boolean aWon = anyContains(ra, "Wygrywasz pojedynek");
        boolean bWon = anyContains(rb, "Wygrywasz pojedynek");
        assertTrue(aWon ^ bWon, "dokładnie jeden zwycięzca");
        assertTrue(anyContains(aWon ? rb : ra, "Przegrywasz pojedynek"));
        assertFalse(plugin.show().arenaBusy());
    }

    @Test
    void duelFallsBackToTheWindowWithoutAnArena() {
        PlayerMock a = newPlayer();
        PlayerMock b = newPlayer();
        assertNull(plugin.rooms().challenge(a, b, 1_000));
        assertNull(plugin.rooms().accept(b));
        assertFalse(plugin.show().running());
        ticks(20);
        assertEquals("DuelGui", plugin.guis().current(a).getClass().getSimpleName());
        assertEquals("DuelGui", plugin.guis().current(b).getClass().getSimpleName());
        assertEquals(0, mannequins());
        ticks(20 * 12);
        List<String> all = messages(a);
        all.addAll(messages(b));
        assertTrue(anyContains(all, "Wygrywasz pojedynek") && anyContains(all, "Przegrywasz pojedynek"));
    }

    @Test
    void duelFallsBackToTheWindowWhenOnlyTheBankStands() {
        server.dispatchCommand(admin, "kasyno bank zbuduj");
        PlayerMock a = newPlayer();
        PlayerMock b = newPlayer();
        assertNull(plugin.rooms().challenge(a, b, 1_000));
        assertNull(plugin.rooms().accept(b));
        assertFalse(plugin.show().running(), "sama scena banku nie odpala pojedynku");
        ticks(20);
        assertEquals("DuelGui", plugin.guis().current(a).getClass().getSimpleName());
    }

    @Test
    void secondDuelWhileTheArenaIsBusyUsesTheWindow() {
        server.dispatchCommand(admin, "kasyno arena zbuduj");
        PlayerMock a = newPlayer();
        PlayerMock b = newPlayer();
        PlayerMock c = newPlayer();
        PlayerMock d = newPlayer();
        assertNull(plugin.rooms().challenge(a, b, 1_000));
        assertNull(plugin.rooms().accept(b));
        ticks(100);
        assertTrue(plugin.show().arenaBusy());
        assertNull(plugin.rooms().challenge(c, d, 1_000));
        assertNull(plugin.rooms().accept(d));
        ticks(20);
        assertEquals("DuelGui", plugin.guis().current(c).getClass().getSimpleName(), "arena zajęta: drugi pojedynek w oknie");
        assertEquals("DuelGui", plugin.guis().current(d).getClass().getSimpleName());
        assertNull(plugin.guis().current(a));
        assertTrue(mannequins() <= 2);
        ticks(20 * 30);
        assertFalse(plugin.show().running());
        assertEquals(0, mannequins());
    }

    @Test
    void shutdownDuringDuelRemovesFiguresAndStillAnnounces() {
        server.dispatchCommand(admin, "kasyno arena zbuduj");
        PlayerMock a = newPlayer();
        PlayerMock b = newPlayer();
        assertNull(plugin.rooms().challenge(a, b, 1_000));
        assertNull(plugin.rooms().accept(b));
        ticks(150);
        assertEquals(2, mannequins());
        long before = wallets(a, b);
        messages(a);
        messages(b);
        plugin.show().disable();
        assertEquals(0, mannequins(), "wyłączenie usuwa figury");
        assertFalse(plugin.show().running());
        assertEquals(before, wallets(a, b));
        List<String> all = messages(a);
        all.addAll(messages(b));
        assertTrue(anyContains(all, "Wygrywasz pojedynek"), "wynik dociera do graczy mimo przerwania sceny");
    }

    // ---------- jackpot ----------

    @Test
    void worldJackpotCirclesThePlayersSettlesFirstAndCleansUp() {
        server.dispatchCommand(admin, "kasyno arena zbuduj");
        PlayerMock a = newPlayer();
        PlayerMock b = newPlayer();
        PlayerMock c = newPlayer();
        PlayerMock d = newPlayer();
        assertNull(plugin.rooms().start(a, Rooms.Kind.JACKPOT, 1_000));
        plugin.rooms().join(b, Rooms.Kind.JACKPOT);
        plugin.rooms().join(c, Rooms.Kind.JACKPOT);
        plugin.rooms().join(d, Rooms.Kind.JACKPOT);
        ticks(20 * 46); // losowanie
        Rooms.Lobby l = plugin.rooms().lobby(Rooms.Kind.JACKPOT);
        assertTrue(l.resolved);
        assertFalse(l.sceneDone, "scena dopiero się zaczyna");
        assertTrue(plugin.show().running(), "losowanie idzie na arenie");
        for (PlayerMock p : List.of(a, b, c, d)) assertNull(plugin.guis().current(p), "okno jackpota nie otwiera się, gdy arena stoi w świecie");

        // pieniądze rozliczone przed sceną i nietknięte w trakcie
        long total = wallets(a, b, c, d);
        assertEquals(4 * 9_000L + l.payout, total);
        for (PlayerMock p : List.of(a, b, c, d)) messages(p);

        int maxFigures = 0;
        int guard = 0;
        while (!l.sceneDone && guard++ < 6_000) {
            ticks(5);
            maxFigures = Math.max(maxFigures, mannequins());
            assertEquals(total, wallets(a, b, c, d), "scena nie rusza pieniędzy");
        }
        assertTrue(l.sceneDone, "pokaz zakończył scenę");
        assertEquals(4, maxFigures, "wszyscy uczestnicy jako figury");
        assertEquals(l.spin.length - 1, l.step, "ostatnia klatka stoi na zwycięzcy");
        ticks(5);
        assertFalse(plugin.show().running());
        assertEquals(0, mannequins(), "po pokazie nie zostaje żadna figura");
        assertEquals(0, showFireworks(), "ani fajerwerk pokazu");
        assertEquals(total, wallets(a, b, c, d));

        // podsumowanie dla zwycięzcy i przegranych
        int winners = 0;
        int losers = 0;
        for (PlayerMock p : List.of(a, b, c, d)) {
            List<String> msgs = messages(p);
            if (anyContains(msgs, "WYGRYWASZ JACKPOT")) winners++;
            if (anyContains(msgs, "Jackpot wygrywa")) losers++;
            assertTrue(anyContains(msgs, "JACKPOT") || anyContains(msgs, "Jackpot"), "każdy dostał zaproszenie albo wynik");
        }
        assertEquals(1, winners);
        assertEquals(3, losers);
    }

    @Test
    void jackpotFallsBackToTheWindowWithoutAnArena() {
        server.dispatchCommand(admin, "kasyno bank zbuduj");
        PlayerMock a = newPlayer();
        PlayerMock b = newPlayer();
        assertNull(plugin.rooms().start(a, Rooms.Kind.JACKPOT, 1_000));
        plugin.rooms().join(b, Rooms.Kind.JACKPOT);
        ticks(20 * 46);
        assertFalse(plugin.show().running());
        assertNotNull(plugin.guis().current(a), "bez areny okno z głowami otwiera się samo");
        assertEquals("RoomGui", plugin.guis().current(a).getClass().getSimpleName());
        ticks(20 * 15);
        assertTrue(plugin.rooms().lobby(Rooms.Kind.JACKPOT).sceneDone);
        assertEquals(0, mannequins());
    }

    @Test
    void jackpotWhileTheArenaIsBusyUsesTheWindow() {
        server.dispatchCommand(admin, "kasyno arena zbuduj");
        PlayerMock a = newPlayer();
        PlayerMock b = newPlayer();
        assertNull(plugin.rooms().start(a, Rooms.Kind.JACKPOT, 1_000));
        plugin.rooms().join(b, Rooms.Kind.JACKPOT);
        ticks(20 * 42); // tuż przed losowaniem (lobby trwa 45 s) arenę zajmuje pokaz próbny
        server.dispatchCommand(admin, "kasyno arena test duel");
        assertTrue(plugin.show().arenaBusy());
        ticks(20 * 4);
        assertTrue(plugin.rooms().lobby(Rooms.Kind.JACKPOT).resolved);
        assertEquals("RoomGui", plugin.guis().current(a).getClass().getSimpleName(), "arena zajęta: losowanie w oknie");
        ticks(20 * 15);
        assertTrue(plugin.rooms().lobby(Rooms.Kind.JACKPOT).sceneDone);
    }

    // ---------- pokazy próbne, stop i sprzątanie ----------

    @Test
    void adminDemosRunWithoutMoneyAndLeaveNothing() {
        server.dispatchCommand(admin, "kasyno arena zbuduj");
        long before = plugin.eco().wallet(admin.getUniqueId());

        server.dispatchCommand(admin, "kasyno arena test duel");
        assertTrue(plugin.show().arenaBusy());
        // druga próba naraz jest odrzucana
        server.dispatchCommand(admin, "kasyno arena test jackpot 5");
        int max = 0;
        int guard = 0;
        while (plugin.show().running() && guard++ < 6_000) {
            ticks(5);
            max = Math.max(max, mannequins());
        }
        assertEquals(2, max, "pojedynek próbny: dwie figury");
        assertEquals(0, mannequins());

        server.dispatchCommand(admin, "kasyno arena test jackpot 5");
        assertTrue(plugin.show().arenaBusy());
        max = 0;
        guard = 0;
        while (plugin.show().running() && guard++ < 6_000) {
            ticks(5);
            max = Math.max(max, mannequins());
        }
        assertEquals(5, max, "jackpot próbny: pięć figur");
        assertEquals(0, mannequins());
        assertEquals(before, plugin.eco().wallet(admin.getUniqueId()), "pokazy próbne nie ruszają pieniędzy");

        // stop usuwa figury w trakcie
        server.dispatchCommand(admin, "kasyno arena test jackpot 8");
        ticks(100);
        assertEquals(8, mannequins());
        server.dispatchCommand(admin, "kasyno arena stop");
        assertFalse(plugin.show().running());
        assertEquals(0, mannequins(), "stop usuwa figury");
    }

    @Test
    void demoWithoutAnArenaExplainsWhatIsMissing() {
        server.dispatchCommand(admin, "kasyno arena test duel");
        assertFalse(plugin.show().running());
        assertTrue(anyContains(messages(admin), "Arena nie jest gotowa"));
    }

    @Test
    void purgeRemovesOnlyTaggedLeftovers() {
        server.dispatchCommand(admin, "kasyno arena zbuduj");
        assertNotNull(BukkitBackend.TAG);
        Location at = new Location(world, 15.5, 64, 15.5);
        Mannequin tagged = world.spawn(at, Mannequin.class);
        tagged.getPersistentDataContainer().set(BukkitBackend.TAG, PersistentDataType.STRING, "1");
        Mannequin other = world.spawn(at, Mannequin.class);
        assertEquals(2, mannequins());
        plugin.show().purge();
        assertEquals(1, mannequins(), "zostaje tylko nieoznaczony");
        assertFalse(tagged.isValid());
        assertTrue(other.isValid());
    }
}
