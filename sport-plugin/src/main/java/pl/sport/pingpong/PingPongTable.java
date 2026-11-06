package pl.sport.pingpong;

import pl.sport.core.BallSpec;
import pl.sport.core.Box;
import pl.sport.core.Contact;
import pl.sport.core.Floor;
import pl.sport.core.PhysicsWorld;
import pl.sport.core.V3;

/**
 * Geometria stołu do ping-ponga w układzie lokalnym hali: u wzdłuż dłuższego boku hali (stół leży wzdłuż niego, na środku), v w poprzek, y w
 * górę od podłogi. Wymiary to wymiary prawdziwego stołu (2,74 x 1,525 m, blat na 76 cm, siatka 15,25 cm) pomnożone przez skalę, bo
 * w grze stół w skali 1:1 jest za mały, a piłeczka za szybka, żeby dało się ją odbijać kliknięciem. Siatka stoi w połowie długości stołu.
 */
public final class PingPongTable {
    public static final double REAL_LENGTH = 2.74;
    public static final double REAL_WIDTH = 1.525;
    public static final double REAL_HEIGHT = 0.76;
    public static final double REAL_NET = 0.1525;
    /** Siatka wystaje poza stół tyle samo, ile jest wysoka. */
    public static final double REAL_OVERHANG = 0.1525;
    /** Grubość blatu (nad nią piłeczka się odbija, pod nią już nie). */
    public static final double TOP_THICKNESS = 0.05;
    /** Sprężystość blatu: z 30 cm piłeczka wraca na ok. 23 cm. */
    public static final double TABLE_RESTITUTION = 0.88;

    private final double arenaLength;
    private final double arenaWidth;
    private final double scale;

    public PingPongTable(double arenaLength, double arenaWidth, double scale) {
        this.arenaLength = arenaLength;
        this.arenaWidth = arenaWidth;
        this.scale = scale;
    }

    /** Skala stołu z wysokości siatki zapisanej w boisku. */
    public static double scaleOfNet(double netHeight) {
        return netHeight / REAL_NET;
    }

    public double scale() {
        return scale;
    }

    public double tableLength() {
        return REAL_LENGTH * scale;
    }

    public double tableWidth() {
        return REAL_WIDTH * scale;
    }

    /** Wysokość blatu nad podłogą. */
    public double topY() {
        return REAL_HEIGHT * scale;
    }

    public double netHeight() {
        return REAL_NET * scale;
    }

    public double netTopY() {
        return topY() + netHeight();
    }

    public double overhang() {
        return REAL_OVERHANG * scale;
    }

    public double u0() {
        return (arenaLength - tableLength()) / 2;
    }

    public double u1() {
        return u0() + tableLength();
    }

    public double v0() {
        return (arenaWidth - tableWidth()) / 2;
    }

    public double v1() {
        return v0() + tableWidth();
    }

    public double netU() {
        return arenaLength / 2;
    }

    /** Najmniejsza długość i szerokość hali, w której stół ma jeszcze miejsce dla graczy (po 1,5 m z każdej strony). */
    public static int minLength(double scale) {
        return (int) Math.ceil(REAL_LENGTH * scale + 3);
    }

    public static int minWidth(double scale) {
        return (int) Math.ceil(REAL_WIDTH * scale + 3);
    }

    /** Czy punkt leży po niższej stronie siatki (mniejsze u). */
    public boolean lowSide(V3 p) {
        return p.x() < netU();
    }

    /** Czy środek piłeczki jest nad blatem (rzut z góry), z zapasem `margin` wokół. */
    public boolean overTable(V3 p, double margin) {
        return p.x() >= u0() - margin && p.x() <= u1() + margin && p.z() >= v0() - margin && p.z() <= v1() + margin;
    }

    /** Linia końcowa stołu po danej stronie siatki (u). */
    public double endLineU(boolean lowSide) {
        return lowSide ? u0() : u1();
    }

    /** Miejsce zagrywającego: za linią końcową, o `vOffset` od osi stołu. */
    public V3 serveSpot(boolean lowSide, double vOffset) {
        double u = lowSide ? u0() - 0.9 : u1() + 0.9;
        return new V3(u, 0, arenaWidth / 2 + vOffset);
    }

    /** Czy gracz w tym miejscu stoi w stole: blat z zapasem na ciało gracza. Stół nie ma kolizji w grze, więc pilnuje tego ruch. */
    public boolean insideTableFootprint(V3 feet) {
        double m = 0.3;
        return feet.x() > u0() - m && feet.x() < u1() + m && feet.z() > v0() - m && feet.z() < v1() + m;
    }

    /** Świat fizyki: podłoga, blat i siatka. Zderzenia piłeczki są zgłaszane od bardzo małych prędkości, bo jest lekka. */
    public PhysicsWorld physics(BallSpec spec) {
        PhysicsWorld w = new PhysicsWorld(spec).reportFrom(0.2);
        w.add(new Floor(0, spec.floorRestitution(), spec.tangentialLoss()));
        w.add(new Box(Contact.Kind.TABLE, new V3(u0(), topY() - TOP_THICKNESS, v0()), new V3(u1(), topY(), v1()), TABLE_RESTITUTION, 0.06));
        w.add(new Box(Contact.Kind.NET, new V3(netU() - 0.01, topY(), v0() - overhang()), new V3(netU() + 0.01, netTopY(), v1() + overhang()), 0.1, 0.5));
        return w;
    }
}
