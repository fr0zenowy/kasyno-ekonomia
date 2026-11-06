package pl.kasyno.shark;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/** Rekin z BlockDisplay: każda bryła modelu to osobny wyświetlacz w jednym punkcie (środek rekina), a pozę niesie jego przekształcenie. */
public final class BukkitSharkBackend implements SharkBackend {
    /** Ile ticków klient wygładza przesunięcie i zmianę pozy (więcej = płynniej, ale większe opóźnienie). */
    private static final int SMOOTH_TICKS = 2;

    private static final class Shark {
        final World world;
        final List<BlockDisplay> parts = new ArrayList<>();

        Shark(World world) {
            this.world = world;
        }
    }

    private final NamespacedKey tag;

    public BukkitSharkBackend(Plugin plugin) {
        this.tag = new NamespacedKey(plugin, "shark");
    }

    private static void quiet(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // ustawienie kosmetyczne: gdyby któreś nie było dostępne, rekin i tak ma działać
        }
    }

    private static Transformation transformation(SharkModel.Xform x) {
        return new Transformation(x.translation(), x.rotation(), x.scale(), new Quaternionf());
    }

    @Override
    public Object spawn(World world, SharkChoreo.Frame f) {
        Shark s = new Shark(world);
        Location at = new Location(world, f.x(), f.y(), f.z());
        for (SharkModel.Part part : SharkModel.PARTS) {
            Material m = Material.matchMaterial(part.block());
            if (m == null) m = Material.GRAY_CONCRETE;
            final Material block = m;
            SharkModel.Xform x = SharkModel.xform(part, f.pose(), f.orientation());
            BlockDisplay d = world.spawn(at, BlockDisplay.class, e -> {
                e.setBlock(Bukkit.createBlockData(block));
                e.setPersistent(false);
                e.getPersistentDataContainer().set(tag, PersistentDataType.STRING, "1");
                quiet(() -> e.setTransformation(transformation(x)));
                quiet(() -> e.setViewRange(2.5f));
                quiet(() -> e.setBrightness(new Display.Brightness(11, 11)));
                quiet(() -> e.setTeleportDuration(SMOOTH_TICKS));
                quiet(() -> e.setInterpolationDuration(SMOOTH_TICKS));
            });
            s.parts.add(d);
        }
        return s;
    }

    @Override
    public void update(Object handle, SharkChoreo.Frame f) {
        if (!(handle instanceof Shark s)) return;
        Location at = new Location(s.world, f.x(), f.y(), f.z());
        for (int i = 0; i < s.parts.size() && i < SharkModel.PARTS.size(); i++) {
            BlockDisplay d = s.parts.get(i);
            if (!d.isValid()) continue;
            SharkModel.Xform x = SharkModel.xform(SharkModel.PARTS.get(i), f.pose(), f.orientation());
            quiet(() -> d.setInterpolationDelay(0));
            quiet(() -> d.setInterpolationDuration(SMOOTH_TICKS));
            quiet(() -> d.setTransformation(transformation(x)));
            d.teleport(at);
        }
    }

    @Override
    public void remove(Object handle) {
        if (!(handle instanceof Shark s)) return;
        for (BlockDisplay d : s.parts) d.remove();
        s.parts.clear();
    }

    @Override
    public int purge() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (BlockDisplay d : w.getEntitiesByClass(BlockDisplay.class)) {
                if (d.getPersistentDataContainer().has(tag, PersistentDataType.STRING)) {
                    d.remove();
                    n++;
                }
            }
        }
        return n;
    }
}
