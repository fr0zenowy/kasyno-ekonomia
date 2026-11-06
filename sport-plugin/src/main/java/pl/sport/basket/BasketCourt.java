package pl.sport.basket;

import pl.sport.core.BallSpec;
import pl.sport.core.Box;
import pl.sport.core.Contact;
import pl.sport.core.Floor;
import pl.sport.core.HoopSensor;
import pl.sport.core.PhysicsWorld;
import pl.sport.core.Ring;
import pl.sport.core.Team;
import pl.sport.core.V3;

/**
 * Geometria boiska do koszykówki w układzie lokalnym: u wzdłuż dłuższego boku (0 to jedna linia końcowa, length to druga), v wzdłuż
 * krótszego (0 do width), y w górę od podłogi. Kosz A stoi przy u = 0, kosz B przy u = length. Wymiary to metry (bloki), kosz wisi na
 * 3,05 m. Średnica obręczy i piłki jest powiększona względem prawdziwej tak samo, żeby proporcje (a więc trudność rzutu) się zgadzały.
 */
public final class BasketCourt {
    public static final double RIM_HEIGHT = 3.05;
    public static final double RIM_RADIUS = 0.28;
    public static final double RIM_TUBE = 0.022;
    /** Odległość płaszczyzny tablicy od linii końcowej. */
    public static final double BOARD_OFFSET = 1.2;
    /** Odległość środka obręczy od tablicy. */
    public static final double RIM_FROM_BOARD = 0.44;
    public static final double BOARD_WIDTH = 1.8;
    public static final double BOARD_HEIGHT = 1.05;
    public static final double BOARD_BOTTOM = 2.9;
    public static final double BOARD_THICKNESS = 0.06;
    /** Odległość linii rzutów za trzy punkty od środka obręczy. */
    public static final double THREE_POINT = 6.75;

    private final double length;
    private final double width;

    public BasketCourt(double length, double width) {
        this.length = length;
        this.width = width;
    }

    public double length() {
        return length;
    }

    public double width() {
        return width;
    }

    /** Środek obręczy kosza (indeks 0 = kosz przy u=0, 1 = kosz przy u=length), na wysokości obręczy. */
    public V3 rimCenter(int hoop) {
        double u = hoop == 0 ? BOARD_OFFSET + RIM_FROM_BOARD : length - BOARD_OFFSET - RIM_FROM_BOARD;
        return new V3(u, RIM_HEIGHT, width / 2);
    }

    /** Którą drużynę ten kosz broni: kosz 0 broni drużyna po stronie niskiego u (A), kosz 1 druga (B). Po zmianie stron drużyny się zamieniają. */
    public Team defender(int hoop, boolean swapped) {
        Team low = swapped ? Team.B : Team.A;
        return hoop == 0 ? low : low.other();
    }

    /** Odległość linii za trzy punkty dopasowana do rozmiaru boiska (na małych boiskach linia nie może wychodzić poza ćwierć długości). */
    public double threePointLine() {
        return Math.min(THREE_POINT, length * 0.3);
    }

    /** Ile punktów warta jest celna próba z danego miejsca (odległość pozioma od środka obręczy docelowego kosza). */
    public int pointsFor(V3 releasePos, int hoop) {
        return releasePos.horizontalDistanceTo(rimCenter(hoop)) >= threePointLine() ? 3 : 2;
    }

    public boolean inBounds(V3 p) {
        return p.x() >= 0 && p.x() <= length && p.z() >= 0 && p.z() <= width;
    }

    /** Świat fizyki piłki: podłoga, tablice i obręcze obu koszy. */
    public PhysicsWorld physics(BallSpec spec) {
        PhysicsWorld w = new PhysicsWorld(spec);
        w.add(new Floor(0, spec.floorRestitution(), spec.tangentialLoss()));
        for (int hoop = 0; hoop < 2; hoop++) {
            double boardU = hoop == 0 ? BOARD_OFFSET : length - BOARD_OFFSET;
            w.add(new Box(Contact.Kind.BOARD,
                    new V3(boardU - BOARD_THICKNESS / 2, BOARD_BOTTOM, width / 2 - BOARD_WIDTH / 2),
                    new V3(boardU + BOARD_THICKNESS / 2, BOARD_BOTTOM + BOARD_HEIGHT, width / 2 + BOARD_WIDTH / 2), 0.62, 0.1));
            V3 c = rimCenter(hoop);
            w.add(new Ring(c.x(), c.y(), c.z(), RIM_RADIUS, RIM_TUBE, 0.55, 0.12));
        }
        return w;
    }

    /** Czujnik wpadnięcia do kosza o danym indeksie. */
    public HoopSensor sensor(int hoop) {
        V3 c = rimCenter(hoop);
        return new HoopSensor(c.x(), c.y(), c.z(), RIM_RADIUS - RIM_TUBE);
    }

    /** Punkt wznowienia gry zza linii końcowej kosza, który stracił punkty (drużyna broniąca wznawia z własnej strony). */
    public V3 baselineSpot(int hoop) {
        return new V3(hoop == 0 ? 1.0 : length - 1.0, 0.0, width / 2);
    }

    /** Najbliższy punkt boiska, tuż za linią (do wznowienia z autu). Piłka stoi na wysokości `y`. */
    public V3 sidelineSpot(V3 exit) {
        double u = Math.max(0.8, Math.min(length - 0.8, exit.x()));
        double v = Math.max(0.8, Math.min(width - 0.8, exit.z()));
        // wznowienie z linii bocznej, jeśli piłka wyszła bokiem, albo z końcowej
        double toSide = Math.min(exit.z(), width - exit.z());
        double toEnd = Math.min(exit.x(), length - exit.x());
        if (toSide <= toEnd) v = exit.z() < width / 2 ? 0.8 : width - 0.8;
        else u = exit.x() < length / 2 ? 0.8 : length - 0.8;
        return new V3(u, 0.0, v);
    }
}
