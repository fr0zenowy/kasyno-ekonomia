package pl.kasyno.show;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sceny pokazów w świecie: nazwane zestawy punktów w jednym świecie. Są dwie niezależne grupy:
 * <ul>
 *   <li>bank (napad): gdzie wysiada ekipa, drzwi banku, hol, skarbiec, miejsce dla widzów itd.,</li>
 *   <li>arena (pojedynek i jackpot): stanowiska dwóch rewolwerowców, środek okręgu i miejsce dla widzów.</li>
 * </ul>
 * Każda grupa ma własny świat, więc bank i arena mogą stać nawet w różnych światach. Powstają automatycznie z prefabrykatów
 * (BankPrefab, ArenaPrefab) albo z punktów ustawionych ręcznie przez administratora, a trafiają do pliku stage.yml, więc
 * przeżywają restart serwera.
 */
public final class Stage {
    /** Punkt w świecie (pozycja stóp); współrzędne bloków do drzwi i lamp zapisujemy tak samo. */
    public record P(double x, double y, double z) {
        public P add(double dx, double dy, double dz) {
            return new P(x + dx, y + dy, z + dz);
        }

        public double flat(P o) {
            return Math.hypot(x - o.x, z - o.z);
        }
    }

    /** Przedrostek punktów areny; wszystkie inne punkty należą do banku. */
    public static final String ARENA_PREFIX = "arena-";
    public static final String ARENA_A = "arena-a";
    public static final String ARENA_B = "arena-b";
    public static final String ARENA_CENTER = "arena-center";
    public static final String ARENA_VIEW = "arena-view";

    /** Wymagane punkty banku: bez nich pokaz napadu nie ruszy (przy braku scena zostaje w oknie z mapą). */
    public static final List<String> REQUIRED = List.of("start", "street", "door", "hall", "front", "safe", "guard", "police", "jail", "view");
    /** Dodatkowe: bloki drzwi (otwierają się w trakcie pokazu) i lampy alarmu (migają). */
    public static final List<String> OPTIONAL = List.of("bankdoor", "vaultdoor", "lamp1", "lamp2", "lamp3", "lamp4");
    /** Wymagane punkty areny: stanowisko A i B (pojedynek), środek okręgu (jackpot) i miejsce dla widzów. */
    public static final List<String> ARENA_REQUIRED = List.of(ARENA_A, ARENA_B, ARENA_CENTER, ARENA_VIEW);

    private String world;
    private float viewYaw;
    private String arenaWorld;
    private float arenaViewYaw;
    private final Map<String, P> points = new LinkedHashMap<>();

    public static boolean isArenaPoint(String name) {
        return name.startsWith(ARENA_PREFIX);
    }

    /** Skrót z komendy ("a", "center") albo pełna nazwa ("arena-a") na pełną nazwę punktu areny; null, gdy takiego punktu nie ma. */
    public static String arenaPointName(String typed) {
        String n = typed.toLowerCase(java.util.Locale.ROOT);
        if (!n.startsWith(ARENA_PREFIX)) n = ARENA_PREFIX + n;
        return ARENA_REQUIRED.contains(n) ? n : null;
    }

    // ---------- bank ----------

    public String world() {
        return world;
    }

    public void world(String name) {
        this.world = name;
    }

    public float viewYaw() {
        return viewYaw;
    }

    public void viewYaw(float yaw) {
        this.viewYaw = yaw;
    }

    /** Wszystkie punkty obu grup (zapis i odczyt); do liczenia obszaru pokazu służą bankPoints() i arenaPoints(). */
    public Map<String, P> points() {
        return points;
    }

    public Map<String, P> bankPoints() {
        Map<String, P> out = new LinkedHashMap<>();
        points.forEach((k, v) -> {
            if (!isArenaPoint(k)) out.put(k, v);
        });
        return out;
    }

    public Map<String, P> arenaPoints() {
        Map<String, P> out = new LinkedHashMap<>();
        points.forEach((k, v) -> {
            if (isArenaPoint(k)) out.put(k, v);
        });
        return out;
    }

    public P get(String name) {
        return points.get(name);
    }

    public void set(String name, P p) {
        points.put(name, p);
    }

    /** Czyści obie grupy. */
    public void clear() {
        clearBank();
        clearArena();
    }

    public void clearBank() {
        points.keySet().removeIf(k -> !isArenaPoint(k));
        world = null;
        viewYaw = 0;
    }

    public void clearArena() {
        points.keySet().removeIf(Stage::isArenaPoint);
        arenaWorld = null;
        arenaViewYaw = 0;
    }

    public List<String> missing() {
        return REQUIRED.stream().filter(n -> !points.containsKey(n)).toList();
    }

    /** Czy komplet wymaganych punktów banku jest ustawiony i świat istnieje. */
    public boolean ready() {
        return world != null && missing().isEmpty() && Bukkit.getWorld(world) != null;
    }

    /** Ta sama logika bez zależności od serwera (testy logiki pokazu): komplet punktów banku. */
    public boolean complete() {
        return missing().isEmpty();
    }

    public World bukkitWorld() {
        return world == null ? null : Bukkit.getWorld(world);
    }

    public Location location(String name) {
        P p = points.get(name);
        World w = bukkitWorld();
        return p == null || w == null ? null : new Location(w, p.x(), p.y(), p.z());
    }

    /** Podmienia grupę bank całą zawartością `fresh` (np. świeżo postawiony prefabrykat); arena zostaje bez zmian. */
    public void replaceBank(Stage fresh) {
        clearBank();
        world = fresh.world;
        viewYaw = fresh.viewYaw;
        points.putAll(fresh.bankPoints());
    }

    // ---------- arena ----------

    public String arenaWorld() {
        return arenaWorld;
    }

    public void arenaWorld(String name) {
        this.arenaWorld = name;
    }

    public float arenaViewYaw() {
        return arenaViewYaw;
    }

    public void arenaViewYaw(float yaw) {
        this.arenaViewYaw = yaw;
    }

    public List<String> arenaMissing() {
        return ARENA_REQUIRED.stream().filter(n -> !points.containsKey(n)).toList();
    }

    /** Komplet punktów areny bez zależności od serwera (testy logiki pokazu). */
    public boolean arenaComplete() {
        return arenaMissing().isEmpty();
    }

    /** Czy komplet punktów areny jest ustawiony i jej świat istnieje. */
    public boolean arenaReady() {
        return arenaWorld != null && arenaComplete() && Bukkit.getWorld(arenaWorld) != null;
    }

    public World arenaBukkitWorld() {
        return arenaWorld == null ? null : Bukkit.getWorld(arenaWorld);
    }

    public Location arenaLocation(String name) {
        P p = points.get(name);
        World w = arenaBukkitWorld();
        return p == null || w == null ? null : new Location(w, p.x(), p.y(), p.z());
    }

    /** Podmienia grupę arena całą zawartością `fresh`; bank zostaje bez zmian. */
    public void replaceArena(Stage fresh) {
        clearArena();
        arenaWorld = fresh.arenaWorld;
        arenaViewYaw = fresh.arenaViewYaw;
        points.putAll(fresh.arenaPoints());
    }

    // ---------- zapis ----------

    public void save(File file) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", world);
        y.set("view-yaw", (double) viewYaw);
        y.set("arena-world", arenaWorld);
        y.set("arena-view-yaw", (double) arenaViewYaw);
        for (Map.Entry<String, P> e : points.entrySet()) y.set("points." + e.getKey(), List.of(e.getValue().x(), e.getValue().y(), e.getValue().z()));
        y.save(file);
    }

    public static Stage load(File file) {
        Stage s = new Stage();
        if (!file.isFile()) return s;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        s.world = y.getString("world");
        s.viewYaw = (float) y.getDouble("view-yaw", 0);
        s.arenaWorld = y.getString("arena-world");
        s.arenaViewYaw = (float) y.getDouble("arena-view-yaw", 0);
        ConfigurationSection sec = y.getConfigurationSection("points");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                List<Double> v = sec.getDoubleList(key);
                if (v.size() == 3) s.points.put(key, new P(v.get(0), v.get(1), v.get(2)));
            }
        }
        return s;
    }
}
