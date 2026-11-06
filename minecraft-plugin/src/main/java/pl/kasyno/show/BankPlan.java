package pl.kasyno.show;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Projekt banku jako dane: siatka bloków (stany w zapisie Minecrafta, np. "minecraft:quartz_stairs[facing=north,half=top]"), napisy
 * i punkty sceny pokazu napadu. Bez Bukkita, więc da się go badać testami (czy droga ekipy jest przejezdna, czy drzwi i lampy leżą
 * tam, gdzie pokaz ich szuka) i rysować podglądem. BankPrefab nakłada plan na prawdziwy świat.
 *
 * <p>Styl: klasycystyczny (Greek Revival) bank wzorowany na Second Bank of the United States w Filadelfii: sześciokolumnowy portyk
 * porządku doryckiego z trójkątnym frontonem, belkowanie z tryglifami, szerokie schody, a w środku hala operacyjna z kasetonowym
 * sufitem, pod kopułą, z ladami z szybami i skarbcem z kołem na drzwiach. Biel kwarcu, ciemny łupek dachu, złoto i patynowa miedź.
 *
 * <pre>
 *   z = 0-2    plac z drzewami (miejsce dla widzów)        z = 3-7    jezdnia (van po prawej, radiowóz po lewej)
 *   z = 8-11   krawężnik i chodnik                         z = 12-14  schody z płyt (co pół bloku)
 *   z = 15-17  portyk (kolumny w rzędzie z = 15)           z = 18     ściana frontowa z drzwiami (x = 16)
 *   z = 19-29  hala pod kopułą                             z = 30     drzwi skarbca (x = 16)
 *   z = 32-36  skarbiec                                    z = 37     tylna ściana
 * </pre>
 * Oś symetrii to x = 16, ziemia ma wysokość stóp 0 (bloki na y = -1), a podłoga portyku i hali 2.
 */
public final class BankPlan {
    public static final int WIDTH = 33;
    public static final int DEPTH = 38;
    public static final int MIN_Y = -4;
    public static final int MAX_Y = 27;
    /** Wysokość stóp na podeście i w hali. */
    public static final int F = 2;
    /** Oś symetrii budynku i drogi ekipy. */
    public static final int AXIS = 16;

    private static final int HEIGHT = MAX_Y - MIN_Y + 1;

    /** Napis w świecie (TextDisplay): środek, treść w MiniMessage i skala. */
    public record Text(double x, double y, double z, String text, float scale) {}

    /** Punkt sceny we współrzędnych planu. */
    public record Point(double x, double y, double z) {}

    private final short[] cells = new short[WIDTH * HEIGHT * DEPTH];
    private final List<String> palette = new ArrayList<>();
    private final Map<String, Short> index = new HashMap<>();
    private final List<Text> texts = new ArrayList<>();
    private final Map<String, Point> points = new LinkedHashMap<>();
    private int dropped;

    private BankPlan() {}

    public static BankPlan create() {
        BankPlan p = new BankPlan();
        p.design();
        return p;
    }

    // ---------- dostęp ----------

    private static int at(int x, int y, int z) {
        return (x * HEIGHT + (y - MIN_Y)) * DEPTH + z;
    }

    private static boolean inside(int x, int y, int z) {
        return x >= 0 && x < WIDTH && z >= 0 && z < DEPTH && y >= MIN_Y && y <= MAX_Y;
    }

    /** Stan bloku w planie albo null, gdy plan niczego tam nie ustawia (świat zostaje bez zmian). */
    public String get(int x, int y, int z) {
        if (!inside(x, y, z)) return null;
        short i = cells[at(x, y, z)];
        return i == 0 ? null : palette.get(i - 1);
    }

    public List<String> palette() {
        return palette;
    }

    public List<Text> texts() {
        return texts;
    }

    public Map<String, Point> points() {
        return points;
    }

    public float viewYaw() {
        return 0;
    }

    /** Ile razy projekt próbował ustawić blok poza obszarem planu (powinno być zero). */
    public int dropped() {
        return dropped;
    }

    /** Nazwa materiału z zapisu stanu ("minecraft:barrel[facing=up]" daje "BARREL"). */
    public static String material(String state) {
        String s = state.startsWith("minecraft:") ? state.substring("minecraft:".length()) : state;
        int bracket = s.indexOf('[');
        if (bracket >= 0) s = s.substring(0, bracket);
        return s.toUpperCase(Locale.ROOT);
    }

    // ---------- budowanie ----------

    private void set(int x, int y, int z, String state) {
        if (!inside(x, y, z)) {
            dropped++;
            return;
        }
        Short i = index.get(state);
        if (i == null) {
            palette.add(state);
            i = (short) palette.size();
            index.put(state, i);
        }
        cells[at(x, y, z)] = i;
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, String state) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, state);
            }
        }
    }

    private static String block(String name) {
        return "minecraft:" + name;
    }

    private static String stairs(String name, String facing, boolean top) {
        return "minecraft:" + name + "_stairs[facing=" + facing + ",half=" + (top ? "top" : "bottom") + "]";
    }

    private static String slab(String name, boolean top) {
        return "minecraft:" + name + "_slab[type=" + (top ? "top" : "bottom") + "]";
    }

    private static String leaves() {
        return "minecraft:oak_leaves[persistent=true]";
    }

    private static String door(boolean upper) {
        return "minecraft:iron_door[facing=north,half=" + (upper ? "upper" : "lower") + ",hinge=left,open=false,powered=false]";
    }

    private static final String AIR = "minecraft:air";
    private static final String QB = block("quartz_bricks");
    private static final String SQ = block("smooth_quartz");
    private static final String QPILLAR = "minecraft:quartz_pillar[axis=y]";
    private static final String CHISELED = block("chiseled_quartz_block");
    private static final String STONE_BRICKS = block("stone_bricks");
    private static final String ANDESITE = block("polished_andesite");
    private static final String WALL = block("stone_bricks");
    private static final String GOLD = block("gold_block");
    private static final String IRON = block("iron_block");
    private static final String GLASS = block("glass");
    private static final String SEA = block("sea_lantern");
    private static final String SLATE = block("deepslate_tiles");
    private static final String DARK_OAK = block("dark_oak_planks");
    private static final String BLACKSTONE = block("polished_blackstone");
    private static final String BLACKSTONE_BRICKS = block("polished_blackstone_bricks");

    private void design() {
        fill(0, 0, 0, WIDTH - 1, 26, DEPTH - 1, AIR);
        site();
        podium();
        shell();
        hall();
        facade();
        lining();
        portico();
        vault();
        dome();
        props();
        placePoints();
    }

    /** Odległość środka bloku od osi kopuły (blok 16, 24). */
    private static double fromDome(int x, int z) {
        return Math.hypot(x - AXIS, z - 24);
    }

    // ---------- plac, ulica, chodnik ----------

    private void site() {
        fill(0, -1, 0, WIDTH - 1, -1, DEPTH - 1, block("grass_block"));
        // plac po drugiej stronie ulicy, z którego widać fasadę
        fill(0, -1, 0, WIDTH - 1, -1, 1, ANDESITE);
        fill(0, -1, 2, WIDTH - 1, -1, 2, STONE_BRICKS);
        // jezdnia z przerywaną linią, krawężnik i chodnik
        fill(0, -1, 3, WIDTH - 1, -1, 7, block("gray_concrete"));
        for (int x = 0; x < WIDTH; x++) if (x % 4 < 2) set(x, -1, 5, block("white_concrete"));
        fill(0, -1, 8, WIDTH - 1, -1, 8, block("smooth_stone"));
        fill(0, -1, 9, WIDTH - 1, -1, 11, ANDESITE);
        for (int x = 0; x < WIDTH; x += 4) set(x, -1, 10, STONE_BRICKS);
        // dziedziniec przed schodami
        fill(0, -1, 12, 3, -1, 17, ANDESITE);
        fill(29, -1, 12, WIDTH - 1, -1, 17, ANDESITE);
    }

    // ---------- podest i schody ----------

    private void podium() {
        fill(4, -1, 12, 28, -1, 17, STONE_BRICKS);
        fill(4, 0, 15, 28, 0, 17, STONE_BRICKS);
        // schody z płyt: wierzchy na 0,5 / 1,0 / 1,5, potem podest na 2,0 (pół bloku na krok)
        for (int x = 5; x <= 27; x++) {
            set(x, 0, 12, slab("smooth_quartz", false));
            set(x, 0, 13, SQ);
            set(x, 0, 14, SQ);
            set(x, 1, 14, slab("smooth_quartz", false));
        }
        // policzki schodów i podest po bokach
        for (int x : new int[]{4, 28}) {
            set(x, 0, 12, SQ);
            set(x, 0, 13, SQ);
            set(x, 1, 13, slab("smooth_quartz", false));
            set(x, 0, 14, SQ);
            set(x, 1, 14, SQ);
            fill(x, 1, 15, x, 1, 17, SQ);
            // latarnia na policzku
            fill(x, 1, 12, x, 3, 12, block("iron_bars"));
            set(x, 4, 12, "minecraft:lantern[hanging=false]");
        }
    }

    // ---------- bryła budynku ----------

    private void shell() {
        fill(3, -1, 18, 29, 1, 37, ANDESITE);
        fill(3, 2, 18, 29, 11, 37, WALL);
    }

    // ---------- hala operacyjna ----------

    private void hall() {
        fill(4, 2, 19, 28, 10, 29, AIR);
        // posadzka w szachownicę
        for (int x = 4; x <= 28; x++) for (int z = 19; z <= 29; z++) set(x, 1, z, (x + z) % 2 == 0 ? SQ : ANDESITE);
        // sufit kasetonowy: belki z płyt zwisają z sufitu co 6 i 5 bloków
        fill(4, 11, 19, 28, 11, 29, SQ);
        for (int x : new int[]{4, 10, 16, 22, 28}) fill(x, 10, 19, x, 10, 29, slab("quartz", true));
        for (int z : new int[]{19, 24, 29}) fill(4, 10, z, 28, 10, z, slab("quartz", true));
        // złoty gzyms wokół hali
        fill(4, 10, 19, 4, 10, 29, GOLD);
        fill(28, 10, 19, 28, 10, 29, GOLD);
        fill(4, 10, 19, 28, 10, 19, GOLD);
        fill(4, 10, 29, 28, 10, 29, GOLD);
        // boazeria z ciemnego dębu
        fill(4, 2, 19, 4, 3, 29, DARK_OAK);
        fill(28, 2, 19, 28, 3, 29, DARK_OAK);
        fill(5, 2, 19, 13, 3, 19, DARK_OAK);
        fill(19, 2, 19, 27, 3, 19, DARK_OAK);
        fill(5, 2, 29, 12, 3, 29, DARK_OAK);
        fill(20, 2, 29, 27, 3, 29, DARK_OAK);
        // czerwony dywan na osi z żółtymi brzegami, od portyku do skarbca (zaczyna się za rzędem kolumn, żeby ze schodów
        // nie trzeba było skakać na grubość dywanu)
        for (int z = 16; z <= 29; z++) {
            if (z == 18) continue;
            for (int x = 15; x <= 17; x++) set(x, 2, z, block("red_carpet"));
            if (z >= 19) {
                set(14, 2, z, block("yellow_carpet"));
                set(18, 2, z, block("yellow_carpet"));
            }
        }
        // lady z szybami po obu stronach, stanowiska kasjerów
        for (int side = 0; side < 2; side++) {
            int cx = side == 0 ? 8 : 24;
            int desk = side == 0 ? 6 : 26;
            int chair = side == 0 ? 5 : 27;
            for (int z = 20; z <= 28; z++) {
                set(cx, 2, z, DARK_OAK);
                set(cx, 3, z, slab("smooth_quartz", false));
                if (z != 22 && z != 26) {
                    set(cx, 4, z, GLASS);
                    set(cx, 5, z, GLASS);
                }
            }
            for (int z : new int[]{21, 23, 25, 27}) {
                set(desk, 2, z, DARK_OAK);
                set(desk, 3, z, "minecraft:lectern[facing=" + (side == 0 ? "east" : "west") + ",has_book=false,powered=false]");
                set(chair, 2, z, stairs("dark_oak", side == 0 ? "west" : "east", false));
            }
        }
        // żyrandole na łańcuchach
        int[][] lamps = {{7, 21}, {7, 27}, {25, 21}, {25, 27}, {12, 20}, {20, 20}, {12, 28}, {20, 28}};
        for (int[] l : lamps) {
            set(l[0], 10, l[1], "minecraft:iron_chain[axis=y]");
            set(l[0], 9, l[1], "minecraft:iron_chain[axis=y]");
            set(l[0], 8, l[1], "minecraft:lantern[hanging=true]");
        }
    }

    /**
     * Wnętrze hali jest jasne: ściany z szarego kamienia na zewnątrz dostają od środka okładzinę z kwarcu, z wyciętymi otworami okien.
     * Okładzina leży w komórkach hali tuż przy ścianie, nad boazerią (y = 4-9) i pod złotym gzymsem.
     */
    private void lining() {
        for (int z = 19; z <= 29; z++) {
            if (!isGlass(get(3, 5, z))) fill(4, 4, z, 4, 9, z, QB);
            if (!isGlass(get(29, 5, z))) fill(28, 4, z, 28, 9, z, QB);
            for (int y = 4; y <= 9; y++) {
                if (isGlass(get(3, y, z))) set(4, y, z, AIR);
                if (isGlass(get(29, y, z))) set(28, y, z, AIR);
            }
        }
        for (int x = 5; x <= 27; x++) {
            for (int y = 4; y <= 9; y++) {
                if (!isGlass(get(x, y, 18))) set(x, y, 19, QB);
                if (x < 13 || x > 19) set(x, y, 29, QB);
            }
        }
    }

    private static boolean isGlass(String state) {
        return state != null && material(state).endsWith("GLASS");
    }

    // ---------- fasada, okna, drzwi, dach ----------

    private void windowOnFront(int cx) {
        fill(cx - 1, 4, 18, cx + 1, 7, 18, GLASS);
        set(cx, 8, 18, GLASS);
        set(cx - 1, 8, 18, stairs("quartz", "west", true));
        set(cx + 1, 8, 18, stairs("quartz", "east", true));
    }

    private void windowOnSide(int x, int cz) {
        fill(x, 4, cz - 1, x, 7, cz + 1, GLASS);
        set(x, 8, cz, GLASS);
        set(x, 8, cz - 1, stairs("quartz", "north", true));
        set(x, 8, cz + 1, stairs("quartz", "south", true));
    }

    private void facade() {
        for (int cx : new int[]{8, 12, 20, 24}) windowOnFront(cx);
        for (int x : new int[]{3, 29}) for (int cz : new int[]{20, 24, 28}) windowOnSide(x, cz);
        // wąskie okna w skrzydłach fasady
        for (int x : new int[]{4, 28}) {
            fill(x, 4, 18, x, 8, 18, GLASS);
        }
        // pilastry na bokach i narożnikach
        for (int x : new int[]{3, 29}) for (int z : new int[]{18, 22, 26, 30, 37}) fill(x, 2, z, x, 10, z, QPILLAR);
        for (int x : new int[]{9, 16, 23}) fill(x, 2, 37, x, 10, 37, QPILLAR);
        // wejście: drzwi, szyby boczne, naświetle i złoty nadproże
        set(16, 2, 18, door(false));
        set(16, 3, 18, door(true));
        for (int x : new int[]{15, 17}) fill(x, 2, 18, x, 3, 18, block("black_stained_glass"));
        fill(15, 4, 18, 17, 5, 18, GLASS);
        fill(14, 6, 18, 18, 6, 18, GOLD);
        // gzyms z zębami nad ścianami
        for (int z = 18; z <= 37; z++) {
            String west = (z % 2 == 0) ? stairs("quartz", "east", true) : QB;
            set(3, 11, z, west);
            set(29, 11, z, (z % 2 == 0) ? stairs("quartz", "west", true) : QB);
        }
        for (int x = 4; x <= 28; x++) set(x, 11, 37, (x % 2 == 0) ? stairs("quartz", "north", true) : QB);
        // dach z łupku, attyka i płyta wieńcząca
        fill(3, 12, 18, 29, 12, 37, QB);
        fill(4, 12, 19, 28, 12, 36, SLATE);
        fill(3, 13, 18, 3, 13, 37, QB);
        fill(29, 13, 18, 29, 13, 37, QB);
        fill(3, 13, 37, 29, 13, 37, QB);
        fill(3, 13, 18, 29, 13, 18, QB);
        for (int x = 3; x <= 29; x++) {
            set(x, 14, 18, slab("smooth_quartz", false));
            set(x, 14, 37, slab("smooth_quartz", false));
        }
        for (int z = 19; z <= 36; z++) {
            set(3, 14, z, slab("smooth_quartz", false));
            set(29, 14, z, slab("smooth_quartz", false));
        }
    }

    // ---------- portyk z frontonem ----------

    private void portico() {
        // posadzka portyku
        for (int x = 5; x <= 27; x++) for (int z = 15; z <= 17; z++) set(x, 1, z, (x + z) % 2 == 0 ? SQ : ANDESITE);
        // ściany boczne portyku
        for (int x : new int[]{5, 27}) {
            fill(x, 2, 16, x, 10, 17, QB);
            fill(x, 2, 15, x, 10, 15, QPILLAR);
        }
        // kolumny: baza, trzon, głowica
        for (int x : new int[]{6, 10, 14, 18, 22, 26}) {
            set(x, 2, 15, SQ);
            fill(x, 3, 15, x, 9, 15, QPILLAR);
            set(x, 10, 15, CHISELED);
        }
        // architraw, strop portyku z lampami
        fill(5, 11, 15, 27, 11, 15, SQ);
        fill(5, 11, 16, 27, 11, 17, QB);
        for (int x : new int[]{8, 12, 20, 24}) set(x, 11, 16, SEA);
        set(16, 11, 17, SEA);
        // fryz z tryglifami i czarną tablicą na napis
        for (int x = 5; x <= 27; x++) {
            String s;
            if (x >= 12 && x <= 20) s = BLACKSTONE;
            else if (x >= 6 && ((x - 6) % 4 == 0 || (x - 6) % 4 == 2)) s = QPILLAR;
            else s = SQ;
            set(x, 12, 15, s);
        }
        fill(5, 12, 16, 27, 12, 17, SQ);
        // gzyms nad frontem z odwróconych schodków i ciągły pas
        for (int x = 4; x <= 28; x++) set(x, 13, 14, stairs("quartz", "south", true));
        fill(5, 13, 15, 27, 13, 17, SQ);
        for (int z = 15; z <= 17; z++) {
            set(4, 13, z, stairs("quartz", "east", true));
            set(28, 13, z, stairs("quartz", "west", true));
        }
        // fronton: trójkąt, którego górna krawędź unosi się o pół bloku co dwa bloki
        for (int x = 5; x <= 27; x++) {
            int i = Math.min(x - 5, 27 - x);
            int halves = 2 + i / 2;
            int full = halves / 2;
            boolean odd = halves % 2 == 1;
            for (int k = 0; k < full; k++) set(x, 14 + k, 17, SQ);
            if (odd) set(x, 14 + full, 17, slab("smooth_quartz", false));
            // płaszczyzna tympanonu (cofnięta o blok): szary trójkąt pod dachem
            for (int k = 0; k < full; k++) set(x, 14 + k, 16, (!odd && k == full - 1) ? SQ : block("light_gray_concrete"));
            if (odd) set(x, 14 + full, 16, slab("smooth_quartz", false));
            // listwa gzymsowa na froncie
            set(x, 14 + full - 1, 15, SQ);
            if (odd) set(x, 14 + full, 15, slab("smooth_quartz", false));
        }
        // akroteriony na końcach i na szczycie frontonu
        for (int x : new int[]{5, 27}) {
            set(x, 15, 16, CHISELED);
            set(x, 16, 16, GOLD);
        }
        set(AXIS, 18, 16, CHISELED);
        set(AXIS, 19, 16, GOLD);
        // złota moneta na tympanonie
        for (int x = 15; x <= 17; x++) for (int y = 14; y <= 15; y++) set(x, y, 16, GOLD);
        // napis nad wejściem
        // TextDisplay stoi dolną krawędzią ramki tekstu na swojej pozycji: litery (7 pikseli po 0,025 bloku razy skala) zaczynają się
        // ok. 3 piksele wyżej. Dla skali 4 wychodzi 11,85 + 0,3 = 12,15 na dole i 12,85 na górze, czyli litery leżą w tablicy (y = 12),
        // a nie wchodzą pod gzyms (y = 13), który ucinał ich górną połowę.
        texts.add(new Text(AXIS + 0.5, 11.85, 14.92, "<gold><bold>B A N K", 4.0f));
    }

    // ---------- skarbiec ----------

    private void vault() {
        // stalowa wykładzina i wnętrze
        fill(9, 1, 31, 23, 7, 36, ANDESITE);
        fill(10, 2, 32, 22, 6, 36, AIR);
        fill(10, 1, 32, 22, 1, 36, BLACKSTONE);
        fill(16, 2, 31, 16, 3, 31, AIR);
        // drzwi skarbca w ramie od strony hali
        fill(12, 2, 30, 20, 4, 30, BLACKSTONE_BRICKS);
        fill(15, 2, 30, 15, 4, 30, IRON);
        fill(17, 2, 30, 17, 4, 30, IRON);
        set(16, 4, 30, IRON);
        fill(11, 2, 30, 11, 5, 30, GOLD);
        fill(21, 2, 30, 21, 5, 30, GOLD);
        fill(11, 5, 30, 21, 5, 30, GOLD);
        set(16, 2, 30, door(false));
        set(16, 3, 30, door(true));
        // koło skarbca nad drzwiami: złota piasta, żelazna tarcza i złoty wieniec
        for (int x = 13; x <= 19; x++) {
            for (int y = 6; y <= 10; y++) {
                double d = Math.hypot(x - AXIS, y - 8);
                if (d <= 0.5) set(x, y, 30, GOLD);
                else if (d <= 1.5) set(x, y, 30, IRON);
                else if (d <= 2.5) set(x, y, 30, GOLD);
            }
        }
        // skrytki depozytowe po bokach
        for (int z = 32; z <= 35; z++) {
            for (int y = 2; y <= 4; y++) {
                set(10, y, z, "minecraft:barrel[facing=east,open=false]");
                set(22, y, z, "minecraft:barrel[facing=west,open=false]");
            }
        }
        // złoto pod tylną ścianą po obu stronach, a oś skarbca (x = 15-17) zostaje wolna dla ekipy; skrzynie przy samej osi
        for (int side = 0; side < 2; side++) {
            int dir = side == 0 ? 1 : -1;          // od ściany bocznej w stronę osi
            int wall = side == 0 ? 11 : 21;
            fill(wall, 2, 36, wall + 3 * dir, 2, 36, GOLD);
            fill(wall, 3, 36, wall + 2 * dir, 3, 36, GOLD);
            fill(wall, 4, 36, wall + dir, 4, 36, GOLD);
            fill(wall, 2, 35, wall + dir, 2, 35, GOLD);
            set(wall + 3 * dir, 2, 35, "minecraft:chest[facing=" + (side == 0 ? "east" : "west") + ",type=single]");
        }
        // światło
        for (int[] l : new int[][]{{12, 33}, {20, 33}, {12, 35}, {20, 35}, {16, 34}}) set(l[0], 7, l[1], SEA);
        set(16, 4, 31, SEA);
    }

    /**
     * Numer wycinka (z 16) wokół osi kopuły. Środki wycinków leżą na osiach i przekątnych, więc wzór (parzyste, co czwarty)
     * jest symetryczny względem osi budynku: odbicie wycinka k to 8 - k, które ma tę samą parzystość i podzielność przez cztery.
     */
    private static int sector(int x, int z) {
        double u = Math.atan2(z - 24, x - AXIS) + Math.PI;
        return Math.floorMod((int) Math.floor(u / (Math.PI / 8) + 0.5), 16);
    }

    // ---------- kopuła ----------

    private void dome() {
        // otwór w suficie hali i bęben kopuły
        for (int x = 11; x <= 21; x++) {
            for (int z = 19; z <= 29; z++) {
                double d = fromDome(x, z);
                if (d <= 4.6) {
                    set(x, 11, z, AIR);
                    set(x, 10, z, AIR);
                }
                if (d <= 5.8) set(x, 12, z, AIR);
                if (d > 4.6 && d <= 5.8) {
                    int sector = sector(x, z);
                    for (int y = 12; y <= 15; y++) {
                        String s;
                        if (sector % 2 == 0) s = QPILLAR;
                        else if (y == 13 || y == 14) s = GLASS;
                        else s = QB;
                        if (y == 12 && sector % 4 == 0) s = SEA;
                        set(x, y, z, s);
                    }
                } else if (d <= 4.6) {
                    for (int y = 12; y <= 15; y++) set(x, y, z, AIR);
                }
            }
        }
        // czasza: powłoka z miedzi na kwarcu, szklane oko na szczycie
        for (int y = 16; y <= 22; y++) {
            double dy = y + 0.5 - 16;
            for (int x = 10; x <= 22; x++) {
                for (int z = 18; z <= 30; z++) {
                    double d = fromDome(x, z);
                    double d3 = Math.sqrt(d * d + dy * dy);
                    if (d3 <= 4.6 || d3 > 5.8) continue;
                    if (d <= 1.2 && dy > 4.9) {
                        set(x, y, z, GLASS);
                        continue;
                    }
                    int sector = sector(x, z);
                    String outer = sector % 4 == 0 ? block("waxed_weathered_copper") : block("waxed_oxidized_copper");
                    set(x, y, z, d3 > 5.2 ? outer : SQ);
                }
            }
        }
        set(AXIS, 22, 24, GOLD);
        set(AXIS, 23, 24, block("lightning_rod"));
    }

    // ---------- otoczenie ----------

    /** Drzewo z pniem z dębu i kulistą koroną o promieniu `r` bloków (korona nie wychodzi poza kwadrat r x r wokół pnia). */
    private void tree(int x, int z, int r) {
        set(x, -1, z, block("grass_block"));
        fill(x, 0, z, x, 3, z, "minecraft:oak_log[axis=y]");
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int y = 3; y <= 6; y++) {
                    if (dx == 0 && dz == 0 && y <= 3) continue;
                    double d = Math.sqrt(dx * dx + dz * dz + (y - 4.5) * (y - 4.5));
                    if (d <= r + 0.4) set(x + dx, y, z + dz, leaves());
                }
            }
        }
    }

    private void props() {
        for (int x : new int[]{2, 9, 23, 30}) {
            fill(x, 0, 9, x, 3, 9, block("iron_bars"));
            set(x, 4, 9, "minecraft:lantern[hanging=false]");
        }
        for (int x : new int[]{4, 10, 22, 28}) tree(x, 1, 1);
        for (int z : new int[]{22, 30, 35}) {
            tree(1, z, 1);
            tree(31, z, 1);
        }
        tree(1, 15, 1);
        tree(31, 15, 1);
        for (int x : new int[]{3, 29}) fill(x, 0, 12, x, 1, 14, leaves());
        // van po prawej (przodem na wschód)
        fill(22, 0, 6, 26, 2, 7, block("black_concrete"));
        for (int z : new int[]{6, 7}) set(26, 1, z, block("black_stained_glass"));
        for (int x : new int[]{24, 25}) for (int z : new int[]{6, 7}) set(x, 1, z, block("black_stained_glass"));
        // radiowóz po lewej
        fill(4, 0, 6, 8, 0, 7, block("blue_concrete"));
        fill(4, 1, 6, 8, 1, 7, block("white_concrete"));
        fill(5, 2, 6, 7, 2, 7, block("white_concrete"));
        for (int z : new int[]{6, 7}) for (int x : new int[]{5, 7}) set(x, 1, z, block("light_blue_stained_glass"));
        set(5, 3, 6, block("red_stained_glass"));
        set(7, 3, 6, block("blue_stained_glass"));
    }

    // ---------- punkty sceny ----------

    private void placePoints() {
        points.put("start", new Point(26.5, 0, 10.5));
        points.put("street", new Point(16.5, 0, 11.5));
        points.put("door", new Point(16.5, F, 15.5));
        points.put("hall", new Point(16.5, F, 23.0));
        points.put("front", new Point(16.5, F, 29.0));
        points.put("safe", new Point(16.5, F, 34.0));
        points.put("guard", new Point(21.5, F, 26.5));
        points.put("police", new Point(8.5, 0, 10.5));
        points.put("jail", new Point(5.5, 0, 8.5));
        points.put("view", new Point(16.5, 0, 1.5));
        points.put("bankdoor", new Point(16, F, 18));
        points.put("vaultdoor", new Point(16, F, 30));
        points.put("lamp1", new Point(3, 9, 20));
        points.put("lamp2", new Point(29, 9, 20));
        points.put("lamp3", new Point(3, 9, 28));
        points.put("lamp4", new Point(29, 9, 28));
        // lampy alarmu nad oknami bocznych ścian
        for (String name : new String[]{"lamp1", "lamp2", "lamp3", "lamp4"}) {
            Point p = points.get(name);
            set((int) p.x(), (int) p.y(), (int) p.z(), "minecraft:redstone_lamp[lit=false]");
        }
    }

    // ---------- zapis dla podglądu ----------

    /** Zapis planu do JSON-a dla narzędzia rysującego podgląd (tylko ustawione bloki poza powietrzem). */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"width\":" + WIDTH + ",\"depth\":" + DEPTH + ",\"minY\":" + MIN_Y + ",\"palette\":[");
        for (int i = 0; i < palette.size(); i++) sb.append(i == 0 ? "" : ",").append('"').append(palette.get(i).replace("\"", "\\\"")).append('"');
        sb.append("],\"blocks\":[");
        boolean first = true;
        for (int x = 0; x < WIDTH; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = 0; z < DEPTH; z++) {
                    short i = cells[at(x, y, z)];
                    if (i == 0 || palette.get(i - 1).equals(AIR)) continue;
                    sb.append(first ? "" : ",").append('[').append(x).append(',').append(y).append(',').append(z).append(',').append(i - 1).append(']');
                    first = false;
                }
            }
        }
        sb.append("],\"points\":{");
        boolean firstPoint = true;
        for (Map.Entry<String, Point> e : points.entrySet()) {
            Point p = e.getValue();
            sb.append(firstPoint ? "" : ",").append('"').append(e.getKey()).append("\":[").append(p.x()).append(',').append(p.y()).append(',').append(p.z()).append(']');
            firstPoint = false;
        }
        sb.append("}}");
        return sb.toString();
    }
}
