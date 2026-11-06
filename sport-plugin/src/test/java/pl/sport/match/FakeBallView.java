package pl.sport.match;

import org.bukkit.Location;

/** Widok piłki bez encji do testów: zapamiętuje ostatnie położenie. */
public final class FakeBallView implements BallView {
    public Location last;
    public double floorY;
    public boolean removed;
    public int moves;

    public FakeBallView(Location where) {
        this.last = where;
    }

    @Override
    public void move(Location center, double floorY) {
        this.last = center;
        this.floorY = floorY;
        moves++;
    }

    @Override
    public void remove() {
        removed = true;
    }

    @Override
    public boolean valid() {
        return !removed;
    }
}
