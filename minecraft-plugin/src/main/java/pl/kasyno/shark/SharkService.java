package pl.kasyno.shark;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Vector3f;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Rekin: im dalej od lądu pływasz po głębokiej wodzie, tym większa szansa, że podpłynie. Najpierw widać płetwę (masz kilka sekund
 * na wyjście z wody), potem rekin rzuca się na Ciebie, chwyta i pożera: tracisz połowę gotówki z portfela (bank jest bezpieczny)
 * i giniesz. Cały atak to animacja z klocków (SharkModel), a decyzje i ruch kamery świata liczy SharkChoreo.
 */
public final class SharkService implements Listener {
    /** Uprawnienie, które chroni przed rekinami (domyślnie nikt go nie ma). */
    public static final String BYPASS = "kasyno.shark.bypass";

    private static final class Track {
        Location lastDry;
        Location lastSeen;
        int sinceRoll;
        long cooldownUntil;
    }

    /** Tyle bloków w poziomie między dwoma sprawdzeniami (co sekundę) to teleport, a nie pływanie. */
    private static final double TELEPORT_JUMP = 60;

    private static final class Attack {
        final UUID id;
        final World world;
        final SharkChoreo choreo;
        final boolean harmless;
        Object handle;
        boolean grabbed;
        boolean killed;
        /** Atak zaczął się na pływaku: wyjście z wody w czasie ostrzeżenia go ratuje (atak wywołany przez administratora na lądzie biegnie do końca). */
        boolean mustSwim;
        int age;
        double tx;
        double ty;
        double tz;

        Attack(Player p, SharkChoreo choreo, boolean harmless) {
            this.id = p.getUniqueId();
            this.world = p.getWorld();
            this.choreo = choreo;
            this.harmless = harmless;
        }
    }

    private final KasynoPlugin plugin;
    private final Map<UUID, Track> tracks = new HashMap<>();
    private final Map<UUID, Attack> attacks = new HashMap<>();
    private final Set<UUID> victims = new HashSet<>();
    private final Map<Object, BukkitTask> previews = new HashMap<>();
    private SharkBackend backend;
    private WaterProbe probe = WaterProbe.BLOCKS;
    private BukkitTask scanTask;
    private BukkitTask attackTask;

    public SharkService(KasynoPlugin plugin) {
        this.plugin = plugin;
        this.backend = new BukkitSharkBackend(plugin);
    }

    // ---------- test seams ----------

    void useBackend(SharkBackend b) {
        this.backend = b;
    }

    void useProbe(WaterProbe p) {
        this.probe = p;
    }

    // ---------- start i koniec ----------

    public void enable() {
        backend.purge();
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scan, 20L, 20L);
        attackTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAttacks, 1L, 1L);
    }

    public void disable() {
        if (scanTask != null) scanTask.cancel();
        if (attackTask != null) attackTask.cancel();
        scanTask = null;
        attackTask = null;
        for (Attack a : new ArrayList<>(attacks.values())) {
            if (a.handle != null) backend.remove(a.handle);
        }
        attacks.clear();
        previews.values().forEach(BukkitTask::cancel);
        previews.clear();
        backend.purge();
    }

    // ---------- konfiguracja ----------

    public SharkRules.Config config() {
        FileConfiguration c = plugin.getConfig();
        SharkRules.Config d = SharkRules.Config.defaults();
        return new SharkRules.Config(c.getBoolean("shark.enabled", d.enabled()), c.getDouble("shark.min-distance", d.minDistance()), c.getDouble("shark.max-distance", d.maxDistance()),
                c.getDouble("shark.max-chance", d.maxChance()), c.getInt("shark.check-seconds", d.checkSeconds()), c.getInt("shark.min-depth", d.minDepth()),
                c.getInt("shark.warning-seconds", d.warningTicks() / 20) * 20, c.getInt("shark.cooldown-seconds", d.cooldownSeconds()), c.getDouble("shark.loss-fraction", d.lossFraction())).sane();
    }

    public boolean enabled() {
        return config().enabled();
    }

    public void setEnabled(boolean on) {
        plugin.getConfig().set("shark.enabled", on);
        plugin.saveConfig();
        if (!on) abortAll("Rekin odpłynął.");
    }

    public boolean attacking(UUID id) {
        return attacks.containsKey(id);
    }

    public int activeAttacks() {
        return attacks.size();
    }

    /** Ryzyko dla gracza, który jest `distance` bloków od lądu: szansa na atak przy jednym losowaniu. */
    public double risk(double distance) {
        return SharkRules.chance(distance, config());
    }

    /** Odległość gracza od ostatniego lądu, na którym stał (poziomo), albo -1, gdy nie wiadomo. */
    public double distanceFromLand(Player p) {
        Track t = tracks.get(p.getUniqueId());
        if (t == null || t.lastDry == null || t.lastDry.getWorld() == null || !t.lastDry.getWorld().equals(p.getWorld())) return -1;
        return Math.hypot(p.getLocation().getX() - t.lastDry.getX(), p.getLocation().getZ() - t.lastDry.getZ());
    }

    // ---------- co sekundę: odległość od lądu i losowanie ----------

    private boolean eligible(Player p) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return false;
        if (p.isDead() || p.hasPermission(BYPASS)) return false;
        if (plugin.prison() != null && plugin.prison().isJailed(p.getUniqueId())) return false;
        return p.getWorld().getEnvironment() == World.Environment.NORMAL;
    }

    void scan() {
        SharkRules.Config cfg = config();
        long now = plugin.eco().now();
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            UUID id = p.getUniqueId();
            if (!eligible(p)) {
                tracks.remove(id);
                continue;
            }
            Track t = tracks.computeIfAbsent(id, k -> new Track());
            Location at = p.getLocation();
            boolean sameWorld = t.lastDry != null && t.lastDry.getWorld() != null && t.lastDry.getWorld().equals(at.getWorld());
            boolean jumped = t.lastSeen != null && sameWorld && Math.hypot(at.getX() - t.lastSeen.getX(), at.getZ() - t.lastSeen.getZ()) > TELEPORT_JUMP;
            t.lastSeen = at.clone();
            boolean grounded = probe.grounded(p);
            if (grounded || !sameWorld || jumped) {
                // ląd (albo nowy świat, albo teleport) jest punktem odniesienia: odległość i odliczanie do losowania zaczynają się od zera
                t.lastDry = at.clone();
                t.sinceRoll = 0;
            }
            if (grounded) continue;
            if (!cfg.enabled() || !probe.swimming(p) || attacks.containsKey(id) || now < t.cooldownUntil) continue;
            if (probe.depth(p) < cfg.minDepth()) continue;
            if (++t.sinceRoll < cfg.checkSeconds()) continue;
            t.sinceRoll = 0;
            double chance = SharkRules.chance(Math.hypot(at.getX() - t.lastDry.getX(), at.getZ() - t.lastDry.getZ()), cfg);
            if (chance > 0 && plugin.eco().rng.nextDouble() < chance) start(p, false);
        }
    }

    // ---------- atak ----------

    /** Rzuca na gracza rekina (bez losowania i sprawdzania wody). `harmless`: tylko animacja, bez śmierci i bez utraty gotówki. Zwraca false, gdy już trwa atak. */
    public boolean start(Player p, boolean harmless) {
        if (attacks.containsKey(p.getUniqueId())) return false;
        SharkRules.Config cfg = config();
        double surface = probe.swimming(p) ? probe.surfaceY(p) : p.getLocation().getY() + 0.3;
        Attack a = new Attack(p, new SharkChoreo(cfg.warningTicks(), surface, plugin.eco().rng), harmless);
        a.mustSwim = probe.swimming(p);
        attacks.put(p.getUniqueId(), a);
        Fx.title(p, "<dark_red><bold>REKIN!", "<gray>Płetwa zbliża się. Wyjdź z wody!");
        Fx.sound(p, Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.8f);
        T.send(p, "<red><bold>Rekin!</bold> <gray>Masz kilka sekund, żeby wyjść z wody albo wsiąść do łodzi.");
        return true;
    }

    private boolean escaped(Player p) {
        GameMode gm = p.getGameMode();
        return !probe.swimming(p) || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }

    void tickAttacks() {
        if (attacks.isEmpty()) return;
        for (Attack a : new ArrayList<>(attacks.values())) {
            Player p = Bukkit.getPlayer(a.id);
            if (p == null || !p.isOnline()) {
                finish(a, false);
                continue;
            }
            boolean dead = p.isDead();
            if (!p.getWorld().equals(a.world) || (dead && !a.killed)) {
                finish(a, true);
                continue;
            }
            if (!dead && a.mustSwim && a.choreo.phase() == SharkChoreo.Phase.APPROACH && escaped(p)) {
                Fx.actionBar(p, "<green>Rekin odpłynął.");
                finish(a, true);
                continue;
            }
            if (!dead) {
                Location at = p.getLocation();
                a.tx = at.getX();
                a.ty = at.getY();
                a.tz = at.getZ();
            }
            SharkChoreo.Step s = a.choreo.step(a.tx, a.ty, a.tz);
            SharkChoreo.Frame f = s.frame();
            if (a.handle == null) a.handle = backend.spawn(a.world, f);
            else backend.update(a.handle, f);
            effects(a, p, s, dead);
            a.age++;
            if (s.finished()) finish(a, true);
        }
    }

    private void effects(Attack a, Player p, SharkChoreo.Step s, boolean dead) {
        SharkChoreo.Frame f = s.frame();
        World w = a.world;
        Location shark = new Location(w, f.x(), f.y(), f.z());
        switch (f.phase()) {
            case APPROACH -> {
                int interval = Math.max(6, 24 - (int) (18.0 * a.age / Math.max(1, config().warningTicks())));
                if (a.age % interval == 0) Fx.sound(p, Sound.ENTITY_WARDEN_HEARTBEAT, 0.9f, 0.7f + 0.5f * a.age / Math.max(1, config().warningTicks()));
                if (a.age % 2 == 0) quiet(() -> w.spawnParticle(Particle.SPLASH, new Location(w, f.x(), f.y() + SharkChoreo.CRUISE_DEPTH + 0.1, f.z()), 6, 0.3, 0.05, 0.3, 0.05));
                if (a.age % 20 == 0) {
                    double d = Math.hypot(p.getLocation().getX() - f.x(), p.getLocation().getZ() - f.z());
                    Fx.actionBar(p, "<red>Płetwa " + Math.round(d) + " bloków od Ciebie! <gray>Wyjdź z wody!");
                }
            }
            case STRIKE -> {
                if (s.breach()) {
                    Fx.sound(p, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.2f, 0.6f);
                    quiet(() -> w.spawnParticle(Particle.SPLASH, shark, 80, 0.8, 0.3, 0.8, 0.2));
                }
                if (a.age % 2 == 0) quiet(() -> w.spawnParticle(Particle.BUBBLE, shark, 8, 0.4, 0.2, 0.4, 0.05));
            }
            case BITE -> {
                Vector3f m = SharkChoreo.mouth(f);
                Location mouth = new Location(w, m.x, m.y, m.z, p.getLocation().getYaw(), p.getLocation().getPitch());
                if (dead) {
                    if (a.age % 2 == 0) quiet(() -> w.spawnParticle(Particle.BLOCK, mouth, 8, 0.25, 0.25, 0.25, 0.0, Bukkit.createBlockData(Material.REDSTONE_BLOCK)));
                    break;
                }
                if (!a.grabbed) {
                    a.grabbed = true;
                    Fx.sound(p, Sound.ENTITY_GENERIC_EAT, 1.2f, 0.5f);
                    Fx.sound(p, Sound.ENTITY_PLAYER_HURT, 1f, 0.8f);
                    quiet(() -> p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, false, false)));
                    Fx.title(p, "<dark_red><bold>...", "");
                }
                p.teleport(mouth);
                if (a.age % 2 == 0) quiet(() -> w.spawnParticle(Particle.BLOCK, mouth, 14, 0.25, 0.25, 0.25, 0.0, Bukkit.createBlockData(Material.REDSTONE_BLOCK)));
                if (a.age % 5 == 0) Fx.sound(p, Sound.ENTITY_PLAYER_HURT_DROWN, 0.9f, 0.7f);
                if (s.kill()) kill(a, p, mouth);
            }
            case DIVE -> {
                if (a.age % 2 == 0) quiet(() -> w.spawnParticle(Particle.BUBBLE, shark, 10, 0.5, 0.3, 0.5, 0.05));
            }
            default -> {
            }
        }
    }

    private void kill(Attack a, Player p, Location mouth) {
        a.killed = true;
        quiet(() -> a.world.spawnParticle(Particle.BLOCK, mouth, 60, 0.4, 0.4, 0.4, 0.0, Bukkit.createBlockData(Material.REDSTONE_BLOCK)));
        if (a.harmless) {
            T.send(p, "<yellow>To był tylko test rekina: nic nie straciłeś.");
            return;
        }
        long loss = SharkRules.loss(plugin.eco().wallet(p.getUniqueId()), config().lossFraction());
        if (loss > 0) plugin.eco().take(p.getUniqueId(), loss);
        victims.add(p.getUniqueId());
        T.send(p, "<dark_red><bold>Rekin Cię pożarł!</bold> <gray>Tracisz <gold>" + pl.kasyno.core.Fmt.money(loss) + "</gold> z portfela <gray>(pieniądze w banku są bezpieczne).");
        p.setHealth(0);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!victims.remove(e.getEntity().getUniqueId())) return;
        Component msg = T.c("<dark_red>" + T.esc(e.getEntity().getName()) + "<red> został pożarty przez rekina");
        e.deathMessage(msg);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        tracks.remove(id);
        victims.remove(id);
        Attack a = attacks.get(id);
        if (a == null) return;
        // kto wychodzi w trakcie rzutu albo chwytu, nie unika straty gotówki
        if (!a.harmless && a.choreo.phase() != SharkChoreo.Phase.APPROACH) {
            long loss = SharkRules.loss(plugin.eco().wallet(id), config().lossFraction());
            if (loss > 0) plugin.eco().take(id, loss);
        }
        finish(a, true);
    }

    /** Kończy atak: usuwa rekina, a po prawdziwym ataku (albo ucieczce) włącza odpoczynek od rekinów. */
    private void finish(Attack a, boolean cooldown) {
        a.choreo.abort();
        if (a.handle != null) backend.remove(a.handle);
        a.handle = null;
        attacks.remove(a.id);
        if (cooldown) {
            Track t = tracks.computeIfAbsent(a.id, k -> new Track());
            t.cooldownUntil = plugin.eco().now() + config().cooldownSeconds() * 1000L;
        }
    }

    private void abortAll(String why) {
        for (Attack a : new ArrayList<>(attacks.values())) {
            Player p = Bukkit.getPlayer(a.id);
            if (p != null) T.send(p, "<green>" + why);
            finish(a, false);
        }
    }

    /** Pokaz modelu: rekin krąży przez `ticks` ticków wokół punktu, co chwilę otwiera paszczę. Nikogo nie atakuje. */
    public void preview(Location center, int ticks, Consumer<Boolean> done) {
        World w = center.getWorld();
        if (w == null) return;
        SharkChoreo.Frame first = SharkChoreo.demo(0, center.getX(), center.getY(), center.getZ(), 4);
        Object handle = backend.spawn(w, first);
        int[] tick = {0};
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tick[0]++;
            if (tick[0] > ticks) {
                backend.remove(handle);
                self[0].cancel();
                previews.remove(handle);
                if (done != null) done.accept(true);
                return;
            }
            backend.update(handle, SharkChoreo.demo(tick[0], center.getX(), center.getY(), center.getZ(), 4));
        }, 1L, 1L);
        previews.put(handle, self[0]);
    }

    private static void quiet(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // efekt kosmetyczny
        }
    }
}
