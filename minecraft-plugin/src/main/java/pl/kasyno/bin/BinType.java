package pl.kasyno.bin;

import java.util.List;

/**
 * Rodzaj śmietnika: nazwa, domyślna przerwa między przeszukaniami, jakość łupu (szczęście i ilość, żeby dłuższa przerwa się opłacała),
 * szansa na pusty strzał oraz model zbudowany z bloków (BlockDisplay). Same dane, więc da się je testować bez serwera.
 */
public record BinType(String id, String name, int cooldownSec, double luck, double qty, double miss, float hitW, float hitH, List<Part> parts) {

    /**
     * Jedna bryła modelu: blok, środek względem podstawy śmietnika (y = 0 to ziemia, z+ to przód) i rozmiar w blokach. Wieko (lid)
     * obraca się na zawiasie przy tylnej, dolnej krawędzi, więc da się je podnieść.
     */
    public record Part(String block, double cx, double cy, double cz, double sx, double sy, double sz, boolean lid) {}

    private static Part part(String block, double cx, double cy, double cz, double sx, double sy, double sz) {
        return new Part(block, cx, cy, cz, sx, sy, sz, false);
    }

    private static Part lid(String block, double cx, double cy, double cz, double sx, double sy, double sz) {
        return new Part(block, cx, cy, cz, sx, sy, sz, true);
    }

    public static final List<BinType> ALL = List.of(
            // uliczny kosz: szary walec z zielonym pasem i czarnym wiekiem
            new BinType("kosz", "Kosz uliczny", 90, 1.0, 1.0, 0.15, 0.8f, 1.0f, List.of(
                    part("minecraft:gray_concrete", 0, 0.40, 0, 0.50, 0.80, 0.50),
                    part("minecraft:lime_concrete", 0, 0.45, 0, 0.52, 0.14, 0.52),
                    part("minecraft:black_concrete", 0, 0.82, 0, 0.56, 0.05, 0.56),
                    lid("minecraft:black_concrete", 0, 0.88, 0, 0.54, 0.05, 0.54))),
            // beczka z obręczami i klapą
            new BinType("beczka", "Beczka na odpady", 135, 1.4, 1.3, 0.12, 1.0f, 1.1f, List.of(
                    part("minecraft:barrel[facing=up]", 0, 0.45, 0, 0.80, 0.90, 0.80),
                    part("minecraft:iron_block", 0, 0.20, 0, 0.84, 0.05, 0.84),
                    part("minecraft:iron_block", 0, 0.70, 0, 0.84, 0.05, 0.84),
                    lid("minecraft:black_concrete", 0, 0.93, 0, 0.82, 0.06, 0.82))),
            // zielony kontener na kółkach z dwiema klapami
            new BinType("kontener", "Kontener na śmieci", 200, 1.8, 1.8, 0.08, 2.0f, 1.3f, List.of(
                    part("minecraft:black_concrete", 0, 0.12, 0, 1.90, 0.14, 1.00),
                    part("minecraft:green_concrete", 0, 0.65, 0, 1.80, 0.95, 0.90),
                    part("minecraft:white_concrete", 0, 0.75, 0.46, 1.00, 0.12, 0.02),
                    part("minecraft:black_concrete", -0.75, 0.07, 0.55, 0.14, 0.14, 0.14),
                    part("minecraft:black_concrete", 0.75, 0.07, 0.55, 0.14, 0.14, 0.14),
                    part("minecraft:black_concrete", -0.75, 0.07, -0.55, 0.14, 0.14, 0.14),
                    part("minecraft:black_concrete", 0.75, 0.07, -0.55, 0.14, 0.14, 0.14),
                    lid("minecraft:black_concrete", -0.45, 1.17, 0, 0.88, 0.06, 0.92),
                    lid("minecraft:black_concrete", 0.45, 1.17, 0, 0.88, 0.06, 0.92))),
            // osiedlowa wiata: trzy pojemniki do segregacji pod dachem
            new BinType("wiata", "Wiata śmietnikowa", 300, 2.5, 2.2, 0.05, 2.8f, 1.8f, List.of(
                    part("minecraft:dark_oak_planks", 0, 1.65, 0, 2.60, 0.08, 1.10),
                    part("minecraft:stripped_dark_oak_log", -1.15, 0.80, -0.40, 0.12, 1.60, 0.12),
                    part("minecraft:stripped_dark_oak_log", 1.15, 0.80, -0.40, 0.12, 1.60, 0.12),
                    part("minecraft:spruce_planks", 0, 0.90, -0.45, 2.40, 1.70, 0.06),
                    part("minecraft:blue_concrete", -0.70, 0.45, 0, 0.60, 0.90, 0.60),
                    part("minecraft:yellow_concrete", 0, 0.45, 0, 0.60, 0.90, 0.60),
                    part("minecraft:green_concrete", 0.70, 0.45, 0, 0.60, 0.90, 0.60),
                    lid("minecraft:black_concrete", -0.70, 0.93, 0, 0.64, 0.05, 0.64),
                    lid("minecraft:black_concrete", 0, 0.93, 0, 0.64, 0.05, 0.64),
                    lid("minecraft:black_concrete", 0.70, 0.93, 0, 0.64, 0.05, 0.64))));

    public static BinType find(String id) {
        for (BinType t : ALL) if (t.id().equals(id)) return t;
        return null;
    }

    public static List<String> ids() {
        return ALL.stream().map(BinType::id).toList();
    }
}
