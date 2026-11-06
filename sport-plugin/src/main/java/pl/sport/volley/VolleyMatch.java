package pl.sport.volley;

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
import pl.sport.core.PhysicsWorld;
import pl.sport.core.Team;
import pl.sport.core.V3;
import pl.sport.match.BallKind;
import pl.sport.match.Match;
import pl.sport.match.Participant;
import pl.sport.ui.Msg;
import pl.sport.volley.VolleyGame.HitType;
import pl.sport.volley.VolleyGame.PointResult;
import pl.sport.volley.VolleyGame.Reason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mecz siatkówki na boisku: ustawia graczy, serwuje, prowadzi piłkę fizyką i zamienia kliknięcia graczy na uderzenia. Zasady
 * punktacji, odbić i setów siedzą w {@link VolleyGame}, a tor lotu po uderzeniu w {@link HitModel}.
 */
public final class VolleyMatch extends Match {
    private enum Sub { PREP, SERVE, RALLY, POINT, SET_BREAK }

    private static final long SECOND = 1_000_000_000L;

    private final VolleyCourt court;
    private VolleyGame game;
    private final Map<Team, List<UUID>> order = new EnumMap<>(Team.class);
    private final List<String> setScores = new ArrayList<>();
    private Sub sub = Sub.PREP;
    private double subTimer;
    private UUID serverId;
    private boolean tossed;
    private double sinceToss;
    private long lastTouchNanos = -1_000_000_000_000L;
    private int barTicks;

    public VolleyMatch(SportPlugin plugin, Arena arena) {
        super(plugin, arena);
        this.court = new VolleyCourt(arena.length(), arena.width(), arena.netHeight());
    }

    public VolleyGame game() {
        return game;
    }

    public VolleyCourt court() {
        return court;
    }

    /** Gracz, który teraz zagrywa (null poza zagrywką). */
    public UUID serverId() {
        return sub == Sub.SERVE ? serverId : null;
    }

    /** Czy piłka została już podrzucona do zagrywki. */
    public boolean tossed() {
        return tossed;
    }

    /** Czy akcja trwa (piłka w grze po zagrywce). */
    public boolean rallyOn() {
        return sub == Sub.RALLY;
    }

    /** Położenie piłki w układzie boiska (null przed pierwszą zagrywką). */
    public V3 ballPosition() {
        return ball == null ? null : ball.pos();
    }

    public V3 ballVelocity() {
        return ball == null ? null : ball.vel();
    }

    @Override
    protected String sportLabel() {
        return "Siatkówka";
    }

    @Override
    protected int maxPerTeam() {
        return cfg().volleyMaxPerTeam();
    }

    @Override
    protected BallSpec ballSpec() {
        return BallSpec.VOLLEYBALL;
    }

    @Override
    protected BallKind ballKind() {
        return BallKind.VOLLEYBALL;
    }

    // ---------- ustawienie graczy ----------

    private boolean lowSideOf(Team t) {
        if (game == null) return t == Team.A;
        return game.sideOwner(true) == t;
    }

    private V3 netFacing(boolean lowSide) {
        return new V3(lowSide ? 1 : -1, 0, 0);
    }

    private V3 teamSpot(boolean lowSide, int index, int count) {
        double v = arena.width() * (index + 1.0) / (count + 1.0);
        double u = court.netU() + (lowSide ? -4.5 : 4.5);
        return new V3(u, 0, v);
    }

    @Override
    protected void placeInLobby(Participant part) {
        List<Participant> mates = team(part.team());
        int idx = mates.indexOf(part);
        boolean low = lowSideOf(part.team());
        teleport(part, spot(teamSpot(low, idx, Math.max(1, mates.size())), netFacing(low)));
    }

    private void placeTeams() {
        for (Team t : Team.values()) {
            List<UUID> list = order.getOrDefault(t, List.of());
            boolean low = lowSideOf(t);
            for (int i = 0; i < list.size(); i++) {
                Participant part = players.get(list.get(i));
                if (part != null) teleport(part, spot(teamSpot(low, i, list.size()), netFacing(low)));
            }
        }
    }

    // ---------- start i koniec ----------

    @Override
    protected void onStart() {
        Team first = rng.nextBoolean() ? Team.A : Team.B;
        game = new VolleyGame(new VolleyGame.Config(cfg().volleySetsToWin(), cfg().volleyPointsPerSet(), cfg().volleyPointsDecidingSet(), 2, 3, true), first);
        physics = court.physics(ballSpec());
        for (Team t : Team.values()) game.setTeamSize(t, teamSize(t));
        order.clear();
        for (Team t : Team.values()) {
            List<UUID> ids = new ArrayList<>();
            for (Participant p : team(t)) ids.add(p.id());
            order.put(t, ids);
        }
        setScores.clear();
        placeTeams();
        for (Participant part : players.values()) {
            Player pl = part.player();
            if (pl != null && teamSize(part.team()) == 1) {
                Msg.send(pl, "<yellow>Grasz sam w drużynie: <gray>masz do <white>3 odbić z rzędu</white>. <white>Shift + LPM</white> (albo spojrzenie mocno w górę przy odbiciu) wystawia piłkę Tobie, a <white>skok + LPM</white> przy wysokiej piłce to atak. Ostatnie odbicie zawsze idzie na drugą stronę.");
            }
        }
        broadcast("<green>Zaczyna zagrywką: " + coloredTeam(first) + "<green>. Do " + game.pointsTarget() + " punktów w secie, wygrywa pierwsza drużyna z " + game.config().setsToWin() + " wygranymi setami.");
        startPrep(2.5);
    }

    /** Ograniczenia ruchu: gracz zostaje na swojej połowie boiska (siatka jest granicą). Wolno mu wracać na swoją stronę. */
    @Override
    protected boolean canMove(Participant part, V3 from, V3 to) {
        boolean low = lowSideOf(part.team());
        double net = court.netU();
        boolean onOwnSide = low ? to.x() < net : to.x() > net;
        if (onOwnSide) return true;
        boolean towardsOwn = low ? to.x() < from.x() : to.x() > from.x();
        return towardsOwn;
    }

    /** Ustawia piłkę w danym miejscu (do testów i narzędzi administratora). */
    public void teleportBall(V3 pos, V3 vel) {
        if (ball != null) ball.set(pos, vel);
    }

    @Override
    protected void onReset() {
        game = null;
        physics = null;
        order.clear();
        setScores.clear();
        sub = Sub.PREP;
        tossed = false;
        serverId = null;
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
        String score = game.sets(Team.A) + " : " + game.sets(Team.B);
        String detail = setScores.isEmpty() ? "" : " <dark_gray>(" + String.join(", ", setScores) + ")";
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
        if (sv != null) teleport(sv, spot(court.serveSpot(low), netFacing(low)));
        tossed = false;
        sinceToss = 0;
        sub = Sub.SERVE;
        subTimer = cfg().volleyServeSeconds();
        Player p = sv == null ? null : sv.player();
        V3 start = p == null ? court.serveSpot(low).plus(new V3(0, 1.3, 0)) : handPos(p);
        if (ball == null) spawnBall(start);
        else {
            ball.set(start, V3.ZERO);
        }
        if (p != null) {
            Msg.title(p, "<yellow><bold>Twoja zagrywka!", "<gray>LPM podrzuca piłkę, drugie LPM ją uderza", 0.1, 2.5, 0.3);
            Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
        }
        broadcast(coloredTeam(st) + " <gray>serwują: " + teamColor(st) + (sv == null ? "?" : Msg.esc(sv.name())));
        refreshHud();
    }

    /** Dłoń zagrywającego: tuż przed ciałem na wysokości pasa. */
    private V3 handPos(Player p) {
        V3 feet = local(p.getLocation());
        V3 look = arena.dirToLocal(vec(p.getLocation().getDirection()));
        V3 fwd = look.horizontal().normalized();
        return feet.plus(new V3(0, 1.15, 0)).plus(fwd.times(0.5));
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
            case RALLY -> stepBall(dt, true);
            case POINT -> {
                stepBall(dt, false);
                subTimer -= dt;
                if (subTimer <= 0 && !game.finished()) startPrep(0.6);
            }
            case SET_BREAK -> {
                stepBall(dt, false);
                subTimer -= dt;
                if (subTimer <= 0 && !game.finished()) startPrep(2.5);
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
        if (sv != null && overServeLine(local(sv.getLocation()))) {
            // zagrywka tylko zza linii końcowej: kto wejdzie na boisko przed uderzeniem, traci punkt
            Team before = game.serving();
            resolve(before, game.footFault());
            return;
        }
        if (!tossed) {
            if (sv != null) ball.set(handPos(sv), V3.ZERO);
            subTimer -= dt;
            if (subTimer <= 0) {
                Team before = game.serving();
                resolve(before, game.serveTimeout());
                return;
            }
        } else {
            stepBall(dt, true);
            sinceToss += dt;
            if (sub == Sub.SERVE && sinceToss > 4.0) {
                Team before = game.serving();
                resolve(before, game.serveTimeout());
            }
        }
    }

    private void updateBar() {
        if (hud == null || game == null) return;
        switch (sub) {
            case SERVE -> hud.bar("<yellow>Zagrywka: " + (tossed ? "piłka w górze, uderz!" : "podrzuć piłkę (LPM)"), tossed ? 1 : subTimer / cfg().volleyServeSeconds(), net.kyori.adventure.bossbar.BossBar.Color.YELLOW);
            case RALLY -> {
                Team tt = game.touchTeam();
                hud.bar(tt == null ? "<gray>Akcja trwa" : teamColor(tt) + teamName(tt) + "<gray>: odbicia " + game.touches() + "/" + game.config().maxTouches(),
                        tt == null ? 1 : 1 - game.touches() / (double) (game.config().maxTouches() + 1), net.kyori.adventure.bossbar.BossBar.Color.GREEN);
            }
            case POINT -> hud.bar("<gold>Punkt!", 1, net.kyori.adventure.bossbar.BossBar.Color.RED);
            case SET_BREAK -> hud.bar("<gold>Przerwa między setami", 1, net.kyori.adventure.bossbar.BossBar.Color.PURPLE);
            default -> hud.bar("<gray>Przygotowanie", 1, net.kyori.adventure.bossbar.BossBar.Color.WHITE);
        }
    }

    // ---------- piłka ----------

    private void stepBall(double dt, boolean detectLanding) {
        if (ball == null || physics == null) return;
        physics.step(ball, dt, this::onContact);
        unstick(dt);
        if (!detectLanding) return;
        V3 p = ball.pos();
        boolean far = Math.abs(p.x() - court.netU()) > 70 || Math.abs(p.z()) > 70 || p.y() < -15;
        if (ball.onFloor() || far) landed(far);
    }

    private void onContact(Contact c, double impact, V3 pos) {
        float vol = (float) Math.min(1.0, 0.25 + impact / 14.0);
        Location l = worldAt(pos);
        switch (c.kind()) {
            case NET -> world.playSound(l, Sound.BLOCK_WOOL_HIT, SoundCategory.PLAYERS, vol, 0.8f);
            case POST -> world.playSound(l, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.25f, 2f);
            case FLOOR -> {
                world.playSound(l, Sound.BLOCK_SAND_STEP, SoundCategory.PLAYERS, vol, 0.7f);
                if (impact > 3) world.spawnParticle(Particle.CLOUD, l.clone().add(0, 0.1, 0), 4, 0.1, 0.02, 0.1, 0.01);
            }
            default -> { }
        }
    }

    private void landed(boolean far) {
        V3 p = ball.pos();
        boolean in = !far && court.inCourt(p, ballSpec().radius());
        Team owner = in ? game.sideOwner(court.lowSide(p)) : null;
        Team before = game.serving();
        PointResult r = game.landed(owner, in);
        afterResult(before, r, p);
    }

    private void resolve(Team servingBefore, PointResult r) {
        afterResult(servingBefore, r, ball == null ? null : ball.pos());
    }

    private void afterResult(Team servingBefore, PointResult r, V3 at) {
        if (r == null) return;
        if (game.serving() != servingBefore && !r.matchFinished()) {
            List<UUID> list = order.get(game.serving());
            if (list != null && list.size() > 1) Collections.rotate(list, -1);
        }
        if (at != null && world != null) {
            Location l = worldAt(at);
            world.spawnParticle(Particle.CLOUD, l.clone().add(0, 0.1, 0), 12, 0.25, 0.05, 0.25, 0.02);
        }
        String why = switch (r.reason()) {
            case LANDED_IN -> "Piłka upadła na boisku.";
            case LANDED_OUT -> "Aut!";
            case FOUR_TOUCHES -> "Cztery odbicia!";
            case DOUBLE_TOUCH -> "Podwójne odbicie!";
            case SERVE_FAULT -> "Błąd zagrywki!";
            case FOOT_FAULT -> "Zagrywka z boiska: przekroczona linia końcowa!";
        };
        Team w = r.winner();
        broadcast(coloredTeam(w) + " <green>zdobywają punkt. <gray>" + why + " <white>" + r.pointsA() + ":" + r.pointsB()
                + (r.setFinished() ? "" : " <dark_gray>(sety " + game.sets(Team.A) + ":" + game.sets(Team.B) + ")"));
        broadcastSound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        for (Participant part : players.values()) {
            Player p = part.player();
            if (p != null) Msg.actionBar(p, teamColor(w) + "<bold>Punkt dla " + teamName(w) + "</bold> <gray>" + why + " <white>" + r.pointsA() + ":" + r.pointsB());
        }
        if (r.matchFinished()) {
            setScores.add(r.pointsA() + ":" + r.pointsB());
            finishMatch(r.matchWinner(), false);
            return;
        }
        if (r.setFinished()) {
            setScores.add(r.pointsA() + ":" + r.pointsB());
            placeTeams(); // strony już się zmieniły: gracze od razu idą na swoje nowe połowy
            broadcastTitle(teamColor(w) + "<bold>Set dla " + teamName(w), "<gray>" + r.pointsA() + ":" + r.pointsB() + " <dark_gray>• sety " + game.sets(Team.A) + ":" + game.sets(Team.B));
            broadcast("<gold>Koniec seta " + (game.setNumber() - 1) + ": " + r.pointsA() + ":" + r.pointsB() + ". Zmiana stron!");
            sub = Sub.SET_BREAK;
            subTimer = 6;
        } else {
            sub = Sub.POINT;
            subTimer = 2.5;
        }
        refreshHud();
    }

    // ---------- sterowanie ----------

    /** Wynik próby uderzenia piłki po kliknięciu. */
    private enum HitResult {
        HIT,
        /** Odbicie było błędem (np. czwarte odbicie, podwójne odbicie albo wejście na boisko przy zagrywce): punkt rozstrzygnięty. */
        FAULT,
        /** Piłka za daleko: warto spróbować jeszcze raz za chwilę, bo może właśnie nadlatuje. */
        TOO_FAR,
        /** Piłka za plecami gracza. */
        BEHIND,
        /** Piłka po drugiej stronie siatki. */
        OTHER_SIDE,
        /** Za wcześnie po poprzednim uderzeniu (podwójne kliknięcie): ignorowane po cichu. */
        TOO_SOON
    }

    /** Tyle czasu kliknięcie „czeka” na piłkę, która dopiero zbliża się do gracza (bez tego trzeba było trafić w okno co do ticka). */
    private static final long CLICK_BUFFER = 250_000_000L;
    /** Ponowne kliknięcie tego samego gracza krócej niż tyle po jego odbiciu to podwójne kliknięcie, a nie drugie odbicie. */
    private static final long DOUBLE_CLICK = 350_000_000L;

    private record BufferedClick(long at, HitResult reason) {}

    private final Map<UUID, BufferedClick> buffered = new LinkedHashMap<>();

    private static boolean retryable(HitResult r) {
        return r == HitResult.TOO_FAR || r == HitResult.BEHIND || r == HitResult.OTHER_SIDE;
    }

    private String missText(HitResult r) {
        return switch (r) {
            case TOO_FAR -> "<gray>Za daleko od piłki: podejdź bliżej (zasięg " + String.format("%.1f", cfg().reach()) + " m)";
            case BEHIND -> "<gray>Piłka jest za Tobą: obróć się w jej stronę";
            case OTHER_SIDE -> "<gray>Piłka jest po drugiej stronie siatki";
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
            // kliknięcie chwilę przed piłką nie przepada: czeka kilka ticków, a jeśli piłka nie nadleci, gracz dostanie powód
            buffered.put(p.getUniqueId(), new BufferedClick(now(), r));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.4f, 1.5f);
        } else {
            buffered.remove(p.getUniqueId());
        }
    }

    /** Ponawia kliknięcia, które trafiły przed piłką, a po upływie okna mówi graczowi, dlaczego się nie udało. */
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
        V3 look = arena.dirToLocal(vec(sv.getLocation().getDirection())).horizontal().normalized();
        ball.setVel(new V3(look.x() * 0.8, 6.3, look.z() * 0.8));
        tossed = true;
        sinceToss = 0;
        ballSound(Sound.ENTITY_SNOWBALL_THROW, 0.8f, 1.2f);
        Msg.actionBar(sv, "<yellow>Piłka w górze! <gray>Uderz LPM, gdy będzie nad Tobą");
    }

    private boolean airborne(Player p, V3 feet) {
        return !p.isOnGround() && feet.y() > 0.35;
    }

    /** Numer odbicia, które gracz właśnie wykonuje dla swojej drużyny (1, 2, 3). */
    private int touchNumber(Participant part) {
        return (game.touchTeam() == part.team() ? game.touches() : 0) + 1;
    }

    private HitResult tryHit(Player p, Participant part) {
        long now = now();
        if (now - part.lastHit < (long) (cfg().hitCooldownSeconds() * SECOND)) return HitResult.TOO_SOON;
        Location el = p.getEyeLocation();
        V3 eye = local(el);
        V3 look = arena.dirToLocal(vec(el.getDirection())).normalized();
        V3 b = ball.pos();
        // piłka na ekranie jest o ok. jeden tick za swoim położeniem w grze, więc zasięg liczymy też od miejsca, które gracz widzi
        V3 seen = b.minus(ball.vel().times(0.06));
        V3 to = b.minus(eye);
        V3 toSeen = seen.minus(eye);
        double dist = Math.min(to.length(), toSeen.length());
        if (toSeen.length() < to.length()) to = toSeen;
        if (dist > cfg().reach()) return HitResult.TOO_FAR;
        // piłka tuż nad głową (wystawiona sobie) ma być do odbicia nawet wtedy, gdy gracz patrzy w siatkę
        boolean inCone = dist < 1.6 || to.normalized().dot(look) > -0.1;
        if (!inCone) return HitResult.BEHIND;
        // podwójne kliknięcie tuż po własnym odbiciu nie robi nic zamiast psuć akcję
        if (sub == Sub.RALLY && p.getUniqueId().equals(game.lastToucher()) && now - lastTouchNanos < DOUBLE_CLICK) return HitResult.TOO_SOON;

        V3 feet = local(p.getLocation());
        if (sub == Sub.SERVE && overServeLine(feet)) {
            Team before = game.serving();
            resolve(before, game.footFault());
            return HitResult.FAULT;
        }
        boolean ballLow = court.lowSide(b);
        boolean playerLow = court.lowSide(feet);
        boolean air = airborne(p, feet);
        HitType type = classify(p, part, eye, b, feet, air);
        if (type != HitType.BLOCK && type != HitType.SERVE && ballLow != playerLow) return HitResult.OTHER_SIDE; // przez siatkę nie sięga się po piłkę rywala, chyba że to blok

        int number = touchNumber(part);
        double quality = quality(type, eye, look, b);
        V3 mate = (type == HitType.BUMP || type == HitType.SET) ? findMate(p, part, eye, look) : null;
        if (mate == null && teamSize(part.team()) == 1) mate = selfTarget(type, feet, look, number);
        HitModel.Hit hit = HitModel.compute(court, ballSpec(), new HitModel.Request(type, b, ball.vel(), eye, look, playerLow, mate, quality), rng);

        Team before = game.serving();
        PointResult res = game.touch(part.team(), part.id(), type);
        ball.setVel(hit.velocity());
        part.lastHit = now;
        lastTouchNanos = now;
        if (sub == Sub.SERVE) {
            sub = Sub.RALLY;
        }
        hitEffects(p, type, quality, b, hit.aimedAtTarget() && mate != null && teamSize(part.team()) == 1 ? number : 0);
        afterResult(before, res, b);
        return res != null ? HitResult.FAULT : HitResult.HIT;
    }

    private HitType classify(Player p, Participant part, V3 eye, V3 b, V3 feet, boolean air) {
        if (sub == Sub.SERVE) return HitType.SERVE;
        double dNet = Math.abs(eye.x() - court.netU());
        boolean ballNearNet = Math.abs(b.x() - court.netU()) < 1.4;
        boolean opponentsBall = game.touchTeam() != null && game.touchTeam() != part.team();
        if (air && dNet < 1.8 && ballNearNet && opponentsBall && b.y() > court.netHeight() - 0.8) return HitType.BLOCK;
        if (air && b.y() >= eye.y() - 0.35 && !p.isSneaking()) return HitType.SPIKE;
        if (p.isSneaking()) return HitType.SET;
        return HitType.BUMP;
    }

    /** Jakość uderzenia 0-1: jak blisko idealnego punktu kontaktu (zależnego od rodzaju uderzenia) znajduje się piłka. */
    private double quality(HitType type, V3 eye, V3 look, V3 ball) {
        V3 ideal = switch (type) {
            case SERVE -> eye.plus(new V3(0, 0.9, 0)).plus(look.horizontal().normalized().times(0.2));
            case SPIKE -> eye.plus(new V3(0, 0.55, 0)).plus(look.horizontal().normalized().times(0.45));
            case SET -> eye.plus(new V3(0, 0.45, 0)).plus(look.horizontal().normalized().times(0.25));
            case BLOCK -> eye.plus(new V3(0, 0.4, 0)).plus(look.horizontal().normalized().times(0.7));
            case BUMP -> eye.plus(new V3(0, -0.45, 0)).plus(look.horizontal().normalized().times(0.8));
        };
        double d = ball.distanceTo(ideal);
        return Math.max(0, Math.min(1, 1 - (d - 0.5) / 1.8));
    }

    /**
     * Gracz bez partnera (np. 1 na 1) wystawia piłkę sobie: Shift + LPM albo odbicie z patrzeniem w górę (ponad ok. 20 stopni) kieruje ją wysoko
     * tuż przed niego. Ostatnie dozwolone odbicie nigdy nie jest wystawieniem sobie, bo kolejne byłoby błędem czterech odbić.
     */
    private V3 selfTarget(HitType type, V3 feet, V3 look, int touchNumber) {
        if (touchNumber >= game.config().maxTouches()) return null;
        if (type != HitType.SET && !(type == HitType.BUMP && look.y() > 0.3)) return null;
        double toNet = court.netU() - feet.x() > 0 ? 1 : -1;
        return new V3(feet.x() + toNet * 0.7, 0, feet.z());
    }

    /** Czy zagrywający stoi przed linią końcową (na boisku). Wolno mu stać na samej linii. */
    private boolean overServeLine(V3 feet) {
        boolean low = lowSideOf(game.serving());
        return low ? feet.x() > 0.3 : feet.x() < court.length() - 0.3;
    }

    /** Kolega z drużyny, na którego gracz patrzy (w stożku 30 stopni); do niego trafia wystawienie albo odbicie. */
    private V3 findMate(Player p, Participant me, V3 eye, V3 look) {
        V3 lookH = look.horizontal().normalized();
        V3 best = null;
        double bestScore = Double.MAX_VALUE;
        for (Participant other : players.values()) {
            if (other == me || other.team() != me.team()) continue;
            Player op = other.player();
            if (op == null) continue;
            V3 pos = local(op.getLocation());
            V3 d = pos.minus(eye).horizontal();
            double dist = d.length();
            if (dist < 1.5 || dist > 16) continue;
            double cos = lookH.dot(d.normalized());
            if (cos < Math.cos(Math.toRadians(30))) continue;
            double score = Math.acos(Math.min(1, cos)) * dist;
            if (score < bestScore) {
                bestScore = score;
                best = new V3(pos.x(), 0, pos.z());
            }
        }
        return best;
    }

    private void hitEffects(Player p, HitType type, double quality, V3 at, int soloTouch) {
        Location l = worldAt(at);
        String label;
        switch (type) {
            case SERVE -> {
                label = "Zagrywka";
                world.playSound(l, Sound.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 0.6f, 1.6f);
            }
            case SPIKE -> {
                label = "Atak";
                world.playSound(l, Sound.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.9f, 1.4f);
                world.spawnParticle(Particle.CRIT, l, 8, 0.15, 0.15, 0.15, 0.2);
            }
            case SET -> {
                label = "Wystawienie";
                world.playSound(l, Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 0.8f, 1.6f);
            }
            case BLOCK -> {
                label = "Blok";
                world.playSound(l, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.35f, 1.7f);
                world.spawnParticle(Particle.CRIT, l, 10, 0.2, 0.2, 0.2, 0.2);
            }
            default -> {
                label = "Odbicie";
                world.playSound(l, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.7f, 0.7f);
            }
        }
        int stars = (int) Math.round(quality * 5);
        String counter = soloTouch > 0 ? " <white>odbicie " + soloTouch + "/" + game.config().maxTouches() + (soloTouch >= game.config().maxTouches() ? " <red>(ostatnie: piłka idzie na drugą stronę)" : "") : "";
        Msg.actionBar(p, "<gold>" + label + " <gray>" + "★".repeat(stars) + "<dark_gray>" + "★".repeat(5 - stars) + counter);
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
        l.add("<gray>Set <white>" + game.setNumber() + (game.decidingSet() ? " <gold>(decydujący)" : ""));
        l.add(" ");
        for (Team t : Team.values()) {
            l.add(teamColor(t) + teamName(t) + "<gray>: <white><bold>" + game.points(t) + "</bold> <dark_gray>(sety " + game.sets(t) + ")" + (game.serving() == t ? " <yellow>●" : ""));
        }
        l.add("  ");
        l.add("<gray>Do <white>" + game.pointsTarget() + "<gray> punktów, przewaga 2");
        return l;
    }
}
