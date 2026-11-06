package pl.kasyno.npc;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Display;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.games.GameRegistry;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Screens;
import pl.kasyno.gui.T;
import pl.kasyno.work.JobDef;
import pl.kasyno.work.Jobs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Placeholdery: niewidzialne pola kliknięcia (encje Interaction) stawiane nad automatem, stołem albo makietą zbudowaną z bloków.
 * Prawy przycisk myszy skierowany mniej więcej w to pole otwiera przypisany ekran albo grę, tak samo jak kliknięcie NPC, tylko bez
 * żadnej postaci. Pole ma wybraną szerokość i wysokość, a nazwa gry zapisuje się w danych encji (PersistentDataContainer),
 * więc przeżywa restart serwera. Nad polem wisi napis z nazwą gry (domyślny, własny albo wyłączony), który znika razem z polem.
 */
public final class PlaceholderManager implements Listener {
    public static final float DEFAULT_SIZE = 1.0f;
    public static final float MIN_SIZE = 0.1f;
    public static final float MAX_SIZE = 16.0f;
    /** Wartość własnego napisu, która wyłącza napis nad polem. */
    public static final String LABEL_OFF = "off";

    private static final Map<String, String> SCREEN_NAMES = Map.ofEntries(
            Map.entry("menu", "Menu"), Map.entry("kasyno", "Kasyno"), Map.entry("zarobki", "Zarobki"), Map.entry("sklep", "Sklep"),
            Map.entry("ekwipunek", "Ekwipunek"), Map.entry("bank", "Bank"), Map.entry("biznes", "Biznes i farma"), Map.entry("skrzynie", "Skrzynie"),
            Map.entry("ranking", "Ranking"), Map.entry("profil", "Profil"), Map.entry("pokoj", "Pokój graczy"), Map.entry("pomoc", "Pomoc"));

    private static final Color OUTLINE = Color.fromRGB(0, 220, 255);
    private static final double OUTLINE_STEP = 0.25;
    private static final double SHOW_RADIUS = 48;

    private final KasynoPlugin plugin;
    private final NamespacedKey gameKey;
    private final NamespacedKey labelKey;
    private final NamespacedKey textKey;
    private final NamespacedKey holoKey;
    private final Map<UUID, BukkitTask> showing = new HashMap<>();

    public PlaceholderManager(KasynoPlugin plugin) {
        this.plugin = plugin;
        this.gameKey = new NamespacedKey(plugin, "placeholder_game");
        this.labelKey = new NamespacedKey(plugin, "placeholder_label");
        this.textKey = new NamespacedKey(plugin, "placeholder_text");
        this.holoKey = new NamespacedKey(plugin, "placeholder_holo");
    }

    /** Nazwy, jakie da się przypisać (menu, ekrany i wszystkie gry), te same co u NPC. */
    public List<String> types() {
        return Screens.allKeys();
    }

    /** Ogranicza wymiar pola do rozsądnego zakresu (encja Interaction i tak nie przyjmie wartości spoza niego). */
    public static float clamp(double size) {
        if (Double.isNaN(size)) return DEFAULT_SIZE;
        return (float) Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
    }

    /**
     * Stawia pole kliknięcia. Środek jego podstawy leży w podanym miejscu, a pole sięga `width` na boki i `height` w górę.
     * Zwraca null dla nieznanej nazwy gry.
     */
    public Interaction spawn(Location loc, String game, float width, float height) {
        if (!Screens.exists(game)) return null;
        World world = loc.getWorld();
        float w = clamp(width);
        float h = clamp(height);
        Interaction in = world.spawn(loc.clone(), Interaction.class, e -> {
            e.getPersistentDataContainer().set(gameKey, PersistentDataType.STRING, game);
            e.setInteractionWidth(w);
            e.setInteractionHeight(h);
            e.setResponsive(true);
            e.setPersistent(true);
        });
        refreshLabel(in);
        return in;
    }

    // ---------- napis nad polem ----------

    /** Nazwa do napisu: nazwa gry, sali albo ekranu (np. "Blackjack", "Karty i kości", "Bank"). */
    public String titleOf(String game) {
        GameRegistry.Def g = GameRegistry.find(game);
        if (g != null) return g.name();
        GameRegistry.Hall h = GameRegistry.hall(game);
        if (h != null) return h.name();
        JobDef job = Jobs.find(game);
        if (job != null) return job.title();
        return SCREEN_NAMES.getOrDefault(game, game);
    }

    /** Domyślny napis (MiniMessage): nazwa gry pogrubiona i podpowiedź, co zrobić. */
    public String defaultText(String game) {
        String color = plugin.getConfig().getString("npc.name-color", "<gold>");
        return color + "<bold>" + T.esc(titleOf(game)) + "</bold><newline><gray>Kliknij prawym przyciskiem";
    }

    /** Własny napis pola (MiniMessage), LABEL_OFF dla wyłączonego albo null, gdy pole ma napis domyślny. */
    public String customText(Interaction in) {
        return in.getPersistentDataContainer().get(textKey, PersistentDataType.STRING);
    }

    /** Ustawia własny napis (MiniMessage), LABEL_OFF wyłącza napis, null przywraca domyślny. Napis od razu się odświeża. */
    public void setText(Interaction in, String text) {
        if (text == null) in.getPersistentDataContainer().remove(textKey);
        else in.getPersistentDataContainer().set(textKey, PersistentDataType.STRING, text);
        refreshLabel(in);
    }

    /** Napis wiszący nad polem (null, gdy go nie ma albo chunk jest niezaładowany). */
    public TextDisplay labelOf(Interaction in) {
        String id = in.getPersistentDataContainer().get(labelKey, PersistentDataType.STRING);
        if (id == null) return null;
        try {
            return Bukkit.getEntity(UUID.fromString(id)) instanceof TextDisplay t ? t : null;
        } catch (IllegalArgumentException e) {
            return null; // uszkodzony wpis
        }
    }

    /** Stawia napis od nowa według ustawienia pola. Bez napisu (np. wyłączonego) pole nadal działa. */
    public void refreshLabel(Interaction in) {
        removeLabel(in);
        String custom = customText(in);
        if (LABEL_OFF.equals(custom)) return;
        String body = custom != null ? custom : defaultText(gameOf(in));
        try {
            Location at = in.getLocation().clone().add(0, in.getInteractionHeight() + 0.3, 0);
            TextDisplay label = in.getWorld().spawn(at, TextDisplay.class, t -> {
                t.text(T.MM.deserialize(body));
                // wygląd jest kosmetyczny: gdyby któreś ustawienie nie było dostępne, napis i tak ma się pojawić
                quiet(() -> t.setBillboard(Display.Billboard.CENTER));
                quiet(() -> t.setShadowed(true));
                t.setPersistent(true);
                t.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, "1");
            });
            in.getPersistentDataContainer().set(labelKey, PersistentDataType.STRING, label.getUniqueId().toString());
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Nie udało się postawić napisu nad placeholderem: " + e);
        }
    }

    private static void quiet(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // ustawienie kosmetyczne
        }
    }

    private void removeLabel(Interaction in) {
        TextDisplay label = labelOf(in);
        if (label != null) label.remove();
        in.getPersistentDataContainer().remove(labelKey);
    }

    public boolean isPlaceholder(Entity e) {
        return e instanceof Interaction && e.getPersistentDataContainer().has(gameKey, PersistentDataType.STRING);
    }

    /** Gra albo ekran przypisany do pola (null, gdy to nie jest placeholder). */
    public String gameOf(Entity e) {
        return e.getPersistentDataContainer().get(gameKey, PersistentDataType.STRING);
    }

    /** Wszystkie placeholdery w załadowanych chunkach. */
    public List<Interaction> all() {
        List<Interaction> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) for (Interaction in : w.getEntitiesByClass(Interaction.class)) if (isPlaceholder(in)) out.add(in);
        return out;
    }

    public int count() {
        return all().size();
    }

    /** Usuwa pole razem z napisem nad nim. */
    public void remove(Interaction in) {
        removeLabel(in);
        in.remove();
    }

    /** Usuwa wszystkie pola w załadowanych chunkach i osierocone napisy po nich. Zwraca liczbę usuniętych pól. */
    public int removeAll() {
        int n = 0;
        for (Interaction in : all()) {
            remove(in);
            n++;
        }
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay t : w.getEntitiesByClass(TextDisplay.class)) {
                if (t.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) t.remove();
            }
        }
        return n;
    }

    /** Pole, na które patrzy gracz, a gdy żadne nie jest na celowniku, najbliższe w promieniu `range` bloków (liczone do jego krawędzi). */
    public Interaction nearest(Player p, double range) {
        Entity target = null;
        try {
            target = p.getTargetEntity((int) Math.ceil(range));
        } catch (RuntimeException ignored) {
            // bez promienia patrzenia szukamy po prostu najbliższego pola
        }
        if (target != null && isPlaceholder(target)) return (Interaction) target;
        Interaction best = null;
        double bestDist = range * range;
        Location eye = p.getEyeLocation();
        for (Interaction in : all()) {
            if (!in.getWorld().equals(p.getWorld())) continue;
            double d = distanceSquared(in.getBoundingBox(), eye);
            if (d <= bestDist) {
                best = in;
                bestDist = d;
            }
        }
        return best;
    }

    /** Kwadrat odległości punktu od pola (0, gdy punkt leży w środku). */
    static double distanceSquared(BoundingBox box, Location at) {
        double dx = Math.max(Math.max(box.getMinX() - at.getX(), 0), at.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - at.getY(), 0), at.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - at.getZ(), 0), at.getZ() - box.getMaxZ());
        return dx * dx + dy * dy + dz * dz;
    }

    // ---------- podgląd pól (są niewidzialne) ----------

    /** Na `seconds` sekund rysuje cząsteczkami obrys wszystkich pól w pobliżu gracza, żeby dało się je ustawić na oko. */
    public void show(Player p, int seconds) {
        hide(p);
        int ticks = Math.max(1, seconds) * 20;
        int[] left = {ticks};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline() || (left[0] -= 10) <= 0) {
                hide(p);
                return;
            }
            Location at = p.getLocation();
            for (Interaction in : all()) {
                if (!in.getWorld().equals(p.getWorld()) || in.getLocation().distanceSquared(at) > SHOW_RADIUS * SHOW_RADIUS) continue;
                outline(p, in.getBoundingBox());
            }
        }, 0L, 10L);
        showing.put(p.getUniqueId(), task);
    }

    public void hide(Player p) {
        BukkitTask t = showing.remove(p.getUniqueId());
        if (t != null) t.cancel();
    }

    public boolean isShowing(Player p) {
        return showing.containsKey(p.getUniqueId());
    }

    private void outline(Player p, BoundingBox b) {
        double[] xs = {b.getMinX(), b.getMaxX()};
        double[] ys = {b.getMinY(), b.getMaxY()};
        double[] zs = {b.getMinZ(), b.getMaxZ()};
        for (double y : ys) {
            for (double z : zs) line(p, b.getMinX(), y, z, b.getMaxX(), y, z);
            for (double x : xs) line(p, x, y, b.getMinZ(), x, y, b.getMaxZ());
        }
        for (double x : xs) for (double z : zs) line(p, x, b.getMinY(), z, x, b.getMaxY(), z);
    }

    private void line(Player p, double x1, double y1, double z1, double x2, double y2, double z2) {
        double len = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        int steps = Math.max(1, (int) Math.ceil(len / OUTLINE_STEP));
        Particle.DustOptions dust = new Particle.DustOptions(OUTLINE, 1.0f);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Location at = new Location(p.getWorld(), x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t);
            try {
                p.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
            } catch (RuntimeException ignored) {
                return; // podgląd jest kosmetyczny
            }
        }
    }

    // ---------- kliknięcia ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!isPlaceholder(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("kasyno.use")) {
            T.send(p, "<red>Nie masz uprawnień do kasyna.");
            return;
        }
        String game = gameOf(e.getRightClicked());
        Fx.click(p);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!Screens.open(plugin, p, game)) T.send(p, "<red>To pole jest źle skonfigurowane (gra: " + T.esc(String.valueOf(game)) + ").");
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        hide(e.getPlayer());
    }
}
