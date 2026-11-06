package pl.sport.volley;

import pl.sport.core.BallSpec;
import pl.sport.core.Box;
import pl.sport.core.Contact;
import pl.sport.core.Floor;
import pl.sport.core.PhysicsWorld;
import pl.sport.core.V3;

/**
 * Geometria boiska do siatkówki w układzie lokalnym: u wzdłuż dłuższego boku (0 do length), v wzdłuż krótszego (0 do width), y w górę od
 * podłogi. Siatka stoi w u = length / 2, jej górna taśma na wysokości netHeight (2,43 m u mężczyzn, 2,24 m u kobiet).
 */
public final class VolleyCourt {
    /** Wysokość pasa siatki (od taśmy w dół). */
    public static final double NET_BAND = 1.0;
    /** Słupki stoją tyle metrów za linią boczną. */
    public static final double POST_OFFSET = 0.5;

    private final double length;
    private final double width;
    private final double netHeight;

    public VolleyCourt(double length, double width, double netHeight) {
        this.length = length;
        this.width = width;
        this.netHeight = netHeight;
    }

    public double length() {
        return length;
    }

    public double width() {
        return width;
    }

    public double netHeight() {
        return netHeight;
    }

    public double netU() {
        return length / 2;
    }

    /** Czy punkt (środek piłki) leży na niższej połowie boiska (mniejsze u). */
    public boolean lowSide(V3 p) {
        return p.x() < netU();
    }

    /** Czy piłka dotykająca podłogi w tym miejscu jest na boisku (linie należą do boiska, więc liczy się jej promień). */
    public boolean inCourt(V3 p, double radius) {
        return p.x() >= -radius && p.x() <= length + radius && p.z() >= -radius && p.z() <= width + radius;
    }

    /** Miejsce zagrywki: za linią końcową połowy, na środku jej szerokości. */
    public V3 serveSpot(boolean lowSide) {
        return new V3(lowSide ? -1.2 : length + 1.2, 0, width / 2);
    }

    /** Świat fizyki: podłoga, siatka i dwa słupki. */
    public PhysicsWorld physics(BallSpec spec) {
        PhysicsWorld w = new PhysicsWorld(spec);
        w.add(new Floor(0, spec.floorRestitution(), spec.tangentialLoss()));
        w.add(new Box(Contact.Kind.NET, new V3(netU() - 0.02, netHeight - NET_BAND, -POST_OFFSET), new V3(netU() + 0.02, netHeight, width + POST_OFFSET), 0.1, 0.45));
        double r = 0.06;
        for (double v : new double[]{-POST_OFFSET, width + POST_OFFSET}) {
            w.add(new Box(Contact.Kind.POST, new V3(netU() - r, 0, v - r), new V3(netU() + r, netHeight + 0.1, v + r), 0.35, 0.2));
        }
        return w;
    }
}
