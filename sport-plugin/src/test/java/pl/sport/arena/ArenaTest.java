package pl.sport.arena;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.sport.core.V3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Przeliczanie układu lokalnego boiska na współrzędne świata we wszystkich czterech orientacjach. */
class ArenaTest {
    private static Arena arena(int dir) {
        return new Arena("a", Arena.Sport.VOLLEYBALL, "world", 100, 64, 200, dir, 18, 9, 2.43);
    }

    @Test
    void worldAndLocalAreInverseInEveryOrientation() {
        for (int dir = 0; dir < 4; dir++) {
            Arena a = arena(dir);
            for (V3 p : new V3[]{new V3(0, 0, 0), new V3(18, 2.5, 9), new V3(7.3, 1.1, 4.4), new V3(-2, 0, -3)}) {
                Location l = a.toWorld(null, p);
                V3 back = a.toLocal(l);
                assertEquals(p.x(), back.x(), 1e-9, "dir " + dir);
                assertEquals(p.y(), back.y(), 1e-9);
                assertEquals(p.z(), back.z(), 1e-9);
            }
        }
    }

    @Test
    void theLongAxisFollowsTheDirection() {
        // dir 0: u rośnie w +X, v w +Z
        Location l0 = arena(0).toWorld(null, new V3(10, 0, 0));
        assertEquals(110, l0.getX(), 1e-9);
        assertEquals(200, l0.getZ(), 1e-9);
        // dir 1: u rośnie w +Z, v w -X
        Location l1 = arena(1).toWorld(null, new V3(10, 0, 3));
        assertEquals(97, l1.getX(), 1e-9);
        assertEquals(210, l1.getZ(), 1e-9);
        // dir 2: u rośnie w -X, v w -Z
        Location l2 = arena(2).toWorld(null, new V3(10, 0, 3));
        assertEquals(90, l2.getX(), 1e-9);
        assertEquals(197, l2.getZ(), 1e-9);
        // dir 3: u rośnie w -Z, v w +X
        Location l3 = arena(3).toWorld(null, new V3(10, 0, 3));
        assertEquals(103, l3.getX(), 1e-9);
        assertEquals(190, l3.getZ(), 1e-9);
    }

    @Test
    void yawLooksAlongTheRequestedLocalDirection() {
        for (int dir = 0; dir < 4; dir++) {
            Arena a = arena(dir);
            float yaw = a.yawOf(new V3(1, 0, 0));
            V3 look = V3.fromYaw(yaw);
            V3 world = a.dirToWorld(new V3(1, 0, 0));
            assertEquals(world.x(), look.x(), 1e-6, "dir " + dir);
            assertEquals(world.z(), look.z(), 1e-6);
            // i kierunek w poprzek boiska
            float yawV = a.yawOf(new V3(0, 0, 1));
            V3 lookV = V3.fromYaw(yawV);
            V3 worldV = a.dirToWorld(new V3(0, 0, 1));
            assertEquals(worldV.x(), lookV.x(), 1e-6);
            assertEquals(worldV.z(), lookV.z(), 1e-6);
        }
    }

    @Test
    void directionVectorsConvertBothWays() {
        for (int dir = 0; dir < 4; dir++) {
            Arena a = arena(dir);
            V3 d = new V3(0.6, 0.3, -0.8);
            V3 back = a.dirToLocal(a.dirToWorld(d));
            assertEquals(d.x(), back.x(), 1e-9);
            assertEquals(d.y(), back.y(), 1e-9);
            assertEquals(d.z(), back.z(), 1e-9);
        }
    }

    @Test
    void dirFromYawPicksTheNearestCardinalDirection() {
        assertEquals(1, ArenaBuilder.dirFromYaw(0), "yaw 0 patrzy na +Z");
        assertEquals(2, ArenaBuilder.dirFromYaw(90), "yaw 90 patrzy na -X");
        assertEquals(3, ArenaBuilder.dirFromYaw(180), "yaw 180 patrzy na -Z");
        assertEquals(0, ArenaBuilder.dirFromYaw(-90), "yaw -90 patrzy na +X");
        assertEquals(0, ArenaBuilder.dirFromYaw(270));
        assertEquals(1, ArenaBuilder.dirFromYaw(20));
        assertEquals(2, ArenaBuilder.dirFromYaw(100));
        assertEquals(3, ArenaBuilder.dirFromYaw(-170));
    }

    @Test
    void arenasSurviveSerialization() {
        Arena a = new Arena("Hala", Arena.Sport.BASKETBALL, "world", 12, 70, -30, 3, 28, 15, 2.43);
        YamlConfiguration y = new YamlConfiguration();
        a.save(y.createSection("a"));
        Arena b = Arena.load("Hala", y.getConfigurationSection("a"));
        assertNotNull(b);
        assertEquals(Arena.Sport.BASKETBALL, b.sport());
        assertEquals(28, b.length());
        assertEquals(15, b.width());
        assertEquals(3, b.dir());
        assertEquals(-30, b.originZ(), 1e-9);
        assertTrue(Arena.load("x", new YamlConfiguration()) == null, "wpis bez dyscypliny jest odrzucany");
    }
}
