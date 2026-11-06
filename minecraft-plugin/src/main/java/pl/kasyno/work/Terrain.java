package pl.kasyno.work;

import pl.kasyno.show.Stage.P;

/** Pytania o teren do rozstawiania celów. Prawdziwą wersję daje świat Bukkita, a testy podstawiają płaski teren. */
public interface Terrain {
    /** Punkt stóp na twardym gruncie w (x, z): ziemia pod drzewami też się liczy, ale nie woda, liście ani niezaładowany chunk. Null, gdy się nie nadaje. */
    P ground(double x, double z);

    /** Punkt tuż nad powierzchnią wody w (x, z) albo null, gdy tam nie ma wody. */
    P water(double x, double z);
}
