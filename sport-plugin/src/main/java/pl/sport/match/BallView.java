package pl.sport.match;

import org.bukkit.Location;

/** Wygląd piłki w świecie. Fizyka liczy się osobno, a widok tylko pokazuje wynik. Dzięki temu mecz da się testować bez prawdziwych encji. */
public interface BallView {
    /** Przesuwa piłkę: środek piłki i wysokość podłogi pod nią (do cienia). */
    void move(Location center, double floorY);

    /** Obraca piłkę o `degrees` wokół osi poziomej prostopadłej do kierunku ruchu `dirX, dirZ` (efekt toczenia i rotacji w locie). */
    default void spin(double dirX, double dirZ, double degrees) {}

    void remove();

    boolean valid();

    @FunctionalInterface
    interface Factory {
        BallView create(Location where, BallKind kind, double radius);
    }
}
