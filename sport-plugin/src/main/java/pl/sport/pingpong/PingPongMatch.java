package pl.sport.pingpong;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import pl.sport.SportPlugin;
import pl.sport.arena.Arena;
import pl.sport.core.BallSpec;
import pl.sport.core.Contact;
import pl.sport.core.Team;
import pl.sport.core.V3;
import pl.sport.match.BallKind;
import pl.sport.match.Match;
import pl.sport.match.Participant;
import pl.sport.pingpong.PingPongGame.PointResult;
import pl.sport.pingpong.PingPongGame.Reason;
import pl.sport.pingpong.PingPongHitModel.Stroke;
import pl.sport.ui.Msg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Mecz ping-ponga przy stole: ustawia graczy za końcami stołu, serwuje, prowadzi piłeczkę fizyką (blat, siatka, podłoga) i zamienia kliknięcia
 * na uderzenia. Zasady (serwis, odbicia od stołu, błędy, gemy) siedzą w {@link PingPongGame}, a tor lotu po uderzeniu w {@link PingPongHitModel}.
 */
public final class PingPongMatch extends Match {
    private enum Sub { PREP, SERVE, RALLY, POINT, GAME_BREAK }

    private static final long SECOND = 1_000_000_000L;
    /** Serwis uderzany z piłeczki co najmniej tyle nad blatem (podrzut musi być wyraźny). */
    private static final double MIN_SERVE_HEIGHT = 0.22;

    private final PingPongTable table;
    private final BallSpec spec;
    private PingPongGame game;
    private final Map<Team, List<UUID>> order = new EnumMap<>(Team.class);
    private final List<String> gameScores = new ArrayList<>();
    private Sub sub = Sub.PREP;
    private double subTimer;
    private UUID serverId;
    private boolean tossed;
    private double sinceToss;
    private double restTimer;
    private long lastTouchNanos = -1_000_000_000_000L;
    private int barTicks;

    public PingPongMatch(SportPlugin plugin, Arena arena) {
        super(plugin, arena);
        this.table = new PingPongTable(arena.length(), arena.width(), PingPongTable.scaleOfNet(arena.netHeight()));
        this.spec = BallSpec.PINGPONG.withGravity(plugin.sportConfig().pingpongGravity());
    }

    public PingPongGame game() {
        return game;
    }

    public PingPongTable table() {
        return table;
    }

    /** Gracz, który teraz serwuje (null poza serwisem). */
    public UUID serverId() {
        return sub == Sub.SERVE ? serverId : null;
    }

    /** Czy piłeczka została już podrzucona do serwisu. */
    public boolean tossed() {
        return tossed;
    }

    /** Czy akcja trwa (piłeczka w grze po serwisie). */
    public boolean rallyOn() {
        return sub == Sub.RALLY;
    }

    /** Czy trwa serwis (czekamy na podrzut i uderzenie). */
    public boolean serving() {
        return sub == Sub.SERVE;
    }

    public V3 ballPosition() {
        return ball == null ? null : ball.pos();
    }

    public V3 ballVelocity() {
        return ball == null ? null : ball.vel();
    }

    /** Ustawia piłeczkę w danym miejscu (do testów i narzędzi administratora). */
    public void teleportBall(V3 pos, V3 vel) {
        if (ball != null) ball.set(pos, vel);
    }

    @Override
    protected String sportLabel() {
        return "Ping-pong";
    }

    @Override
    protected int maxPerTeam() {
        return cfg().pingpongMaxPerTeam();
    }

    @Override
    protected BallSpec ballSpec() {
        return spec;
    }

    @Override
    protected BallKind ballKind() {
        return BallKind.PINGPONG;
    }

    @Override
    protected double supportY(V3 p) {
        return table.overTable(p, 0) && p.y() >= table.topY() - 0.02 ? table.topY() : 0;
    }

    @Override
    public String moveBlockedMessage() {
        return "<red>Tu nie wejdziesz: stół i połowa rywala są zablokowane.";
    }

    // ---------- ustawienie graczy ----------

    private boolean lowSideOf(Team t) {
        if (game == null) return t == Team.A;
        return game.sideOwner(true) == t;
    }

    private static V3 facing(boolean lowSide) {
        return new V3(lowSide ? 1 : -1, 0, 0);
    }

    private V3 teamSpot(boolean lowSide, int index, int count) {
        double u = lowSide ? table.u0() - 1.3 : table.u1() + 1.3;
        double v = arena.width() * (index + 1.0) / (count + 1.0);
        return new V3(u, 0, v);
    }

    /** Ustawia całą drużynę w poczekalni na nowo (po dołączeniu gracza koledzy rozsuwają się, żeby nie stali na sobie). */
    @Override
    protected void placeInLobby(Participant part) {
        List<Participant> mates = team(part.team());
        boolean low = lowSideOf(part.team());
        for (int i = 0; i < mates.size(); i++) teleport(mates.get(i), spot(teamSpot(low, i, mates.size()), facing(low)));
    }

    private void placeTeams() {
        for (Team t : Team.values()) {
            List<UUID> list = order.getOrDefault(t, List.of());
            boolean low = lowSideOf(t);
            for (int i = 0; i < list.size(); i++) {
                Participant part = players.get(list.get(i));
                if (part != null) teleport(part, spot(teamSpot(low, i, list.size()), facing(low)));
            }
        }
    }

    /** Gracz zostaje na swojej stronie siatki i nie wchodzi w stół (stół nie ma kolizji, więc pilnuje tego ruch). */
    @Override
    protected boolean canMove(Participant part, V3 from, V3 to) {
        boolean low = lowSideOf(part.team());
        double net = table.netU();
        boolean ownSide = low ? to.x() < net - 0.2 : to.x() > net + 0.2;
        if (!ownSide) {
            boolean towardsOwn = low ? to.x() < from.x() : to.x() > from.x();
            if (!towardsOwn) return false;
        }
        return !(table.insideTableFootprint(to) && !table.insideTableFootprint(from));
    }

    // ---------- start i koniec ----------

    @Override
    protected void onStart() {
        Team first = rng.nextBoolean() ? Team.A : Team.B;
        game = new PingPongGame(new PingPongGame.Config(cfg().pingpongGamesToWin(), cfg().pingpongPointsPerGame(), 2, cfg().pingpongVolleyFault()), first);
        for (Team t : Team.values()) game.setTeamSize(t, teamSize(t));
        physics = table.physics(spec);
        order.clear();
        for (Team t : Team.values()) {
            List<UUID> ids = new ArrayList<>();
            for (Participant p : team(t)) ids.add(p.id());
            order.put(t, ids);
        }
        gameScores.clear();
        placeTeams();
        broadcast("<green>Zaczyna serwisem: " + coloredTeam(first) + "<green>. Gem do " + game.pointsTarget() + " punktów z przewagą 2, wygrywa pierwsza drużyna z " + game.config().gamesToWin() + " wygranymi gemami.");
        startPrep(2.5);
    }

    @Override
    protected void onReset() {
        game = null;
        physics = null;
        order.clear();
        gameScores.clear();
        sub = Sub.PREP;
        tossed = false;
        serverId = null;
        restTimer = 0;
        buffered.clear();
    }

    @Override
    protected void onPlayerLeft(Participant part) {
        List<UUID> list = order.get(part.team());
        if (list != null) list.remove(part.id());
        if (game == null || game.finished()) return;
        game.setTeamSize(part.team(), teamSize(part.team()));
        if (teamSize(part.team()) == 0) {
            broadcast(coloredTeam(part.team()) + " <red>nie ma już graczy, mecz kończy się walkowerem.");
            finishMatch(part.team().other(), true);
            return;
        }
        if (sub == Sub.SERVE && part.id().equals(serverId)) beginServe();
    }

    private void finishMatch(Team winner, boolean forfeit) {
        game.abort();
        String score = game.games(Team.A) + " : " + game.games(Team.B);
        String detail = gameScores.isEmpty() ? "" : " <dark_gray>(" + String.join(", ", gameScores) + ")";
        for (Participant part : players.values()) {
            Player p = part.player();
            if (p == null) continue;
            boolean won = part.team() == winner;
            Msg.title(p, won ? "<green><bold>ZWYCIĘSTWO!" : "<red><bold>PORAŻKA", teamColor(winner) + teamName(winner) + "<gray> wygrywają " + score, 0.2, 4, 1);
            Msg.sound(p, won ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
        broadcast("<gold><bold>Koniec meczu!</bold> " + coloredTeam(winner) + " <gold>wygrywają " + score + "</gold>" + detail + (forfeit ? " <gray>(walkower)" : ""));
        refreshHud();
        beginEnding();
    }

    // ---------- przebieg akcji ----------

    private void startPrep(double seconds) {
        sub = Sub.PREP;
        subTimer = seconds;
    }

    private void beginServe() {
        game.beginRally();
        Team st = game.serving();
        List<UUID> list = order.get(st);
        serverId = list.get(0);
        Participant sv = players.get(serverId);
        boolean low = lowSideOf(st);
        if (sv != null) teleport(sv, spot(table.serveSpot(low, 0), facing(low)));
        tossed = false;
        sinceToss = 0;
        restTimer = 0;
        sub = Sub.SERVE;
        subTimer = cfg().pingpongServeSeconds();
        Player p = sv == null ? null : sv.player();
        V3 start = p == null ? table.serveSpot(low, 0).plus(new V3(0, Math.max(1.1, table.topY() + 0.1), 0)) : handPos(p);
        if (ball == null) spawnBall(start);
        else ball.set(start, V3.ZERO);
        if (p != null) {
            Msg.title(p, "<yellow><bold>Twój serwis!", "<gray>LPM podrzuca piłeczkę, drugie LPM ją uderza (zza linii końcowej stołu)", 0.1, 2.5, 0.3);
            Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
        }
        broadcast(coloredTeam(st) + " <gray>serwują: " + teamColor(st) + (sv == null ? "?" : Msg.esc(sv.name())));
        refreshHud();
    }

    /** Dłoń zagrywającego: tuż przed ciałem na wysokości pasa. */
    private V3 handPos(Player p) {
        V3 feet = local(p.getLocation());
        V3 fwd = arena.dirToLocal(vec(p.getLocation().getDirection())).horizontal().normalized();
        // dłoń na wysokości pasa, ale zawsze nad blatem, żeby piłeczka trzymana nad stołem nie tkwiła w blacie
        return feet.plus(new V3(0, Math.max(1.1, table.topY() + 0.1), 0)).plus(fwd.times(0.45));
    }

    private static V3 vec(Vector v) {
        return new V3(v.getX(), v.getY(), v.getZ());
    }

    @Override
    protected void onTick(double dt) {
        if (game == null) return;
        switch (sub) {
            case PREP -> {
                subTimer -= dt;
                if (subTimer <= 0 && !game.finished()) beginServe();
            }
            case SERVE -> tickServe(dt);
            case RALLY -> stepBall(dt);
            case POINT -> {
                stepBall(dt);
                subTimer -= dt;
                if (subTimer <= 0 && !game.finished()) startPrep(0.8);
            }
            case GAME_BREAK -> {
                stepBall(dt);
                subTimer -= dt;
                if (subTimer <= 0 && !game.finished()) startPrep(2.0);
            }
        }
        flushClicks();
        syncView(dt);
        if (++barTicks >= 5) {
            barTicks = 0;
            updateBar();
        }
    }

    @Override
    protected void onEndingTick(double dt) {
        if (ball != null && physics != null) {
            physics.step(ball, dt, null);
            syncView(dt);
        }
    }

    private void tickServe(double dt) {
        Player sv = serverId == null ? null : onlinePlayer(serverId);
        subTimer -= dt;
        if (subTimer <= 0) {
            apply(() -> game.serveTimeout(), ball == null ? null : ball.pos());
            return;
        }
        if (!tossed) {
            if (sv != null) ball.set(handPos(sv), V3.ZERO);
            return;
        }
        physics.step(ball, dt, this::onContact);
        sinceToss += dt;
        if (sub == Sub.SERVE && (ball.onFloor() || ball.pos().y() < table.topY() * 0.6 || sinceToss > 4.0)) {
            // piłeczka spadła nieuderzona: podrzut można powtórzyć, a czas na serwis dalej płynie
            if (sv != null) ball.set(handPos(sv), V3.ZERO);
            tossed = false;
            if (sv != null) Msg.actionBar(sv, "<gray>Piłeczka spadła. Podrzuć ją jeszcze raz (LPM).");
        }
    }

    private void updateBar() {
        if (hud == null || game == null) return;
        switch (sub) {
            case SERVE -> hud.bar("<yellow>Serwis: " + (tossed ? "piłeczka w górze, uderz!" : "podrzuć piłeczkę (LPM)"), tossed ? 1 : subTimer / cfg().pingpongServeSeconds(), BossBar.Color.YELLOW);
            case RALLY -> {
                Team last = game.lastHitTeam();
                String who = game.stage() == PingPongGame.Stage.GOOD && last != null ? teamColor(last.other()) + teamName(last.other()) + "<gray>: odbij!" : "<gray>Piłeczka w locie";
                hud.bar(who, 1, BossBar.Color.GREEN);
            }
            case POINT -> hud.bar("<gold>Punkt!", 1, BossBar.Color.RED);
            case GAME_BREAK -> hud.bar("<gold>Przerwa między gemami: zmiana stron", 1, BossBar.Color.PURPLE);
            default -> hud.bar("<gray>Przygotowanie", 1, BossBar.Color.WHITE);
        }
    }

    // ---------- piłeczka ----------

    private void stepBall(double dt) {
        if (ball == null || physics == null) return;
        physics.step(ball, dt, this::onContact);
        unstick(dt);
        if (sub != Sub.RALLY) return;
        V3 p = ball.pos();
        boolean far = p.x() < -6 || p.x() > arena.length() + 6 || Math.abs(p.z() - arena.width() / 2) > arena.width() / 2 + 6 || p.y() < -5;
        if (far || ball.onFloor()) {
            apply(() -> game.deadBall(), p);
            return;
        }
        // piłeczka, która leży na blacie bez ruchu, nie odbije się już ani nie zostanie uderzona
        boolean resting = ball.vel().length() < 0.4 && p.y() <= table.topY() + spec.radius() + 0.03 && table.overTable(p, 0);
        restTimer = resting ? restTimer + dt : 0;
        if (restTimer > 0.6) {
            restTimer = 0;
            apply(() -> game.deadBall(), p);
        }
    }

    private void onContact(Contact c, double impact, V3 pos) {
        if (game == null) return;
        float vol = (float) Math.min(1.0, 0.2 + impact / 8.0);
        Location l = worldAt(pos);
        switch (c.kind()) {
            case TABLE -> {
                boolean top = c.normal().y() > 0.5;
                world.playSound(l, Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, vol, top ? 1.9f : 1.2f);
                if (sub == Sub.RALLY) {
                    if (top) {
                        Team owner = game.sideOwner(table.lowSide(pos));
                        apply(() -> game.bounce(owner), pos);
                    } else {
                        apply(() -> game.deadBall(), pos);
                    }
                }
            }
            case NET -> {
                world.playSound(l, Sound.BLOCK_WOOL_HIT, SoundCategory.PLAYERS, vol, 1.4f);
                if (sub == Sub.RALLY) game.netTouch();
            }
            case FLOOR -> {
                world.playSound(l, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, SoundCategory.PLAYERS, vol * 0.5f, 1.8f);
                if (sub == Sub.RALLY) apply(() -> game.deadBall(), pos);
            }
            default -> { }
        }
    }

    // ---------- wynik akcji ----------

    /** Wykonuje zdarzenie silnika i obsługuje jego wynik (punkt, powtórka serwisu, koniec gemu albo meczu). */
    private void apply(Supplier<PointResult> action, V3 at) {
        Team before = game.serving();
        PointResult r = action.get();
        if (r == null) return;
        if (r.let()) {
            broadcast("<yellow>Siatka! <gray>Serwis jest powtarzany.");
            broadcastSound(Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.4f);
            startPrep(1.2);
            return;
        }
        afterResult(before, r, at);
    }

    private static String reasonText(Reason r) {
        return switch (r) {
            case NOT_RETURNED -> "Piłeczka nie została odbita.";
            case OUT -> "Piłeczka poza stołem.";
            case OWN_HALF -> "Piłeczka odbiła się od własnej połowy.";
            case VOLLEY -> "Odbicie przed odbiciem od stołu.";
            case DOUBLE_HIT -> "Podwójne odbicie.";
            case SERVE_FAULT -> "Błąd serwisu.";
            case SERVE_POSITION -> "Serwis spoza linii końcowej stołu.";
            case SERVE_TIMEOUT -> "Za długo z serwisem.";
        };
    }

    private void afterResult(Team servingBefore, PointResult r, V3 at) {
        if (game.serving() != servingBefore && !r.matchFinished()) {
            List<UUID> list = order.get(game.serving());
            if (list != null && list.size() > 1) Collections.rotate(list, -1);
        }
        if (at != null && world != null) {
            Location l = worldAt(at);
            world.spawnParticle(Particle.CLOUD, l.clone().add(0, 0.05, 0), 6, 0.1, 0.03, 0.1, 0.01);
        }
        String why = reasonText(r.reason());
        Team w = r.winner();
        broadcast(coloredTeam(w) + " <green>zdobywają punkt. <gray>" + why + " <white>" + r.pointsA() + ":" + r.pointsB()
                + (r.gameFinished() ? "" : " <dark_gray>(gemy " + game.games(Team.A) + ":" + game.games(Team.B) + ")"));
        broadcastSound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        for (Participant part : players.values()) {
            Player p = part.player();
            if (p != null) Msg.actionBar(p, teamColor(w) + "<bold>Punkt dla " + teamName(w) + "</bold> <gray>" + why + " <white>" + r.pointsA() + ":" + r.pointsB());
        }
        if (r.matchFinished()) {
            gameScores.add(r.pointsA() + ":" + r.pointsB());
            finishMatch(r.matchWinner(), false);
            return;
        }
        if (r.gameFinished()) {
            gameScores.add(r.pointsA() + ":" + r.pointsB());
            placeTeams(); // strony już się zmieniły: gracze od razu idą na swoje nowe końce stołu
            broadcastTitle(teamColor(w) + "<bold>Gem dla " + teamName(w), "<gray>" + r.pointsA() + ":" + r.pointsB() + " <dark_gray>• gemy " + game.games(Team.A) + ":" + game.games(Team.B));
            broadcast("<gold>Koniec gemu " + (game.gameNumber() - 1) + ": " + r.pointsA() + ":" + r.pointsB() + ". Zmiana stron!");
            sub = Sub.GAME_BREAK;
            subTimer = 6;
        } else {
            if (r.changeEnds()) {
                placeTeams();
                broadcast("<gold>5 punktów w gemie decydującym: zmiana stron!");
            }
            sub = Sub.POINT;
            subTimer = 2.2;
        }
        refreshHud();
    }

    // ---------- sterowanie ----------

    /** Wynik próby uderzenia piłeczki po kliknięciu. */
    private enum HitResult {
        HIT,
        /** Uderzenie było błędem (np. wolej, podwójne odbicie, serwis sprzed linii): punkt rozstrzygnięty. */
        FAULT,
        TOO_FAR,
        BEHIND,
        OTHER_SIDE,
        /** Serwis uderzany za nisko nad blatem. */
        TOO_LOW,
        /** Za wcześnie po poprzednim uderzeniu (podwójne kliknięcie): ignorowane po cichu. */
        TOO_SOON
    }

    /** Tyle czasu kliknięcie „czeka” na piłeczkę, która dopiero zbliża się do gracza. */
    private static final long CLICK_BUFFER = 250_000_000L;

    private record BufferedClick(long at, HitResult reason) {}

    private final Map<UUID, BufferedClick> buffered = new LinkedHashMap<>();

    private static boolean retryable(HitResult r) {
        return r == HitResult.TOO_FAR || r == HitResult.BEHIND || r == HitResult.OTHER_SIDE || r == HitResult.TOO_LOW;
    }

    private String missText(HitResult r) {
        return switch (r) {
            case TOO_FAR -> "<gray>Za daleko od piłeczki: podejdź bliżej (zasięg " + String.format("%.1f", cfg().pingpongReach()) + " m)";
            case BEHIND -> "<gray>Piłeczka jest za Tobą: obróć się w jej stronę";
            case OTHER_SIDE -> "<gray>Piłeczka jest po drugiej stronie siatki";
            case TOO_LOW -> "<gray>Za nisko: poczekaj, aż piłeczka wzniesie się nad stół";
            default -> "";
        };
    }

    @Override
    public void onSwing(Player p) {
        if (stage != Stage.PLAYING || game == null || ball == null) return;
        Participant part = players.get(p.getUniqueId());
        if (part == null) return;
        if (sub == Sub.SERVE) {
            if (!p.getUniqueId().equals(serverId)) return;
            if (!tossed) {
                toss(p);
                return;
            }
        } else if (sub != Sub.RALLY) {
            return;
        }
        HitResult r = tryHit(p, part);
        if (retryable(r)) {
            buffered.put(p.getUniqueId(), new BufferedClick(now(), r));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.4f, 1.8f);
        } else {
            buffered.remove(p.getUniqueId());
        }
    }

    /** Ponawia kliknięcia, które trafiły przed piłeczką, a po upływie okna mówi graczowi, dlaczego się nie udało. */
    private void flushClicks() {
        if (buffered.isEmpty()) return;
        long now = now();
        boolean live = sub == Sub.RALLY || (sub == Sub.SERVE && tossed);
        for (Map.Entry<UUID, BufferedClick> e : new ArrayList<>(buffered.entrySet())) {
            Participant part = players.get(e.getKey());
            Player p = part == null ? null : part.player();
            if (p == null || !live || game == null || ball == null) {
                buffered.remove(e.getKey());
                continue;
            }
            HitResult r = tryHit(p, part);
            if (!retryable(r)) {
                buffered.remove(e.getKey());
            } else if (now - e.getValue().at() > CLICK_BUFFER) {
                buffered.remove(e.getKey());
                Msg.actionBar(p, missText(r));
            } else {
                buffered.put(e.getKey(), new BufferedClick(e.getValue().at(), r));
            }
        }
    }

    private void toss(Player sv) {
        // podrzut prawie pionowo, na ok. 65 cm nad dłoń
        double vy = Math.sqrt(2 * spec.gravity() * 0.65);
        ball.setVel(new V3(0, vy, 0));
        tossed = true;
        sinceToss = 0;
        ballSound(Sound.ENTITY_SNOWBALL_THROW, 0.5f, 1.6f);
        Msg.actionBar(sv, "<yellow>Piłeczka w górze! <gray>Uderz LPM, gdy będzie nad stołem");
    }

    private HitResult tryHit(Player p, Participant part) {
        long now = now();
        if (now - part.lastHit < (long) (cfg().hitCooldownSeconds() * SECOND)) return HitResult.TOO_SOON;
        Location el = p.getEyeLocation();
        V3 eye = local(el);
        V3 look = arena.dirToLocal(vec(el.getDirection())).normalized();
        V3 b = ball.pos();
        // piłeczka na ekranie jest o ok. jeden tick za swoim położeniem w grze, więc zasięg liczymy też od miejsca, które gracz widzi
        V3 seen = b.minus(ball.vel().times(0.06));
        V3 to = b.minus(eye);
        V3 toSeen = seen.minus(eye);
        double dist = Math.min(to.length(), toSeen.length());
        if (toSeen.length() < to.length()) to = toSeen;
        if (dist > cfg().pingpongReach()) return HitResult.TOO_FAR;
        if (dist >= 1.6 && to.normalized().dot(look) <= -0.1) return HitResult.BEHIND;
        boolean playerLow = lowSideOf(part.team());
        if (sub == Sub.SERVE) return serveHit(p, part, eye, look, b, playerLow, now);
        // przez siatkę nie sięga się po piłeczkę rywala
        if (table.lowSide(b) != playerLow) return HitResult.OTHER_SIDE;
        // ponowne kliknięcie tuż po odbiciu przez drużynę nie robi nic zamiast psuć akcję podwójnym odbiciem
        if (game.lastHitTeam() == part.team() && now - lastTouchNanos < (long) (0.6 * SECOND)) return HitResult.TOO_SOON;

        Stroke stroke = classify(p, look, b, playerLow);
        double quality = quality(stroke, eye, look, b);
        PingPongHitModel.Hit h = PingPongHitModel.compute(table, spec, new PingPongHitModel.Request(stroke, b, eye, look, playerLow, quality, cfg().pingpongAccuracy()), rng);
        UUID id = part.id();
        Team team = part.team();
        Team before = game.serving();
        PointResult res = game.hit(team, id);
        ball.setVel(h.velocity());
        part.lastHit = now;
        lastTouchNanos = now;
        hitEffects(p, stroke, quality, b);
        if (res != null) afterResult(before, res, b);
        return res != null ? HitResult.FAULT : HitResult.HIT;
    }

    private HitResult serveHit(Player p, Participant part, V3 eye, V3 look, V3 b, boolean playerLow, long now) {
        if (b.y() < table.topY() + MIN_SERVE_HEIGHT) return HitResult.TOO_LOW;
        boolean behind = playerLow ? b.x() <= table.u0() + 0.03 : b.x() >= table.u1() - 0.03;
        double quality = quality(Stroke.SERVE, eye, look, b);
        PingPongHitModel.Hit h = PingPongHitModel.compute(table, spec, new PingPongHitModel.Request(Stroke.SERVE, b, eye, look, playerLow, quality, cfg().pingpongAccuracy()), rng);
        Team before = game.serving();
        PointResult res = game.serveHit(part.team(), part.id(), behind);
        ball.setVel(h.velocity());
        part.lastHit = now;
        lastTouchNanos = now;
        if (res == null) sub = Sub.RALLY;
        hitEffects(p, Stroke.SERVE, quality, b);
        if (res != null) afterResult(before, res, b);
        return res != null ? HitResult.FAULT : HitResult.HIT;
    }

    private Stroke classify(Player p, V3 look, V3 b, boolean playerLow) {
        if (p.isSneaking()) return Stroke.PUSH;
        double pitchDown = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -look.y()))));
        boolean high = b.y() > table.netTopY() + 0.18 && Math.abs(b.x() - table.netU()) < 2.4;
        return high && pitchDown >= 8 ? Stroke.SMASH : Stroke.DRIVE;
    }

    /** Jakość uderzenia 0-1: jak blisko idealnego punktu kontaktu (zależnego od rodzaju uderzenia) znajduje się piłeczka. */
    private double quality(Stroke stroke, V3 eye, V3 look, V3 ball) {
        V3 lh = look.horizontal().normalized();
        V3 ideal = switch (stroke) {
            case SERVE -> eye.plus(new V3(0, -0.15, 0)).plus(lh.times(0.4));
            case SMASH -> eye.plus(new V3(0, 0.1, 0)).plus(lh.times(0.5));
            case PUSH -> eye.plus(new V3(0, -0.75, 0)).plus(lh.times(0.7));
            case DRIVE -> eye.plus(new V3(0, -0.45, 0)).plus(lh.times(0.75));
        };
        double d = ball.distanceTo(ideal);
        return Math.max(0, Math.min(1, 1 - (d - 0.45) / 1.6));
    }

    private void hitEffects(Player p, Stroke stroke, double quality, V3 at) {
        Location l = worldAt(at);
        float pitch = switch (stroke) {
            case SMASH -> 0.8f;
            case PUSH -> 1.7f;
            case SERVE -> 1.5f;
            default -> 1.3f;
        };
        world.playSound(l, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, SoundCategory.PLAYERS, 0.8f, pitch);
        if (stroke == Stroke.SMASH) world.spawnParticle(Particle.CRIT, l, 8, 0.1, 0.1, 0.1, 0.2);
        int stars = (int) Math.round(quality * 5);
        Msg.actionBar(p, "<gold>" + stroke.label() + " <gray>" + "★".repeat(stars) + "<dark_gray>" + "★".repeat(5 - stars));
    }

    // ---------- tablica ----------

    @Override
    protected List<String> scoreboardLines() {
        List<String> l = new ArrayList<>();
        l.add("<dark_gray>" + Msg.esc(arena.name()));
        if (game == null) {
            l.add(" ");
            l.add(teamColor(Team.A) + teamName(Team.A) + "<gray>: <white>" + teamSize(Team.A) + " graczy");
            l.add(teamColor(Team.B) + teamName(Team.B) + "<gray>: <white>" + teamSize(Team.B) + " graczy");
            l.add("  ");
            l.add(stage == Stage.COUNTDOWN ? "<green>Start za " + (int) Math.ceil(countdownLeft) + " s" : "<yellow>Czekamy na graczy");
            return l;
        }
        l.add("<gray>Gem <white>" + game.gameNumber() + (game.decidingGame() ? " <gold>(decydujący)" : ""));
        l.add(" ");
        for (Team t : Team.values()) {
            l.add(teamColor(t) + teamName(t) + "<gray>: <white><bold>" + game.points(t) + "</bold> <dark_gray>(gemy " + game.games(t) + ")" + (game.serving() == t ? " <yellow>●" : ""));
        }
        l.add("  ");
        l.add("<gray>Do <white>" + game.pointsTarget() + "<gray> punktów, przewaga 2");
        return l;
    }
}
