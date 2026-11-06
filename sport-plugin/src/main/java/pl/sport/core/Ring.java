package pl.sport.core;

/** Obręcz kosza: torus o poziomym kole o środku (cx, y, cz), promieniu `radius` i grubości rurki `tube`. */
public record Ring(double cx, double y, double cz, double radius, double tube, double restitution, double tangentialLoss) implements Collider {
    @Override
    public Contact test(V3 c, double r) {
        double hx = c.x() - cx;
        double hz = c.z() - cz;
        double hl = Math.sqrt(hx * hx + hz * hz);
        // najbliższy punkt okręgu rurki; gdy piłka jest dokładnie nad środkiem, kierunek jest dowolny
        double px = hl < 1e-9 ? cx + radius : cx + hx / hl * radius;
        double pz = hl < 1e-9 ? cz : cz + hz / hl * radius;
        double dx = c.x() - px;
        double dy = c.y() - y;
        double dz = c.z() - pz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double reach = r + tube;
        if (d >= reach) return null;
        V3 n = d < 1e-9 ? V3.UP : new V3(dx / d, dy / d, dz / d);
        return new Contact(Contact.Kind.RIM, n, reach - d, restitution, tangentialLoss);
    }
}
