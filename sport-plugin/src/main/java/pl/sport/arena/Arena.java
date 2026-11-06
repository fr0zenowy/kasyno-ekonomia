package pl.sport.arena;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import pl.sport.core.V3;

/**
 * Boisko w świecie: nazwa, dyscyplina, położenie początku układu lokalnego i kierunek osi. Układ lokalny: u wzdłuż dłuższego boku
 * (kierunek dirU, jedna z czterech stron świata), v wzdłuż krótszego (dirV = dirU obrócone o 90 stopni), y w górę od powierzchni
 * podłogi. Całe boisko zajmuje u w [0, length] i v w [0, width]. Cała fizyka i zasady liczą się w układzie lokalnym, a dopiero
 * widok (piłka, gracze) przelicza go na współrzędne świata.
 */
public final class Arena {
    public enum Sport {
        VOLLEYBALL("siatkowka", "Siatkówka"),
        BASKETBALL("koszykowka", "Koszykówka"),
        PINGPONG("pingpong", "Ping-pong");

        private final String key;
        private final String label;

        Sport(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public static Sport byKey(String k) {
            for (Sport s : values()) if (s.key.equalsIgnoreCase(k) || s.name().equalsIgnoreCase(k)) return s;
            return null;
        }
    }

    /** Kierunki u: 0 = +X, 1 = +Z, 2 = -X, 3 = -Z. */
    private static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private final String name;
    private final Sport sport;
    private final String worldName;
    private final double ox;
    private final double oy;
    private final double oz;
    private final int dir;
    private final int length;
    private final int width;
    private final double netHeight;

    public Arena(String name, Sport sport, String worldName, double ox, double oy, double oz, int dir, int length, int width, double netHeight) {
        this.name = name;
        this.sport = sport;
        this.worldName = worldName;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.dir = Math.floorMod(dir, 4);
        this.length = length;
        this.width = width;
        this.netHeight = netHeight;
    }

    public String name() {
        return name;
    }

    public Sport sport() {
        return sport;
    }

    public String worldName() {
        return worldName;
    }

    public int length() {
        return length;
    }

    public int width() {
        return width;
    }

    public double netHeight() {
        return netHeight;
    }

    public int dir() {
        return dir;
    }

    public double originX() {
        return ox;
    }

    public double originY() {
        return oy;
    }

    public double originZ() {
        return oz;
    }

    private int ux() {
        return DIRS[dir][0];
    }

    private int uz() {
        return DIRS[dir][1];
    }

    /** dirV = dirU obrócone o +90 stopni: (x, z) -> (-z, x). */
    private int vx() {
        return -DIRS[dir][1];
    }

    private int vz() {
        return DIRS[dir][0];
    }

    /** Punkt układu lokalnego w świecie. */
    public Location toWorld(World world, V3 local) {
        double x = ox + ux() * local.x() + vx() * local.z();
        double z = oz + uz() * local.x() + vz() * local.z();
        return new Location(world, x, oy + local.y(), z);
    }

    public double worldX(V3 local) {
        return ox + ux() * local.x() + vx() * local.z();
    }

    public double worldZ(V3 local) {
        return oz + uz() * local.x() + vz() * local.z();
    }

    /** Punkt świata w układzie lokalnym. */
    public V3 toLocal(double x, double y, double z) {
        double dx = x - ox;
        double dz = z - oz;
        return new V3(dx * ux() + dz * uz(), y - oy, dx * vx() + dz * vz());
    }

    public V3 toLocal(Location l) {
        return toLocal(l.getX(), l.getY(), l.getZ());
    }

    /** Wektor kierunku lokalnego na wektor świata (bez przesunięcia). */
    public V3 dirToWorld(V3 local) {
        return new V3(ux() * local.x() + vx() * local.z(), local.y(), uz() * local.x() + vz() * local.z());
    }

    /** Wektor świata na kierunek lokalny. */
    public V3 dirToLocal(V3 world) {
        return new V3(world.x() * ux() + world.z() * uz(), world.y(), world.x() * vx() + world.z() * vz());
    }

    /** Yaw Minecrafta (stopnie), który patrzy w lokalny kierunek poziomy `local`. */
    public float yawOf(V3 local) {
        V3 w = dirToWorld(local);
        return (float) Math.toDegrees(Math.atan2(-w.x(), w.z()));
    }

    /** Czy punkt świata leży nad boiskiem powiększonym o margines (blokach) i nie wyżej niż `height` ponad podłogą. */
    public boolean contains(Location l, double margin, double height) {
        if (l.getWorld() == null || !l.getWorld().getName().equals(worldName)) return false;
        V3 p = toLocal(l);
        return p.x() >= -margin && p.x() <= length + margin && p.z() >= -margin && p.z() <= width + margin && p.y() >= -3 && p.y() <= height;
    }

    public void save(ConfigurationSection s) {
        s.set("sport", sport.key());
        s.set("world", worldName);
        s.set("origin.x", ox);
        s.set("origin.y", oy);
        s.set("origin.z", oz);
        s.set("dir", dir);
        s.set("length", length);
        s.set("width", width);
        s.set("net-height", netHeight);
    }

    public static Arena load(String name, ConfigurationSection s) {
        Sport sport = Sport.byKey(s.getString("sport", ""));
        String world = s.getString("world");
        if (sport == null || world == null) return null;
        return new Arena(name, sport, world, s.getDouble("origin.x"), s.getDouble("origin.y"), s.getDouble("origin.z"), s.getInt("dir"),
                s.getInt("length", 18), s.getInt("width", 9), s.getDouble("net-height", 2.43));
    }
}
