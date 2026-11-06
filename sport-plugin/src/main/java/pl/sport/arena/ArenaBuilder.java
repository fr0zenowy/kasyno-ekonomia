package pl.sport.arena;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import pl.sport.SportPlugin;
import pl.sport.basket.BasketCourt;
import pl.sport.config.SportConfig;
import pl.sport.core.V3;
import pl.sport.pingpong.PingPongTable;
import pl.sport.volley.VolleyCourt;

import java.util.HashSet;
import java.util.Set;

/**
 * Buduje boiska: podłoga z liniami z bloków oraz elementy, które nie leżą na siatce bloków (siatka, słupki, tablice, obręcze),
 * jako encje BlockDisplay o dokładnych wymiarach. Wszystkie encje mają znacznik boiska, więc dają się usunąć razem z nim.
 */
public final class ArenaBuilder {
    private static final int MARGIN = 3;
    private static final int CLEAR_HEIGHT = 8;

    private final SportPlugin plugin;
    private final NamespacedKey decorKey;

    public ArenaBuilder(SportPlugin plugin) {
        this.plugin = plugin;
        this.decorKey = new NamespacedKey(plugin, "arena_decor");
    }

    /** Domyślne wymiary boiska danej dyscypliny z konfiguracji. */
    public int defaultLength(Arena.Sport sport) {
        SportConfig c = plugin.sportConfig();
        return switch (sport) {
            case VOLLEYBALL -> c.integer("siatkowka.dlugosc-boiska", 18);
            case BASKETBALL -> c.integer("koszykowka.dlugosc-boiska", 28);
            case PINGPONG -> c.integer("pingpong.dlugosc-boiska", 12);
        };
    }

    public int defaultWidth(Arena.Sport sport) {
        SportConfig c = plugin.sportConfig();
        return switch (sport) {
            case VOLLEYBALL -> c.integer("siatkowka.szerokosc-boiska", 9);
            case BASKETBALL -> c.integer("koszykowka.szerokosc-boiska", 15);
            case PINGPONG -> c.integer("pingpong.szerokosc-boiska", 8);
        };
    }

    /** Kierunek u (0 = +X, 1 = +Z, 2 = -X, 3 = -Z) najbliższy kierunkowi patrzenia (yaw w stopniach). */
    public static int dirFromYaw(float yaw) {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r);
        double fz = Math.cos(r);
        if (Math.abs(fx) >= Math.abs(fz)) return fx >= 0 ? 0 : 2;
        return fz >= 0 ? 1 : 3;
    }

    /** Tworzy boisko tak, że gracz stoi na środku jednej linii końcowej i patrzy w głąb boiska. */
    public Arena create(Location at, Arena.Sport sport, String name, int length, int width) {
        World w = at.getWorld();
        int dir = dirFromYaw(at.getYaw());
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        int ux = dirs[dir][0];
        int uz = dirs[dir][1];
        int vx = -uz;
        int vz = ux;
        int half = width / 2;
        double ox = at.getBlockX() - vx * half;
        double oz = at.getBlockZ() - vz * half;
        // początek leży na krawędzi bloku: dla dodatnich osi to róg komórki, dla ujemnych trzeba przesunąć o 1
        if (ux < 0 || vx < 0) ox += 1;
        if (uz < 0 || vz < 0) oz += 1;
        double oy = at.getBlockY();
        // wysokość siatki zapisana w boisku: w ping-pongu z niej wynika skala stołu
        double net = sport == Arena.Sport.PINGPONG ? PingPongTable.REAL_NET * plugin.sportConfig().pingpongScale() : plugin.sportConfig().volleyNetHeight();
        Arena a = new Arena(name, sport, w.getName(), ox, oy, oz, dir, length, width, net);
        build(a);
        return a;
    }

    // ---------- budowa ----------

    public void build(Arena a) {
        World w = plugin.getServer().getWorld(a.worldName());
        if (w == null) throw new IllegalStateException("Brak świata " + a.worldName());
        clearDecor(a);
        buildFloor(w, a);
        switch (a.sport()) {
            case VOLLEYBALL -> volleyDecor(w, a);
            case BASKETBALL -> basketDecor(w, a);
            case PINGPONG -> pingpongDecor(w, a);
        }
    }

    private Block cellBlock(World w, Arena a, int i, int j, int y) {
        V3 c = new V3(i + 0.5, 0, j + 0.5);
        int bx = (int) Math.floor(a.worldX(c));
        int bz = (int) Math.floor(a.worldZ(c));
        return w.getBlockAt(bx, (int) a.originY() + y, bz);
    }

    private void buildFloor(World w, Arena a) {
        SportConfig c = plugin.sportConfig();
        Arena.Sport sport = a.sport();
        String p = switch (sport) {
            case VOLLEYBALL -> "siatkowka.";
            case BASKETBALL -> "koszykowka.";
            case PINGPONG -> "pingpong.";
        };
        Material courtDefault = switch (sport) {
            case VOLLEYBALL -> Material.SMOOTH_SANDSTONE;
            case BASKETBALL -> Material.BIRCH_PLANKS;
            case PINGPONG -> Material.SMOOTH_STONE;
        };
        Material court = c.material(p + "material-podlogi", courtDefault);
        Material outside = c.material(p + "material-obrzeza", Material.LIGHT_GRAY_CONCRETE);
        Material line = c.material(p + "material-linii", Material.WHITE_CONCRETE);
        Material line2 = c.material(p + "material-linii-dodatkowych", sport == Arena.Sport.VOLLEYBALL ? Material.LIGHT_BLUE_CONCRETE : Material.RED_TERRACOTTA);

        BasketCourt bc = sport == Arena.Sport.BASKETBALL ? new BasketCourt(a.length(), a.width()) : null;
        for (int i = -MARGIN; i < a.length() + MARGIN; i++) {
            for (int j = -MARGIN; j < a.width() + MARGIN; j++) {
                boolean inside = i >= 0 && i < a.length() && j >= 0 && j < a.width();
                Material m;
                if (!inside) m = outside;
                else m = switch (sport) {
                    case VOLLEYBALL -> volleyCell(a, i, j, court, line, line2);
                    case BASKETBALL -> basketCell(a, bc, i, j, court, line, line2);
                    case PINGPONG -> i == 0 || j == 0 || i == a.length() - 1 || j == a.width() - 1 ? line : court;
                };
                cellBlock(w, a, i, j, -1).setType(m, false);
                for (int y = 0; y < CLEAR_HEIGHT; y++) {
                    Block b = cellBlock(w, a, i, j, y);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
            }
        }
    }

    private Material volleyCell(Arena a, int i, int j, Material court, Material line, Material attack) {
        if (i == 0 || j == 0 || i == a.length() - 1 || j == a.width() - 1) return line;
        int mid = a.length() / 2;
        if (i == mid - 3 || i == mid + 2) return attack;
        if (i == mid - 1 || i == mid) return court;
        return court;
    }

    private Material basketCell(Arena a, BasketCourt bc, int i, int j, Material court, Material line, Material key) {
        int len = a.length();
        int wid = a.width();
        if (i == 0 || j == 0 || i == len - 1 || j == wid - 1) return line;
        if (i == len / 2 - 1 || i == len / 2) return line; // linia środkowa
        double u = i + 0.5;
        double v = j + 0.5;
        // koło środkowe
        double cr = Math.hypot(u - len / 2.0, v - wid / 2.0);
        if (Math.abs(cr - 1.8) < 0.5) return line;
        // pole podkoszowe (szerokie na 4,9 m, długie na 5,8 m od linii końcowej)
        double keyHalf = Math.min(2.45, wid / 2.0 - 1.5);
        for (int hoop = 0; hoop < 2; hoop++) {
            double edge = hoop == 0 ? 5.8 : len - 5.8;
            boolean inLength = hoop == 0 ? u < 5.8 : u > edge;
            boolean inWidth = Math.abs(v - wid / 2.0) <= keyHalf;
            if (inLength && inWidth) {
                boolean onEdge = Math.abs(u - edge) < 0.5 || Math.abs(Math.abs(v - wid / 2.0) - keyHalf) < 0.5;
                if (onEdge) return line;
                return key;
            }
            // łuk za trzy punkty
            V3 rim = bc.rimCenter(hoop);
            double d = Math.hypot(u - rim.x(), v - rim.z());
            boolean forward = hoop == 0 ? u > rim.x() : u < rim.x();
            if (forward && Math.abs(d - bc.threePointLine()) < 0.5) return line;
        }
        return court;
    }

    // ---------- elementy z encji ----------

    /** Prostopadłościan z BlockDisplay o osiach równoległych do osi boiska: od `min` do `max` w układzie lokalnym. */
    private BlockDisplay box(World w, Arena a, Material mat, V3 min, V3 max, String tag) {
        Location c0 = a.toWorld(w, min);
        Location c1 = a.toWorld(w, max);
        double x = Math.min(c0.getX(), c1.getX());
        double y = Math.min(c0.getY(), c1.getY());
        double z = Math.min(c0.getZ(), c1.getZ());
        Vector3f size = new Vector3f((float) Math.abs(c1.getX() - c0.getX()), (float) Math.abs(c1.getY() - c0.getY()), (float) Math.abs(c1.getZ() - c0.getZ()));
        ensureLoaded(w, x, z);
        return w.spawn(new Location(w, x, y, z), BlockDisplay.class, d -> {
            d.setBlock(mat.createBlockData());
            safe(() -> d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), size, new AxisAngle4f())));
            decorate(d, a, tag);
        });
    }

    /** Odcinek o środku w punkcie lokalnym `center`, długości `len` w kierunku poziomym `dirLocal`, o przekroju thick x thick. */
    private BlockDisplay segment(World w, Arena a, Material mat, V3 center, V3 dirLocal, double len, double thick, String tag) {
        Location c = a.toWorld(w, center);
        V3 wd = a.dirToWorld(dirLocal).horizontal().normalized();
        float phi = (float) Math.atan2(-wd.z(), wd.x());
        Quaternionf rot = new Quaternionf().rotateY(phi);
        Vector3f size = new Vector3f((float) len, (float) thick, (float) thick);
        Vector3f t = new Vector3f(-size.x / 2, -size.y / 2, -size.z / 2).rotate(rot);
        ensureLoaded(w, c.getX(), c.getZ());
        return w.spawn(c, BlockDisplay.class, d -> {
            d.setBlock(mat.createBlockData());
            safe(() -> d.setTransformation(new Transformation(t, rot, size, new Quaternionf())));
            decorate(d, a, tag);
        });
    }

    /** Ustawienia wyglądu są kosmetyczne: gdy któreś nie jest dostępne (np. w atrapie serwera), element i tak ma powstać. */
    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // niedostępne w tym środowisku (np. w atrapie serwera)
        }
    }

    private void decorate(Display d, Arena a, String tag) {
        d.setPersistent(true);
        safe(() -> d.setViewRange(1.5f));
        d.getPersistentDataContainer().set(decorKey, PersistentDataType.STRING, a.name() + ":" + tag);
    }

    private void ensureLoaded(World w, double x, double z) {
        w.getChunkAt((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4).load(true);
    }

    private void volleyDecor(World w, Arena a) {
        VolleyCourt vc = new VolleyCourt(a.length(), a.width(), a.netHeight());
        double nu = vc.netU();
        double h = a.netHeight();
        double span0 = -VolleyCourt.POST_OFFSET;
        double span1 = a.width() + VolleyCourt.POST_OFFSET;
        // siatka: półprzezroczysty pas i białe taśmy
        box(w, a, Material.WHITE_STAINED_GLASS, new V3(nu - 0.02, h - VolleyCourt.NET_BAND, span0), new V3(nu + 0.02, h, span1), "net");
        box(w, a, Material.WHITE_CONCRETE, new V3(nu - 0.035, h - 0.1, span0), new V3(nu + 0.035, h + 0.02, span1), "tape-top");
        box(w, a, Material.WHITE_CONCRETE, new V3(nu - 0.03, h - VolleyCourt.NET_BAND, span0), new V3(nu + 0.03, h - VolleyCourt.NET_BAND + 0.05, span1), "tape-bottom");
        for (double v : new double[]{span0, span1}) {
            box(w, a, Material.STRIPPED_OAK_LOG, new V3(nu - 0.07, 0, v - 0.07), new V3(nu + 0.07, h + 0.12, v + 0.07), "post");
        }
        // anteny wyznaczają przestrzeń przejścia piłki
        for (double v : new double[]{0, a.width()}) {
            box(w, a, Material.RED_CONCRETE, new V3(nu - 0.02, h, v - 0.02), new V3(nu + 0.02, h + 0.8, v + 0.02), "antenna");
        }
    }

    /** Stół: blat z białymi liniami, cztery nogi ze wzmocnieniem, siatka z taśmą i dwoma uchwytami. Wszystko to BlockDisplay o dokładnych wymiarach. */
    private void pingpongDecor(World w, Arena a) {
        SportConfig c = plugin.sportConfig();
        double scale = PingPongTable.scaleOfNet(a.netHeight());
        PingPongTable t = new PingPongTable(a.length(), a.width(), scale);
        Material top = c.material("pingpong.material-stolu", Material.BLUE_CONCRETE);
        Material lines = c.material("pingpong.material-linii-stolu", Material.WHITE_CONCRETE);
        double h = t.topY();
        double th = PingPongTable.TOP_THICKNESS;
        double u0 = t.u0();
        double u1 = t.u1();
        double v0 = t.v0();
        double v1 = t.v1();
        box(w, a, top, new V3(u0, h - th, v0), new V3(u1, h, v1), "top");
        // białe linie na blacie: dwa boki, dwa końce i linia środkowa (do gry podwójnej)
        double lw = 0.02 * scale;
        double ly = 0.004;
        double mid = (v0 + v1) / 2;
        box(w, a, lines, new V3(u0, h, v0), new V3(u1, h + ly, v0 + lw), "line-side-0");
        box(w, a, lines, new V3(u0, h, v1 - lw), new V3(u1, h + ly, v1), "line-side-1");
        box(w, a, lines, new V3(u0, h, v0), new V3(u0 + lw, h + ly, v1), "line-end-0");
        box(w, a, lines, new V3(u1 - lw, h, v0), new V3(u1, h + ly, v1), "line-end-1");
        box(w, a, lines, new V3(u0, h, mid - lw / 2), new V3(u1, h + ly, mid + lw / 2), "line-center");
        // nogi i poprzeczki
        double leg = 0.07 * scale;
        double inU = 0.25 * scale;
        double inV = 0.10 * scale;
        for (double lu : new double[]{u0 + inU, u1 - inU}) {
            for (double lv : new double[]{v0 + inV, v1 - inV}) {
                box(w, a, Material.GRAY_CONCRETE, new V3(lu - leg / 2, 0, lv - leg / 2), new V3(lu + leg / 2, h - th, lv + leg / 2), "leg");
            }
            box(w, a, Material.GRAY_CONCRETE, new V3(lu - 0.02, 0.45 * h, v0 + inV), new V3(lu + 0.02, 0.45 * h + 0.05, v1 - inV), "brace");
        }
        // siatka: półprzezroczysty pas, biała taśma u góry i dwa czarne uchwyty po bokach stołu
        double nu = t.netU();
        double nh = t.netHeight();
        double over = t.overhang();
        box(w, a, Material.WHITE_STAINED_GLASS, new V3(nu - 0.01, h + 0.02, v0 - over), new V3(nu + 0.01, h + nh - 0.02, v1 + over), "net");
        box(w, a, Material.WHITE_CONCRETE, new V3(nu - 0.015, h + nh - 0.025, v0 - over), new V3(nu + 0.015, h + nh, v1 + over), "tape-top");
        for (double v : new double[]{v0 - over, v1 + over}) {
            box(w, a, Material.BLACK_CONCRETE, new V3(nu - 0.03, h, v - 0.02), new V3(nu + 0.03, h + nh + 0.02, v + 0.02), "post");
        }
    }

    private void basketDecor(World w, Arena a) {
        BasketCourt bc = new BasketCourt(a.length(), a.width());
        double mid = a.width() / 2.0;
        double half = BasketCourt.BOARD_WIDTH / 2;
        double bottom = BasketCourt.BOARD_BOTTOM;
        double top = bottom + BasketCourt.BOARD_HEIGHT;
        double th = BasketCourt.BOARD_THICKNESS;
        for (int hoop = 0; hoop < 2; hoop++) {
            double bu = hoop == 0 ? BasketCourt.BOARD_OFFSET : a.length() - BasketCourt.BOARD_OFFSET;
            // tablica ze szkła z białą ramką i czerwonym kwadratem nad obręczą
            box(w, a, Material.WHITE_STAINED_GLASS, new V3(bu - th / 2, bottom, mid - half), new V3(bu + th / 2, top, mid + half), "board-" + hoop);
            double f = 0.05;
            box(w, a, Material.WHITE_CONCRETE, new V3(bu - th / 2 - 0.005, top - f, mid - half), new V3(bu + th / 2 + 0.005, top, mid + half), "frame-top-" + hoop);
            box(w, a, Material.WHITE_CONCRETE, new V3(bu - th / 2 - 0.005, bottom, mid - half), new V3(bu + th / 2 + 0.005, bottom + f, mid + half), "frame-bottom-" + hoop);
            box(w, a, Material.WHITE_CONCRETE, new V3(bu - th / 2 - 0.005, bottom, mid - half), new V3(bu + th / 2 + 0.005, top, mid - half + f), "frame-left-" + hoop);
            box(w, a, Material.WHITE_CONCRETE, new V3(bu - th / 2 - 0.005, bottom, mid + half - f), new V3(bu + th / 2 + 0.005, top, mid + half), "frame-right-" + hoop);
            double tt = 0.03;
            double ty0 = BasketCourt.RIM_HEIGHT;
            double ty1 = ty0 + 0.45;
            box(w, a, Material.RED_CONCRETE, new V3(bu - th / 2 - 0.01, ty0, mid - 0.3), new V3(bu + th / 2 + 0.01, ty0 + tt, mid + 0.3), "target-bottom-" + hoop);
            box(w, a, Material.RED_CONCRETE, new V3(bu - th / 2 - 0.01, ty1 - tt, mid - 0.3), new V3(bu + th / 2 + 0.01, ty1, mid + 0.3), "target-top-" + hoop);
            box(w, a, Material.RED_CONCRETE, new V3(bu - th / 2 - 0.01, ty0, mid - 0.3), new V3(bu + th / 2 + 0.01, ty1, mid - 0.3 + tt), "target-left-" + hoop);
            box(w, a, Material.RED_CONCRETE, new V3(bu - th / 2 - 0.01, ty0, mid + 0.3 - tt), new V3(bu + th / 2 + 0.01, ty1, mid + 0.3), "target-right-" + hoop);
            // obręcz: 16 odcinków ułożonych w okrąg
            V3 rc = bc.rimCenter(hoop);
            int n = 16;
            double seg = 2 * Math.PI * BasketCourt.RIM_RADIUS / n * 1.12;
            for (int k = 0; k < n; k++) {
                double ang = 2 * Math.PI * k / n;
                V3 p = new V3(rc.x() + Math.cos(ang) * BasketCourt.RIM_RADIUS, rc.y(), rc.z() + Math.sin(ang) * BasketCourt.RIM_RADIUS);
                V3 tangent = new V3(-Math.sin(ang), 0, Math.cos(ang));
                segment(w, a, Material.ORANGE_CONCRETE, p, tangent, seg, 0.05, "rim-" + hoop + "-" + k);
            }
            // siatka pod obręczą: biały, półprzezroczysty walec zbudowany z czterech pasów
            for (int k = 0; k < 4; k++) {
                double ang = Math.PI / 2 * k + Math.PI / 4;
                V3 p = new V3(rc.x() + Math.cos(ang) * BasketCourt.RIM_RADIUS * 0.8, rc.y() - 0.22, rc.z() + Math.sin(ang) * BasketCourt.RIM_RADIUS * 0.8);
                V3 tangent = new V3(-Math.sin(ang), 0, Math.cos(ang));
                segment(w, a, Material.WHITE_STAINED_GLASS, p, tangent, BasketCourt.RIM_RADIUS * 1.2, 0.4, "net-" + hoop + "-" + k);
            }
            // słup za linią końcową i ramię łączące go z tablicą
            double poleU = hoop == 0 ? -0.7 : a.length() + 0.7;
            box(w, a, Material.GRAY_CONCRETE, new V3(poleU - 0.15, 0, mid - 0.15), new V3(poleU + 0.15, top + 0.25, mid + 0.15), "pole-" + hoop);
            double armLo = Math.min(poleU, bu);
            double armHi = Math.max(poleU, bu);
            box(w, a, Material.GRAY_CONCRETE, new V3(armLo, top - 0.2, mid - 0.08), new V3(armHi, top - 0.05, mid + 0.08), "arm-" + hoop);
        }
    }

    // ---------- sprzątanie ----------

    /** Usuwa encje ozdobne boiska (siatka, słupki, tablice). Bloki podłogi zostają. */
    public int clearDecor(Arena a) {
        World w = plugin.getServer().getWorld(a.worldName());
        if (w == null) return 0;
        Set<Chunk> chunks = new HashSet<>();
        for (double u = -MARGIN - 2; u <= a.length() + MARGIN + 2; u += 8) {
            for (double v = -MARGIN - 2; v <= a.width() + MARGIN + 2; v += 8) {
                Location l = a.toWorld(w, new V3(u, 0, v));
                chunks.add(w.getChunkAt((int) Math.floor(l.getX()) >> 4, (int) Math.floor(l.getZ()) >> 4));
            }
        }
        int removed = 0;
        String prefix = a.name() + ":";
        for (Chunk ch : chunks) {
            ch.load(true);
            for (Entity e : ch.getEntities()) {
                String tag = e.getPersistentDataContainer().get(decorKey, PersistentDataType.STRING);
                if (tag != null && tag.startsWith(prefix)) {
                    e.remove();
                    removed++;
                }
            }
        }
        return removed;
    }
}
