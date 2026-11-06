package pl.kasyno.waypoint;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Waypoint: nazwa, miejsce w świecie i kolor. Rodzaje: PLACE (stworzony przez administratora, widoczny dla wszystkich, może mieć nagrodę
 * za odkrycie), AUTO (miejsca samego kasyna: bank, arena, więzienie, wyliczane na bieżąco), HOME i DEATH (osobiste, widzi je tylko właściciel).
 */
public record Waypoint(String id, String name, String world, double x, double y, double z, int color, Kind kind, long reward) {
    public enum Kind { PLACE, AUTO, HOME, DEATH }

    public static final int MAX_NAME = 32;
    private static final Pattern VALID = Pattern.compile("[\\p{L}\\p{N}_-]{1," + MAX_NAME + "}");

    /** Zwykły waypoint administratora (bez nagrody). */
    public Waypoint(String id, String name, String world, double x, double y, double z, int color) {
        this(id, name, world, x, y, z, color, Kind.PLACE, 0);
    }

    public boolean personal() {
        return kind == Kind.HOME || kind == Kind.DEATH;
    }

    public Waypoint withReward(long amount) {
        return new Waypoint(id, name, world, x, y, z, color, kind, Math.max(0, amount));
    }

    public Waypoint withColor(int rgb) {
        return new Waypoint(id, name, world, x, y, z, rgb, kind, reward);
    }

    public Waypoint movedTo(String newWorld, double nx, double ny, double nz) {
        return new Waypoint(id, name, newWorld, nx, ny, nz, color, kind, reward);
    }

    /** Czy nazwa nadaje się na waypoint: litery (także polskie), cyfry, podkreślenie i myślnik, do 32 znaków. Spacje zastępuj podkreśleniem. */
    public static boolean validName(String name) {
        return name != null && VALID.matcher(name).matches();
    }

    /** Identyfikator z nazwy: małe litery bez ogonków, więc „Bank_Główny” i „bank_glowny” to ten sam waypoint. */
    public static String idOf(String name) {
        String s = name.toLowerCase(Locale.ROOT).replace('ł', 'l');
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return s.replaceAll("[^a-z0-9_-]", "_");
    }

    /** Nazwa do wyświetlenia: podkreślenia stają się spacjami. */
    public static String display(String name) {
        return name.replace('_', ' ');
    }

    public String title() {
        return display(name);
    }
}
