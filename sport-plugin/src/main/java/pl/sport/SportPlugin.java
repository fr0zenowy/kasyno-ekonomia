package pl.sport;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import pl.sport.arena.ArenaBuilder;
import pl.sport.arena.ArenaRegistry;
import pl.sport.command.SportCommand;
import pl.sport.config.SportConfig;
import pl.sport.listener.SportListener;
import pl.sport.match.BallView;
import pl.sport.match.DisplayBallView;
import pl.sport.match.MatchManager;
import pl.sport.match.SnapshotStore;

/** Siatkówka, koszykówka i ping-pong dla Paper 1.21.11: boiska budowane komendą, piłka z fizyką, zasady i punktacja. */
public class SportPlugin extends JavaPlugin {
    private SportConfig sportConfig;
    private ArenaRegistry arenas;
    private ArenaBuilder builder;
    private MatchManager matches;
    private SnapshotStore snapshots;
    private BallView.Factory ballFactory;
    private NamespacedKey ballTag;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        sportConfig = new SportConfig(this);
        ballTag = new NamespacedKey(this, "ball");
        ballFactory = (where, kind, radius) -> new DisplayBallView(this, where, kind, radius);
        arenas = new ArenaRegistry(this);
        arenas.load();
        builder = new ArenaBuilder(this);
        snapshots = new SnapshotStore(this);
        matches = new MatchManager(this);

        Bukkit.getPluginManager().registerEvents(new SportListener(this), this);
        for (String name : new String[]{"siatkowka", "koszykowka", "pingpong", "sport"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd == null) continue;
            SportCommand exec = new SportCommand(this, name);
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
        }
        Bukkit.getScheduler().runTaskTimer(this, matches::tickAll, 1L, 1L);
        getLogger().info("Sport: " + arenas.all().size() + " boisk. Siatkówka: /siatkowka, koszykówka: /koszykowka, ping-pong: /pingpong.");
    }

    @Override
    public void onDisable() {
        if (matches != null) matches.shutdown();
    }

    public SportConfig sportConfig() {
        return sportConfig;
    }

    public ArenaRegistry arenas() {
        return arenas;
    }

    public ArenaBuilder builder() {
        return builder;
    }

    public MatchManager matches() {
        return matches;
    }

    public SnapshotStore snapshots() {
        return snapshots;
    }

    public BallView.Factory ballFactory() {
        return ballFactory;
    }

    /** Podmiana widoku piłki (testy używają widoku bez encji). */
    public void setBallFactory(BallView.Factory f) {
        this.ballFactory = f;
    }

    public NamespacedKey ballTag() {
        return ballTag;
    }
}
