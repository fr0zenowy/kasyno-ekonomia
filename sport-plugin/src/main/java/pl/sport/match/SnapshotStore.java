package pl.sport.match;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import pl.sport.SportPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Zapisuje na dysku, gdzie gracz stał i w jakim trybie grał przed meczem. Gdy serwer padnie w trakcie meczu, gracz przy wejściu
 * wraca na swoje miejsce i do swojego trybu gry zamiast zostać na boisku w trybie przygody.
 */
public final class SnapshotStore {
    private final SportPlugin plugin;
    private final File file;
    private final YamlConfiguration y;

    public SnapshotStore(SportPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "snapshots.yml");
        this.y = YamlConfiguration.loadConfiguration(file);
    }

    public void put(UUID id, PlayerSnapshot s) {
        if (y.contains(id.toString())) return; // pierwszy zapis jest tym właściwym (przed pierwszym meczem)
        Location l = s.location();
        String p = id + ".";
        y.set(p + "world", l.getWorld() == null ? null : l.getWorld().getName());
        y.set(p + "x", l.getX());
        y.set(p + "y", l.getY());
        y.set(p + "z", l.getZ());
        y.set(p + "yaw", l.getYaw());
        y.set(p + "pitch", l.getPitch());
        y.set(p + "mode", s.gameMode().name());
        y.set(p + "flight", s.allowFlight());
        save();
    }

    public void remove(UUID id) {
        if (!y.contains(id.toString())) return;
        y.set(id.toString(), null);
        save();
    }

    public boolean has(UUID id) {
        return y.contains(id.toString());
    }

    /** Przywraca gracza po awarii (miejsce i tryb gry) i kasuje zapis. */
    public boolean restoreAfterCrash(Player p) {
        String base = p.getUniqueId().toString();
        if (!y.contains(base)) return false;
        String worldName = y.getString(base + ".world");
        var world = worldName == null ? null : plugin.getServer().getWorld(worldName);
        if (world != null) {
            p.teleport(new Location(world, y.getDouble(base + ".x"), y.getDouble(base + ".y"), y.getDouble(base + ".z"), (float) y.getDouble(base + ".yaw"), (float) y.getDouble(base + ".pitch")));
        }
        try {
            p.setGameMode(GameMode.valueOf(y.getString(base + ".mode", "SURVIVAL")));
        } catch (IllegalArgumentException e) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        p.setAllowFlight(y.getBoolean(base + ".flight", false));
        remove(p.getUniqueId());
        return true;
    }

    private void save() {
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie udało się zapisać snapshots.yml: " + e.getMessage());
        }
    }
}
