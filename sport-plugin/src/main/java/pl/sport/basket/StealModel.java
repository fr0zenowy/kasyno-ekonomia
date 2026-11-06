package pl.sport.basket;

/**
 * Szansa przechwytu piłki od kozłującego i szansa faulu przy nieudanej próbie. Liczy się to, co w prawdziwej koszykówce: piłka nisko przy
 * podłodze (dolna faza kozła) jest łatwiejsza do wybicia niż ta przy ręce, łatwiej od tyłu i z bliska, trudniej, gdy kozłujący osłania piłkę
 * ciałem (kuca), biegnie albo jest w wyskoku. Niecelny przechwyt z kontaktem (od tyłu, w rękę rzucającego) to faul, z przodu to czysta próba.
 */
public final class StealModel {
    private StealModel() {}

    /** Sytuacja przy próbie przechwytu. */
    public record Situation(double distance, boolean ballLow, boolean fromBehind, boolean holderProtecting, boolean holderSprinting, boolean holderAir, boolean stealerAir) {}

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Szansa udanego przechwytu (0-0,9) dla podstawowej szansy `base` z konfiguracji. */
    public static double chance(double base, Situation s) {
        if (base >= 10) return 1; // tryb „zawsze” (testy, zabawa): przechwyt udaje się za każdym razem
        double c = base;
        c *= s.ballLow() ? 1.6 : 0.55;
        if (s.fromBehind()) c *= 1.35;
        if (s.holderProtecting()) c *= 0.35;
        if (s.holderSprinting()) c *= 0.7;
        if (s.holderAir()) c *= 0.4;
        if (s.stealerAir()) c *= 1.15;
        c *= clamp(1.25 - 0.28 * s.distance(), 0.4, 1.2);
        return clamp(c, 0.02, 0.9);
    }

    /**
     * Szansa, że nieudana próba przechwytu kończy się faulem. Z przodu i z daleka to czysta próba, a faul daje dopiero kontakt: uderzenie w ręce
     * rzucającego albo skaczącego, zahaczenie od tyłu i wejście w piłkę osłoniętą ciałem.
     */
    public static double foulChance(Situation s) {
        double f = 0;
        if (s.holderAir()) f = s.distance() < 1.7 ? 0.9 : 0.3;
        if (s.fromBehind()) f = Math.max(f, s.distance() < 1.4 ? 0.9 : 0.35);
        if (s.holderProtecting()) f = Math.max(f, s.distance() < 1.1 ? 0.5 : 0.1);
        return clamp(f, 0, 0.95);
    }
}
