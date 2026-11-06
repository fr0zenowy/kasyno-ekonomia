package pl.kasyno.core;

import java.util.HashMap;
import java.util.Map;

/** Cały zapis gracza: pieniądze, poziom, ekwipunek, cooldowny, biznesy i farma. Serializowany do JSON przez Gson. */
public final class PlayerData {
    public String name = "?";
    public long wallet;
    public long bank;
    public long xp;
    public int level = 1;
    public int streak;
    public long lastDaily;
    public long lastWeekly;
    public String job = "sprzatacz";
    public long totalWon;
    public long totalLost;
    public long biggestWin;
    public int games;
    public int bankTier;
    public int lotteryTickets;
    public long created;

    /** Wygląd okien wybrany przez gracza: "on" (zawsze nowy), "off" (zawsze zwykły), brak = automatycznie (gdy serwer wie, że paczka jest załadowana). */
    public String guiMode;

    /** Stawka zdjęta z portfela, a jeszcze nierozliczona (gra w toku). Po awarii serwera wraca do gracza przy wejściu. */
    public long activeStake;
    public String activeGame;

    /** Wyrok w więzieniu: do kiedy trwa (ms, 0 = wolny), ile zadań zostało i ile było, za co (id powodu) i w której celi siedzi. */
    public long jailUntil;
    public int jailTasks;
    public int jailTotal;
    public String jailReason;
    public int jailCell;

    /** Osobiste waypointy: dom ustawiony przez gracza, miejsce ostatniej śmierci (z czasem), prowadzony cel i odkryte miejsca z nagrodą. */
    public String homeWorld;
    public double homeX;
    public double homeY;
    public double homeZ;
    public String deathWorld;
    public double deathX;
    public double deathY;
    public double deathZ;
    public long deathAt;
    public String track;
    public java.util.Set<String> discovered = new java.util.HashSet<>();

    /** Stawki zatrzymane przy wspólnych stołach (id stołu → kwota). Po awarii serwera wracają do portfela przy starcie. */
    public Map<String, Long> held = new HashMap<>();

    public Map<String, Integer> inventory = new HashMap<>();
    public Map<String, Long> cooldowns = new HashMap<>();
    public Map<String, Integer> buffs = new HashMap<>();
    public Map<String, Long> boosts = new HashMap<>();
    public Map<String, Biz> businesses = new HashMap<>();
    public Map<String, Plot> farm = new HashMap<>();

    public static final class Biz {
        public int level = 1;
        public long collectedAt;

        public Biz() {}

        public Biz(int level, long collectedAt) {
            this.level = level;
            this.collectedAt = collectedAt;
        }
    }

    public static final class Plot {
        public String crop;
        public long readyAt;

        public Plot() {}

        public Plot(String crop, long readyAt) {
            this.crop = crop;
            this.readyAt = readyAt;
        }
    }
}
