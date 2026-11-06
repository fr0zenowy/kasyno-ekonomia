package pl.kasyno.work;

import java.util.List;

/**
 * Praca w prawdziwym świecie, zlecana przez NPC: gracz chodzi po okolicy, szuka świecących celów (grzybów, rud, ryb, śmieci, paczek
 * do dostarczenia albo skarbu), klika je i dostaje pieniądze. Opis jednej pracy: czego szukamy, ile, jak długo i za ile.
 * To same dane (bez Bukkita), więc da się je testować i liczyć z nich bilans.
 */
public record JobDef(String id, String npc, String title, String icon, Kind kind, int nodes, int quota, int radius, int seconds, long cooldownMs,
                     String gatherKey, double completionBonus, String intro, List<String> tips, List<NodeType> types) {

    /** Rodzaj pracy: zbieranie nieruchomych celów, łowienie ruchomych na wodzie, trasa kurierska albo szukanie ukrytego skarbu. */
    public enum Kind { COLLECT, WATER, ROUTE, HUNT }

    /** Rodzaj celu: nazwa, przedmiot do pokazania, waga losowania, czy rzadki, wypłata (od-do) i kolor poświaty (#rrggbb). */
    public record NodeType(String id, String name, String icon, int weight, boolean rare, long min, long max, String glow) {
        public double avgPay() {
            return (min + max) / 2.0;
        }
    }

    /** Średnia wypłata za jeden cel przy luck = 1 (do bilansu i opisów). */
    public double avgNodePay() {
        double total = 0;
        double w = 0;
        for (NodeType t : types) {
            total += t.weight() * t.avgPay();
            w += t.weight();
        }
        return w == 0 ? 0 : total / w;
    }

    /** Ile celów trzeba złapać, żeby dostać premię za komplet. */
    public int goal() {
        return quota;
    }

    /** Przybliżony zarobek za całą rundę z premią (przy luck = 1). */
    public double expectedRound() {
        return avgNodePay() * quota * (1 + completionBonus);
    }

    public NodeType type(int index) {
        return types.get(index);
    }
}
