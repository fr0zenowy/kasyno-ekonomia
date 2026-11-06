package pl.kasyno.waypoint;

/**
 * Punkt odniesienia gracza do ustawiania napisów waypointów. Zamiast prawdziwego położenia oczu, które drga razem ze skokami,
 * kucaniem i chodzeniem po schodach, trzymamy położenie wygładzone:
 * <ul>
 *   <li>wysokość zmienia się tylko wtedy, gdy gracz naprawdę zmienia poziom (stoi na ziemi, lata, pływa, wspina się), i to łagodnie,
 *       więc w skoku napis nie drga, a schodek przesuwa go płynnie; duża zmiana (spadek, winda, teleport) przeskakuje od razu,</li>
 *   <li>w poziomie położenie jest wygładzone (filtr dolnoprzepustowy) i lekko wyprzedzone o wygładzoną prędkość, która tylko częściowo
 *       równoważy opóźnienie filtra. Ruch gracza dochodzi do serwera nierówno (raz pół kroku, raz półtora, czasem dwa naraz), a napis,
 *       który wiernie by to powtarzał, drgałby; tak wygładzony drga o połowę mniej, a po zatrzymaniu zbiega łagodnie i bez przestrzelenia.</li>
 * </ul>
 * Wołamy to co tick. Same obliczenia, bez Bukkita.
 */
public final class ViewAnchor {
    /** Wysokość oczu stojącego gracza nad stopami. */
    public static final double EYE_HEIGHT = 1.62;
    /** O tyle bloków gracz musi zmienić poziom bez „podłoża”, żeby punkt odniesienia poszedł za nim od razu (spadek, winda, duży skok w górę). */
    static final double LEVEL_CHANGE = 3.0;
    /** Przesunięcie o więcej bloków w jednym kroku to teleport: wtedy nie przewidujemy ruchu. */
    static final double TELEPORT = 6.0;
    /** Jaką część różnicy do położenia gracza nadrabiamy w każdym kroku: mniej = gładziej, ale większe opóźnienie. */
    public static final double POSITION_SMOOTHING = 0.3;
    /** O ile ticków wygładzonej prędkości wyprzedzamy punkt (częściowo równoważy opóźnienie filtra, bez przestrzelenia po zatrzymaniu). */
    public static final double LOOKAHEAD = 1.2;
    /** Jaką część nowej prędkości bierzemy w każdym kroku (reszta to poprzednia). */
    public static final double SMOOTHING = 0.25;
    /** Jaką część różnicy poziomów nadrabiamy w każdym kroku, gdy gracz zmienia poziom (schodki, platformy). */
    public static final double LEVEL_SMOOTHING = 0.25;

    private boolean init;
    private String world = "";
    private double x;
    private double y;
    private double z;
    private double lastX;
    private double lastZ;
    private double lpX;
    private double lpZ;
    private double vx;
    private double vz;

    /**
     * Aktualizuje punkt odniesienia położeniem stóp gracza. `followY` mówi, że gracz stoi na ziemi, lata, pływa albo się wspina, więc
     * jego wysokość trzeba śledzić; w skoku jest false i wysokość zostaje.
     */
    public void update(String worldName, double feetX, double feetY, double feetZ, boolean followY) {
        if (!init || !world.equals(worldName)) {
            init = true;
            world = worldName;
            x = feetX;
            y = feetY;
            z = feetZ;
            lastX = feetX;
            lastZ = feetZ;
            lpX = feetX;
            lpZ = feetZ;
            vx = 0;
            vz = 0;
            return;
        }
        double rawX = feetX - lastX;
        double rawZ = feetZ - lastZ;
        lastX = feetX;
        lastZ = feetZ;
        if (Math.hypot(rawX, rawZ) > TELEPORT) {
            vx = 0;
            vz = 0;
            lpX = feetX;
            lpZ = feetZ;
        } else {
            vx += (rawX - vx) * SMOOTHING;
            vz += (rawZ - vz) * SMOOTHING;
            if (Math.abs(vx) < 1e-4) vx = 0;
            if (Math.abs(vz) < 1e-4) vz = 0;
            lpX += (feetX - lpX) * POSITION_SMOOTHING;
            lpZ += (feetZ - lpZ) * POSITION_SMOOTHING;
            if (Math.abs(feetX - lpX) < 1e-4) lpX = feetX;
            if (Math.abs(feetZ - lpZ) < 1e-4) lpZ = feetZ;
        }
        x = lpX + vx * LOOKAHEAD;
        z = lpZ + vz * LOOKAHEAD;
        double diff = feetY - y;
        if (Math.abs(diff) > LEVEL_CHANGE) y = feetY;
        else if (followY) y = Math.abs(diff) < 0.02 ? feetY : y + diff * LEVEL_SMOOTHING;
    }

    /** Stopy w poziomie (z wyprzedzeniem) i na stabilnej wysokości. */
    public double x() {
        return x;
    }

    public double feetY() {
        return y;
    }

    public double z() {
        return z;
    }

    /** Wysokość oczu stojącego gracza na stabilnym poziomie. */
    public double eyeY() {
        return y + EYE_HEIGHT;
    }
}
