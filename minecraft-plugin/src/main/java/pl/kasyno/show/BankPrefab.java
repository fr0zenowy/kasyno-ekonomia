package pl.kasyno.show;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import pl.kasyno.gui.T;
import pl.kasyno.show.Stage.P;

import java.util.HashMap;
import java.util.Map;

/**
 * Gotowy bank do pokazu napadu, stawiany jedną komendą: klasycystyczny gmach z sześciokolumnowym portykiem, frontonem, schodami,
 * halą pod kopułą, ladami i skarbcem z kołem na drzwiach, a przed nim ulica z vanem i radiowozem. Projekt (BankPlan) to same dane;
 * tutaj nakładamy go na świat i zamieniamy jego punkty w scenę pokazu. Powstaje na południe i wschód od miejsca, w którym stoi
 * administrator (oś Z rośnie na południe, X na wschód), więc potrzeba płaskiego terenu WIDTH x DEPTH bloków i około 28 bloków wolnej wysokości.
 */
public final class BankPrefab {
    /** Wymiary całości (X, Z), żeby administrator wiedział, ile miejsca potrzeba. */
    public static final int WIDTH = BankPlan.WIDTH;
    public static final int DEPTH = BankPlan.DEPTH;
    /** Znacznik napisów banku w danych encji, żeby przy ponownym budowaniu stary napis nie został w powietrzu. */
    public static final NamespacedKey TAG = NamespacedKey.fromString("kasyno:bank");

    private BankPrefab() {}

    /** Stawia bank w świecie `origin` i zwraca komplet punktów sceny. */
    public static Stage build(Location origin) {
        World w = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        BankPlan plan = BankPlan.create();
        purgeTexts(w, ox, oy, oz);
        apply(plan, w, ox, oy, oz);
        for (BankPlan.Text t : plan.texts()) spawnText(w, ox, oy, oz, t);
        return stage(plan, w.getName(), ox, oy, oz);
    }

    private static void quiet(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // ustawienie kosmetyczne
        }
    }

    // ---------- bloki ----------

    private static BlockData parse(String state) {
        try {
            return Bukkit.createBlockData(state);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Nakłada plan na świat bez fizyki bloków (drzwi, latarnie i dywany stoją tak, jak je ustawiono). */
    static void apply(BankPlan plan, World w, int ox, int oy, int oz) {
        Map<String, BlockData> parsed = new HashMap<>();
        Map<String, Material> fallback = new HashMap<>();
        for (int x = 0; x < BankPlan.WIDTH; x++) {
            for (int y = BankPlan.MIN_Y; y <= BankPlan.MAX_Y; y++) {
                for (int z = 0; z < BankPlan.DEPTH; z++) {
                    String state = plan.get(x, y, z);
                    if (state == null) continue;
                    Block b = w.getBlockAt(ox + x, oy + y, oz + z);
                    if (state.equals("minecraft:air")) {
                        // czyszczenie bryły: ruszamy tylko to, co naprawdę stoi w powietrzu, bo to większość z 40 tysięcy komórek
                        if (!b.getType().isAir()) b.setType(Material.AIR, false);
                        continue;
                    }
                    if (!parsed.containsKey(state)) parsed.put(state, parse(state));
                    BlockData data = parsed.get(state);
                    if (data != null) {
                        try {
                            b.setBlockData(data, false);
                            continue;
                        } catch (RuntimeException ignored) {
                            // spróbujemy samego materiału
                        }
                    }
                    Material m = fallback.computeIfAbsent(state, s -> {
                        Material found = Material.matchMaterial(BankPlan.material(s));
                        return found == null ? Material.AIR : found;
                    });
                    b.setType(m, false);
                }
            }
        }
    }

    // ---------- napisy ----------

    private static void purgeTexts(World w, int ox, int oy, int oz) {
        if (TAG == null) return;
        try {
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class)) {
                if (!d.getPersistentDataContainer().has(TAG, PersistentDataType.STRING)) continue;
                Location l = d.getLocation();
                if (l.getX() >= ox - 2 && l.getX() <= ox + BankPlan.WIDTH + 2 && l.getZ() >= oz - 2 && l.getZ() <= oz + BankPlan.DEPTH + 2
                        && l.getY() >= oy + BankPlan.MIN_Y - 2 && l.getY() <= oy + BankPlan.MAX_Y + 2) d.remove();
            }
        } catch (RuntimeException ignored) {
            // bez sprzątania stary napis zostanie, co najwyżej obok nowego
        }
    }

    private static void spawnText(World w, int ox, int oy, int oz, BankPlan.Text t) {
        try {
            w.spawn(new Location(w, ox + t.x(), oy + t.y(), oz + t.z()), TextDisplay.class, d -> {
                d.text(T.MM.deserialize(t.text()));
                d.setPersistent(true);
                quiet(() -> d.setBillboard(Display.Billboard.VERTICAL));
                quiet(() -> d.setShadowed(true));
                quiet(() -> d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0)));
                quiet(() -> d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(t.scale(), t.scale(), t.scale()), new AxisAngle4f())));
                quiet(() -> d.setViewRange(1.5f));
                if (TAG != null) d.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, "1");
            });
        } catch (RuntimeException e) {
            // bez napisu nad wejściem bank nadal stoi
        }
    }

    // ---------- scena ----------

    private static Stage stage(BankPlan plan, String world, int ox, int oy, int oz) {
        Stage s = new Stage();
        s.world(world);
        for (Map.Entry<String, BankPlan.Point> e : plan.points().entrySet()) {
            BankPlan.Point p = e.getValue();
            s.set(e.getKey(), new P(ox + p.x(), oy + p.y(), oz + p.z()));
        }
        s.viewYaw(plan.viewYaw());
        return s;
    }

    /** Czy encja jest napisem postawionym przez bank (do sprzątania i testów). */
    public static boolean isBankText(Entity e) {
        return TAG != null && e instanceof TextDisplay && e.getPersistentDataContainer().has(TAG, PersistentDataType.STRING);
    }
}
