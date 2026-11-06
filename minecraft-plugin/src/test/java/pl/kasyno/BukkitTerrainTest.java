package pl.kasyno;

import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import pl.kasyno.show.Stage.P;
import pl.kasyno.work.BukkitTerrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Czytanie terenu z prawdziwego świata (BukkitTerrain) na atrapie: trawa, pień, korona drzewa, staw płytki i głęboki, niezaładowany chunk.
 * Atrapa nie zna map wysokości, więc „najwyższy blok” to po prostu najwyższy niepusty. To wystarcza, by sprawdzić, co uznajemy za ziemię.
 */
class BukkitTerrainTest {
    ServerMock server;
    World world;
    BukkitTerrain terrain;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("las");
        world.getChunkAt(0, 0);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) world.getBlockAt(x, 60, z).setType(Material.GRASS_BLOCK);
        terrain = new BukkitTerrain(world);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void grassIsGroundAndTheFeetPointSitsOnTopOfIt() {
        P p = terrain.ground(1.2, 1.7);
        assertNotNull(p, "trawa to ziemia");
        assertEquals(1.5, p.x(), 1e-9);
        assertEquals(61.0, p.y(), 1e-9);
        assertEquals(1.5, p.z(), 1e-9);
        // ten sam blok daje ten sam punkt niezależnie od miejsca w bloku, także dla ujemnych współrzędnych
        assertEquals(p, terrain.ground(1.9, 1.1));
        world.getChunkAt(-1, -1);
        world.getBlockAt(-3, 60, -3).setType(Material.DIRT);
        assertEquals(new P(-2.5, 61.0, -2.5), terrain.ground(-2.2, -2.9));
    }

    @Test
    void treesLeavesAndBuildingsAreNotMistakenForTheForestFloor() {
        for (int y = 61; y <= 66; y++) world.getBlockAt(3, y, 3).setType(Material.OAK_LOG);
        assertNull(terrain.ground(3.5, 3.5), "wierzchołek pnia to nie ziemia");
        world.getBlockAt(6, 63, 6).setType(Material.OAK_LEAVES);
        assertNull(terrain.ground(6.5, 6.5), "liście nie są podłożem");
        world.getBlockAt(8, 61, 8).setType(Material.RED_MUSHROOM_BLOCK);
        assertNull(terrain.ground(8.5, 8.5), "kapelusz wielkiego grzyba nie jest podłożem");
        world.getBlockAt(9, 60, 9).setType(Material.CACTUS);
        assertNull(terrain.ground(9.5, 9.5), "po kaktusie się nie chodzi");
        world.getBlockAt(10, 60, 10).setType(Material.MAGMA_BLOCK);
        assertNull(terrain.ground(10.5, 10.5), "magma parzy");
        // sąsiednie kolumny nadal są ziemią
        assertNotNull(terrain.ground(4.5, 3.5));
        assertNotNull(terrain.ground(7.5, 6.5));
    }

    @Test
    void waterMustBeDeepEnoughForAFishToSwimIn() {
        for (int x = 12; x <= 14; x++) {
            for (int z = 12; z <= 14; z++) {
                world.getBlockAt(x, 59, z).setType(Material.WATER);
                world.getBlockAt(x, 60, z).setType(Material.WATER);
            }
        }
        P p = terrain.water(13.5, 13.5);
        assertNotNull(p, "głęboka woda");
        assertEquals(13.5, p.x(), 1e-9);
        assertEquals(60.9, p.y(), 1e-9, "tuż nad powierzchnią");
        assertNull(terrain.ground(13.5, 13.5), "woda nie jest ziemią");
        assertNull(terrain.water(1.5, 1.5), "trawa to nie woda");

        world.getBlockAt(0, 59, 15).setType(Material.STONE);
        world.getBlockAt(0, 60, 15).setType(Material.WATER);
        assertNull(terrain.water(0.5, 15.5), "kałuża o głębokości jednego bloku jest za płytka");
    }

    @Test
    void unloadedChunksAreNeverTouched() {
        assertNull(terrain.ground(1000.5, 1000.5));
        assertNull(terrain.water(1000.5, 1000.5));
        assertNull(terrain.ground(-5000.5, 40.5));
    }
}
