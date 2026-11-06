package pl.kasyno.show;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import pl.kasyno.show.BankPlan.Point;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Projekt banku bez serwera: czy ekipa przejdzie całą drogę pokazu (także w kolejce gęsiego), czy punkty stoją na podłodze, czy drzwi
 * i lampy leżą tam, gdzie pokaz ich szuka, czy fasada jest symetryczna, czy nic nie wisi w powietrzu i czy wnętrza są oświetlone.
 */
class BankPlanTest {
    private static final BankPlan PLAN = BankPlan.create();

    // ---------- pomocnicze ----------

    private static String state(int x, int y, int z) {
        return PLAN.get(x, y, z);
    }

    private static boolean air(String s) {
        return s == null || s.equals("minecraft:air");
    }

    /** Górna krawędź kolizji w rzędzie `by` w planie: drzwi (w pokazie się otwierają), łańcuchy i powietrze nie przeszkadzają. */
    private static double top(String s, int by) {
        if (air(s)) return Double.NaN;
        String m = BankPlan.material(s);
        if (m.equals("IRON_DOOR") || m.equals("IRON_CHAIN")) return Double.NaN;
        if (m.endsWith("_CARPET")) return by + 0.0625;
        if (m.endsWith("_SLAB") && s.contains("type=bottom")) return by + 0.5;
        if (m.equals("LANTERN")) return by + 0.5;
        return by + 1.0;
    }

    private static Ground.Surface surface(double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        return by -> top(state(bx, by, bz), by);
    }

    /** Czy ciało stojące na wysokości `feet` mieści się w punkcie (x, z): od stóp do 1,79 nad nimi nic nie sięga wyżej niż stopy. */
    private static String blocked(double x, double feet, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(feet); by <= (int) Math.floor(feet + 1.79); by++) {
            double t = top(state(bx, by, bz), by);
            if (!Double.isNaN(t) && t > feet + 0.01) return state(bx, by, bz) + " na y=" + by;
        }
        return null;
    }

    private static Point pt(String name) {
        Point p = PLAN.points().get(name);
        assertNotNull(p, name);
        return p;
    }

    // ---------- plan jako całość ----------

    @Test
    void theDesignFitsInsideItsDeclaredBoxAndUsesRealBlocks() {
        assertEquals(0, PLAN.dropped(), "żaden blok nie wypadł poza obszar planu");
        assertTrue(BankPlan.WIDTH == BankPrefab.WIDTH && BankPlan.DEPTH == BankPrefab.DEPTH);
        assertTrue(PLAN.palette().size() > 40, "bogata paleta: " + PLAN.palette().size());
        for (String s : PLAN.palette()) {
            String name = BankPlan.material(s);
            assertNotNull(Material.matchMaterial(name), "nieznany materiał " + name + " w " + s);
            assertTrue(s.startsWith("minecraft:"), s);
        }
        assertEquals(1, PLAN.texts().size());
        BankPlan.Text t = PLAN.texts().get(0);
        assertEquals(BankPlan.AXIS + 0.5, t.x(), 1e-9);
        assertTrue(t.text().replace(" ", "").contains("BANK"));
        // napis wisi na tablicy we fryzie (y = 12), tuż przed jej licem (z = 15). TextDisplay stoi dolną krawędzią ramki na swojej
        // pozycji, a litery (7 pikseli po 0,025 bloku razy skala) zaczynają się ok. 3 piksele wyżej: cała ich wysokość musi się
        // zmieścić w tablicy, bo wyżej jest gzyms (y = 13), który wchodzi przed płaszczyznę napisu i ucina górę liter
        assertEquals("minecraft:polished_blackstone", state(16, 12, 15));
        double px = 0.025 * t.scale();
        double glyphBottom = t.y() + 3 * px;
        double glyphTop = glyphBottom + 7 * px;
        assertTrue(glyphBottom >= 12.0 && glyphTop <= 13.0, "litery od " + glyphBottom + " do " + glyphTop + " mają leżeć w tablicy (12-13)");
        assertTrue(glyphTop - glyphBottom >= 0.6, "litery są dość duże, żeby je było widać z ulicy");
        assertTrue(t.z() > 14.5 && t.z() < 15);
        // przed płaszczyzną napisu nic nie stoi: nad tablicą jest gzyms, ale na wysokości liter przed licem jest powietrze
        for (int y = 12; y < 13; y++) assertTrue(air(state(16, y, 14)), "przed tablicą jest wolne miejsce na wysokości y=" + y);
    }

    @Test
    void exportPlanForThePreviewTool() throws IOException {
        Path out = Path.of("target", "bank-plan.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, PLAN.toJson(), StandardCharsets.UTF_8);
        assertTrue(Files.size(out) > 10_000);
    }

    private static java.util.Map<String, java.util.Set<String>> props(String... kv) {
        java.util.Map<String, java.util.Set<String>> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], new HashSet<>(List.of(kv[i + 1].split("[|]"))));
        return m;
    }

    @Test
    void everyBlockStateUsesOnlyPropertiesAndValuesTheGameKnows() {
        var dirs = "north|south|east|west";
        for (String s : PLAN.palette()) {
            String m = BankPlan.material(s);
            java.util.Map<String, java.util.Set<String>> allowed;
            if (m.endsWith("_STAIRS")) allowed = props("facing", dirs, "half", "top|bottom");
            else if (m.endsWith("_SLAB")) allowed = props("type", "top|bottom|double");
            else if (m.equals("QUARTZ_PILLAR") || m.equals("OAK_LOG") || m.equals("IRON_CHAIN")) allowed = props("axis", "x|y|z");
            else if (m.equals("IRON_DOOR")) allowed = props("facing", dirs, "half", "lower|upper", "hinge", "left|right", "open", "true|false", "powered", "true|false");
            else if (m.equals("LANTERN")) allowed = props("hanging", "true|false");
            else if (m.equals("LECTERN")) allowed = props("facing", dirs, "has_book", "true|false", "powered", "true|false");
            else if (m.equals("BARREL")) allowed = props("facing", dirs + "|up|down", "open", "true|false");
            else if (m.equals("CHEST")) allowed = props("facing", dirs, "type", "single|left|right");
            else if (m.equals("REDSTONE_LAMP")) allowed = props("lit", "true|false");
            else if (m.equals("OAK_LEAVES")) allowed = props("persistent", "true|false");
            else allowed = props();
            int bracket = s.indexOf('[');
            if (bracket < 0) continue;
            assertTrue(s.endsWith("]"), s);
            for (String kv : s.substring(bracket + 1, s.length() - 1).split(",")) {
                String[] parts = kv.split("=");
                assertEquals(2, parts.length, "zapis właściwości w " + s);
                assertTrue(allowed.containsKey(parts[0]), "nieznana właściwość " + parts[0] + " w " + s);
                assertTrue(allowed.get(parts[0]).contains(parts[1]), "nieznana wartość " + parts[1] + " właściwości " + parts[0] + " w " + s);
            }
        }
        // pełne zestawy tam, gdzie gra ich wymaga: schodki mają kierunek i połowę, drzwi wszystkie pola
        for (String s : PLAN.palette()) {
            String m = BankPlan.material(s);
            if (m.endsWith("_STAIRS")) assertTrue(s.contains("facing=") && s.contains("half="), s);
            if (m.equals("IRON_DOOR")) assertTrue(s.contains("half=") && s.contains("hinge=") && s.contains("facing="), s);
        }
    }

    // ---------- droga ekipy ----------

    @Test
    void everyStagePointIsInsideTheBoxWithRoomForAPerson() {
        for (var e : PLAN.points().entrySet()) {
            Point p = e.getValue();
            assertTrue(p.x() >= 0 && p.x() < BankPlan.WIDTH && p.z() >= 0 && p.z() < BankPlan.DEPTH, e.getKey());
        }
        for (String name : new String[]{"start", "street", "door", "hall", "front", "safe", "guard", "police", "jail", "view"}) {
            Point p = pt(name);
            double ground = Ground.snap(p.y(), surface(p.x(), p.z()));
            assertEquals(p.y(), ground, 0.07, name + ": punkt stoi na podłożu (wysokość " + p.y() + ", podłoże " + ground + ")");
            assertEquals(null, blocked(p.x(), ground, p.z()), name + ": w punkcie zmieści się człowiek");
        }
    }

    @Test
    void theCrewCanWalkTheWholeShowRouteAndBack() {
        String[] route = {"start", "street", "door", "hall", "front", "safe"};
        for (int i = 0; i + 1 < route.length; i++) {
            Point a = pt(route[i]);
            Point b = pt(route[i + 1]);
            double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
            int steps = (int) Math.ceil(len / 0.25);
            for (int k = 0; k <= steps; k++) {
                double f = steps == 0 ? 1 : (double) k / steps;
                double x = a.x() + (b.x() - a.x()) * f;
                double z = a.z() + (b.z() - a.z()) * f;
                double y = a.y() + (b.y() - a.y()) * f;
                double feet = Ground.snap(y, surface(x, z));
                String why = blocked(x, feet, z);
                assertEquals(null, why, route[i] + " -> " + route[i + 1] + " w (" + x + ", " + z + "): " + why);
                assertTrue(Math.abs(feet - y) <= 1.01, route[i] + " -> " + route[i + 1] + ": figura na wysokości " + feet + " zamiast " + y);
                assertTrue(!Double.isNaN(feet));
            }
        }
    }

    @Test
    void theWholeAxisIsAClearCorridorForALongQueue() {
        // ekipa idzie gęsiego co 0,95 bloku, do ośmiu osób: ogon sięga 7,6 bloku za przewodnikiem w obie strony, aż do końca skarbca
        for (double z = 0.5; z <= 36.9; z += 0.25) {
            double x = BankPlan.AXIS + 0.5;
            double feet = Ground.snap(z < 12 ? 0 : BankPlan.F, surface(x, z));
            assertTrue(feet >= 0 && feet <= BankPlan.F + 0.07, "wysokość podłoża na osi w z=" + z + ": " + feet);
            assertEquals(null, blocked(x, feet, z), "oś zablokowana w z=" + z);
        }
    }

    @Test
    void stairsRiseHalfABlockPerStepSoFiguresCanFollowThem() {
        double x = BankPlan.AXIS + 0.5;
        assertEquals(0.0, Ground.snap(0, surface(x, 11.5)), 1e-9);
        assertEquals(0.5, Ground.snap(0, surface(x, 12.5)), 1e-9);
        assertEquals(1.0, Ground.snap(1, surface(x, 13.5)), 1e-9);
        assertEquals(1.5, Ground.snap(1.5, surface(x, 14.5)), 1e-9);
        assertEquals(2.0, Ground.snap(2, surface(x, 15.5)), 0.07, "podest z dywanem");
        // reszta kolejki ma wysokość przewodnika, a stoi niżej: podłoże ją sprowadza na dół
        assertEquals(0.0, Ground.snap(2, surface(x, 9.5)), 1e-9);
        assertEquals(1.0, Ground.snap(2, surface(x, 13.5)), 1e-9);
        // każdy stopień to najwyżej pół bloku: da się wejść bez skakania
        double prev = 0;
        for (int z = 11; z <= 16; z++) {
            double feet = Ground.snap(prev, surface(x, z + 0.5));
            assertTrue(feet - prev <= 0.5 + 1e-9, "stopień przy z=" + z + ": " + (feet - prev));
            prev = feet;
        }
    }

    // ---------- drzwi i lampy ----------

    @Test
    void doorsAndAlarmLampsSitWhereTheShowLooksForThem() {
        for (String name : new String[]{"bankdoor", "vaultdoor"}) {
            Point p = pt(name);
            int x = (int) p.x();
            int y = (int) p.y();
            int z = (int) p.z();
            assertEquals(p.x(), x, 0, "drzwi leżą na współrzędnych całkowitych");
            assertTrue(state(x, y, z).startsWith("minecraft:iron_door") && state(x, y, z).contains("half=lower"), name + " dół: " + state(x, y, z));
            assertTrue(state(x, y + 1, z).startsWith("minecraft:iron_door") && state(x, y + 1, z).contains("half=upper"), name + " góra: " + state(x, y + 1, z));
            assertTrue(state(x, y, z).contains("open=false"), "drzwi zaczynają zamknięte");
            assertEquals(BankPlan.AXIS, x, "drzwi stoją na osi");
        }
        for (int i = 1; i <= 4; i++) {
            Point p = pt("lamp" + i);
            assertTrue(state((int) p.x(), (int) p.y(), (int) p.z()).startsWith("minecraft:redstone_lamp"), "lampa " + i);
        }
        // drzwi wejściowe są w ścianie frontowej, drzwi skarbca w ścianie hali
        assertEquals(18, (int) pt("bankdoor").z());
        assertEquals(30, (int) pt("vaultdoor").z());
        // przy drzwiach skarbca, po obu stronach, jest przejście dla ekipy (otwór za drzwiami)
        assertTrue(air(state(16, 2, 31)) && air(state(16, 3, 31)));
    }

    // ---------- wygląd i konstrukcja ----------

    @Test
    void theFacadeHasThePorticoStairsAndPedimentOfAGreekRevivalBank() {
        // sześć kolumn z bazą, trzonem i głowicą
        for (int x : new int[]{6, 10, 14, 18, 22, 26}) {
            assertEquals("minecraft:smooth_quartz", state(x, 2, 15));
            for (int y = 3; y <= 9; y++) assertTrue(state(x, y, 15).startsWith("minecraft:quartz_pillar"), x + "," + y);
            assertEquals("minecraft:chiseled_quartz_block", state(x, 10, 15));
        }
        for (int x : new int[]{7, 8, 9, 11, 12, 13, 15, 16, 17}) {
            for (int y = 3; y <= 10; y++) assertTrue(air(state(x, y, 15)), "przerwa między kolumnami: " + x + "," + y);
            assertTrue(air(state(x, 2, 15)) || BankPlan.material(state(x, 2, 15)).endsWith("_CARPET"), "przerwa u podstawy: " + x);
        }
        // belkowanie i fronton
        for (int x = 5; x <= 27; x++) assertEquals("minecraft:smooth_quartz", state(x, 11, 15), "architraw");
        assertTrue(state(16, 17, 15).contains("smooth_quartz_slab") || state(16, 16, 15).equals("minecraft:smooth_quartz"), "szczyt frontonu");
        assertEquals("minecraft:gold_block", state(16, 15, 16), "złota moneta na tympanonie");
        assertEquals("minecraft:gold_block", state(16, 19, 16), "akroterion na szczycie");
        assertTrue(state(5, 14, 15) != null && air(state(4, 14, 15)), "fronton zaczyna się nad skrajnymi kolumnami");
        // fronton rośnie ku środkowi
        int prev = 0;
        for (int x = 5; x <= 16; x++) {
            int h = 0;
            for (int y = 14; y <= 20; y++) if (!air(state(x, y, 17))) h = y - 13;
            assertTrue(h >= prev, "wysokość frontonu nie maleje ku środkowi: x=" + x);
            prev = h;
        }
        assertTrue(prev >= 3, "fronton ma co najmniej trzy bloki przy szczycie: " + prev);
        // kopuła z miedzi z oczkiem i złotym zwieńczeniem
        assertEquals("minecraft:gold_block", state(16, 22, 24));
        assertEquals("minecraft:lightning_rod", state(16, 23, 24));
        boolean copper = false;
        for (int y = 16; y <= 22; y++) for (int x = 10; x <= 22; x++) if (state(x, y, 24) != null && BankPlan.material(state(x, y, 24)).contains("COPPER")) copper = true;
        assertTrue(copper, "kopuła z miedzi");
    }

    @Test
    void theHallHasACarpetCoffersChandeliersAndTellerCounters() {
        for (int z = 19; z <= 29; z++) {
            assertEquals("minecraft:red_carpet", state(16, 2, z), "dywan na osi");
            assertEquals("minecraft:yellow_carpet", state(14, 2, z));
        }
        assertEquals("minecraft:lantern[hanging=true]", state(12, 8, 20));
        assertEquals("minecraft:iron_chain[axis=y]", state(12, 9, 20));
        for (int z = 20; z <= 28; z++) {
            assertEquals("minecraft:dark_oak_planks", state(8, 2, z));
            assertEquals("minecraft:dark_oak_planks", state(24, 2, z));
        }
        assertTrue(state(8, 4, 21).equals("minecraft:glass") && air(state(8, 4, 22)), "szyba ze stanowiskiem kasjera");
        // kasetony: belki zwisają z sufitu
        assertTrue(state(10, 10, 21).contains("quartz_slab"), "belka kasetonu: " + state(10, 10, 21));
        assertEquals("minecraft:smooth_quartz", state(12, 11, 21), "sufit między belkami");
        // oko w suficie pod kopułą
        assertTrue(air(state(16, 11, 24)) && air(state(16, 14, 24)));
    }

    @Test
    void theVaultIsSteelLinedLitAndHasGoldAndDepositBoxes() {
        assertTrue(state(10, 2, 33).contains("barrel") && state(22, 2, 33).contains("barrel"));
        assertEquals("minecraft:gold_block", state(11, 2, 36));
        assertEquals("minecraft:gold_block", state(21, 2, 36));
        assertTrue(state(14, 2, 35).startsWith("minecraft:chest") && state(18, 2, 35).startsWith("minecraft:chest"));
        for (int z = 32; z <= 36; z++) for (int y = 2; y <= 6; y++) assertTrue(air(state(16, y, z)) && air(state(15, y, z)) && air(state(17, y, z)), "oś skarbca wolna: " + y + "," + z);
        // koło skarbca nad drzwiami: złota piasta i wieniec
        assertEquals("minecraft:gold_block", state(16, 8, 30));
        assertEquals("minecraft:iron_block", state(16, 9, 30));
        assertEquals("minecraft:gold_block", state(16, 10, 30));
    }

    @Test
    void theBuildingIsLeftRightSymmetricFromTheSidewalkToTheBackWall() {
        List<String> diffs = new ArrayList<>();
        for (int z = 8; z < BankPlan.DEPTH; z++) {
            for (int y = BankPlan.MIN_Y; y <= BankPlan.MAX_Y; y++) {
                for (int x = 0; x <= BankPlan.AXIS; x++) {
                    String l = state(x, y, z);
                    String r = state(2 * BankPlan.AXIS - x, y, z);
                    String lm = l == null ? null : BankPlan.material(l);
                    String rm = r == null ? null : BankPlan.material(r);
                    if (lm == null ? rm != null : !lm.equals(rm)) diffs.add(x + "," + y + "," + z + ": " + lm + " / " + rm);
                }
            }
        }
        assertTrue(diffs.isEmpty(), "niesymetryczne bloki (" + diffs.size() + "): " + diffs.subList(0, Math.min(8, diffs.size())));
    }

    @Test
    void nothingFloatsInTheAir() {
        int w = BankPlan.WIDTH;
        int d = BankPlan.DEPTH;
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        int solid = 0;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                for (int y = BankPlan.MIN_Y; y <= BankPlan.MAX_Y; y++) {
                    if (air(state(x, y, z))) continue;
                    solid++;
                    if (y == -1 && seen.add(key(x, y, z))) queue.add(new int[]{x, y, z});
                }
            }
        }
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] dd : dirs) {
                int nx = c[0] + dd[0];
                int ny = c[1] + dd[1];
                int nz = c[2] + dd[2];
                if (nx < 0 || nx >= w || nz < 0 || nz >= d || ny < BankPlan.MIN_Y || ny > BankPlan.MAX_Y) continue;
                if (air(state(nx, ny, nz)) || !seen.add(key(nx, ny, nz))) continue;
                queue.add(new int[]{nx, ny, nz});
            }
        }
        List<String> floating = new ArrayList<>();
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                for (int y = BankPlan.MIN_Y; y <= BankPlan.MAX_Y; y++) {
                    if (!air(state(x, y, z)) && !seen.contains(key(x, y, z))) floating.add(x + "," + y + "," + z + " " + state(x, y, z));
                }
            }
        }
        assertTrue(floating.isEmpty(), "bloki wiszące w powietrzu (" + floating.size() + " z " + solid + "): " + floating.subList(0, Math.min(8, floating.size())));
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) | ((long) (y - BankPlan.MIN_Y) << 20) | z;
    }

    @Test
    void everyPlaceInsideIsLitSoNothingHostileSpawnsInTheBank() {
        List<int[]> lights = new ArrayList<>();
        for (int x = 0; x < BankPlan.WIDTH; x++) {
            for (int z = 0; z < BankPlan.DEPTH; z++) {
                for (int y = 0; y <= BankPlan.MAX_Y; y++) {
                    String s = state(x, y, z);
                    if (s == null) continue;
                    String m = BankPlan.material(s);
                    if (m.equals("LANTERN") || m.equals("SEA_LANTERN")) lights.add(new int[]{x, y, z});
                }
            }
        }
        assertTrue(lights.size() > 20, "źródła światła: " + lights.size());
        List<String> dark = new ArrayList<>();
        // wnętrze hali, portyku i skarbca: każda płaska komórka podłogi z powietrzem nad nią ma światło w zasięgu 12 bloków
        for (int x = 4; x <= 28; x++) {
            for (int z = 15; z <= 36; z++) {
                String floor = state(x, 1, z);
                if (floor == null || air(floor) || !air(state(x, 2, z)) && !BankPlan.material(state(x, 2, z)).endsWith("_CARPET")) continue;
                if (!air(state(x, 3, z))) continue;
                int best = Integer.MAX_VALUE;
                for (int[] l : lights) best = Math.min(best, Math.abs(l[0] - x) + Math.abs(l[1] - 2) + Math.abs(l[2] - z));
                if (best > 12) dark.add(x + "," + z + " (" + best + ")");
            }
        }
        assertTrue(dark.isEmpty(), "ciemne miejsca w środku: " + dark.subList(0, Math.min(8, dark.size())));
    }

    @Test
    void outsideThereAreLampPostsAlongTheSidewalkAndTheVehiclesStandInTheStreet() {
        for (int x : new int[]{2, 9, 23, 30}) {
            assertEquals("minecraft:iron_bars", state(x, 0, 9));
            assertEquals("minecraft:lantern[hanging=false]", state(x, 4, 9));
        }
        assertEquals("minecraft:black_concrete", state(22, 1, 6), "van po prawej");
        assertEquals("minecraft:blue_concrete", state(5, 0, 6), "radiowóz po lewej");
        assertEquals("minecraft:white_concrete", state(4, 1, 6), "radiowóz: biała karoseria");
        // start ekipy leży obok vana, a radiowóz przy punktach policji i więzienia
        assertTrue(Math.abs(pt("start").x() - 24) <= 3);
        assertTrue(Math.abs(pt("jail").x() - 6) <= 3);
    }
}
