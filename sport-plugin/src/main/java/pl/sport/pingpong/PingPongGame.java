package pl.sport.pingpong;

import pl.sport.core.Team;

import java.util.UUID;

/**
 * Zasady tenisa stołowego bez zależności od Bukkita: punktacja, serwis, odbicia piłeczki od stołu i błędy. Silnik dostaje zdarzenia
 * (serwis, odbicie rakietką, odbicie od połowy stołu, dotknięcie siatki, piłka poza grą) i zwraca wynik akcji, jeśli ta się skończyła.
 *
 * <p>Przyjęte zasady (uproszczone z ITTF): gem do 11 punktów z przewagą 2, mecz do zadanej liczby wygranych gemów, serwis zmienia się co dwa
 * punkty (po 10:10 co punkt), w kolejnym gemie zaczyna druga drużyna, strony zmieniają się co gem, a w gemie decydującym także po 5 punktach.
 * Serwis musi odbić się najpierw od połowy serwującego, potem przejść nad siatką i odbić od połowy odbierającego; jeśli dotknie siatki,
 * a poza tym jest dobry, serwis się powtarza (let). Odbierający musi odbić piłeczkę po jej odbiciu od swojej połowy (zagranie z woleja to
 * błąd), a każde odbicie ma trafić najpierw w połowę rywala. W grze podwójnej gracze drużyny odbijają na zmianę.
 */
public final class PingPongGame {
    public enum Phase { SERVE, RALLY, BREAK, FINISHED }

    /** Gdzie jest piłeczka w akcji: czeka na serwis, leci po serwisie albo po zagraniu, albo już odbiła się od połowy odbierającego. */
    public enum Stage { WAIT_SERVE, SERVE_OWN, SERVE_OVER, FLIGHT, GOOD }

    public enum Reason {
        /** Odbierający nie odbił piłeczki po jej dobrym odbiciu od swojej połowy. */
        NOT_RETURNED,
        /** Zagranie nie trafiło w połowę rywala (aut, siatka, podłoga, bok stołu). */
        OUT,
        /** Piłeczka odbiła się od połowy gracza, który ją uderzył. */
        OWN_HALF,
        /** Odbicie piłeczki, zanim odbiła się od stołu. */
        VOLLEY,
        /** Dwa odbicia z rzędu jednej drużyny (albo ten sam gracz w grze podwójnej). */
        DOUBLE_HIT,
        SERVE_FAULT,
        /** Serwis uderzony sprzed linii końcowej stołu. */
        SERVE_POSITION,
        SERVE_TIMEOUT
    }

    public record Config(int gamesToWin, int pointsToWinGame, int minLead, boolean volleyFault) {
        public static Config standard() {
            return new Config(2, 11, 2, true);
        }
    }

    /** Wynik zakończonej akcji. Gdy `let` jest prawdą, nikt nie dostaje punktu i serwis się powtarza. */
    public record PointResult(Team winner, Reason reason, int pointsA, int pointsB, boolean gameFinished, boolean matchFinished, Team matchWinner, boolean changeEnds, boolean let) {
        static PointResult replay(int a, int b) {
            return new PointResult(null, null, a, b, false, false, null, false, true);
        }
    }

    private final Config cfg;
    private final int[] points = new int[2];
    private final int[] games = new int[2];
    private final int[] teamSize = {1, 1};
    private final UUID[] lastOfTeam = new UUID[2];
    private int gameIndex;
    private boolean swapped;
    private boolean endsChanged;
    private Team firstServer;
    private Team serving;
    private Phase phase = Phase.BREAK;
    private Stage stage = Stage.WAIT_SERVE;
    private Team lastHitTeam;
    private UUID lastHitter;
    private boolean netTouched;

    public PingPongGame(Config cfg, Team firstServer) {
        this.cfg = cfg;
        this.firstServer = firstServer;
        this.serving = firstServer;
    }

    // ---------- stan ----------

    public Config config() {
        return cfg;
    }

    public Phase phase() {
        return phase;
    }

    public Stage stage() {
        return stage;
    }

    public Team serving() {
        return serving;
    }

    public Team receiving() {
        return serving.other();
    }

    public int points(Team t) {
        return points[t.ordinal()];
    }

    public int games(Team t) {
        return games[t.ordinal()];
    }

    public int gameNumber() {
        return gameIndex + 1;
    }

    public int maxGames() {
        return cfg.gamesToWin() * 2 - 1;
    }

    /** Czy trwa gem decydujący (ostatni możliwy): w nim strony zmieniają się jeszcze po 5 punktach. */
    public boolean decidingGame() {
        return maxGames() > 1 && gameIndex == maxGames() - 1;
    }

    public int pointsTarget() {
        return cfg.pointsToWinGame();
    }

    public boolean swapped() {
        return swapped;
    }

    /** Drużyna, do której należy niższa połowa stołu (mniejsze u). */
    public Team lowSideOwner() {
        return swapped ? Team.B : Team.A;
    }

    public Team sideOwner(boolean lowSide) {
        return lowSide ? lowSideOwner() : lowSideOwner().other();
    }

    public Team lastHitTeam() {
        return lastHitTeam;
    }

    public UUID lastHitter() {
        return lastHitter;
    }

    /** Gracz drużyny, który odbił piłeczkę jako ostatni w tej akcji (null, gdy drużyna jeszcze nie odbijała). W deblu następne odbicie należy do jego partnera. */
    public UUID lastHitterOf(Team t) {
        return lastOfTeam[t.ordinal()];
    }

    public boolean finished() {
        return phase == Phase.FINISHED;
    }

    /** Ilu graczy ma drużyna. W drużynie dwuosobowej gracze odbijają na zmianę. */
    public void setTeamSize(Team t, int size) {
        teamSize[t.ordinal()] = size;
    }

    public int teamSize(Team t) {
        return teamSize[t.ordinal()];
    }

    // ---------- przebieg akcji ----------

    /** Zaczyna akcję od serwisu drużyny `serving()`. */
    public void beginRally() {
        if (phase == Phase.FINISHED) return;
        phase = Phase.SERVE;
        stage = Stage.WAIT_SERVE;
        lastHitTeam = null;
        lastHitter = null;
        lastOfTeam[0] = null;
        lastOfTeam[1] = null;
        netTouched = false;
    }

    /** Uderzenie serwisu. `behindEndLine` mówi, czy piłeczka została uderzona zza linii końcowej stołu. */
    public PointResult serveHit(Team team, UUID player, boolean behindEndLine) {
        if (phase != Phase.SERVE || team != serving) return null;
        if (!behindEndLine) return award(serving.other(), Reason.SERVE_POSITION);
        phase = Phase.RALLY;
        stage = Stage.SERVE_OWN;
        lastHitTeam = team;
        lastHitter = player;
        lastOfTeam[team.ordinal()] = player;
        netTouched = false;
        return null;
    }

    /** Odbicie piłeczki rakietką w trakcie akcji. Zwraca wynik, gdy odbicie jest błędem, a w przeciwnym razie null. */
    public PointResult hit(Team team, UUID player) {
        if (phase != Phase.RALLY) return null;
        if (team == lastHitTeam) return award(team.other(), Reason.DOUBLE_HIT);
        if (stage != Stage.GOOD) {
            // piłeczka jeszcze nie odbiła się od połowy odbierającego: zagranie z woleja
            if (cfg.volleyFault()) return award(lastHitTeam, Reason.VOLLEY);
        }
        if (teamSize[team.ordinal()] > 1 && player.equals(lastOfTeam[team.ordinal()])) return award(team.other(), Reason.DOUBLE_HIT);
        lastHitTeam = team;
        lastHitter = player;
        lastOfTeam[team.ordinal()] = player;
        stage = Stage.FLIGHT;
        netTouched = false;
        return null;
    }

    /** Piłeczka odbiła się od blatu na połowie należącej do `halfOwner`. */
    public PointResult bounce(Team halfOwner) {
        if (phase != Phase.RALLY) return null;
        switch (stage) {
            case SERVE_OWN -> {
                if (halfOwner == lastHitTeam) {
                    stage = Stage.SERVE_OVER;
                    return null;
                }
                return award(lastHitTeam.other(), Reason.SERVE_FAULT);
            }
            case SERVE_OVER -> {
                if (halfOwner == lastHitTeam) return award(lastHitTeam.other(), Reason.SERVE_FAULT);
                if (netTouched) {
                    // dotknęła siatki, ale poza tym serwis jest dobry: powtórka
                    phase = Phase.SERVE;
                    stage = Stage.WAIT_SERVE;
                    netTouched = false;
                    return PointResult.replay(points[0], points[1]);
                }
                stage = Stage.GOOD;
                return null;
            }
            case FLIGHT -> {
                if (halfOwner == lastHitTeam) return award(lastHitTeam.other(), Reason.OWN_HALF);
                stage = Stage.GOOD;
                return null;
            }
            case GOOD -> {
                // drugie odbicie od stołu bez uderzenia rakietką: odbierający nie zdążył
                return award(lastHitTeam, Reason.NOT_RETURNED);
            }
            default -> {
                return null;
            }
        }
    }

    public void netTouch() {
        if (phase == Phase.RALLY) netTouched = true;
    }

    /** Piłeczka wypadła z gry: spadła na podłogę, uderzyła w bok stołu albo wyleciała z hali. */
    public PointResult deadBall() {
        if (phase != Phase.RALLY) return null;
        return switch (stage) {
            case SERVE_OWN, SERVE_OVER -> award(lastHitTeam.other(), Reason.SERVE_FAULT);
            case FLIGHT -> award(lastHitTeam.other(), Reason.OUT);
            case GOOD -> award(lastHitTeam, Reason.NOT_RETURNED);
            default -> null;
        };
    }

    /** Zagrywający nie uderzył piłeczki w wyznaczonym czasie. */
    public PointResult serveTimeout() {
        if (phase != Phase.SERVE) return null;
        return award(serving.other(), Reason.SERVE_TIMEOUT);
    }

    /** Kto serwuje przy danej liczbie rozegranych punktów w gemie: co dwa punkty zmiana, a od 10:10 co punkt. */
    private Team serverFor(int total) {
        boolean first = total >= 20 ? total % 2 == 0 : (total / 2) % 2 == 0;
        return first ? firstServer : firstServer.other();
    }

    /** Przyznaje punkt. Zwraca null, gdy akcja już się skończyła (nie liczymy dwóch punktów za jedną akcję). */
    public PointResult award(Team winner, Reason reason) {
        if (phase == Phase.FINISHED || phase == Phase.BREAK) return null;
        phase = Phase.BREAK;
        stage = Stage.WAIT_SERVE;
        points[winner.ordinal()]++;
        int pw = points[winner.ordinal()];
        int pl = points[winner.other().ordinal()];
        boolean gameDone = pw >= cfg.pointsToWinGame() && pw - pl >= cfg.minLead();
        if (!gameDone) {
            serving = serverFor(points[0] + points[1]);
            boolean change = false;
            if (decidingGame() && !endsChanged && Math.max(points[0], points[1]) >= 5) {
                endsChanged = true;
                swapped = !swapped;
                change = true;
            }
            return new PointResult(winner, reason, points[0], points[1], false, false, null, change, false);
        }
        int a = points[0];
        int b = points[1];
        games[winner.ordinal()]++;
        if (games[winner.ordinal()] >= cfg.gamesToWin()) {
            phase = Phase.FINISHED;
            return new PointResult(winner, reason, a, b, true, true, winner, false, false);
        }
        // następny gem: zero punktów, zmiana stron, a serwis zaczyna druga drużyna niż poprzednio
        points[0] = 0;
        points[1] = 0;
        gameIndex++;
        swapped = !swapped;
        endsChanged = false;
        firstServer = firstServer.other();
        serving = firstServer;
        return new PointResult(winner, reason, a, b, true, false, null, false, false);
    }

    /** Kończy mecz bez rozstrzygnięcia (np. brak graczy). */
    public void abort() {
        phase = Phase.FINISHED;
    }
}
