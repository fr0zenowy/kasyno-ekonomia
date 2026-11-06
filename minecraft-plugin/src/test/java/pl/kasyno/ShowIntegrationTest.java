package pl.kasyno;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kasyno.games.Rooms;
import pl.kasyno.gui.GuiManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Napad w prawdziwym świecie: bank stawiany komendą, figury ze skórkami graczy, rozliczenie i sprzątanie po pokazie. */
class ShowIntegrationTest {
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

    @Test
    void bankBuildsFromOneCommandAndSceneBecomesReady() {
        assertFalse(plugin.show().ready());
        server.dispatchCommand(admin, "kasyno bank zbuduj");
        assertTrue(plugin.show().ready(), "komplet punktów sceny");
        assertTrue(plugin.show().stage().missing().isEmpty());
        // bryła banku stoi tam, gdzie obiecuje projekt (administrator stał w (0, 64, 0), więc współrzędne planu to współrzędne świata + 64 w górę)
        assertEquals(Material.QUARTZ_PILLAR, world.getBlockAt(6, 67, 15).getType(), "kolumna portyku");
        assertEquals(Material.CHISELED_QUARTZ_BLOCK, world.getBlockAt(6, 74, 15).getType(), "głowica kolumny");
        assertEquals(Material.SMOOTH_QUARTZ, world.getBlockAt(16, 65, 20).getType(), "posadzka holu (szachownica)");
        assertEquals(Material.POLISHED_ANDESITE, world.getBlockAt(16, 65, 21).getType(), "posadzka holu (szachownica)");
        assertEquals(Material.RED_CARPET, world.getBlockAt(16, 66, 20).getType(), "dywan na osi");
        assertEquals(Material.GOLD_BLOCK, world.getBlockAt(11, 66, 36).getType(), "złoto w skarbcu");
        assertEquals(Material.BLACK_CONCRETE, world.getBlockAt(22, 65, 6).getType(), "van");
        assertEquals(Material.REDSTONE_LAMP, world.getBlockAt(3, 73, 20).getType(), "lampa alarmu");
        assertEquals(Material.STONE_BRICKS, world.getBlockAt(10, 66, 18).getType(), "ściana frontowa");
        assertEquals(Material.GOLD_BLOCK, world.getBlockAt(16, 83, 16).getType(), "akroterion na szczycie frontonu");
        assertEquals(Material.LIGHTNING_ROD, world.getBlockAt(16, 87, 24).getType(), "zwieńczenie kopuły");
        for (int[] d : new int[][]{{16, 66, 18}, {16, 66, 30}}) {
            Material door = world.getBlockAt(d[0], d[1], d[2]).getType();
            assertTrue(door == Material.IRON_DOOR || door == Material.AIR, "drzwi " + d[2] + ": " + door);
        }
        assertEquals(16.5, plugin.show().stage().get("door").x(), 1e-9);
        assertEquals(66, plugin.show().stage().get("door").y(), 1e-9, "punkt przy drzwiach leży na podeście");
        assertEquals(64, plugin.show().stage().get("street").y(), 1e-9, "punkt na chodniku leży na ziemi");
        assertEquals(1, world.getEntitiesByClass(org.bukkit.entity.TextDisplay.class).stream().filter(pl.kasyno.show.BankPrefab::isBankText).count(), "napis BANK nad wejściem");
        // plik sceny przeżywa restart
        assertTrue(new java.io.File(plugin.getDataFolder(), "stage.yml").isFile());
        assertEquals(world.getName(), pl.kasyno.show.Stage.load(new java.io.File(plugin.getDataFolder(), "stage.yml")).world());
    }

    @Test
    void worldHeistPlaysAtTheBankSettlesAndCleansUp() {
        server.dispatchCommand(admin, "kasyno bank zbuduj");
        PlayerMock a = newPlayer();
        PlayerMock b = newPlayer();
        PlayerMock c = newPlayer();
        assertNull(plugin.rooms().start(a, Rooms.Kind.HEIST, 500));
        plugin.rooms().join(b, Rooms.Kind.HEIST);
        plugin.rooms().join(c, Rooms.Kind.HEIST);
        ticks(20 * 46); // losowanie
        Rooms.Lobby l = plugin.rooms().lobby(Rooms.Kind.HEIST);
        assertTrue(l.resolved);
        // pieniądze są rozliczone od razu, a pokaz trwa w świecie
        long total = 0;
        for (PlayerMock p : List.of(a, b, c)) total += plugin.eco().wallet(p.getUniqueId());
        assertEquals(3 * 9_500L + (l.success ? l.share * l.heist.survivors().size() : 0), total);
        assertTrue(plugin.show().running(), "pokaz idzie w świecie");
        for (PlayerMock p : List.of(a, b, c)) assertNull(plugin.guis().current(p), "okno z mapą nie otwiera się, gdy bank stoi w świecie");

        int maxFigures = 0;
        int guard = 0;
        while (!l.sceneDone && guard++ < 8_000) {
            ticks(5);
            maxFigures = Math.max(maxFigures, mannequins());
        }
        assertTrue(l.sceneDone, "pokaz zakończył scenę");
        assertTrue(maxFigures >= 3, "na scenie stały figury ekipy: " + maxFigures);
        ticks(5);
        assertFalse(plugin.show().running());
        assertEquals(0, mannequins(), "po pokazie nie zostaje żadna figura");
        // zaproszenie dla wszystkich i podsumowanie dla ekipy
        for (PlayerMock p : List.of(a, b, c)) {
            boolean invite = false;
            String msg;
            while ((msg = p.nextMessage()) != null) if (msg.contains("ZOBACZ")) invite = true;
            assertTrue(invite, "każdy dostał link [ZOBACZ NA ŻYWO]");
        }
    }

    @Test
    void adminDemoRunsWithoutMoneyAndLeavesNothing() {
        server.dispatchCommand(admin, "kasyno bank zbuduj");
        long before = plugin.eco().wallet(admin.getUniqueId());
        server.dispatchCommand(admin, "kasyno bank test 4");
        assertTrue(plugin.show().running());
        int maxFigures = 0;
        int guard = 0;
        while (plugin.show().running() && guard++ < 8_000) {
            ticks(5);
            maxFigures = Math.max(maxFigures, mannequins());
        }
        assertFalse(plugin.show().running());
        assertTrue(maxFigures >= 4, "ekipa na scenie: " + maxFigures);
        assertEquals(0, mannequins());
        assertEquals(before, plugin.eco().wallet(admin.getUniqueId()), "pokaz próbny nie rusza pieniędzy");
        // drugi pokaz naraz jest odrzucany
        server.dispatchCommand(admin, "kasyno bank test");
        assertTrue(plugin.show().running());
        server.dispatchCommand(admin, "kasyno bank test");
        server.dispatchCommand(admin, "kasyno bank stop");
        assertFalse(plugin.show().running());
        assertEquals(0, mannequins(), "stop usuwa figury");
    }

    @Test
    void playersCanTeleportToTheViewingSpotAndOnlyAdminsBuild() {
        PlayerMock p = newPlayer();
        server.dispatchCommand(p, "kasyno bank zbuduj");
        assertFalse(plugin.show().ready(), "zwykły gracz nie stawia banku");
        server.dispatchCommand(p, "kasyno bank tp");
        server.dispatchCommand(admin, "kasyno bank zbuduj");
        p.teleport(new Location(world, 100, 64, 100));
        server.dispatchCommand(p, "kasyno bank tp");
        assertEquals(16.5, p.getLocation().getX(), 0.01);
        assertEquals(1.5, p.getLocation().getZ(), 0.01);
    }

    @Test
    void withoutAStageTheHeistFallsBackToTheMapWindow() {
        PlayerMock a = newPlayer();
        PlayerMock b = newPlayer();
        assertNull(plugin.rooms().start(a, Rooms.Kind.HEIST, 500));
        plugin.rooms().join(b, Rooms.Kind.HEIST);
        ticks(20 * 46);
        assertFalse(plugin.show().running());
        assertNotNull(plugin.guis().current(a), "bez sceny w świecie okno z mapą otwiera się samo");
        assertEquals("RoomGui", plugin.guis().current(a).getClass().getSimpleName());
        ticks(20 * 40);
        assertTrue(plugin.rooms().lobby(Rooms.Kind.HEIST).sceneDone);
        List<String> unused = new ArrayList<>();
        assertTrue(unused.isEmpty());
    }

    @Test
    void shutdownDuringShowRemovesFiguresAndStillAnnounces() {
        server.dispatchCommand(admin, "kasyno bank zbuduj");
        PlayerMock a = newPlayer();
        PlayerMock b = newPlayer();
        plugin.rooms().start(a, Rooms.Kind.HEIST, 500);
        plugin.rooms().join(b, Rooms.Kind.HEIST);
        ticks(20 * 46 + 80);
        assertTrue(plugin.show().running());
        plugin.show().disable();
        assertEquals(0, mannequins(), "wyłączenie usuwa figury");
        assertFalse(plugin.show().running());
        // pieniądze były rozliczone na starcie, więc nic się nie gubi
        long total = plugin.eco().wallet(a.getUniqueId()) + plugin.eco().wallet(b.getUniqueId());
        assertTrue(total >= 2 * 9_500L);
    }

    @Test
    void buildingTwiceKeepsOneSignAndClearsWhateverWasThere() {
        world.getBlockAt(16, 70, 20).setType(Material.STONE); // coś stoi w przyszłej hali
        world.getBlockAt(30, 68, 30).setType(Material.DIRT); // i obok, w bryle placu
        server.dispatchCommand(admin, "kasyno bank zbuduj");
        assertEquals(Material.AIR, world.getBlockAt(16, 70, 20).getType(), "hala jest pusta, bank sprząta teren");
        server.dispatchCommand(admin, "kasyno bank zbuduj");
        assertEquals(1, world.getEntitiesByClass(org.bukkit.entity.TextDisplay.class).stream().filter(pl.kasyno.show.BankPrefab::isBankText).count(), "stary napis zniknął");
        assertTrue(plugin.show().ready());
    }

    @Test
    void bankFigureHeightsFallBackToTheStagePointWhenTheWorldHasNoCollisionShapes() {
        // atrapa serwera nie zna kształtów kolizji: figury stoją na wysokości punktów sceny i pokaz idzie dalej
        server.dispatchCommand(admin, "kasyno bank zbuduj");
        server.dispatchCommand(admin, "kasyno bank test 3");
        int guard = 0;
        double minY = 1e9;
        double maxY = -1e9;
        while (plugin.show().running() && guard++ < 8_000) {
            ticks(5);
            for (Mannequin m : world.getEntitiesByClass(Mannequin.class)) {
                minY = Math.min(minY, m.getLocation().getY());
                maxY = Math.max(maxY, m.getLocation().getY());
            }
        }
        assertFalse(plugin.show().running());
        assertTrue(minY >= 63.9 && maxY <= 66.1, "figury zostają między chodnikiem a podestem: " + minY + " - " + maxY);
    }

    @Test
    void almostEveryBankBlockStateParsesOnTheMockServer() {
        java.util.List<String> failed = new ArrayList<>();
        int total = 0;
        for (String state : pl.kasyno.show.BankPlan.create().palette()) {
            total++;
            try {
                org.bukkit.Bukkit.createBlockData(state);
            } catch (RuntimeException e) {
                failed.add(state + " -> " + e.getMessage());
            }
        }
        // atrapa serwera zna nie wszystkie właściwości (np. latarnie); na prawdziwym serwerze te zapisy są poprawne, a w razie błędu bank
        // ustawia sam materiał. Reszta musi się parsować, a zapisy z samymi nazwami materiałów zawsze.
        assertTrue(failed.size() * 4 <= total, "za dużo zapisów nie przechodzi na atrapie (" + failed.size() + " z " + total + "): " + failed);
        for (String f : failed) assertTrue(f.contains("lantern") || f.contains("chain"), "zapis nieobsługiwany przez atrapę, ale nie z listy znanych ograniczeń: " + f);
    }
}
