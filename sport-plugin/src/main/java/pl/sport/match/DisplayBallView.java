package pl.sport.match;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import pl.sport.SportPlugin;

/**
 * Piłka jako ItemDisplay: płynny ruch dzięki interpolacji pozycji między tickami, obracanie podczas lotu i (opcjonalnie) cień na podłodze,
 * który pomaga ocenić, gdzie piłka spadnie. Encja nie jest zapisywana na dysku, a po awarii serwera czyści ją znacznik.
 */
public final class DisplayBallView implements BallView {
    private final ItemDisplay ball;
    private final BlockDisplay shadow;
    private final float size;
    private final boolean flat;
    private final Quaternionf orientation = new Quaternionf();
    private boolean removed;

    public DisplayBallView(SportPlugin plugin, Location where, BallKind kind, double radius) {
        World w = where.getWorld();
        NamespacedKey tag = plugin.ballTag();
        var cfg = plugin.sportConfig();
        int interp = Math.max(0, Math.min(10, cfg.integer("pilka.interpolacja-ticki", 1)));
        Material mat = cfg.ballItem(kind);
        String model = cfg.ballModel(kind);
        // sprite przedmiotu zajmuje ok. 3/4 szerokości pola, więc powiększamy go tak, żeby średnica piłki się zgadzała
        this.flat = model.isEmpty();
        boolean sprite = this.flat;
        this.size = (float) (radius * 2 * (sprite ? 1.45 : 1.0));
        this.ball = w.spawn(where, ItemDisplay.class, d -> {
            ItemStack stack = new ItemStack(mat);
            if (!sprite) {
                ItemMeta meta = stack.getItemMeta();
                NamespacedKey key = NamespacedKey.fromString(model);
                if (meta != null && key != null) {
                    meta.setItemModel(key);
                    stack.setItemMeta(meta);
                }
            }
            d.setItemStack(stack);
            d.setPersistent(false);
            d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            safe(() -> d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED));
            safe(() -> d.setBillboard(sprite ? Display.Billboard.CENTER : Display.Billboard.FIXED));
            safe(() -> d.setTeleportDuration(interp));
            safe(() -> d.setViewRange(2f));
            safe(() -> d.setBrightness(new Display.Brightness(15, 15)));
            safe(() -> d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(size, size, size), new Quaternionf())));
        });
        if (cfg.shadow()) {
            this.shadow = w.spawn(where, BlockDisplay.class, d -> {
                d.setBlock(Material.GRAY_STAINED_GLASS.createBlockData());
                d.setPersistent(false);
                d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
                float s = (float) (radius * 2.6);
                safe(() -> d.setTransformation(new Transformation(new Vector3f(-s / 2, 0, -s / 2), new Quaternionf(), new Vector3f(s, 0.015f, s), new Quaternionf())));
                safe(() -> d.setTeleportDuration(interp));
                safe(() -> d.setViewRange(1.2f));
            });
        } else {
            this.shadow = null;
        }
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // ustawienie kosmetyczne niedostępne w tym środowisku (np. w atrapie serwera)
        }
    }

    @Override
    public void move(Location center, double floorY) {
        if (removed) return;
        if (ball.isValid()) ball.teleport(center);
        if (shadow != null && shadow.isValid()) shadow.teleport(new Location(center.getWorld(), center.getX(), floorY + 0.012, center.getZ()));
    }

    @Override
    public void spin(double dirX, double dirZ, double degrees) {
        // sprite zawsze patrzy na gracza, więc kręcić ma sens tylko model 3D z paczki zasobów
        if (removed || flat) return;
        // przetaczanie: obrót wokół osi poziomej prostopadłej do kierunku ruchu; narasta, więc piłka kręci się ciągle
        float len = (float) Math.hypot(dirX, dirZ);
        if (len < 1e-4f) return;
        Quaternionf step = new Quaternionf().rotateAxis((float) Math.toRadians(degrees), (float) (dirZ / len), 0f, (float) (-dirX / len));
        orientation.premul(step).normalize();
        safe(() -> {
            ball.setInterpolationDelay(0);
            ball.setInterpolationDuration(2);
            ball.setTransformation(new Transformation(new Vector3f(), orientation, new Vector3f(size, size, size), new Quaternionf()));
        });
    }

    @Override
    public void remove() {
        removed = true;
        if (ball != null) ball.remove();
        if (shadow != null) shadow.remove();
    }

    @Override
    public boolean valid() {
        return !removed && ball.isValid();
    }
}
