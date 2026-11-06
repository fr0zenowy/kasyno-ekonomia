package pl.sport.basket;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import pl.sport.SportPlugin;
import pl.sport.arena.Arena;
import pl.sport.basket.BasketGame.FoulType;
import pl.sport.basket.BasketGame.Update;
import pl.sport.core.Ballistics;
import pl.sport.core.BallSpec;
import pl.sport.core.Contact;
import pl.sport.core.HoopSensor;
import pl.sport.core.Team;
import pl.sport.core.V3;
import pl.sport.match.BallKind;
import pl.sport.match.Match;
import pl.sport.match.Participant;
import pl.sport.ui.Msg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mecz koszykówki: kozłowanie, rzuty (zwykły, w wyskoku, dwutakt, wsad), podania, przechwyty, bloki, faule i rzuty osobiste.
 * Rzuty podlegają prawdziwej fizyce (obręcz, tablica), a zegary, punkty, faule i rzuty osobiste prowadzi {@link BasketGame}.
 */
public final class BasketMatch extends Match {
    private enum Sub { PREP, PLAY, SCORED, FREE_THROW }

    private enum Move { LAYUP, DUNK }

    private record Pending(Move move, int hoop, long due, double quality) {}

    private static final long SECOND = 1_000_000_000L;
    private static final double DRIBBLE_PERIOD = 0.55;
    /** Linia rzutów osobistych: odległość od linii końcowej. */
    private static final double FT_LINE = 5.8;
    private static final double LAYUP_RANGE = 4.8;
    private static final double DUNK_RANGE = 2.6;
    /** Największa odległość od obręczy, z której wsad jeszcze się udaje (gracz mógł nie dolecieć). */
    private static final double DUNK_REACH = 3.4;

    private final BasketCourt court;
    private BasketGame game;
    private final HoopSensor[] sensors = new HoopSensor[2];
    private Sub sub = Sub.PREP;
    private double subTimer;
    private UUID holderId;
    private V3 deadSpot;
    private V3 pendingSpot;
    private double dribblePhase;
    private double dribbleHeight = 1;
    private double hover;
    private int barTicks;
    private int lastBounce;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private String technique;
    private String scoredTechnique;
    private V3 foulSpot;
    private double shotAge;
    private long chargeLockUntil;
    private String foulReason;
    /** Kiedy ostatnio dany gracz puścił piłkę w rzucie (do oceny spóźnionych machnięć obrońcy). */
    private final Map<UUID, Long> shotAt = new HashMap<>();
    /** Kiedy gracz może znów spróbować wsadu (po próbie musi odpocząć). */
    private final Map<UUID, Long> dunkReadyAt = new HashMap<>();
    /** Adresat ostatniego podania: łapie piłkę z większej odległości niż rywale przecinający tor. */
    private UUID passTarget;
    private long passTargetUntil;
    private final Map<UUID, Map<String, Long>> hintsShown = new HashMap<>();
    private int hintTicks;

    private static final long STEAL_COOLDOWN = 900_000_000L;
    /** Próba przechwytu z za daleka albo bokiem prawie nie blokuje następnej. */
    private static final long INVALID_CLICK_COOLDOWN = 250_000_000L;
    private static final double STEAL_RANGE = 2.0;

    // rzuty osobiste
    private int ftHoop;
    private boolean ftReleased;
    private double ftWait;
    private double ftTimer;
    private double ftFlight;
    private HoopSensor ftSensor;

    public BasketMatch(SportPlugin plugin, Arena arena) {
        super(plugin, arena);
        this.court = new BasketCourt(arena.length(), arena.width());
    }

    public BasketGame game() {
        return game;
    }

    public BasketCourt court() {
        return court;
    }

    public UUID holder() {
        return holderId;
    }

    /** Położenie piłki w układzie boiska (null przed rozpoczęciem). */
    public V3 ballPosition() {
        return ball == null ? null : ball.pos();
    }

    /** Czy trwa rzut dwutaktem albo wsad (gracz „w akcji”). */
    public boolean hasPendingMove(UUID id) {
        return pending.containsKey(id);
    }

    /** Czy trwają rzuty osobiste. */
    public boolean inFreeThrows() {
        return sub == Sub.FREE_THROW;
    }

    /** Etykieta techniki rzutu w locie (np. „Wsad”) albo null. */
    public String technique() {
        return technique;
    }

    /** Technika ostatniego zdobytego kosza z gry (null dla zwykłego rzutu albo rzutu osobistego). */
    public String scoredTechnique() {
        return scoredTechnique;
    }

    /** Ustawia piłkę w danym miejscu (do testów i narzędzi administratora). */
    public void teleportBall(V3 pos, V3 vel) {
        if (ball != null) ball.set(pos, vel);
    }

    @Override
    protected String sportLabel() {
        return "Koszykówka";
    }

    @Override
    protected int maxPerTeam() {
        return cfg().basketMaxPerTeam();
    }

    @Override
    protected BallSpec ballSpec() {
        return BallSpec.BASKETBALL;
    }

    @Override
    protected BallKind ballKind() {
        return BallKind.BASKETBALL;
    }

    // ---------- ustawienie graczy ----------

    private V3 teamSpot(Team t, int index, int count) {
        double v = arena.width() * (index + 1.0) / (count + 1.0);
        double u = arena.length() / 2.0 + (t == Team.A ? -3.0 : 3.0);
        return new V3(u, 0, v);
    }

    private V3 facing(Team t) {
        return new V3(t == Team.A ? 1 : -1, 0, 0);
    }

    @Override
    protected void placeInLobby(Participant part) {
        List<Participant> mates = team(part.team());
        teleport(part, spot(teamSpot(part.team(), mates.indexOf(part), Math.max(1, mates.size())), facing(part.team())));
    }

    private void placeTeams() {
        for (Team t : Team.values()) {
            List<Participant> mates = team(t);
            for (int i = 0; i < mates.size(); i++) teleport(mates.get(i), spot(teamSpot(t, i, mates.size()), facing(t)));
        }
    }

    /** Podczas rzutów osobistych nikt się nie rusza (stoją przy linii i na swoich miejscach). */
    @Override
    protected boolean canMove(Participant part, V3 from, V3 to) {
        return sub != Sub.FREE_THROW;
    }

    // ---------- start i koniec ----------

    @Override
    protected void onStart() {
        double bonus = cfg().basketBonusFouls();
        int limit = cfg().basketFoulLimit();
        BasketGame.Config c = cfg().basketPointsToWin() > 0
                ? new BasketGame.Config(0, cfg().basketShotClock(), 14, 0, cfg().basketPointsToWin(), (int) bonus, limit)
                : new BasketGame.Config(cfg().basketMinutes() * 60, cfg().basketShotClock(), 14, cfg().basketOvertimeSeconds(), 0, (int) bonus, limit);
        Team first = rng.nextBoolean() ? Team.A : Team.B;
        game = new BasketGame(c, Team.A, first);
        physics = court.physics(ballSpec());
        sensors[0] = court.sensor(0);
        sensors[1] = court.sensor(1);
        holderId = null;
        pending.clear();
        placeTeams();
        V3 center = new V3(arena.length() / 2.0, 0, arena.width() / 2.0);
        placeDead(center);
        broadcast("<green>Rzut sędziowski: piłkę dostaje " + coloredTeam(first) + "<green>. <gray>Każda drużyna atakuje kosz po przeciwnej stronie boiska niż ta, na której stoi na starcie.");
        sub = Sub.PLAY;
        refreshHud();
    }

    @Override
    protected void onReset() {
        game = null;
        physics = null;
        holderId = null;
        deadSpot = null;
        pendingSpot = null;
        pending.clear();
        technique = null;
        shotAt.clear();
        dunkReadyAt.clear();
        passTarget = null;
        hintsShown.clear();
        sub = Sub.PREP;
    }

    @Override
    protected void onPlayerLeft(Participant part) {
        pending.remove(part.id());
        if (game == null || game.ended()) return;
        if (part.id().equals(holderId)) {
            Player p = part.player();
            game.drop();
            holderId = null;
            if (ball != null && p != null) ball.set(local(p.getLocation()).plus(new V3(0, 1.0, 0)), V3.ZERO);
        }
        if (teamSize(part.team()) == 0) {
            broadcast(coloredTeam(part.team()) + " <red>nie ma już graczy, mecz kończy się walkowerem.");
            finishMatch(part.team().other(), true);
            return;
        }
        if (sub == Sub.FREE_THROW && part.id().equals(game.freeThrowShooter())) {
            // poszkodowany wyszedł: rzuty osobiste wykona jego kolega z drużyny
            List<Participant> mates = team(game.freeThrowTeam());
            if (!mates.isEmpty()) {
                game.setFreeThrowShooter(mates.get(0).id());
                beginFreeThrows();
            }
        }
    }

    private void finishMatch(Team winner, boolean forfeit) {
        String score = game.score(Team.A) + " : " + game.score(Team.B);
        for (Participant part : players.values()) {
            Player p = part.player();
            if (p == null) continue;
            boolean won = part.team() == winner;
            Msg.title(p, won ? "<green><bold>ZWYCIĘSTWO!" : "<red><bold>PORAŻKA", teamColor(winner) + teamName(winner) + "<gray> wygrywają " + score, 0.2, 4, 1);
            Msg.sound(p, won ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
        broadcast("<gold><bold>Koniec meczu!</bold> " + coloredTeam(winner) + " <gold>wygrywają " + score + "</gold>" + (forfeit ? " <gray>(walkower)" : ""));
        holderId = null;
        pending.clear();
        refreshHud();
        beginEnding();
    }

    // ---------- piłka martwa i trzymana ----------

    /** Piłka czeka w danym miejscu na wznowienie: unosi się na wysokości pasa. */
    private void placeDead(V3 spotOnFloor) {
        holderId = null;
        pending.clear();
        deadSpot = new V3(spotOnFloor.x(), 0, spotOnFloor.z());
        V3 pos = deadSpot.plus(new V3(0, 1.05, 0));
        if (ball == null) spawnBall(pos);
        else ball.set(pos, V3.ZERO);
        for (Participant part : players.values()) {
            Player p = part.player();
            if (p != null && part.team() == game.inbound()) Msg.actionBar(p, "<yellow>Wznawiasz grę! <gray>Podejdź do piłki, żeby ją wziąć");
        }
    }

    private void followHolder(double dt) {
        Player hp = onlinePlayer(holderId);
        if (hp == null) {
            game.drop();
            holderId = null;
            return;
        }
        Participant part = players.get(holderId);
        V3 feet = local(hp.getLocation());
        // wyjście z piłką poza linie to aut: piłkę dostaje drużyna rywali przy najbliższej linii
        double margin = 0.4;
        if (feet.x() < -margin || feet.x() > arena.length() + margin || feet.z() < -margin || feet.z() > arena.width() + margin) {
            V3 spot = court.sidelineSpot(feet);
            ball.set(new V3(feet.x(), 1.0, feet.z()), V3.ZERO);
            handle(game.outOfBounds());
            if (sub == Sub.PLAY && game.phase() == BasketGame.Phase.DEAD) placeDead(spot);
            return;
        }
        if (part != null && checkCharge(hp, part, feet)) return;
        Location l = hp.getLocation();
        // prawa ręka: kierunek patrzenia i prostopadła na prawo (w świecie), potem zamiana na układ boiska
        Vector dir = l.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        V3 fwd = arena.dirToLocal(new V3(dir.getX(), 0, dir.getZ()));
        V3 right = arena.dirToLocal(new V3(-dir.getZ(), 0, dir.getX()));
        boolean air = part != null && part.air();
        boolean protect = hp.isSneaking() && !air;
        double h;
        V3 side = right.times(0.28);
        double ahead = 0.5;
        if (air) {
            h = 1.35;
        } else if (protect) {
            // osłona piłki: piłka nisko, po stronie przeciwnej do najbliższego obrońcy
            Participant def = part == null ? null : closestOpponent(part, feet, 3.0);
            h = ballSpec().radius() + 0.1 + Math.abs(Math.sin(dribblePhase)) * 0.25;
            dribblePhase += dt / DRIBBLE_PERIOD * Math.PI;
            if (def != null && def.player() != null) {
                V3 dp = local(def.player().getLocation());
                V3 away = new V3(feet.x() - dp.x(), 0, feet.z() - dp.z()).normalized();
                side = away.times(0.4);
                ahead = 0;
            }
        } else {
            dribblePhase += dt / DRIBBLE_PERIOD * Math.PI;
            double s = Math.abs(Math.sin(dribblePhase));
            h = ballSpec().radius() + 0.05 + s * 0.78;
            int bounce = (int) Math.floor(dribblePhase / Math.PI);
            if (bounce != lastBounce) {
                lastBounce = bounce;
                world.playSound(worldAt(feet), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, SoundCategory.PLAYERS, 0.25f, 1.4f);
            }
        }
        dribbleHeight = h;
        V3 pos = new V3(feet.x(), 0, feet.z()).plus(fwd.times(ahead)).plus(side).plus(new V3(0, feet.y() + h, 0));
        ball.set(pos, V3.ZERO);
    }

    /**
     * Faul w ataku: bieg z piłką prosto w obrońcę, który stoi w miejscu i patrzy na atakującego. Obrońca w ruchu (wchodzący pod nogi) albo
     * odwrócony plecami nie jest sfaulowany, więc zwykłe mijanie się z rywalem nigdy nie kończy się gwizdkiem.
     */
    private boolean checkCharge(Player hp, Participant holder, V3 feet) {
        if (!foulsOn() || now() < chargeLockUntil || holder.air()) return false;
        double sp = Math.hypot(holder.vx(), holder.vz());
        if (sp < 3.4) return false;
        Participant def = closestOpponent(holder, feet, 0.65);
        if (def == null || def.player() == null || def.air()) return false;
        V3 dp = local(def.player().getLocation());
        V3 toDef = new V3(dp.x() - feet.x(), 0, dp.z() - feet.z()).normalized();
        V3 run = new V3(holder.vx(), 0, holder.vz()).normalized();
        if (Math.hypot(def.vx(), def.vz()) > 0.8 || run.dot(toDef) < 0.7) return false;
        Vector dl = def.player().getLocation().getDirection().setY(0);
        if (dl.lengthSquared() > 1e-6) {
            V3 defLook = arena.dirToLocal(new V3(dl.getX(), 0, dl.getZ())).normalized();
            if (defLook.dot(toDef.times(-1)) < 0.0) return false; // odwrócony plecami: to on wszedł pod atakującego
        }
        chargeLockUntil = now() + 2 * SECOND;
        if (!roll(1.0)) return false;
        callFoul(FoulType.OFFENSIVE, holder, def, feet, "najechanie na obrońcę (faul w ataku, strata piłki)");
        return true;
    }

    // ---------- tick ----------

    @Override
    protected void onTick(double dt) {
        if (game == null) return;
        switch (sub) {
            case PREP -> sub = Sub.PLAY;
            case PLAY -> {
                runPending();
                play(dt);
            }
            case SCORED -> {
                if (physics != null && ball != null) {
                    physics.step(ball, dt, this::onContact);
                    unstick(dt);
                }
                subTimer -= dt;
                if (subTimer <= 0 && !game.ended()) {
                    placeDead(pendingSpot != null ? pendingSpot : new V3(arena.length() / 2.0, 0, arena.width() / 2.0));
                    sub = Sub.PLAY;
                }
            }
            case FREE_THROW -> tickFreeThrow(dt);
        }
        syncView(dt);
        if (++barTicks >= 4) {
            barTicks = 0;
            updateBar();
        }
    }

    @Override
    protected void onEndingTick(double dt) {
        if (ball != null && physics != null && holderId == null) {
            physics.step(ball, dt, null);
            syncView(dt);
        }
    }

    private void play(double dt) {
        coachHints();
        handle(game.tick(dt));
        if (game.ended() || sub != Sub.PLAY) return;
        if (game.phase() == BasketGame.Phase.DEAD) {
            hover += dt * 3;
            if (deadSpot != null) ball.set(deadSpot.plus(new V3(0, 1.05 + Math.sin(hover) * 0.05, 0)), V3.ZERO);
            tryPickup(game.inbound());
            return;
        }
        if (holderId != null) {
            followHolder(dt);
            return;
        }
        flyBall(dt);
    }

    /** Swobodna piłka: fizyka, kosze, aut, upadek rzutu i podniesienie przez gracza. */
    private void flyBall(double dt) {
        boolean[] scored = {false};
        int[] hoop = {-1};
        physics.step(ball, dt, this::onContact, (from, to) -> {
            for (int h = 0; h < 2 && !scored[0]; h++) {
                if (sensors[h].update(from, to)) {
                    scored[0] = true;
                    hoop[0] = h;
                }
            }
        });
        shotAge = game.shotInFlight() ? shotAge + dt : 0;
        if (scored[0]) {
            handle(game.basket(hoop[0]));
            if (game.ended()) return;
            if (sub == Sub.PLAY && game.phase() == BasketGame.Phase.DEAD) {
                // kosz zaliczony: piłka spada przez siatkę, a wznowienie z linii końcowej przygotowuje przerwa
                pendingSpot = court.baselineSpot(hoop[0]);
                sub = Sub.SCORED;
                subTimer = 2.0;
            }
            return;
        }
        unstick(dt);
        V3 p = ball.pos();
        if (!court.inBounds(p) && (p.x() < -0.15 || p.x() > arena.length() + 0.15 || p.z() < -0.15 || p.z() > arena.width() + 0.15)) {
            V3 spot = court.sidelineSpot(p);
            handle(game.outOfBounds());
            if (sub == Sub.PLAY && game.phase() == BasketGame.Phase.DEAD) placeDead(spot);
            return;
        }
        boolean landed = ball.onFloor() && p.y() < ballSpec().radius() + 0.1;
        if (game.shotInFlight() && (landed || shotAge > 7)) {
            shotAge = 0;
            handle(game.shotResolved());
        }
        if (game.ended() || sub != Sub.PLAY) return;
        tryPickup(null);
    }

    private void onContact(Contact c, double impact, V3 pos) {
        float vol = (float) Math.min(1.0, 0.25 + impact / 12.0);
        Location l = worldAt(pos);
        switch (c.kind()) {
            case FLOOR -> world.playSound(l, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, SoundCategory.PLAYERS, vol, 1.1f);
            case RIM -> {
                world.playSound(l, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, vol * 0.5f, 1.8f);
                if (game != null) game.rimTouch();
            }
            case BOARD -> world.playSound(l, Sound.BLOCK_GLASS_HIT, SoundCategory.PLAYERS, vol, 0.9f);
            default -> { }
        }
    }

    /**
     * Czy piłkę w tej chwili wolno złapać. Rzut w locie jest nietykalny (można go tylko zablokować), dopóki nie trafi w obręcz albo nie opadnie
     * nisko po pudle, bo inaczej każdy, kto stoi pod łukiem lotu, „łapałby” rzut w powietrzu.
     */
    private boolean canCatch() {
        if (!game.shotInFlight()) return true;
        return game.rimTouched() || (ball.vel().y() < 0 && ball.pos().y() < 2.5);
    }

    /** Czy któryś gracz (z drużyny `only`, gdy podana) jest dość blisko, żeby wziąć piłkę. Bierze najbliższego, z pierwszeństwem adresata podania. */
    private void tryPickup(Team only) {
        if (!canCatch()) return;
        long now = now();
        double radius = cfg().basketPickupRadius();
        boolean targeted = passTarget != null && now < passTargetUntil;
        Participant best = null;
        double bestScore = Double.MAX_VALUE;
        for (Participant part : players.values()) {
            if (only != null && part.team() != only) continue;
            if (now < part.pickupLockUntil) continue;
            Player p = part.player();
            if (p == null) continue;
            V3 f = local(p.getLocation());
            double dxz = Math.hypot(f.x() - ball.pos().x(), f.z() - ball.pos().z());
            double dy = ball.pos().y() - f.y();
            boolean receiver = targeted && part.id().equals(passTarget);
            double r = receiver ? radius * 1.6 : radius;
            if (dxz > r || dy < -0.3 || dy > 2.3) continue;
            double score = receiver ? dxz - 0.5 : dxz;
            if (score < bestScore) {
                bestScore = score;
                best = part;
            }
        }
        if (best != null) takeBall(best);
    }

    /** Gracz bierze piłkę (jeśli zasady pozwalają). */
    private boolean takeBall(Participant best) {
        if (!game.pickup(best.team(), best.id())) return false;
        holderId = best.id();
        dribblePhase = 0;
        technique = null;
        passTarget = null;
        Player p = best.player();
        if (p != null) {
            world.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.6f, 0.9f);
            Msg.actionBar(p, "<gold>Masz piłkę! <gray>LPM: rzut (w biegu pod kosz: dwutakt albo wsad)  •  F: podanie  •  Shift: osłona");
        }
        return true;
    }

    // ---------- zdarzenia z zasad ----------

    private void handle(List<Update> updates) {
        for (Update u : updates) {
            switch (u.kind()) {
                case SCORE -> {
                    Team t = u.team();
                    if (sub == Sub.FREE_THROW) {
                        broadcastTitle(teamColor(t) + "<bold>+1", "<green><bold>Rzut osobisty celny!");
                        broadcast(coloredTeam(t) + " <green>trafiają rzut osobisty. <white>" + game.score(Team.A) + ":" + game.score(Team.B));
                        broadcastSound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
                    } else {
                        String label = technique == null ? (u.points() == 3 ? "<gold><bold>TRÓJKA!" : "<green><bold>KOSZ!") : "<gold><bold>" + technique.toUpperCase() + "!";
                        broadcastTitle(teamColor(t) + "<bold>+" + u.points(), label);
                        broadcast(coloredTeam(t) + " <green>zdobywają <white>" + u.points() + "<green> pkt" + (technique == null ? "" : " <gray>(" + technique.toLowerCase() + ")") + ". <white>" + game.score(Team.A) + ":" + game.score(Team.B));
                        broadcastSound(Sound.ENTITY_PLAYER_LEVELUP, 0.7f, u.points() == 3 ? 1.6f : 1.2f);
                        if (ball != null) world.spawnParticle(Particle.FIREWORK, worldAt(ball.pos()), 24, 0.3, 0.3, 0.3, 0.08);
                    }
                    scoredTechnique = sub == Sub.FREE_THROW ? null : technique;
                    technique = null;
                    holderId = null;
                }
                case SHOT_CLOCK -> {
                    broadcast("<red>Naruszenie zegara akcji!</red> <gray>Piłka dla " + coloredTeam(u.team()));
                    broadcastSound(Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.7f);
                    V3 spot = court.sidelineSpot(ball.pos());
                    holderId = null;
                    placeDead(spot);
                }
                case OUT_OF_BOUNDS -> {
                    broadcast("<yellow>Aut!</yellow> <gray>Piłka dla " + coloredTeam(u.team()));
                    broadcastSound(Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 0.6f);
                    holderId = null;
                }
                case OVERTIME -> {
                    broadcastTitle("<red><bold>DOGRYWKA!", "<gray>" + (game.suddenDeath() ? "Pierwszy kosz wygrywa" : "Gramy jeszcze " + (int) cfg().basketOvertimeSeconds() + " s"));
                    broadcast("<red><bold>Remis!</bold> <gray>Dogrywka.");
                }
                case FOUL -> announceFoul(u);
                case FREE_THROWS -> {
                    // rzuty osobiste rozpoczyna afterUpdates(), a kolejne rzuty prowadzi finishFreeThrow()
                }
                case FOUL_OUT -> {
                    UUID id = game.foulOutPlayer();
                    Participant out = id == null ? null : players.get(id);
                    if (out != null) {
                        broadcast(teamColor(out.team()) + Msg.esc(out.name()) + "<red> przekroczył limit fauli i opuszcza mecz.");
                        leave(id, true);
                    }
                }
                case GAME_OVER -> finishMatch(u.team(), false);
            }
        }
        afterUpdates();
    }

    private void afterUpdates() {
        if (game == null || game.ended() || stage != Stage.PLAYING) return; // po walkowerze nie zaczynamy rzutów osobistych
        if (game.phase() == BasketGame.Phase.FREE_THROW && sub != Sub.FREE_THROW) beginFreeThrows();
        refreshHud();
    }

    private void announceFoul(Update u) {
        BasketGame.Foul f = game.lastFoul();
        if (f == null) return;
        Participant fo = players.get(f.foulerId());
        Participant vi = players.get(f.victimId());
        String fouler = fo == null ? "?" : teamColor(f.fouler()) + Msg.esc(fo.name());
        String victim = vi == null ? "?" : teamColor(f.victim()) + Msg.esc(vi.name());
        String what = foulReason != null ? foulReason : switch (f.type()) {
            case SHOOTING -> "faul na rzucającym";
            case PERSONAL -> "faul";
            case OFFENSIVE -> "faul w ataku (strata piłki)";
        };
        foulReason = null;
        String result = f.freeThrows() > 0 ? " <white>" + f.freeThrows() + " rzuty osobiste" : (f.type() == FoulType.OFFENSIVE ? "" : " <white>piłka dla poszkodowanych");
        broadcast("<yellow><bold>Faul!</bold> " + fouler + "<gray> (" + what + ") na " + victim + "<gray>." + result + " <dark_gray>(faule drużyny: " + game.teamFouls(f.fouler()) + ")");
        broadcastSound(Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.9f);
        for (Participant part : players.values()) {
            Player p = part.player();
            if (p != null) Msg.title(p, "<yellow><bold>FAUL", teamColor(f.fouler()) + (fo == null ? "" : Msg.esc(fo.name())) + "<gray>: " + what, 0.05, 1.4, 0.3);
        }
        holderId = null;
        pending.clear();
        if (game.phase() == BasketGame.Phase.DEAD) {
            V3 at = foulSpot != null ? foulSpot : (ball == null ? new V3(arena.length() / 2.0, 0, arena.width() / 2.0) : ball.pos());
            placeDead(court.sidelineSpot(at));
        }
    }

    private boolean foulsOn() {
        return game != null && cfg().basketFouls() && game.phase() == BasketGame.Phase.LIVE;
    }

    /** Losowanie z podanym prawdopodobieństwem pomnożonym przez ustawienie `szansa-faulu`. */
    private boolean roll(double base) {
        return rng.nextDouble() < Math.min(1.0, base * cfg().basketFoulChance());
    }

    private void callFoul(FoulType type, Participant fouler, Participant victim, V3 spot, String reason) {
        foulSpot = spot;
        foulReason = reason;
        handle(game.foul(type, fouler.id(), fouler.team(), victim.id(), victim.team()));
    }

    private Participant closestOpponent(Participant me, V3 at, double maxDist) {
        Participant best = null;
        double bestD = maxDist;
        for (Participant o : players.values()) {
            if (o.team() == me.team() || o.player() == null) continue;
            V3 p = local(o.player().getLocation());
            double d = Math.hypot(p.x() - at.x(), p.z() - at.z());
            if (d <= bestD) {
                bestD = d;
                best = o;
            }
        }
        return best;
    }

    // ---------- sterowanie ----------

    @Override
    public void onSwing(Player p) {
        if (stage != Stage.PLAYING || game == null || ball == null) return;
        Participant part = players.get(p.getUniqueId());
        if (part == null || game.ended()) return;
        if (sub == Sub.FREE_THROW) {
            if (p.getUniqueId().equals(game.freeThrowShooter()) && !ftReleased && ftWait <= 0) releaseFreeThrow(part);
            return;
        }
        if (pending.containsKey(p.getUniqueId())) return;
        if (p.getUniqueId().equals(holderId)) {
            shoot(p, part);
            return;
        }
        if (game.phase() == BasketGame.Phase.DEAD && holderId == null) {
            catchDeadBall(p, part);
            return;
        }
        if (game.phase() != BasketGame.Phase.LIVE) return;
        if (holderId != null) steal(p, part);
        else contest(p, part);
    }

    /** LPM przy piłce czekającej na wznowienie: bierze ją drużyna wznawiająca (bez wchodzenia na nią), reszta dowiaduje się, kto wznawia. */
    private void catchDeadBall(Player p, Participant part) {
        if (part.team() != game.inbound()) {
            Msg.actionBar(p, "<gray>Piłkę wznawia teraz " + teamColor(game.inbound()) + teamName(game.inbound()));
            return;
        }
        double dist = ball.pos().distanceTo(local(p.getEyeLocation()));
        if (dist > cfg().reach()) {
            Msg.actionBar(p, "<gray>Podejdź do piłki (" + String.format("%.1f", dist) + " m, zasięg " + String.format("%.1f", cfg().reach()) + " m)");
            return;
        }
        takeBall(part);
    }

    @Override
    public boolean onSwap(Player p) {
        Participant part = players.get(p.getUniqueId());
        if (part == null) return false;
        if (stage == Stage.PLAYING && game != null && sub != Sub.FREE_THROW && p.getUniqueId().equals(holderId) && !pending.containsKey(p.getUniqueId())) pass(p, part);
        return true;
    }

    private V3 lookOf(Player p) {
        Vector d = p.getEyeLocation().getDirection();
        return arena.dirToLocal(new V3(d.getX(), d.getY(), d.getZ())).normalized();
    }

    private void shoot(Player p, Participant part) {
        long now = now();
        if (now - part.lastHit < 400_000_000L) return;
        V3 eye = local(p.getEyeLocation());
        V3 look = lookOf(p);
        V3 lookH = look.horizontal().normalized();
        int hoop = game.attackedHoop(part.team());
        V3 rim = court.rimCenter(hoop);
        V3 toRim = rim.minus(eye).horizontal();
        double dist = toRim.length();
        double cos = lookH.lengthSq() < 1e-9 || dist < 1e-6 ? 1 : lookH.dot(toRim.normalized());
        boolean aimed = dist <= 22 && cos >= Math.cos(Math.toRadians(42));

        if (aimed && cfg().basketTechniques()) {
            Move m = pickMove(part, eye, rim, dist);
            if (m != null) {
                startMove(p, part, m, hoop, rim);
                return;
            }
        }

        V3 release = eye.plus(lookH.times(0.3)).plus(new V3(0, 0.35, 0));
        ShotModel.Shot shot;
        int points = court.pointsFor(release, hoop);
        double contest = 0;
        if (aimed) {
            contest = contestOf(part, release, rim);
            boolean perfect = part.air() && part.footY() > 0.6 && Math.abs(part.vy()) < 1.2;
            boolean sprint = p.isSprinting() && !part.air();
            ShotModel.Context ctx = new ShotModel.Context(release, rim, contest, part.air(), perfect, sprint, cfg().basketAccuracy());
            shot = ShotModel.aimed(ballSpec(), ctx, rng);
            Msg.actionBar(p, (perfect ? "<green><bold>Idealny rzut! " : "<gold>Rzut ") + "<gray>" + String.format("%.1f", dist) + " m" + (contest > 0.4 ? " <red>(obrońca!)" : "") + (points == 3 ? " <aqua>za 3" : ""));
        } else {
            double power = 7.5; // rzut „na oko": moc stała, kierunek z patrzenia
            V3 aimDir = look.y() < 0.15 ? new V3(look.x(), 0.55, look.z()).normalized() : look;
            shot = ShotModel.free(aimDir, power, rng);
            Msg.actionBar(p, "<gray>Rzut bez celowania (patrz w stronę kosza)");
        }
        releaseShot(part, release, shot.velocity(), hoop, points, contest, null);
    }

    /** Wspólne wypuszczenie piłki do kosza: stan gry, piłka, blokady i ewentualny faul na rzucającym. */
    private void releaseShot(Participant part, V3 release, V3 velocity, int hoop, int points, double contest, String label) {
        game.shoot(hoop, points);
        holderId = null;
        technique = label;
        ball.set(release, velocity);
        long now = now();
        part.lastHit = now;
        part.pickupLockUntil = now + 900_000_000L;
        shotAge = 0;
        Player p = part.player();
        if (p != null) world.playSound(worldAt(release), Sound.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.7f, 0.8f);
        shotAt.put(part.id(), now);
    }

    /** Jak bardzo obrońca przeszkadza: 0 (nikogo) do 1 (stoi tuż przed rzucającym, na linii do kosza). */
    private double contestOf(Participant shooter, V3 release, V3 rim) {
        double best = 0;
        V3 toRim = rim.minus(release).horizontal().normalized();
        for (Participant o : players.values()) {
            if (o.team() == shooter.team()) continue;
            Player op = o.player();
            if (op == null) continue;
            V3 pos = local(op.getLocation());
            V3 d = new V3(pos.x() - release.x(), 0, pos.z() - release.z());
            double dist = d.length();
            if (dist > 2.4) continue;
            double inFront = dist < 0.6 ? 1 : Math.max(0, d.normalized().dot(toRim));
            double c = Math.max(0, Math.min(1, 1 - (dist - 0.4) / 2.0)) * (0.55 + 0.45 * inFront);
            if (o.air()) c = Math.min(1, c + 0.25);
            best = Math.max(best, c);
        }
        return best;
    }

    // ---------- dwutakt i wsad ----------

    /** Prędkość gracza w stronę obręczy (m/s). */
    private double towardRim(Participant part, V3 eye, V3 rim, double dist) {
        double dx = rim.x() - eye.x();
        double dz = rim.z() - eye.z();
        return dist < 1e-6 ? 0 : (part.vx() * dx + part.vz() * dz) / dist;
    }

    /**
     * Czy rzut z biegu pod kosz jest dwutaktem albo wsadem. Dwutakt wymaga biegu w stronę kosza. Wsad jest trudniejszy: trzeba być w wyskoku
     * (skok + LPM), biec w stronę kosza z prędkością co najmniej 2,2 m/s, być bliżej niż 2,6 m i nie odpoczywać po poprzednim wsadzie.
     */
    private Move pickMove(Participant part, V3 eye, V3 rim, double dist) {
        double sp = Math.hypot(part.vx(), part.vz());
        double toward = towardRim(part, eye, rim, dist);
        boolean running = sp >= 1.8 && toward >= 1.2;
        if (dist <= DUNK_RANGE && part.air() && part.footY() >= 0.25 && toward >= 2.2) {
            Long ready = dunkReadyAt.get(part.id());
            if (ready == null || now() >= ready) return Move.DUNK;
            Player p = part.player();
            if (p != null) Msg.actionBar(p, "<gray>Wsad odpoczywa jeszcze " + (int) Math.ceil((ready - now()) / (double) SECOND) + " s: zamiast niego zwykły dwutakt");
            return Move.LAYUP;
        }
        if (dist <= LAYUP_RANGE && (running || (part.air() && toward >= 1.2))) return Move.LAYUP;
        return null;
    }

    private void startMove(Player p, Participant part, Move move, int hoop, V3 rim) {
        long now = now();
        part.lastHit = now;
        V3 eye = local(p.getEyeLocation());
        double dist = Math.hypot(rim.x() - eye.x(), rim.z() - eye.z());
        double quality = DunkModel.quality(part.footY(), towardRim(part, eye, rim, dist), dist, DUNK_RANGE);
        pending.put(part.id(), new Pending(move, hoop, now + (move == Move.DUNK ? 400_000_000L : 300_000_000L), quality));
        V3 feet = local(p.getLocation());
        V3 toRim = new V3(rim.x() - feet.x(), 0, rim.z() - feet.z()).normalized();
        V3 w = arena.dirToWorld(toRim);
        double k = move == Move.DUNK ? 0.38 : 0.30;
        double up = move == Move.DUNK ? 0.60 : 0.42;
        try {
            p.setVelocity(new Vector(w.x() * k, up, w.z() * k));
        } catch (RuntimeException ignored) {
            // skok jest tylko oprawą akcji: rzut zostanie wykonany tak czy inaczej
        }
        if (move == Move.DUNK) {
            double contest = contestOf(part, eye.plus(new V3(0, 0.35, 0)), rim);
            long percent = Math.round(100 * DunkModel.chance(quality, contest, cfg().basketDunkDifficulty()));
            Msg.actionBar(p, "<gold><bold>Wsad!</bold> <gray>szansa ok. " + percent + "%" + (contest > 0.4 ? " <red>(obrońca!)" : ""));
        } else {
            Msg.actionBar(p, "<gold><bold>Dwutakt!");
        }
        world.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.6f, move == Move.DUNK ? 0.8f : 1.3f);
    }

    private void runPending() {
        if (pending.isEmpty()) return;
        long now = now();
        for (Map.Entry<UUID, Pending> e : new ArrayList<>(pending.entrySet())) {
            if (now < e.getValue().due()) continue;
            pending.remove(e.getKey());
            Participant part = players.get(e.getKey());
            Player p = part == null ? null : part.player();
            if (p == null || !e.getKey().equals(holderId) || game.phase() != BasketGame.Phase.LIVE) continue; // piłkę ktoś zabrał albo zagwizdano
            finishMove(p, part, e.getValue());
        }
    }

    private void finishMove(Player p, Participant part, Pending move) {
        V3 eye = local(p.getEyeLocation());
        V3 look = lookOf(p);
        V3 lookH = look.horizontal().normalized();
        V3 rim = court.rimCenter(move.hoop());
        double dist = Math.hypot(rim.x() - eye.x(), rim.z() - eye.z());
        V3 release = eye.plus(lookH.times(0.3)).plus(new V3(0, 0.35, 0));
        double contest = contestOf(part, release, rim);
        if (move.move() == Move.DUNK && dist <= DUNK_REACH) {
            slam(p, part, move.hoop(), rim, release, contest, move.quality());
            return;
        }
        // dwutakt (albo wsad, do którego gracz nie dolecił): miękki rzut z bliska, bardzo celny
        double boost = cfg().basketAccuracy() * cfg().basketLayupBoost();
        ShotModel.Context ctx = new ShotModel.Context(release, rim, contest, true, false, false, boost);
        ShotModel.Shot shot = ShotModel.aimed(ballSpec(), ctx, rng);
        Msg.actionBar(p, "<gold>Dwutakt! <gray>" + String.format("%.1f", dist) + " m" + (contest > 0.4 ? " <red>(obrońca!)" : ""));
        releaseShot(part, release, shot.velocity(), move.hoop(), 2, contest, "Dwutakt");
    }

    /**
     * Wsad: piłka jest wbijana w obręcz z góry. Obrońca w wyskoku tuż obok może go zablokować, a sam wsad wpada tylko z szansą zależną od najścia
     * ({@link DunkModel}); niecelny odbija się od przedniej części obręczy. Po próbie gracz odpoczywa (patrz `wsad-odpoczynek-sekundy`).
     */
    private void slam(Player p, Participant part, int hoop, V3 rim, V3 release, double contest, double quality) {
        game.shoot(hoop, 2);
        holderId = null;
        long now = now();
        part.lastHit = now;
        part.pickupLockUntil = now + 900_000_000L;
        shotAt.put(part.id(), now);
        dunkReadyAt.put(part.id(), now + (long) (cfg().basketDunkRestSeconds() * SECOND));
        shotAge = 0;
        Participant def = closestOpponent(part, release, 1.8);
        V3 away = new V3(release.x() - rim.x(), 0, release.z() - rim.z()).normalized();
        if (def != null && rng.nextDouble() < DunkModel.blockChance(def.air(), contest)) {
            ball.set(release, new V3(away.x() * 6, 1.5, away.z() * 6));
            game.deflect(def.team());
            Player dp = def.player();
            if (dp != null) Msg.actionBar(dp, "<gold><bold>BLOK wsadu!");
            Msg.actionBar(p, "<red>Wsad zablokowany!");
            world.playSound(worldAt(release), Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.6f, 1.6f);
            broadcast(teamColor(def.team()) + Msg.esc(def.name()) + "<gray> blokuje wsad!");
            return;
        }
        Location l = worldAt(new V3(rim.x(), rim.y(), rim.z()));
        if (rng.nextDouble() >= DunkModel.chance(quality, contest, cfg().basketDunkDifficulty())) {
            // niecelny wsad: piłka uderza w przód obręczy i odbija się do rzucającego (zbiórka dla każdego)
            ball.set(new V3(rim.x() + away.x() * 0.4, rim.y() + 0.3, rim.z() + away.z() * 0.4), new V3(away.x() * 3.0, 1.8, away.z() * 3.0 + (rng.nextDouble() - 0.5) * 1.5));
            world.playSound(l, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.8f, 1.2f);
            Msg.actionBar(p, "<red>Wsad nie wszedł! <gray>Piłka odbiła się od obręczy.");
            broadcast(teamColor(part.team()) + Msg.esc(part.name()) + "<gray> próbuje wsadu, ale piłka odbija się od obręczy.");
            return;
        }
        technique = "Wsad";
        double jitter = (rng.nextDouble() - 0.5) * 0.04;
        ball.set(new V3(rim.x() + jitter, rim.y() + 0.55, rim.z() + jitter), new V3(0, -8, 0));
        world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.5f, 1.6f);
        world.playSound(l, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.7f, 0.6f);
        world.spawnParticle(Particle.EXPLOSION, l, 1);
        Msg.actionBar(p, "<gold><bold>WSAD!");
    }

    // ---------- podanie, przechwyt, blok ----------

    private void pass(Player p, Participant part) {
        long now = now();
        if (now - part.lastHit < 300_000_000L) return;
        V3 eye = local(p.getEyeLocation());
        V3 look = lookOf(p);
        V3 lookH = look.horizontal().normalized();
        V3 from = eye.plus(lookH.times(0.35)).plus(new V3(0, -0.25, 0));

        Participant mate = null;
        double bestScore = Double.MAX_VALUE;
        for (Participant o : players.values()) {
            if (o == part || o.team() != part.team()) continue;
            Player op = o.player();
            if (op == null) continue;
            V3 pos = local(op.getLocation());
            V3 d = new V3(pos.x() - eye.x(), 0, pos.z() - eye.z());
            double dist = d.length();
            if (dist < 1.8 || dist > 32) continue;
            double cos = lookH.dot(d.normalized());
            if (cos < Math.cos(Math.toRadians(38))) continue;
            double score = Math.acos(Math.min(1, cos)) * 4 + dist * 0.02;
            if (score < bestScore) {
                bestScore = score;
                mate = o;
            }
        }
        V3 velocity;
        if (mate != null) {
            V3 target = leadTarget(mate, from);
            V3 delta = target.minus(from);
            double dist = delta.horizontalLength();
            Ballistics.Aim aim = Ballistics.solveSpeed(ballSpec(), from.y(), dist, target.y(), 9, 22);
            if (aim == null) aim = Ballistics.solveSpeed(ballSpec(), from.y(), dist, target.y(), 28, 24);
            if (aim == null) aim = new Ballistics.Aim(22, 30, false);
            velocity = aim.velocity(delta);
            Player mp = mate.player();
            if (mp != null) Msg.actionBar(mp, "<green>Podanie od " + Msg.esc(part.name()));
        } else {
            velocity = new V3(lookH.x() * 11, 2.2, lookH.z() * 11);
        }
        game.pass();
        holderId = null;
        if (mate != null) {
            passTarget = mate.id();
            passTargetUntil = now + 2_500_000_000L;
        } else {
            passTarget = null;
            Msg.actionBar(p, "<gray>Brak kolegi w polu widzenia: piłka poleciała przed Ciebie. Patrz na kolegę, żeby mu podać.");
        }
        ball.set(from, velocity);
        part.lastHit = now;
        part.pickupLockUntil = now + 450_000_000L;
        world.playSound(worldAt(from), Sound.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.6f, 1.3f);
    }

    /** Miejsce, w którym piłka ma spotkać kolegę: jego klatka piersiowa z wyprzedzeniem o czas lotu. */
    private V3 leadTarget(Participant mate, V3 from) {
        V3 pos = local(mate.player().getLocation());
        double dist = Math.hypot(pos.x() - from.x(), pos.z() - from.z());
        double t = dist / 13.0;
        double lead = 0.6 * t;
        double tx = pos.x() + mate.vx() * lead;
        double tz = pos.z() + mate.vz() * lead;
        // nie wyprzedzaj za daleko poza boisko
        tx = Math.max(0.3, Math.min(arena.length() - 0.3, tx));
        tz = Math.max(0.3, Math.min(arena.width() - 0.3, tz));
        return new V3(tx, Math.max(0, pos.y()) + 1.3, tz);
    }

    /**
     * Wyrywanie piłki trzymającemu ją przeciwnikowi: szansa zależy od sytuacji (patrz {@link StealModel}). Nieudana próba z przodu jest czysta;
     * faulem kończy się dopiero kontakt: zahaczenie od tyłu, uderzenie w ręce rzucającego, wejście w osłoniętą piłkę.
     */
    private void steal(Player p, Participant part) {
        long now = now();
        if (now - part.lastSteal < STEAL_COOLDOWN) return;
        Participant holder = players.get(holderId);
        if (holder == null || holder.team() == part.team()) return;
        Player hp = holder.player();
        if (hp == null) return;
        V3 mine = local(p.getLocation());
        V3 theirs = local(hp.getLocation());
        double dxz = Math.hypot(mine.x() - theirs.x(), mine.z() - theirs.z());
        if (dxz > STEAL_RANGE || Math.abs(mine.y() - theirs.y()) > 1.8) {
            part.lastSteal = now - STEAL_COOLDOWN + INVALID_CLICK_COOLDOWN;
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.4f, 1.4f);
            Msg.actionBar(p, "<gray>Za daleko od kozłującego (" + String.format("%.1f", dxz) + " m): przechwyt działa do " + String.format("%.0f", STEAL_RANGE) + " m");
            return;
        }
        V3 lookH = lookOf(p).horizontal().normalized();
        V3 to = new V3(theirs.x() - mine.x(), 0, theirs.z() - mine.z()).normalized();
        if (lookH.dot(to) < 0.2) {
            part.lastSteal = now - STEAL_COOLDOWN + INVALID_CLICK_COOLDOWN;
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.4f, 1.4f);
            Msg.actionBar(p, "<gray>Patrz na kozłującego, żeby wyrwać mu piłkę");
            return;
        }
        part.lastSteal = now;
        // czy stoję za plecami kozłującego: jego kierunek patrzenia jest przeciwny do kierunku ode mnie
        Vector hl = hp.getLocation().getDirection().setY(0);
        V3 holderLook = hl.lengthSquared() < 1e-6 ? V3.ZERO : arena.dirToLocal(new V3(hl.getX(), 0, hl.getZ())).normalized();
        V3 holderToMe = new V3(mine.x() - theirs.x(), 0, mine.z() - theirs.z()).normalized();
        boolean behind = holderLook.dot(holderToMe) < -0.2;
        boolean shooting = pending.containsKey(holder.id());
        StealModel.Situation s = new StealModel.Situation(dxz, dribbleHeight < 0.5, behind, hp.isSneaking(), hp.isSprinting(), holder.air() || shooting, part.air());
        double chance = StealModel.chance(cfg().basketStealChance(), s);
        if (rng.nextDouble() < chance && game.steal(part.team(), part.id())) {
            holderId = part.id();
            dribblePhase = 0;
            pending.remove(holder.id());
            holder.pickupLockUntil = now + 700_000_000L;
            world.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.5f);
            Msg.actionBar(p, "<green><bold>Przechwyt!");
            Msg.actionBar(hp, "<red>Straciłeś piłkę!");
            broadcast(teamColor(part.team()) + Msg.esc(part.name()) + "<gray> przechwytuje piłkę od " + teamColor(holder.team()) + Msg.esc(holder.name()));
            return;
        }
        if (foulsOn() && roll(StealModel.foulChance(s))) {
            String why = shooting || holder.air() ? "uderzenie po rękach rzucającego" : behind ? "zahaczenie od tyłu" : "wejście w osłoniętą piłkę";
            callFoul(shooting ? FoulType.SHOOTING : FoulType.PERSONAL, part, holder, theirs, why);
            return;
        }
        Msg.actionBar(p, "<gray>Przechwyt nieudany" + (hp.isSneaking() ? " (osłania piłkę)" : "") + ". <dark_gray>Od tyłu i przy niskiej piłce idzie łatwiej.");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.6f, 1.0f);
    }

    /**
     * LPM przy piłce, której nikt nie trzyma. Rzut rywali w locie to blok, a każda inna piłka (podanie kolegi, zbiórka, podanie rywali, luźna piłka)
     * jest łapana, tak jak podpowiada instynkt. Machnięcie, gdy piłka jest już poza zasięgiem, a rzucający stoi tuż przed obrońcą, to faul.
     */
    private void contest(Player p, Participant part) {
        long now = now();
        if (now - part.lastHit < 350_000_000L) return;
        V3 eye = local(p.getEyeLocation());
        V3 look = lookOf(p);
        V3 b = ball.pos();
        V3 seen = b.minus(ball.vel().times(0.06));
        double dist = Math.min(b.distanceTo(eye), seen.distanceTo(eye));
        boolean inCone = dist <= 1.2 || b.minus(eye).normalized().dot(look) >= 0.15 || seen.minus(eye).normalized().dot(look) >= 0.15;
        if (dist > cfg().reach() || !inCone) {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.4f, 1.5f);
            if (!lateContest(p, part, look)) {
                Msg.actionBar(p, dist > cfg().reach() ? "<gray>Piłka za daleko (" + String.format("%.1f", dist) + " m, zasięg " + String.format("%.1f", cfg().reach()) + " m)" : "<gray>Patrz na piłkę");
            }
            return;
        }
        boolean block = game.shotInFlight() && game.shotTeam() != part.team();
        if (block) {
            part.lastHit = now;
            V3 lookH = look.horizontal().normalized();
            double speed = 7 + rng.nextDouble() * 2;
            ball.setVel(new V3(lookH.x() * speed, -1 + rng.nextDouble() * 2, lookH.z() * speed));
            game.deflect(part.team());
            Location l = worldAt(b);
            world.playSound(l, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.5f, 1.7f);
            world.spawnParticle(Particle.CRIT, l, 8, 0.15, 0.15, 0.15, 0.2);
            Msg.actionBar(p, "<gold><bold>BLOK!");
            broadcast(teamColor(part.team()) + Msg.esc(part.name()) + "<gray> blokuje rzut!");
            return;
        }
        if (now < part.pickupLockUntil) return;
        if (!canCatch()) {
            Msg.actionBar(p, "<gray>Rzut w locie możesz tylko zablokować (LPM tuż przy piłce). Złapiesz ją po odbiciu od obręczy.");
            return;
        }
        part.lastHit = now - 250_000_000L;
        if (!takeBall(part)) Msg.actionBar(p, "<gray>Tej piłki teraz nie weźmiesz.");
    }

    /** Machnięcie po tym, jak piłka wyleciała z rąk rzucającego: jeśli rzucający stoi tuż przed obrońcą, to uderzenie po rękach. */
    private boolean lateContest(Player p, Participant part, V3 look) {
        if (!foulsOn() || !game.shotInFlight()) return false;
        long now = now();
        V3 mine = local(p.getLocation());
        V3 lookH = look.horizontal().normalized();
        for (Participant o : players.values()) {
            if (o.team() == part.team() || o.player() == null || game.shotTeam() != o.team()) continue;
            Long at = shotAt.get(o.id());
            if (at == null || now - at > 700_000_000L) continue;
            V3 op = local(o.player().getLocation());
            if (Math.hypot(op.x() - mine.x(), op.z() - mine.z()) > 1.6) continue;
            V3 to = new V3(op.x() - mine.x(), 0, op.z() - mine.z()).normalized();
            if (lookH.dot(to) < 0.4) continue;
            if (!roll(1.0)) return false;
            callFoul(FoulType.SHOOTING, part, o, new V3(op.x(), 0, op.z()), "uderzenie po rękach rzucającego (spóźniony blok)");
            return true;
        }
        return false;
    }

    // ---------- podpowiedzi ----------

    /** Pokazuje graczowi podpowiedź o sterowaniu, nie częściej niż raz na `seconds` sekund dla danego klucza. */
    private void hint(Participant part, String key, String text, int seconds) {
        long now = now();
        Map<String, Long> shown = hintsShown.computeIfAbsent(part.id(), k -> new HashMap<>());
        Long last = shown.get(key);
        if (last != null && now - last < seconds * SECOND) return;
        Player p = part.player();
        if (p == null) return;
        shown.put(key, now);
        Msg.actionBar(p, text);
    }

    /** Co pół sekundy podpowiada graczom, co zrobić w ich sytuacji (przy kozłującym rywalu: przechwyt, przy piłce w zasięgu: złap). */
    private void coachHints() {
        if (++hintTicks < 10) return;
        hintTicks = 0;
        if (ball == null || game.phase() != BasketGame.Phase.LIVE) return;
        for (Participant part : players.values()) {
            Player p = part.player();
            if (p == null) continue;
            V3 f = local(p.getLocation());
            if (holderId != null) {
                Participant h = players.get(holderId);
                if (h == null || h.team() == part.team() || h.player() == null) continue;
                V3 hp = local(h.player().getLocation());
                if (Math.hypot(hp.x() - f.x(), hp.z() - f.z()) < 2.6) hint(part, "steal", "<gray>Przechwyt: <white>LPM</white> przy kozłującym (do " + String.format("%.0f", STEAL_RANGE) + " m, patrząc na niego). Od tyłu idzie łatwiej.", 12);
            } else if (canCatch()) {
                double d = ball.pos().distanceTo(f.plus(new V3(0, 1.62, 0)));
                if (d < cfg().reach() && d > cfg().basketPickupRadius()) hint(part, "catch", "<gray>Piłka w zasięgu: <white>LPM</white> ją łapie (albo po prostu do niej podejdź).", 10);
            }
        }
    }

    // ---------- rzuty osobiste ----------

    private V3 ftSpot(int hoop) {
        return new V3(hoop == 0 ? FT_LINE : arena.length() - FT_LINE, 0, arena.width() / 2.0);
    }

    /** Ustawia graczy do rzutów osobistych: wykonawca na linii, reszta stoi z boku, daleko od kosza. */
    private void beginFreeThrows() {
        Team team = game.freeThrowTeam();
        ftHoop = game.attackedHoop(team);
        Participant shooter = players.get(game.freeThrowShooter());
        if (shooter == null || shooter.player() == null) {
            List<Participant> mates = team(team);
            if (mates.isEmpty()) return;
            shooter = mates.get(0);
            game.setFreeThrowShooter(shooter.id());
        }
        sub = Sub.FREE_THROW;
        pending.clear();
        holderId = null;
        V3 rim = court.rimCenter(ftHoop);
        V3 line = ftSpot(ftHoop);
        V3 facing = new V3(rim.x() - line.x(), 0, rim.z() - line.z());
        teleport(shooter, spot(line, facing));
        List<Participant> others = new ArrayList<>();
        for (Participant o : players.values()) if (o != shooter) others.add(o);
        for (int i = 0; i < others.size(); i++) {
            double u = ftHoop == 0 ? FT_LINE + 5 : arena.length() - FT_LINE - 5;
            double v = arena.width() * (i + 1.0) / (others.size() + 1.0);
            V3 at = new V3(u, 0, v);
            teleport(others.get(i), spot(at, new V3(rim.x() - u, 0, rim.z() - v)));
        }
        prepareFreeThrow();
    }

    private void prepareFreeThrow() {
        ftReleased = false;
        ftWait = 1.5;
        ftTimer = 10;
        ftFlight = 0;
        ftSensor = court.sensor(ftHoop);
        holdFreeThrowBall();
        Participant shooter = players.get(game.freeThrowShooter());
        Player p = shooter == null ? null : shooter.player();
        if (p != null) {
            Msg.title(p, "<yellow><bold>Rzut osobisty " + game.freeThrowNumber() + "/" + game.freeThrowsTotal(), "<gray>LPM: rzut", 0.1, 1.6, 0.3);
            Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }
        broadcast(coloredTeam(game.freeThrowTeam()) + " <gray>rzut osobisty " + game.freeThrowNumber() + "/" + game.freeThrowsTotal() + ": " + teamColor(game.freeThrowTeam())
                + (shooter == null ? "?" : Msg.esc(shooter.name())));
    }

    private void holdFreeThrowBall() {
        Participant shooter = players.get(game.freeThrowShooter());
        Player p = shooter == null ? null : shooter.player();
        if (p == null || ball == null) return;
        V3 eye = local(p.getEyeLocation());
        V3 lookH = lookOf(p).horizontal().normalized();
        ball.set(eye.plus(lookH.times(0.35)).plus(new V3(0, -0.15, 0)), V3.ZERO);
    }

    private void releaseFreeThrow(Participant shooter) {
        Player p = shooter.player();
        if (p == null) return;
        V3 eye = local(p.getEyeLocation());
        V3 rim = court.rimCenter(ftHoop);
        V3 toRim = new V3(rim.x() - eye.x(), 0, rim.z() - eye.z()).normalized();
        V3 release = eye.plus(toRim.times(0.3)).plus(new V3(0, 0.35, 0));
        ShotModel.Context ctx = new ShotModel.Context(release, rim, 0, false, false, false, cfg().basketAccuracy() * cfg().basketFreeThrowAccuracy());
        ShotModel.Shot shot = ShotModel.aimed(ballSpec(), ctx, rng);
        ball.set(release, shot.velocity());
        ftReleased = true;
        ftFlight = 0;
        world.playSound(worldAt(release), Sound.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.7f, 0.9f);
    }

    private void tickFreeThrow(double dt) {
        Participant shooter = players.get(game.freeThrowShooter());
        if (shooter == null || shooter.player() == null) {
            List<Participant> mates = team(game.freeThrowTeam());
            if (mates.isEmpty()) return;
            game.setFreeThrowShooter(mates.get(0).id());
            beginFreeThrows();
            return;
        }
        if (!ftReleased) {
            holdFreeThrowBall();
            ftWait -= dt;
            ftTimer -= dt;
            if (ftTimer <= 0) releaseFreeThrow(shooter);
            return;
        }
        ftFlight += dt;
        boolean[] made = {false};
        physics.step(ball, dt, this::onContact, (from, to) -> {
            if (ftSensor.update(from, to)) made[0] = true;
        });
        unstick(dt);
        V3 p = ball.pos();
        boolean out = p.x() < -0.5 || p.x() > arena.length() + 0.5 || p.z() < -0.5 || p.z() > arena.width() + 0.5;
        if (made[0]) finishFreeThrow(true);
        else if ((ball.onFloor() && p.y() < ballSpec().radius() + 0.1) || ftFlight > 5 || out) finishFreeThrow(false);
    }

    private void finishFreeThrow(boolean made) {
        handle(game.freeThrowResult(made));
        if (game.ended()) return;
        switch (game.phase()) {
            case FREE_THROW -> prepareFreeThrow();
            case DEAD -> {
                // ostatni rzut wpadł: wznawia rywal zza linii końcowej tego kosza
                pendingSpot = court.baselineSpot(ftHoop);
                sub = Sub.SCORED;
                subTimer = 1.6;
            }
            case LIVE -> sub = Sub.PLAY; // zbiórka po chybionym ostatnim rzucie: piłka jest w grze
            default -> { }
        }
        refreshHud();
    }

    // ---------- tablica ----------

    private static String mmss(double seconds) {
        int s = (int) Math.ceil(Math.max(0, seconds));
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private void updateBar() {
        if (hud == null || game == null) return;
        if (sub == Sub.FREE_THROW) {
            hud.bar("<yellow>Rzut osobisty " + game.freeThrowNumber() + "/" + game.freeThrowsTotal() + ": " + teamColor(game.freeThrowTeam()) + teamName(game.freeThrowTeam()),
                    ftReleased ? 1 : Math.max(0, ftTimer) / 10.0, BossBar.Color.PURPLE);
            return;
        }
        if (game.phase() == BasketGame.Phase.DEAD) {
            hud.bar("<yellow>Wznowienie: " + teamColor(game.inbound()) + teamName(game.inbound()), 1, BossBar.Color.WHITE);
            return;
        }
        double sc = game.shotClock();
        boolean running = game.possession() != null && !game.shotInFlight();
        hud.bar((running ? "<white>" : "<gray>") + "Zegar akcji: " + (sc < 5 ? "<red>" : "") + (int) Math.ceil(Math.max(0, sc)) + " s",
                sc / cfg().basketShotClock(), sc < 5 ? BossBar.Color.RED : BossBar.Color.YELLOW);
    }

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
        l.add(game.overtime() ? "<red>Dogrywka" : (Double.isInfinite(game.clock()) ? "<gray>Do " + cfg().basketPointsToWin() + " pkt" : "<gray>Czas: <white>" + mmss(game.clock())));
        l.add(" ");
        for (Team t : Team.values()) {
            boolean has = game.possession() == t || (game.phase() == BasketGame.Phase.DEAD && game.inbound() == t);
            l.add(teamColor(t) + teamName(t) + "<gray>: <white><bold>" + game.score(t) + "</bold>" + (has ? " <yellow>●" : "") + " <dark_gray>(faule " + game.teamFouls(t) + ")");
        }
        l.add("  ");
        l.add("<gray>Zegar akcji: <white>" + (int) Math.ceil(Math.max(0, game.shotClock())));
        return l;
    }
}
