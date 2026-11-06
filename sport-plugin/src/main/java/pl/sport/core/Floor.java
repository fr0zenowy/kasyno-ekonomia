package pl.sport.core;

/** Podłoga: półprzestrzeń poniżej wysokości y. */
public record Floor(double y, double restitution, double tangentialLoss) implements Collider {
    @Override
    public Contact test(V3 c, double r) {
        double pen = y - (c.y() - r);
        return pen > 0 ? new Contact(Contact.Kind.FLOOR, V3.UP, pen, restitution, tangentialLoss) : null;
    }
}
