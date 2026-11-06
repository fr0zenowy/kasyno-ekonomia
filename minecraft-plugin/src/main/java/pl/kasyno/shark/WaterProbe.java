package pl.kasyno.shark;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;

/** Co gracz ma pod nogami i wokół siebie: czy pływa, jak głęboka jest woda, gdzie jest jej lustro i czy stoi na lądzie. */
public interface WaterProbe {
    /** Stopy gracza są w wodzie, a on sam nie siedzi w łodzi ani na wierzchowcu. */
    boolean swimming(Player p);

    /** Ile bloków wody jest pod stopami gracza (do kilku). */
    int depth(Player p);

    /** Wysokość lustra wody nad graczem. */
    double surfaceY(Player p);

    /** Gracz stoi na stałym gruncie (nie w wodzie, nie w pojeździe): to miejsce jest „lądem”, od którego liczymy odległość. */
    boolean grounded(Player p);

    /** Czy w tym punkcie jest woda (rekin pływa tylko w wodzie). */
    boolean water(World w, double x, double y, double z);

    /** Prawdziwy wykrywacz na blokach świata. */
    WaterProbe BLOCKS = new WaterProbe() {
        private static final int MAX_DEPTH = 8;

        private boolean isWater(Block b) {
            Material t = b.getType();
            if (t == Material.WATER || t == Material.BUBBLE_COLUMN || t == Material.KELP || t == Material.KELP_PLANT || t == Material.SEAGRASS || t == Material.TALL_SEAGRASS) return true;
            return b.getBlockData() instanceof Waterlogged w && w.isWaterlogged();
        }

        @Override
        public boolean swimming(Player p) {
            return p.getVehicle() == null && isWater(p.getLocation().getBlock());
        }

        @Override
        public int depth(Player p) {
            Block b = p.getLocation().getBlock();
            int n = 0;
            while (n < MAX_DEPTH && isWater(b)) {
                n++;
                b = b.getRelative(0, -1, 0);
            }
            return n;
        }

        @Override
        public double surfaceY(Player p) {
            Block b = p.getLocation().getBlock();
            int up = 0;
            while (up < 6 && isWater(b.getRelative(0, 1, 0))) {
                b = b.getRelative(0, 1, 0);
                up++;
            }
            return b.getY() + 0.89;
        }

        @Override
        public boolean grounded(Player p) {
            if (p.getVehicle() != null) return false;
            Block feet = p.getLocation().getBlock();
            if (isWater(feet)) return false;
            return p.getLocation().clone().subtract(0, 0.2, 0).getBlock().getType().isSolid();
        }

        @Override
        public boolean water(World w, double x, double y, double z) {
            return isWater(w.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        }
    };
}
