package pl.kasyno.waypoint;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Waypointy zapisane w pliku waypoints.yml (źródło prawdy; encje w świecie są tylko ich odbiciem). Kolejność dodawania zostaje. */
public final class WaypointStore {
    private final File file;
    private final Map<String, Waypoint> map = new LinkedHashMap<>();

    public WaypointStore(File file) {
        this.file = file;
    }

    /** Wczytuje plik; uszkodzone wpisy są pomijane (wracają do zapisu przy następnej zmianie bez nich). */
    public void load() {
        map.clear();
        if (!file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("waypoints");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            String name = s.getString("name");
            String world = s.getString("world");
            if (name == null || world == null || !Waypoint.validName(name) || !s.contains("x") || !s.contains("y") || !s.contains("z")) continue;
            String realId = Waypoint.idOf(name);
            if (map.containsKey(realId)) continue;
            int color = parseColor(s.getString("color"));
            map.put(realId, new Waypoint(realId, name, world, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), color, Waypoint.Kind.PLACE, Math.max(0, s.getLong("reward", 0))));
        }
    }

    private static int parseColor(String text) {
        Integer c = WaypointColors.parse(text);
        return c == null ? 0xFFD700 : c;
    }

    public void save() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        for (Waypoint w : map.values()) {
            String p = "waypoints." + w.id();
            y.set(p + ".name", w.name());
            y.set(p + ".world", w.world());
            y.set(p + ".x", w.x());
            y.set(p + ".y", w.y());
            y.set(p + ".z", w.z());
            y.set(p + ".color", WaypointColors.hex(w.color()));
            if (w.reward() > 0) y.set(p + ".reward", w.reward());
        }
        File dir = file.getParentFile();
        if (dir != null) dir.mkdirs();
        y.save(file);
    }

    public Collection<Waypoint> all() {
        return new ArrayList<>(map.values());
    }

    public Waypoint get(String nameOrId) {
        return nameOrId == null ? null : map.get(Waypoint.idOf(nameOrId));
    }

    public void put(Waypoint w) {
        map.put(w.id(), w);
    }

    public Waypoint remove(String nameOrId) {
        return nameOrId == null ? null : map.remove(Waypoint.idOf(nameOrId));
    }

    public List<String> ids() {
        return new ArrayList<>(map.keySet());
    }

    public int size() {
        return map.size();
    }
}
