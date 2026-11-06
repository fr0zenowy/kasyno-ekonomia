package pl.sport.arena;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.sport.SportPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lista boisk zapisana w plugins/SportPlugin/arenas.yml. Nazwy nie rozróżniają wielkości liter, a boisko każdej dyscypliny ma własną nazwę. */
public final class ArenaRegistry {
    private final SportPlugin plugin;
    private final File file;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    public ArenaRegistry(SportPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public void load() {
        arenas.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("arenas");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            Arena a = s == null ? null : Arena.load(name, s);
            if (a == null) plugin.getLogger().warning("Pominięto uszkodzone boisko w arenas.yml: " + name);
            else arenas.put(key(name), a);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Arena a : arenas.values()) a.save(y.createSection("arenas." + a.name()));
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie udało się zapisać arenas.yml: " + e.getMessage());
        }
    }

    public Arena get(String name) {
        return name == null ? null : arenas.get(key(name));
    }

    public Arena get(String name, Arena.Sport sport) {
        Arena a = get(name);
        return a != null && a.sport() == sport ? a : null;
    }

    public void add(Arena a) {
        arenas.put(key(a.name()), a);
        save();
    }

    public boolean remove(String name) {
        boolean r = arenas.remove(key(name)) != null;
        if (r) save();
        return r;
    }

    public List<Arena> all() {
        return new ArrayList<>(arenas.values());
    }

    public List<Arena> of(Arena.Sport sport) {
        return arenas.values().stream().filter(a -> a.sport() == sport).toList();
    }
}
