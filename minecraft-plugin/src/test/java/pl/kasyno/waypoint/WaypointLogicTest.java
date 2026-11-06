package pl.kasyno.waypoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.kasyno.waypoint.MarkerBackend.Spec;
import pl.kasyno.waypoint.WaypointView.Candidate;
import pl.kasyno.waypoint.WaypointView.Placement;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Czysta logika waypointów: nazwy, kolory, zapis w pliku i rozmieszczanie znaczników wokół gracza. Bez serwera. */
class WaypointLogicTest {
    private static Waypoint wp(String name, String world, double x, double y, double z) {
        return new Waypoint(Waypoint.idOf(name), name, world, x, y, z, 0xFFD700);
    }

    private static Waypoint wp(String name, double x, double y, double z) {
        return wp(name, "world", x, y, z);
    }

    // ---------- nazwy ----------

    @Test
    void namesAllowLettersDigitsUnderscoreAndDashUpToTheLimit() {
        for (String ok : List.of("Bank", "Moj_Dom", "Główny-Bank2", "ŻÓŁĆ", "a", "x".repeat(Waypoint.MAX_NAME))) assertTrue(Waypoint.validName(ok), ok);
        for (String bad : List.of("", "a b", "<red>Bank", "Bank!", "x".repeat(Waypoint.MAX_NAME + 1), "a/b", "a.b")) assertFalse(Waypoint.validName(bad), bad);
        assertFalse(Waypoint.validName(null));
    }

    @Test
    void idsIgnoreCaseAndPolishDiacriticsSoSpellingVariantsAreTheSameWaypoint() {
        assertEquals("bank_glowny", Waypoint.idOf("Bank_Główny"));
        assertEquals("bank_glowny", Waypoint.idOf("BANK_GLOWNY"));
        assertEquals("zolc", Waypoint.idOf("ŻÓŁĆ"));
        assertEquals("zazolc_gesla-jazn", Waypoint.idOf("Zażółć_Gęślą-Jaźń"));
        assertEquals("moj_dom", Waypoint.idOf("Moj_Dom"));
        assertEquals("Moj Dom", Waypoint.display("Moj_Dom"));
        assertEquals("Moj Dom", wp("Moj_Dom", 0, 0, 0).title());
    }

    // ---------- kolory ----------

    @Test
    void colorsParseFromPolishAndEnglishNamesAndHex() {
        assertEquals(0xFF5555, WaypointColors.parse("czerwony"));
        assertEquals(0xFF5555, WaypointColors.parse("RED"));
        assertEquals(0xFFD700, WaypointColors.parse("Złoty"));
        assertEquals(0xFFD700, WaypointColors.parse("zloty"));
        assertEquals(0xFF55FF, WaypointColors.parse("różowy"));
        assertEquals(0x8B4513, WaypointColors.parse("brązowy"));
        assertEquals(0xFFAA00, WaypointColors.parse("#FFAA00"));
        assertEquals(0xFFAA00, WaypointColors.parse("ffaa00"));
        assertEquals(0x0000AA, WaypointColors.parse("#0000aa"));
        assertEquals(0, WaypointColors.parse("czarny"), "czarny to 0, a nie brak koloru");
        assertNull(WaypointColors.parse("ffaa0"));
        assertNull(WaypointColors.parse("#gg0000"));
        assertNull(WaypointColors.parse("fioletowawy"));
        assertNull(WaypointColors.parse(""));
        assertNull(WaypointColors.parse(null));
    }

    @Test
    void colorsPrintBackAsNamesOrHex() {
        assertEquals("ffaa00", WaypointColors.hex(0xFFAA00));
        assertEquals("0000aa", WaypointColors.hex(0x0000AA));
        assertEquals("000000", WaypointColors.hex(0));
        assertEquals("czerwony", WaypointColors.describe(0xFF5555));
        assertEquals("#123456", WaypointColors.describe(0x123456));
        assertEquals(17, WaypointColors.names().size());
        assertTrue(WaypointColors.names().contains("zloty"));
        for (String n : WaypointColors.names()) assertNotNull(WaypointColors.parse(n), n);
    }

    // ---------- gdzie stoi znacznik ----------

    private static final double EPS = 1e-9;

    @Test
    void aNearWaypointGetsItsMarkerRightWhereItIs() {
        Placement p = WaypointView.place(0, 65, 0, 30, 70, -40, 80);
        assertFalse(p.projected());
        assertEquals(30, p.x(), EPS);
        assertEquals(70, p.y(), EPS);
        assertEquals(-40, p.z(), EPS);
        assertEquals(Math.sqrt(30 * 30 + 5 * 5 + 40 * 40), p.distance(), EPS);
        // dokładnie na granicy nadal prawdziwe miejsce
        assertFalse(WaypointView.place(0, 0, 0, 80, 0, 0, 80).projected());
        // waypoint pod nogami
        Placement here = WaypointView.place(5, 64, 5, 5, 64, 5, 80);
        assertFalse(here.projected());
        assertEquals(0, here.distance(), EPS);
    }

    @Test
    void aFarWaypointGetsAMarkerStuckInItsDirectionAtMarkerDistance() {
        Placement p = WaypointView.place(10, 65, 20, 10, 65, 1020, 80);
        assertTrue(p.projected());
        assertEquals(10, p.x(), EPS);
        assertEquals(65, p.y(), EPS);
        assertEquals(100, p.z(), EPS, "80 bloków od gracza w stronę waypointu");
        assertEquals(1000, p.distance(), EPS, "odległość to prawdziwa, a nie przyklejona");

        // kierunek i kąt wzniesienia zostają: waypoint 300 w prawo, 400 do przodu i 200 w górę
        Placement q = WaypointView.place(0, 0, 0, 300, 200, 400, 80);
        assertTrue(q.projected());
        double len = Math.sqrt(q.x() * q.x() + q.y() * q.y() + q.z() * q.z());
        assertEquals(80, len, 1e-6);
        double real = Math.sqrt(300 * 300 + 200 * 200 + 400 * 400);
        assertEquals(300 / real, q.x() / len, 1e-9);
        assertEquals(200 / real, q.y() / len, 1e-9);
        assertEquals(400 / real, q.z() / len, 1e-9);
        assertEquals(real, q.distance(), 1e-9);
    }

    @Test
    void theMarkerMovesContinuouslyAsYouWalkAwayFromTheEdge() {
        // przechodzenie z „prawdziwego miejsca” na „przyklejony” nie skacze: na granicy oba położenia są tym samym punktem
        Placement inside = WaypointView.place(0, 0, 0, 0, 0, 80, 80);
        Placement outside = WaypointView.place(0, 0, 0, 0, 0, 80.001, 80);
        assertEquals(inside.z(), outside.z(), 0.01);
        assertFalse(inside.projected());
        assertTrue(outside.projected());
    }

    // ---------- które waypointy widzi gracz ----------

    @Test
    void playersSeeTheNearestOnesFromTheirOwnWorldNearestFirst() {
        List<Waypoint> all = List.of(wp("Daleki", 0, 64, 500), wp("Blisko", 0, 64, 10), wp("Srednio", 0, 64, 100), wp("Inny_swiat", "nether", 0, 64, 5));
        List<Candidate> c = WaypointView.nearest(all, "world", 0, 64, 0, 10, 0);
        assertEquals(List.of("Blisko", "Srednio", "Daleki"), c.stream().map(x -> x.waypoint().name()).toList());
        assertEquals(10, c.get(0).distance(), EPS);

        assertEquals(List.of("Blisko", "Srednio"), WaypointView.nearest(all, "world", 0, 64, 0, 2, 0).stream().map(x -> x.waypoint().name()).toList(), "limit");
        assertEquals(List.of("Blisko", "Srednio"), WaypointView.nearest(all, "world", 0, 64, 0, 10, 150).stream().map(x -> x.waypoint().name()).toList(), "limit odległości");
        assertEquals(List.of("Inny_swiat"), WaypointView.nearest(all, "nether", 0, 64, 0, 10, 0).stream().map(x -> x.waypoint().name()).toList());
        assertTrue(WaypointView.nearest(all, "world", 0, 64, 0, 0, 0).isEmpty());
        assertTrue(WaypointView.nearest(List.of(), "world", 0, 64, 0, 5, 0).isEmpty());
    }

    @Test
    void equalDistancesAreOrderedByIdSoTheChoiceIsStable() {
        List<Waypoint> all = List.of(wp("B", 10, 64, 0), wp("A", -10, 64, 0), wp("C", 0, 64, 10));
        assertEquals(List.of("A", "B", "C"), WaypointView.nearest(all, "world", 0, 64, 0, 3, 0).stream().map(x -> x.waypoint().name()).toList());
        assertEquals(List.of("A", "B"), WaypointView.nearest(all, "world", 0, 64, 0, 2, 0).stream().map(x -> x.waypoint().name()).toList());
    }

    // ---------- napis ----------

    @Test
    void theLabelKeepsAConstantSizeAndOnlyShrinksWhenTheWaypointIsCloserThanTheLabelDistance() {
        // stały rozmiar niezależnie od tego, jak daleko jest waypoint: napis stoi zawsze na label-distance od gracza
        assertEquals(3.0f, WaypointView.labelScale(12, 12, 3.0), 1e-6);
        assertEquals(3.0f, WaypointView.labelScale(12, 12, 3.0), 1e-6, "ta sama wartość za każdym razem");
        // dalej niż label-distance napis nie rośnie
        assertEquals(3.0f, WaypointView.labelScale(24, 12, 3.0), 1e-6);
        assertEquals(3.0f, WaypointView.labelScale(5000, 12, 3.0), 1e-6);
        // bliżej: maleje proporcjonalnie, żeby na ekranie wyglądał tak samo, ale nie poniżej 15%
        assertEquals(1.5f, WaypointView.labelScale(6, 12, 3.0), 1e-6);
        assertEquals(0.45f, WaypointView.labelScale(0, 12, 3.0), 1e-6);
        assertEquals(0.45f, WaypointView.labelScale(1, 12, 3.0), 1e-6);
        // własny rozmiar z ustawień
        assertEquals(5.0f, WaypointView.labelScale(12, 12, 5.0), 1e-6);
    }

    @Test
    void numbersAndUnitsAgreeInPolish() {
        assertEquals("76 metrów", WaypointView.distance(76));
        assertEquals("0 metrów", WaypointView.distance(0.4));
        assertEquals("1 metr", WaypointView.distance(1.2));
        for (int n : new int[]{2, 3, 4, 22, 23, 24, 32, 102, 104, 222}) assertEquals(n + " metry", WaypointView.distance(n), "" + n);
        for (int n : new int[]{5, 6, 9, 10, 11, 12, 13, 14, 15, 20, 21, 25, 26, 100, 101, 111, 112, 113, 114, 115, 211, 212, 999})
            assertEquals(n + " metrów", WaypointView.distance(n), "" + n);
        assertEquals("999 metrów", WaypointView.distance(999.4));
        assertEquals("76", WaypointView.number(76.4));
        assertEquals("metrów", WaypointView.unit(76.4));
    }

    @Test
    void fromAKilometreOnDistancesAreInKilometresWithACommaAndNoRoundingGlitch() {
        assertEquals("1,0 km", WaypointView.distance(999.6), "999,6 zaokrągla się do 1000, więc już kilometry");
        assertEquals("1,3 km", WaypointView.distance(1250));
        assertEquals("1,5 km", WaypointView.distance(1500));
        assertEquals("12,0 km", WaypointView.distance(12_000));
        assertEquals("km", WaypointView.unit(5000));
        assertEquals("5,0", WaypointView.number(5000));
    }

    @Test
    void aMarkerOnlyNeedsRedrawingWhenSomethingVisibleChanged() {
        Spec a = new Spec(10, 64, 20, 10, 66, 20, "<white>Bank", 2.0f, 0xFF5555, true, true, 256);
        assertFalse(a.differs(a));
        assertFalse(a.differs(new Spec(10.02, 64, 20.02, 10.01, 66.01, 20.01, "<white>Bank", 2.01f, 0xFF5555, true, true, 256)), "mikroskopijne przesunięcia i rozmiar pomijamy");
        assertTrue(a.differs(new Spec(11, 64, 20, 10, 66, 20, "<white>Bank", 2.0f, 0xFF5555, true, true, 256)), "kreska o blok dalej");
        assertTrue(a.differs(new Spec(10.1, 64, 20, 10, 66, 20, "<white>Bank", 2.0f, 0xFF5555, true, true, 256)), "kreska o 10 cm dalej");
        assertTrue(a.differs(new Spec(10, 64, 20, 10.05, 66, 20, "<white>Bank", 2.0f, 0xFF5555, true, true, 256)), "napis o 5 cm dalej: jedzie za graczem co tick, więc reagujemy na każdy realny ruch");
        assertTrue(a.differs(new Spec(10, 64, 20, 10, 66, 20, "<white>Bank", 2.05f, 0xFF5555, true, true, 256)), "rozmiar o 0,05 (waypoint bliżej niż label-distance)");
        assertTrue(a.differs(new Spec(10, 64, 20, 10, 66, 20, "<white>Bank 2", 2.0f, 0xFF5555, true, true, 256)), "napis");
        assertTrue(a.differs(new Spec(10, 64, 20, 10, 66, 20, "<white>Bank", 2.5f, 0xFF5555, true, true, 256)), "rozmiar");
        assertTrue(a.differs(new Spec(10, 64, 20, 10, 66, 20, "<white>Bank", 2.0f, 0x00FF00, true, true, 256)), "kolor");
        assertTrue(a.differs(new Spec(10, 64, 20, 10, 66, 20, "<white>Bank", 2.0f, 0xFF5555, false, true, 256)), "kreska");
        assertTrue(a.differs(new Spec(10, 64, 20, 10, 66, 20, "<white>Bank", 2.0f, 0xFF5555, true, false, 256)), "napis włączony");
        assertTrue(a.differs(new Spec(10, 64, 20, 10, 66, 20, "<white>Bank", 2.0f, 0xFF5555, true, true, 128)), "wysokość");
        // gdy kreski nie ma, jej położenie nie ma znaczenia (i na odwrót dla napisu)
        Spec noBeam = new Spec(10, 64, 20, 10, 66, 20, "<white>Bank", 2.0f, 0xFF5555, false, true, 256);
        assertFalse(noBeam.differs(new Spec(90, 64, 20, 10, 66, 20, "<white>Bank", 2.0f, 0xFF5555, false, true, 256)));
        Spec noLabel = new Spec(10, 64, 20, 10, 66, 20, "<white>Bank", 2.0f, 0xFF5555, true, false, 256);
        assertFalse(noLabel.differs(new Spec(10, 64, 20, 90, 66, 20, "<white>Bank", 2.0f, 0xFF5555, true, false, 256)));
    }

    // ---------- plik ----------

    @Test
    void waypointsSurviveSavingAndLoadingInTheSameOrder(@TempDir Path dir) throws IOException {
        File f = dir.resolve("sub").resolve("waypoints.yml").toFile();
        WaypointStore a = new WaypointStore(f);
        a.put(new Waypoint("bank", "Bank", "world", 10.5, 64, -3.25, 0xFF5555));
        a.put(new Waypoint("moj_dom", "Moj_Dom", "world_nether", -100.125, 12, 7, 0x00AA00));
        a.put(new Waypoint("zazolc", "Zażółć", "world", 0, 0, 0, 0));
        a.save();
        assertTrue(f.isFile(), "plik powstaje razem z folderem");

        WaypointStore b = new WaypointStore(f);
        b.load();
        assertEquals(3, b.size());
        assertEquals(List.of("bank", "moj_dom", "zazolc"), b.ids());
        Waypoint dom = b.get("MOJ_DOM");
        assertNotNull(dom, "szukanie ignoruje wielkość liter");
        assertEquals(new Waypoint("moj_dom", "Moj_Dom", "world_nether", -100.125, 12, 7, 0x00AA00), dom);
        assertEquals(0, b.get("Zażółć").color(), "czarny też się zapisuje");
        assertNotNull(b.get("zazolc"), "ten sam waypoint bez ogonków");
        assertEquals(0xFF5555, b.get("bank").color());

        assertNotNull(b.remove("Bank"));
        assertNull(b.remove("Bank"));
        b.save();
        WaypointStore c = new WaypointStore(f);
        c.load();
        assertEquals(List.of("moj_dom", "zazolc"), c.ids());
    }

    @Test
    void damagedEntriesAreSkippedAndABadColourFallsBackToGold(@TempDir Path dir) throws IOException {
        File f = dir.resolve("waypoints.yml").toFile();
        Files.writeString(f.toPath(), String.join("\n",
                "waypoints:",
                "  dobry:",
                "    name: Dobry",
                "    world: world",
                "    x: 1.5",
                "    y: 64",
                "    z: 2.5",
                "    color: nie-kolor",
                "  bez_wspolrzednych:",
                "  zly:",
                "    name: Zla nazwa!",
                "    world: world",
                "    x: 0",
                "    y: 0",
                "    z: 0",
                "  bez_swiata:",
                "    name: Bez_swiata",
                "    x: 0",
                "    y: 0",
                "    z: 0",
                "  brak_z:",
                "    name: Brak_z",
                "    world: world",
                "    x: 0",
                "    y: 0",
                "  duplikat:",
                "    name: DOBRY",
                "    world: world",
                "    x: 9",
                "    y: 9",
                "    z: 9",
                ""));
        WaypointStore s = new WaypointStore(f);
        s.load();
        assertEquals(List.of("dobry"), s.ids(), "tylko poprawny wpis, bez duplikatu o tej samej nazwie");
        Waypoint w = s.get("Dobry");
        assertEquals(1.5, w.x());
        assertEquals(0xFFD700, w.color());
        assertEquals("world", w.world());

        WaypointStore missing = new WaypointStore(dir.resolve("nie-ma.yml").toFile());
        missing.load();
        assertEquals(0, missing.size());
    }
}
