package pl.kasyno.prison;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.PlayerData;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.T;
import pl.kasyno.prison.PrisonRules.Reason;
import pl.kasyno.prison.PrisonRules.Spot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Więzienie: kto wpadł (nieudane przestępstwo, okradanie, napad), trafia do losowej z cel, nie może się stamtąd ruszyć ani grać w kasyno
 * i wychodzi, wykonując zadania (minigierki) albo po upływie czasu. Wyrok siedzi w danych gracza, więc przeżywa wyjście z serwera i restart.
 */
public final class PrisonService {
    /** Kto ma to uprawnienie, nie da się go uwięzić (domyślnie operatorzy). */
    public static final String BYPASS = "kasyno.prison.bypass";

    /** Stan wyroku gracza do pokazania w oknie i na pasku. */
    public record Status(Reason reason, int tasksLeft, int tasksTotal, long secondsLeft, int cell) {}

    private static final List<String> DEFAULT_CELLS = List.of("-79 92 65", "-82 92 65", "-85 92 65");
    private static final List<String> DEFAULT_COMMANDS = List.of("kasyno wiezienie", "k wiezienie", "casino wiezienie", "wiezienie", "cela", "msg", "tell", "w", "r", "reply", "help", "list");

    private final KasynoPlugin plugin;
    /** Zatrzymani, których jeszcze nie zabrano do celi (widzą wynik swojej wpadki). */
    private final Set<UUID> arresting = new HashSet<>();
    private final Map<UUID, GameMode> modes = new HashMap<>();
    private BukkitTask task;

    public PrisonService(KasynoPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void disable() {
        if (task != null) task.cancel();
        task = null;
        arresting.clear();
    }

    // ---------- konfiguracja ----------

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    /** Więzienie działa, gdy jest włączone, ma świat i przynajmniej jedną celę. */
    public boolean enabled() {
        return cfg().getBoolean("prison.enabled", true) && world() != null && !spots().isEmpty();
    }

    public World world() {
        String name = cfg().getString("prison.world", "");
        World w = name == null || name.isBlank() ? null : Bukkit.getWorld(name);
        if (w != null) return w;
        List<World> all = Bukkit.getWorlds();
        return all.isEmpty() ? null : all.get(0);
    }

    public List<Spot> spots() {
        List<?> raw = cfg().isList("prison.cells") ? cfg().getList("prison.cells") : DEFAULT_CELLS;
        return PrisonRules.parseSpots(raw);
    }

    public int cellCount() {
        return spots().size();
    }

    /** Miejsce w celi o danym numerze (od 0); kierunek patrzenia gracza zostaje, jaki był. */
    public Location cellLocation(int index, float yaw, float pitch) {
        List<Spot> spots = spots();
        World w = world();
        if (spots.isEmpty() || w == null) return null;
        Spot s = spots.get(Math.floorMod(index, spots.size()));
        return new Location(w, s.x(), s.y(), s.z(), yaw, pitch);
    }

    /** Gdzie wychodzi się z więzienia: wpis prison.exit albo punkt spawnu świata. */
    public Location exitLocation() {
        World w = world();
        if (w == null) return null;
        Spot s = PrisonRules.parseSpot(cfg().getString("prison.exit", ""));
        return s == null ? w.getSpawnLocation() : new Location(w, s.x(), s.y(), s.z());
    }

    private double radius() {
        return Math.max(1.5, cfg().getDouble("prison.radius", 3.5));
    }

    private int tasksFor(Reason r) {
        return Math.max(1, Math.min(20, cfg().getInt("prison.sentences." + r.id() + ".tasks", r.defaultTasks())));
    }

    private long millisFor(Reason r) {
        return Math.max(1, Math.min(240, cfg().getInt("prison.sentences." + r.id() + ".minutes", r.defaultMinutes()))) * 60_000L;
    }

    public List<String> allowedCommands() {
        List<String> custom = cfg().getStringList("prison.allowed-commands");
        return custom.isEmpty() ? DEFAULT_COMMANDS : custom;
    }

    // ---------- stan ----------

    public boolean isJailed(UUID id) {
        PlayerData d = plugin.eco().pd(id);
        return d.jailUntil > plugin.eco().now();
    }

    /** Siedzi i już jest w celi (a nie dopiero czeka na zabranie): wtedy obowiązują ograniczenia. */
    public boolean restricted(UUID id) {
        return isJailed(id) && !arresting.contains(id);
    }

    /** Czy za kratki może trafić ten gracz (więzienie działa i nie ma uprawnienia obejścia). */
    public boolean canJail(Player p) {
        return enabled() && !p.hasPermission(BYPASS);
    }

    public Status status(UUID id) {
        PlayerData d = plugin.eco().pd(id);
        long now = plugin.eco().now();
        if (d.jailUntil <= now) return null;
        Reason r = Reason.byId(d.jailReason);
        return new Status(r == null ? Reason.CRIME : r, d.jailTasks, Math.max(d.jailTotal, d.jailTasks), PrisonRules.secondsLeft(d.jailUntil, now), d.jailCell);
    }

    // ---------- wyrok ----------

    /**
     * Skazuje gracza. Online dostaje chwilę (prison.arrest-delay-ticks) na obejrzenie wyniku swojej wpadki, potem trafia do losowej celi;
     * offline trafia tam przy następnym wejściu. Zwraca false, gdy nie da się go uwięzić (wyłączone, brak celi, uprawnienie obejścia).
     */
    public boolean jail(UUID id, Reason reason) {
        if (!enabled()) return false;
        Player online = Bukkit.getPlayer(id);
        if (online != null && online.hasPermission(BYPASS)) return false;
        PlayerData d = plugin.eco().pd(id);
        long now = plugin.eco().now();
        if (d.jailUntil > now) return true;
        d.jailUntil = now + millisFor(reason);
        d.jailTasks = tasksFor(reason);
        d.jailTotal = d.jailTasks;
        d.jailReason = reason.id();
        d.jailCell = PrisonRules.pickCell(cellCount(), plugin.eco().rng);
        plugin.store().touch(id);
        if (online == null) return true;
        long delay = Math.max(0, cfg().getLong("prison.arrest-delay-ticks", 80L));
        Fx.title(online, "<red><bold>ZATRZYMANY!", "<gray>Policja zabiera Cię do więzienia");
        if (delay == 0) {
            enter(online);
            return true;
        }
        arresting.add(id);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null && isJailed(id)) enter(p);
        }, delay);
        return true;
    }

    /** Zabiera gracza do jego celi: tryb przygody, teleport, napisy i przycisk z zadaniami. */
    public void enter(Player p) {
        UUID id = p.getUniqueId();
        arresting.remove(id);
        Status st = status(id);
        if (st == null) return;
        GameMode before = p.getGameMode() == GameMode.ADVENTURE ? GameMode.SURVIVAL : p.getGameMode();
        modes.putIfAbsent(id, before);
        p.closeInventory();
        p.setGameMode(GameMode.ADVENTURE);
        Location to = cellLocation(st.cell(), p.getLocation().getYaw(), p.getLocation().getPitch());
        if (to != null) p.teleport(to);
        Fx.title(p, "<dark_red><bold>ZA KRATKAMI", "<gray>" + st.reason().title() + " • cela " + (st.cell() + 1));
        Fx.lose(p);
        quiet(() -> p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.01));
        T.send(p, "<red><bold>Siedzisz w więzieniu</bold> <gray>(" + st.reason().title() + ", cela " + (st.cell() + 1) + ").");
        T.send(p, "<gray>Wyjdziesz, gdy wykonasz <white>" + st.tasksLeft() + "</white> zadań (minigierek) albo za <white>" + PrisonRules.clock(st.secondsLeft()) + "</white>. "
                + "<click:run_command:'/kasyno wiezienie'><yellow><bold>[OTWÓRZ LISTĘ ZADAŃ]</bold></yellow></click>");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && restricted(id) && plugin.guis().current(p) == null) new PrisonGui(plugin, p).open();
        }, 40L);
    }

    /** Zadanie wykonane: zostaje o jedno mniej, a przy ostatnim gracz wychodzi. Zwraca, ile zadań zostało (-1, gdy gracz nie siedzi). */
    public int completeTask(UUID id) {
        PlayerData d = plugin.eco().pd(id);
        if (d.jailUntil <= plugin.eco().now()) return -1;
        d.jailTasks = Math.max(0, d.jailTasks - 1);
        plugin.store().touch(id);
        Player p = Bukkit.getPlayer(id);
        if (d.jailTasks == 0) {
            release(id, "Odrobiłeś wyrok.");
            return 0;
        }
        if (p != null) {
            Fx.win(p);
            T.send(p, "<green>Zadanie wykonane! <gray>Do wyjścia zostało: <white>" + d.jailTasks + "</white>.");
        }
        return d.jailTasks;
    }

    /** Wypuszcza gracza. Offline: wyrok jest oznaczany jako zakończony, a wyjście dopełnia się przy następnym wejściu. */
    public void release(UUID id, String why) {
        PlayerData d = plugin.eco().pd(id);
        arresting.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (p == null) {
            if (d.jailUntil > 0) {
                d.jailUntil = 1;
                d.jailTasks = 0;
                plugin.store().touch(id);
            }
            return;
        }
        boolean wasInside = d.jailUntil > 0;
        d.jailUntil = 0;
        d.jailTasks = 0;
        d.jailTotal = 0;
        d.jailReason = null;
        d.jailCell = 0;
        plugin.store().touch(id);
        if (!wasInside) return;
        GameMode back = modes.remove(id);
        p.setGameMode(back == null ? GameMode.SURVIVAL : back);
        var top = p.getOpenInventory().getTopInventory();
        if (top != null && top.getHolder() instanceof pl.kasyno.gui.Gui) p.closeInventory();
        Location exit = exitLocation();
        if (exit != null) p.teleport(exit);
        Fx.title(p, "<green><bold>WOLNOŚĆ!", "<gray>Wychodzisz z więzienia");
        Fx.win(p);
        T.send(p, "<green><bold>Jesteś wolny.</bold> <gray>" + (why == null ? "" : why + " ") + "Następnym razem uważaj.");
    }

    /** Wejście na serwer: wyrok w toku zabiera do celi, zakończony w czasie nieobecności wypuszcza. */
    public void onJoin(Player p) {
        PlayerData d = plugin.eco().pd(p.getUniqueId());
        if (d.jailUntil == 0) return;
        if (d.jailUntil <= plugin.eco().now()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) release(p.getUniqueId(), "Twój wyrok dobiegł końca.");
            }, 5L);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && isJailed(p.getUniqueId())) enter(p);
        }, 5L);
    }

    public void onQuit(UUID id) {
        arresting.remove(id);
        modes.remove(id);
    }

    // ---------- co sekundę ----------

    private void tick() {
        long now = plugin.eco().now();
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            UUID id = p.getUniqueId();
            PlayerData d = plugin.eco().pd(id);
            if (d.jailUntil == 0 || arresting.contains(id)) continue;
            if (d.jailUntil <= now) {
                release(id, "Odsiedziałeś swoje.");
                continue;
            }
            if (outside(p, d)) {
                Location to = cellLocation(d.jailCell, p.getLocation().getYaw(), p.getLocation().getPitch());
                if (to != null) {
                    p.teleport(to);
                    T.send(p, "<red>Strażnik zawraca Cię do celi.");
                    Fx.deny(p);
                }
            }
            Fx.actionBar(p, "<red>Więzienie <gray>• zadania <white>" + d.jailTasks + "/" + Math.max(d.jailTotal, d.jailTasks) + " <gray>• wolność za <white>"
                    + PrisonRules.clock(PrisonRules.secondsLeft(d.jailUntil, now)) + " <gray>• /kasyno wiezienie");
        }
    }

    private boolean outside(Player p, PlayerData d) {
        Location to = cellLocation(d.jailCell, 0, 0);
        if (to == null) return false;
        Location at = p.getLocation();
        if (at.getWorld() == null || !at.getWorld().equals(to.getWorld())) return true;
        double dx = at.getX() - to.getX();
        double dz = at.getZ() - to.getZ();
        return Math.hypot(dx, dz) > radius() || Math.abs(at.getY() - to.getY()) > 4;
    }

    // ---------- ustawienia dla administratora ----------

    /** Ustawia celę o numerze 1..liczba+1 na bloku, w którym stoi administrator. Zwraca false dla złego numeru. */
    public boolean setCell(int number, Location at) {
        List<String> cells = new ArrayList<>(spots().stream().map(PrisonRules::format).toList());
        if (number < 1 || number > cells.size() + 1) return false;
        String text = PrisonRules.format(new Spot(at.getBlockX() + 0.5, at.getBlockY(), at.getBlockZ() + 0.5));
        if (number == cells.size() + 1) cells.add(text);
        else cells.set(number - 1, text);
        cfg().set("prison.cells", cells);
        if (at.getWorld() != null) cfg().set("prison.world", at.getWorld().getName());
        plugin.saveConfig();
        return true;
    }

    public void setExit(Location at) {
        cfg().set("prison.exit", PrisonRules.format(new Spot(at.getBlockX() + 0.5, at.getBlockY(), at.getBlockZ() + 0.5)));
        plugin.saveConfig();
    }

    private static void quiet(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // efekt kosmetyczny
        }
    }
}
