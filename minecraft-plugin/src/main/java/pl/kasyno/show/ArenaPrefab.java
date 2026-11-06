package pl.kasyno.show;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import pl.kasyno.show.Stage.P;

/**
 * Gotowa arena do pojedynków i losowania jackpota, stawiana jedną komendą: okrągły plac z piasku z niskim murkiem, dwoma
 * stanowiskami rewolwerowców (pomarańczowe znaczniki), złotym środkiem z okręgiem, na którym staje ekipa jackpota, latarniami
 * na słupach i trybuną dla widzów od południa. Powstaje na południe i wschód od miejsca, w którym stoi administrator (oś Z rośnie
 * na południe, X na wschód); przy okazji tworzy komplet punktów areny.
 *
 * <pre>
 *   x = 4..26, z = 4..26   okrągły plac (promień 10,5), środek w (15,15)
 *   x = 11 i x = 19, z = 15   stanowiska A (zachód) i B (wschód), 8 bloków od siebie
 *   z = 27..29             trybuna: trzy rzędy, każdy blok wyżej; widzowie stoją na ostatnim
 * </pre>
 */
public final class ArenaPrefab {
    /** Wymiary całości (X, Z), żeby administrator wiedział, ile miejsca potrzeba. */
    public static final int WIDTH = 31;
    public static final int DEPTH = 31;

    private static final int CX = 15;
    private static final int CZ = 15;
    private static final double FLOOR = 10.5;
    private static final double MARK = 4;

    private final World w;
    private final int ox;
    private final int oy;
    private final int oz;

    private ArenaPrefab(Location origin) {
        this.w = origin.getWorld();
        this.ox = origin.getBlockX();
        this.oy = origin.getBlockY();
        this.oz = origin.getBlockZ();
    }

    private void set(int x, int y, int z, Material m) {
        w.getBlockAt(ox + x, oy + y, oz + z).setType(m, false);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, m);
            }
        }
    }

    private P p(double x, double y, double z) {
        return new P(ox + x, oy + y, oz + z);
    }

    /** Stawia arenę w świecie `origin` i zwraca komplet jej punktów (Stage z samą grupą arena). */
    public static Stage build(Location origin) {
        ArenaPrefab a = new ArenaPrefab(origin);
        a.construct();
        return a.stage();
    }

    private static double dist(int x, int z) {
        return Math.hypot(x - CX, z - CZ);
    }

    private void construct() {
        // plac: czyścimy bryłę i kładziemy podłoże
        fill(-1, 0, -1, WIDTH, 8, DEPTH, Material.AIR);
        fill(-1, -1, -1, WIDTH, -1, DEPTH, Material.STONE);

        // okrągła podłoga: piasek, ciemniejszy brzeg, okrąg dla ekipy jackpota i złoty środek
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < DEPTH; z++) {
                double d = dist(x, z);
                if (d > FLOOR) continue;
                Material m = d > 9 ? Material.RED_SAND : Material.SAND;
                if (Math.abs(d - MARK) < 0.55) m = Material.YELLOW_TERRACOTTA;
                set(x, -1, z, m);
            }
        }
        set(CX, -1, CZ, Material.GOLD_BLOCK);
        // stanowiska rewolwerowców
        set(CX - (int) MARK, -1, CZ, Material.ORANGE_TERRACOTTA);
        set(CX + (int) MARK, -1, CZ, Material.ORANGE_TERRACOTTA);

        // niski murek wokół placu (połówki płyt, widzowie patrzą ponad nim) i latarnie na słupach co 45 stopni
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < DEPTH; z++) {
                double d = dist(x, z);
                if (d > FLOOR && d <= FLOOR + 1.1) {
                    set(x, -1, z, Material.STONE_BRICKS);
                    set(x, 0, z, Material.STONE_BRICK_SLAB);
                }
            }
        }
        for (int i = 0; i < 8; i++) {
            double ang = Math.PI / 4 * i;
            int x = CX + (int) Math.round(Math.sin(ang) * (FLOOR + 0.5));
            int z = CZ + (int) Math.round(Math.cos(ang) * (FLOOR + 0.5));
            fill(x, 0, z, x, 2, z, Material.OAK_FENCE);
            set(x, 3, z, Material.LANTERN);
        }

        // trybuna od południa: trzy rzędy, każdy o blok wyżej, z tylną ścianką
        for (int row = 0; row < 3; row++) {
            int z = 27 + row;
            fill(5, -1, z, 25, row, z, Material.SPRUCE_PLANKS);
            fill(5, row, z, 25, row, z, Material.DARK_OAK_PLANKS);
        }
        fill(5, 0, 30, 25, 4, 30, Material.DARK_OAK_PLANKS);
        fill(4, 0, 27, 4, 4, 30, Material.DARK_OAK_PLANKS);
        fill(26, 0, 27, 26, 4, 30, Material.DARK_OAK_PLANKS);
        set(4, 5, 28, Material.LANTERN);
        set(26, 5, 28, Material.LANTERN);
        set(CX, 5, 30, Material.LANTERN);
    }

    private Stage stage() {
        Stage s = new Stage();
        s.arenaWorld(w.getName());
        s.set(Stage.ARENA_A, p(CX + 0.5 - MARK, 0, CZ + 0.5));
        s.set(Stage.ARENA_B, p(CX + 0.5 + MARK, 0, CZ + 0.5));
        s.set(Stage.ARENA_CENTER, p(CX + 0.5, 0, CZ + 0.5));
        s.set(Stage.ARENA_VIEW, p(CX + 0.5, 3, 29.5));
        s.arenaViewYaw(180);
        return s;
    }
}
