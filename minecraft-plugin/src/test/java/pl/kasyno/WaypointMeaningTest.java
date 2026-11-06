package pl.kasyno;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kasyno.core.PlayerData;
import pl.kasyno.gui.GuiManager;
import pl.kasyno.show.Stage;
import pl.kasyno.waypoint.Waypoint;
import pl.kasyno.waypoint.WaypointManager;
import pl.kasyno.waypoint.WaypointStore;
import pl.kasyno.WaypointTest.FakeMarkers;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Waypointy z sensem: miejsca kasyna, prowadzenie do celu, nagrody za odkrycie, dom i miejsce śmierci (każdy gracz widzi swoje). */
class WaypointMeaningTest {
    static {
        System.setProperty("kasyno.test", "1");
    }

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
        for (int cx = -12; cx <= 12; cx++) for (int cz = -12; cz <= 12; cz++) world.getChunkAt(cx, cz);
        admin = server.addPlayer();
        PlayerListener.prepare(plugin, admin);
        admin.teleport(new Location(world, 10, 64, 20, 0f, 0f));
        for (Waypoint w : new ArrayList<>(wps().all())) wps().remove(w.id());
        Files.deleteIfExists(file().toPath());
        plugin.getConfig().set("waypoints", null);
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

    private Location at(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    private PlayerMock newPlayer(double x, double z) {
        PlayerMock p = server.addPlayer();
        PlayerListener.prepare(plugin, p);
        p.teleport(new Location(world, x, 64, z, 0f, 0f));
        return p;
    }

    private List<String> labels(PlayerMock p) {
        return fake.liveFor(p).stream().map(m -> m.spec.label()).toList();
    }

    private boolean sees(PlayerMock p, String text) {
        return labels(p).stream().anyMatch(l -> l.contains(text));
    }

    private static String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) sb.append(m).append('\n');
        return sb.toString();
    }

    private PlayerData data(PlayerMock p) {
        return plugin.eco().pd(p.getUniqueId());
    }

    private void deathAt(PlayerMock p) {
        PlayerDeathEvent e = new PlayerDeathEvent(p, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), new ArrayList<>(), 0, (Component) null, true);
        server.getPluginManager().callEvent(e);
    }

    // ---------- miejsca kasyna ----------

    @Test
    void thePrisonShowsUpAsAnAutomaticPlace() {
        List<Waypoint> auto = wps().autoPlaces();
        assertEquals(1, auto.size());
        Waypoint w = auto.get(0);
        assertEquals(Waypoint.Kind.AUTO, w.kind());
        assertEquals("Więzienie", w.title());
        assertEquals(-78.5, w.x(), 1e-9);
        assertEquals(92, w.y(), 1e-9);
        assertEquals(65.5, w.z(), 1e-9);
        plugin.getConfig().set("prison.enabled", false);
        assertTrue(wps().autoPlaces().isEmpty(), "bez więzienia nie ma tego punktu");
        plugin.getConfig().set("prison.enabled", true);
        plugin.getConfig().set("waypoints.auto-places", false);
        assertTrue(wps().autoPlaces().isEmpty(), "automatyczne punkty wyłączają się w ustawieniach");
    }

    @Test
    void theBankAndTheArenaAppearOnceTheyAreBuilt() {
        Stage st = plugin.show().stage();
        st.world("world");
        for (String n : Stage.REQUIRED) st.set(n, new Stage.P(1, 64, 2));
        st.set("door", new Stage.P(5, 64, 7));
        st.arenaWorld("world");
        for (String n : Stage.ARENA_REQUIRED) st.set(n, new Stage.P(30, 64, 30));
        List<String> titles = wps().autoPlaces().stream().map(Waypoint::title).toList();
        assertTrue(titles.contains("Bank") && titles.contains("Arena") && titles.contains("Więzienie"), titles.toString());
        Waypoint bank = wps().autoPlaces().stream().filter(w -> w.title().equals("Bank")).findFirst().orElseThrow();
        assertEquals(5, bank.x(), 1.0, "bank wskazuje wejście");
    }

    @Test
    void everyPlayerSeesTheCasinoPlacesNearbyButTheyAreNotEditable() {
        PlayerMock p = newPlayer(-70, 60);
        wps().tick();
        assertTrue(sees(p, "Więzienie"), labels(p).toString());
        assertEquals(0, wps().count(), "nie ma ich w zapisanych waypointach");
        assertFalse(wps().remove("wiezienie"));
    }

    @Test
    void anAdminsOwnBankWinsOverTheAutomaticOne() {
        Stage st = plugin.show().stage();
        st.world("world");
        for (String n : Stage.REQUIRED) st.set(n, new Stage.P(1, 64, 2));
        wps().add(at(100, 64, 100), "Bank", null);
        PlayerMock p = newPlayer(0, 0);
        assertEquals(100, wps().resolve(p, "bank").x(), 1e-9);
        wps().remove("Bank");
        assertEquals(Waypoint.Kind.AUTO, wps().resolve(p, "bank").kind());
    }

    // ---------- prowadzenie ----------

    @Test
    void trackingShowsOnlyTheChosenPlaceAndEndsOnArrival() {
        wps().add(at(10, 64, 20), "Kopalnia", null);
        wps().add(at(30, 64, 40), "Las", null);
        PlayerMock p = newPlayer(0, 0);
        wps().tick();
        assertTrue(sees(p, "Kopalnia") && sees(p, "Las"));
        assertNull(wps().track(p, "las"));
        assertEquals("Las", wps().tracked(p).title());
        wps().tick();
        assertTrue(sees(p, "Las"));
        assertTrue(sees(p, "➤"), "prowadzony cel ma strzałkę: " + labels(p));
        assertFalse(sees(p, "Kopalnia"), "reszta znika, żeby nie przeszkadzała");
        assertFalse(sees(p, "Więzienie"));
        p.teleport(at(30, 64, 44));
        wps().tick();
        assertNull(wps().tracked(p), "dotarłeś, więc prowadzenie się kończy");
        assertTrue(drain(p).contains("Dotarłeś"));
        wps().tick();
        assertTrue(sees(p, "Kopalnia"), "i wszystko wraca");
    }

    @Test
    void trackingCanBeSwitchedOffAndRejectsUnknownOrDistantPlaces() {
        PlayerMock p = newPlayer(0, 0);
        wps().add(at(30, 64, 40), "Las", null);
        assertNotNull(wps().track(p, "nie_ma"));
        World other = server.addSimpleWorld("nether");
        wps().add(new Location(other, 0, 64, 0), "Portal", null);
        String err = wps().track(p, "Portal");
        assertNotNull(err);
        assertTrue(err.contains("innym świecie"), err);
        assertNull(wps().track(p, "Las"));
        assertNotNull(wps().tracked(p));
        assertNull(wps().track(p, "wylacz"));
        assertNull(wps().tracked(p));
    }

    @Test
    void trackingEndsWhenTheTargetIsRemoved() {
        PlayerMock p = newPlayer(0, 0);
        wps().add(at(30, 64, 40), "Las", null);
        assertNull(wps().track(p, "Las"));
        wps().remove("Las");
        wps().tick();
        assertNull(data(p).track);
        assertNull(wps().tracked(p));
    }

    // ---------- nagrody za odkrycie ----------

    @Test
    void discoveringARewardedPlacePaysOnceAndTheStarDisappears() {
        wps().add(at(30, 64, 40), "Skarb", null);
        assertNull(wps().setReward("Skarb", 500));
        PlayerMock p = newPlayer(0, 0);
        data(p).wallet = 100;
        wps().tick();
        assertTrue(sees(p, "★"), "z daleka widać gwiazdkę i nagrodę: " + labels(p));
        assertEquals(100, plugin.eco().wallet(p.getUniqueId()));
        p.teleport(at(30, 64, 45));
        wps().tick();
        assertEquals(600, plugin.eco().wallet(p.getUniqueId()));
        assertTrue(wps().discovered(p.getUniqueId(), "skarb"));
        assertTrue(drain(p).contains("Odkrycie"));
        wps().tick();
        assertFalse(sees(p, "★"), "po odebraniu nagrody gwiazdka znika: " + labels(p));
        p.teleport(at(0, 64, 0));
        wps().tick();
        p.teleport(at(30, 64, 45));
        wps().tick();
        assertEquals(600, plugin.eco().wallet(p.getUniqueId()), "nagroda jest raz na gracza");
        assertArrayEquals2(new int[]{1, 1}, wps().discoveryProgress(p.getUniqueId()));
    }

    private static void assertArrayEquals2(int[] expected, int[] actual) {
        assertEquals(expected[0], actual[0]);
        assertEquals(expected[1], actual[1]);
    }

    @Test
    void everyPlayerGetsTheRewardOnTheirOwn() {
        wps().add(at(30, 64, 40), "Skarb", null);
        wps().setReward("Skarb", 300);
        PlayerMock a = newPlayer(30, 42);
        PlayerMock b = newPlayer(0, 0);
        data(a).wallet = 0;
        data(b).wallet = 0;
        wps().tick();
        assertEquals(300, plugin.eco().wallet(a.getUniqueId()));
        assertEquals(0, plugin.eco().wallet(b.getUniqueId()), "drugi gracz jeszcze nie dotarł");
        b.teleport(at(32, 64, 40));
        wps().tick();
        assertEquals(300, plugin.eco().wallet(b.getUniqueId()));
    }

    @Test
    void rewardsAreValidatedAndCanBeRemoved() {
        wps().add(at(30, 64, 40), "Skarb", null);
        assertNotNull(wps().setReward("nie_ma", 5));
        assertNotNull(wps().setReward("Skarb", -1));
        assertNotNull(wps().setReward("Skarb", 2_000_000));
        assertNull(wps().setReward("Skarb", 80));
        assertEquals(80, wps().find("skarb").reward());
        assertNull(wps().setReward("Skarb", 0));
        assertEquals(0, wps().find("skarb").reward());
        PlayerMock p = newPlayer(30, 41);
        data(p).wallet = 0;
        wps().tick();
        assertEquals(0, plugin.eco().wallet(p.getUniqueId()), "bez nagrody nic się nie płaci");
    }

    @Test
    void rewardsAreSavedInTheFileAndOldFilesStillLoad() throws Exception {
        wps().add(at(30, 64, 40), "Skarb", null);
        wps().add(at(31, 64, 41), "Zwykly", null);
        wps().setReward("Skarb", 500);
        WaypointStore again = new WaypointStore(file());
        again.load();
        assertEquals(500, again.get("skarb").reward());
        assertEquals(0, again.get("zwykly").reward());
        Files.writeString(file().toPath(), "waypoints:\n  stary:\n    name: Stary\n    world: world\n    x: 1.0\n    y: 64.0\n    z: 2.0\n    color: ff0000\n");
        WaypointStore old = new WaypointStore(file());
        old.load();
        assertEquals(0, old.get("stary").reward());
        assertEquals(Waypoint.Kind.PLACE, old.get("stary").kind());
    }

    @Test
    void movingOrRecolouringKeepsTheReward() {
        wps().add(at(30, 64, 40), "Skarb", null);
        wps().setReward("Skarb", 500);
        wps().move("Skarb", at(60, 64, 60));
        wps().recolor("Skarb", 0xFF0000);
        assertEquals(500, wps().find("skarb").reward());
        assertEquals(60, wps().find("skarb").x(), 1e-9);
        assertEquals(0xFF0000, wps().find("skarb").color());
    }

    // ---------- dom ----------

    @Test
    void homeIsPersonalTrackableAndRemovable() {
        PlayerMock p = newPlayer(5, 5);
        PlayerMock other = newPlayer(6, 6);
        wps().setHome(p);
        wps().tick();
        assertTrue(sees(p, "⌂"), labels(p).toString());
        assertFalse(sees(other, "⌂"), "cudzego domu nie widać");
        assertNull(wps().track(p, "dom"));
        assertEquals("Dom", wps().tracked(p).title());
        assertTrue(wps().clearHome(p));
        wps().tick();
        assertNull(wps().tracked(p), "bez domu nie ma co prowadzić");
        assertFalse(wps().clearHome(p));
        assertFalse(sees(p, "⌂"));
    }

    @Test
    void ownHomeWinsOverAPublicWaypointCalledDom() {
        PlayerMock p = newPlayer(5, 5);
        wps().add(at(100, 64, 100), "Dom", null);
        assertEquals(100, wps().resolve(p, "dom").x(), 1e-9, "bez własnego domu: publiczny");
        wps().setHome(p);
        assertEquals(Waypoint.Kind.HOME, wps().resolve(p, "dom").kind());
    }

    // ---------- miejsce śmierci ----------

    @Test
    void deathLeavesATemporaryPersonalPointThatEndsWhenYouArrive() {
        PlayerMock p = newPlayer(20, 20);
        PlayerMock other = newPlayer(22, 22);
        deathAt(p);
        assertNotNull(data(p).deathWorld);
        assertTrue(drain(p).contains("Miejsce śmierci"));
        p.teleport(at(25, 64, 25));
        wps().tick();
        assertTrue(sees(p, "☠"), labels(p).toString());
        assertFalse(sees(other, "☠"), "cudzej śmierci nie widać");
        p.teleport(at(80, 64, 80));
        wps().tick();
        assertNotNull(data(p).deathWorld, "daleko punkt zostaje");
        p.teleport(at(21, 64, 21));
        wps().tick();
        assertNull(data(p).deathWorld, "dotarłeś, punkt znika");
        assertTrue(drain(p).contains("miejscu śmierci"));
        wps().tick();
        assertFalse(sees(p, "☠"));
    }

    @Test
    void theDeathPointExpiresAndCanBeSwitchedOff() {
        PlayerMock p = newPlayer(20, 20);
        deathAt(p);
        p.teleport(at(90, 64, 90));
        data(p).deathAt = plugin.eco().now() - 11 * 60_000L;
        wps().tick();
        assertNull(data(p).deathWorld, "po 10 minutach punkt wygasa");
        assertFalse(sees(p, "☠"));
        plugin.getConfig().set("waypoints.death-minutes", 0);
        deathAt(p);
        assertNull(data(p).deathWorld, "death-minutes: 0 wyłącza ten punkt");
    }

    @Test
    void youCanBeLedToTheDeathPlace() {
        PlayerMock p = newPlayer(20, 20);
        deathAt(p);
        p.teleport(at(90, 64, 90));
        assertNull(wps().track(p, "smierc"));
        assertEquals("Miejsce śmierci", wps().tracked(p).title());
    }

    // ---------- komendy ----------

    @Test
    void theCommandsTrackSetHomeAndShowProgress() {
        admin.addAttachment(plugin, "kasyno.admin", true);
        admin.performCommand("kasyno waypoint dodaj Skarb");
        admin.performCommand("kasyno waypoint nagroda Skarb 750");
        assertEquals(750, wps().find("skarb").reward());
        PlayerMock p = newPlayer(0, 0);
        p.performCommand("kasyno waypoint nagroda Skarb 1");
        assertEquals(750, wps().find("skarb").reward(), "zwykły gracz nie ustawia nagród");
        drain(p);
        p.performCommand("kasyno waypoint lista");
        String list = drain(p);
        assertTrue(list.contains("Skarb") && list.contains("nagroda"), list);
        p.performCommand("kasyno waypoint prowadz skarb");
        assertEquals("Skarb", wps().tracked(p).title());
        drain(p);
        p.performCommand("kasyno waypoint prowadz wylacz");
        assertNull(wps().tracked(p));
        p.performCommand("kasyno waypoint dom ustaw");
        assertNotNull(data(p).homeWorld);
        p.performCommand("kasyno waypoint odkryte");
        assertTrue(drain(p).contains("0/1"));
        p.performCommand("kasyno waypoint dom usun");
        assertNull(data(p).homeWorld);
    }

    @Test
    void oldPlayerSavesLoadWithoutPersonalPoints() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        PlayerData old = gson.fromJson("{\"name\":\"Stary\",\"wallet\":5}", PlayerData.class);
        assertNull(old.homeWorld);
        assertNull(old.deathWorld);
        assertNull(old.track);
        assertNotNull(old.discovered);
        assertTrue(old.discovered.isEmpty());
    }
}
