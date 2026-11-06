package pl.kasyno.work;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import pl.kasyno.show.Stage.P;

/** Teren z prawdziwego świata: ziemia pod drzewami (liście są pomijane) i powierzchnia wody. Nie ładuje chunków, tylko korzysta z załadowanych. */
public final class BukkitTerrain implements Terrain {
    private final World world;

    public BukkitTerrain(World world) {
        this.world = world;
    }

    private boolean loaded(int bx, int bz) {
        return world.isChunkLoaded(bx >> 4, bz >> 4);
    }

    @Override
    public P ground(double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!loaded(bx, bz)) return null;
        try {
            Block top = world.getHighestBlockAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Material m = top.getType();
            if (!m.isSolid() || top.isLiquid() || Tag.LEAVES.isTagged(m) || m == Material.CACTUS || m == Material.MAGMA_BLOCK || Tag.ICE.isTagged(m)) return null;
            // wierzchołek pnia, gałąź albo kapelusz wielkiego grzyba to nie ziemia: cel wylądowałby wysoko w koronie drzewa
            if (Tag.LOGS.isTagged(m) || m == Material.MUSHROOM_STEM || m == Material.RED_MUSHROOM_BLOCK || m == Material.BROWN_MUSHROOM_BLOCK) return null;
            Block a1 = top.getRelative(0, 1, 0);
            Block a2 = top.getRelative(0, 2, 0);
            if (a1.getType().isSolid() || a1.isLiquid() || a2.getType().isSolid() || a2.isLiquid()) return null; // głowa gracza musi się zmieścić
            return new P(bx + 0.5, top.getY() + 1.0, bz + 0.5);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public P water(double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!loaded(bx, bz)) return null;
        try {
            Block top = world.getHighestBlockAt(bx, bz, HeightMap.WORLD_SURFACE);
            if (top.getType() != Material.WATER) return null;
            // woda ma być głębsza niż jeden blok, żeby ryba miała gdzie pływać
            if (top.getRelative(0, -1, 0).getType() != Material.WATER) return null;
            return new P(bx + 0.5, top.getY() + 0.9, bz + 0.5);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
