package pl.sport.core;

/** Przeszkoda, z którą zderza się kula o podanym środku i promieniu. */
public interface Collider {
    /** Zwraca styk, gdy kula wnika w przeszkodę, a w przeciwnym razie null. */
    Contact test(V3 center, double radius);
}
