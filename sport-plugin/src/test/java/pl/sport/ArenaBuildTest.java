package pl.sport;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.junit.jupiter.api.Test;
import pl.sport.arena.Arena;
import pl.sport.core.V3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Budowa boisk komendą: podłoga, linie, oczyszczenie terenu, orientacje, zapis i usuwanie. */
class ArenaBuildTest extends SportTestBase {
    private Block floorAt(Arena a, double u, double v) {
        return world.getBlockAt(a.toWorld(world, new V3(u, -0.5, v)));
    }

    private Block airAt(Arena a, double u, double v, double y) {
        return world.getBlockAt(a.toWorld(world, new V3(u, y, v)));
    }

    @Test
    void anOrdinaryPlayerCannotBuildCourts() {
        a.teleport(new org.bukkit.Location(world, 10, 64, 10));
        server.dispatchCommand(a, "siatkowka stworz moje");
        assertNull(plugin.arenas().get("moje"));
    }

    @Test
    void volleyballCourtHasFloorLinesAndClearAirAbove() {
        // kamień na drodze: boisko ma go usunąć
        world.getBlockAt(55, 66, 50).setType(Material.STONE);
        Arena arena = build("siatkowka", "hala1", 50, 50, -90);
        assertEquals(Arena.Sport.VOLLEYBALL, arena.sport());
        assertEquals(18, arena.length());
        assertEquals(9, arena.width());
        assertEquals(Material.WHITE_CONCRETE, floorAt(arena, 0.5, 0.5).getType(), "róg boiska to linia");
        assertEquals(Material.WHITE_CONCRETE, floorAt(arena, 17.5, 8.5).getType());
        assertEquals(Material.WHITE_CONCRETE, floorAt(arena, 9.5, 0.5).getType(), "linia boczna");
        assertEquals(Material.SMOOTH_SANDSTONE, floorAt(arena, 4.5, 4.5).getType(), "wnętrze boiska");
        assertEquals(Material.LIGHT_BLUE_CONCRETE, floorAt(arena, 6.5, 4.5).getType(), "linia ataku 3 m od siatki");
        assertEquals(Material.LIGHT_BLUE_CONCRETE, floorAt(arena, 11.5, 4.5).getType());
        assertEquals(Material.LIGHT_GRAY_CONCRETE, floorAt(arena, -1.5, 4.5).getType(), "obrzeże za linią końcową");
        for (double u = -2.5; u < 20; u += 1) {
            for (double v = -2.5; v < 12; v += 1) {
                assertTrue(airAt(arena, u, v, 0.5).getType().isAir(), "powietrze nad boiskiem w (" + u + ", " + v + ")");
                assertTrue(airAt(arena, u, v, 5.5).getType().isAir());
            }
        }
        assertTrue(world.getBlockAt(55, 66, 50).getType().isAir(), "kamień znikł");
    }

    @Test
    void courtsAreBuiltFacingEveryDirection() {
        float[] yaws = {-90, 0, 90, 180}; // +X, +Z, -X, -Z
        for (int i = 0; i < 4; i++) {
            Arena a = build("siatkowka", "k" + i, 100 + i * 60, 100, yaws[i]);
            assertEquals(i, a.dir(), "kierunek dla yaw " + yaws[i]);
            // gracz stał na środku linii końcowej, więc ta komórka jest linią, a środek boiska wnętrzem
            V3 playerCell = a.toLocal(100 + i * 60 + 0.5, 64, 100 + 0.5);
            assertTrue(playerCell.x() >= 0 && playerCell.x() <= 1, "gracz na linii końcowej: " + playerCell);
            assertTrue(Math.abs(playerCell.z() - 4.5) < 1.0, "gracz na środku szerokości: " + playerCell);
            assertEquals(Material.SMOOTH_SANDSTONE, floorAt(a, 4.5, 4.5).getType());
            assertEquals(Material.WHITE_CONCRETE, floorAt(a, 0.5, 4.5).getType());
            assertEquals(Material.WHITE_CONCRETE, floorAt(a, 17.5, 4.5).getType());
            assertEquals(Material.LIGHT_BLUE_CONCRETE, floorAt(a, 6.5, 2.5).getType());
        }
    }

    @Test
    void basketballCourtHasLinesCenterCircleAndKeys() {
        Arena arena = build("koszykowka", "kosz1", 200, 200, 0);
        assertEquals(Arena.Sport.BASKETBALL, arena.sport());
        assertEquals(28, arena.length());
        assertEquals(15, arena.width());
        assertEquals(Material.WHITE_CONCRETE, floorAt(arena, 0.5, 7.5).getType(), "linia końcowa");
        assertEquals(Material.WHITE_CONCRETE, floorAt(arena, 13.5, 3.5).getType(), "linia środkowa");
        assertEquals(Material.WHITE_CONCRETE, floorAt(arena, 14.0 + 1.8, 7.5).getType(), "koło środkowe");
        assertEquals(Material.RED_TERRACOTTA, floorAt(arena, 3.5, 7.5).getType(), "pole podkoszowe");
        assertEquals(Material.BIRCH_PLANKS, floorAt(arena, 9.5, 3.5).getType(), "parkiet");
        // łuk za trzy punkty: ok. 6,75 m od środka obręczy (u = 1,64) w głąb boiska
        assertEquals(Material.WHITE_CONCRETE, floorAt(arena, 1.64 + 6.75, 7.5).getType());
    }

    @Test
    void courtsHaveDecorationEntitiesThatAreRemovedWithTheCourt() {
        Arena arena = build("siatkowka", "dek", 300, 300, -90);
        long displays = world.getEntitiesByClass(BlockDisplay.class).size();
        assertEquals(7, displays, "siatka, dwie taśmy, dwa słupki i dwie anteny");
        server.dispatchCommand(admin, "siatkowka usun dek");
        assertNull(plugin.arenas().get("dek"));
        assertEquals(0, world.getEntitiesByClass(BlockDisplay.class).size(), "ozdoby usunięte razem z boiskiem");
        assertEquals(Material.SMOOTH_SANDSTONE, floorAt(arena, 4.5, 4.5).getType(), "podłoga z bloków zostaje");
    }

    @Test
    void basketballHoopsAreBuiltAsRimsBoardsAndPoles() {
        build("koszykowka", "h", 400, 400, -90);
        long n = world.getEntitiesByClass(BlockDisplay.class).size();
        // dwa kosze: tablica, 4 ramki, 4 elementy kwadratu, 16 odcinków obręczy, 4 pasy siatki, słup i ramię
        assertTrue(n >= 2 * 30, "elementy koszy: " + n);
    }

    @Test
    void arenasArePersistedAndReloaded() {
        Arena built = build("siatkowka", "trwala", 500, 500, 90);
        plugin.arenas().load();
        Arena loaded = plugin.arenas().get("TRWALA");
        assertNotNull(loaded, "nazwy nie rozróżniają wielkości liter");
        assertEquals(built.dir(), loaded.dir());
        assertEquals(built.originX(), loaded.originX(), 1e-9);
        assertEquals(built.originZ(), loaded.originZ(), 1e-9);
        assertEquals(built.length(), loaded.length());
    }

    @Test
    void invalidNamesAndSizesAreRejected() {
        admin.teleport(new org.bukkit.Location(world, 600, 64, 600));
        server.dispatchCommand(admin, "siatkowka stworz zła/nazwa");
        server.dispatchCommand(admin, "siatkowka stworz ok 17"); // nieparzysta długość
        server.dispatchCommand(admin, "siatkowka stworz ok2 5 5"); // za małe
        server.dispatchCommand(admin, "koszykowka stworz kosz abc");
        assertTrue(plugin.arenas().all().isEmpty());
        server.dispatchCommand(admin, "siatkowka stworz dobra 20 10");
        assertEquals(20, plugin.arenas().get("dobra").length());
        server.dispatchCommand(admin, "koszykowka stworz dobra");
        assertEquals(1, plugin.arenas().all().size(), "druga arena o tej samej nazwie nie powstaje");
    }

    @Test
    void aCourtOfOneSportIsNotManagedByTheOtherCommand() {
        build("siatkowka", "tylko-siatka", 700, 700, 0);
        a.teleport(new org.bukkit.Location(world, 700, 64, 700));
        server.dispatchCommand(a, "koszykowka dolacz tylko-siatka");
        assertFalse(plugin.matches().of(a) != null, "koszykówka nie wpuszcza na boisko siatkarskie");
    }
}
