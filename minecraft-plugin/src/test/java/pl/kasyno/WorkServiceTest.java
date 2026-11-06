package pl.kasyno;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kasyno.gui.GuiManager;
import pl.kasyno.gui.Screens;
import pl.kasyno.gui.T;
import pl.kasyno.show.Stage.P;
import pl.kasyno.work.BukkitNodeBackend;
import pl.kasyno.work.JobDef;
import pl.kasyno.work.JobDef.NodeType;
import pl.kasyno.work.Jobs;
import pl.kasyno.work.NodeBackend;
import pl.kasyno.work.Terrain;
import pl.kasyno.work.WorkService;
import pl.kasyno.work.WorkService.End;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prace w terenie: pełne rundy z fałszywymi celami (pieniądze, premie, przerwy, sprzątanie) oraz prawdziwe encje na atrapie
 * serwera (NPC, okno pracy, kliknięcia w świecące cele).
 */
class WorkServiceTest {
    static {
        System.setProperty("kasyno.test", "1");
    }

    /** Cele w pamięci zamiast encji: widać, co jest postawione, co odkryte i ile razy się coś ruszyło. */
    static final class FakeBackend implements NodeBackend {
        static final class Fake {
            final UUID id = UUID.randomUUID();
            final NodeType type;
            P pos;
            boolean visible;

            Fake(NodeType type, P pos, boolean visible) {
                this.type = type;
                this.pos = pos;
                this.visible = visible;
            }
        }

        final Map<UUID, Fake> live = new LinkedHashMap<>();
        int spawned;
        int moves;
        int spins;
        int sparkles;
        int reveals;
        final List<String> popups = new ArrayList<>();
        boolean purged;

        @Override
        public Spawned spawn(Player owner, NodeType type, P at, boolean visible) {
            Fake f = new Fake(type, at, visible);
            live.put(f.id, f);
            spawned++;
            return new Spawned(f, f.id);
        }

        @Override
        public void reveal(Player owner, Object handle) {
            ((Fake) handle).visible = true;
            reveals++;
        }

        @Override
        public void move(Object handle, P at) {
            ((Fake) handle).pos = at;
            moves++;
        }

        @Override
        public void spin(Object handle, int step) {
            spins++;
        }

        @Override
        public void remove(Object handle) {
            live.remove(((Fake) handle).id);
        }

        @Override
        public void sparkle(Player owner, P at, boolean rare) {
            sparkles++;
        }

        @Override
        public void popup(Player owner, String miniMessage, P at) {
            popups.add(miniMessage);
        }

        @Override
        public void purge() {
            purged = true;
        }
    }

    private static Terrain terrain(Predicate<P> ground, Predicate<P> water) {
        return new Terrain() {
            @Override
            public P ground(double x, double z) {
                P p = new P(Math.floor(x) + 0.5, 64, Math.floor(z) + 0.5);
                return ground.test(p) ? p : null;
            }

            @Override
            public P water(double x, double z) {
                P p = new P(Math.floor(x) + 0.5, 63.9, Math.floor(z) + 0.5);
                return water.test(p) ? p : null;
            }
        };
    }

    private static final P ORIGIN = new P(0, 64, 0);

    ServerMock server;
    KasynoPlugin plugin;
    World world;
    PlayerMock player;
    UUID id;
    FakeBackend backend;
    Terrain flat;
    Terrain current;
    WorkService svc;

    @BeforeEach
    void setUp() {
        GuiManager.clickGapMs = 0;
        server = MockBukkit.mock();
        plugin = MockBukkit.load(KasynoPlugin.class);
        world = server.addSimpleWorld("world");
        player = server.addPlayer();
        PlayerListener.prepare(plugin, player);
        player.teleport(new Location(world, 0, 64, 0));
        id = player.getUniqueId();
        var d = plugin.eco().pd(id);
        d.wallet = 0;
        d.level = 50; // żeby premie za poziom nie mieszały w saldzie
        backend = new FakeBackend();
        flat = terrain(p -> true, p -> false);
        current = flat;
        svc = new WorkService(plugin, backend, w -> current);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private WorkService.Site site(int radius) {
        return new WorkService.Site("world", ORIGIN, radius, "test");
    }

    private long wallet() {
        return plugin.eco().wallet(id);
    }

    private void approach(P pos) {
        player.teleport(new Location(world, pos.x() + 1, pos.y(), pos.z()));
    }

    private JobDef job(String key) {
        return Jobs.find(key);
    }

    /** Podchodzi do każdego celu z listy i go klika (zbieranie: wszystkie cele stoją naraz). */
    private void clickAll() {
        for (WorkService.NodeInfo n : svc.nodes(id)) {
            approach(n.pos());
            assertTrue(svc.click(player, n.clickId()));
        }
    }

    /** Dostawa i skarb: kolejny cel pojawia się dopiero po poprzednim. */
    private int clickOneByOne() {
        int clicks = 0;
        while (svc.active(id) && clicks < 50) {
            List<WorkService.NodeInfo> ns = svc.nodes(id);
            WorkService.NodeInfo n = ns.get(0);
            if (!n.visible()) svc.tick(); // skarb odkrywa się dopiero z bliska
            approach(n.pos());
            svc.tick();
            n = svc.nodes(id).get(0);
            assertTrue(svc.click(player, n.clickId()));
            clicks++;
        }
        return clicks;
    }

    private void run(int ticks) {
        for (int i = 0; i < ticks && svc.active(id); i++) svc.tick();
    }

    // ---------- pełne rundy ----------

    @Test
    void forestRoundPaysPerMushroomAndAddsTheBonusAtTheEnd() {
        JobDef j = job("lesnik");
        assertNull(svc.start(player, j, site(40)));
        assertTrue(svc.active(id));
        assertEquals(10, backend.live.size());
        assertTrue(backend.live.values().stream().allMatch(f -> f.visible), "grzyby widać od razu");
        for (var f : backend.live.values()) assertTrue(f.pos.flat(ORIGIN) <= 41, "w promieniu pracy");

        long before = wallet();
        List<Long> deltas = new ArrayList<>();
        for (WorkService.NodeInfo n : svc.nodes(id)) {
            long w0 = wallet();
            approach(n.pos());
            assertTrue(svc.click(player, n.clickId()));
            deltas.add(wallet() - w0);
            if (deltas.size() < 10) assertTrue(deltas.get(deltas.size() - 1) >= n.type().min() && deltas.get(deltas.size() - 1) <= n.type().max(), n.type().id() + " płaci w swoim przedziale");
        }
        assertFalse(svc.active(id));
        assertTrue(backend.live.isEmpty(), "po rundzie nic nie zostaje w świecie");
        WorkService.Summary sum = svc.lastSummary(id);
        assertEquals(End.DONE, sum.how());
        assertEquals(10, sum.collected());
        assertEquals(10, sum.goal());
        assertEquals(wallet() - before, sum.earned(), "saldo = to, co podsumowanie");
        assertEquals(Math.round((sum.earned() - sum.bonus()) * 0.20), sum.bonus(), "premia 20% za komplet");
        assertTrue(sum.bonus() > 0);
        assertEquals(10, backend.popups.size(), "napis z kwotą nad każdym zebranym");
        assertTrue(plugin.eco().cooldownLeft(id, "work:lesnik") > 0, "przerwa po rundzie");
        assertTrue(plugin.eco().cooldownLeft(id, "work:lesnik") <= 150_000);
        assertTrue(svc.start(player, j, site(40)).contains("Następna runda"), "druga runda od razu nie ruszy");
        assertTrue(backend.live.isEmpty());
    }

    @Test
    void miningNeedsAPickaxeFromTheShop() {
        JobDef j = job("gornik");
        String err = svc.start(player, j, site(40));
        assertNotNull(err);
        assertTrue(err.contains("Potrzebujesz narzędzia"), err);
        assertFalse(svc.active(id));
        assertTrue(backend.live.isEmpty());
        assertEquals(0, plugin.eco().cooldownLeft(id, "work:gornik"), "nieudany start nie zakłada przerwy");

        plugin.eco().addItem(id, "kilof", 1);
        assertNull(svc.start(player, j, site(40)));
        assertEquals(10, backend.live.size());
        clickAll();
        assertFalse(svc.active(id));
        assertEquals(End.DONE, svc.lastSummary(id).how());
        assertTrue(wallet() >= 10 * 15, "co najmniej węgiel za każdą rudę");
        assertEquals(1, plugin.eco().qty(id, "kilof"), "narzędzie nie zużywa się przy pracy w terenie");
    }

    @Test
    void whenTimeRunsOutYouKeepWhatYouFoundButGetNoBonus() {
        JobDef j = job("lesnik");
        assertNull(svc.start(player, j, site(40)));
        List<WorkService.NodeInfo> ns = svc.nodes(id);
        for (int i = 0; i < 3; i++) {
            approach(ns.get(i).pos());
            assertTrue(svc.click(player, ns.get(i).clickId()));
        }
        approach(ORIGIN);
        long paid = wallet();
        assertTrue(paid >= 3 * 20);
        run(700);
        assertFalse(svc.active(id), "po 150 s runda się kończy");
        WorkService.Summary sum = svc.lastSummary(id);
        assertEquals(End.TIMEOUT, sum.how());
        assertEquals(3, sum.collected());
        assertEquals(0, sum.bonus());
        assertEquals(paid, wallet(), "po czasie nic nie dochodzi");
        assertEquals(paid, sum.earned());
        assertTrue(backend.live.isEmpty(), "niezebrane cele znikają");
        assertTrue(plugin.eco().cooldownLeft(id, "work:lesnik") > 0);
    }

    @Test
    void cancellingKeepsPayForFoundTargetsAndStartsTheBreak() {
        assertNull(svc.start(player, job("sprzatacz"), site(30)));
        assertEquals(12, backend.live.size());
        List<WorkService.NodeInfo> ns = svc.nodes(id);
        for (int i = 0; i < 2; i++) {
            approach(ns.get(i).pos());
            svc.click(player, ns.get(i).clickId());
        }
        long paid = wallet();
        assertTrue(paid >= 2 * 8);
        assertTrue(svc.cancel(player));
        assertFalse(svc.cancel(player), "druga próba nie ma czego przerywać");
        assertEquals(paid, wallet());
        assertEquals(End.CANCEL, svc.lastSummary(id).how());
        assertTrue(backend.live.isEmpty());
        assertTrue(plugin.eco().cooldownLeft(id, "work:sprzatacz") > 0, "anulowanie nie pozwala losować układu od nowa");
    }

    @Test
    void leavingTheServerOrDisablingTheJobCleansUp() {
        assertNull(svc.start(player, job("lesnik"), site(40)));
        svc.quit(player);
        assertFalse(svc.active(id));
        assertTrue(backend.live.isEmpty());
        assertTrue(plugin.eco().cooldownLeft(id, "work:lesnik") > 0);

        plugin.eco().clearCooldown(id, "work:lesnik");
        assertNull(svc.start(player, job("lesnik"), site(40)));
        WorkService.NodeInfo first = svc.nodes(id).get(0);
        approach(first.pos());
        svc.click(player, first.clickId());
        long paid = wallet();
        svc.disable();
        assertFalse(svc.active(id));
        assertTrue(backend.live.isEmpty());
        assertTrue(backend.purged, "przy wyłączeniu sprzątamy też zapomniane cele");
        assertEquals(paid, wallet(), "wypłacone zostaje");
        assertEquals(End.SHUTDOWN, svc.lastSummary(id).how());
        assertEquals(0, plugin.eco().cooldownLeft(id, "work:lesnik"), "wyłączenie serwera nie karze gracza przerwą");
    }

    @Test
    void walkingFarAwayOrChangingWorldEndsTheRound() {
        assertNull(svc.start(player, job("lesnik"), site(40)));
        player.teleport(new Location(world, 400, 64, 400));
        svc.tick();
        assertFalse(svc.active(id));
        assertEquals(End.LEASH, svc.lastSummary(id).how());
        assertTrue(backend.live.isEmpty());

        plugin.eco().clearCooldown(id, "work:lesnik");
        player.teleport(new Location(world, 0, 64, 0));
        assertNull(svc.start(player, job("lesnik"), site(40)));
        World other = server.addSimpleWorld("other");
        player.teleport(new Location(other, 0, 64, 0));
        svc.tick();
        assertFalse(svc.active(id));
        assertEquals(End.LEASH, svc.lastSummary(id).how());
        assertTrue(backend.live.isEmpty());
    }

    @Test
    void youCannotStartFarFromTheNpcOrInTheWrongWorldOrWithTwoJobsAtOnce() {
        player.teleport(new Location(world, 300, 64, 300));
        String far = svc.start(player, job("lesnik"), site(40));
        assertNotNull(far);
        assertTrue(far.contains("za daleko"), far);
        assertFalse(svc.active(id));

        player.teleport(new Location(world, 0, 64, 0));
        String wrong = svc.start(player, job("lesnik"), new WorkService.Site("nether", ORIGIN, 40, "test"));
        assertNotNull(wrong);
        assertTrue(wrong.contains("innym świecie"), wrong);
        assertNotNull(svc.start(player, job("lesnik"), null), "bez NPC nie ma pracy");

        assertNull(svc.start(player, job("lesnik"), site(40)));
        plugin.eco().addItem(id, "kilof", 1);
        String twice = svc.start(player, job("gornik"), site(40));
        assertNotNull(twice);
        assertTrue(twice.contains("Masz już pracę"), twice);
        assertEquals(10, backend.live.size(), "druga próba nie postawiła nic dodatkowo");
    }

    // ---------- kliknięcia ----------

    @Test
    void aClickFromTooFarAwayDoesNothingAndNeitherDoesTheSecondClickOnTheSameTarget() {
        assertNull(svc.start(player, job("lesnik"), site(40)));
        WorkService.NodeInfo far = svc.nodes(id).stream().max((a, b) -> Double.compare(a.pos().flat(ORIGIN), b.pos().flat(ORIGIN))).orElseThrow();
        approach(ORIGIN);
        assertTrue(far.pos().flat(ORIGIN) > 9, "najdalszy cel jest poza zasięgiem ręki");
        long w0 = wallet();
        assertTrue(svc.click(player, far.clickId()), "to jest cel pracy, więc kliknięcie jest 'zużyte'");
        assertEquals(w0, wallet());
        assertEquals(10, backend.live.size());

        approach(far.pos());
        assertTrue(svc.click(player, far.clickId()));
        long once = wallet();
        assertTrue(once > w0);
        assertFalse(svc.click(player, far.clickId()), "po zebraniu to już nie jest cel");
        assertEquals(once, wallet(), "drugi raz nie płaci");
        assertEquals(9, backend.live.size());
        assertFalse(svc.click(player, UUID.randomUUID()), "obce pole kliknięcia nie jest celem pracy");
    }

    @Test
    void otherPlayersCannotTakeYourTargets() {
        assertNull(svc.start(player, job("lesnik"), site(40)));
        PlayerMock other = server.addPlayer();
        PlayerListener.prepare(plugin, other);
        plugin.eco().pd(other.getUniqueId()).wallet = 0;
        WorkService.NodeInfo n = svc.nodes(id).get(0);
        other.teleport(new Location(world, n.pos().x() + 1, n.pos().y(), n.pos().z()));
        assertTrue(svc.click(other, n.clickId()), "kliknięcie zużyte");
        assertEquals(0, plugin.eco().wallet(other.getUniqueId()));
        assertEquals(10, backend.live.size(), "cel nadal czeka na właściciela");
        assertFalse(svc.active(other.getUniqueId()));
    }

    @Test
    void twoPlayersWorkTheSameForestIndependently() {
        PlayerMock other = server.addPlayer();
        PlayerListener.prepare(plugin, other);
        plugin.eco().pd(other.getUniqueId()).wallet = 0;
        plugin.eco().pd(other.getUniqueId()).level = 50;
        other.teleport(new Location(world, 2, 64, 2));
        assertNull(svc.start(player, job("lesnik"), site(40)));
        assertNull(svc.start(other, job("lesnik"), site(40)));
        assertEquals(20, backend.live.size());
        assertEquals(10, svc.nodes(id).size());
        assertEquals(10, svc.nodes(other.getUniqueId()).size());
        clickAll();
        assertEquals(10, backend.live.size(), "runda jednego gracza nie rusza celów drugiego");
        assertTrue(svc.active(other.getUniqueId()));
        assertEquals(0, plugin.eco().wallet(other.getUniqueId()));
    }

    // ---------- ryby ----------

    @Test
    void fishNeedWaterAndARod() {
        plugin.eco().addItem(id, "wedka", 1);
        current = terrain(p -> true, p -> false);
        String err = svc.start(player, job("rybak"), site(40));
        assertNotNull(err);
        assertTrue(err.contains("nie ma wody"), err);
        assertFalse(svc.active(id));
        assertTrue(backend.live.isEmpty());
        assertEquals(0, plugin.eco().cooldownLeft(id, "work:rybak"), "brak wody nie zakłada przerwy");

        plugin.eco().takeItem(id, "wedka", 1);
        current = terrain(p -> false, p -> p.flat(new P(15, 64, 0)) <= 10);
        String noRod = svc.start(player, job("rybak"), site(40));
        assertNotNull(noRod);
        assertTrue(noRod.contains("Potrzebujesz narzędzia"), noRod);
    }

    @Test
    void fishSwimStayInTheLakeEscapeAndAreReplaced() {
        plugin.eco().addItem(id, "wedka", 1);
        P lake = new P(15, 64, 0);
        current = terrain(p -> false, p -> p.flat(lake) <= 10);
        assertNull(svc.start(player, job("rybak"), site(40)));
        assertEquals(5, backend.live.size());
        List<P> start = backend.live.values().stream().map(f -> f.pos).toList();

        for (int i = 0; i < 20; i++) svc.tick();
        assertTrue(backend.moves > 0, "ryby pływają");
        assertTrue(backend.live.values().stream().anyMatch(f -> !start.contains(f.pos)));
        for (var f : backend.live.values()) assertTrue(f.pos.flat(lake) <= 10.8, "ryba nie wypływa na ląd");

        int spawnedBefore = backend.spawned;
        for (int i = 0; i < 90; i++) svc.tick(); // życie ryby to 12-17 s, więc część ucieka
        assertTrue(backend.spawned > spawnedBefore, "uciekające ryby są zastępowane nowymi");
        assertTrue(backend.live.size() >= 4, "stado nie wyczerpuje się: " + backend.live.size());
        for (var f : backend.live.values()) assertTrue(f.pos.flat(lake) <= 10.8);

        var fish = backend.live.values().iterator().next();
        long w0 = wallet();
        approach(fish.pos);
        assertTrue(svc.click(player, fish.id));
        long pay = wallet() - w0;
        assertTrue(pay >= fish.type.min() && pay <= fish.type.max(), "wypłata za " + fish.type.id() + ": " + pay);
    }

    @Test
    void catchingTwelveFishCompletesTheRound() {
        plugin.eco().addItem(id, "wedka", 1);
        P lake = new P(15, 64, 0);
        current = terrain(p -> false, p -> p.flat(lake) <= 10);
        assertNull(svc.start(player, job("rybak"), site(40)));
        int caught = 0;
        while (svc.active(id) && caught < 40) {
            var f = backend.live.values().iterator().next();
            approach(f.pos);
            assertTrue(svc.click(player, f.id));
            caught++;
        }
        assertEquals(12, caught, "dokładnie tyle połowów do kompletu");
        assertEquals(End.DONE, svc.lastSummary(id).how());
        assertEquals(12, svc.lastSummary(id).collected());
        assertTrue(backend.live.isEmpty());
    }

    // ---------- kurier i skarb ----------

    @Test
    void courierDeliversInOrderAndGetsTipsForSpeed() {
        assertNull(svc.start(player, job("kurier"), site(70)));
        assertEquals(1, backend.live.size(), "tylko bieżący adres");
        long before = wallet();
        int clicks = 0;
        long last = before;
        while (svc.active(id) && clicks < 10) {
            assertEquals(1, svc.nodes(id).size());
            WorkService.NodeInfo n = svc.nodes(id).get(0);
            approach(n.pos());
            assertTrue(svc.click(player, n.clickId()));
            clicks++;
            long delta = wallet() - last;
            if (svc.active(id)) assertTrue(delta >= 110 && delta <= 213, "dostawa " + clicks + ": " + delta);
            last = wallet();
        }
        assertEquals(3, clicks);
        WorkService.Summary sum = svc.lastSummary(id);
        assertEquals(End.DONE, sum.how());
        assertEquals(3, sum.collected());
        assertEquals(Math.round((sum.earned() - sum.bonus()) * 0.30), sum.bonus(), "premia 30% za komplet");
        assertTrue(backend.popups.stream().anyMatch(s -> s.contains("napiwek")), "szybka dostawa daje napiwek");
        assertTrue(backend.live.isEmpty());
    }

    @Test
    void treasureStaysHiddenUntilYouAreCloseThenPaysOnce() {
        assertNull(svc.start(player, job("poszukiwacz"), site(70)));
        assertEquals(1, backend.live.size());
        WorkService.NodeInfo hidden = svc.nodes(id).get(0);
        assertFalse(hidden.visible(), "skarbu nie widać z daleka");
        assertTrue(hidden.pos().flat(ORIGIN) >= 24);
        svc.tick();
        assertFalse(svc.nodes(id).get(0).visible(), "z daleka nadal ukryty");
        assertEquals(0, backend.reveals);

        long w0 = wallet();
        approach(hidden.pos());
        assertTrue(svc.click(player, hidden.clickId()), "ukrytego skarbu nie da się kliknąć");
        assertEquals(w0, wallet());

        svc.tick();
        assertTrue(svc.nodes(id).get(0).visible(), "z bliska skrzynia zaczyna świecić");
        assertEquals(1, backend.reveals);
        assertTrue(svc.click(player, hidden.clickId()));
        long pay = wallet() - w0;
        assertTrue(pay >= 500 && pay <= 900, "skarb: " + pay);
        assertFalse(svc.active(id));
        assertEquals(End.DONE, svc.lastSummary(id).how());
        assertEquals(0, svc.lastSummary(id).bonus(), "skarb nie ma premii");
        assertTrue(backend.live.isEmpty());
        assertTrue(plugin.eco().cooldownLeft(id, "work:poszukiwacz") > 200_000);
    }

    @Test
    void roundsOfEveryJobFinishCleanly() {
        plugin.eco().addItem(id, "kilof", 1);
        plugin.eco().addItem(id, "wedka", 1);
        P lake = new P(15, 64, 0);
        current = terrain(p -> true, p -> p.flat(lake) <= 10);
        // woda i ziemia naraz: rybak widzi jezioro, reszta ziemię
        for (JobDef j : Jobs.ALL) {
            plugin.eco().clearCooldown(id, "work:" + j.id());
            player.teleport(new Location(world, 0, 64, 0));
            long w0 = wallet();
            assertNull(svc.start(player, j, site(j.radius())), j.id());
            switch (j.kind()) {
                case COLLECT -> clickAll();
                case WATER -> {
                    int guard = 0;
                    while (svc.active(id) && guard++ < 40) {
                        var f = backend.live.values().iterator().next();
                        approach(f.pos);
                        svc.click(player, f.id);
                    }
                }
                default -> clickOneByOne();
            }
            assertFalse(svc.active(id), j.id() + " zakończona");
            assertTrue(backend.live.isEmpty(), j.id() + ": nic nie zostało w świecie");
            assertEquals(End.DONE, svc.lastSummary(id).how(), j.id());
            long earned = wallet() - w0;
            assertEquals(svc.lastSummary(id).earned(), earned, j.id() + ": saldo zgadza się z podsumowaniem");
            double maxPossible = j.types().stream().mapToLong(NodeType::max).max().orElse(0) * j.quota() * (1 + j.completionBonus()) * 1.26;
            assertTrue(earned >= j.types().stream().mapToLong(NodeType::min).min().orElse(0) * j.goal(), j.id() + ": " + earned);
            assertTrue(earned <= maxPossible, j.id() + ": " + earned + " > " + maxPossible);
        }
    }

    // ---------- miejsce pracy ----------

    @Test
    void workSiteComesFromTheNearestNpcOrTheAdminOrNothing() {
        JobDef j = job("lesnik");
        player.setOp(false);
        assertNull(svc.siteFor(player, j), "bez NPC zwykły gracz nie ma gdzie pracować");

        player.setOp(true);
        WorkService.Site admin = svc.siteFor(player, j);
        assertNotNull(admin);
        assertEquals("admin", admin.source());
        assertEquals(j.radius(), admin.radius());
        player.setOp(false);

        Villager npc = plugin.npcs().spawn(new Location(world, 0, 64, 0), "lesnik", 0, 25);
        assertNotNull(npc);
        assertEquals(25, plugin.npcs().radiusOf(npc));
        WorkService.Site site = svc.siteFor(player, j);
        assertNotNull(site);
        assertEquals("npc", site.source());
        assertEquals(25, site.radius());
        assertEquals("world", site.world());

        // inny typ NPC nie liczy się jako leśniczy
        player.teleport(new Location(world, 200, 64, 0));
        Villager miner = plugin.npcs().spawn(new Location(world, 200, 64, 0), "gornik", 0);
        assertEquals(0, plugin.npcs().radiusOf(miner));
        WorkService.Site far = svc.siteFor(player, j);
        assertNotNull(far, "gracz daleko od leśniczego dostaje jego miejsce, żeby okno mogło mu je pokazać");
        assertEquals(0, far.center().flat(ORIGIN), 1e-9);
        String tooFar = svc.start(player, j, far);
        assertTrue(tooFar.contains("za daleko"), tooFar);
        assertEquals(job("gornik").radius(), svc.siteFor(player, job("gornik")).radius(), "domyślny promień pracy");
    }

    @Test
    void npcRadiusIsClampedAndOnlyKeptForJobNpcs() {
        Villager big = plugin.npcs().spawn(new Location(world, 0, 64, 0), "rybak", 0, 500);
        assertEquals(WorkService.MAX_RADIUS, plugin.npcs().radiusOf(big));
        Villager tiny = plugin.npcs().spawn(new Location(world, 5, 64, 0), "rybak", 0, 2);
        assertEquals(WorkService.MIN_RADIUS, plugin.npcs().radiusOf(tiny));
        Villager bank = plugin.npcs().spawn(new Location(world, 9, 64, 0), "bank", 0, 40);
        assertEquals(0, plugin.npcs().radiusOf(bank), "bankier nie ma promienia pracy");
        plugin.npcs().setRadius(big, 0);
        assertEquals(0, plugin.npcs().radiusOf(big));
        assertEquals("Rybak", plugin.npcs().labelOf("rybak"));
        assertEquals("Leśniczy", plugin.npcs().labelOf("lesnik"));
        assertEquals("Kierownik kopalni", plugin.npcs().labelOf("gornik"));
        for (JobDef j : Jobs.ALL) assertTrue(plugin.npcs().types().contains(j.id()), j.id() + " jest typem NPC");
    }

    // ---------- okna, komendy, NPC ----------

    private String windowName() {
        var g = plugin.guis().current(player);
        return g == null ? null : g.getClass().getSimpleName();
    }

    private String slotName(int slot) {
        var it = player.getOpenInventory().getTopInventory().getItem(slot);
        return it == null || !it.hasItemMeta() || it.getItemMeta().displayName() == null ? "" : T.plain(it.getItemMeta().displayName());
    }

    private void rightClick(Entity e) {
        server.getPluginManager().callEvent(new PlayerInteractEntityEvent(player, e, EquipmentSlot.HAND));
        server.getScheduler().performTicks(2);
    }

    @Test
    void everyJobIsAScreenButNotAPackBackgroundScreen() {
        for (JobDef j : Jobs.ALL) {
            assertTrue(Screens.exists(j.id()), j.id());
            assertTrue(Screens.allKeys().contains(j.id()), j.id());
            assertFalse(Screens.keys().contains(j.id()), j.id() + ": prace nie mają własnego tła w paczce");
            assertTrue(Screens.open(plugin, player, j.id()), j.id());
            server.getScheduler().performTicks(2);
            assertEquals("WorkGui", windowName(), j.id());
            player.closeInventory();
            server.getScheduler().performTicks(2);
        }
    }

    @Test
    void earnWindowLeadsToTheJobListAndEachJobToItsWindow() {
        assertTrue(Screens.open(plugin, player, "zarobki"));
        server.getScheduler().performTicks(2);
        player.simulateInventoryClick(25);
        server.getScheduler().performTicks(2);
        assertEquals("WorkHubGui", windowName());
        assertTrue(slotName(11).contains("Grzybobranie"), slotName(11));
        player.simulateInventoryClick(11);
        server.getScheduler().performTicks(2);
        assertEquals("WorkGui", windowName());
        player.simulateInventoryClick(49);
        server.getScheduler().performTicks(2);
        assertEquals("WorkHubGui", windowName());
        player.simulateInventoryClick(49);
        server.getScheduler().performTicks(2);
        assertEquals("EarnGui", windowName());
    }

    @Test
    void shortCommandsOpenTheJobWindows() {
        server.dispatchCommand(player, "lesnik");
        server.getScheduler().performTicks(2);
        assertEquals("WorkGui", windowName());
        player.closeInventory();
        server.dispatchCommand(player, "prace");
        server.getScheduler().performTicks(2);
        assertEquals("WorkHubGui", windowName());
        player.closeInventory();
        server.dispatchCommand(player, "kasyno praca");
        server.getScheduler().performTicks(2);
        assertEquals("WorkHubGui", windowName());
        player.closeInventory();
        server.dispatchCommand(player, "kasyno rybak");
        server.getScheduler().performTicks(2);
        assertEquals("WorkGui", windowName());
        server.dispatchCommand(player, "kasyno praca info");
        server.dispatchCommand(player, "kasyno praca anuluj");
    }

    @Test
    void adminPlacesJobNpcsWithARadiusAndCanChangeItLater() {
        player.setOp(true);
        server.dispatchCommand(player, "kasyno npc spawn rybak 35");
        List<Villager> all = plugin.npcs().all();
        assertEquals(1, all.size());
        assertEquals("rybak", plugin.npcs().typeOf(all.get(0)));
        assertEquals(35, plugin.npcs().radiusOf(all.get(0)));
        server.dispatchCommand(player, "kasyno praca promien 50");
        assertEquals(50, plugin.npcs().radiusOf(all.get(0)));
        server.dispatchCommand(player, "kasyno praca promien 9999");
        assertEquals(WorkService.MAX_RADIUS, plugin.npcs().radiusOf(all.get(0)));
        server.dispatchCommand(player, "kasyno praca promien 0");
        assertEquals(0, plugin.npcs().radiusOf(all.get(0)), "0 przywraca domyślny");
        server.dispatchCommand(player, "kasyno praca promien abc");
        assertEquals(0, plugin.npcs().radiusOf(all.get(0)));
        server.dispatchCommand(player, "kasyno npc spawn lesnik abc");
        assertEquals(1, plugin.npcs().count(), "zły promień nie stawia NPC");

        player.setOp(false);
        server.dispatchCommand(player, "kasyno praca promien 70");
        assertEquals(0, plugin.npcs().radiusOf(all.get(0)), "zwykły gracz nie zmienia promienia");
        server.dispatchCommand(player, "kasyno npc spawn kurier 20");
        assertEquals(1, plugin.npcs().count(), "zwykły gracz nie stawia NPC");
    }

    @Test
    void windowShowsReadinessToolAndBreakAndOnlyAdminsCanWorkWithoutAnNpc() {
        // zwykły gracz, brak NPC: nie ma gdzie pracować
        assertTrue(Screens.open(plugin, player, "lesnik"));
        server.getScheduler().performTicks(2);
        assertTrue(slotName(31).contains("Brak miejsca pracy"), slotName(31));
        player.closeInventory();

        // przy NPC okno proponuje start
        Villager npc = plugin.npcs().spawn(new Location(world, 0, 64, 0), "lesnik", 0, 30);
        rightClick(npc);
        assertEquals("WorkGui", windowName());
        assertTrue(slotName(31).contains("Zacznij pracę"), slotName(31));
        assertTrue(slotName(4).contains("Grzybobranie"));
        player.closeInventory();

        // kopalnia bez kilofa: okno mówi o narzędziu
        Villager mine = plugin.npcs().spawn(new Location(world, 0, 64, 4), "gornik", 0, 30);
        rightClick(mine);
        assertTrue(slotName(31).contains("Brak narzędzia"), slotName(31));
        player.closeInventory();

        // po rundzie okno pokazuje przerwę
        plugin.eco().setCooldown(id, "work:lesnik", 60_000);
        rightClick(npc);
        assertTrue(slotName(31).contains("Przerwa"), slotName(31));
    }

    // ---------- prawdziwe encje ----------

    private List<Entity> workEntities() {
        List<Entity> out = new ArrayList<>();
        for (Entity e : world.getEntities()) {
            if (BukkitNodeBackend.TAG != null && e.getPersistentDataContainer().has(BukkitNodeBackend.TAG, PersistentDataType.STRING)) out.add(e);
        }
        return out;
    }

    @Test
    void clickingTheNpcAndStartingPutsGlowingTargetsInTheWorldThatCanBeClicked() {
        plugin.work().useTerrain(w -> flat);
        Villager npc = plugin.npcs().spawn(new Location(world, 0, 64, 0), "lesnik", 0, 30);
        rightClick(npc);
        assertEquals("WorkGui", windowName());
        player.simulateInventoryClick(31);
        server.getScheduler().performTicks(3);
        assertTrue(plugin.work().active(id), "runda wystartowała z okna NPC");

        List<Entity> ents = workEntities();
        List<Interaction> clicks = ents.stream().filter(e -> e instanceof Interaction).map(e -> (Interaction) e).toList();
        assertEquals(10, clicks.size(), "10 pól kliknięcia");
        assertEquals(10, ents.stream().filter(e -> e instanceof ItemDisplay).count(), "10 świecących przedmiotów");
        assertTrue(clicks.stream().allMatch(c -> plugin.work().isNode(c.getUniqueId())));

        long w0 = plugin.eco().wallet(id);
        for (Interaction c : clicks) {
            player.teleport(c.getLocation().add(1, 0, 0));
            var event = new PlayerInteractEntityEvent(player, c, EquipmentSlot.HAND);
            server.getPluginManager().callEvent(event);
            assertTrue(event.isCancelled(), "kliknięcie w cel nie robi nic innego");
        }
        assertFalse(plugin.work().active(id), "po dziesiątym grzybie runda skończona");
        assertTrue(plugin.eco().wallet(id) > w0 + 10 * 20, "pieniądze wpadły od razu");
        assertEquals(0, ents.stream().filter(Entity::isValid).filter(e -> e instanceof Interaction || e instanceof ItemDisplay).count(), "po rundzie w świecie nic nie zostało");
        server.getScheduler().performTicks(40);
        assertTrue(workEntities().isEmpty(), "napisy z kwotami też znikły");
    }

    @Test
    void leftClickAndOffHandBehaveAndStrayEntitiesAreSweptAway() {
        plugin.work().useTerrain(w -> flat);
        player.setOp(true);
        assertNull(plugin.work().start(player, job("lesnik"), site(40)));
        List<Entity> ents = workEntities();
        Interaction first = (Interaction) ents.stream().filter(e -> e instanceof Interaction).findFirst().orElseThrow();
        player.teleport(first.getLocation().add(1, 0, 0));
        long w0 = plugin.eco().wallet(id);

        var off = new PlayerInteractEntityEvent(player, first, EquipmentSlot.OFF_HAND);
        server.getPluginManager().callEvent(off);
        assertTrue(off.isCancelled());
        assertEquals(w0, plugin.eco().wallet(id), "druga ręka nie zbiera");

        var hit = new EntityDamageByEntityEvent(player, first, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0);
        server.getPluginManager().callEvent(hit);
        assertTrue(hit.isCancelled());
        assertTrue(plugin.eco().wallet(id) > w0, "uderzenie lewym przyciskiem też zbiera");

        // zostawione encje (np. po awarii) znikają przy sprzątaniu
        assertFalse(workEntities().isEmpty());
        new BukkitNodeBackend(plugin).purge();
        assertTrue(workEntities().isEmpty());
        plugin.work().cancel(player);
    }

    @Test
    void quittingOrDisablingThePluginLeavesNoEntitiesBehind() {
        plugin.work().useTerrain(w -> flat);
        assertNull(plugin.work().start(player, job("sprzatacz"), site(30)));
        assertEquals(12, workEntities().stream().filter(e -> e instanceof ItemDisplay).count());
        plugin.work().quit(player);
        assertTrue(workEntities().isEmpty());

        plugin.eco().clearCooldown(id, "work:sprzatacz");
        assertNull(plugin.work().start(player, job("sprzatacz"), site(30)));
        assertFalse(workEntities().isEmpty());
        plugin.work().disable();
        assertTrue(workEntities().isEmpty());
        assertEquals(0, plugin.work().running());
    }

    @Test
    void aRealRoundRunsOutOfTimeOnTheSchedulerAndCleansUp() {
        plugin.work().useTerrain(w -> flat);
        assertNull(plugin.work().start(player, job("sprzatacz"), site(30)));
        server.getScheduler().performTicks(120 * 20 + 40);
        assertFalse(plugin.work().active(id), "zegar pracy chodzi na harmonogramie serwera");
        assertEquals(End.TIMEOUT, plugin.work().lastSummary(id).how());
        assertTrue(workEntities().stream().noneMatch(e -> e instanceof Interaction || e instanceof ItemDisplay));
        server.getScheduler().performTicks(40);
        assertTrue(workEntities().isEmpty());
    }
}
