package pl.sport.volley;

import pl.sport.core.Team;

import java.util.UUID;

/**
 * Zasady siatkówki bez żadnej zależności od Bukkita: punktacja rally point, sety, zmiana stron, kto serwuje, liczenie odbić
 * i błędy. Silnik dostaje zdarzenia (odbicie piłki, upadek, brak zagrywki) i zwraca wynik akcji, jeśli ta się skończyła.
 *
 * <p>Przyjęte zasady (uproszczone z FIVB): do 3 odbić na drużynę, ten sam gracz nie odbija dwa razy z rzędu (poza blokującym), blok nie
 * liczy się do trzech odbić, piłka na własnej połowie to punkt dla rywala, aut to punkt dla rywala drużyny, która dotknęła ją ostatnia,
 * zagrywka, która nie zostanie odbita w czasie, to błąd zagrywki. Set do 25 (decydujący do 15) z przewagą 2, strony zmieniają się po każdym secie.
 */
public final class VolleyGame {
    public enum Phase { SERVE, RALLY, BREAK, FINISHED }

    public enum HitType { SERVE, BUMP, SET, SPIKE, BLOCK }

    public enum Reason {
        /** Piłka upadła na boisko drużyny, która ją miała odbić. */
        LANDED_IN,
        /** Piłka wyszła poza boisko po ostatnim dotknięciu drużyny. */
        LANDED_OUT,
        FOUR_TOUCHES,
        DOUBLE_TOUCH,
        /** Zagrywający nie odbił piłki w czasie albo zagrywka nie przeszła na drugą stronę. */
        SERVE_FAULT,
        /** Zagrywający nadepnął na boisko (przekroczył linię końcową) przed uderzeniem piłki. */
        FOOT_FAULT
    }

    /** Ustawienia meczu. */
    public record Config(int setsToWin, int pointsToWinSet, int pointsDecidingSet, int minLead, int maxTouches, boolean doubleTouchFault) {
        public static Config standard() {
            return new Config(2, 25, 15, 2, 3, true);
        }
    }

    /** Wynik zakończonej akcji. */
    public record PointResult(Team winner, Reason reason, int pointsA, int pointsB, boolean setFinished, Team setWinner, boolean matchFinished, Team matchWinner) {}

    private final Config cfg;
    private final int[] points = new int[2];
    private final int[] sets = new int[2];
    private int setIndex;
    private boolean swapped;
    private Team serving;
    private Team firstServerOfSet;
    private Phase phase = Phase.BREAK;

    private final int[] teamSize = {2, 2};
    private Team touchTeam;
    private int touches;
    private UUID lastToucher;
    private UUID blockWaiver;

    public VolleyGame(Config cfg, Team firstServer) {
        this.cfg = cfg;
        this.serving = firstServer;
        this.firstServerOfSet = firstServer;
    }

    // ---------- stan ----------

    public Config config() {
        return cfg;
    }

    public Phase phase() {
        return phase;
    }

    public Team serving() {
        return serving;
    }

    public int points(Team t) {
        return points[t.ordinal()];
    }

    public int sets(Team t) {
        return sets[t.ordinal()];
    }

    public int setNumber() {
        return setIndex + 1;
    }

    public boolean swapped() {
        return swapped;
    }

    /** Drużyna, do której należy niższa połowa boiska (mniejsze u). */
    public Team lowSideOwner() {
        return swapped ? Team.B : Team.A;
    }

    /** Drużyna, do której należy dana połowa boiska. */
    public Team sideOwner(boolean lowSide) {
        return lowSide ? lowSideOwner() : lowSideOwner().other();
    }

    public int maxSets() {
        return cfg.setsToWin() * 2 - 1;
    }

    /** Czy trwa set decydujący (krótszy, do 15). */
    public boolean decidingSet() {
        return maxSets() > 1 && setIndex == maxSets() - 1;
    }

    public int pointsTarget() {
        return decidingSet() ? cfg.pointsDecidingSet() : cfg.pointsToWinSet();
    }

    public Team touchTeam() {
        return touchTeam;
    }

    public int touches() {
        return touches;
    }

    public UUID lastToucher() {
        return lastToucher;
    }

    public boolean finished() {
        return phase == Phase.FINISHED;
    }

    /**
     * Ilu graczy ma drużyna. W drużynie jednoosobowej (np. 1 na 1) ten sam gracz może odbić piłkę kilka razy z rzędu i wystawić ją sobie,
     * a zakaz podwójnego odbicia nie obowiązuje. Zostaje limit trzech odbić.
     */
    public void setTeamSize(Team t, int size) {
        teamSize[t.ordinal()] = size;
    }

    public int teamSize(Team t) {
        return teamSize[t.ordinal()];
    }

    // ---------- przebieg akcji ----------

    /** Zaczyna akcję od zagrywki drużyny `serving()`. */
    public void beginRally() {
        if (phase == Phase.FINISHED) return;
        phase = Phase.SERVE;
        touchTeam = null;
        touches = 0;
        lastToucher = null;
        blockWaiver = null;
    }

    /**
     * Odbicie piłki. Zwraca wynik akcji, gdy odbicie jest błędem, a w przeciwnym razie null.
     * W fazie zagrywki liczy się tylko odbicie typu SERVE drużyny serwującej.
     */
    public PointResult touch(Team team, UUID player, HitType type) {
        if (phase == Phase.SERVE) {
            if (team != serving || type != HitType.SERVE) return null;
            phase = Phase.RALLY;
            touchTeam = team;
            touches = 0; // zagrywka nie wlicza się do trzech odbić
            lastToucher = player;
            return null;
        }
        if (phase != Phase.RALLY) return null;

        if (type == HitType.BLOCK && team != touchTeam) {
            // blok nie liczy się jako odbicie, a blokujący może zagrać piłkę jeszcze raz
            touchTeam = team;
            touches = 0;
            lastToucher = player;
            blockWaiver = player;
            return null;
        }

        if (team != touchTeam) {
            touchTeam = team;
            touches = 1;
            lastToucher = player;
            blockWaiver = null;
            return null;
        }

        boolean same = player.equals(lastToucher) && !player.equals(blockWaiver) && teamSize[team.ordinal()] > 1;
        if (cfg.doubleTouchFault() && same) return award(team.other(), Reason.DOUBLE_TOUCH);
        touches++;
        if (touches > cfg.maxTouches()) return award(team.other(), Reason.FOUR_TOUCHES);
        lastToucher = player;
        blockWaiver = null;
        return null;
    }

    /**
     * Piłka dotknęła podłogi. `areaOwner` to drużyna, do której należy miejsce upadku (null, gdy poza boiskiem), `inBounds` mówi, czy to boisko.
     */
    public PointResult landed(Team areaOwner, boolean inBounds) {
        if (phase != Phase.RALLY && phase != Phase.SERVE) return null;
        if (inBounds && areaOwner != null) return award(areaOwner.other(), Reason.LANDED_IN);
        Team last = touchTeam != null ? touchTeam : serving;
        return award(last.other(), Reason.LANDED_OUT);
    }

    /** Zagrywający nie odbił piłki w wyznaczonym czasie. */
    public PointResult serveTimeout() {
        if (phase != Phase.SERVE) return null;
        return award(serving.other(), Reason.SERVE_FAULT);
    }

    /** Zagrywający wszedł na boisko przed uderzeniem piłki: punkt dla rywali. */
    public PointResult footFault() {
        if (phase != Phase.SERVE) return null;
        return award(serving.other(), Reason.FOOT_FAULT);
    }

    /** Przyznaje punkt (np. po błędzie wykrytym poza silnikiem). */
    public PointResult award(Team winner, Reason reason) {
        if (phase == Phase.FINISHED || phase == Phase.BREAK) return null;
        phase = Phase.BREAK;
        points[winner.ordinal()]++;
        if (winner != serving) serving = winner;

        int pw = points[winner.ordinal()];
        int pl = points[winner.other().ordinal()];
        boolean setDone = pw >= pointsTarget() && pw - pl >= cfg.minLead();
        if (!setDone) return new PointResult(winner, reason, points[0], points[1], false, null, false, null);

        int a = points[0];
        int b = points[1];
        sets[winner.ordinal()]++;
        if (sets[winner.ordinal()] >= cfg.setsToWin()) {
            phase = Phase.FINISHED;
            return new PointResult(winner, reason, a, b, true, winner, true, winner);
        }
        // następny set: wyzerowane punkty, zmiana stron, zagrywkę zaczyna druga drużyna niż poprzednio
        points[0] = 0;
        points[1] = 0;
        setIndex++;
        swapped = !swapped;
        firstServerOfSet = firstServerOfSet.other();
        serving = firstServerOfSet;
        return new PointResult(winner, reason, a, b, true, winner, false, null);
    }

    /** Kończy mecz bez rozstrzygnięcia (np. brak graczy). */
    public void abort() {
        phase = Phase.FINISHED;
    }
}
