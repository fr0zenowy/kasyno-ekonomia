package pl.kasyno.prison;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;

/** Zasady więzienia bez Bukkita: powody wyroku, współrzędne cel, losowanie celi i filtr komend. */
public final class PrisonRules {
    private PrisonRules() {}

    /** Za co się siedzi: ile zadań trzeba wykonać i po ilu minutach strażnik wypuszcza sam (domyślne, w config.yml można zmienić). */
    public enum Reason {
        CRIME("przestepstwo", "Przestępstwo", 3, 8),
        ROB("okradanie", "Okradanie gracza", 2, 5),
        HEIST("napad", "Napad na bank", 4, 12);

        private final String id;
        private final String title;
        private final int tasks;
        private final int minutes;

        Reason(String id, String title, int tasks, int minutes) {
            this.id = id;
            this.title = title;
            this.tasks = tasks;
            this.minutes = minutes;
        }

        public String id() {
            return id;
        }

        public String title() {
            return title;
        }

        public int defaultTasks() {
            return tasks;
        }

        public int defaultMinutes() {
            return minutes;
        }

        public static Reason byId(String id) {
            if (id == null) return null;
            for (Reason r : values()) if (r.id.equalsIgnoreCase(id)) return r;
            return null;
        }
    }

    /** Miejsce w świecie (środek bloku, jeśli współrzędne podano jako liczby całkowite). */
    public record Spot(double x, double y, double z) {}

    /**
     * Czyta „x y z” (spacje, przecinki albo średniki). Liczby całkowite to współrzędne bloku, więc stajemy na jego środku (x+0,5 i z+0,5);
     * liczby z częścią ułamkową zostają dokładnie takie, jakie podano. Zwraca null, gdy to nie są trzy liczby.
     */
    public static Spot parseSpot(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("[\\s,;]+");
        if (parts.length != 3) return null;
        double[] v = new double[3];
        boolean[] whole = new boolean[3];
        try {
            for (int i = 0; i < 3; i++) {
                v[i] = Double.parseDouble(parts[i].replace(',', '.'));
                whole[i] = !parts[i].contains(".");
                if (Double.isNaN(v[i]) || Double.isInfinite(v[i])) return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return new Spot(whole[0] ? v[0] + 0.5 : v[0], v[1], whole[2] ? v[2] + 0.5 : v[2]);
    }

    /** Zamienia wpisy z konfiguracji na miejsca; błędne wpisy są pomijane. */
    public static List<Spot> parseSpots(List<?> raw) {
        List<Spot> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object o : raw) {
            Spot s = parseSpot(String.valueOf(o));
            if (s != null) out.add(s);
        }
        return out;
    }

    /** Zapis miejsca do konfiguracji: współrzędne bloku (całkowite), jeśli stoimy na środku bloku, inaczej dokładne. */
    public static String format(Spot s) {
        return coord(s.x(), true) + " " + coord(s.y(), false) + " " + coord(s.z(), true);
    }

    private static String coord(double v, boolean centred) {
        double base = centred ? v - 0.5 : v;
        if (Math.abs(base - Math.rint(base)) < 1e-9) return String.valueOf((long) Math.rint(base));
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** Losowa cela (równe szanse dla każdej). */
    public static int pickCell(int cells, RandomGenerator rng) {
        return cells <= 1 ? 0 : rng.nextInt(cells);
    }

    /** Ile całych sekund zostało do końca wyroku (nie mniej niż 0). */
    public static long secondsLeft(long until, long now) {
        return Math.max(0, (until - now + 999) / 1000);
    }

    /** Czas jako m:ss. */
    public static String clock(long seconds) {
        return (seconds / 60) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    /** Czy komenda z czatu jest dozwolona w celi: porównujemy początek wpisu (bez ukośnika i prefiksu pluginu) z listą dozwolonych. */
    public static boolean commandAllowed(String line, List<String> allowed) {
        String n = normalize(line);
        if (n.isEmpty()) return true;
        for (String a : allowed) {
            String e = normalize(a);
            if (!e.isEmpty() && (n.equals(e) || n.startsWith(e + " "))) return true;
        }
        return false;
    }

    private static String normalize(String line) {
        if (line == null) return "";
        String s = line.trim().toLowerCase(Locale.ROOT);
        while (s.startsWith("/")) s = s.substring(1);
        s = s.replaceAll("\\s+", " ");
        int colon = s.indexOf(':');
        int space = s.indexOf(' ');
        if (colon > 0 && (space < 0 || colon < space)) s = s.substring(colon + 1);
        return s;
    }
}
