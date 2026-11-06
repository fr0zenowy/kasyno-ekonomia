package pl.kasyno.waypoint;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.gui.T;

/**
 * Znaczniki waypointów w prawdziwym świecie: napis z nazwą i odległością (TextDisplay, widoczny przez ściany, o stałym rozmiarze) i
 * opcjonalna cienka, jasna kolumna (BlockDisplay) z poświatą w kolorze waypointu. Obie encje są ukryte dla wszystkich i pokazane
 * tylko jednemu graczowi (setVisibleByDefault(false) + Player#showEntity), nie zapisują się w świecie i mają znacznik, po którym
 * purge() je sprząta. Ustawienia kosmetyczne są osłonięte: gdy któreś nie zadziała, znacznik i tak stoi.
 */
public final class BukkitMarkerBackend implements MarkerBackend {
    /** Znacznik w danych encji do sprzątania zostawionych znaczników. */
    public static final NamespacedKey TAG = NamespacedKey.fromString("kasyno:waypoint_marker");

    /** Grubość kolumny w blokach. */
    static final float BEAM_WIDTH = 0.14f;

    /** Znacznik gracza: encje i to, co już na nich ustawiono, żeby przy ruchu nie wysyłać niezmienionych danych. */
    private static final class Marker {
        final BlockDisplay beam;
        final TextDisplay label;
        String text;
        float scale;
        int color;
        double height;

        Marker(BlockDisplay beam, TextDisplay label, Spec s) {
            this.beam = beam;
            this.label = label;
            this.text = s.label();
            this.scale = s.scale();
            this.color = s.color();
            this.height = s.beamHeight();
        }
    }

    private final KasynoPlugin plugin;
    private boolean warned;

    public BukkitMarkerBackend(KasynoPlugin plugin) {
        this.plugin = plugin;
    }

    private static void quiet(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // ustawienie kosmetyczne
        }
    }

    private static void mark(Entity e) {
        if (TAG != null) e.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, "1");
    }

    private static Transformation beamTransform(double height) {
        return new Transformation(new Vector3f(-BEAM_WIDTH / 2, 0, -BEAM_WIDTH / 2), new Quaternionf(), new Vector3f(BEAM_WIDTH, (float) height, BEAM_WIDTH), new Quaternionf());
    }

    private static Transformation labelTransform(float scale) {
        return new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f());
    }

    private static BlockData beamBlock() {
        try {
            return Bukkit.createBlockData(Material.WHITE_CONCRETE);
        } catch (RuntimeException e) {
            return Material.WHITE_CONCRETE.createBlockData();
        }
    }

    @Override
    public Object spawn(Player owner, Spec s) {
        try {
            World w = owner.getWorld();
            Color glow = Color.fromRGB(s.color() & 0xFFFFFF);
            BlockDisplay beam = null;
            TextDisplay label = null;
            if (s.beam()) {
                beam = w.spawn(new Location(w, s.beamX(), s.beamY(), s.beamZ()), BlockDisplay.class, d -> {
                    d.setBlock(beamBlock());
                    quiet(() -> d.setVisibleByDefault(false));
                    quiet(() -> d.setPersistent(false));
                    quiet(() -> d.setBillboard(Display.Billboard.FIXED));
                    quiet(() -> d.setTransformation(beamTransform(s.beamHeight())));
                    quiet(() -> d.setBrightness(new Display.Brightness(15, 15)));
                    quiet(() -> d.setGlowing(true));
                    quiet(() -> d.setGlowColorOverride(glow));
                    quiet(() -> d.setViewRange(3.0f));
                    quiet(() -> d.setTeleportDuration(WaypointManager.PERIOD_TICKS));
                    mark(d);
                });
            }
            if (s.showLabel()) {
                label = w.spawn(new Location(w, s.labelX(), s.labelY(), s.labelZ()), TextDisplay.class, t -> {
                    t.text(T.MM.deserialize(s.label()));
                    quiet(() -> t.setVisibleByDefault(false));
                    quiet(() -> t.setPersistent(false));
                    quiet(() -> t.setBillboard(Display.Billboard.CENTER));
                    quiet(() -> t.setSeeThrough(true));
                    quiet(() -> t.setShadowed(true));
                    quiet(() -> t.setAlignment(TextDisplay.TextAlignment.CENTER));
                    quiet(() -> t.setBackgroundColor(Color.fromARGB(110, 0, 0, 0)));
                    quiet(() -> t.setTransformation(labelTransform(s.scale())));
                    quiet(() -> t.setViewRange(2.0f));
                    quiet(() -> t.setTeleportDuration(WaypointManager.PERIOD_TICKS));
                    mark(t);
                });
            }
            if (beam != null) {
                BlockDisplay b = beam;
                quiet(() -> owner.showEntity(plugin, b));
            }
            if (label != null) {
                TextDisplay l = label;
                quiet(() -> owner.showEntity(plugin, l));
            }
            return new Marker(beam, label, s);
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Waypointy: nie udało się postawić znacznika (" + e + ").");
            }
            return null;
        }
    }

    /**
     * Przesuwa znacznik (co tick, więc tylko pozycja idzie za każdym razem) i przepisuje to, co się naprawdę zmieniło: napis, rozmiar
     * (płynnie przez 3 ticki), kolor i wysokość kreski.
     */
    @Override
    public void update(Object handle, Spec s) {
        if (!(handle instanceof Marker m)) return;
        if (m.beam != null && m.beam.isValid()) {
            quiet(() -> m.beam.teleport(new Location(m.beam.getWorld(), s.beamX(), s.beamY(), s.beamZ())));
            if (m.height != s.beamHeight()) {
                m.height = s.beamHeight();
                quiet(() -> m.beam.setTransformation(beamTransform(s.beamHeight())));
            }
            if (m.color != s.color()) {
                m.color = s.color();
                quiet(() -> m.beam.setGlowColorOverride(Color.fromRGB(s.color() & 0xFFFFFF)));
            }
        }
        if (m.label != null && m.label.isValid()) {
            quiet(() -> m.label.teleport(new Location(m.label.getWorld(), s.labelX(), s.labelY(), s.labelZ())));
            if (!m.text.equals(s.label())) {
                m.text = s.label();
                quiet(() -> m.label.text(T.MM.deserialize(s.label())));
            }
            if (Math.abs(m.scale - s.scale()) > 1e-4f) {
                m.scale = s.scale();
                quiet(() -> {
                    m.label.setInterpolationDelay(0);
                    m.label.setInterpolationDuration(3);
                    m.label.setTransformation(labelTransform(s.scale()));
                });
            }
        }
    }

    @Override
    public void remove(Object handle) {
        if (!(handle instanceof Marker m)) return;
        if (m.beam != null) quiet(() -> m.beam.remove());
        if (m.label != null) quiet(() -> m.label.remove());
    }

    @Override
    public void purge() {
        if (TAG == null) return;
        for (World w : Bukkit.getWorlds()) {
            quiet(() -> {
                for (Display d : w.getEntitiesByClass(Display.class)) {
                    if (d.getPersistentDataContainer().has(TAG, PersistentDataType.STRING)) d.remove();
                }
            });
        }
    }
}
