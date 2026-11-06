package pl.sport.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import pl.sport.SportPlugin;
import pl.sport.match.BallKind;

import java.util.Locale;

/** Ustawienia z config.yml. Każda wartość ma domyślną w kodzie, więc stary plik konfiguracji bez nowych kluczy nie psuje pluginu. */
public final class SportConfig {
    private final SportPlugin plugin;

    public SportConfig(SportPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration c() {
        return plugin.getConfig();
    }

    public Material material(String path, Material fallback) {
        String s = c().getString(path, fallback.name());
        Material m = s == null ? null : Material.matchMaterial(s.toUpperCase(Locale.ROOT));
        return m != null ? m : fallback;
    }

    public double num(String path, double def) {
        return c().getDouble(path, def);
    }

    public int integer(String path, int def) {
        return c().getInt(path, def);
    }

    public boolean flag(String path, boolean def) {
        return c().getBoolean(path, def);
    }

    public String text(String path, String def) {
        return c().getString(path, def);
    }

    // ---------- wspólne ----------

    public int countdownSeconds() {
        return Math.max(3, integer("wspolne.odliczanie-sekundy", 10));
    }

    public int minPlayersPerTeam() {
        return Math.max(1, integer("wspolne.min-graczy-w-druzynie", 1));
    }

    /** Zasięg uderzenia piłki w blokach (liczony od oczu gracza). */
    public double reach() {
        return Math.max(1.5, num("pilka.zasieg", 3.0));
    }

    public double hitCooldownSeconds() {
        return Math.max(0.1, num("pilka.odstep-uderzen-sekundy", 0.30));
    }

    public boolean shadow() {
        return flag("pilka.cien", true);
    }

    public Material ballItem(BallKind kind) {
        return switch (kind) {
            case VOLLEYBALL -> material("pilka.przedmiot-siatkowka", Material.SNOWBALL);
            case BASKETBALL -> material("pilka.przedmiot-koszykowka", Material.MAGMA_CREAM);
            case PINGPONG -> material("pilka.przedmiot-pingpong", Material.SNOWBALL);
        };
    }

    /** Model przedmiotu piłki z paczki zasobów Kasyna (domyślnie "kasyno:ball_basket" / "kasyno:ball_volley"); "brak" = zwykły przedmiot. */
    public String ballModel(BallKind kind) {
        String path = switch (kind) {
            case VOLLEYBALL -> "pilka.model-siatkowka";
            case BASKETBALL -> "pilka.model-koszykowka";
            case PINGPONG -> "pilka.model-pingpong";
        };
        String def = switch (kind) {
            case VOLLEYBALL -> "kasyno:ball_volley";
            case BASKETBALL -> "kasyno:ball_basket";
            case PINGPONG -> "";
        };
        String v = text(path, def);
        // stary config.yml z pierwszej wersji miał tu puste pola ("") i przez to piłki nigdy nie dostawały modelu: puste znaczy teraz „domyślny model”
        if (v == null || v.isBlank()) return def;
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "brak", "none", "off", "false", "nie" -> "";
            default -> v.trim();
        };
    }

    public boolean protectBlocks() {
        return flag("wspolne.chron-boisko", true);
    }

    // ---------- siatkówka ----------

    public int volleySetsToWin() {
        return Math.max(1, integer("siatkowka.sety-do-wygrania", 2));
    }

    public int volleyPointsPerSet() {
        return Math.max(5, integer("siatkowka.punkty-w-secie", 25));
    }

    public int volleyPointsDecidingSet() {
        return Math.max(5, integer("siatkowka.punkty-w-secie-decydujacym", 15));
    }

    public int volleyMaxPerTeam() {
        return Math.max(1, integer("siatkowka.max-graczy-w-druzynie", 6));
    }

    public double volleyServeSeconds() {
        return Math.max(3, num("siatkowka.czas-na-zagrywke-sekundy", 10));
    }

    public double volleyNetHeight() {
        return Math.max(1.5, num("siatkowka.wysokosc-siatki", 2.43));
    }

    // ---------- koszykówka ----------

    public double basketMinutes() {
        return Math.max(0.5, num("koszykowka.minuty", 5));
    }

    public int basketPointsToWin() {
        return Math.max(0, integer("koszykowka.punkty-do-wygrania", 0));
    }

    public double basketShotClock() {
        return Math.max(5, num("koszykowka.zegar-akcji-sekundy", 24));
    }

    public double basketOvertimeSeconds() {
        return Math.max(0, num("koszykowka.dogrywka-sekundy", 60));
    }

    public int basketMaxPerTeam() {
        return Math.max(1, integer("koszykowka.max-graczy-w-druzynie", 5));
    }

    /** Mnożnik celności rzutów (1 = normalnie, więcej = łatwiej). */
    public double basketAccuracy() {
        return Math.max(0.3, num("koszykowka.celnosc", 1.1));
    }

    public double basketPickupRadius() {
        return Math.max(0.5, num("koszykowka.zasieg-podniesienia", 1.1));
    }

    /** Wsad i dwutakt (specjalne rzuty z bliska). */
    public boolean basketTechniques() {
        return flag("koszykowka.techniki", true);
    }

    /** Faule (obrona, atak, na rzucającym), rzuty osobiste i limit fauli. */
    public boolean basketFouls() {
        return flag("koszykowka.faule", true);
    }

    /** Mnożnik szansy odgwizdania faulu (1 = normalnie, 0 = nigdy, duże wartości wymuszają faul przy każdym kontakcie). */
    public double basketFoulChance() {
        return Math.max(0, num("koszykowka.szansa-faulu", 1.0));
    }

    /** Od którego faulu drużyny w okresie każdy zwykły faul daje dwa rzuty osobiste. */
    public int basketBonusFouls() {
        return Math.max(1, integer("koszykowka.faule-premia", 5));
    }

    /** Ile fauli osobistych wyklucza gracza z meczu (0 = bez limitu). */
    public int basketFoulLimit() {
        return Math.max(0, integer("koszykowka.faule-limit", 6));
    }

    /** Mnożnik celności rzutów osobistych względem zwykłych rzutów z tej odległości. */
    public double basketFreeThrowAccuracy() {
        return Math.max(0.3, num("koszykowka.celnosc-rzutow-osobistych", 1.35));
    }

    /** Mnożnik celności dwutaktu i wsadu z bliska. */
    public double basketLayupBoost() {
        return Math.max(0.3, num("koszykowka.celnosc-dwutaktu", 1.7));
    }

    // ---------- ping-pong ----------

    /** Skala stołu względem prawdziwego (1 = 2,74 x 1,525 m). */
    public double pingpongScale() {
        return Math.max(1.0, Math.min(3.0, num("pingpong.skala", 1.5)));
    }

    public int pingpongGamesToWin() {
        return Math.max(1, integer("pingpong.gemy-do-wygrania", 2));
    }

    public int pingpongPointsPerGame() {
        return Math.max(5, integer("pingpong.punkty-w-gemie", 11));
    }

    public int pingpongMaxPerTeam() {
        return Math.max(1, Math.min(2, integer("pingpong.max-graczy-w-druzynie", 2)));
    }

    /** Zasięg rakietki w blokach od oczu gracza. */
    public double pingpongReach() {
        return Math.max(1.2, num("pingpong.zasieg", 2.4));
    }

    public double pingpongServeSeconds() {
        return Math.max(4, num("pingpong.czas-na-serwis-sekundy", 15));
    }

    /** Mnożnik celności uderzeń (1 = normalnie, więcej = celniej). */
    public double pingpongAccuracy() {
        return Math.max(0.3, num("pingpong.celnosc", 1.0));
    }

    /** Czy odbicie piłeczki z woleja (zanim odbije się od stołu) jest błędem. */
    public boolean pingpongVolleyFault() {
        return flag("pingpong.blad-woleja", true);
    }

    /** Grawitacja piłeczki (m/s²). Mniej = wolniejsza, bardziej „pływająca” gra. */
    public double pingpongGravity() {
        return Math.max(3.0, Math.min(12.0, num("pingpong.grawitacja", 7.0)));
    }

    /** Trudność wsadu: dzieli szansę, że wpadnie (1 = normalnie, 2 = dwa razy trudniej, 0 = zawsze wpada). */
    public double basketDunkDifficulty() {
        return Math.max(0, Math.min(10, num("koszykowka.wsad-trudnosc", 1.0)));
    }

    /** Po próbie wsadu gracz musi odpocząć tyle sekund, zanim spróbuje następnego (w tym czasie wyskok pod koszem to zwykły dwutakt). */
    public double basketDunkRestSeconds() {
        return Math.max(0, num("koszykowka.wsad-odpoczynek-sekundy", 10));
    }

    public double basketStealChance() {
        return Math.max(0, Math.min(10, num("koszykowka.szansa-przechwytu", 0.35)));
    }
}
