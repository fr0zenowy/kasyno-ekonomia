package pl.kasyno.table;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import pl.kasyno.KasynoPlugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Wszystkie wspólne stoły i pętla, która je popycha co kilka ticków. */
public final class TableManager {
    private final KasynoPlugin plugin;
    private final Map<String, Table> tables = new LinkedHashMap<>();
    private BukkitTask task;

    public TableManager(KasynoPlugin plugin) {
        this.plugin = plugin;
        for (Table t : new Table[]{new CrashTable(plugin), new RouletteTable(plugin), new KoloTable(plugin), new RaceTable(plugin)}) tables.put(t.id, t);
    }

    public Table get(String id) {
        return tables.get(id);
    }

    public CrashTable crash() {
        return (CrashTable) tables.get("crash");
    }

    public RouletteTable roulette() {
        return (RouletteTable) tables.get("ruletka");
    }

    public KoloTable wheel() {
        return (KoloTable) tables.get("kolo");
    }

    public RaceTable races() {
        return (RaceTable) tables.get("wyscigi");
    }

    public Collection<Table> all() {
        return tables.values();
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, Table.STEP, Table.STEP);
    }

    private void update() {
        for (Table t : tables.values()) {
            try {
                t.update();
            } catch (RuntimeException e) {
                // błąd w jednym stole nie może zabrać pieniędzy graczom: odwołujemy rundę i oddajemy stawki
                plugin.getLogger().severe("Błąd stołu " + t.id + ": " + e + ". Runda odwołana, stawki zwrócone.");
                e.printStackTrace();
                t.shutdown();
            }
        }
    }

    /** Wyłączenie pluginu: wszystkie nierozliczone stawki wracają do graczy. */
    public void shutdown() {
        if (task != null) task.cancel();
        for (Table t : tables.values()) t.shutdown();
    }
}
