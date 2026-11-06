package pl.sport.core;

/**
 * Parametry fizyczne piłki. Jednostki: metr (blok), sekunda. Opór powietrza jest kwadratowy (a = -k·|v|·v), k wyznaczone
 * z rozmiaru i masy prawdziwych piłek, dzięki czemu zagrywka siatkarska wyraźnie hamuje, a rzut do kosza leci niemal paraboliczną krzywą.
 */
public record BallSpec(String name, double radius, double gravity, double dragK, double floorRestitution, double tangentialLoss, double rollDecel) {
    /** Piłka siatkowa: lekka, mocno hamowana przez powietrze, średnio sprężysta. Promień trochę większy od prawdziwego dla czytelności. */
    public static final BallSpec VOLLEYBALL = new BallSpec("volleyball", 0.13, 9.81, 0.036, 0.55, 0.15, 1.3);
    /** Piłka do koszykówki: cięższa, wysoko się odbija od parkietu. */
    public static final BallSpec BASKETBALL = new BallSpec("basketball", 0.15, 9.81, 0.021, 0.78, 0.08, 0.9);

    /** Piłeczka do ping-ponga: bardzo lekka (duży opór), bardzo sprężysta. Promień i grawitacja dobrane do stołu powiększonego o 1,5, żeby gra była czytelna. */
    public static final BallSpec PINGPONG = new BallSpec("pingpong", 0.04, 7.0, 0.09, 0.75, 0.05, 1.0);

    public BallSpec withDrag(double k) {
        return new BallSpec(name, radius, gravity, k, floorRestitution, tangentialLoss, rollDecel);
    }

    public BallSpec withGravity(double g) {
        return new BallSpec(name, radius, g, dragK, floorRestitution, tangentialLoss, rollDecel);
    }
}
