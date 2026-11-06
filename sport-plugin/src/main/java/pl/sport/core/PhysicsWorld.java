package pl.sport.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Świat fizyki jednej piłki: grawitacja, kwadratowy opór powietrza i zderzenia ze sprężystymi przeszkodami. Krok jest dzielony tak,
 * żeby piłka nie przesuwała się o więcej niż {@link #MAX_MOVE} metra na podkrok, więc nawet najszybsza piłka nie przeskoczy przez cienką siatkę
 * ani tablicę.
 */
public final class PhysicsWorld {
    /** Największe przesunięcie środka piłki w jednym podkroku. Mniejsze od promienia piłki i grubości tablicy. */
    public static final double MAX_MOVE = 0.04;
    /** Poniżej tej prędkości zderzenie jest niesprężyste, żeby piłka nie drgała na podłodze. */
    public static final double REST_SPEED = 0.7;
    private static final int MAX_SUBSTEPS = 400;

    /** Odbiorca zdarzeń zderzeń (dźwięki, zasady gry). */
    @FunctionalInterface
    public interface Listener {
        /** @param impact prędkość uderzenia w kierunku normalnej, m/s (zawsze dodatnia) */
        void onContact(Contact contact, double impact, V3 position);
    }

    /** Odbiorca ruchu: wołany po każdym podkroku z poprzednim i nowym położeniem środka piłki (np. czujnik kosza). */
    @FunctionalInterface
    public interface MoveListener {
        void onMove(V3 from, V3 to);
    }

    private final BallSpec spec;
    private final List<Collider> colliders = new ArrayList<>();
    private double reportFrom = 0.5;

    public PhysicsWorld(BallSpec spec) {
        this.spec = spec;
    }

    public BallSpec spec() {
        return spec;
    }

    /** Od jakiej prędkości uderzenia (m/s) zderzenie jest zgłaszane odbiorcy zdarzeń. Domyślnie 0,5, a lekka piłeczka potrzebuje mniej. */
    public PhysicsWorld reportFrom(double impact) {
        this.reportFrom = impact;
        return this;
    }

    public PhysicsWorld add(Collider c) {
        colliders.add(c);
        return this;
    }

    public List<Collider> colliders() {
        return colliders;
    }

    /** Przyspieszenie przy prędkości v: grawitacja i opór. */
    private V3 accel(V3 v) {
        double speed = v.length();
        return new V3(-spec.dragK() * speed * v.x(), -spec.gravity() - spec.dragK() * speed * v.y(), -spec.dragK() * speed * v.z());
    }

    /** Posuwa piłkę o dt sekund. */
    public void step(BallBody b, double dt, Listener listener) {
        step(b, dt, listener, null);
    }

    /** Posuwa piłkę o dt sekund i zgłasza każdy podkrok ruchu. */
    public void step(BallBody b, double dt, Listener listener, MoveListener moves) {
        double speed = b.vel().length();
        int n = (int) Math.max(1, Math.min(MAX_SUBSTEPS, Math.ceil(speed * dt / MAX_MOVE)));
        double h = dt / n;
        boolean floorTouched = false;
        for (int i = 0; i < n; i++) {
            // metoda punktu środkowego (RK2): dokładniejsza od Eulera przy tym samym koszcie
            V3 v = b.vel();
            V3 vMid = v.plus(accel(v).times(h / 2));
            V3 pos = b.pos().plus(vMid.times(h));
            V3 vel = v.plus(accel(vMid).times(h));
            for (int pass = 0; pass < 4; pass++) {
                boolean hit = false;
                for (Collider c : colliders) {
                    Contact k = c.test(pos, spec.radius());
                    if (k == null) continue;
                    hit = true;
                    pos = pos.plus(k.normal().times(k.depth()));
                    double vn = vel.dot(k.normal());
                    if (vn < 0) {
                        double impact = -vn;
                        double e = impact < REST_SPEED ? 0 : k.restitution();
                        V3 normalPart = k.normal().times(-e * vn);
                        V3 tangential = vel.minus(k.normal().times(vn)).times(1 - k.tangentialLoss());
                        vel = tangential.plus(normalPart);
                        if (k.kind() == Contact.Kind.FLOOR) {
                            floorTouched = true;
                            if (impact < REST_SPEED) vel = rollingFriction(vel, h);
                        }
                        if (listener != null && impact > reportFrom) listener.onContact(k, impact, pos);
                    } else if (k.kind() == Contact.Kind.FLOOR) {
                        floorTouched = true;
                        vel = rollingFriction(vel, h);
                    }
                }
                if (!hit) break;
            }
            V3 before = b.pos();
            b.set(pos, vel);
            if (moves != null) moves.onMove(before, pos);
        }
        b.onFloor(floorTouched);
    }

    private V3 rollingFriction(V3 v, double h) {
        double hs = v.horizontalLength();
        if (hs < 1e-9) return v;
        double slowed = Math.max(0, hs - spec.rollDecel() * h);
        double k = slowed / hs;
        return new V3(v.x() * k, v.y(), v.z() * k);
    }

    /** Symuluje lot przez `seconds` sekund krokami po dt (przydatne w testach). */
    public void run(BallBody b, double seconds, double dt, Listener listener) {
        for (double t = 0; t < seconds - 1e-9; t += dt) step(b, Math.min(dt, seconds - t), listener);
    }
}
