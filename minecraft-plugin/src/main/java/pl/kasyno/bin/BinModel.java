package pl.kasyno.bin;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import pl.kasyno.bin.BinType.Part;

/**
 * Geometria modelu śmietnika: z opisu bryły (środek, rozmiar, wieko na zawiasie) liczy przekształcenie dla BlockDisplay.
 * BlockDisplay obraca i skaluje wokół swojego rogu, więc przesunięcie musi to wyrównać, żeby bryła stała dokładnie tam, gdzie
 * opisano jej środek. Obrót całego modelu (yaw) kręci wszystko wokół środka podstawy. Same obliczenia, bez Bukkita.
 */
public final class BinModel {
    private BinModel() {}

    /** Przekształcenie bryły: przesunięcie, obrót i skala (jak w Transformation, bez obrotu prawego). */
    public record Xform(Vector3f translation, Quaternionf rotation, Vector3f scale) {}

    /** Kąt otwarcia wieka w stopniach przy pełnym otwarciu. */
    public static final double OPEN_DEGREES = 65;

    /** Obrót modelu zapisany w stopniach, zaokrąglony do 90 (śmietnik stoi równolegle do osi świata). */
    public static int snapYaw(double yawDegrees) {
        long q = Math.round(yawDegrees / 90.0);
        return (int) Math.floorMod(q * 90, 360L);
    }

    /**
     * Obrót modelu, przy którym przód śmietnika (z+) patrzy na gracza stojącego twarzą do niego: yaw gracza w Bukkicie to 0 = południe,
     * a obrót modelu o kąt t kieruje przód na (sin t, cos t), więc potrzebne jest 180 - yaw, zaokrąglone do 90.
     */
    public static int facing(double playerYaw) {
        return snapYaw(180 - playerYaw);
    }

    /** Środek bryły we współrzędnych świata względem podstawy, po otwarciu wieka o `openDeg` i obrocie modelu o `yawDeg`. */
    public static Vector3f center(Part p, double yawDeg, double openDeg) {
        Vector3f c = new Vector3f((float) p.cx(), (float) p.cy(), (float) p.cz());
        if (p.lid() && openDeg != 0) {
            Vector3f hinge = hinge(p);
            Quaternionf open = new Quaternionf().rotateX((float) -Math.toRadians(openDeg));
            c = open.transform(c.sub(hinge, new Vector3f())).add(hinge);
        }
        return new Quaternionf().rotateY((float) Math.toRadians(yawDeg)).transform(c);
    }

    /** Zawias wieka: tylna, dolna krawędź (z- to tył). */
    public static Vector3f hinge(Part p) {
        return new Vector3f((float) p.cx(), (float) (p.cy() - p.sy() / 2), (float) (p.cz() - p.sz() / 2));
    }

    public static Xform xform(Part p, double yawDeg, double openDeg) {
        Quaternionf left = new Quaternionf().rotateY((float) Math.toRadians(yawDeg));
        if (p.lid() && openDeg != 0) left.rotateX((float) -Math.toRadians(openDeg));
        Vector3f scale = new Vector3f((float) p.sx(), (float) p.sy(), (float) p.sz());
        // wierzchołek v z przedziału [0, 1] ląduje w T + L * (S * v), więc środek (v = 0,5) ma trafić w center()
        Vector3f half = left.transform(new Vector3f(scale).mul(0.5f));
        Vector3f translation = center(p, yawDeg, openDeg).sub(half);
        return new Xform(translation, left, scale);
    }

    /** Narożniki bryły po przekształceniu (osiem punktów), do sprawdzania, czy model mieści się w polu kliknięcia. */
    public static Vector3f[] corners(Part p, double yawDeg, double openDeg) {
        Xform x = xform(p, yawDeg, openDeg);
        Vector3f[] out = new Vector3f[8];
        int i = 0;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    Vector3f v = new Vector3f(dx * x.scale().x, dy * x.scale().y, dz * x.scale().z);
                    out[i++] = x.rotation().transform(v).add(x.translation());
                }
            }
        }
        return out;
    }

    /** Nazwa materiału z zapisu bloku ("minecraft:barrel[facing=up]" daje "BARREL"). */
    public static String materialName(String block) {
        String s = block.startsWith("minecraft:") ? block.substring("minecraft:".length()) : block;
        int bracket = s.indexOf('[');
        if (bracket >= 0) s = s.substring(0, bracket);
        return s.toUpperCase(java.util.Locale.ROOT);
    }
}
