package pl.kasyno.shark;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.PlayerListener;
import pl.kasyno.gui.GuiManager;
import pl.kasyno.gui.T;
import pl.kasyno.prison.PrisonRules;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rekin na atrapie serwera: ryzyko zależne od odległości i głębokości, ucieczka, zjedzenie z utratą połowy gotówki i komendy. */
class SharkTest {
    static {
        System.setProperty("kasyno.test", "1");
    }

    /** Wykrywacz wody sterowany z testu. */
    static final class FakeWater implements WaterProbe {
        boolean swimming;
        boolean grounded = true;
        int depth = 10;
        double surface = 64.89;

        @Override
        public boolean swimming(Player p) {
            return swimming;
        }

        @Override
        public int depth(Player p) {
            return depth;
        }

        @Override
        public double surfaceY(Player p) {
            return surface;
        }

        @Override
        public boolean grounded(Player p) {
            return grounded;
        }

        @Override
        public boolean water(World w, double x, double y, double z) {
            return true;
        }

        void toWater() {
            swimming = true;
            grounded = false;
        }

        void toLand() {
            swimming = false;
            grounded = true;
        }
    }

    /** Rekin w pamięci: zapisuje klatki zamiast stawiać klocki. */
    static final class FakeSharks implements SharkBackend {
        final List<SharkChoreo.Frame> frames = new ArrayList<>();
        int spawns;
        int removes;
        boolean alive;

        @Override
        public Object spawn(World world, SharkChoreo.Frame frame) {
            spawns++;
            alive = true;
            frames.add(frame);
            return this;
        }

        @Override
        public void update(Object handle, SharkChoreo.Frame frame) {
            frames.add(frame);
        }

        @Override
        public void remove(Object handle) {
            removes++;
            alive = false;
        }

        @Override
        public int purge() {
            return 0;
        }
    }

    ServerMock server;
    KasynoPlugin plugin;
    World world;
    PlayerMock player;
    FakeWater water;
    FakeSharks sharks;
    SharkService service;

    @BeforeEach
    void setUp() {
        GuiManager.clickGapMs = 0;
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(KasynoPlugin.class);
        service = plugin.shark();
        water = new FakeWater();
        sharks = new FakeSharks();
        service.useProbe(water);
        service.useBackend(sharks);
        player = server.addPlayer();
        PlayerListener.prepare(plugin, player);
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(new Location(world, 0, 64, 0));
        // niezawodne losowanie: od 5 do 10 bloków od lądu szansa to 100%
        plugin.getConfig().set("shark.min-distance", 5);
        plugin.getConfig().set("shark.max-distance", 10);
        plugin.getConfig().set("shark.max-chance", 1.0);
        plugin.getConfig().set("shark.check-seconds", 1);
        plugin.getConfig().set("shark.warning-seconds", 3);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Gracz stoi na brzegu, wchodzi do wody i odpływa na `blocks` bloków (w krokach, jak przy prawdziwym pływaniu). */
    private void swimOut(int blocks) {
        water.toLand();
        service.scan();
        player.teleport(new Location(world, blocks, 64, 0));
        water.toWater();
    }

    private void tick(int n) {
        for (int i = 0; i < n; i++) service.tickAttacks();
    }

    private boolean runAttackToEnd() {
        for (int i = 0; i < 400 && service.attacking(player.getUniqueId()); i++) service.tickAttacks();
        return !service.attacking(player.getUniqueId());
    }

    // ---------- losowanie ----------

    @Test
    void farFromLandInDeepWaterTheSharkComes() {
        swimOut(50);
        service.scan();
        assertTrue(service.attacking(player.getUniqueId()));
        assertEquals(0, sharks.spawns, "rekin pojawia się dopiero w pierwszym ticku animacji");
        tick(1);
        assertEquals(1, sharks.spawns);
    }

    @Test
    void closeToLandNothingHappens() {
        swimOut(4);
        service.scan();
        assertFalse(service.attacking(player.getUniqueId()));
        assertEquals(0, service.distanceFromLand(player) - 4, 0.01);
    }

    @Test
    void shallowWaterIsSafeEvenFarFromLand() {
        swimOut(50);
        water.depth = 2;
        service.scan();
        assertFalse(service.attacking(player.getUniqueId()));
        water.depth = 3;
        service.scan();
        assertTrue(service.attacking(player.getUniqueId()));
    }

    @Test
    void standingOnLandResetsTheDistance() {
        swimOut(50);
        water.toLand();
        service.scan();
        assertEquals(0, service.distanceFromLand(player), 0.01, "wyspa po drodze zeruje odległość");
        water.toWater();
        service.scan();
        assertFalse(service.attacking(player.getUniqueId()));
    }

    @Test
    void aBoatIsNeitherLandNorSwimming() {
        swimOut(50);
        water.swimming = false;
        water.grounded = false;
        service.scan();
        assertFalse(service.attacking(player.getUniqueId()));
        assertEquals(50, service.distanceFromLand(player), 0.01, "płynięcie łodzią nie zeruje odległości");
        water.toWater();
        service.scan();
        assertTrue(service.attacking(player.getUniqueId()), "wyskok z łodzi daleko od brzegu jest ryzykowny");
    }

    @Test
    void theRiskGrowsWithDistanceInTheDefaultConfiguration() {
        plugin.getConfig().set("shark.min-distance", 20);
        plugin.getConfig().set("shark.max-distance", 150);
        plugin.getConfig().set("shark.max-chance", 0.2);
        assertEquals(0, service.risk(10), 1e-9);
        assertEquals(0, service.risk(20), 1e-9);
        assertTrue(service.risk(60) < service.risk(100));
        assertEquals(0.2, service.risk(150), 1e-9);
        assertEquals(0.2, service.risk(900), 1e-9);
    }

    @Test
    void protectedAndSpecialPlayersAreNeverEaten() {
        swimOut(50);
        player.setGameMode(GameMode.CREATIVE);
        service.scan();
        assertFalse(service.attacking(player.getUniqueId()));
        player.setGameMode(GameMode.SURVIVAL);
        player.addAttachment(plugin, SharkService.BYPASS, true);
        service.scan();
        assertFalse(service.attacking(player.getUniqueId()), "uprawnienie kasyno.shark.bypass chroni");
    }

    @Test
    void jailedPlayersAreIgnoredAndTheWholeFeatureCanBeSwitchedOff() {
        swimOut(50);
        plugin.getConfig().set("shark.enabled", false);
        service.scan();
        assertFalse(service.attacking(player.getUniqueId()));
        plugin.getConfig().set("shark.enabled", true);
        plugin.getConfig().set("prison.arrest-delay-ticks", 0);
        plugin.prison().jail(player.getUniqueId(), PrisonRules.Reason.CRIME);
        service.scan();
        assertFalse(service.attacking(player.getUniqueId()));
    }

    // ---------- przebieg ataku ----------

    @Test
    void theWholeAttackEatsThePlayerAndTakesHalfTheWallet() {
        plugin.eco().pd(player.getUniqueId()).wallet = 1001;
        plugin.eco().pd(player.getUniqueId()).bank = 5000;
        swimOut(50);
        service.scan();
        assertTrue(service.attacking(player.getUniqueId()));
        assertTrue(runAttackToEnd());
        assertEquals(501, plugin.eco().wallet(player.getUniqueId()), "zostaje połowa w górę: zabrano 500");
        assertEquals(5000, plugin.eco().pd(player.getUniqueId()).bank, "bank jest bezpieczny");
        assertTrue(player.isDead() || player.getHealth() <= 0, "gracz ginie");
        assertEquals(1, sharks.spawns);
        assertEquals(1, sharks.removes);
        assertFalse(sharks.alive, "po wszystkim rekina już nie ma");
        long phases = sharks.frames.stream().map(SharkChoreo.Frame::phase).distinct().count();
        assertEquals(4, phases, "approach, strike, bite i dive");
    }

    @Test
    void theVictimIsHeldAtTheMouthDuringTheBite() {
        swimOut(50);
        service.scan();
        boolean sawBite = false;
        for (int i = 0; i < 400 && service.attacking(player.getUniqueId()); i++) {
            service.tickAttacks();
            SharkChoreo.Frame f = sharks.frames.get(sharks.frames.size() - 1);
            if (f.phase() == SharkChoreo.Phase.BITE && !player.isDead()) {
                sawBite = true;
                var mouth = SharkChoreo.mouth(f);
                assertEquals(mouth.x, player.getLocation().getX(), 0.01, "gracz tkwi w paszczy");
                assertEquals(mouth.y, player.getLocation().getY(), 0.01);
            }
        }
        assertTrue(sawBite);
    }

    @Test
    void deathMessageNamesTheShark() {
        List<PlayerDeathEvent> seen = new ArrayList<>();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onDeath(PlayerDeathEvent e) {
                seen.add(e);
            }
        }, plugin);
        swimOut(50);
        service.scan();
        assertTrue(runAttackToEnd());
        if (seen.isEmpty()) {
            // atrapa nie zawsze sama wysyła zdarzenie śmierci przy setHealth(0): wtedy robimy to za nią
            PlayerDeathEvent e = new PlayerDeathEvent(player, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), new ArrayList<>(), 0, (net.kyori.adventure.text.Component) null, true);
            server.getPluginManager().callEvent(e);
            seen.add(e);
        }
        assertNotNull(seen.get(0).deathMessage());
        assertTrue(T.plain(seen.get(0).deathMessage()).contains("pożarty przez rekina"), String.valueOf(seen.get(0).deathMessage()));
    }

    @Test
    void anOrdinaryDeathKeepsTheNormalMessage() {
        PlayerDeathEvent e = new PlayerDeathEvent(player, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), new ArrayList<>(), 0, net.kyori.adventure.text.Component.text("zwykła"), true);
        server.getPluginManager().callEvent(e);
        assertEquals("zwykła", T.plain(e.deathMessage()));
    }

    @Test
    void leavingTheWaterDuringTheWarningSavesYou() {
        plugin.eco().pd(player.getUniqueId()).wallet = 1000;
        swimOut(50);
        service.scan();
        tick(30);
        assertTrue(service.attacking(player.getUniqueId()));
        water.toLand();
        tick(1);
        assertFalse(service.attacking(player.getUniqueId()), "rekin odpływa");
        assertFalse(sharks.alive);
        assertEquals(1000, plugin.eco().wallet(player.getUniqueId()));
        assertFalse(player.isDead());
        // chwilowy spokój: znowu do wody nie kończy się atakiem od razu
        water.toWater();
        service.scan();
        assertFalse(service.attacking(player.getUniqueId()), "po ucieczce rekin daje spokój");
    }

    @Test
    void afterTheWarningThereIsNoEscape() {
        plugin.eco().pd(player.getUniqueId()).wallet = 1000;
        swimOut(50);
        service.scan();
        int warning = config().warningTicks();
        tick(warning + 3);
        assertEquals(SharkChoreo.Phase.STRIKE, sharks.frames.get(sharks.frames.size() - 1).phase());
        water.toLand();
        assertTrue(runAttackToEnd());
        assertEquals(500, plugin.eco().wallet(player.getUniqueId()), "rzut już się zaczął, ucieczka za późno");
    }

    private SharkRules.Config config() {
        return service.config();
    }

    @Test
    void thereIsNoSecondSharkWhileOneIsAttacking() {
        swimOut(50);
        service.scan();
        assertTrue(service.attacking(player.getUniqueId()));
        assertFalse(service.start(player, false));
        service.scan();
        tick(1);
        assertEquals(1, sharks.spawns);
    }

    @Test
    void afterAnAttackTheSharkLeavesYouAloneForAWhile() {
        swimOut(50);
        assertTrue(service.start(player, true));
        assertTrue(runAttackToEnd());
        service.scan();
        assertFalse(service.attacking(player.getUniqueId()), "odpoczynek od rekinów po ataku");
    }

    @Test
    void withoutTheCooldownTheNextAttackCanComeAtOnce() {
        plugin.getConfig().set("shark.cooldown-seconds", 0);
        swimOut(50);
        assertTrue(service.start(player, true));
        assertTrue(runAttackToEnd());
        service.scan();
        assertTrue(service.attacking(player.getUniqueId()));
    }

    @Test
    void quittingDuringTheBiteDoesNotSaveYourMoney() {
        plugin.eco().pd(player.getUniqueId()).wallet = 2000;
        swimOut(50);
        service.scan();
        int warning = config().warningTicks();
        tick(warning + SharkChoreo.STRIKE_TICKS + 3);
        assertEquals(SharkChoreo.Phase.BITE, sharks.frames.get(sharks.frames.size() - 1).phase());
        service.onQuit(new PlayerQuitEvent(player, net.kyori.adventure.text.Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertEquals(1000, plugin.eco().wallet(player.getUniqueId()));
        assertFalse(service.attacking(player.getUniqueId()));
        assertFalse(sharks.alive);
    }

    @Test
    void quittingDuringTheWarningCostsNothing() {
        plugin.eco().pd(player.getUniqueId()).wallet = 2000;
        swimOut(50);
        service.scan();
        tick(20);
        service.onQuit(new PlayerQuitEvent(player, net.kyori.adventure.text.Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertEquals(2000, plugin.eco().wallet(player.getUniqueId()));
        assertFalse(sharks.alive);
    }

    @Test
    void aWalletOfZeroLosesNothingButTheLife() {
        plugin.eco().pd(player.getUniqueId()).wallet = 0;
        swimOut(50);
        service.scan();
        assertTrue(runAttackToEnd());
        assertEquals(0, plugin.eco().wallet(player.getUniqueId()));
        assertTrue(player.isDead() || player.getHealth() <= 0);
    }

    // ---------- komendy ----------

    @Test
    void anAdminAttackOnSomeoneStandingOnLandRunsToTheEnd() {
        PlayerMock admin = server.addPlayer();
        admin.addAttachment(plugin, "kasyno.admin", true);
        admin.performCommand("kasyno rekin test " + player.getName());
        tick(config().warningTicks() + 5);
        assertTrue(service.attacking(player.getUniqueId()), "nie ma jak uciec z wody, której nie ma: pokaz leci dalej");
        assertTrue(runAttackToEnd());
        assertTrue(sharks.frames.stream().anyMatch(f -> f.phase() == SharkChoreo.Phase.BITE));
    }

    @Test
    void theTestAttackIsHarmless() {
        plugin.eco().pd(player.getUniqueId()).wallet = 1000;
        PlayerMock admin = server.addPlayer();
        admin.addAttachment(plugin, "kasyno.admin", true);
        admin.performCommand("kasyno rekin test " + player.getName());
        assertTrue(service.attacking(player.getUniqueId()));
        assertTrue(runAttackToEnd());
        assertEquals(1000, plugin.eco().wallet(player.getUniqueId()), "test nie zabiera pieniędzy");
        assertFalse(player.isDead());
        assertTrue(player.getHealth() > 0);
    }

    @Test
    void theRealAttackCommandKills() {
        plugin.eco().pd(player.getUniqueId()).wallet = 1000;
        PlayerMock admin = server.addPlayer();
        admin.addAttachment(plugin, "kasyno.admin", true);
        admin.performCommand("kasyno rekin zaatakuj " + player.getName());
        assertTrue(service.attacking(player.getUniqueId()));
        assertTrue(runAttackToEnd());
        assertEquals(500, plugin.eco().wallet(player.getUniqueId()));
        assertTrue(player.isDead() || player.getHealth() <= 0);
    }

    @Test
    void ordinaryPlayersCannotStartAttacks() {
        PlayerMock other = server.addPlayer();
        other.performCommand("kasyno rekin zaatakuj " + player.getName());
        assertFalse(service.attacking(player.getUniqueId()));
        other.performCommand("kasyno rekin info");
        String all = drain(other);
        assertTrue(all.contains("Rekiny"), all);
    }

    @Test
    void adminCanSwitchSharksOffAndOnAndItEndsTheRunningAttacks() {
        PlayerMock admin = server.addPlayer();
        admin.addAttachment(plugin, "kasyno.admin", true);
        swimOut(50);
        service.scan();
        assertTrue(service.attacking(player.getUniqueId()));
        admin.performCommand("kasyno rekin wylacz");
        assertFalse(service.enabled());
        assertFalse(service.attacking(player.getUniqueId()));
        water.toWater();
        service.scan();
        assertFalse(service.attacking(player.getUniqueId()));
        admin.performCommand("kasyno rekin wlacz");
        assertTrue(service.enabled());
    }

    @Test
    void theShowCommandSpawnsAndRemovesAModelWithoutAttacking() {
        PlayerMock admin = server.addPlayer();
        admin.addAttachment(plugin, "kasyno.admin", true);
        admin.teleport(new Location(world, 0, 64, 0));
        admin.performCommand("kasyno rekin pokaz");
        assertEquals(1, sharks.spawns);
        assertTrue(sharks.alive);
        server.getScheduler().performTicks(205);
        assertFalse(sharks.alive, "po dziesięciu sekundach model znika");
        assertFalse(service.attacking(admin.getUniqueId()));
    }

    // ---------- prawdziwe klocki ----------

    @Test
    void theRealBackendBuildsTheSharkOutOfTaggedBlockDisplays() {
        BukkitSharkBackend real = new BukkitSharkBackend(plugin);
        SharkChoreo.Frame f = SharkChoreo.demo(0, 0, 64, 0, 4);
        Object handle = real.spawn(world, f);
        List<BlockDisplay> displays = new ArrayList<>(world.getEntitiesByClass(BlockDisplay.class));
        assertEquals(SharkModel.PARTS.size(), displays.size());
        NamespacedKey tag = new NamespacedKey(plugin, "shark");
        for (BlockDisplay d : displays) assertTrue(d.getPersistentDataContainer().has(tag));
        assertTrue(displays.stream().anyMatch(d -> d.getBlock().getMaterial() == Material.RED_CONCRETE));
        real.update(handle, SharkChoreo.demo(10, 0, 64, 0, 4));
        assertEquals(SharkModel.PARTS.size(), world.getEntitiesByClass(BlockDisplay.class).size());
        real.remove(handle);
        assertEquals(0, world.getEntitiesByClass(BlockDisplay.class).size());
        real.spawn(world, f);
        assertEquals(SharkModel.PARTS.size(), real.purge(), "zabłąkane bryłki znikają");
        assertEquals(0, world.getEntitiesByClass(BlockDisplay.class).size());
    }

    @Test
    void theBlockProbeReadsWaterDepthSurfaceAndGround() {
        Location pool = new Location(world, 100, 64, 100);
        for (int x = 98; x <= 102; x++) {
            for (int z = 98; z <= 102; z++) {
                world.getBlockAt(x, 60, z).setType(Material.STONE);
                for (int y = 61; y <= 64; y++) world.getBlockAt(x, y, z).setType(Material.WATER);
            }
        }
        for (int x = 108; x <= 112; x++) for (int z = 98; z <= 102; z++) world.getBlockAt(x, 63, z).setType(Material.STONE);
        player.teleport(pool);
        assertTrue(WaterProbe.BLOCKS.swimming(player));
        assertEquals(4, WaterProbe.BLOCKS.depth(player));
        assertEquals(64.89, WaterProbe.BLOCKS.surfaceY(player), 0.01);
        assertFalse(WaterProbe.BLOCKS.grounded(player));
        assertTrue(WaterProbe.BLOCKS.water(world, 100.5, 62.5, 100.5));
        player.teleport(new Location(world, 110.5, 64, 100.5));
        assertFalse(WaterProbe.BLOCKS.swimming(player));
        assertTrue(WaterProbe.BLOCKS.grounded(player));
        assertEquals(0, WaterProbe.BLOCKS.depth(player));
    }

    private static String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) sb.append(m).append('\n');
        return sb.toString();
    }
}
