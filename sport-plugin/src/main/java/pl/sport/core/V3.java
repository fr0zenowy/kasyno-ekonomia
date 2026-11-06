package pl.sport.core;

/** Niezmienny wektor 3D w jednostkach "blok = metr". Rdzeń gry nie zależy od Bukkita, dzięki czemu fizykę i zasady da się testować bez serwera. */
public record V3(double x, double y, double z) {
    public static final V3 ZERO = new V3(0, 0, 0);
    public static final V3 UP = new V3(0, 1, 0);

    public V3 plus(V3 o) {
        return new V3(x + o.x, y + o.y, z + o.z);
    }

    public V3 minus(V3 o) {
        return new V3(x - o.x, y - o.y, z - o.z);
    }

    public V3 times(double k) {
        return new V3(x * k, y * k, z * k);
    }

    public double dot(V3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public double lengthSq() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSq());
    }

    /** Wektor jednostkowy; dla zera zwraca zero (bez NaN). */
    public V3 normalized() {
        double l = length();
        return l < 1e-12 ? ZERO : times(1 / l);
    }

    public V3 withY(double ny) {
        return new V3(x, ny, z);
    }

    /** Rzut na płaszczyznę poziomą. */
    public V3 horizontal() {
        return new V3(x, 0, z);
    }

    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    public double distanceTo(V3 o) {
        return minus(o).length();
    }

    public double horizontalDistanceTo(V3 o) {
        return minus(o).horizontalLength();
    }

    public V3 lerp(V3 o, double t) {
        return new V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    /** Wektor poziomy o kierunku yaw (stopnie, jak w Minecrafcie: 0 = +Z, 90 = -X). */
    public static V3 fromYaw(double yawDeg) {
        double r = Math.toRadians(yawDeg);
        return new V3(-Math.sin(r), 0, Math.cos(r));
    }

    /** Kierunek patrzenia (yaw i pitch w stopniach, pitch dodatni = w dół, jak w Minecrafcie). */
    public static V3 fromLook(double yawDeg, double pitchDeg) {
        double yr = Math.toRadians(yawDeg);
        double pr = Math.toRadians(pitchDeg);
        double c = Math.cos(pr);
        return new V3(-Math.sin(yr) * c, -Math.sin(pr), Math.cos(yr) * c);
    }
}
