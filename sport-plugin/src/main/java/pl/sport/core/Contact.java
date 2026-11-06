package pl.sport.core;

/** Styk piłki z przeszkodą: normalna (od przeszkody do środka piłki), głębokość wnikania i właściwości odbicia. */
public record Contact(Kind kind, V3 normal, double depth, double restitution, double tangentialLoss) {
    public enum Kind { FLOOR, NET, POST, BOARD, RIM, WALL, TABLE }
}
