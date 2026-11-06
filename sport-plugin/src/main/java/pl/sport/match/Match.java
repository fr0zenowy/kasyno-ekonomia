package pl.sport.match;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import pl.sport.SportPlugin;
import pl.sport.arena.Arena;
import pl.sport.config.SportConfig;
import pl.sport.core.BallBody;
import pl.sport.core.BallSpec;
import pl.sport.core.PhysicsWorld;
import pl.sport.core.Team;
import pl.sport.core.V3;
import pl.sport.ui.Hud;
import pl.sport.ui.Msg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Wspólny szkielet meczu: lobby i odliczanie, dołączanie i wychodzenie graczy (z przywracaniem ich stanu), piłka i jej widok,
 * tablica wyników oraz końcówka meczu. Dyscypliny (siatkówka, koszykówka) dopisują zasady, ruch i sterowanie.
 */
public abstract class Match {
    public enum Stage { LOBBY, COUNTDOWN, PLAYING, ENDING }

    /** Czas, przez który po zakończeniu meczu gracze zostają na boisku przed powrotem. */
    protected static final double ENDING_SECONDS = 7;

    protected final SportPlugin plugin;
    protected final Arena arena;
    protected final World world;
    protected final Random rng = new Random();
    protected final Map<UUID, Participant> players = new LinkedHashMap<>();
    protected Stage stage = Stage.LOBBY;
    protected double countdownLeft;
    protected double endingLeft;
    private int lastCountdownShown = -1;

    protected BallBody ball;
    protected BallView view;
    protected PhysicsWorld physics;
    protected Hud hud;

    protected Match(SportPlugin plugin, Arena arena) {
        this.plugin = plugin;
        this.arena = arena;
        this.world = plugin.getServer().getWorld(arena.worldName());
    }

    // ---------- opis dyscypliny ----------

    public Arena arena() {
        return arena;
    }

    /** Czy świat boiska nie był załadowany przy tworzeniu meczu (wtedy mecz trzeba utworzyć od nowa). */
    public boolean worldMissing() {
        return world == null;
    }

    public Stage stage() {
        return stage;
    }

    protected SportConfig cfg() {
        return plugin.sportConfig();
    }

    protected abstract String sportLabel();

    protected abstract int maxPerTeam();

    protected abstract BallSpec ballSpec();

    protected abstract BallKind ballKind();

    /** Wywoływane raz po odliczaniu: rozstawia graczy i zaczyna grę. */
    protected abstract void onStart();

    /** Co tick w trakcie gry (dt w sekundach). */
    protected abstract void onTick(double dt);

    /** Gracz wyszedł albo rozłączył się w trakcie gry. */
    protected abstract void onPlayerLeft(Participant p);

    /** Przywraca stan początkowy po meczu (zerowanie punktów, usunięcie piłki). */
    protected abstract void onReset();

    /** Linie tablicy wyników i tytuł (od góry do dołu). */
    protected abstract List<String> scoreboardLines();

    /** Aktualne linie tablicy wyników (do testów i podglądu). */
    public final List<String> scoreboard() {
        return scoreboardLines();
    }

    public abstract void onSwing(Player p);

    /**
     * Czy gracz może przejść z `from` do `to` (położenia w świecie). Mecz może zabronić np. wejścia na połowę rywali. Po zakończeniu
     * meczu ruch jest wolny.
     */
    public final boolean allowMove(Participant part, Location from, Location to) {
        if (stage == Stage.ENDING) return true;
        return canMove(part, local(from), local(to));
    }

    /** Komunikat dla gracza, któremu zabroniono ruchu (MiniMessage). */
    public String moveBlockedMessage() {
        return "<red>Nie możesz wejść na połowę rywali.";
    }

    /** Ograniczenia ruchu danej dyscypliny (układ lokalny boiska); domyślnie brak. */
    protected boolean canMove(Participant part, V3 from, V3 to) {
        return true;
    }

    /** Klawisz zamiany rąk (F). Zwraca true, gdy mecz go obsłużył i zdarzenie trzeba anulować. */
    public boolean onSwap(Player p) {
        return false;
    }

    /** Ustawia ziarno losowania (kto zaczyna, rozrzut uderzeń). Używane w testach, żeby przebieg meczu był powtarzalny. */
    public void seed(long seed) {
        rng.setSeed(seed);
    }

    // ---------- gracze ----------

    public Map<UUID, Participant> players() {
        return players;
    }

    public Participant participant(UUID id) {
        return players.get(id);
    }

    public List<Participant> team(Team t) {
        List<Participant> out = new ArrayList<>();
        for (Participant p : players.values()) if (p.team() == t) out.add(p);
        return out;
    }

    public int teamSize(Team t) {
        int n = 0;
        for (Participant p : players.values()) if (p.team() == t) n++;
        return n;
    }

    public static String teamName(Team t) {
        return t == Team.A ? "Czerwoni" : "Niebiescy";
    }

    public static String teamColor(Team t) {
        return t == Team.A ? "<red>" : "<aqua>";
    }

    protected String coloredTeam(Team t) {
        return teamColor(t) + "<bold>" + teamName(t) + "</bold><gray>";
    }

    /** Dołącza gracza. Zwraca komunikat błędu albo null, gdy się udało. */
    public String join(Player p, Team wanted) {
        if (stage == Stage.PLAYING || stage == Stage.ENDING) return "Mecz już trwa. Poczekaj na koniec albo wybierz inne boisko.";
        if (players.containsKey(p.getUniqueId())) return "Już jesteś w tym meczu.";
        if (world == null) return "Świat tego boiska nie jest załadowany.";
        Team team = wanted;
        if (team == null) team = teamSize(Team.A) <= teamSize(Team.B) ? Team.A : Team.B;
        if (teamSize(team) >= maxPerTeam()) return "Drużyna " + teamName(team) + " jest pełna (" + maxPerTeam() + " graczy).";

        PlayerSnapshot snap = PlayerSnapshot.of(p);
        plugin.snapshots().put(p.getUniqueId(), snap);
        Participant part = new Participant(p, team, snap);
        players.put(p.getUniqueId(), part);
        plugin.matches().bind(p.getUniqueId(), this);
        if (hud == null) hud = new Hud(plugin.getLogger(), "<yellow>" + sportLabel());
        p.setGameMode(GameMode.ADVENTURE);
        hud.show(p);
        placeInLobby(part);
        broadcast(teamColor(team) + Msg.esc(p.getName()) + "<gray> dołączył do drużyny " + coloredTeam(team) + " <dark_gray>(" + teamSize(Team.A) + " : " + teamSize(Team.B) + ")");
        refreshLobby();
        return null;
    }

    /** Ustawia gracza na jego połowie boiska w poczekalni. */
    protected abstract void placeInLobby(Participant p);

    /** Usuwa gracza z meczu i oddaje mu poprzedni stan. */
    public void leave(UUID id, boolean teleportBack) {
        Participant part = players.remove(id);
        if (part == null) return;
        plugin.matches().unbind(id);
        Player p = part.player();
        if (p != null) {
            if (hud != null) hud.hide(p);
            part.snapshot().restore(p, teleportBack);
            Msg.send(p, "Opuściłeś mecz.");
        }
        plugin.snapshots().remove(id);
        broadcast(teamColor(part.team()) + Msg.esc(part.name()) + "<gray> opuścił mecz.");
        if (stage == Stage.PLAYING) onPlayerLeft(part);
        if (stage == Stage.COUNTDOWN && !canStart()) {
            stage = Stage.LOBBY;
            lastCountdownShown = -1;
            broadcast("<yellow>Odliczanie przerwane: za mało graczy.");
        }
        if (players.isEmpty()) reset();
        else refreshLobby();
    }

    /** Gdy wszyscy gracze jednej drużyny wyszli w trakcie gry. */
    protected boolean teamEmpty() {
        return teamSize(Team.A) == 0 || teamSize(Team.B) == 0;
    }

    // ---------- przebieg ----------

    public boolean canStart() {
        int min = cfg().minPlayersPerTeam();
        return teamSize(Team.A) >= min && teamSize(Team.B) >= min;
    }

    /** Zaczyna odliczanie (z komendy albo automatycznie, gdy są obie drużyny). Zwraca komunikat błędu albo null. */
    public String startCountdown() {
        if (stage != Stage.LOBBY) return stage == Stage.COUNTDOWN ? "Odliczanie już trwa." : "Mecz już trwa.";
        if (!canStart()) return "Potrzeba co najmniej " + cfg().minPlayersPerTeam() + " gracza w każdej drużynie (jest " + teamSize(Team.A) + " : " + teamSize(Team.B) + ").";
        stage = Stage.COUNTDOWN;
        countdownLeft = cfg().countdownSeconds();
        lastCountdownShown = -1;
        broadcast("<green>Mecz zaczyna się za <white>" + cfg().countdownSeconds() + "</white> s!");
        return null;
    }

    /** Wymusza natychmiastowy start (admin). */
    public String forceStart() {
        if (stage == Stage.PLAYING || stage == Stage.ENDING) return "Mecz już trwa.";
        if (!canStart()) return "Potrzeba co najmniej " + cfg().minPlayersPerTeam() + " gracza w każdej drużynie (jest " + teamSize(Team.A) + " : " + teamSize(Team.B) + ").";
        begin();
        return null;
    }

    private void begin() {
        stage = Stage.PLAYING;
        broadcastTitle("<green><bold>START!", "<gray>" + sportLabel());
        onStart();
        refreshHud();
    }

    /** Przerywa mecz bez zwycięzcy i odsyła wszystkich. */
    public void stop(String reason) {
        if (!players.isEmpty()) broadcast("<red>" + reason);
        reset();
    }

    /** Zwraca wszystkich graczy, sprząta piłkę i przywraca stan początkowy. */
    public void reset() {
        for (Participant part : new ArrayList<>(players.values())) {
            Player p = part.player();
            plugin.matches().unbind(part.id());
            plugin.snapshots().remove(part.id());
            if (p != null) {
                if (hud != null) hud.hide(p);
                part.snapshot().restore(p, true);
            }
        }
        players.clear();
        removeBall();
        if (hud != null) {
            hud.hideAll();
            hud = null;
        }
        stage = Stage.LOBBY;
        lastCountdownShown = -1;
        onReset();
    }

    /** Wywoływane co tick (dt w sekundach). */
    public final void tick(double dt) {
        ticks++;
        switch (stage) {
            case LOBBY -> {
                if (!players.isEmpty() && canStart() && cfg().flag("wspolne.autostart", true)) startCountdown();
            }
            case COUNTDOWN -> {
                if (!canStart()) {
                    stage = Stage.LOBBY;
                    return;
                }
                countdownLeft -= dt;
                int sec = (int) Math.ceil(countdownLeft);
                if (sec != lastCountdownShown && sec > 0) {
                    lastCountdownShown = sec;
                    if (sec <= 5) {
                        broadcastTitle("<yellow><bold>" + sec, "<gray>Przygotuj się");
                        for (Participant part : players.values()) {
                            Player p = part.player();
                            if (p != null) Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.2f);
                        }
                    }
                    refreshHud();
                }
                if (countdownLeft <= 0) begin();
            }
            case PLAYING -> {
                trackMotion(dt);
                onTick(dt);
                if (stage == Stage.PLAYING && ++hudTicks >= 10) {
                    hudTicks = 0;
                    refreshHud();
                }
            }
            case ENDING -> {
                endingLeft -= dt;
                onEndingTick(dt);
                if (endingLeft <= 0) reset();
            }
        }
    }

    private int hudTicks;
    private long ticks;

    /** Zegar meczu w nanosekundach liczony tickami, a nie czasem ściany: odstępy między uderzeniami nie skracają się, gdy serwer przycina. */
    protected final long now() {
        return ticks * 50_000_000L;
    }

    /** Ruch piłki podczas końcówki (np. toczenie się po koszu); domyślnie nic. */
    protected void onEndingTick(double dt) {}

    /** Wchodzi w fazę końcową: gracze jeszcze chwilę są na boisku, potem wracają. */
    protected void beginEnding() {
        stage = Stage.ENDING;
        endingLeft = ENDING_SECONDS;
    }

    // ---------- tablica i komunikaty ----------

    protected void refreshLobby() {
        refreshHud();
    }

    protected void refreshHud() {
        if (hud == null) return;
        hud.render("<gold><bold>" + sportLabel(), scoreboardLines());
    }

    public void broadcast(String mm) {
        Component c = Msg.c(Msg.PREFIX + mm);
        for (Participant part : players.values()) {
            Player p = part.player();
            if (p != null) p.sendMessage(c);
        }
    }

    protected void broadcastTitle(String title, String subtitle) {
        for (Participant part : players.values()) {
            Player p = part.player();
            if (p != null) Msg.title(p, title, subtitle, 0.1, 1.2, 0.3);
        }
    }

    protected void broadcastSound(Sound s, float vol, float pitch) {
        for (Participant part : players.values()) {
            Player p = part.player();
            if (p != null) Msg.sound(p, s, vol, pitch);
        }
    }

    // ---------- układ współrzędnych i teleporty ----------

    protected V3 local(Location l) {
        return arena.toLocal(l);
    }

    protected Location worldAt(V3 local) {
        return arena.toWorld(world, local);
    }

    /** Miejsce w świecie z kierunkiem patrzenia w lokalny kierunek `face`. */
    protected Location spot(V3 local, V3 face) {
        Location l = worldAt(local);
        l.setYaw(arena.yawOf(face));
        l.setPitch(0);
        return l;
    }

    /** Liczy prędkość i skok graczy z różnic położenia między tickami. */
    protected void trackMotion(double dt) {
        for (Participant part : players.values()) {
            Player p = part.player();
            if (p == null) continue;
            V3 lp = arena.toLocal(p.getLocation());
            if (part.motionInit) {
                part.vy = (lp.y() - part.y) / dt;
                part.vx = (lp.x() - part.px) / dt;
                part.vz = (lp.z() - part.pz) / dt;
            }
            part.motionInit = true;
            part.y = lp.y();
            part.px = lp.x();
            part.pz = lp.z();
            part.air = !p.isOnGround() && lp.y() > 0.3;
        }
    }

    protected void teleport(Participant part, Location to) {
        Player p = part.player();
        if (p == null) return;
        part.expectTeleport(to);
        part.motionInit = false;
        p.teleport(to);
        p.setFallDistance(0);
    }

    // ---------- piłka ----------

    protected void spawnBall(V3 pos) {
        removeBall();
        ball = new BallBody(pos, V3.ZERO);
        Location l = worldAt(pos);
        view = plugin.ballFactory().create(l, ballKind(), ballSpec().radius());
    }

    protected void removeBall() {
        if (view != null) view.remove();
        view = null;
        ball = null;
    }

    /** Wysokość powierzchni pod piłką (układ lokalny): podłoga albo np. blat stołu. Na niej leży cień piłki. */
    protected double supportY(V3 ballPos) {
        return 0;
    }

    /** Przesuwa widok piłki do jej bieżącego położenia fizycznego. */
    protected void syncView(double dt) {
        if (view == null || ball == null) return;
        view.move(worldAt(ball.pos()), arena.originY() + supportY(ball.pos()));
        double speed = ball.vel().horizontalLength();
        if (speed > 0.05) {
            V3 w = arena.dirToWorld(ball.vel());
            double deg = Math.toDegrees(speed / ballSpec().radius()) * dt;
            view.spin(w.x(), w.z(), deg);
        }
    }

    private double stuck;

    /**
     * Piłka, która leży nieruchomo w powietrzu (na górnej krawędzi tablicy, na siatce albo na obręczy), zablokowałaby grę, bo nikt
     * po nią nie sięgnie. Po 1,5 s dostaje lekkie szturchnięcie w stronę środka boiska.
     */
    protected void unstick(double dt) {
        if (ball == null) return;
        boolean still = ball.vel().length() < 0.25 && ball.pos().y() > ballSpec().radius() + 0.25;
        stuck = still ? stuck + dt : 0;
        if (stuck <= 1.5) return;
        stuck = 0;
        V3 toCenter = new V3(arena.length() / 2.0 - ball.pos().x(), 0, arena.width() / 2.0 - ball.pos().z()).normalized();
        double jitter = (rng.nextDouble() - 0.5) * 0.8;
        ball.setVel(new V3(toCenter.x() * 1.4 + jitter, 1.0, toCenter.z() * 1.4 + jitter));
    }

    protected void ballSound(Sound s, float vol, float pitch) {
        if (ball == null) return;
        world.playSound(worldAt(ball.pos()), s, vol, pitch);
    }

    protected Player onlinePlayer(UUID id) {
        return Bukkit.getPlayer(id);
    }
}
