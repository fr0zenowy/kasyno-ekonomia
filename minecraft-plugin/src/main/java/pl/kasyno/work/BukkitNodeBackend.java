package pl.kasyno.work;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.gui.T;
import pl.kasyno.show.Stage.P;
import pl.kasyno.work.JobDef.NodeType;

/**
 * Cele w prawdziwym świecie: świecący przedmiot (ItemDisplay z kolorową poświatą) i niewidzialne pole kliknięcia (Interaction).
 * Oba są ukryte dla wszystkich i pokazane tylko właścicielowi (setVisibleByDefault(false) + Player#showEntity), więc każdy gracz
 * ma własne grzyby, a inni ich nie widzą ani nie mogą zerwać. Ustawienia kosmetyczne są osłonięte: gdy któreś nie zadziała, cel
 * i tak stoi i da się go kliknąć.
 */
public final class BukkitNodeBackend implements NodeBackend {
    /** Znacznik w danych encji do sprzątania zostawionych celów. */
    public static final NamespacedKey TAG = NamespacedKey.fromString("kasyno:work");

    private record Node(ItemDisplay display, Interaction click) {}

    private final KasynoPlugin plugin;
    private boolean warned;

    public BukkitNodeBackend(KasynoPlugin plugin) {
        this.plugin = plugin;
    }

    private static void quiet(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // ustawienie kosmetyczne
        }
    }

    private static Color color(String hex) {
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16));
        } catch (RuntimeException e) {
            return Color.WHITE;
        }
    }

    private static void mark(Entity e) {
        if (TAG != null) e.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, "1");
    }

    @Override
    public Spawned spawn(Player owner, NodeType type, P at, boolean visible) {
        try {
            World w = owner.getWorld();
            Material mat = Material.matchMaterial(type.icon());
            ItemStack item = new ItemStack(mat == null ? Material.BARRIER : mat);
            ItemDisplay display = w.spawn(new Location(w, at.x(), at.y() + 0.55, at.z()), ItemDisplay.class, d -> {
                d.setItemStack(item);
                quiet(() -> d.setVisibleByDefault(false));
                quiet(() -> d.setPersistent(false));
                quiet(() -> d.setBillboard(Display.Billboard.FIXED));
                quiet(() -> d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.75f, 0.75f, 0.75f), new AxisAngle4f())));
                quiet(() -> d.setGlowing(true));
                quiet(() -> d.setGlowColorOverride(color(type.glow())));
                quiet(() -> d.setViewRange(1.5f));
                mark(d);
            });
            Interaction click = w.spawn(new Location(w, at.x(), at.y(), at.z()), Interaction.class, e -> {
                quiet(() -> e.setVisibleByDefault(false));
                quiet(() -> e.setPersistent(false));
                quiet(() -> e.setInteractionWidth(1.1f));
                quiet(() -> e.setInteractionHeight(1.1f));
                quiet(() -> e.setResponsive(true));
                mark(e);
            });
            Node n = new Node(display, click);
            if (visible) reveal(owner, n);
            return new Spawned(n, click.getUniqueId());
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Praca: nie udało się postawić celu (" + e + ").");
            }
            return null;
        }
    }

    @Override
    public void reveal(Player owner, Object handle) {
        if (handle instanceof Node n) {
            quiet(() -> owner.showEntity(plugin, n.display()));
            quiet(() -> owner.showEntity(plugin, n.click()));
        }
    }

    @Override
    public void move(Object handle, P at) {
        if (handle instanceof Node n) {
            quiet(() -> n.display().teleport(new Location(n.display().getWorld(), at.x(), at.y() + 0.55, at.z())));
            quiet(() -> n.click().teleport(new Location(n.click().getWorld(), at.x(), at.y(), at.z())));
        }
    }

    @Override
    public void spin(Object handle, int step) {
        if (handle instanceof Node n) {
            quiet(() -> {
                n.display().setInterpolationDelay(0);
                n.display().setInterpolationDuration(10);
                float angle = (float) Math.toRadians((step * 36) % 360);
                n.display().setTransformation(new Transformation(new Vector3f(0, (float) (0.06 * Math.sin(step * 0.6)), 0), new AxisAngle4f(angle, 0, 1, 0), new Vector3f(0.75f, 0.75f, 0.75f), new AxisAngle4f()));
            });
        }
    }

    @Override
    public void remove(Object handle) {
        if (handle instanceof Node n) {
            quiet(() -> n.display().remove());
            quiet(() -> n.click().remove());
        }
    }

    @Override
    public void sparkle(Player owner, P at, boolean rare) {
        quiet(() -> owner.spawnParticle(rare ? Particle.END_ROD : Particle.HAPPY_VILLAGER, new Location(owner.getWorld(), at.x(), at.y() + 0.7, at.z()), rare ? 3 : 2, 0.25, 0.3, 0.25, 0.01));
    }

    @Override
    public void popup(Player owner, String mm, P at) {
        try {
            World w = owner.getWorld();
            TextDisplay t = w.spawn(new Location(w, at.x(), at.y() + 1.3, at.z()), TextDisplay.class, d -> {
                d.text(T.MM.deserialize(mm));
                quiet(() -> d.setVisibleByDefault(false));
                quiet(() -> d.setPersistent(false));
                quiet(() -> d.setBillboard(Display.Billboard.CENTER));
                quiet(() -> d.setShadowed(true));
                mark(d);
            });
            quiet(() -> owner.showEntity(plugin, t));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (t.isValid()) t.remove();
            }, 30L);
        } catch (RuntimeException e) {
            // napis jest tylko dodatkiem
        }
    }

    @Override
    public void purge() {
        if (TAG == null) return;
        for (World w : Bukkit.getWorlds()) {
            quiet(() -> {
                for (Entity e : w.getEntities()) {
                    if ((e instanceof ItemDisplay || e instanceof Interaction || e instanceof TextDisplay) && e.getPersistentDataContainer().has(TAG, PersistentDataType.STRING)) e.remove();
                }
            });
        }
    }
}
