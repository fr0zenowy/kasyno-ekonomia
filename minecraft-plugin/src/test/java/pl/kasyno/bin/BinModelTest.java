package pl.kasyno.bin;

import org.bukkit.Material;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import pl.kasyno.bin.BinModel.Xform;
import pl.kasyno.bin.BinType.Part;
import pl.kasyno.core.Catalog;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Modele śmietników (geometria, mieszczenie się w polu kliknięcia, obrót, wieko) i łup z podziałem na rodzaje. Bez Bukkita. */
class BinModelTest {
    private static final double EPS = 1e-4;

    // ---------- definicje ----------

    @Test
    void typesAreWellFormedAndBetterBinsNeedALongerBreak() {
        assertEquals(4, BinType.ALL.size());
        Set<String> ids = new HashSet<>();
        BinType prev = null;
        for (BinType t : BinType.ALL) {
            assertTrue(ids.add(t.id()), "unikalne id " + t.id());
            assertFalse(t.name().isBlank());
            assertTrue(t.cooldownSec() >= BinManager.MIN_COOLDOWN && t.cooldownSec() <= BinManager.MAX_COOLDOWN, t.id());
            assertTrue(t.hitW() > 0 && t.hitH() > 0, t.id());
            assertTrue(t.miss() >= 0 && t.miss() < 0.5, t.id());
            assertTrue(t.luck() >= 1 && t.qty() >= 1, t.id());
            assertEquals(t, BinType.find(t.id()));
            if (prev != null) {
                assertTrue(t.cooldownSec() > prev.cooldownSec(), t.id() + ": dłuższa przerwa niż " + prev.id());
                assertTrue(t.luck() > prev.luck() && t.qty() > prev.qty(), t.id() + ": lepszy łup niż " + prev.id());
                assertTrue(t.miss() < prev.miss(), t.id() + ": rzadziej puste niż " + prev.id());
            }
            prev = t;
        }
        assertNull(BinType.find("nie_ma"));
        assertEquals(BinType.ALL.size(), BinType.ids().size());
    }

    @Test
    void everyModelHasSolidPartsALidAndRealBlocks() {
        for (BinType t : BinType.ALL) {
            assertTrue(t.parts().size() >= 4, t.id() + ": model z kilku brył");
            assertTrue(t.parts().stream().anyMatch(Part::lid), t.id() + ": ma wieko");
            for (Part p : t.parts()) {
                Material m = Material.matchMaterial(BinModel.materialName(p.block()));
                assertNotNull(m, t.id() + ": nieznany blok " + p.block());
                assertTrue(p.sx() > 0 && p.sy() > 0 && p.sz() > 0, t.id() + ": rozmiar " + p.block());
                assertTrue(p.cy() - p.sy() / 2 >= -1e-6, t.id() + ": " + p.block() + " nie wchodzi pod ziemię");
            }
        }
        assertEquals("BARREL", BinModel.materialName("minecraft:barrel[facing=up]"));
        assertEquals("GRAY_CONCRETE", BinModel.materialName("minecraft:gray_concrete"));
        assertEquals("OAK_LOG", BinModel.materialName("oak_log"));
    }

    @Test
    void theClosedModelFitsInsideItsClickBox() {
        for (BinType t : BinType.ALL) {
            for (int yaw : new int[]{0, 90, 180, 270}) {
                for (Part p : t.parts()) {
                    for (Vector3f c : BinModel.corners(p, yaw, 0)) {
                        assertTrue(Math.abs(c.x) <= t.hitW() / 2 + 0.05, t.id() + " x " + c.x + " yaw " + yaw);
                        assertTrue(Math.abs(c.z) <= t.hitW() / 2 + 0.05, t.id() + " z " + c.z + " yaw " + yaw);
                        assertTrue(c.y >= -1e-4 && c.y <= t.hitH() + 0.05, t.id() + " y " + c.y);
                    }
                }
            }
        }
    }

    // ---------- geometria ----------

    @Test
    void theTransformPutsEachBlocksCentreWhereTheModelSaysItIs() {
        Random rng = new Random(3);
        for (BinType t : BinType.ALL) {
            for (Part p : t.parts()) {
                for (int i = 0; i < 12; i++) {
                    double yaw = rng.nextInt(4) * 90;
                    double open = p.lid() ? rng.nextDouble() * 80 : 0;
                    Xform x = BinModel.xform(p, yaw, open);
                    Vector3f mid = x.rotation().transform(new Vector3f(x.scale()).mul(0.5f)).add(x.translation());
                    Vector3f want = BinModel.center(p, yaw, open);
                    assertEquals(want.x, mid.x, EPS, t.id() + " x");
                    assertEquals(want.y, mid.y, EPS, t.id() + " y");
                    assertEquals(want.z, mid.z, EPS, t.id() + " z");
                    assertEquals((float) p.sx(), x.scale().x, EPS);
                }
            }
        }
    }

    @Test
    void turningTheModelRotatesEveryPartAroundTheCentreOfTheBase() {
        Part lid = BinType.find("kontener").parts().stream().filter(p -> p.lid() && p.cx() < 0).findFirst().orElseThrow();
        Vector3f a = BinModel.center(lid, 0, 0);
        assertEquals(-0.45, a.x, EPS);
        assertEquals(1.17, a.y, EPS);
        assertEquals(0, a.z, EPS);
        Vector3f b = BinModel.center(lid, 90, 0); // (x, z) -> (z, -x)
        assertEquals(0, b.x, EPS);
        assertEquals(1.17, b.y, EPS);
        assertEquals(0.45, b.z, EPS);
        Vector3f c = BinModel.center(lid, 180, 0);
        assertEquals(0.45, c.x, EPS);
        assertEquals(0, c.z, EPS);
        Vector3f d = BinModel.center(lid, 270, 0);
        assertEquals(0, d.x, EPS);
        assertEquals(-0.45, d.z, EPS);
        // obrót nie zmienia odległości od środka podstawy
        for (int yaw : new int[]{90, 180, 270}) assertEquals(Math.hypot(a.x, a.z), Math.hypot(BinModel.center(lid, yaw, 0).x, BinModel.center(lid, yaw, 0).z), EPS);
    }

    @Test
    void theLidSwingsUpOnItsBackHingeAndTheHingeStaysPut() {
        for (BinType t : BinType.ALL) {
            for (Part lid : t.parts()) {
                if (!lid.lid()) continue;
                Vector3f hinge = BinModel.hinge(lid);
                Vector3f closed = BinModel.center(lid, 0, 0);
                Vector3f open = BinModel.center(lid, 0, BinModel.OPEN_DEGREES);
                // odległość środka wieka od zawiasu się nie zmienia, wieko tylko się obraca
                assertEquals(closed.distance(hinge), open.distance(hinge), EPS, t.id());
                // przód wieka (z+) idzie w górę: najwyższy narożnik jest wyżej niż przy zamkniętym
                double maxClosed = 0;
                double maxOpen = 0;
                for (Vector3f c : BinModel.corners(lid, 0, 0)) maxClosed = Math.max(maxClosed, c.y);
                for (Vector3f c : BinModel.corners(lid, 0, BinModel.OPEN_DEGREES)) maxOpen = Math.max(maxOpen, c.y);
                assertTrue(maxOpen > maxClosed + lid.sz() * 0.5, t.id() + ": wieko się unosi (" + maxClosed + " -> " + maxOpen + ")");
                // środek tylnej dolnej krawędzi bryły (lokalny punkt 0,5 / 0 / 0) leży w zawiasie i po otwarciu, i przed nim
                for (double open2 : new double[]{0, BinModel.OPEN_DEGREES}) {
                    Xform x = BinModel.xform(lid, 0, open2);
                    Vector3f edge = x.rotation().transform(new Vector3f(x.scale()).mul(0.5f, 0f, 0f)).add(x.translation());
                    assertEquals(hinge.x, edge.x, EPS, t.id() + ": zawias x przy otwarciu " + open2);
                    assertEquals(hinge.y, edge.y, EPS, t.id() + ": zawias y przy otwarciu " + open2);
                    assertEquals(hinge.z, edge.z, EPS, t.id() + ": zawias z przy otwarciu " + open2);
                }
                // po obrocie modelu zawias jedzie razem z modelem
                Xform turned = BinModel.xform(lid, 90, BinModel.OPEN_DEGREES);
                Vector3f turnedEdge = turned.rotation().transform(new Vector3f(turned.scale()).mul(0.5f, 0f, 0f)).add(turned.translation());
                Vector3f turnedHinge = new Quaternionf().rotateY((float) Math.toRadians(90)).transform(new Vector3f(hinge));
                assertEquals(turnedHinge.x, turnedEdge.x, EPS, t.id() + ": zawias po obrocie x");
                assertEquals(turnedHinge.z, turnedEdge.z, EPS, t.id() + ": zawias po obrocie z");
                // przy obrocie modelu wieko nadal otwiera się w górę
                for (int yaw : new int[]{90, 180, 270}) {
                    double top = 0;
                    for (Vector3f c : BinModel.corners(lid, yaw, BinModel.OPEN_DEGREES)) top = Math.max(top, c.y);
                    assertEquals(maxOpen, top, EPS, t.id() + " yaw " + yaw);
                }
            }
        }
    }

    @Test
    void openingDoesNothingToPartsThatAreNotLids() {
        Part body = BinType.find("kosz").parts().get(0);
        assertFalse(body.lid());
        Vector3f a = BinModel.center(body, 0, 0);
        Vector3f b = BinModel.center(body, 0, 65);
        assertEquals(a.x, b.x, EPS);
        assertEquals(a.y, b.y, EPS);
        assertEquals(a.z, b.z, EPS);
    }

    @Test
    void yawIsSnappedToRightAnglesAndBinsFaceThePlayer() {
        assertEquals(0, BinModel.snapYaw(0));
        assertEquals(0, BinModel.snapYaw(44));
        assertEquals(90, BinModel.snapYaw(46));
        assertEquals(180, BinModel.snapYaw(135));
        assertEquals(270, BinModel.snapYaw(-90));
        assertEquals(0, BinModel.snapYaw(360));
        assertEquals(90, BinModel.snapYaw(450));
        assertEquals(0, BinModel.snapYaw(-10));
        // gracz patrzy na południe (yaw 0): przód śmietnika musi patrzeć na północ, czyli na gracza
        assertEquals(180, BinModel.facing(0));
        assertEquals(90, BinModel.facing(90));
        assertEquals(0, BinModel.facing(180));
        assertEquals(270, BinModel.facing(-90));
        for (int playerYaw : new int[]{0, 90, 180, 270, -90}) {
            // przód modelu po obrocie ma być przeciwny do kierunku patrzenia gracza (-sin y, cos y)
            Vector3f front = new Quaternionf().rotateY((float) Math.toRadians(BinModel.facing(playerYaw))).transform(new Vector3f(0, 0, 1));
            double lookX = -Math.sin(Math.toRadians(playerYaw));
            double lookZ = Math.cos(Math.toRadians(playerYaw));
            assertEquals(-1.0, front.x * lookX + front.z * lookZ, 1e-4, "yaw gracza " + playerYaw);
        }
    }

    // ---------- łup ----------

    private static final int DRAWS = 120_000;

    private record Stats(double miss, double avgValue, double rareShare, double crateShare, int maxSingleQty, Map<String, Integer> counts) {}

    private static Stats simulate(Catalog cat, BinType t, double luckMult, double lootMult) {
        Catalog.Gather g = cat.gather.get("smietnik");
        Random rng = new Random(11);
        Set<String> rareIds = new HashSet<>();
        Set<String> singles = new HashSet<>();
        for (Catalog.LootEntry e : g.table()) {
            if (e.rare()) rareIds.add(e.id());
            if (e.qty()[1] == 1) singles.add(e.id());
        }
        int miss = 0;
        int rare = 0;
        int crates = 0;
        int maxSingle = 0;
        double value = 0;
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < DRAWS; i++) {
            BinLoot.Drop d = BinLoot.roll(g, t, luckMult, lootMult, rng);
            if (d == null) {
                miss++;
                continue;
            }
            assertTrue(d.qty() >= 1, "ilość co najmniej 1");
            Catalog.Item it = cat.item(d.item());
            assertNotNull(it, "przedmiot z tabeli istnieje: " + d.item());
            value += it.sellValue() * (double) d.qty();
            counts.merge(d.item(), 1, Integer::sum);
            if (rareIds.contains(d.item())) rare++;
            if (it.type().equals("crate")) crates++;
            if (singles.contains(d.item())) maxSingle = Math.max(maxSingle, d.qty());
        }
        return new Stats(miss / (double) DRAWS, value / DRAWS, rare / (double) DRAWS, crates / (double) DRAWS, maxSingle, counts);
    }

    @Test
    void lootComesFromTheTrashTableWithTheTypesOwnMissRate() {
        Catalog cat = Catalog.load();
        Catalog.Gather g = cat.gather.get("smietnik");
        Set<String> table = new HashSet<>();
        for (Catalog.LootEntry e : g.table()) table.add(e.id());
        for (BinType t : BinType.ALL) {
            Stats s = simulate(cat, t, 1.0, 1.0);
            assertEquals(t.miss(), s.miss(), 0.01, t.id() + ": szansa na pusty strzał");
            assertTrue(table.containsAll(s.counts().keySet()), t.id() + ": tylko przedmioty z tabeli śmietnika");
            assertEquals(1, s.maxSingleQty(), t.id() + ": telefon, zegarek, obrączka i skrzynka zawsze po jednej sztuce");
        }
    }

    @Test
    void betterBinsPayMoreRareFindsAndEarnAboutTheSamePerMinute() {
        Catalog cat = Catalog.load();
        double prevAvg = 0;
        double prevRare = 0;
        for (BinType t : BinType.ALL) {
            Stats s = simulate(cat, t, 1.0, 1.0);
            double perMinute = s.avgValue() / (t.cooldownSec() / 60.0);
            assertTrue(s.avgValue() > prevAvg, t.id() + ": średnio więcej za jedno przeszukanie niż poprzedni (" + s.avgValue() + ")");
            assertTrue(s.rareShare() > prevRare, t.id() + ": więcej rzadkich znalezisk");
            assertTrue(perMinute >= 10 && perMinute <= 30, t.id() + ": " + perMinute + " $/min wartości łupu (menu: ok. 50, ale bez chodzenia)");
            assertTrue(s.crateShare() <= 0.06, t.id() + ": skrzynka w co najwyżej 6% przeszukań");
            prevAvg = s.avgValue();
            prevRare = s.rareShare();
        }
    }

    @Test
    void playerLuckAndLootBoostsStillApplyAndNeverBreakTheRules() {
        Catalog cat = Catalog.load();
        BinType kosz = BinType.find("kosz");
        Stats base = simulate(cat, kosz, 1.0, 1.0);
        Stats lucky = simulate(cat, kosz, 3.0, 1.0);
        assertTrue(lucky.rareShare() > base.rareShare() + 0.05, "eliksir szczęścia daje więcej rzadkich");
        Stats loot2 = simulate(cat, kosz, 1.0, 2.0);
        assertTrue(loot2.avgValue() > base.avgValue() * 1.2, "eliksir łupu zwiększa ilość puszek i butelek");
        Stats none = simulate(cat, kosz, 0.0, 1.0);
        assertEquals(0.0, none.rareShare(), 1e-9, "bez szczęścia nie ma rzadkich");
    }
}
