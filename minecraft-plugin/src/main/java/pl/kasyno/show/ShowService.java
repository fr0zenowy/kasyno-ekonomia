package pl.kasyno.show;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Heist;
import pl.kasyno.games.HeistFrames;
import pl.kasyno.gui.Fx;
import pl.kasyno.show.ArenaShow.Caption;
import pl.kasyno.show.Stage.P;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Pokazy na żywo: trzyma scenę (stage.yml), stawia bank i arenę, odpala i popycha pokazy co tick (napad w banku, pojedynek i jackpot
 * na arenie) i sprząta po nich (figury, drzwi, lampy). Gdy sceny nie ma, gra rozgrywa się w oknie z mapą albo z głowami graczy.
 * Pieniądze liczy kto inny przed startem pokazu: tu nic ich nie dotyka.
 */
public final class ShowService {
    /** Odległość od środka sceny, w której gracz słyszy dźwięki pokazu sam, bez dodatkowego sygnału. */
    private static final double NEAR = 48;

    private final KasynoPlugin plugin;
    private final File file;
    private Stage stage = new Stage();
    private final List<Active> active = new ArrayList<>();
    private BukkitTask task;

    private record Active(Scene runner, Runnable done, Set<Long> tickets, World world, boolean arena) {}

    public ShowService(KasynoPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stage.yml");
    }

    public void enable() {
        stage = Stage.load(file);
        purge();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void disable() {
        if (task != null) task.cancel();
        for (Active a : new ArrayList<>(active)) {
            a.runner().abort();
            release(a);
            try {
                // wynik jest już rozliczony, więc gracze dostają podsumowanie także przy wyłączeniu
                a.done().run();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Zakończenie pokazu przy wyłączeniu nie powiodło się: " + e);
            }
        }
        active.clear();
        purge();
    }

    public Stage stage() {
        return stage;
    }

    /** Czy scena napadu (bank) jest gotowa. */
    public boolean ready() {
        return stage.ready();
    }

    /** Czy arena (pojedynek, jackpot) jest gotowa. */
    public boolean arenaReady() {
        return stage.arenaReady();
    }

    public boolean running() {
        return !active.isEmpty();
    }

    /** Czy na arenie trwa już jakiś pokaz (arena ma jedno miejsce: kolejna gra wraca wtedy do okna). */
    public boolean arenaBusy() {
        for (Active a : active) if (a.arena()) return true;
        return false;
    }

    // ---------- scena ----------

    private void save() {
        try {
            file.getParentFile().mkdirs();
            stage.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie udało się zapisać stage.yml: " + e);
        }
    }

    /** Stawia gotowy bank w miejscu administratora i ustawia scenę napadu (arena zostaje bez zmian). */
    public Stage build(Location origin) {
        stage.replaceBank(BankPrefab.build(origin));
        save();
        return stage;
    }

    /** Stawia gotową arenę w miejscu administratora i ustawia jej punkty (bank zostaje bez zmian). */
    public Stage buildArena(Location origin) {
        stage.replaceArena(ArenaPrefab.build(origin));
        save();
        return stage;
    }

    /** Ustawia jeden punkt sceny napadu w miejscu administratora (dla własnego budynku). */
    public void setPoint(String name, Location at) {
        if (stage.world() != null && !stage.world().equals(at.getWorld().getName())) stage.clearBank();
        stage.world(at.getWorld().getName());
        boolean block = name.equals("bankdoor") || name.equals("vaultdoor") || name.startsWith("lamp");
        stage.set(name, block ? new P(at.getBlockX(), at.getBlockY(), at.getBlockZ()) : new P(at.getX(), at.getY(), at.getZ()));
        if (name.equals("view")) stage.viewYaw(at.getYaw());
        save();
    }

    /** Ustawia jeden punkt areny (pełna nazwa, np. "arena-a") w miejscu administratora (dla własnej areny). */
    public void setArenaPoint(String name, Location at) {
        if (stage.arenaWorld() != null && !stage.arenaWorld().equals(at.getWorld().getName())) stage.clearArena();
        stage.arenaWorld(at.getWorld().getName());
        stage.set(name, new P(at.getX(), at.getY(), at.getZ()));
        if (name.equals(Stage.ARENA_VIEW)) stage.arenaViewYaw(at.getYaw());
        save();
    }

    /** Usuwa scenę napadu z konfiguracji (arena zostaje). */
    public void clear() {
        stage.clearBank();
        save();
    }

    /** Usuwa arenę z konfiguracji (bank zostaje). */
    public void clearArena() {
        stage.clearArena();
        save();
    }

    /** Teleportuje gracza na miejsce dla widzów. Zwraca false, gdy sceny nie ma. */
    public boolean teleportToView(Player p) {
        Location l = stage.location("view");
        if (l == null) return false;
        l.setYaw(stage.viewYaw());
        p.teleport(l);
        return true;
    }

    /** Teleportuje gracza na trybunę areny. Zwraca false, gdy areny nie ma. */
    public boolean teleportToArena(Player p) {
        Location l = stage.arenaLocation(Stage.ARENA_VIEW);
        if (l == null) return false;
        l.setYaw(stage.arenaViewYaw());
        p.teleport(l);
        return true;
    }

    private static boolean within(Player p, Location center) {
        return center != null && p.getWorld().equals(center.getWorld()) && p.getLocation().distance(center) < NEAR;
    }

    /** Czy gracz jest blisko sceny (wtedy dźwięki pokazu słyszy sam, bez dodatkowego sygnału). */
    public boolean near(Player p) {
        return within(p, stage.location("hall"));
    }

    /** Czy gracz jest blisko areny. */
    public boolean nearArena(Player p) {
        return within(p, stage.arenaLocation(Stage.ARENA_CENTER));
    }

    // ---------- uruchamianie ----------

    /** Bilety chunków wokół punktów sceny, żeby pokaz szedł także wtedy, gdy nikogo nie ma w pobliżu. */
    private Set<Long> tickets(World world, Collection<P> points) {
        Set<Long> tickets = new HashSet<>();
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (P p : points) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        for (int cx = ((int) Math.floor(minX) - 16) >> 4; cx <= ((int) Math.floor(maxX) + 16) >> 4; cx++) {
            for (int cz = ((int) Math.floor(minZ) - 16) >> 4; cz <= ((int) Math.floor(maxZ) + 16) >> 4; cz++) {
                try {
                    world.addPluginChunkTicket(cx, cz, plugin);
                    tickets.add(((long) cx << 32) | (cz & 0xffffffffL));
                } catch (RuntimeException ignored) {
                    // bez biletu chunka pokaz i tak działa, dopóki ktoś jest w pobliżu
                }
            }
        }
        return tickets;
    }

    /** Rejestruje pokaz; `onDone` jest wołane dokładnie raz (także po awarii, przerwaniu komendą i przy wyłączeniu). */
    private void launch(World world, Collection<P> area, boolean arena, Runnable onDone, Function<Runnable, Scene> scene) {
        Set<Long> tickets = tickets(world, area);
        boolean[] finished = {false};
        Runnable once = () -> {
            if (finished[0]) return;
            finished[0] = true;
            onDone.run();
        };
        active.add(new Active(scene.apply(once), once, tickets, world, arena));
    }

    /**
     * Odpala pokaz napadu. `onFrame` dostaje numer klatki filmu, a `onDone` jest wołane raz, gdy pokaz się skończy (także po awarii).
     * Zwraca false, gdy sceny nie ma.
     */
    public boolean play(List<HeistFrames.Frame> frames, List<UUID> crew, List<String> names, IntConsumer onFrame, Runnable onDone) {
        World world = stage.bukkitWorld();
        if (!stage.ready() || world == null) return false;
        launch(world, stage.bankPoints().values(), false, onDone,
                once -> new ShowRunner(new BukkitBackend(stage, world, plugin.getLogger()), stage, crew, names, frames, onFrame, once));
        return true;
    }

    /**
     * Odpala pojedynek na arenie: `a` staje na stanowisku A, `b` na B, a `winner` zostaje na nogach. Napisy (odliczanie, wynik)
     * idą do `captions`, a `onDone` raz po pokazie. Zwraca false, gdy areny nie ma albo jest zajęta (wtedy gra się okno).
     */
    public boolean playDuel(UUID a, String nameA, UUID b, String nameB, UUID winner, int waitTicks, Consumer<Caption> captions, Runnable onDone) {
        World world = stage.arenaBukkitWorld();
        if (!stage.arenaReady() || world == null || arenaBusy()) return false;
        launch(world, stage.arenaPoints().values(), true, onDone,
                once -> new DuelRunner(new BukkitBackend(stage, world, plugin.getLogger()), stage, a, nameA, b, nameB, winner, waitTicks, captions, once));
        return true;
    }

    /**
     * Odpala losowanie jackpota na arenie: uczestnicy na okręgu, podświetlenie krąży po klatkach `spin` (indeksy uczestników,
     * ostatnia na zwycięzcy). `onFrame` dostaje numer klatki. Zwraca false, gdy areny nie ma albo jest zajęta.
     */
    public boolean playJackpot(List<UUID> crew, List<String> names, int[] spin, Consumer<Caption> captions, IntConsumer onFrame, Runnable onDone) {
        World world = stage.arenaBukkitWorld();
        if (!stage.arenaReady() || world == null || arenaBusy() || spin.length == 0) return false;
        launch(world, stage.arenaPoints().values(), true, onDone,
                once -> new JackpotRunner(new BukkitBackend(stage, world, plugin.getLogger()), stage, crew, names, spin, captions, onFrame, once));
        return true;
    }

    /** Pokaz próbny dla administratora: on i kilku „kolegów” (losowe skórki) robią napad, bez pieniędzy i lobby. */
    public boolean demo(Player admin, int crewSize) {
        if (!ready()) return false;
        int n = Math.max(2, Math.min(8, crewSize));
        List<UUID> crew = new ArrayList<>();
        List<String> names = new ArrayList<>();
        crew.add(admin.getUniqueId());
        names.add(admin.getName());
        for (int i = 1; i < n; i++) {
            crew.add(UUID.randomUUID());
            names.add("Kolega " + i);
        }
        Heist.Result res = Heist.run(crew, u -> names.get(crew.indexOf(u)), plugin.eco().rng);
        List<HeistFrames.Frame> frames = HeistFrames.build(res, crew);
        Fx.title(admin, "<red><bold>POKAZ NAPADU", "<gray>Ekipa: " + n + " os. (próba, bez pieniędzy)");
        return play(frames, crew, names, idx -> Fx.actionBar(admin, "<yellow>" + pl.kasyno.gui.T.esc(frames.get(idx).text())), () -> {
            Fx.actionBar(admin, res.success() ? "<green><bold>Ekipa uciekła z łupem!" : "<red><bold>Cała ekipa wpadła.");
            admin.sendMessage(pl.kasyno.gui.T.MM.deserialize(pl.kasyno.gui.T.PREFIX + (res.success() ? "<green>Pokaz próbny: ekipa uciekła (" + res.survivors().size() + " z " + n + ")." : "<red>Pokaz próbny: cała ekipa wpadła.")));
        });
    }

    /** Pojedynek próbny: administrator kontra „Kolega” (losowa skórka), losowy zwycięzca, bez pieniędzy. */
    public boolean demoDuel(Player admin) {
        if (!arenaReady() || arenaBusy()) return false;
        UUID friend = UUID.randomUUID();
        boolean adminWins = plugin.eco().chance(0.5);
        UUID winner = adminWins ? admin.getUniqueId() : friend;
        int wait = 14 + plugin.eco().rng.nextInt(26);
        Fx.title(admin, "<red><bold>POKAZ POJEDYNKU", "<gray>Próba, bez pieniędzy");
        return playDuel(admin.getUniqueId(), admin.getName(), friend, "Kolega", winner, wait, ArenaCaptions.to(this, Set.of(admin.getUniqueId())), () -> {
            admin.sendMessage(pl.kasyno.gui.T.MM.deserialize(pl.kasyno.gui.T.PREFIX + "<gray>Pokaz próbny pojedynku: wygrał " + (adminWins ? "Ty" : "Kolega") + "."));
        });
    }

    /** Jackpot próbny: administrator i kilku „kolegów” (losowe skórki), losowy zwycięzca, bez pieniędzy. */
    public boolean demoJackpot(Player admin, int players) {
        if (!arenaReady() || arenaBusy()) return false;
        int n = Math.max(2, Math.min(20, players));
        List<UUID> crew = new ArrayList<>();
        List<String> names = new ArrayList<>();
        crew.add(admin.getUniqueId());
        names.add(admin.getName());
        for (int i = 1; i < n; i++) {
            crew.add(UUID.randomUUID());
            names.add("Kolega " + i);
        }
        int winnerIdx = plugin.eco().rng.nextInt(n);
        int[] spin = JackpotRunner.spinFrames(n, winnerIdx);
        Fx.title(admin, "<gold><bold>POKAZ JACKPOTA", "<gray>Próba, " + n + " os., bez pieniędzy");
        return playJackpot(crew, names, spin, ArenaCaptions.to(this, Set.of(admin.getUniqueId())), k -> {
        }, () -> admin.sendMessage(pl.kasyno.gui.T.MM.deserialize(pl.kasyno.gui.T.PREFIX + "<gray>Pokaz próbny jackpota: wygrał " + (winnerIdx == 0 ? "Ty" : names.get(winnerIdx)) + ".")));
    }

    public void stopAll() {
        for (Active a : new ArrayList<>(active)) {
            a.runner().abort();
            release(a);
            a.done().run();
        }
        active.clear();
        purge();
    }

    // ---------- pętla i sprzątanie ----------

    private void tick() {
        for (Active a : new ArrayList<>(active)) {
            try {
                if (a.runner().tick()) {
                    release(a);
                    active.remove(a);
                }
            } catch (RuntimeException e) {
                // awaria pokazu nie może zablokować gry: sprzątamy i kończymy scenę
                plugin.getLogger().severe("Błąd pokazu: " + e);
                e.printStackTrace();
                a.runner().abort();
                release(a);
                active.remove(a);
                a.done().run();
            }
        }
    }

    private void release(Active a) {
        for (long t : a.tickets()) {
            try {
                a.world().removePluginChunkTicket((int) (t >> 32), (int) t, plugin);
            } catch (RuntimeException ignored) {
                // bilet i tak wygaśnie przy wyłączeniu pluginu
            }
        }
    }

    /** Usuwa figury i fajerwerki pokazów zostawione po awarii albo przerwanym pokazie (po znaczniku w danych encji), w obu światach. */
    public void purge() {
        if (BukkitBackend.TAG == null) return;
        Set<World> worlds = new LinkedHashSet<>();
        World bank = stage.bukkitWorld();
        World arena = stage.arenaBukkitWorld();
        if (bank != null) worlds.add(bank);
        if (arena != null) worlds.add(arena);
        for (World w : worlds) {
            try {
                for (Entity e : new ArrayList<>(w.getEntities())) {
                    if (e.getPersistentDataContainer().has(BukkitBackend.TAG, PersistentDataType.STRING)) e.remove();
                }
            } catch (RuntimeException ignored) {
                // przy starcie część chunków może nie być jeszcze załadowana
            }
        }
    }
}
