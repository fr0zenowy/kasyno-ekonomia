package pl.kasyno.waypoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Jak waypoint wygląda dla jednego gracza. Napis jest „przyklejony” w kierunku waypointu na stałej, niewielkiej odległości od gracza
 * i ma stały rozmiar, więc na ekranie wygląda zawsze tak samo, jak etykieta z moda z waypointami (nie rośnie ani nie maleje z
 * odległością). Opcjonalna kreska stoi w prawdziwym miejscu waypointu, a gdy ten jest dalej niż `markerDistance`, też „przykleja się”
 * w jego kierunku (serwer nie pokazuje encji bardzo daleko). Same obliczenia, bez Bukkita.
 */
public final class WaypointView {
    private WaypointView() {}

    /** Gdzie stawiamy znacznik, jak daleko jest naprawdę waypoint i czy znacznik jest „przyklejony” w jego kierunku. */
    public record Placement(double x, double y, double z, double distance, boolean projected) {}

    public record Candidate(Waypoint waypoint, double distance) {}

    public static double distance(double px, double py, double pz, Waypoint w) {
        double dx = w.x() - px;
        double dy = w.y() - py;
        double dz = w.z() - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Miejsce znacznika dla gracza w (px, py, pz). Do `markerDistance` bloków to prawdziwe miejsce punktu (wx, wy, wz), dalej punkt na
     * odcinku gracz-punkt odległy od gracza o `markerDistance` (z tym samym kierunkiem i kątem wzniesienia).
     */
    public static Placement place(double px, double py, double pz, double wx, double wy, double wz, double markerDistance) {
        double dx = wx - px;
        double dy = wy - py;
        double dz = wz - pz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d <= markerDistance || d < 1e-9) return new Placement(wx, wy, wz, d, false);
        double t = markerDistance / d;
        return new Placement(px + dx * t, py + dy * t, pz + dz * t, d, true);
    }

    /**
     * Najbliższe waypointy gracza: tylko z jego świata, nie dalej niż `maxDistance` bloków (0 = bez limitu), od najbliższego, nie więcej
     * niż `limit`.
     */
    public static List<Candidate> nearest(List<Waypoint> all, String world, double px, double py, double pz, int limit, double maxDistance) {
        List<Candidate> out = new ArrayList<>();
        for (Waypoint w : all) {
            if (!w.world().equals(world)) continue;
            double d = distance(px, py, pz, w);
            if (maxDistance > 0 && d > maxDistance) continue;
            out.add(new Candidate(w, d));
        }
        out.sort(Comparator.comparingDouble(Candidate::distance).thenComparing(c -> c.waypoint().id()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, Math.max(0, limit))) : out;
    }

    /**
     * Rozmiar napisu: stały (`baseScale`), dopóki napis stoi na odległości `labelDistance` od gracza. Gdy waypoint jest bliżej i napis
     * stoi w jego prawdziwym miejscu, napis maleje tak, żeby na ekranie wyglądał tak samo (nie mniej niż 15% rozmiaru).
     */
    public static float labelScale(double shownDistance, double labelDistance, double baseScale) {
        double k = labelDistance <= 0 ? 1 : Math.max(0.15, Math.min(1.0, shownDistance / labelDistance));
        return (float) (baseScale * k);
    }

    /** Znaczek przed nazwą: ➤ dla prowadzonego celu, ⌂ dom, ☠ miejsce śmierci, ★ miejsce z jeszcze nieodebraną nagrodą. */
    public static String prefix(Waypoint.Kind kind, boolean tracked, boolean rewardPending) {
        String icon = switch (kind) {
            case HOME -> "⌂ ";
            case DEATH -> "☠ ";
            default -> rewardPending ? "★ " : "";
        };
        return (tracked ? "➤ " : "") + icon;
    }

    /** Dopisek pod odległością dla miejsca z nagrodą, której gracz jeszcze nie odebrał. */
    public static String rewardHint(long reward) {
        return " <yellow>★ +" + pl.kasyno.core.Fmt.money(reward);
    }

    /** Liczba w napisie odległości: metry co 1 m do tysiąca, od tysiąca kilometry z jednym miejscem po przecinku. */
    public static String number(double blocks) {
        long m = Math.round(blocks);
        if (m < 1000) return Long.toString(m);
        return String.format(Locale.ROOT, "%.1f", blocks / 1000.0).replace('.', ',');
    }

    /** Jednostka po polsku z odpowiednią formą: 1 metr, 2 metry, 5 metrów, 12 metrów, 22 metry; od tysiąca „km”. */
    public static String unit(double blocks) {
        long m = Math.round(blocks);
        if (m >= 1000) return "km";
        if (m == 1) return "metr";
        long last = m % 10;
        long lastTwo = m % 100;
        return last >= 2 && last <= 4 && !(lastTwo >= 12 && lastTwo <= 14) ? "metry" : "metrów";
    }

    /** Odległość po polsku: „76 metrów”, „1,3 km”. */
    public static String distance(double blocks) {
        return number(blocks) + " " + unit(blocks);
    }
}
