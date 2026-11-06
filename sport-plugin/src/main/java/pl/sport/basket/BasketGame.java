package pl.sport.basket;

import pl.sport.core.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Zasady koszykówki bez zależności od Bukkita: kto ma piłkę, zegar meczu i zegar 24 sekund, punkty za dwa i trzy, aut, przechwyt,
 * wznowienie po koszu i dogrywka. Silnik dostaje zdarzenia z fizyki i sterowania (podniesienie piłki, rzut, wpadnięcie do kosza, wyjście
 * poza boisko, upływ czasu) i zwraca listę zdarzeń do pokazania graczom.
 */
public final class BasketGame {
    /** DEAD: piłka martwa, czeka na wznowienie. LIVE: gra. FREE_THROW: rzuty osobiste (zegary stoją). ENDED: koniec meczu. */
    public enum Phase { DEAD, LIVE, FREE_THROW, ENDED }

    /** Rodzaj faulu: na rzucającym (rzuty osobiste), zwykły obronny (wznowienie albo rzuty po przekroczeniu limitu) i w ataku (strata piłki). */
    public enum FoulType { SHOOTING, PERSONAL, OFFENSIVE }

    /** Ostatni odgwizdany faul (do komunikatów): drużyna faulującego, poszkodowanego i ile rzutów osobistych z tego wynika. */
    public record Foul(FoulType type, UUID foulerId, Team fouler, UUID victimId, Team victim, int freeThrows) {}

    public enum Kind {
        /** Punkty dla drużyny `team` (w polu `points`). */
        SCORE,
        /** 24 sekundy minęły: piłkę dostaje druga drużyna. */
        SHOT_CLOCK,
        /** Piłka wyszła poza boisko: wznawia `team`. */
        OUT_OF_BOUNDS,
        /** Koniec czasu przy remisie: gra toczy się dalej w dogrywce. */
        OVERTIME,
        /** Koniec meczu: wygrała `team`. */
        GAME_OVER,
        /** Faul drużyny `team` (szczegóły w {@link #lastFoul()}). */
        FOUL,
        /** Rzuty osobiste dla `team`; w polu `points` ile jeszcze zostało. */
        FREE_THROWS,
        /** Gracz z drużyny `team` przekroczył limit fauli i musi opuścić mecz (patrz {@link #foulOutPlayer()}). */
        FOUL_OUT
    }

    public record Update(Kind kind, Team team, int points) {}

    /** Ustawienia. Gdy pointsToWin > 0, mecz trwa do tylu punktów i czas się nie liczy. */
    public record Config(double periodSeconds, double shotClock, double reboundShotClock, double overtimeSeconds, int pointsToWin, int bonusFouls, int foulLimit) {
        /** Ustawienia bez limitu fauli i z premią (rzuty osobiste za zwykłe faule) od piątego faulu drużyny w okresie. */
        public Config(double periodSeconds, double shotClock, double reboundShotClock, double overtimeSeconds, int pointsToWin) {
            this(periodSeconds, shotClock, reboundShotClock, overtimeSeconds, pointsToWin, 5, 0);
        }

        public static Config timed(double minutes) {
            return new Config(minutes * 60, 24, 14, 60, 0);
        }

        public static Config toPoints(int points) {
            return new Config(0, 24, 14, 0, points);
        }
    }

    private final Config cfg;
    private final Team defenderOfHoop0;
    private final int[] score = new int[2];
    private Phase phase = Phase.DEAD;
    private Team inbound;
    private double clock;
    private double shotClock;
    private boolean clockExpired;
    private boolean overtime;
    private boolean suddenDeath;

    private UUID holder;
    private Team holderTeam;
    private Team possession;
    private Team lastTouch;

    private boolean shotInFlight;
    private Team shotTeam;
    private int shotPoints;
    private int shotHoop;
    private boolean rimTouched;

    private final int[] teamFouls = new int[2];
    private final java.util.Map<UUID, Integer> personalFouls = new java.util.HashMap<>();
    private Foul lastFoul;
    private UUID foulOut;
    /** Faul na rzucającym, gdy rzut jest już w locie: rozstrzyga się, gdy piłka wpadnie albo spadnie. */
    private Foul pendingShotFoul;
    private int pendingFreeThrows;

    private Team ftTeam;
    private UUID ftShooter;
    private int ftRemaining;
    private int ftTotal;

    /** @param defenderOfHoop0 drużyna broniąca kosza o indeksie 0 (kosz 1 broni druga drużyna) */
    public BasketGame(Config cfg, Team defenderOfHoop0, Team firstInbound) {
        this.cfg = cfg;
        this.defenderOfHoop0 = defenderOfHoop0;
        this.inbound = firstInbound;
        this.clock = cfg.pointsToWin() > 0 ? Double.POSITIVE_INFINITY : cfg.periodSeconds();
        this.shotClock = cfg.shotClock();
    }

    // ---------- stan ----------

    public Phase phase() {
        return phase;
    }

    public int score(Team t) {
        return score[t.ordinal()];
    }

    /** Kto wznawia grę po przerwie (tylko w fazie DEAD). */
    public Team inbound() {
        return inbound;
    }

    public double clock() {
        return clock;
    }

    public double shotClock() {
        return shotClock;
    }

    public boolean overtime() {
        return overtime;
    }

    public UUID holder() {
        return holder;
    }

    public Team holderTeam() {
        return holderTeam;
    }

    /** Drużyna, która kontroluje piłkę (trzyma ją albo właśnie podała); null, gdy piłka jest luźna. */
    public Team possession() {
        return possession;
    }

    public Team lastTouch() {
        return lastTouch;
    }

    public boolean shotInFlight() {
        return shotInFlight;
    }

    public Team shotTeam() {
        return shotTeam;
    }

    public int shotPoints() {
        return shotPoints;
    }

    /** Drużyna broniąca danego kosza. */
    public Team defender(int hoop) {
        return hoop == 0 ? defenderOfHoop0 : defenderOfHoop0.other();
    }

    /** Kosz, na który atakuje drużyna (przeciwny do własnego). */
    public int attackedHoop(Team t) {
        return defender(0) == t ? 1 : 0;
    }

    public boolean ended() {
        return phase == Phase.ENDED;
    }

    public int teamFouls(Team t) {
        return teamFouls[t.ordinal()];
    }

    public int personalFouls(UUID player) {
        return personalFouls.getOrDefault(player, 0);
    }

    public Foul lastFoul() {
        return lastFoul;
    }

    /** Gracz, który właśnie przekroczył limit fauli (po zdarzeniu FOUL_OUT). */
    public UUID foulOutPlayer() {
        return foulOut;
    }

    /** Drużyna wykonująca rzuty osobiste (faza FREE_THROW). */
    public Team freeThrowTeam() {
        return ftTeam;
    }

    public UUID freeThrowShooter() {
        return ftShooter;
    }

    /** Zmienia wykonawcę rzutów osobistych (np. gdy poszkodowany wyszedł z meczu). */
    public void setFreeThrowShooter(UUID id) {
        if (phase == Phase.FREE_THROW) ftShooter = id;
    }

    /** Ile rzutów osobistych jeszcze zostało (z bieżącym włącznie). */
    public int freeThrowsLeft() {
        return ftRemaining;
    }

    /** Który z kolei jest bieżący rzut osobisty (od 1). */
    public int freeThrowNumber() {
        return ftTotal - ftRemaining + 1;
    }

    public int freeThrowsTotal() {
        return ftTotal;
    }

    /** Czy trwa rzut z odgwizdanym faulem na rzucającym (piłka jeszcze w locie). */
    public boolean pendingShotFoul() {
        return pendingShotFoul != null;
    }

    private boolean shotClockRuns() {
        return phase == Phase.LIVE && !shotInFlight && possession != null;
    }

    // ---------- piłka ----------

    /** Gracz drużyny `team` bierze piłkę. Zwraca false, gdy teraz nie wolno (ktoś ją trzyma albo to nie ta drużyna wznawia). */
    public boolean pickup(Team team, UUID player) {
        if (phase == Phase.ENDED || phase == Phase.FREE_THROW) return false;
        if (pendingShotFoul != null) return false; // rzut z faulem musi się rozstrzygnąć, zanim ktoś złapie piłkę
        if (phase == Phase.DEAD) {
            if (team != inbound) return false;
            phase = Phase.LIVE;
            shotClock = cfg.shotClock();
        } else {
            if (holder != null) return false;
            if (possession != team) shotClock = cfg.shotClock();
            else if (rimTouched) shotClock = Math.max(shotClock, cfg.reboundShotClock());
        }
        holder = player;
        holderTeam = team;
        possession = team;
        lastTouch = team;
        shotInFlight = false;
        rimTouched = false;
        return true;
    }

    /** Trzymający odchodzi z piłką (rozłączył się): piłka staje się luźna. */
    public void drop() {
        if (holder == null) return;
        holder = null;
        holderTeam = null;
        possession = null;
    }

    /** Przechwyt: gracz drużyny `thief` wyrywa piłkę trzymającemu ją rywalowi. */
    public boolean steal(Team thief, UUID player) {
        if (phase != Phase.LIVE || holder == null || holderTeam == thief) return false;
        holder = player;
        holderTeam = thief;
        possession = thief;
        lastTouch = thief;
        shotClock = cfg.shotClock();
        return true;
    }

    /** Trzymający podaje piłkę (zegar 24 s biegnie dalej, piłka nadal należy do drużyny). */
    public void pass() {
        if (phase != Phase.LIVE || holder == null) return;
        holder = null;
        lastTouch = holderTeam;
        possession = holderTeam;
    }

    /** Trzymający rzuca do kosza `hoop`; rzut jest wart `points`. Zegar 24 s stoi, aż piłka zostanie dotknięta albo trafi w obręcz. */
    public void shoot(int hoop, int points) {
        if (phase != Phase.LIVE || holder == null) return;
        shotTeam = holderTeam;
        shotHoop = hoop;
        shotPoints = points;
        shotInFlight = true;
        rimTouched = false;
        lastTouch = holderTeam;
        possession = holderTeam;
        holder = null;
    }

    /** Piłkę dotknął (np. zablokował lub odbił) gracz drużyny `team` bez zabierania jej: rzut w locie przestaje być rzutem. */
    public void deflect(Team team) {
        if (phase != Phase.LIVE) return;
        shotInFlight = false;
        lastTouch = team;
        possession = null;
    }

    /** Czy piłka rzutu w locie dotknęła już obręczy albo tablicy (od tej chwili można ją łapać). */
    public boolean rimTouched() {
        return rimTouched;
    }

    public void rimTouch() {
        if (shotInFlight) rimTouched = true;
    }

    /** Rzut skończył się bez punktu (piłka upadła na podłogę): wraca zwykła gra z luźną piłką. */
    public List<Update> shotResolved() {
        List<Update> out = new ArrayList<>();
        shotInFlight = false;
        possession = null;
        if (pendingShotFoul != null) {
            // rzut z faulem nie wpadł: rzuty osobiste (dwa albo trzy, zależnie od rodzaju rzutu)
            Foul f = pendingShotFoul;
            pendingShotFoul = null;
            startFreeThrows(out, f.victim(), f.victimId(), pendingFreeThrows);
            return out;
        }
        return resolveExpiry();
    }

    // ---------- zdarzenia z boiska ----------

    /** Piłka wpadła do kosza o indeksie `hoop`. */
    public List<Update> basket(int hoop) {
        List<Update> out = new ArrayList<>();
        if (phase != Phase.LIVE) return out;
        Team scorer = defender(hoop).other();
        int pts = shotInFlight && shotTeam == scorer && shotHoop == hoop ? shotPoints : 2;
        score[scorer.ordinal()] += pts;
        out.add(new Update(Kind.SCORE, scorer, pts));
        Foul andOne = pendingShotFoul != null && pendingShotFoul.victim() == scorer ? pendingShotFoul : null;
        pendingShotFoul = null;

        holder = null;
        holderTeam = null;
        possession = null;
        shotInFlight = false;
        rimTouched = false;
        phase = Phase.DEAD;
        inbound = scorer.other();

        if (suddenDeath || (cfg.pointsToWin() > 0 && score[scorer.ordinal()] >= cfg.pointsToWin())) {
            end(out, scorer);
        } else if (andOne != null) {
            // kosz mimo faulu: punkty się liczą i dochodzi jeden rzut osobisty
            lastFoul = new Foul(andOne.type(), andOne.foulerId(), andOne.fouler(), andOne.victimId(), andOne.victim(), 1);
            startFreeThrows(out, scorer, andOne.victimId(), 1);
        } else if (clockExpired) {
            out.addAll(resolveExpiry());
        }
        return out;
    }

    /** Piłka wyszła poza boisko; wznawia drużyna przeciwna do tej, która dotknęła ją ostatnia. */
    public List<Update> outOfBounds() {
        List<Update> out = new ArrayList<>();
        if (phase != Phase.LIVE) return out;
        Team toss = lastTouch != null ? lastTouch.other() : (possession != null ? possession.other() : Team.A);
        holder = null;
        holderTeam = null;
        possession = null;
        shotInFlight = false;
        rimTouched = false;
        if (pendingShotFoul != null) {
            // rzut z faulem wyleciał poza boisko: rzuty osobiste jak po chybionym rzucie
            Foul f = pendingShotFoul;
            pendingShotFoul = null;
            out.add(new Update(Kind.OUT_OF_BOUNDS, toss, 0));
            startFreeThrows(out, f.victim(), f.victimId(), pendingFreeThrows);
            return out;
        }
        phase = Phase.DEAD;
        inbound = toss;
        out.add(new Update(Kind.OUT_OF_BOUNDS, toss, 0));
        out.addAll(resolveExpiry());
        return out;
    }

    /** Upływ czasu (sekundy), wołany co tick. Zegary biegną tylko, gdy piłka jest w grze. */
    public List<Update> tick(double dt) {
        List<Update> out = new ArrayList<>();
        if (phase != Phase.LIVE) return out;
        if (shotClockRuns()) {
            shotClock -= dt;
            if (shotClock <= 0) {
                Team toss = possession.other();
                holder = null;
                holderTeam = null;
                possession = null;
                phase = Phase.DEAD;
                inbound = toss;
                shotClock = cfg.shotClock();
                out.add(new Update(Kind.SHOT_CLOCK, toss, 0));
            }
        }
        if (cfg.pointsToWin() == 0 && !clockExpired) {
            clock -= dt;
            if (clock <= 0) {
                clock = 0;
                clockExpired = true;
            }
        }
        out.addAll(resolveExpiry());
        return out;
    }

    // ---------- faule i rzuty osobiste ----------

    /**
     * Odgwizduje faul. SHOOTING: faul na rzucającym; gdy rzut jest już w locie, rozstrzyga się po jego wyniku (kosz daje dodatkowy rzut osobisty,
     * pudło dwa albo trzy), a w przeciwnym razie rzuty osobiste idą od razu. PERSONAL: zwykły faul, piłka dla poszkodowanych, a od limitu fauli
     * drużyny w okresie dwa rzuty osobiste. OFFENSIVE: strata piłki bez rzutów.
     */
    public List<Update> foul(FoulType type, UUID foulerId, Team fouler, UUID victimId, Team victim) {
        List<Update> out = new ArrayList<>();
        if (phase != Phase.LIVE) return out;
        int personal = personalFouls.merge(foulerId, 1, Integer::sum);
        boolean offensive = type == FoulType.OFFENSIVE;
        if (!offensive) teamFouls[fouler.ordinal()]++;
        foulOut = null;

        int fts = 0;
        if (type == FoulType.SHOOTING) {
            fts = shotInFlight && shotTeam == victim ? shotPoints : 2;
        } else if (type == FoulType.PERSONAL && teamFouls[fouler.ordinal()] >= cfg.bonusFouls()) {
            fts = 2;
        }
        lastFoul = new Foul(type, foulerId, fouler, victimId, victim, fts);
        out.add(new Update(Kind.FOUL, fouler, fts));
        if (cfg.foulLimit() > 0 && personal >= cfg.foulLimit()) {
            foulOut = foulerId;
            out.add(new Update(Kind.FOUL_OUT, fouler, personal));
        }

        if (type == FoulType.SHOOTING && shotInFlight && shotTeam == victim) {
            // piłka jest w locie: czekamy na wynik rzutu
            pendingShotFoul = lastFoul;
            pendingFreeThrows = fts;
            return out;
        }
        holder = null;
        holderTeam = null;
        possession = null;
        shotInFlight = false;
        rimTouched = false;
        if (fts > 0) {
            startFreeThrows(out, victim, victimId, fts);
        } else {
            phase = Phase.DEAD;
            inbound = victim;
        }
        return out;
    }

    private void startFreeThrows(List<Update> out, Team team, UUID shooter, int count) {
        phase = Phase.FREE_THROW;
        ftTeam = team;
        ftShooter = shooter;
        ftRemaining = count;
        ftTotal = count;
        holder = null;
        holderTeam = null;
        possession = null;
        shotInFlight = false;
        out.add(new Update(Kind.FREE_THROWS, team, count));
    }

    /**
     * Wynik rzutu osobistego. Po ostatnim celnym wznawia rywal (faza DEAD), po ostatnim niecelnym gra toczy się dalej (faza LIVE, zbiórka),
     * a gdy zostały jeszcze rzuty, faza zostaje FREE_THROW.
     */
    public List<Update> freeThrowResult(boolean made) {
        List<Update> out = new ArrayList<>();
        if (phase != Phase.FREE_THROW) return out;
        ftRemaining--;
        if (made) {
            score[ftTeam.ordinal()] += 1;
            out.add(new Update(Kind.SCORE, ftTeam, 1));
            if (cfg.pointsToWin() > 0 && score[ftTeam.ordinal()] >= cfg.pointsToWin()) {
                ftRemaining = 0;
                end(out, ftTeam);
                return out;
            }
        }
        if (ftRemaining > 0) {
            out.add(new Update(Kind.FREE_THROWS, ftTeam, ftRemaining));
            return out;
        }
        if (made) {
            phase = Phase.DEAD;
            inbound = ftTeam.other();
        } else {
            phase = Phase.LIVE; // zbiórka: luźna piłka, każdy może ją wziąć
            possession = null;
            rimTouched = true;
        }
        if (suddenDeath && made) {
            end(out, ftTeam);
            return out;
        }
        out.addAll(resolveExpiry());
        return out;
    }

    /** Kończy okres, jeśli czas minął i żaden rzut nie jest w locie (rzut puszczony przed syreną jeszcze się liczy). */
    private List<Update> resolveExpiry() {
        List<Update> out = new ArrayList<>();
        if (!clockExpired || shotInFlight || phase == Phase.ENDED) return out;
        clockExpired = false;
        int a = score[0];
        int b = score[1];
        if (a != b) {
            end(out, a > b ? Team.A : Team.B);
        } else if (cfg.overtimeSeconds() > 0) {
            overtime = true;
            teamFouls[0] = 0;
            teamFouls[1] = 0;
            clock = cfg.overtimeSeconds();
            out.add(new Update(Kind.OVERTIME, null, 0));
        } else {
            // bez czasu dogrywki: złoty kosz, mecz kończy pierwszy zdobyty punkt
            overtime = true;
            suddenDeath = true;
            clock = Double.POSITIVE_INFINITY;
            out.add(new Update(Kind.OVERTIME, null, 0));
        }
        return out;
    }

    private void end(List<Update> out, Team winner) {
        phase = Phase.ENDED;
        holder = null;
        holderTeam = null;
        possession = null;
        out.add(new Update(Kind.GAME_OVER, winner, 0));
    }

    /** Czy gra toczy się do pierwszego kosza (dogrywka bez limitu czasu). */
    public boolean suddenDeath() {
        return suddenDeath;
    }
}
