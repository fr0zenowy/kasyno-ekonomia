package pl.kasyno;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
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
import pl.kasyno.bin.BinManager;
import pl.kasyno.bin.BinManager.Result;
import pl.kasyno.bin.BinManager.Status;
import pl.kasyno.bin.BinModel;
import pl.kasyno.bin.BinType;
import pl.kasyno.gui.GuiManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Śmietniki w świecie na atrapie serwera: model z brył, własna przerwa każdego śmietnika, przeszukiwanie, komendy i trwałość. */
class BinTest {
    static {
        System.setProperty("kasyno.test", "1");
    }

    ServerMock server;
    KasynoPlugin plugin;
    World world;
    PlayerMock player;
    UUID id;

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
        d.level = 50;
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private BinManager bins() {
        return plugin.bins();
    }

    private Interaction place(String type, double x, double z, int cooldown) {
        return bins().place(new Location(world, x, 64, z), BinType.find(type), 0, cooldown);
    }

    private int displays() {
        return world.getEntitiesByClass(BlockDisplay.class).size();
    }

    private int labels() {
        return world.getEntitiesByClass(TextDisplay.class).size();
    }

    private int items() {
        int n = 0;
        for (int q : plugin.eco().pd(id).inventory.values()) n += q;
        return n;
    }

    // ---------- model ----------

    @Test
    void aBinIsAModelMadeOfBlocksWithAClickBoxAndALabel() {
        Interaction in = place("kosz", 0, 0, 0);
        BinType kosz = BinType.find("kosz");
        assertTrue(bins().isBin(in));
        assertEquals("kosz", bins().typeOf(in).id());
        assertEquals(kosz.parts().size(), displays(), "każda bryła modelu to osobny BlockDisplay");
        assertEquals(1, labels(), "napis nad śmietnikiem");
        assertEquals(1, world.getEntitiesByClass(Interaction.class).size());
        assertEquals(0.8f, in.getInteractionWidth(), 1e-6);
        assertEquals(1.0f, in.getInteractionHeight(), 1e-6);
        assertTrue(in.isPersistent(), "przeżywa restart");
        assertEquals(1, bins().count());
        // stoi na środku bloku, w którym stoisz
        assertEquals(0.5, in.getLocation().getX(), 1e-9);
        assertEquals(0.5, in.getLocation().getZ(), 1e-9);
    }

    @Test
    void everyTypeBuildsAllItsParts() {
        int expected = 0;
        double x = 0;
        for (BinType t : BinType.ALL) {
            place(t.id(), x, 0, 0);
            x += 6;
            expected += t.parts().size();
        }
        assertEquals(expected, displays());
        assertEquals(BinType.ALL.size(), labels());
        assertEquals(BinType.ALL.size(), bins().count());
    }

    @Test
    void theTwoLidsOfTheDumpsterAreMarkedSoTheyCanOpen() {
        place("kontener", 0, 0, 0);
        NamespacedKey lid = new NamespacedKey(plugin, "bin_lid");
        long lids = world.getEntitiesByClass(BlockDisplay.class).stream().filter(d -> d.getPersistentDataContainer().has(lid, PersistentDataType.STRING)).count();
        assertEquals(2, lids);
    }

    // ---------- własna przerwa ----------

    @Test
    void everyBinGetsItsOwnIdAndItsOwnCooldown() {
        Set<String> ids = new HashSet<>();
        Set<Integer> cooldowns = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            Interaction in = place("kosz", i * 3, 0, 0);
            assertTrue(ids.add(bins().idOf(in)), "unikalny identyfikator");
            int cd = bins().cooldownOf(in);
            assertTrue(cd >= 70 && cd <= 115 && cd % 5 == 0, "kosz 90 s -20%..+25%, co 5 s: " + cd);
            cooldowns.add(cd);
        }
        assertTrue(cooldowns.size() >= 6, "każdy kosz ma trochę inną przerwę, było tylko " + cooldowns.size() + " różnych");

        assertEquals(200, bins().cooldownOf(place("beczka", 200, 0, 200)), "własna przerwa z komendy");
        assertEquals(BinManager.MIN_COOLDOWN, bins().cooldownOf(place("beczka", 210, 0, 5)));
        assertEquals(BinManager.MAX_COOLDOWN, bins().cooldownOf(place("beczka", 220, 0, 99_999)));
    }

    @Test
    void cooldownIsPerBinAndPerPlayer() {
        BinType t = BinType.find("kosz");
        Interaction a = place("kosz", 0, 0, 120);
        Interaction b = place("kosz", 5, 0, 120);
        String ida = bins().idOf(a);
        String idb = bins().idOf(b);

        Result first = bins().search(id, ida, t, 120);
        assertNotEquals(Status.COOLDOWN, first.status());
        long left = plugin.eco().cooldownLeft(id, "bin:" + ida);
        assertTrue(left > 110_000 && left <= 120_000, "przerwa tego śmietnika: " + left);
        int afterFirst = items();

        Result again = bins().search(id, ida, t, 120);
        assertEquals(Status.COOLDOWN, again.status());
        assertTrue(again.left() > 0 && again.left() <= 120_000);
        assertEquals(afterFirst, items(), "w przerwie nic nie wpada");

        assertNotEquals(Status.COOLDOWN, bins().search(id, idb, t, 120).status(), "drugi śmietnik ma własną przerwę");
        assertEquals(0, plugin.eco().cooldownLeft(id, "bin:zupelnie-inny"));

        PlayerMock other = server.addPlayer();
        PlayerListener.prepare(plugin, other);
        assertNotEquals(Status.COOLDOWN, bins().search(other.getUniqueId(), ida, t, 120).status(), "inny gracz ma swoją przerwę na tym samym śmietniku");
    }

    @Test
    void aSearchPaysInItemsFromTheTrashTableAndNeverInCoins() {
        BinType t = BinType.find("wiata");
        Set<String> table = new HashSet<>();
        plugin.catalog().gather.get("smietnik").table().forEach(e -> table.add(e.id()));
        int found = 0;
        int misses = 0;
        for (int i = 0; i < 200; i++) {
            int before = items();
            long xp = plugin.eco().pd(id).xp;
            Result r = bins().search(id, "t" + i, t, 300);
            if (r.status() == Status.OK) {
                found++;
                assertTrue(table.contains(r.item()), r.item());
                assertTrue(r.qty() >= 1);
                assertEquals(before + r.qty(), items(), "łup trafia do ekwipunku");
                assertTrue(plugin.eco().pd(id).xp > xp, "za znalezisko jest XP");
            } else {
                assertEquals(Status.MISS, r.status());
                misses++;
                assertEquals(before, items());
            }
        }
        assertTrue(found > 150, "wiata rzadko bywa pusta: " + found);
        assertTrue(misses < 40);
        assertEquals(0, plugin.eco().wallet(id), "śmietniki dają przedmioty do sprzedaży, nie monety");
    }

    // ---------- kliknięcia ----------

    private void rightClick(Interaction in, EquipmentSlot hand) {
        var e = new PlayerInteractEntityEvent(player, in, hand);
        server.getPluginManager().callEvent(e);
        assertTrue(e.isCancelled(), "kliknięcie w śmietnik nie robi nic innego");
    }

    @Test
    void rightClickSearchesOnceThenTellsToWaitAndTheOffHandDoesNothing() {
        Interaction in = place("beczka", 0, 0, 100);
        String bin = bins().idOf(in);
        rightClick(in, EquipmentSlot.OFF_HAND);
        assertEquals(0, plugin.eco().cooldownLeft(id, "bin:" + bin), "druga ręka nie przeszukuje");

        rightClick(in, EquipmentSlot.HAND);
        long left = plugin.eco().cooldownLeft(id, "bin:" + bin);
        assertTrue(left > 90_000 && left <= 100_000, "przerwa tego śmietnika: " + left);
        int items = items();
        rightClick(in, EquipmentSlot.HAND);
        rightClick(in, EquipmentSlot.HAND);
        assertEquals(items, items(), "w przerwie kolejne kliknięcia nic nie dają");
        assertTrue(plugin.eco().cooldownLeft(id, "bin:" + bin) <= left, "i nie wydłużają przerwy");
    }

    @Test
    void leftClickSearchesToo() {
        Interaction in = place("kosz", 0, 0, 100);
        var hit = new EntityDamageByEntityEvent(player, in, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0);
        server.getPluginManager().callEvent(hit);
        assertTrue(hit.isCancelled());
        assertTrue(plugin.eco().cooldownLeft(id, "bin:" + bins().idOf(in)) > 0);
    }

    @Test
    void theLidOpensAndClosesAgainWithoutLeavingAnythingBehind() {
        Interaction in = place("kontener", 0, 0, 60);
        int before = displays();
        rightClick(in, EquipmentSlot.HAND);
        server.getScheduler().performTicks(40);
        assertEquals(before, displays(), "wieka wracają na miejsce, nic nie przybyło ani nie zniknęło");
        assertTrue(world.getEntitiesByClass(BlockDisplay.class).stream().allMatch(BlockDisplay::isValid));
        assertTrue(in.isValid());
    }

    @Test
    void theReadyHintTaskRunsWithoutTroubleNearBinsOnAndOffCooldown() {
        Interaction ready = place("kosz", 2, 0, 60);
        Interaction used = place("kosz", -2, 0, 60);
        bins().search(id, bins().idOf(used), BinType.find("kosz"), 60);
        server.getScheduler().performTicks(130);
        assertTrue(ready.isValid() && used.isValid());
        assertEquals(2, bins().count());
    }

    // ---------- komendy ----------

    @Test
    void adminPlacesChangesAndRemovesBins() {
        player.setOp(true);
        player.teleport(new Location(world, 10, 64, 10, 0f, 0f));
        server.dispatchCommand(player, "kasyno smietnik postaw beczka 150");
        assertEquals(1, bins().count());
        Interaction in = bins().all().get(0);
        assertEquals("beczka", bins().typeOf(in).id());
        assertEquals(150, bins().cooldownOf(in));
        assertEquals(BinType.find("beczka").parts().size(), displays());
        assertEquals(BinModel.facing(0), bins().yawOf(in), "przodem do gracza, który patrzy na południe");

        server.dispatchCommand(player, "kasyno smietnik cooldown 99");
        assertEquals(99, bins().cooldownOf(in));
        server.dispatchCommand(player, "kasyno smietnik cooldown 3");
        assertEquals(BinManager.MIN_COOLDOWN, bins().cooldownOf(in), "za krótka przerwa jest podnoszona do minimum");
        server.dispatchCommand(player, "kasyno smietnik cooldown abc");
        assertEquals(BinManager.MIN_COOLDOWN, bins().cooldownOf(in));
        server.dispatchCommand(player, "kasyno smietnik lista");
        server.dispatchCommand(player, "kasyno smietnik typy");

        server.dispatchCommand(player, "kasyno smietnik postaw nieznany");
        server.dispatchCommand(player, "kasyno smietnik postaw kosz abc");
        assertEquals(1, bins().count(), "błędne polecenia niczego nie stawiają");

        server.dispatchCommand(player, "kasyno smietnik usun");
        assertEquals(0, bins().count());
        assertEquals(0, displays(), "razem ze śmietnikiem znika cały model");
        assertEquals(0, labels());
        assertEquals(0, world.getEntitiesByClass(Interaction.class).size());
        server.dispatchCommand(player, "kasyno smietnik usun");
    }

    @Test
    void binsFaceWhoeverPlacedThemWhicheverWayTheyLook() {
        player.setOp(true);
        for (float yaw : new float[]{0f, 90f, 180f, -90f}) {
            bins().removeAll();
            player.teleport(new Location(world, 0, 64, 0, yaw, 0f));
            server.dispatchCommand(player, "kasyno smietnik postaw kosz");
            assertEquals(BinModel.facing(yaw), bins().yawOf(bins().all().get(0)), "yaw gracza " + yaw);
        }
    }

    @Test
    void regularPlayersCanOnlyReadTheTypes() {
        server.dispatchCommand(player, "kasyno smietnik typy");
        server.dispatchCommand(player, "kasyno smietnik postaw kosz");
        server.dispatchCommand(player, "kasyno smietnik wyczysc potwierdz");
        assertEquals(0, bins().count());

        player.setOp(true);
        server.dispatchCommand(player, "kasyno smietnik postaw kosz");
        player.setOp(false);
        server.dispatchCommand(player, "kasyno smietnik usun");
        server.dispatchCommand(player, "kasyno smietnik cooldown 500");
        server.dispatchCommand(player, "kasyno smietnik wyczysc potwierdz");
        assertEquals(1, bins().count(), "zwykły gracz niczego nie usuwa ani nie zmienia");
        assertNotEquals(500, bins().cooldownOf(bins().all().get(0)));
    }

    @Test
    void clearingNeedsConfirmationAndAlsoSweepsOrphanedParts() {
        player.setOp(true);
        place("kosz", 0, 0, 0);
        Interaction second = place("kontener", 10, 0, 0);
        server.dispatchCommand(player, "kasyno smietnik wyczysc");
        assertEquals(2, bins().count(), "bez potwierdzenia nic się nie dzieje");
        second.remove(); // np. /kill na polu kliknięcia: bryły zostały osierocone
        assertEquals(1, bins().count());
        assertTrue(displays() > BinType.find("kosz").parts().size());
        assertEquals(1, bins().removeAll());
        assertEquals(0, displays(), "sprzątanie usuwa też osierocone bryły");
        assertEquals(0, labels());
    }

    // ---------- trwałość ----------

    @Test
    void binsAreRememberedInTheEntitiesThemselves() {
        Interaction in = place("wiata", 0, 0, 240);
        String bin = bins().idOf(in);
        BinManager fresh = new BinManager(plugin); // jak po restarcie: bez żadnej pamięci poza encjami
        List<Interaction> all = fresh.all();
        assertEquals(1, all.size());
        assertEquals(bin, fresh.idOf(all.get(0)));
        assertEquals("wiata", fresh.typeOf(all.get(0)).id());
        assertEquals(240, fresh.cooldownOf(all.get(0)));
        assertNotNull(fresh.nearest(player, 10));
        assertEquals(in, fresh.nearest(player, 10));
        assertFalse(fresh.isBin(player));
    }

    @Test
    void thePluginRegistersTheBinCommandInTabCompletion() {
        player.setOp(true);
        var options = plugin.getCommand("kasyno").getTabCompleter().onTabComplete(player, plugin.getCommand("kasyno"), "kasyno", new String[]{"smietnik", "postaw", ""});
        assertTrue(options.containsAll(BinType.ids()), options.toString());
        var first = plugin.getCommand("kasyno").getTabCompleter().onTabComplete(player, plugin.getCommand("kasyno"), "kasyno", new String[]{"smie"});
        assertTrue(first.contains("smietnik"), first.toString());
    }
}
