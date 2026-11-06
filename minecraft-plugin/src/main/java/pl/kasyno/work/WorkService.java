package pl.kasyno.work;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.scheduler.BukkitTask;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Notify;
import pl.kasyno.gui.T;
import pl.kasyno.show.Stage.P;
import pl.kasyno.work.JobDef.Kind;
import pl.kasyno.work.JobDef.NodeType;
import pl.kasyno.work.JobPlanner.Plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * Prace w prawdziwym świecie. NPC (Leśniczy, Kierownik kopalni, Rybak...) zleca rundę: wokół niego pojawiają się świecące cele
 * widoczne tylko dla tego gracza (grzyby, rudy, ryby, śmieci, paczki, skarb). Gracz je znajduje, klika i od razu dostaje pieniądze.
 * Zarobek liczy się jak przy innych zarobkach (karty VIP, zwierzaki, eliksiry), a cooldown rundy startuje, gdy runda się kończy.
 * Cały czas liczymy w tickach pętli, nie w czasie zegarowym. Wszystko dzieje się w wątku głównego serwera.
 */
public final class WorkService {
    public enum End { DONE, TIMEOUT, CANCEL, QUIT, LEASH, SHUTDOWN }

    /** Miejsce pracy: świat, środek (pozycja NPC) i promień, w którym rozstawiamy cele. */
    public record Site(String world, P center, int radius, String source) {}

    /** Stan trwającej rundy (do okna NPC). */
    public record Status(JobDef job, int collected, int goal, long earned, long ticksLeft) {}

    /** Wynik zakończonej rundy. */
    public record Summary(JobDef job, End how, int collected, int goal, long earned, long bonus) {}

    /** Co ile ticków chodzi pętla pracy. */
    static final int STEP = 5;
    /** Tyle czasu (ms) pamiętamy, którego NPC gracz kliknął. */
    static final long CONTEXT_MS = 90_000;
    /** Najdalej od celu, z jakiego kliknięcie jeszcze się liczy (bloki). */
    static final double REACH = 7;
    /** Ile bloków za promieniem pracy gracz może stać przy starcie rundy. */
    static final double JOIN_SLACK = 25;
    public static final int MIN_RADIUS = 10;
    public static final int MAX_RADIUS = 120;
    /** Szansa na skrzynkę ze skarbu. */
    static final double TREASURE_CRATE = 0.25;

    private static final class Node {
        final NodeBackend.Spawned spawned;
        final int type;
        final long born;
        final long life;
        P pos;
        boolean visible;
        double heading;

        Node(NodeBackend.Spawned spawned, int type, P pos, boolean visible, long born, long life, double heading) {
            this.spawned = spawned;
            this.type = type;
            this.pos = pos;
            this.visible = visible;
            this.born = born;
            this.life = life;
            this.heading = heading;
        }
    }

    private static final class Session {
        final Player player;
        final JobDef job;
        final World world;
        final Site site;
        final Terrain terrain;
        final double luck;
        final long start;
        final long deadline;
        final List<Node> nodes = new ArrayList<>();
        final List<Plan> route = new ArrayList<>();
        int goal;
        int collected;
        int respawns;
        long earned;
        long legStart;
        P legFrom;
        double lastDistance = -1;
        String trend = "";
        boolean warned;

        Session(Player player, JobDef job, World world, Site site, Terrain terrain, double luck, long start) {
            this.player = player;
            this.job = job;
            this.world = world;
            this.site = site;
            this.terrain = terrain;
            this.luck = luck;
            this.start = start;
            this.deadline = start + job.seconds() * 20L;
        }
    }

    private record Context(String job, Site site, long until) {}

    private final KasynoPlugin plugin;
    private final NodeBackend backend;
    private Function<World, Terrain> terrains;
    private final RandomGenerator rng;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, UUID> nodeOwner = new HashMap<>();
    private final Map<UUID, Context> contexts = new HashMap<>();
    private final Map<UUID, Summary> last = new HashMap<>();
    private BukkitTask task;
    private long clock;

    public WorkService(KasynoPlugin plugin) {
        this(plugin, new BukkitNodeBackend(plugin), BukkitTerrain::new);
    }

    /** Wersja do testów: własne cele i teren zamiast prawdziwego świata. */
    public WorkService(KasynoPlugin plugin, NodeBackend backend, Function<World, Terrain> terrains) {
        this.plugin = plugin;
        this.backend = backend;
        this.terrains = terrains;
        this.rng = plugin.eco().rng;
    }

    /** Podmienia sposób czytania terenu (testy na atrapie świata, własne generatory). Dotyczy rund, które ruszą później. */
    public void useTerrain(Function<World, Terrain> terrains) {
        this.terrains = terrains;
    }

    public void enable() {
        backend.purge();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, STEP, STEP);
    }

    /** Kończy wszystkie rundy bez cooldownu (to nie wina graczy), sprząta cele; pieniądze za zebrane cele już są wypłacone. */
    public void disable() {
        if (task != null) task.cancel();
        task = null;
        for (Session s : new ArrayList<>(sessions.values())) end(s, End.SHUTDOWN);
        backend.purge();
    }

    // ---------- miejsce pracy ----------

    public static int clampRadius(int r) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, r));
    }

    private static P at(Location l) {
        return new P(l.getX(), l.getY(), l.getZ());
    }

    public Site siteOf(Villager npc, JobDef job) {
        int r = plugin.npcs().radiusOf(npc);
        Location l = npc.getLocation();
        return new Site(l.getWorld().getName(), at(l), r > 0 ? clampRadius(r) : job.radius(), "npc");
    }

    /** Zapamiętuje NPC, którego gracz właśnie kliknął, żeby okno pracy wiedziało, gdzie rozstawić cele. */
    public void remember(Player p, JobDef job, Site site) {
        contexts.put(p.getUniqueId(), new Context(job.id(), site, plugin.eco().now() + CONTEXT_MS));
    }

    /**
     * Miejsce pracy dla gracza: ostatnio kliknięty NPC tej pracy, inaczej najbliższy taki NPC w jego świecie. Gdy żadnego nie ma w
     * zasięgu, administrator może pracować tam, gdzie stoi (do prób), a zwykły gracz dostaje najbliższego NPC, nawet daleko,
     * żeby okno mogło mu powiedzieć, gdzie go szukać. Null, gdy w świecie nie ma takiego NPC.
     */
    public Site siteFor(Player p, JobDef job) {
        Context c = contexts.get(p.getUniqueId());
        String world = p.getWorld().getName();
        if (c != null && c.job().equals(job.id()) && plugin.eco().now() < c.until() && c.site().world().equals(world)) return c.site();
        Site nearest = null;
        double best = Double.MAX_VALUE;
        P me = at(p.getLocation());
        for (Villager v : plugin.npcs().all()) {
            if (!job.id().equals(plugin.npcs().typeOf(v)) || !v.getWorld().equals(p.getWorld())) continue;
            Site s = siteOf(v, job);
            double d = me.flat(s.center());
            if (d < best) {
                best = d;
                nearest = s;
            }
        }
        if (nearest != null && best <= nearest.radius() + JOIN_SLACK) return nearest;
        if (p.hasPermission("kasyno.admin")) return new Site(world, me, job.radius(), "admin");
        return nearest;
    }

    // ---------- start i koniec ----------

    private String toolHint(String key) {
        Catalog cat = plugin.catalog();
        List<Catalog.GatherTool> tools = cat.gather.get(key).tools();
        for (int i = tools.size() - 1; i >= 0; i--) {
            String id = tools.get(i).id();
            if (id != null && cat.item(id) != null) return cat.item(id).name() + " (albo lepszy)";
        }
        return "narzędzie ze sklepu";
    }

    /** Rozpoczyna rundę. Zwraca komunikat błędu (MiniMessage) albo null, gdy runda ruszyła. */
    public String start(Player p, JobDef job, Site site) {
        UUID id = p.getUniqueId();
        Eco eco = plugin.eco();
        Session running = sessions.get(id);
        if (running != null) return "Masz już pracę w toku: " + running.job.title() + ". Dokończ ją albo anuluj w oknie NPC.";
        if (site == null) return "Nie ma tu NPC od tej pracy. Poproś administratora o /kasyno npc spawn " + job.id() + ".";
        World w = p.getWorld();
        if (!w.getName().equals(site.world())) return "Miejsce pracy jest w innym świecie (" + T.esc(site.world()) + ").";
        P me = at(p.getLocation());
        if (me.flat(site.center()) > site.radius() + JOIN_SLACK) {
            P c = site.center();
            return "Jesteś za daleko od miejsca pracy. Podejdź do NPC (" + Math.round(c.x()) + " " + Math.round(c.y()) + " " + Math.round(c.z()) + ").";
        }
        long left = eco.cooldownLeft(id, cooldownKey(job));
        if (left > 0) return "Następna runda za " + Fmt.duration(left) + ".";
        double toolLuck = 1;
        if (job.gatherKey() != null) {
            Catalog.GatherTool tool = plugin.gather().bestTool(id, job.gatherKey());
            if (tool == null) return "Potrzebujesz narzędzia: " + toolHint(job.gatherKey()) + ". Kupisz je w sklepie.";
            toolLuck = tool.luck();
        }
        double luck = toolLuck * eco.luckMultiplier(id);
        Terrain terrain = terrains.apply(w);
        List<Plan> plan = JobPlanner.plan(job, terrain, site.center(), site.radius(), rng, luck);
        if (plan.isEmpty()) {
            return job.kind() == Kind.WATER ? "W promieniu " + site.radius() + " bloków od NPC nie ma wody (albo jest za płytka). Poproś administratora o inne miejsce."
                    : "Nie znalazłem w okolicy NPC miejsca na cele (teren zajęty albo niezaładowany). Spróbuj za chwilę.";
        }
        Session s = new Session(p, job, w, site, terrain, luck, clock);
        sessions.put(id, s);
        switch (job.kind()) {
            case COLLECT -> {
                for (Plan pl : plan) spawn(s, pl.type(), pl.pos(), true, 0);
                s.goal = Math.min(job.quota(), s.nodes.size());
            }
            case WATER -> {
                for (Plan pl : plan) spawn(s, pl.type(), pl.pos(), true, fishLife());
                s.goal = job.quota();
            }
            case ROUTE -> {
                s.route.addAll(plan);
                s.goal = Math.min(job.quota(), s.route.size());
                s.legFrom = site.center();
                spawnStop(s);
            }
            case HUNT -> {
                Plan pl = plan.get(0);
                spawn(s, pl.type(), pl.pos(), false, 0);
                s.goal = 1;
            }
        }
        if (s.nodes.isEmpty()) {
            sessions.remove(id);
            return "Nie udało się postawić celów w świecie. Sprawdź log serwera.";
        }
        Fx.title(p, "<green><bold>" + T.esc(job.title()), "<gray>Cel: " + s.goal + " • czas: " + Compass.clock(job.seconds() * 20L));
        Fx.ding(p, 1.4f);
        T.send(p, "<green>" + T.esc(job.intro()));
        return null;
    }

    /** Kończy rundę gracza na jego prośbę. Zwraca false, gdy nic nie trwało. */
    public boolean cancel(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return false;
        end(s, End.CANCEL);
        return true;
    }

    /** Wyjście z serwera: runda się kończy (cooldown zostaje), cele znikają. */
    public void quit(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null) end(s, End.QUIT);
        contexts.remove(p.getUniqueId());
    }

    private String cooldownKey(JobDef job) {
        return "work:" + job.id();
    }

    private Summary end(Session s, End how) {
        UUID id = s.player.getUniqueId();
        if (sessions.get(id) != s) return null;
        sessions.remove(id);
        for (Node n : s.nodes) {
            backend.remove(n.spawned.handle());
            nodeOwner.remove(n.spawned.clickId());
        }
        s.nodes.clear();
        Eco eco = plugin.eco();
        long bonus = 0;
        if (how == End.DONE && s.job.completionBonus() > 0 && s.earned > 0) {
            bonus = Math.round(s.earned * s.job.completionBonus());
            eco.add(id, bonus);
            s.earned += bonus;
        }
        if (how != End.SHUTDOWN) eco.setCooldown(id, cooldownKey(s.job), eco.scaledCooldown(id, s.job.cooldownMs()));
        Summary sum = new Summary(s.job, how, s.collected, s.goal, s.earned, bonus);
        last.put(id, sum);
        Player p = s.player;
        if (p.isOnline() && how != End.QUIT) report(p, sum);
        return sum;
    }

    private void report(Player p, Summary sum) {
        String title = T.esc(sum.job().title());
        String result = sum.collected() + "/" + sum.goal() + " • " + Fmt.money(sum.earned());
        switch (sum.how()) {
            case DONE -> {
                Fx.title(p, "<green><bold>" + title + ": komplet!", "<gold>" + Fmt.money(sum.earned()) + (sum.bonus() > 0 ? " <gray>(w tym premia " + Fmt.money(sum.bonus()) + ")" : ""));
                Fx.jackpot(p);
                T.send(p, "<green>Runda skończona: <white>" + result + "</white>" + (sum.bonus() > 0 ? " <gray>(premia za komplet " + Fmt.money(sum.bonus()) + ")" : "") + ".");
            }
            case TIMEOUT -> {
                Fx.title(p, "<yellow><bold>Czas minął", "<gray>" + result);
                T.send(p, "<yellow>Czas minął. Zebrano <white>" + result + "</white>.");
            }
            case CANCEL -> T.send(p, "<yellow>Przerwano pracę: <white>" + result + "</white>. Cele zniknęły.");
            case LEASH -> T.send(p, "<yellow>Oddaliłeś się od miejsca pracy, runda skończona: <white>" + result + "</white>.");
            case SHUTDOWN -> T.send(p, "<yellow>Praca przerwana (restart albo przeładowanie). Zarobione pieniądze zostają: <white>" + Fmt.money(sum.earned()) + "</white>.");
            case QUIT -> {
            }
        }
        if (sum.earned() > 0 && sum.how() != End.DONE) Fx.coin(p);
    }

    // ---------- zapytania ----------

    public boolean active(UUID id) {
        return sessions.containsKey(id);
    }

    public int running() {
        return sessions.size();
    }

    public Status status(UUID id) {
        Session s = sessions.get(id);
        return s == null ? null : new Status(s.job, s.collected, s.goal, s.earned, Math.max(0, s.deadline - clock));
    }

    public Summary lastSummary(UUID id) {
        return last.get(id);
    }

    /** Czy to pole kliknięcia należy do któregoś celu pracy (wtedy kliknięcie nie robi nic innego). */
    public boolean isNode(UUID entity) {
        return nodeOwner.containsKey(entity);
    }

    /** Cele gracza (do testów i podglądu): pozycje, rodzaje i widoczność. */
    public List<NodeInfo> nodes(UUID id) {
        Session s = sessions.get(id);
        List<NodeInfo> out = new ArrayList<>();
        if (s != null) for (Node n : s.nodes) out.add(new NodeInfo(n.spawned.clickId(), s.job.type(n.type), n.pos, n.visible));
        return out;
    }

    public record NodeInfo(UUID clickId, NodeType type, P pos, boolean visible) {}

    // ---------- cele ----------

    private static long fishLife() {
        return 240;
    }

    private Node spawn(Session s, int type, P pos, boolean visible, long life) {
        NodeBackend.Spawned sp = backend.spawn(s.player, s.job.type(type), pos, visible);
        if (sp == null) return null;
        Node n = new Node(sp, type, pos, visible, clock, life == 0 ? 0 : life + rng.nextInt(100), rng.nextDouble() * Math.PI * 2);
        s.nodes.add(n);
        nodeOwner.put(sp.clickId(), s.player.getUniqueId());
        return n;
    }

    private void spawnStop(Session s) {
        Plan pl = s.route.get(s.collected);
        s.legStart = clock;
        spawn(s, pl.type(), pl.pos(), true, 0);
    }

    private void drop(Session s, Node n) {
        s.nodes.remove(n);
        nodeOwner.remove(n.spawned.clickId());
        backend.remove(n.spawned.handle());
    }

    /** Dodatkowa premia za szybką dostawę: tyle czasu, ile trzeba na dojście, plus zapas. */
    static double tipFactor(double legDistance, long elapsedTicks) {
        double allowed = legDistance / 4.3 * 20 + 8 * 20;
        if (elapsedTicks <= allowed) return 0.25;
        if (elapsedTicks <= allowed * 1.6) return 0.10;
        return 0;
    }

    // ---------- kliknięcia ----------

    /** Kliknięcie gracza w pole kliknięcia. Zwraca true, gdy to był cel pracy (kliknięcie wtedy nie robi nic innego). */
    public boolean click(Player p, UUID entity) {
        UUID owner = nodeOwner.get(entity);
        if (owner == null) return false;
        if (!owner.equals(p.getUniqueId())) return true;
        Session s = sessions.get(owner);
        if (s == null) return true;
        Node node = null;
        for (Node n : s.nodes) {
            if (n.spawned.clickId().equals(entity)) {
                node = n;
                break;
            }
        }
        if (node == null || !node.visible) return true;
        P me = at(p.getLocation());
        if (me.flat(node.pos) > REACH || Math.abs(me.y() - node.pos.y()) > REACH) {
            Fx.deny(p);
            Fx.actionBar(p, "<red>Za daleko, podejdź bliżej.");
            return true;
        }
        collect(s, node);
        return true;
    }

    private void collect(Session s, Node n) {
        Player p = s.player;
        UUID id = p.getUniqueId();
        Eco eco = plugin.eco();
        NodeType nt = s.job.type(n.type);
        long base = eco.randLong(nt.min(), nt.max());
        double tip = 0;
        if (s.job.kind() == Kind.ROUTE) tip = tipFactor(s.legFrom.flat(n.pos), clock - s.legStart);
        long amount = eco.applyEarn(id, Math.round(base * (1 + tip))).amount();
        eco.add(id, amount);
        s.earned += amount;
        s.collected++;
        Eco.LevelUp lv = eco.addXp(id, nt.rare() ? 12 : 5);
        P where = n.pos;
        drop(s, n);

        String line = "<white>" + T.esc(nt.name()) + "<newline><gold><bold>+" + Fmt.money(amount) + (tip > 0 ? "<newline><green>napiwek +" + Math.round(tip * 100) + "%" : "");
        backend.popup(p, line, where);
        backend.sparkle(p, where, nt.rare());
        if (nt.rare()) Fx.win(p);
        else Fx.coin(p);
        if (lv != null && lv.leveled()) Notify.levelUp(p, lv);
        if (s.job.kind() == Kind.HUNT && eco.chance(TREASURE_CRATE)) {
            eco.addItem(id, "skrzynia", 1);
            T.send(p, "<aqua>W skarbcu leżała też skrzynia! <gray>Znajdziesz ją w ekwipunku.");
        }

        if (s.collected >= s.goal) {
            end(s, End.DONE);
            return;
        }
        switch (s.job.kind()) {
            case ROUTE -> {
                s.legFrom = where;
                spawnStop(s);
            }
            case WATER -> {
                while (s.goal - s.collected > s.nodes.size() && refill(s)) {
                    // uzupełniamy ryby, dopóki do kompletu brakuje więcej, niż pływa
                }
            }
            default -> {
            }
        }
        Fx.actionBar(p, "<green>" + T.esc(nt.name()) + " <gold>+" + Fmt.money(amount) + " <gray>• <white>" + s.collected + "/" + s.goal);
    }

    /** Dokłada jedną rybę w wolnym miejscu. False, gdy nie ma gdzie albo za dużo już było. */
    private boolean refill(Session s) {
        if (s.respawns >= s.job.quota() * 3 || s.deadline - clock < 160) return false;
        List<P> avoid = new ArrayList<>();
        for (Node n : s.nodes) avoid.add(n.pos);
        var spot = JobPlanner.spot(s.job, s.terrain, s.site.center(), s.site.radius(), rng, avoid);
        if (spot.isEmpty()) return false;
        s.respawns++;
        return spawn(s, JobPlanner.pickType(s.job, s.luck, rng), spot.get(), true, fishLife()) != null;
    }

    // ---------- pętla ----------

    /** Jeden krok pętli (co STEP ticków): ruch ryb, odkrywanie skarbu, efekty, pasek akcji i warunki końca. */
    public void tick() {
        clock += STEP;
        for (Session s : new ArrayList<>(sessions.values())) {
            try {
                step(s);
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Błąd pracy " + s.job.id() + ": " + e);
                e.printStackTrace();
                end(s, End.SHUTDOWN);
            }
        }
    }

    private void step(Session s) {
        Player p = s.player;
        if (!p.isOnline()) {
            end(s, End.QUIT);
            return;
        }
        if (!p.getWorld().equals(s.world)) {
            end(s, End.LEASH);
            return;
        }
        P me = at(p.getLocation());
        if (me.flat(s.site.center()) > s.site.radius() * 1.4 + 30) {
            end(s, End.LEASH);
            return;
        }
        if (clock >= s.deadline) {
            end(s, End.TIMEOUT);
            return;
        }
        long left = s.deadline - clock;
        if (!s.warned && left <= 400 && s.job.seconds() * 20L > 800) {
            s.warned = true;
            Fx.title(p, "<red><bold>Zostało 20 s!", "<gray>" + s.collected + "/" + s.goal);
            Fx.ding(p, 0.8f);
        }
        if (s.job.kind() == Kind.WATER) swim(s);
        if (s.job.kind() == Kind.HUNT) hunt(s, me);

        int phase = (int) (clock / STEP);
        for (Node n : s.nodes) {
            if (!n.visible) continue;
            backend.spin(n.spawned.handle(), phase);
            boolean rare = s.job.type(n.type).rare();
            if (phase % (rare ? 2 : 4) == 0 && me.flat(n.pos) < 48) backend.sparkle(p, n.pos, rare);
        }
        Fx.actionBar(p, bar(s, me, left));
    }

    private void swim(Session s) {
        for (Node n : new ArrayList<>(s.nodes)) {
            if (n.life > 0 && clock - n.born >= n.life) {
                drop(s, n);
                if (s.goal - s.collected > s.nodes.size()) refill(s);
                continue;
            }
            n.heading += (rng.nextDouble() - 0.5) * 1.2;
            double nx = n.pos.x() + Math.cos(n.heading) * 0.45;
            double nz = n.pos.z() + Math.sin(n.heading) * 0.45;
            P water = s.terrain.water(nx, nz);
            if (water != null && water.flat(s.site.center()) <= s.site.radius()) {
                n.pos = new P(nx, water.y(), nz);
                backend.move(n.spawned.handle(), n.pos);
            } else {
                n.heading += Math.PI * (0.5 + rng.nextDouble());
            }
        }
    }

    private void hunt(Session s, P me) {
        for (Node n : s.nodes) {
            if (!n.visible && me.flat(n.pos) <= 12 && Math.abs(me.y() - n.pos.y()) <= 12) {
                n.visible = true;
                backend.reveal(s.player, n.spawned.handle());
                Fx.title(s.player, "<gold><bold>Skarb błyszczy!", "<gray>Podejdź i kliknij skrzynię");
                Fx.ding(s.player, 1.6f);
            }
        }
    }

    private String bar(Session s, P me, long left) {
        String head = "<yellow><bold>" + T.esc(s.job.title()) + "</bold> <dark_gray>• <white>" + s.collected + "<gray>/" + s.goal
                + " <dark_gray>• <gold>" + Fmt.money(s.earned) + " <dark_gray>• <white>" + Compass.clock(left) + " <dark_gray>• ";
        if (s.job.kind() == Kind.HUNT) {
            if (s.nodes.isEmpty()) return head + "<gray>…";
            double d = me.flat(s.nodes.get(0).pos);
            if (s.lastDistance >= 0) {
                if (d < s.lastDistance - 0.3) s.trend = " <green>(cieplej)";
                else if (d > s.lastDistance + 0.3) s.trend = " <red>(zimniej)";
            }
            s.lastDistance = d;
            return head + Compass.warmthLabel(Compass.warmth(d)) + s.trend;
        }
        Node nearest = null;
        double best = Double.MAX_VALUE;
        for (Node n : s.nodes) {
            if (!n.visible) continue;
            double d = me.flat(n.pos);
            if (d < best) {
                best = d;
                nearest = n;
            }
        }
        if (nearest == null) return head + "<gray>szukam…";
        String arrow = Compass.arrow(s.player.getLocation().getYaw(), nearest.pos.x() - me.x(), nearest.pos.z() - me.z());
        return head + "<green>" + arrow + " " + Math.round(best) + " m";
    }
}
