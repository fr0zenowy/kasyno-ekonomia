package pl.kasyno.waypoint;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.PlayerData;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.T;
import pl.kasyno.show.Stage;
import pl.kasyno.waypoint.WaypointView.Candidate;
import pl.kasyno.waypoint.WaypointView.Placement;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Własne waypointy widoczne w świecie jak w modach z waypointami: pionowa, świecąca kreska w kolorze waypointu (widać ją przez ściany)
 * i unoszący się nad nią napis z nazwą i odległością. Administrator tworzy je komendą, a plugin pokazuje każdemu graczowi jego
 * własne znaczniki najbliższych waypointów (te same encje nie są współdzielone, każdy gracz ma swoje, dzięki czemu napis zna jego
 * odległość i znacznik da się „przykleić” w kierunku bardzo odległego waypointu). Źródłem prawdy jest plik waypoints.yml, a znaczniki
 * to chwilowe encje, które nie zapisują się w świecie i znikają po restarcie. Wyłączenie w config.yml (waypoints.enabled) zdejmuje
 * wszystkie znaczniki i zostawia dane.
 */
public final class WaypointManager implements Listener {
    public static final int MAX_WAYPOINTS = 200;
    /**
     * Co ile ticków odświeżamy znaczniki: co tick. Napis jedzie za graczem, więc rzadsze, nierówne aktualizacje (skoki o pół bloku co
     * kilka ticków) wyglądają jak drganie. Co tick klient wygładza ruch o jeden tick i napis płynie razem z graczem.
     */
    static final int PERIOD_TICKS = 1;
    /** O tyle bloków nad punktem unosi się napis (punkt to miejsce, w którym stał administrator, czyli ziemia pod nogami). */
    public static final double LABEL_RISE = 2.0;

    private static final class Shown {
        final Object handle;
        final String world;
        MarkerBackend.Spec spec;

        Shown(Object handle, String world, MarkerBackend.Spec spec) {
            this.handle = handle;
            this.world = world;
            this.spec = spec;
        }
    }

    private final KasynoPlugin plugin;
    private final WaypointStore store;
    private final NamespacedKey legacyKey;
    private final Map<UUID, Map<String, Shown>> shown = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private final Map<UUID, ViewAnchor> anchors = new HashMap<>();
    private Predicate<Player> followCheck = WaypointManager::followsLevel;
    private MarkerBackend backend;
    private BukkitTask task;
    private boolean active;

    public WaypointManager(KasynoPlugin plugin) {
        this.plugin = plugin;
        this.store = new WaypointStore(new File(plugin.getDataFolder(), "waypoints.yml"));
        this.legacyKey = new NamespacedKey(plugin, "waypoint_id");
        this.backend = new BukkitMarkerBackend(plugin);
    }

    /** Podmienia sposób stawiania znaczników (testy na atrapie). Dotyczy znaczników stawianych później. */
    public void useBackend(MarkerBackend backend) {
        clearAll();
        this.backend = backend;
    }

    // ---------- ustawienia ----------

    private boolean wanted() {
        return plugin.getConfig().getBoolean("waypoints.enabled", true);
    }

    /** Pionowa kreska jest opcjonalna i domyślnie wyłączona: sam napis wygląda czyściej. */
    private boolean beamOn() {
        return plugin.getConfig().getBoolean("waypoints.beam", false);
    }

    /** Na jakiej odległości od gracza stoi napis, gdy waypoint jest dalej (ta sama odległość = ten sam wygląd na ekranie). */
    public double labelDistance() {
        return Math.max(4, Math.min(40, plugin.getConfig().getDouble("waypoints.label-distance", 12)));
    }

    /** Domyślny rozmiar napisu. */
    public static final double DEFAULT_LABEL_SCALE = 2.2;

    /** Stały rozmiar napisu (1 = litery po ok. 0,2 bloku wysokości) na odległości label-distance. */
    public float labelScale() {
        return (float) Math.max(0.5, Math.min(12, plugin.getConfig().getDouble("waypoints.label-scale", DEFAULT_LABEL_SCALE)));
    }

    private boolean labelOn() {
        return plugin.getConfig().getBoolean("waypoints.label", true);
    }

    private double beamHeight() {
        return Math.max(16, Math.min(512, plugin.getConfig().getDouble("waypoints.beam-height", 256)));
    }

    /** Do tylu bloków znacznik stoi w prawdziwym miejscu waypointu, dalej „przykleja się” w jego kierunku. */
    public double markerDistance() {
        return Math.max(24, Math.min(200, plugin.getConfig().getDouble("waypoints.marker-distance", 80)));
    }

    /** Ile najbliższych waypointów widzi naraz jeden gracz. */
    public int maxVisible() {
        return Math.max(1, Math.min(32, plugin.getConfig().getInt("waypoints.max-visible", 8)));
    }

    /** Dalej niż tyle bloków waypoint się nie pokazuje (0 = bez limitu). */
    public double maxDistance() {
        return Math.max(0, plugin.getConfig().getDouble("waypoints.max-distance", 0));
    }

    private int defaultColor() {
        Integer c = WaypointColors.parse(plugin.getConfig().getString("waypoints.default-color", "ffd700"));
        return c == null ? 0xFFD700 : c;
    }

    // ---------- start, wyłączenie, przeładowanie ----------

    public void enable() {
        store.load();
        backend.purge();
        sweepLegacy();
        reload();
    }

    public void disable() {
        stopTask();
        clearAll();
        backend.purge();
        active = false;
    }

    /** Czyta ustawienia od nowa: włączone waypointy dostają pętlę odświeżania, wyłączone tracą wszystkie znaczniki. */
    public void reload() {
        stopTask();
        active = wanted();
        clearAll();
        if (active) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    private void stopTask() {
        if (task != null) task.cancel();
        task = null;
    }

    public boolean active() {
        return active;
    }

    // ---------- dane ----------

    public Collection<Waypoint> all() {
        return store.all();
    }

    public Waypoint find(String nameOrId) {
        return store.get(nameOrId);
    }

    public List<String> names() {
        return store.all().stream().map(Waypoint::name).toList();
    }

    public int count() {
        return store.size();
    }

    private String save() {
        try {
            store.save();
            return null;
        } catch (IOException e) {
            plugin.getLogger().warning("Nie udało się zapisać waypoints.yml: " + e);
            return "Nie udało się zapisać waypoints.yml (sprawdź log serwera).";
        }
    }

    /** Dodaje waypoint w miejscu `at`. Zwraca komunikat błędu albo null. `color` null oznacza kolor domyślny z config.yml. */
    public String add(Location at, String name, Integer color) {
        if (!Waypoint.validName(name)) return "Nazwa to litery, cyfry, _ i - (do " + Waypoint.MAX_NAME + " znaków). Spację zastąp podkreśleniem, np. Moj_Dom.";
        if (store.get(name) != null) return "Waypoint „" + T.esc(Waypoint.display(name)) + "” już istnieje.";
        if (store.size() >= MAX_WAYPOINTS) return "Za dużo waypointów (limit " + MAX_WAYPOINTS + ").";
        store.put(new Waypoint(Waypoint.idOf(name), name, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), color == null ? defaultColor() : color));
        return save();
    }

    public boolean remove(String nameOrId) {
        Waypoint w = store.remove(nameOrId);
        if (w == null) return false;
        forget(w.id());
        save();
        return true;
    }

    /** Przenosi waypoint do miejsca `at` (także do innego świata). */
    public boolean move(String nameOrId, Location at) {
        Waypoint old = store.get(nameOrId);
        if (old == null) return false;
        store.put(old.movedTo(at.getWorld().getName(), at.getX(), at.getY(), at.getZ()));
        forget(old.id());
        save();
        return true;
    }

    public boolean recolor(String nameOrId, int rgb) {
        Waypoint old = store.get(nameOrId);
        if (old == null) return false;
        store.put(old.withColor(rgb));
        forget(old.id());
        save();
        return true;
    }

    // ---------- znaczniki ----------

    /** Czy gracz sam wyłączył sobie znaczniki (do restartu serwera). */
    public boolean hiddenFor(Player p) {
        return hidden.contains(p.getUniqueId());
    }

    public void setHidden(Player p, boolean hide) {
        if (hide) {
            hidden.add(p.getUniqueId());
            clear(p.getUniqueId());
        } else {
            hidden.remove(p.getUniqueId());
        }
    }

    /** Ile znaczników (kresek z napisami) ma teraz gracz. */
    public int markersOf(UUID player) {
        Map<String, Shown> mine = shown.get(player);
        return mine == null ? 0 : mine.size();
    }

    /** Czy gracz ma teraz znacznik tego waypointu. */
    public boolean showing(UUID player, String id) {
        Map<String, Shown> mine = shown.get(player);
        return mine != null && mine.containsKey(id);
    }

    private void clear(UUID id) {
        anchors.remove(id);
        Map<String, Shown> mine = shown.remove(id);
        if (mine != null) for (Shown s : mine.values()) backend.remove(s.handle);
    }

    private void clearAll() {
        for (UUID id : List.copyOf(shown.keySet())) clear(id);
    }

    /** Zdejmuje znacznik jednego waypointu wszystkim graczom (po zmianie: pojawi się od nowa w następnym kroku). */
    private void forget(String waypointId) {
        for (Map<String, Shown> mine : shown.values()) {
            Shown s = mine.remove(waypointId);
            if (s != null) backend.remove(s.handle);
        }
    }

    /** Jeden krok odświeżania (co PERIOD_TICKS ticków): każdy gracz dostaje znaczniki swoich najbliższych waypointów. */
    public void tick() {
        if (!active) return;
        Set<UUID> online = new HashSet<>();
        List<Waypoint> all = List.copyOf(store.all());
        List<Waypoint> auto = autoPlaces();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getUniqueId());
            try {
                refresh(p, all, auto);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Waypointy: błąd odświeżania znaczników gracza " + p.getName() + ": " + e);
                clear(p.getUniqueId());
            }
        }
        for (UUID id : List.copyOf(shown.keySet())) if (!online.contains(id)) clear(id);
    }

    private void refresh(Player p, List<Waypoint> pub, List<Waypoint> auto) {
        UUID id = p.getUniqueId();
        PlayerData data = plugin.eco().pd(id);
        List<Waypoint> mineOwn = personal(data);
        reach(p, data, pub, auto, mineOwn);
        // prowadzony cel zostawia graczowi tylko siebie i jego osobiste punkty
        List<Waypoint> all = pool(data, pub, auto, mineOwn);
        if (hidden.contains(id)) {
            clear(id);
            return;
        }
        String world = p.getWorld().getName();
        Map<String, Shown> mine = shown.computeIfAbsent(id, k -> new HashMap<>());
        // znaczniki z innego świata (gracz przeszedł przez portal) zdejmujemy od razu
        mine.values().removeIf(s -> {
            if (s.world.equals(world)) return false;
            backend.remove(s.handle);
            return true;
        });
        // punkt odniesienia nie skacze razem z graczem: krótki skok, kucanie ani drganie kamery nie ruszają napisów
        ViewAnchor anchor = anchors.computeIfAbsent(id, k -> new ViewAnchor());
        Location feet = p.getLocation();
        anchor.update(world, feet.getX(), feet.getY(), feet.getZ(), followCheck.test(p));
        double ex = anchor.x();
        double ey = anchor.eyeY();
        double ez = anchor.z();
        double md = Math.min(markerDistance(), Math.max(32, viewDistance(p) * 16.0 - 16));
        double ld = Math.min(labelDistance(), md);
        List<Candidate> top = WaypointView.nearest(all, world, ex, ey, ez, maxVisible(), maxDistance());
        Set<String> keep = new HashSet<>();
        for (Candidate c : top) {
            Waypoint w = c.waypoint();
            Placement beam = WaypointView.place(ex, ey, ez, w.x(), w.y(), w.z(), md);
            // napis stoi nad punktem i przyklejony zawsze blisko gracza, więc zawsze w załadowanym chunku
            Placement label = WaypointView.place(ex, ey, ez, w.x(), w.y() + LABEL_RISE, w.z(), ld);
            // nie ładujemy chunków na siłę: pomijamy znacznik, którego część stałaby w niezaładowanym chunku (kreskę liczymy tylko, gdy jest włączona)
            if (beamOn() && !p.getWorld().isChunkLoaded((int) Math.floor(beam.x()) >> 4, (int) Math.floor(beam.z()) >> 4)) continue;
            if (labelOn() && !p.getWorld().isChunkLoaded((int) Math.floor(label.x()) >> 4, (int) Math.floor(label.z()) >> 4)) continue;
            boolean tracked = w.id().equals(data.track);
            boolean pending = w.kind() == Waypoint.Kind.PLACE && w.reward() > 0 && !data.discovered.contains(w.id());
            MarkerBackend.Spec spec = specOf(w, beam, label, ld, tracked, pending);
            Shown s = mine.get(w.id());
            if (s == null) {
                Object handle = backend.spawn(p, spec);
                if (handle != null) mine.put(w.id(), new Shown(handle, world, spec));
            } else if (spec.differs(s.spec)) {
                backend.update(s.handle, spec);
                s.spec = spec;
            }
            keep.add(w.id());
        }
        mine.entrySet().removeIf(e -> {
            if (keep.contains(e.getKey())) return false;
            backend.remove(e.getValue().handle);
            return true;
        });
    }

    /**
     * Czy wysokość gracza trzeba teraz śledzić: stoi na ziemi, lata, pływa, szybuje, wspina się albo jedzie. W skoku jest false, więc
     * napisy nie podskakują razem z nim. Gdy serwer nie umie odpowiedzieć, śledzimy zawsze (wtedy po prostu nie ma stabilizacji).
     */
    private static boolean followsLevel(Player p) {
        try {
            return p.isOnGround() || p.isFlying() || p.isSwimming() || p.isGliding() || p.isClimbing() || p.isInsideVehicle();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Podmienia sprawdzanie, czy gracz „stoi na podłożu” (testy na atrapie, która nie zna skoku). */
    public void useFollowCheck(Predicate<Player> check) {
        this.followCheck = check;
    }

    /** Zasięg widzenia gracza w chunkach (znacznik nie może stać za nim); 10, gdy serwer go nie podaje. */
    private static int viewDistance(Player p) {
        try {
            return Math.max(2, p.getViewDistance());
        } catch (RuntimeException e) {
            return 10;
        }
    }

    /**
     * Wygląd jak etykieta z moda: nazwa pogrubiona w kolorze waypointu, pod nią odległość po polsku („76 metrów”), na ciemnym tle.
     * Napis stoi wyśrodkowany na `label`, a TextDisplay stoi dolną krawędzią ramki, więc podstawę obniżamy o połowę wysokości
     * dwóch wierszy (ok. 0,25 bloku na jednostkę skali).
     */
    private MarkerBackend.Spec specOf(Waypoint w, Placement beam, Placement label, double labelDistance, boolean tracked, boolean rewardPending) {
        double shown = label.projected() ? labelDistance : label.distance();
        float scale = WaypointView.labelScale(shown, labelDistance, labelScale());
        String text = "<#" + WaypointColors.hex(w.color()) + "><bold>" + WaypointView.prefix(w.kind(), tracked, rewardPending) + T.esc(w.title()) + "</bold><newline><white>"
                + WaypointView.number(beam.distance()) + " <gray>" + WaypointView.unit(beam.distance()) + (rewardPending ? WaypointView.rewardHint(w.reward()) : "");
        // kreska wyrasta nieco pod punktem, żeby nie zaczynała się w powietrzu
        return new MarkerBackend.Spec(beam.x(), beam.y() - 0.5, beam.z(), label.x(), label.y() - 0.25 * scale, label.z(), text, scale, w.color(), beamOn(), labelOn(), beamHeight());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        clear(e.getPlayer().getUniqueId());
        hidden.remove(e.getPlayer().getUniqueId());
    }

    // ---------- waypointy z sensem: miejsca kasyna, dom, miejsce śmierci, prowadzenie i nagrody za odkrycie ----------

    static final String ID_HOME = "@dom";
    static final String ID_DEATH = "@smierc";
    static final String ID_BANK = "~bank";
    static final String ID_ARENA = "~arena";
    static final String ID_PRISON = "~wiezienie";

    /** Ile bloków od punktu trzeba podejść, żeby go „odkryć” i odebrać nagrodę. */
    public double discoverRadius() {
        return Math.max(2, Math.min(64, plugin.getConfig().getDouble("waypoints.discover-radius", 8)));
    }

    /** Ile bloków od prowadzonego celu uznajemy, że gracz do niego dotarł. */
    public double arriveRadius() {
        return Math.max(2, Math.min(64, plugin.getConfig().getDouble("waypoints.arrive-radius", 6)));
    }

    /** Ile minut po śmierci widać „Miejsce śmierci” (rzeczy i tak znikają po 5 minutach). 0 wyłącza ten punkt. */
    public int deathMinutes() {
        return Math.max(0, Math.min(120, plugin.getConfig().getInt("waypoints.death-minutes", 10)));
    }

    private static Waypoint spot(String id, String name, Location at, int color, Waypoint.Kind kind) {
        return new Waypoint(id, name, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), color, kind, 0);
    }

    /** Miejsca samego kasyna (bank, arena, więzienie), o ile istnieją. Nie są zapisywane: wynikają z tego, co administrator już ustawił. */
    public List<Waypoint> autoPlaces() {
        List<Waypoint> out = new ArrayList<>();
        if (!plugin.getConfig().getBoolean("waypoints.auto-places", true)) return out;
        try {
            Stage st = plugin.show().stage();
            if (st.ready()) {
                Location l = st.location("door");
                if (l != null && l.getWorld() != null) out.add(spot(ID_BANK, "Bank", l, 0xFFD700, Waypoint.Kind.AUTO));
            }
            if (st.arenaReady()) {
                Location l = st.arenaLocation(Stage.ARENA_CENTER);
                if (l != null && l.getWorld() != null) out.add(spot(ID_ARENA, "Arena", l, 0xFF5555, Waypoint.Kind.AUTO));
            }
            if (plugin.prison().enabled()) {
                Location l = plugin.prison().cellLocation(0, 0, 0);
                if (l != null && l.getWorld() != null) out.add(spot(ID_PRISON, "Więzienie", l, 0xAAAAAA, Waypoint.Kind.AUTO));
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Waypointy: nie udało się wyliczyć miejsc kasyna: " + e);
        }
        return out;
    }

    /** Osobiste punkty gracza: dom i (przez kilka minut) miejsce ostatniej śmierci. */
    private List<Waypoint> personal(PlayerData d) {
        List<Waypoint> out = new ArrayList<>(2);
        if (d.homeWorld != null) out.add(new Waypoint(ID_HOME, "Dom", d.homeWorld, d.homeX, d.homeY, d.homeZ, 0x55FF55, Waypoint.Kind.HOME, 0));
        if (d.deathWorld != null) {
            long keep = deathMinutes() * 60_000L;
            if (keep > 0 && plugin.eco().now() - d.deathAt <= keep) out.add(new Waypoint(ID_DEATH, "Miejsce_śmierci", d.deathWorld, d.deathX, d.deathY, d.deathZ, 0xFF5555, Waypoint.Kind.DEATH, 0));
            else d.deathWorld = null;
        }
        return out;
    }

    /** Co gracz ma widzieć: wszystko albo, gdy kogoś prowadzimy, tylko ten cel i jego osobiste punkty. */
    private List<Waypoint> pool(PlayerData d, List<Waypoint> pub, List<Waypoint> auto, List<Waypoint> own) {
        if (d.track != null) {
            Waypoint t = byId(d.track, pub, auto, own);
            if (t == null) {
                d.track = null;
            } else {
                List<Waypoint> only = new ArrayList<>(own);
                if (!own.contains(t)) only.add(t);
                return only;
            }
        }
        List<Waypoint> out = new ArrayList<>(pub);
        out.addAll(auto);
        out.addAll(own);
        return out;
    }

    private static Waypoint byId(String id, List<Waypoint> pub, List<Waypoint> auto, List<Waypoint> own) {
        for (List<Waypoint> l : List.of(own, auto, pub)) for (Waypoint w : l) if (w.id().equals(id)) return w;
        return null;
    }

    /** Odkrycia z nagrodą, dotarcie do prowadzonego celu i dojście do miejsca śmierci. */
    private void reach(Player p, PlayerData d, List<Waypoint> pub, List<Waypoint> auto, List<Waypoint> own) {
        Location at = p.getLocation();
        String world = p.getWorld().getName();
        double discover = discoverRadius();
        for (Waypoint w : pub) {
            if (w.reward() <= 0 || !w.world().equals(world) || d.discovered.contains(w.id())) continue;
            if (WaypointView.distance(at.getX(), at.getY(), at.getZ(), w) <= discover) discover(p, d, w);
        }
        for (Waypoint w : own) {
            if (w.kind() != Waypoint.Kind.DEATH || !w.world().equals(world)) continue;
            if (WaypointView.distance(at.getX(), at.getY(), at.getZ(), w) <= 4) {
                d.deathWorld = null;
                if (w.id().equals(d.track)) d.track = null;
                plugin.store().touch(p.getUniqueId());
                T.send(p, "<gray>Jesteś przy miejscu śmierci: punkt zniknął.");
            }
        }
        if (d.track != null) {
            Waypoint t = byId(d.track, pub, auto, own);
            if (t != null && t.world().equals(world) && WaypointView.distance(at.getX(), at.getY(), at.getZ(), t) <= arriveRadius()) {
                d.track = null;
                plugin.store().touch(p.getUniqueId());
                Fx.coin(p);
                Fx.actionBar(p, "<green>Dotarłeś: <white>" + T.esc(t.title()));
                T.send(p, "<green>Dotarłeś do miejsca <white>" + T.esc(t.title()) + "</white>. Prowadzenie wyłączone.");
            }
        }
    }

    private void discover(Player p, PlayerData d, Waypoint w) {
        d.discovered.add(w.id());
        UUID id = p.getUniqueId();
        plugin.eco().add(id, w.reward());
        plugin.eco().addXp(id, 15);
        plugin.store().touch(id);
        Fx.title(p, "<gold><bold>ODKRYTO!", "<white>" + T.esc(w.title()) + " <gray>+" + Fmt.money(w.reward()));
        Fx.coin(p);
        T.send(p, "<gold><bold>Odkrycie!</bold> <white>" + T.esc(w.title()) + "</white> <gray>— nagroda <gold>" + Fmt.money(w.reward()) + "</gold>.");
    }

    /** Zapamiętuje miejsce śmierci gracza (do odnalezienia rzeczy). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (!active || deathMinutes() == 0) return;
        Player p = e.getEntity();
        PlayerData d = plugin.eco().pd(p.getUniqueId());
        Location l = p.getLocation();
        if (l.getWorld() == null) return;
        d.deathWorld = l.getWorld().getName();
        d.deathX = l.getX();
        d.deathY = l.getY();
        d.deathZ = l.getZ();
        d.deathAt = plugin.eco().now();
        plugin.store().touch(p.getUniqueId());
        T.send(p, "<gray>☠ Zaznaczyłem <white>Miejsce śmierci</white> jako waypoint (znika po " + deathMinutes() + " min albo gdy do niego dojdziesz). Prowadzi tam: <white>/kasyno waypoint prowadz smierc");
        forgetFor(p.getUniqueId(), ID_DEATH);
    }

    /** Zdejmuje jeden znacznik jednemu graczowi (pojawi się od nowa w następnym kroku). */
    private void forgetFor(UUID player, String waypointId) {
        Map<String, Shown> mine = shown.get(player);
        if (mine == null) return;
        Shown s = mine.remove(waypointId);
        if (s != null) backend.remove(s.handle);
    }

    /** Wszystkie punkty, które gracz może widzieć: publiczne, miejsca kasyna i jego własne. */
    public List<Waypoint> visibleTo(Player p) {
        PlayerData d = plugin.eco().pd(p.getUniqueId());
        List<Waypoint> out = new ArrayList<>(store.all());
        out.addAll(autoPlaces());
        out.addAll(personal(d));
        return out;
    }

    /**
     * Szuka punktu po nazwie wśród widocznych dla gracza. Kolejność: własne punkty gracza (dom, smierc), potem waypointy administratora
     * (jego „Bank” wygrywa z automatycznym), na końcu miejsca kasyna (bank, arena, wiezienie).
     */
    public Waypoint resolve(Player p, String query) {
        if (query == null || query.isBlank()) return null;
        String q = Waypoint.idOf(query);
        PlayerData d = plugin.eco().pd(p.getUniqueId());
        String own = switch (q) {
            case "dom", "home" -> ID_HOME;
            case "smierc", "miejsce_smierci", "death" -> ID_DEATH;
            default -> null;
        };
        if (own != null) for (Waypoint v : personal(d)) if (v.id().equals(own)) return v;
        Waypoint w = store.get(query);
        if (w != null) return w;
        String auto = switch (q) {
            case "bank" -> ID_BANK;
            case "arena" -> ID_ARENA;
            case "wiezienie", "prison" -> ID_PRISON;
            default -> null;
        };
        if (auto != null) for (Waypoint v : autoPlaces()) if (v.id().equals(auto)) return v;
        return null;
    }

    /** Ustawia prowadzenie do punktu (null albo pusta nazwa wyłącza). Zwraca komunikat błędu albo null. */
    public String track(Player p, String query) {
        PlayerData d = plugin.eco().pd(p.getUniqueId());
        if (query == null || query.isBlank() || query.equalsIgnoreCase("wylacz") || query.equalsIgnoreCase("off")) {
            d.track = null;
            plugin.store().touch(p.getUniqueId());
            return null;
        }
        Waypoint w = resolve(p, query);
        if (w == null) return "Nie ma takiego miejsca. Lista: /kasyno waypoint lista";
        if (!w.world().equals(p.getWorld().getName())) return "To miejsce jest w innym świecie (" + w.world() + ").";
        d.track = w.id();
        plugin.store().touch(p.getUniqueId());
        return null;
    }

    /** Punkt, do którego gracz jest teraz prowadzony (albo null). */
    public Waypoint tracked(Player p) {
        String t = plugin.eco().pd(p.getUniqueId()).track;
        if (t == null) return null;
        for (Waypoint w : visibleTo(p)) if (w.id().equals(t)) return w;
        return null;
    }

    /** Ustawia dom gracza w jego miejscu. */
    public void setHome(Player p) {
        PlayerData d = plugin.eco().pd(p.getUniqueId());
        Location l = p.getLocation();
        d.homeWorld = l.getWorld().getName();
        d.homeX = l.getX();
        d.homeY = l.getY();
        d.homeZ = l.getZ();
        plugin.store().touch(p.getUniqueId());
        forgetFor(p.getUniqueId(), ID_HOME);
    }

    public boolean clearHome(Player p) {
        PlayerData d = plugin.eco().pd(p.getUniqueId());
        if (d.homeWorld == null) return false;
        d.homeWorld = null;
        if (ID_HOME.equals(d.track)) d.track = null;
        plugin.store().touch(p.getUniqueId());
        forgetFor(p.getUniqueId(), ID_HOME);
        return true;
    }

    /** Ustawia nagrodę za odkrycie miejsca (0 = bez nagrody). Zwraca komunikat błędu albo null. */
    public String setReward(String nameOrId, long amount) {
        Waypoint old = store.get(nameOrId);
        if (old == null) return "Nie ma waypointu „" + T.esc(nameOrId) + "”.";
        if (amount < 0 || amount > 1_000_000) return "Nagroda to 0 do 1 000 000.";
        store.put(old.withReward(amount));
        forget(old.id());
        return save();
    }

    public boolean discovered(UUID player, String id) {
        return plugin.eco().pd(player).discovered.contains(id);
    }

    /** Ile miejsc z nagrodą jest w ogóle i ile z nich gracz już odkrył. */
    public int[] discoveryProgress(UUID player) {
        int total = 0;
        int found = 0;
        PlayerData d = plugin.eco().pd(player);
        for (Waypoint w : store.all()) {
            if (w.reward() <= 0) continue;
            total++;
            if (d.discovered.contains(w.id())) found++;
        }
        return new int[]{found, total};
    }

    // ---------- sprzątanie po starej wersji (manekiny na pasku lokalizatora) ----------

    private boolean isLegacy(Entity e) {
        return e instanceof ArmorStand && e.getPersistentDataContainer().has(legacyKey, PersistentDataType.STRING);
    }

    /** Usuwa niewidzialne manekiny, które wcześniejsza wersja pluginu stawiała pod pasek lokalizatora. */
    private void sweepLegacy() {
        for (World world : Bukkit.getWorlds()) {
            try {
                for (ArmorStand a : world.getEntitiesByClass(ArmorStand.class)) if (isLegacy(a)) a.remove();
            } catch (RuntimeException ignored) {
                // część chunków może nie być jeszcze załadowana, resztę łapie EntitiesLoadEvent
            }
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (isLegacy(en)) en.remove();
    }
}
