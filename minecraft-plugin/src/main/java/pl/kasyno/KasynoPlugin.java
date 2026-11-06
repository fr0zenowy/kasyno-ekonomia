package pl.kasyno;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import pl.kasyno.cmd.Commands;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Crates;
import pl.kasyno.core.DataStore;
import pl.kasyno.core.Earn;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Empire;
import pl.kasyno.core.Gather;
import pl.kasyno.games.Lottery;
import pl.kasyno.games.Rooms;
import pl.kasyno.gui.ChatPrompt;
import pl.kasyno.gui.GuiManager;
import pl.kasyno.npc.NpcManager;
import pl.kasyno.npc.PlaceholderManager;
import pl.kasyno.pack.PackManager;
import pl.kasyno.prison.PrisonListener;
import pl.kasyno.prison.PrisonService;
import pl.kasyno.shark.SharkService;
import pl.kasyno.show.ShowService;
import pl.kasyno.table.TableManager;
import pl.kasyno.bin.BinManager;
import pl.kasyno.waypoint.WaypointManager;
import pl.kasyno.work.WorkListener;
import pl.kasyno.work.WorkService;

import java.util.List;
import java.util.random.RandomGenerator;

/** Plugin Kasyno i Ekonomia dla Paper 1.21.11: cała ekonomia, sklep i gry w GUI oraz NPC otwierające menu. */
public class KasynoPlugin extends JavaPlugin {
    private Catalog catalog;
    private DataStore store;
    private Eco eco;
    private Empire empire;
    private Crates crates;
    private Earn earn;
    private Gather gather;
    private GuiManager guis;
    private ChatPrompt prompts;
    private NpcManager npcs;
    private PlaceholderManager placeholders;
    private Rooms rooms;
    private Lottery lottery;
    private TableManager tables;
    private PackManager packs;
    private ShowService show;
    private WorkService work;
    private BinManager bins;
    private WaypointManager waypoints;
    private PrisonService prison;
    private SharkService shark;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        catalog = Catalog.load();
        store = new DataStore(getDataFolder().toPath(), getLogger(), catalog.startBalance);
        store.loadAll();
        eco = new Eco(catalog, store, System::currentTimeMillis, RandomGenerator.getDefault());
        // po (nie)planowanym wyłączeniu żaden stół nie trzyma stawek, więc wszystko, co zostało w zapisie, wraca do graczy
        long recovered = eco.recoverHeld();
        if (recovered > 0) getLogger().info("Zwrócono stawki z przerwanych rund przy stołach: " + recovered);
        tables = new TableManager(this);
        packs = new PackManager(this);
        show = new ShowService(this);
        empire = new Empire(eco);
        crates = new Crates(eco);
        earn = new Earn(eco);
        gather = new Gather(eco);
        guis = new GuiManager(this);
        prompts = new ChatPrompt(this);
        rooms = new Rooms(this);
        lottery = new Lottery(this);
        npcs = new NpcManager(this);
        placeholders = new PlaceholderManager(this);
        work = new WorkService(this);
        bins = new BinManager(this);
        waypoints = new WaypointManager(this);
        prison = new PrisonService(this);
        shark = new SharkService(this);

        Bukkit.getPluginManager().registerEvents(guis, this);
        Bukkit.getPluginManager().registerEvents(new PrisonListener(prison), this);
        Bukkit.getPluginManager().registerEvents(shark, this);
        Bukkit.getPluginManager().registerEvents(prompts, this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(npcs, this);
        Bukkit.getPluginManager().registerEvents(placeholders, this);
        Bukkit.getPluginManager().registerEvents(packs, this);
        Bukkit.getPluginManager().registerEvents(new WorkListener(work), this);
        Bukkit.getPluginManager().registerEvents(bins, this);
        Bukkit.getPluginManager().registerEvents(waypoints, this);
        packs.enable();
        show.enable();
        work.enable();
        bins.enable();
        waypoints.enable();
        prison.enable();
        shark.enable();
        tables.start();
        Commands.registerAll(this);

        // Zapis zmian co 30 s: serializacja w wątku głównym, zapis plików w tle.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            List<Runnable> jobs = store.snapshotDirty();
            if (!jobs.isEmpty()) Bukkit.getScheduler().runTaskAsynchronously(this, () -> jobs.forEach(Runnable::run));
        }, 600L, 600L);
        lottery.start();

        for (Player p : Bukkit.getOnlinePlayers()) PlayerListener.prepare(this, p);
        getLogger().info("Kasyno: " + catalog.items.size() + " przedmiotów, " + store.all().size() + " graczy, NPC: " + npcs.count() + ", placeholdery: " + placeholders.count());
    }

    @Override
    public void onDisable() {
        // najpierw stoły (oddają stawki), dopiero potem zamykamy okna, żeby zamknięcie nie wypłacało niczego po drodze
        if (show != null) show.disable();
        if (work != null) work.disable();
        if (bins != null) bins.disable();
        if (waypoints != null) waypoints.disable();
        if (prison != null) prison.disable();
        if (shark != null) shark.disable();
        if (tables != null) tables.shutdown();
        if (guis != null) guis.closeAll();
        if (rooms != null) rooms.shutdown();
        if (packs != null) packs.disable();
        if (store != null) store.flush();
    }

    public TableManager tables() {
        return tables;
    }

    public PackManager packs() {
        return packs;
    }

    public ShowService show() {
        return show;
    }

    public WorkService work() {
        return work;
    }

    public BinManager bins() {
        return bins;
    }

    public WaypointManager waypoints() {
        return waypoints;
    }

    public PrisonService prison() {
        return prison;
    }

    public SharkService shark() {
        return shark;
    }

    public Catalog catalog() {
        return catalog;
    }

    public DataStore store() {
        return store;
    }

    public Eco eco() {
        return eco;
    }

    public Empire empire() {
        return empire;
    }

    public Crates crates() {
        return crates;
    }

    public Earn earn() {
        return earn;
    }

    public Gather gather() {
        return gather;
    }

    public GuiManager guis() {
        return guis;
    }

    public ChatPrompt prompts() {
        return prompts;
    }

    public NpcManager npcs() {
        return npcs;
    }

    public PlaceholderManager placeholders() {
        return placeholders;
    }

    public Rooms rooms() {
        return rooms;
    }

    public Lottery lottery() {
        return lottery;
    }
}
