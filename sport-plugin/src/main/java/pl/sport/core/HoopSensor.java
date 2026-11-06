package pl.sport.core;

/**
 * Wykrywa wpadnięcie piłki do kosza: środek piłki przechodzi przez płaszczyznę obręczy z góry na dół wewnątrz jej okręgu.
 * Po zaliczeniu czujnik zbroi się dopiero wtedy, gdy piłka wróci powyżej obręczy, więc jedno wpadnięcie liczy się raz,
 * a rzut „od dołu” (piłka wybita w górę przez obręcz) nie daje punktu.
 */
public final class HoopSensor {
    private final double cx;
    private final double rimY;
    private final double cz;
    private final double inner;
    private boolean armed = true;

    /** @param inner promień okręgu, w którym środek piłki zalicza kosz (promień obręczy minus grubość rurki) */
    public HoopSensor(double cx, double rimY, double cz, double inner) {
        this.cx = cx;
        this.rimY = rimY;
        this.cz = cz;
        this.inner = inner;
    }

    /** Sprawdza odcinek ruchu piłki od `from` do `to`. Zwraca true, gdy właśnie wpadła do kosza. */
    public boolean update(V3 from, V3 to) {
        if (to.y() > rimY + 0.25) armed = true;
        if (!armed) return false;
        if (from.y() > rimY && to.y() <= rimY) {
            double f = (from.y() - rimY) / (from.y() - to.y());
            double x = from.x() + (to.x() - from.x()) * f;
            double z = from.z() + (to.z() - from.z()) * f;
            double dx = x - cx;
            double dz = z - cz;
            if (dx * dx + dz * dz <= inner * inner) {
                armed = false;
                return true;
            }
        }
        return false;
    }

    public void reset() {
        armed = true;
    }
}
