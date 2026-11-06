package pl.kasyno.core;

/** Formatowanie liczb i czasu po polsku. */
public final class Fmt {
    private Fmt() {}

    /** 1234567 -> "1 234 567". */
    public static String num(long n) {
        String s = Long.toString(Math.abs(n));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) out.append(' ');
            out.append(s.charAt(i));
        }
        return (n < 0 ? "-" : "") + out;
    }

    /** Kwota z symbolem waluty: "1 234 $". */
    public static String money(long n) {
        return num(n) + " $";
    }

    public static String signed(long n) {
        return (n > 0 ? "+" : "") + money(n);
    }

    /** Krótki zapis dużych liczb: 12 500 -> "12,5 tys.", 3 400 000 -> "3,4 mln". */
    public static String shortNum(long n) {
        double a = Math.abs((double) n);
        if (a >= 1e12) return trim(n / 1e12) + " bln";
        if (a >= 1e9) return trim(n / 1e9) + " mld";
        if (a >= 1e6) return trim(n / 1e6) + " mln";
        if (a >= 1e4) return trim(n / 1e3) + " tys.";
        return num(n);
    }

    private static String trim(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.replace('.', ',');
    }

    /** Czas trwania: "1 h 05 min", "3 min 20 s", "45 s". */
    public static String duration(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (d > 0) return d + " d " + h + " h";
        if (h > 0) return h + " h " + String.format("%02d", m) + " min";
        if (m > 0) return m + " min " + String.format("%02d", sec) + " s";
        return sec + " s";
    }

    /** Mnożnik: 2.0 -> "x2", 1.95 -> "x1,95". */
    public static String mult(double m) {
        if (m == Math.floor(m)) return "x" + (long) m;
        return "x" + trim(m);
    }

    /**
     * Rozumie zapis stawki: 500, 2k, 1.5m, 50%, all, half. Zwraca -1, gdy nie rozumie.
     */
    public static long parseAmount(String text, long wallet) {
        if (text == null) return -1;
        String s = text.trim().toLowerCase().replace(" ", "").replace(',', '.');
        if (s.isEmpty()) return -1;
        switch (s) {
            case "all", "max", "wszystko", "cala" -> {
                return wallet;
            }
            case "half", "polowa", "połowa" -> {
                return wallet / 2;
            }
            default -> {
            }
        }
        try {
            if (s.endsWith("%")) {
                double pct = Double.parseDouble(s.substring(0, s.length() - 1));
                if (pct < 0 || pct > 100) return -1;
                return (long) Math.floor(wallet * pct / 100);
            }
            double mult = 1;
            char last = s.charAt(s.length() - 1);
            if (last == 'k') mult = 1e3;
            else if (last == 'm') mult = 1e6;
            else if (last == 'b') mult = 1e9;
            if (mult != 1) s = s.substring(0, s.length() - 1);
            double v = Double.parseDouble(s);
            if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) return -1;
            return (long) Math.floor(v * mult);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
