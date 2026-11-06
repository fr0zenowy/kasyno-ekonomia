package pl.kasyno.waypoint;

import org.bukkit.entity.Player;

/** Wszystko, co waypointy robią w świecie: stawiają znaczniki widoczne tylko dla jednego gracza, ruszają je i usuwają. */
public interface MarkerBackend {
    /** Najmniejsze przesunięcie napisu (bloki), które zasługuje na ruch: napis jedzie za graczem, więc reagujemy na każdy realny ruch. */
    double LABEL_STEP = 0.02;
    /** To samo dla kreski. */
    double BEAM_STEP = 0.05;
    /** Najmniejsza zmiana rozmiaru napisu, którą przepisujemy (rozmiar zmienia się tylko, gdy waypoint jest bliżej niż label-distance). */
    float SCALE_STEP = 0.03f;

    /**
     * Opis znacznika. Napis (MiniMessage) stoi dolną krawędzią ramki w (labelX, labelY, labelZ) i ma stały rozmiar `scale`,
     * opcjonalna kreska wyrasta w górę z (beamX, beamY, beamZ) na `beamHeight` bloków w kolorze `color` (rgb).
     */
    record Spec(double beamX, double beamY, double beamZ, double labelX, double labelY, double labelZ, String label, float scale, int color,
                boolean beam, boolean showLabel, double beamHeight) {
        private static boolean far(double ax, double ay, double az, double bx, double by, double bz, double limit) {
            double dx = ax - bx;
            double dy = ay - by;
            double dz = az - bz;
            return dx * dx + dy * dy + dz * dz > limit * limit;
        }

        /** Czy znacznik trzeba ruszyć albo przepisać względem `o` (pomijamy tylko mikroskopijne zmiany). */
        public boolean differs(Spec o) {
            return (beam && far(beamX, beamY, beamZ, o.beamX, o.beamY, o.beamZ, BEAM_STEP)) || (showLabel && far(labelX, labelY, labelZ, o.labelX, o.labelY, o.labelZ, LABEL_STEP))
                    || !label.equals(o.label) || Math.abs(scale - o.scale) > SCALE_STEP || color != o.color || beam != o.beam || showLabel != o.showLabel || beamHeight != o.beamHeight;
        }
    }

    /** Stawia znacznik dla `owner`. Zwraca nieprzejrzysty uchwyt albo null, gdy się nie udało. */
    Object spawn(Player owner, Spec spec);

    void update(Object handle, Spec spec);

    void remove(Object handle);

    /** Sprząta znaczniki zostawione po awarii serwera. */
    default void purge() {}
}
