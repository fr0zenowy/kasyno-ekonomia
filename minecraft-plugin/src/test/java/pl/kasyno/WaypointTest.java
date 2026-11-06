package pl.kasyno;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kasyno.gui.GuiManager;
import pl.kasyno.waypoint.BukkitMarkerBackend;
import pl.kasyno.waypoint.MarkerBackend;
import pl.kasyno.waypoint.ViewAnchor;
import pl.kasyno.waypoint.Waypoint;
import pl.kasyno.waypoint.WaypointManager;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Własne waypointy na atrapie serwera: dane i komendy, znaczniki każdego gracza (kreska + napis) z podstawioną atrapą stawiania,
 * wyłączenie w ustawieniach, ukrywanie i prawdziwe encje (BlockDisplay i TextDisplay widoczne tylko dla właściciela).
 */
class WaypointTest {
    static {
        System.setProperty("kasyno.test", "1");
    }

    /** Znaczniki w pamięci zamiast encji: widać, kto co dostał, ile razy coś postawiono, ruszono i usunięto. */
    static final class FakeMarkers implements MarkerBackend {
        static final class M {
            final Player owner;
            Spec spec;
            boolean removed;

            M(Player owner, Spec spec) {
                this.owner = owner;
                this.spec = spec;
            }
        }

        final List<M> all = new ArrayList<>();
        int updates;
        int purges;
        boolean failSpawns;
        int spawnAttempts;

        @Override
        public Object spawn(Player owner, Spec spec) {
            spawnAttempts++;
            if (failSpawns) return null;
            M m = new M(owner, spec);
            all.add(m);
            return m;
        }

        @Override
        public void update(Object handle, Spec spec) {
            ((M) handle).spec = spec;
            updates++;
        }

        @Override
        public void remove(Object handle) {
            ((M) handle).removed = true;
        }

        @Override
        public void purge() {
            purges++;
        }

        List<M> live() {
            return all.stream().filter(m -> !m.removed).toList();
        }

        List<M> liveFor(Player p) {
            return live().stream().filter(m -> m.owner.equals(p)).toList();
        }
    }

    /** Domyślny rozmiar napisu z ustawień. */
    private static final float SCALE = (float) WaypointManager.DEFAULT_LABEL_SCALE;

    ServerMock server;
    KasynoPlugin plugin;
    World world;
    PlayerMock admin;
    FakeMarkers fake;

    @BeforeEach
    void setUp() throws Exception {
        GuiManager.clickGapMs = 0;
        server = MockBukkit.mock();
        plugin = MockBukkit.load(KasynoPlugin.class);
        world = server.addSimpleWorld("world");
        admin = server.addPlayer();
        PlayerListener.prepare(plugin, admin);
        loadChunks(0, 0, 12);
        admin.teleport(new Location(world, 10, 64, 20, 0f, 0f));
        // kolejne testy mogą dostać ten sam folder danych: zaczynamy od pustych waypointów i domyślnych ustawień
        for (Waypoint w : new ArrayList<>(wps().all())) wps().remove(w.id());
        Files.deleteIfExists(file().toPath());
        plugin.getConfig().set("waypoints", null);
        // te testy sprawdzają waypointy administratora; miejsca kasyna (bank, arena, więzienie) mają własne testy w WaypointMeaningTest
        plugin.getConfig().set("waypoints.auto-places", false);
        fake = new FakeMarkers();
        wps().useBackend(fake);
        wps().reload();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private WaypointManager wps() {
        return plugin.waypoints();
    }

    private File file() {
        return new File(plugin.getDataFolder(), "waypoints.yml");
    }

    /** Atrapa serwera uznaje chunk za załadowany dopiero po sięgnięciu po niego: ładujemy kwadrat wokół punktu. */
    private void loadChunks(int blockX, int blockZ, int radiusChunks) {
        for (int cx = (blockX >> 4) - radiusChunks; cx <= (blockX >> 4) + radiusChunks; cx++) {
            for (int cz = (blockZ >> 4) - radiusChunks; cz <= (blockZ >> 4) + radiusChunks; cz++) world.getChunkAt(cx, cz);
        }
    }

    private Location at(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    private PlayerMock newPlayer(double x, double z) {
        PlayerMock p = server.addPlayer();
        PlayerListener.prepare(plugin, p);
        p.teleport(new Location(world, x, 64, z, 0f, 0f));
        return p;
    }

    // ---------- dane ----------

    @Test
    void waypointsAreSavedInAFileAndNamesAreValidatedAndUniqueIgnoringCaseAndDiacritics() {
        assertNull(wps().add(at(10.5, 64, 20.5), "Bank_Główny", 0xFF5555));
        assertEquals(1, wps().count());
        assertTrue(file().isFile() && file().length() > 0, "zapisane w waypoints.yml");
        assertNotNull(wps().add(at(5, 64, 0), "bank_glowny", null), "ta sama nazwa bez ogonków");
        assertNotNull(wps().add(at(5, 64, 0), "BANK_GŁÓWNY", null));
        for (String bad : List.of("", "a b", "<red>x", "Bank!", "x".repeat(33))) assertNotNull(wps().add(at(0, 64, 0), bad, null), bad);
        assertEquals(1, wps().count());
        assertEquals("Bank Główny", wps().find("BANK_GLOWNY").title());
        assertEquals(0xFF5555, wps().find("bank_glowny").color());
        assertEquals(10.5, wps().find("bank_glowny").x(), 1e-9);
    }

    @Test
    void thereIsALimitOnHowManyWaypointsYouCanMake() {
        for (int i = 0; i < WaypointManager.MAX_WAYPOINTS; i++) assertNull(wps().add(at(i, 64, 0), "W" + i, null), "waypoint " + i);
        assertNotNull(wps().add(at(0, 64, 5), "Jeden_za_duzo", null));
        assertEquals(WaypointManager.MAX_WAYPOINTS, wps().count());
    }

    @Test
    void removingMovingAndRecolouringUpdateTheData() {
        wps().add(at(1, 64, 1), "Dom", 0x00AA00);
        wps().add(at(2, 64, 2), "Kopalnia", null);
        assertEquals(0xFFD700, wps().find("kopalnia").color(), "kolor domyślny z config.yml");
        assertTrue(wps().move("dom", at(50.5, 70, 60.5)));
        assertEquals(50.5, wps().find("Dom").x(), 1e-9);
        assertEquals(70, wps().find("Dom").y(), 1e-9);
        assertEquals(60.5, wps().find("Dom").z(), 1e-9);
        assertTrue(wps().recolor("Dom", 0xFF0000));
        assertEquals(0xFF0000, wps().find("dom").color());
        assertTrue(wps().remove("Dom"));
        assertFalse(wps().remove("Dom"));
        assertFalse(wps().move("nie_ma", at(0, 0, 0)));
        assertFalse(wps().recolor("nie_ma", 0));
        assertEquals(1, wps().count());
        assertNull(wps().find("dom"));
    }

    @Test
    void aWaypointCanMoveToAnotherWorldAndTheDataSurvivesARestart() {
        World nether = server.addSimpleWorld("nether");
        wps().add(at(1, 64, 1), "Portal", 0x5555FF);
        wps().add(at(2, 64, 2), "Zażółć", 0x00AA00);
        assertTrue(wps().move("portal", new Location(nether, 8, 70, 8)));
        assertEquals("nether", wps().find("portal").world());

        WaypointManager fresh = new WaypointManager(plugin); // jak po restarcie: pamięć tylko z pliku
        fresh.useBackend(fake);
        fresh.enable();
        assertEquals(2, fresh.count());
        assertEquals("nether", fresh.find("portal").world());
        assertEquals(0x5555FF, fresh.find("portal").color());
        assertEquals("Zażółć", fresh.find("zazolc").name());
        fresh.disable();
    }

    // ---------- znaczniki każdego gracza ----------

    /** Odległość środka napisu (podstawa + połowa wysokości dwóch wierszy) od oczu gracza. */
    private double labelCentreDistance(FakeMarkers.M m, Player p) {
        Location eye = p.getEyeLocation();
        double dx = m.spec.labelX() - eye.getX();
        double dy = m.spec.labelY() + 0.25 * m.spec.scale() - eye.getY();
        double dz = m.spec.labelZ() - eye.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    void everyPlayerGetsHisOwnLabelForTheNearbyWaypoints() {
        PlayerMock other = newPlayer(40, 40);
        wps().add(at(10.5, 64, 40.5), "Bank", 0xFF5555);
        wps().add(at(60.5, 64, 30.5), "Dom", 0x00AA00);
        wps().tick();

        assertEquals(2, fake.liveFor(admin).size());
        assertEquals(2, fake.liveFor(other).size());
        assertEquals(4, fake.live().size(), "znaczniki nie są współdzielone");
        assertEquals(2, wps().markersOf(admin.getUniqueId()));
        assertTrue(wps().showing(admin.getUniqueId(), "bank"));

        FakeMarkers.M bank = fake.liveFor(admin).stream().filter(m -> m.spec.label().contains("Bank")).findFirst().orElseThrow();
        assertEquals(0xFF5555, bank.spec.color());
        assertTrue(bank.spec.showLabel());
        assertFalse(bank.spec.beam(), "kreska jest domyślnie wyłączona");
        assertTrue(bank.spec.label().contains("<#ff5555>") && bank.spec.label().contains("<bold>Bank</bold>"), bank.spec.label());
        assertTrue(bank.spec.label().contains("21") && bank.spec.label().contains("metrów"), "napis jak na screenie: nazwa i „21 metrów”: " + bank.spec.label());
        assertEquals(12, labelCentreDistance(bank, admin), 0.05, "waypoint jest dalej niż 12 bloków, więc napis stoi na 12 blokach w jego stronę");
        assertEquals(SCALE, bank.spec.scale(), 1e-6);
        FakeMarkers.M dom = fake.liveFor(admin).stream().filter(m -> m.spec.label().contains("Dom")).findFirst().orElseThrow();
        assertEquals(SCALE, dom.spec.scale(), 1e-6, "ten sam rozmiar bez względu na odległość");
        assertEquals(12, labelCentreDistance(dom, admin), 0.05);
        assertEquals(0x00AA00, dom.spec.color());
    }

    @Test
    void theLabelPointsTowardsTheWaypointAndShowsTheRealDistanceHoweverFarItIs() {
        wps().add(at(10.5, 64, 1020.5), "Daleko", 0xAA00AA);
        wps().tick();
        FakeMarkers.M m = fake.liveFor(admin).get(0);
        assertEquals(12, labelCentreDistance(m, admin), 0.05);
        assertEquals(admin.getEyeLocation().getZ() + 12, m.spec.labelZ(), 0.3, "12 bloków przed graczem w stronę waypointu (na południe)");
        assertEquals(10.0, m.spec.labelX(), 0.05);
        assertTrue(m.spec.label().contains("km") && m.spec.label().contains("1,0"), "napis mówi prawdę o odległości: " + m.spec.label());
        assertEquals(SCALE, m.spec.scale(), 1e-6, "daleki waypoint ma napis tego samego rozmiaru co bliski");

        // idziemy w stronę waypointu: ten sam znacznik jedzie z nami, napis się zmienia, nic nie powstaje od nowa
        int spawns = fake.all.size();
        admin.teleport(new Location(world, 10, 64, 120, 0f, 0f));
        loadChunks(10, 120, 8);
        wps().tick();
        assertEquals(spawns, fake.all.size(), "to samo urządzenie, tylko przesunięte");
        assertTrue(fake.updates >= 1);
        FakeMarkers.M moved = fake.liveFor(admin).get(0);
        assertEquals(12, labelCentreDistance(moved, admin), 0.05, "wciąż 12 bloków od gracza");
        assertEquals(admin.getEyeLocation().getZ() + 12, moved.spec.labelZ(), 0.3);
        assertTrue(moved.spec.label().matches("(?s).*(900|901) <gray>metrów.*"), moved.spec.label());
        assertEquals(SCALE, moved.spec.scale(), 1e-6);
    }

    @Test
    void aWaypointCloserThanTheLabelDistanceGetsTheLabelAtItsRealSpotAndASmallerScaleSoItLooksTheSame() {
        wps().add(at(10.5, 64, 26.0), "Blisko", null); // 6 bloków przed graczem
        wps().tick();
        FakeMarkers.M m = fake.liveFor(admin).get(0);
        assertEquals(10.5, m.spec.labelX(), 1e-9);
        assertEquals(26.0, m.spec.labelZ(), 1e-9);
        double eyeToLabel = labelCentreDistance(m, admin);
        assertEquals(6.03, eyeToLabel, 0.05, "napis stoi nad punktem (2 bloki wyżej)");
        assertEquals(SCALE * eyeToLabel / 12.0, m.spec.scale(), 0.02, "bliżej, więc mniejszy, żeby na ekranie wyglądał tak samo");
        assertTrue(m.spec.label().contains("<white>6 <gray>metrów"), m.spec.label());
        assertEquals(WaypointManager.LABEL_RISE, 2.0, 1e-9);
    }

    @Test
    void theBeamWhenSwitchedOnStandsWhereTheWaypointIsAndSticksInItsDirectionBeyondMarkerDistance() {
        plugin.getConfig().set("waypoints.beam", true);
        wps().reload();
        wps().add(at(10.5, 64, 90.5), "Granica", null); // 70 bloków od gracza (z = 20)
        wps().tick();
        FakeMarkers.M near = fake.liveFor(admin).get(0);
        assertTrue(near.spec.beam());
        assertEquals(90.5, near.spec.beamZ(), 1e-9);
        assertEquals(63.5, near.spec.beamY(), 1e-9, "kreska wyrasta pół bloku pod punktem");
        assertEquals(10.5, near.spec.beamX(), 1e-9);
        assertEquals(12, labelCentreDistance(near, admin), 0.05, "napis mimo to stoi blisko gracza");

        admin.teleport(new Location(world, 10, 64, -40, 0f, 0f));
        loadChunks(10, -40, 8);
        wps().tick();
        FakeMarkers.M far = fake.liveFor(admin).get(0);
        assertEquals(admin.getEyeLocation().getZ() + 80, far.spec.beamZ(), 0.5, "teraz 130 bloków: kreska przyklejona na 80");
        assertEquals(12, labelCentreDistance(far, admin), 0.05);
    }

    @Test
    void anIdlePlayerCausesNoRespawnsAndNoUpdates() {
        wps().add(at(10.5, 64, 40.5), "Bank", null);
        wps().tick();
        int spawned = fake.all.size();
        int updates = fake.updates;
        for (int i = 0; i < 20; i++) wps().tick();
        assertEquals(spawned, fake.all.size());
        assertEquals(updates, fake.updates, "stojąc w miejscu nic nie przesuwamy ani nie przepisujemy");
        assertEquals(1, fake.live().size());
    }

    @Test
    void aPlayerOnlySeesTheNearestFewAndTheSetFollowsHim() {
        plugin.getConfig().set("waypoints.max-visible", 2);
        for (int i = 1; i <= 5; i++) wps().add(at(10.5, 64, 20.5 + i * 10), "P" + i, null);
        wps().tick();
        assertEquals(2, fake.liveFor(admin).size());
        assertTrue(wps().showing(admin.getUniqueId(), "p1") && wps().showing(admin.getUniqueId(), "p2"));
        assertFalse(wps().showing(admin.getUniqueId(), "p5"));

        admin.teleport(new Location(world, 10, 64, 70, 0f, 0f));
        loadChunks(10, 70, 8);
        wps().tick();
        assertEquals(2, fake.liveFor(admin).size());
        assertTrue(wps().showing(admin.getUniqueId(), "p4") && wps().showing(admin.getUniqueId(), "p5"), "teraz najbliższe są P4 i P5");
        assertFalse(wps().showing(admin.getUniqueId(), "p1"));
        assertEquals(4, fake.all.size(), "dwa pierwsze postawione, potem dwa nowe");
        assertEquals(2, fake.all.stream().filter(m -> m.removed).count(), "dwa stare zdjęte");
    }

    @Test
    void theMaximumDistanceAndOtherWorldsKeepWaypointsOut() {
        World nether = server.addSimpleWorld("nether");
        plugin.getConfig().set("waypoints.max-distance", 100);
        wps().add(at(10.5, 64, 60.5), "Blisko", null);
        wps().add(at(10.5, 64, 600.5), "Daleko", null);
        wps().add(new Location(nether, 10, 64, 30), "Gdzie_indziej", null);
        wps().tick();
        assertEquals(1, fake.liveFor(admin).size());
        assertTrue(wps().showing(admin.getUniqueId(), "blisko"));

        // przejście do innego świata zdejmuje znaczniki starego i stawia te z nowego
        nether.getChunkAt(0, 1);
        nether.getChunkAt(0, 2);
        admin.teleport(new Location(nether, 10, 64, 20, 0f, 0f));
        wps().tick();
        assertFalse(wps().showing(admin.getUniqueId(), "blisko"));
        assertTrue(wps().showing(admin.getUniqueId(), "gdzie_indziej"));
        assertEquals(1, fake.liveFor(admin).size());
    }

    @Test
    void changesAreVisibleToPlayersOnTheNextStep() {
        plugin.getConfig().set("waypoints.beam", true);
        wps().reload();
        wps().add(at(10.5, 64, 40.5), "Bank", 0xFF5555);
        wps().tick();
        FakeMarkers.M before = fake.liveFor(admin).get(0);

        wps().recolor("bank", 0x00FF00);
        assertTrue(before.removed, "stary znacznik schodzi od razu");
        wps().tick();
        assertEquals(0x00FF00, fake.liveFor(admin).get(0).spec.color());

        wps().move("bank", at(30.5, 64, 50.5));
        wps().tick();
        assertEquals(30.5, fake.liveFor(admin).get(0).spec.beamX(), 1e-9);
        assertEquals(1, fake.liveFor(admin).size());

        wps().remove("bank");
        assertEquals(0, fake.liveFor(admin).size(), "usunięty waypoint znika od razu");
        wps().tick();
        assertEquals(0, fake.live().size());
    }

    @Test
    void ifTheBackendCannotSpawnTheStepJustTriesAgain() {
        fake.failSpawns = true;
        wps().add(at(10.5, 64, 40.5), "Bank", null);
        wps().tick();
        wps().tick();
        assertEquals(0, fake.live().size());
        assertEquals(2, fake.spawnAttempts, "bez wyjątku, próba przy każdym kroku");
        fake.failSpawns = false;
        wps().tick();
        assertEquals(1, fake.live().size());
    }

    @Test
    void waypointsOutsideLoadedChunksAreSkippedInsteadOfLoadingThem() {
        World far = server.addSimpleWorld("daleki");
        far.getChunkAt(0, 0);
        PlayerMock p = newPlayer(0, 0);
        p.teleport(new Location(far, 8, 64, 8, 0f, 0f));
        wps().add(new Location(far, 8, 64, 40), "Poza", null); // 32 bloki dalej: napis stanie 12 bloków od gracza w chunku 0,1, kreska w chunku 0,2
        wps().tick();
        assertEquals(0, fake.liveFor(p).size(), "chunk napisu nie jest załadowany");
        far.getChunkAt(0, 1);
        wps().tick();
        assertEquals(1, fake.liveFor(p).size(), "kreska jest wyłączona, więc chunk 0,2 nie jest potrzebny");

        plugin.getConfig().set("waypoints.beam", true);
        wps().reload();
        wps().tick();
        assertEquals(0, fake.liveFor(p).size(), "z kreską trzeba też chunka waypointu");
        far.getChunkAt(0, 2);
        wps().tick();
        assertEquals(1, fake.liveFor(p).size());
    }

    // ---------- stabilność napisu ----------

    @Test
    void jumpingAndSneakingDoNotMoveTheLabel() {
        wps().add(at(10.5, 64, 120.5), "Cel", null);
        wps().useFollowCheck(p -> p.getLocation().getY() < 64.01); // atrapa nie zna skoku: „na ziemi” tylko na poziomie 64
        wps().tick();
        FakeMarkers.M m = fake.liveFor(admin).get(0);
        double x0 = m.spec.labelX();
        double y0 = m.spec.labelY();
        double z0 = m.spec.labelZ();
        int updates = fake.updates;
        int spawns = fake.all.size();

        for (double up : new double[]{0.42, 0.75, 1.0, 1.25, 1.0, 0.7, 0.3, 0.0}) {
            admin.teleport(new Location(world, 10, 64 + up, 20, 0f, 0f));
            wps().tick();
        }
        assertEquals(updates, fake.updates, "w skoku napis nie drgnął");
        assertEquals(spawns, fake.all.size());
        FakeMarkers.M after = fake.liveFor(admin).get(0);
        assertEquals(x0, after.spec.labelX(), 1e-9);
        assertEquals(y0, after.spec.labelY(), 1e-9);
        assertEquals(z0, after.spec.labelZ(), 1e-9);

        admin.setSneaking(true);
        wps().tick();
        admin.setSneaking(false);
        wps().tick();
        assertEquals(updates, fake.updates, "kucanie też nie rusza napisu");
        assertEquals(y0, fake.liveFor(admin).get(0).spec.labelY(), 1e-9);
    }

    @Test
    void theLabelFollowsYouWhenYouReallyChangeLevel() {
        wps().add(at(10.5, 64, 120.5), "Cel", null);
        wps().useFollowCheck(p -> p.getLocation().getY() < 64.01);
        wps().tick();
        double y0 = fake.liveFor(admin).get(0).spec.labelY();

        // wchodzisz na wzgórze 10 bloków wyżej: nawet w „skoku” to zmiana poziomu, więc napis idzie za Tobą
        admin.teleport(new Location(world, 10, 74, 20, 0f, 0f));
        wps().tick();
        assertTrue(fake.liveFor(admin).get(0).spec.labelY() > y0 + 5, "napis na wysokości nowego poziomu: " + fake.liveFor(admin).get(0).spec.labelY());

        // stoisz na ziemi o 2 bloki wyżej (schody, platforma): też idzie za Tobą
        wps().useFollowCheck(p -> true);
        double before = fake.liveFor(admin).get(0).spec.labelY();
        admin.teleport(new Location(world, 10, 76, 20, 0f, 0f));
        wps().tick();
        double afterOne = fake.liveFor(admin).get(0).spec.labelY();
        assertTrue(afterOne > before && afterOne < before + 1.5, "najpierw tylko część różnicy: schodek przesuwa napis łagodnie");
        for (int i = 0; i < 40; i++) wps().tick();
        assertEquals(before + 2, fake.liveFor(admin).get(0).spec.labelY(), 0.5); // nie dokładnie 2, bo kierunek do dalekiego waypointu lekko się pochyla
    }

    @Test
    void theLabelKeepsUpWithAStepAndSettlesExactlyWhenYouStop() {
        wps().add(at(10.5, 64, 400.5), "Cel", null);
        wps().tick();
        admin.teleport(new Location(world, 10, 64, 23, 0f, 0f)); // krok o 3 bloki w stronę waypointu
        wps().tick();
        // jednorazowy krok o 3 bloki: filtr nadrabia część, a wygładzona prędkość daje lekkie wyprzedzenie
        double anchorZ = (20 + ViewAnchor.POSITION_SMOOTHING * 3) + 3 * ViewAnchor.SMOOTHING * ViewAnchor.LOOKAHEAD;
        assertEquals(anchorZ + 12, fake.liveFor(admin).get(0).spec.labelZ(), 0.2, "napis dojeżdża do gracza łagodnie, a nie skacze o cały krok");
        for (int i = 0; i < 40; i++) wps().tick(); // stoisz: punkt zbiega na Ciebie
        assertEquals(23 + 12, fake.liveFor(admin).get(0).spec.labelZ(), 0.1, "gdy staniesz, napis stoi dokładnie 12 bloków przed Tobą");
        // teleport nie jest „krokiem”: bez wyprzedzenia
        admin.teleport(new Location(world, 10, 64, 80, 0f, 0f));
        loadChunks(10, 80, 8);
        wps().tick();
        assertEquals(80 + 12, fake.liveFor(admin).get(0).spec.labelZ(), 0.2);
    }

    @Test
    void walkingMovesTheLabelSmoothlyEveryTickWithNoJerks() {
        wps().add(at(10.5, 64, 400.5), "Cel", null);
        admin.teleport(new Location(world, 10, 64, 10, 0f, 0f));
        wps().tick();
        int spawns = fake.all.size();
        List<Double> zs = new ArrayList<>();
        int updates = fake.updates;
        for (int i = 1; i <= 50; i++) {
            admin.teleport(new Location(world, 10, 64, 10 + 0.2 * i, 0f, 0f)); // 4 bloki na sekundę
            wps().tick();
            zs.add(fake.liveFor(admin).get(0).spec.labelZ());
        }
        assertEquals(spawns, fake.all.size(), "ciągle ten sam napis");
        assertEquals(updates + 50, fake.updates, "przy chodzeniu napis dostaje ruch w każdym ticku, więc płynie, a nie skacze co kilka ticków");
        for (int i = 15; i < zs.size(); i++) {
            double step = zs.get(i) - zs.get(i - 1);
            assertEquals(0.2, step, 0.03, "równy krok w ticku " + i + ": " + step);
        }
        // po rozbiegu stały odstęp od gracza: 12 bloków plus wyprzedzenie
        double playerZ = 10 + 0.2 * 50;
        double lag = 0.2 * ((1 - ViewAnchor.POSITION_SMOOTHING) / ViewAnchor.POSITION_SMOOTHING - ViewAnchor.LOOKAHEAD);
        assertEquals(playerZ - lag + 12, zs.get(zs.size() - 1), 0.1, "stały, niewielki odstęp za graczem");
    }

    @Test
    void theDefaultLabelIsSmallerThanBefore() {
        assertTrue(WaypointManager.DEFAULT_LABEL_SCALE < 3.0, "domyślny rozmiar zmniejszony z 3 do " + WaypointManager.DEFAULT_LABEL_SCALE);
        assertTrue(WaypointManager.DEFAULT_LABEL_SCALE >= 1.5, "ale nadal czytelny z odległości 12 bloków");
        assertEquals(WaypointManager.DEFAULT_LABEL_SCALE, wps().labelScale(), 1e-6);
        assertEquals(2.2, plugin.getConfig().getDouble("waypoints.label-scale"), 1e-9, "taki sam jest w config.yml");
    }

    // ---------- wyłączenie w ustawieniach i ukrywanie ----------

    @Test
    void switchingWaypointsOffInTheSettingsRemovesEveryMarkerAndOnBringsThemBack() {
        PlayerMock other = newPlayer(40, 40);
        wps().add(at(10.5, 64, 40.5), "A", null);
        wps().add(at(20.5, 64, 50.5), "B", null);
        wps().tick();
        assertEquals(4, fake.live().size());
        assertTrue(wps().active());

        plugin.getConfig().set("waypoints.enabled", false);
        wps().reload();
        assertFalse(wps().active());
        assertEquals(0, fake.live().size(), "wyłączone waypointy znikają wszystkim");
        wps().tick();
        assertEquals(0, fake.live().size(), "i się nie pojawiają");
        assertEquals(2, wps().count(), "dane zostają");
        assertNull(wps().add(at(30.5, 64, 60.5), "C", null), "przy wyłączonych można dalej przygotowywać punkty");
        assertEquals(3, wps().count());

        plugin.getConfig().set("waypoints.enabled", true);
        wps().reload();
        wps().tick();
        assertTrue(wps().active());
        assertEquals(3, fake.liveFor(admin).size());
        assertEquals(3, fake.liveFor(other).size());
    }

    @Test
    void beamAndLabelCanBeTurnedOffSeparately() {
        wps().add(at(10.5, 64, 40.5), "Bank", null);
        plugin.getConfig().set("waypoints.beam", false);
        wps().reload();
        wps().tick();
        assertFalse(fake.liveFor(admin).get(0).spec.beam());
        assertTrue(fake.liveFor(admin).get(0).spec.showLabel());

        plugin.getConfig().set("waypoints.beam", true);
        plugin.getConfig().set("waypoints.label", false);
        wps().reload();
        wps().tick();
        assertTrue(fake.liveFor(admin).get(0).spec.beam());
        assertFalse(fake.liveFor(admin).get(0).spec.showLabel());
        assertEquals(1, fake.liveFor(admin).size(), "po zmianie ustawień nie ma duplikatów");
    }

    @Test
    void settingsAreClampedToSaneValues() {
        plugin.getConfig().set("waypoints.marker-distance", 5);
        assertEquals(24, wps().markerDistance(), 1e-9);
        plugin.getConfig().set("waypoints.marker-distance", 9999);
        assertEquals(200, wps().markerDistance(), 1e-9);
        plugin.getConfig().set("waypoints.max-visible", 0);
        assertEquals(1, wps().maxVisible());
        plugin.getConfig().set("waypoints.max-visible", 500);
        assertEquals(32, wps().maxVisible());
        plugin.getConfig().set("waypoints.max-distance", -4);
        assertEquals(0, wps().maxDistance(), 1e-9);
        plugin.getConfig().set("waypoints.label-distance", 1);
        assertEquals(4, wps().labelDistance(), 1e-9);
        plugin.getConfig().set("waypoints.label-distance", 500);
        assertEquals(40, wps().labelDistance(), 1e-9);
        plugin.getConfig().set("waypoints.label-scale", 0.01);
        assertEquals(0.5f, wps().labelScale(), 1e-6);
        plugin.getConfig().set("waypoints.label-scale", 99);
        assertEquals(12f, wps().labelScale(), 1e-6);
        plugin.getConfig().set("waypoints.label-scale", 4.5);
        assertEquals(4.5f, wps().labelScale(), 1e-6);
        plugin.getConfig().set("waypoints.label-distance", 12);
        plugin.getConfig().set("waypoints.beam-height", 1);
        wps().add(at(10.5, 64, 40.5), "Bank", null);
        wps().tick();
        assertEquals(16, fake.liveFor(admin).get(0).spec.beamHeight(), 1e-9);
        assertEquals(4.5f, fake.liveFor(admin).get(0).spec.scale(), 1e-6, "własny rozmiar napisu z ustawień");
    }

    @Test
    void aPlayerCanHideTheMarkersForHimselfAndLeavingCleansUp() {
        PlayerMock other = newPlayer(40, 40);
        wps().add(at(10.5, 64, 40.5), "Bank", null);
        wps().tick();
        assertEquals(2, fake.live().size());

        server.dispatchCommand(admin, "kasyno waypoint ukryj");
        assertTrue(wps().hiddenFor(admin));
        assertEquals(0, fake.liveFor(admin).size());
        wps().tick();
        assertEquals(0, fake.liveFor(admin).size(), "ukryte nie wracają same");
        assertEquals(1, fake.liveFor(other).size(), "inni gracze nadal widzą");

        server.dispatchCommand(admin, "kasyno waypoint pokaz");
        wps().tick();
        assertEquals(1, fake.liveFor(admin).size());

        server.getPluginManager().callEvent(new PlayerQuitEvent(other, net.kyori.adventure.text.Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertEquals(0, fake.liveFor(other).size(), "wyjście z serwera sprząta znaczniki");
        assertEquals(0, wps().markersOf(other.getUniqueId()));
    }

    // ---------- sprzątanie po starej wersji ----------

    @Test
    void leftoverStandsFromTheOldLocatorBarVersionAreRemovedButOrdinaryOnesAreNot() {
        NamespacedKey legacy = new NamespacedKey(plugin, "waypoint_id");
        ArmorStand old1 = world.spawn(at(0, 64, 0), ArmorStand.class, a -> a.getPersistentDataContainer().set(legacy, PersistentDataType.STRING, "bank"));
        ArmorStand old2 = world.spawn(at(1, 64, 0), ArmorStand.class, a -> a.getPersistentDataContainer().set(legacy, PersistentDataType.STRING, "dom"));
        ArmorStand ordinary = world.spawn(at(2, 64, 0), ArmorStand.class);
        wps().enable();
        assertFalse(old1.isValid());
        assertFalse(old2.isValid());
        assertTrue(ordinary.isValid(), "zwykłych manekinów graczy nie ruszamy");
        assertTrue(fake.purges >= 1, "przy starcie sprzątamy też zostawione znaczniki");

        ArmorStand late = world.spawn(at(3, 64, 0), ArmorStand.class, a -> a.getPersistentDataContainer().set(legacy, PersistentDataType.STRING, "kopalnia"));
        ArmorStand plain = world.spawn(at(4, 64, 0), ArmorStand.class);
        server.getPluginManager().callEvent(new EntitiesLoadEvent(world.getChunkAt(0, 0), List.of(late, plain)));
        assertFalse(late.isValid(), "stary manekin z później wczytanego chunka też znika");
        assertTrue(plain.isValid());
    }

    // ---------- komendy ----------

    @Test
    void adminManagesWaypointsWithCommands() {
        admin.setOp(true);
        server.dispatchCommand(admin, "kasyno waypoint dodaj Bank czerwony");
        server.dispatchCommand(admin, "kasyno waypoint dodaj Moj_Dom #00ff00");
        server.dispatchCommand(admin, "kasyno waypoint dodaj Kopalnia");
        assertEquals(3, wps().count());
        assertEquals(0xFF5555, wps().find("bank").color());
        assertEquals(0x00FF00, wps().find("moj_dom").color());
        assertEquals(0xFFD700, wps().find("kopalnia").color());
        assertEquals(10, wps().find("bank").x(), 1e-9);
        assertEquals(20, wps().find("bank").z(), 1e-9);

        server.dispatchCommand(admin, "kasyno waypoint dodaj Bank");
        server.dispatchCommand(admin, "kasyno waypoint dodaj Zly_kolor tecza");
        server.dispatchCommand(admin, "kasyno waypoint dodaj \"x");
        server.dispatchCommand(admin, "kasyno waypoint dodaj");
        assertEquals(3, wps().count(), "błędne polecenia niczego nie dodają");

        server.dispatchCommand(admin, "kasyno waypoint kolor Bank niebieski");
        assertEquals(0x5555FF, wps().find("bank").color());
        server.dispatchCommand(admin, "kasyno waypoint kolor Bank tecza");
        server.dispatchCommand(admin, "kasyno waypoint kolor Bank");
        assertEquals(0x5555FF, wps().find("bank").color());

        admin.teleport(new Location(world, 300.5, 70, -40.5));
        server.dispatchCommand(admin, "kasyno waypoint przenies Bank");
        assertEquals(300.5, wps().find("bank").x(), 1e-9);
        assertEquals(-40.5, wps().find("bank").z(), 1e-9);

        admin.teleport(new Location(world, 0, 64, 0));
        server.dispatchCommand(admin, "kasyno waypoint tp Bank");
        assertEquals(300.5, admin.getLocation().getX(), 1e-9);
        server.dispatchCommand(admin, "kasyno waypoint tp nie_ma");

        server.dispatchCommand(admin, "kasyno waypoint lista");
        server.dispatchCommand(admin, "kasyno waypoint info");
        server.dispatchCommand(admin, "kasyno waypoint");

        server.dispatchCommand(admin, "kasyno waypoint usun Bank");
        server.dispatchCommand(admin, "kasyno waypoint usun Bank");
        assertEquals(2, wps().count());
        server.dispatchCommand(admin, "kasyno wp usun Moj_Dom");
        assertEquals(1, wps().count());
        server.dispatchCommand(admin, "kasyno waypoint nieznane");
    }

    @Test
    void anyoneCanListHideAndShowButOnlyAdminsChange() {
        wps().add(at(1, 64, 1), "Bank", null);
        PlayerMock player = newPlayer(0, 0);
        server.dispatchCommand(player, "kasyno waypoint lista");
        server.dispatchCommand(player, "kasyno waypoint info");
        server.dispatchCommand(player, "kasyno waypoint ukryj");
        assertTrue(wps().hiddenFor(player));
        server.dispatchCommand(player, "kasyno waypoint pokaz");
        assertFalse(wps().hiddenFor(player));

        server.dispatchCommand(player, "kasyno waypoint dodaj Hakerski");
        server.dispatchCommand(player, "kasyno waypoint usun Bank");
        server.dispatchCommand(player, "kasyno waypoint kolor Bank czerwony");
        server.dispatchCommand(player, "kasyno waypoint przenies Bank");
        server.dispatchCommand(player, "kasyno waypoint wylacz");
        assertEquals(1, wps().count());
        assertEquals(0xFFD700, wps().find("bank").color());
        assertEquals(1, wps().find("bank").x(), 1e-9);
        assertTrue(wps().active(), "zwykły gracz nie wyłączy waypointów");
    }

    @Test
    void theSwitchCommandsPersistInTheConfigAndReloadKeepsThem() {
        admin.setOp(true);
        wps().add(at(10.5, 64, 40.5), "A", null);
        wps().tick();
        assertEquals(1, fake.live().size());
        server.dispatchCommand(admin, "kasyno waypoint wylacz");
        assertFalse(plugin.getConfig().getBoolean("waypoints.enabled"));
        assertFalse(wps().active());
        assertEquals(0, fake.live().size());
        server.dispatchCommand(admin, "kasyno reload");
        assertFalse(wps().active(), "przeładowanie czyta zapisane ustawienie");
        wps().tick();
        assertEquals(0, fake.live().size());

        server.dispatchCommand(admin, "kasyno waypoint dodaj B");
        assertEquals(2, wps().count());
        server.dispatchCommand(admin, "kasyno waypoint wlacz");
        assertTrue(wps().active());
        wps().tick();
        assertEquals(2, fake.liveFor(admin).size());
        server.dispatchCommand(admin, "kasyno reload");
        wps().tick();
        assertEquals(2, fake.liveFor(admin).size());
    }

    @Test
    void tabCompletionKnowsTheSubcommandsNamesAndColours() {
        admin.setOp(true);
        wps().add(at(1, 64, 1), "Bank", null);
        var cmd = plugin.getCommand("kasyno");
        var tab = cmd.getTabCompleter();
        assertTrue(tab.onTabComplete(admin, cmd, "kasyno", new String[]{"waypo"}).contains("waypoint"));
        var subs = tab.onTabComplete(admin, cmd, "kasyno", new String[]{"waypoint", ""});
        assertTrue(subs.containsAll(List.of("lista", "info", "ukryj", "pokaz", "dodaj", "usun", "przenies", "kolor", "tp", "wlacz", "wylacz")), subs.toString());
        assertFalse(subs.contains("locatorbar"), "pasek lokalizatora nie jest już używany");
        assertEquals(List.of("Bank"), tab.onTabComplete(admin, cmd, "kasyno", new String[]{"waypoint", "usun", "B"}));
        assertTrue(tab.onTabComplete(admin, cmd, "kasyno", new String[]{"waypoint", "dodaj", "Nowy", "cz"}).containsAll(List.of("czerwony", "czarny")));
        assertTrue(tab.onTabComplete(admin, cmd, "kasyno", new String[]{"wp", "kolor", "Bank", "zl"}).contains("zloty"));

        admin.setOp(false);
        var regular = tab.onTabComplete(admin, cmd, "kasyno", new String[]{"waypoint", ""});
        assertTrue(regular.containsAll(List.of("lista", "ukryj", "pokaz")));
        assertFalse(regular.contains("dodaj"), "zwykły gracz nie dostaje komend administratora");
    }

    // ---------- prawdziwe encje ----------

    private List<Display> markerEntities() {
        List<Display> out = new ArrayList<>();
        for (Display d : world.getEntitiesByClass(Display.class)) {
            if (BukkitMarkerBackend.TAG != null && d.getPersistentDataContainer().has(BukkitMarkerBackend.TAG, PersistentDataType.STRING)) out.add(d);
        }
        return out;
    }

    @Test
    void byDefaultOnlyALabelIsSpawnedAndItIsATaggedTextDisplayNotSavedWithTheWorld() {
        wps().useBackend(new BukkitMarkerBackend(plugin));
        wps().add(at(10.5, 64, 40.5), "Bank", 0xFF5555);
        wps().tick();
        List<Display> ents = markerEntities();
        assertEquals(1, ents.size(), "sam napis, bez kreski");
        assertTrue(ents.get(0) instanceof TextDisplay);
        assertFalse(ents.get(0).isPersistent(), "nie zapisuje się w świecie");
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(((TextDisplay) ents.get(0)).text());
        assertTrue(text.contains("Bank") && text.contains("21 metrów"), "jak na screenie: nazwa i odległość po polsku: " + text);
        Location eye = admin.getEyeLocation();
        Location l = ents.get(0).getLocation();
        double dx = l.getX() - eye.getX();
        double dy = l.getY() + 0.25 * SCALE - eye.getY();
        double dz = l.getZ() - eye.getZ();
        assertEquals(12, Math.sqrt(dx * dx + dy * dy + dz * dz), 0.05, "napis stoi 12 bloków od gracza w stronę waypointu");
        wps().remove("bank");
        assertEquals(0, markerEntities().size());
    }

    @Test
    void realMarkersAreADisplayBeamAndALabelTaggedAndNotSavedWithTheWorld() {
        plugin.getConfig().set("waypoints.beam", true);
        wps().reload();
        wps().useBackend(new BukkitMarkerBackend(plugin));
        wps().add(at(10.5, 64, 40.5), "Bank", 0xFF5555);
        wps().tick();

        List<Display> ents = markerEntities();
        assertEquals(2, ents.size(), "kreska i napis");
        assertEquals(1, ents.stream().filter(e -> e instanceof BlockDisplay).count());
        assertEquals(1, ents.stream().filter(e -> e instanceof TextDisplay).count());
        for (Display d : ents) assertFalse(d.isPersistent(), "znaczniki nie zapisują się w świecie");
        BlockDisplay beam = ents.stream().filter(e -> e instanceof BlockDisplay).map(e -> (BlockDisplay) e).findFirst().orElseThrow();
        TextDisplay label = ents.stream().filter(e -> e instanceof TextDisplay).map(e -> (TextDisplay) e).findFirst().orElseThrow();
        assertEquals(10.5, beam.getLocation().getX(), 1e-9);
        assertEquals(63.5, beam.getLocation().getY(), 1e-9);
        assertEquals(40.5, beam.getLocation().getZ(), 1e-9);
        assertTrue(label.getLocation().distance(admin.getEyeLocation()) < 13, "napis stoi blisko gracza, a nie przy odległej kresce");
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(label.text()).contains("Bank"));

        // ten sam znacznik jedzie za graczem, nowe encje nie powstają
        wps().add(at(10.5, 64, 1000.5), "Daleko", null);
        wps().tick();
        assertEquals(4, markerEntities().size());
        wps().remove("bank");
        assertEquals(2, markerEntities().size(), "usunięty waypoint zabiera swoje encje");
        wps().remove("daleko");
        assertEquals(0, markerEntities().size());
    }

    @Test
    void realMarkersAreRemovedWhenWaypointsAreSwitchedOffAndOnDisableAndSweptAfterACrash() {
        plugin.getConfig().set("waypoints.beam", true);
        wps().reload();
        wps().useBackend(new BukkitMarkerBackend(plugin));
        wps().add(at(10.5, 64, 40.5), "A", null);
        wps().add(at(20.5, 64, 50.5), "B", null);
        wps().tick();
        assertEquals(4, markerEntities().size());

        plugin.getConfig().set("waypoints.enabled", false);
        wps().reload();
        assertEquals(0, markerEntities().size());
        plugin.getConfig().set("waypoints.enabled", true);
        wps().reload();
        wps().tick();
        assertEquals(4, markerEntities().size());
        wps().tick();
        assertEquals(4, markerEntities().size(), "bez duplikatów");

        wps().disable();
        assertEquals(0, markerEntities().size(), "wyłączenie pluginu sprząta");

        // po awarii serwera zostały znaczniki: start pluginu je zamiata
        BukkitMarkerBackend backend = new BukkitMarkerBackend(plugin);
        wps().useBackend(backend);
        wps().reload();
        wps().tick();
        assertEquals(4, markerEntities().size());
        wps().useBackend(new BukkitMarkerBackend(plugin)); // nowy menedżer zapomniał o uchwytach, encje zostały
        assertEquals(0, markerEntities().size(), "podmiana zdejmuje swoje znaczniki");
        world.spawn(at(0, 64, 0), TextDisplay.class, t -> t.getPersistentDataContainer().set(BukkitMarkerBackend.TAG, PersistentDataType.STRING, "1"));
        assertEquals(1, markerEntities().size());
        wps().enable();
        assertEquals(0, markerEntities().size(), "start pluginu usuwa osierocone znaczniki");
    }
}
