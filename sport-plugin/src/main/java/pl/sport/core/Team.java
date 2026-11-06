package pl.sport.core;

/** Drużyna w meczu (obie dyscypliny mają dwie drużyny). */
public enum Team {
    A, B;

    public Team other() {
        return this == A ? B : A;
    }
}
