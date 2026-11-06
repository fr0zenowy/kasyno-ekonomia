package pl.sport.core;

/** Stan piłki w locie: położenie środka i prędkość (m i m/s). */
public final class BallBody {
    private V3 pos;
    private V3 vel;
    private boolean onFloor;

    public BallBody(V3 pos, V3 vel) {
        this.pos = pos;
        this.vel = vel;
    }

    public V3 pos() {
        return pos;
    }

    public V3 vel() {
        return vel;
    }

    public void set(V3 pos, V3 vel) {
        this.pos = pos;
        this.vel = vel;
    }

    public void setPos(V3 pos) {
        this.pos = pos;
    }

    public void setVel(V3 vel) {
        this.vel = vel;
    }

    /** Czy w ostatnim kroku piłka toczyła się po podłodze (do rysowania i dźwięków). */
    public boolean onFloor() {
        return onFloor;
    }

    void onFloor(boolean v) {
        this.onFloor = v;
    }
}
