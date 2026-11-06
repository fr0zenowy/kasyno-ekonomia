package pl.kasyno.waypoint;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Kolory ikon waypointów: nazwy po polsku i angielsku albo zapis szesnastkowy (#rrggbb). */
public final class WaypointColors {
    private WaypointColors() {}

    private static final Map<String, Integer> NAMES = new LinkedHashMap<>();
    private static final List<String> POLISH = new ArrayList<>();
    private static final Map<Integer, String> BY_RGB = new LinkedHashMap<>();

    private static void color(int rgb, String polish, String english) {
        NAMES.put(polish, rgb);
        NAMES.put(english, rgb);
        POLISH.add(polish);
        BY_RGB.put(rgb, polish);
    }

    static {
        color(0xFFD700, "zloty", "gold");
        color(0xFF5555, "czerwony", "red");
        color(0xAA0000, "ciemnoczerwony", "dark_red");
        color(0xFF8C00, "pomaranczowy", "orange");
        color(0xFFFF55, "zolty", "yellow");
        color(0x55FF55, "zielony", "green");
        color(0x00AA00, "ciemnozielony", "dark_green");
        color(0x55FFFF, "turkusowy", "aqua");
        color(0x5555FF, "niebieski", "blue");
        color(0x0000AA, "granatowy", "dark_blue");
        color(0xAA00AA, "fioletowy", "purple");
        color(0xFF55FF, "rozowy", "pink");
        color(0x8B4513, "brazowy", "brown");
        color(0xFFFFFF, "bialy", "white");
        color(0xAAAAAA, "szary", "gray");
        color(0x555555, "ciemnoszary", "dark_gray");
        color(0x000000, "czarny", "black");
    }

    private static String fold(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace('ł', 'l');
        return Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    /** Kolor z nazwy albo zapisu #rrggbb / rrggbb. Null, gdy nie rozumiem. */
    public static Integer parse(String text) {
        if (text == null) return null;
        String s = fold(text.trim());
        Integer named = NAMES.get(s);
        if (named != null) return named;
        String hex = s.startsWith("#") ? s.substring(1) : s;
        if (hex.matches("[0-9a-f]{6}")) return Integer.parseInt(hex, 16);
        return null;
    }

    public static String hex(int rgb) {
        return String.format("%06x", rgb & 0xFFFFFF);
    }

    /** Polskie nazwy kolorów (podpowiedzi przy komendzie). */
    public static List<String> names() {
        return List.copyOf(POLISH);
    }

    /** Polska nazwa koloru, a dla niestandardowych zapis #rrggbb (do wypisywania w komendach). */
    public static String describe(int rgb) {
        String name = BY_RGB.get(rgb & 0xFFFFFF);
        return name != null ? name : "#" + hex(rgb);
    }
}
