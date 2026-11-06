package pl.sport;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.junit.jupiter.api.Test;
import pl.sport.arena.Arena;
import pl.sport.core.V3;
import pl.sport.pingpong.PingPongTable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Budowa hali ze stołem do ping-ponga komendą: wymiary, podłoga, elementy stołu, skala zapisana w boisku, usuwanie. */
class PingPongBuildTest extends SportTestBase {
    private Block floorAt(Arena a, double u, double v) {
        return world.getBlockAt(a.toWorld(world, new V3(u, -0.5, v)));
    }

    @Test
    void theTableHallHasDefaultSizeBorderLinesAndClearAir() {
        world.getBlockAt(55, 66, 50).setType(Material.STONE);
        Arena arena = build("pingpong", "stol1", 50, 50, -90);
        assertEquals(Arena.Sport.PINGPONG, arena.sport());
        assertEquals(12, arena.length());
        assertEquals(8, arena.width());
        assertEquals(Material.WHITE_CONCRETE, floorAt(arena, 0.5, 0.5).getType(), "linia przy ścianie hali");
        assertEquals(Material.SMOOTH_STONE, floorAt(arena, 4.5, 4.5).getType(), "podłoga");
        assertEquals(Material.LIGHT_GRAY_CONCRETE, floorAt(arena, -1.5, 4.5).getType(), "obrzeże");
        assertTrue(world.getBlockAt(55, 66, 50).getType().isAir(), "kamień znikł");
    }

    @Test
    void theTableIsBuiltFromDisplaysWithTheRealProportionsTimesTheScale() {
        Arena arena = build("pingpong", "stol2", 100, 100, -90);
        assertEquals(PingPongTable.REAL_NET * 1.5, arena.netHeight(), 1e-9, "skala zapisana jako wysokość siatki");
        // blat, 5 linii, 4 nogi, 2 poprzeczki, siatka, taśma, 2 uchwyty
        assertEquals(16, world.getEntitiesByClass(BlockDisplay.class).size());
        PingPongTable t = new PingPongTable(arena.length(), arena.width(), PingPongTable.scaleOfNet(arena.netHeight()));
        assertEquals(2.74 * 1.5, t.tableLength(), 1e-9);
        boolean topFound = false;
        for (BlockDisplay d : world.getEntitiesByClass(BlockDisplay.class)) {
            if (d.getBlock().getMaterial() == Material.BLUE_CONCRETE) topFound = true;
        }
        assertTrue(topFound, "niebieski blat");
    }

    @Test
    void theScaleOfNewTablesComesFromTheConfigAndEachTableKeepsItsOwn() {
        plugin.getConfig().set("pingpong.skala", 2.0);
        Arena big = build("pingpong", "duzy", 200, 200, -90);
        assertEquals(PingPongTable.REAL_NET * 2.0, big.netHeight(), 1e-9);
        plugin.getConfig().set("pingpong.skala", 1.5);
        Arena normal = build("pingpong", "normalny", 300, 300, -90);
        assertEquals(PingPongTable.REAL_NET * 1.5, normal.netHeight(), 1e-9);
        plugin.arenas().load();
        assertEquals(PingPongTable.REAL_NET * 2.0, plugin.arenas().get("duzy").netHeight(), 1e-9, "skala przeżywa zapis i wczytanie");
    }

    @Test
    void theTableCanBeRemovedAndTheFloorStays() {
        Arena arena = build("pingpong", "znika", 400, 400, 0);
        server.dispatchCommand(admin, "pingpong usun znika");
        assertNull(plugin.arenas().get("znika"));
        assertEquals(0, world.getEntitiesByClass(BlockDisplay.class).size());
        assertEquals(Material.SMOOTH_STONE, floorAt(arena, 4.5, 4.5).getType());
    }

    @Test
    void hallsThatAreTooSmallForTheTableAreRejected() {
        admin.teleport(new org.bukkit.Location(world, 500, 64, 500));
        server.dispatchCommand(admin, "pingpong stworz mala 6 4");
        assertNull(plugin.arenas().get("mala"), "stół 4,1 m nie mieści się w hali 6 x 4 z miejscem dla graczy");
        server.dispatchCommand(admin, "pingpong stworz ok 9 6");
        assertNotNull(plugin.arenas().get("ok"));
        assertTrue(PingPongTable.minLength(1.5) <= 9 && PingPongTable.minWidth(1.5) <= 6);
    }

    @Test
    void theTableWorksFacingEveryDirection() {
        float[] yaws = {-90, 0, 90, 180};
        for (int i = 0; i < 4; i++) {
            Arena a = build("pingpong", "k" + i, 600 + i * 60, 600, yaws[i]);
            assertEquals(i, a.dir());
            assertEquals(Material.SMOOTH_STONE, floorAt(a, 4.5, 4.5).getType());
        }
        assertEquals(4 * 16, world.getEntitiesByClass(BlockDisplay.class).size());
    }

    @Test
    void theHelpExplainsTheServeAndTheRules() {
        server.dispatchCommand(a, "pingpong pomoc");
        StringBuilder all = new StringBuilder();
        String line;
        while ((line = a.nextMessage()) != null) all.append(line).append(" | ");
        String text = all.toString();
        assertTrue(text.contains("Ping-pong"), text);
        assertTrue(text.contains("Serwis"), text);
        assertTrue(text.contains("odbiciem od stołu"), text);
        assertTrue(!text.contains("Koszykówka") && !text.contains("Siatkówka"), "pomoc ping-ponga nie miesza innych dyscyplin");
    }

    @Test
    void otherSportCommandsDoNotManageTheTable() {
        build("pingpong", "tylko-stol", 900, 900, 0);
        a.teleport(new org.bukkit.Location(world, 900, 64, 900));
        server.dispatchCommand(a, "siatkowka dolacz tylko-stol");
        assertNull(plugin.matches().of(a));
        server.dispatchCommand(a, "pingpong dolacz tylko-stol");
        assertNotNull(plugin.matches().of(a));
    }
}
