package pl.sport.core;

/** Prostopadłościan równoległy do osi (siatka, słupek, tablica, ściana). Kula zderza się z jego powierzchnią, także z krawędziami i rogami. */
public record Box(Contact.Kind kind, V3 min, V3 max, double restitution, double tangentialLoss) implements Collider {
    @Override
    public Contact test(V3 c, double r) {
        double qx = Math.max(min.x(), Math.min(c.x(), max.x()));
        double qy = Math.max(min.y(), Math.min(c.y(), max.y()));
        double qz = Math.max(min.z(), Math.min(c.z(), max.z()));
        double dx = c.x() - qx;
        double dy = c.y() - qy;
        double dz = c.z() - qz;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > 1e-18) {
            if (d2 >= r * r) return null;
            double d = Math.sqrt(d2);
            return new Contact(kind, new V3(dx / d, dy / d, dz / d), r - d, restitution, tangentialLoss);
        }
        // środek piłki leży wewnątrz bryły: wypychamy ją przez najbliższą ścianę
        double[] gaps = {c.x() - min.x(), max.x() - c.x(), c.y() - min.y(), max.y() - c.y(), c.z() - min.z(), max.z() - c.z()};
        V3[] normals = {new V3(-1, 0, 0), new V3(1, 0, 0), new V3(0, -1, 0), new V3(0, 1, 0), new V3(0, 0, -1), new V3(0, 0, 1)};
        int best = 0;
        for (int i = 1; i < 6; i++) if (gaps[i] < gaps[best]) best = i;
        return new Contact(kind, normals[best], gaps[best] + r, restitution, tangentialLoss);
    }
}
