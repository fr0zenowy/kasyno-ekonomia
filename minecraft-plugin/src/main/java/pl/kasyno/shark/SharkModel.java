package pl.kasyno.shark;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Model rekina z klocków (BlockDisplay) i jego animacja: kręgosłup ogona wygina się falą, dolna szczęka otwiera się na zawiasie,
 * a cały rekin jest obracany (kierunek, nachylenie, przechył). Układ lokalny: +z to nos, +y góra, początek w środku korpusu.
 * BlockDisplay obraca i skaluje bryłę wokół swojego rogu, więc przesunięcie wyrównuje to tak, żeby środek bryły stał dokładnie
 * tam, gdzie opisano. Same obliczenia, bez Bukkita.
 */
public final class SharkModel {
    private SharkModel() {}

    /** Kość, do której przyczepiona jest bryła: ogon zagina się w trzech stawach, szczęka na jednym. */
    public enum Bone { ROOT, TAIL1, TAIL2, TAILFIN, JAW }

    /** Bryła: blok, środek, rozmiar, kość i własny obrót (stopnie wokół X, Y, Z). */
    public record Part(String id, String block, double cx, double cy, double cz, double sx, double sy, double sz, Bone bone, double rx, double ry, double rz) {
        Part(String id, String block, double cx, double cy, double cz, double sx, double sy, double sz, Bone bone) {
            this(id, block, cx, cy, cz, sx, sy, sz, bone, 0, 0, 0);
        }
    }

    /** Poza w jednej chwili: faza fali ogona (radiany), jej rozmach i otwarcie szczęki (stopnie). */
    public record Pose(double swimPhase, double tailAmpDeg, double jawOpenDeg) {}

    /** Ustawienie rekina w świecie: kierunek (0 = południe, +z), nachylenie nosa w górę i przechył, w stopniach. */
    public record Orientation(double yawDeg, double pitchDeg, double rollDeg) {}

    /** Przekształcenie BlockDisplay: przesunięcie, obrót i skala. */
    public record Xform(Vector3f translation, Quaternionf rotation, Vector3f scale) {}

    private static final String GRAY = "GRAY_CONCRETE";
    private static final String WHITE = "WHITE_CONCRETE";

    public static final List<Part> PARTS = List.of(
            new Part("grzbiet", GRAY, 0, 0.18, 0.2, 1.00, 0.62, 2.9, Bone.ROOT),
            new Part("brzuch", WHITE, 0, -0.28, 0.2, 0.92, 0.34, 2.9, Bone.ROOT),
            new Part("glowa", GRAY, 0, 0.05, 2.05, 0.84, 0.36, 1.2, Bone.ROOT),
            new Part("pysk", GRAY, 0, 0.0, 2.6, 0.55, 0.3, 0.4, Bone.ROOT),
            new Part("zeby_gorne", WHITE, 0, -0.17, 2.1, 0.7, 0.1, 0.9, Bone.ROOT),
            new Part("oko_p", "BLACK_CONCRETE", 0.44, 0.15, 2.0, 0.1, 0.1, 0.1, Bone.ROOT),
            new Part("oko_l", "BLACK_CONCRETE", -0.44, 0.15, 2.0, 0.1, 0.1, 0.1, Bone.ROOT),
            new Part("szczeka", WHITE, 0, -0.36, 1.95, 0.78, 0.2, 1.15, Bone.JAW),
            new Part("paszcza", "RED_CONCRETE", 0, -0.25, 1.95, 0.66, 0.06, 1.0, Bone.JAW),
            new Part("pletwa_grzbietowa", GRAY, 0, 0.78, 0.55, 0.1, 0.75, 0.75, Bone.ROOT, -20, 0, 0),
            new Part("pletwa_p", GRAY, 0.72, -0.32, 1.0, 0.7, 0.07, 0.55, Bone.ROOT, 0, 0, -28),
            new Part("pletwa_l", GRAY, -0.72, -0.32, 1.0, 0.7, 0.07, 0.55, Bone.ROOT, 0, 0, 28),
            new Part("ogon1", GRAY, 0, 0.02, -1.55, 0.74, 0.58, 1.0, Bone.TAIL1),
            new Part("ogon2", GRAY, 0, 0.0, -2.3, 0.42, 0.42, 0.75, Bone.TAIL2),
            new Part("ster_gorny", GRAY, 0, 0.35, -2.85, 0.09, 0.85, 0.5, Bone.TAILFIN, -25, 0, 0),
            new Part("ster_dolny", GRAY, 0, -0.3, -2.8, 0.09, 0.55, 0.4, Bone.TAILFIN, 25, 0, 0));

    /** Zawiasy kości: punkty, wokół których obracają się ogon (trzy człony) i szczęka. */
    private static final Vector3f TAIL1_HINGE = new Vector3f(0f, 0f, -0.85f);
    private static final Vector3f TAIL2_HINGE = new Vector3f(0f, 0f, -1.75f);
    private static final Vector3f TAILFIN_HINGE = new Vector3f(0f, 0f, -2.6f);
    private static final Vector3f JAW_HINGE = new Vector3f(0f, -0.25f, 1.35f);
    /** Czubek pyska (tam trafia zjadany gracz) w układzie rekina. */
    private static final Vector3f MOUTH = new Vector3f(0f, -0.12f, 2.75f);

    /** Przesunięcie czubka pyska względem środka rekina po ustawieniu go w świecie. */
    public static Vector3f mouthOffset(Orientation o) {
        return orientation(o).transform(new Vector3f(MOUTH));
    }

    /** Punkt z układu rekina przeniesiony do świata (względem środka rekina). */
    public static Vector3f toWorld(Orientation o, Vector3f local) {
        return orientation(o).transform(new Vector3f(local));
    }

    static Quaternionf orientation(Orientation o) {
        return new Quaternionf().rotateY((float) Math.toRadians(o.yawDeg())).rotateX((float) -Math.toRadians(o.pitchDeg())).rotateZ((float) Math.toRadians(o.rollDeg()));
    }

    /** Macierz kości: łańcuch obrotów od korzenia (ogon: każdy człon dziedziczy ruch poprzedniego, a fala opóźnia się o kolejną fazę). */
    private static Matrix4f boneMatrix(Bone bone, Pose pose) {
        Matrix4f m = new Matrix4f();
        double amp = pose.tailAmpDeg();
        double a1 = amp * Math.sin(pose.swimPhase());
        double a2 = amp * 1.6 * Math.sin(pose.swimPhase() - 0.7);
        double a3 = amp * 2.2 * Math.sin(pose.swimPhase() - 1.4);
        switch (bone) {
            case TAIL1 -> swing(m, TAIL1_HINGE, a1);
            case TAIL2 -> {
                swing(m, TAIL1_HINGE, a1);
                swing(m, TAIL2_HINGE, a2);
            }
            case TAILFIN -> {
                swing(m, TAIL1_HINGE, a1);
                swing(m, TAIL2_HINGE, a2);
                swing(m, TAILFIN_HINGE, a3);
            }
            case JAW -> m.translate(JAW_HINGE).rotateX((float) Math.toRadians(pose.jawOpenDeg())).translate(new Vector3f(JAW_HINGE).negate());
            default -> {
            }
        }
        return m;
    }

    private static void swing(Matrix4f m, Vector3f hinge, double degrees) {
        m.translate(hinge).rotateY((float) Math.toRadians(degrees)).translate(new Vector3f(hinge).negate());
    }

    /** Przekształcenie bryły dla danej pozy i ustawienia rekina (względem jego środka). */
    public static Xform xform(Part p, Pose pose, Orientation o) {
        Matrix4f bone = boneMatrix(p.bone(), pose);
        Vector3f center = bone.transformPosition(new Vector3f((float) p.cx(), (float) p.cy(), (float) p.cz()));
        Quaternionf boneRot = bone.getNormalizedRotation(new Quaternionf());
        Quaternionf s = orientation(o);
        Quaternionf partRot = new Quaternionf().rotateY((float) Math.toRadians(p.ry())).rotateX((float) Math.toRadians(p.rx())).rotateZ((float) Math.toRadians(p.rz()));
        Quaternionf rotation = new Quaternionf(s).mul(boneRot).mul(partRot);
        Vector3f scale = new Vector3f((float) p.sx(), (float) p.sy(), (float) p.sz());
        Vector3f worldCenter = s.transform(center);
        Vector3f translation = worldCenter.sub(rotation.transform(new Vector3f(scale).mul(0.5f)), new Vector3f());
        return new Xform(translation, rotation, scale);
    }

    /** Środek bryły po przekształceniu (przesunięcie + obrócona połowa rozmiaru): tam bryła naprawdę stoi. */
    public static Vector3f centerOf(Xform x) {
        return x.rotation().transform(new Vector3f(x.scale()).mul(0.5f)).add(x.translation());
    }

    /** Nazwa materiału bryły. */
    public static String block(Part p) {
        return p.block();
    }
}
