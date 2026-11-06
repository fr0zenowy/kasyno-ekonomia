package pl.kasyno.work;

import org.junit.jupiter.api.Test;
import pl.kasyno.show.Stage.P;
import pl.kasyno.work.JobDef.Kind;
import pl.kasyno.work.JobDef.NodeType;
import pl.kasyno.work.JobPlanner.Plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Czysta logika prac: rozstawianie celów, losowanie rodzajów, kompas i bilans wypłat. Bez Bukkita. */
class WorkLogicTest {
    /** Teren z dowolnych kształtów: ziemia i woda tam, gdzie pozwalają predykaty (płasko na y = 64). */
    private static Terrain terrain(Predicate<P> ground, Predicate<P> water) {
        return new Terrain() {
            @Override
            public P ground(double x, double z) {
                P p = new P(Math.floor(x) + 0.5, 64, Math.floor(z) + 0.5);
                return ground.test(p) ? p : null;
            }

            @Override
            public P water(double x, double z) {
                P p = new P(Math.floor(x) + 0.5, 63.9, Math.floor(z) + 0.5);
                return water.test(p) ? p : null;
            }
        };
    }

    private static final P CENTER = new P(0.5, 64, 0.5);

    private static JobDef job(String id) {
        JobDef j = Jobs.find(id);
        assertNotNull(j, id);
        return j;
    }

    // ---------- planer ----------

    @Test
    void everyCollectingJobGetsItsFullSetOfSpacedTargetsInsideTheRadius() {
        Terrain flat = terrain(p -> true, p -> false);
        for (String id : List.of("lesnik", "gornik", "sprzatacz")) {
            JobDef j = job(id);
            List<Plan> plan = JobPlanner.plan(j, flat, CENTER, j.radius(), new Random(7), 1.0);
            assertEquals(j.nodes(), plan.size(), id);
            for (Plan a : plan) {
                assertTrue(a.pos().flat(CENTER) <= j.radius() + 1, id + ": w promieniu");
                assertTrue(a.type() >= 0 && a.type() < j.types().size(), id);
                for (Plan b : plan) if (a != b) assertTrue(a.pos().flat(b.pos()) >= 2.4, id + ": odstęp między celami");
            }
        }
    }

    @Test
    void targetsOnlyAppearWhereTheTerrainAllowsIt() {
        // polana o promieniu 12 w środku pustkowia: żaden cel nie może wypaść poza nią
        Terrain clearing = terrain(p -> p.flat(CENTER) <= 12, p -> false);
        JobDef j = job("lesnik");
        for (int seed = 0; seed < 20; seed++) {
            List<Plan> plan = JobPlanner.plan(j, clearing, CENTER, j.radius(), new Random(seed), 1.0);
            assertFalse(plan.isEmpty(), "polana jest dość duża na grzyby (seed " + seed + ")");
            for (Plan a : plan) assertTrue(a.pos().flat(CENTER) <= 12.8, "cel na polanie: " + a.pos().flat(CENTER));
        }
    }

    @Test
    void noSuitableTerrainMeansNoRound() {
        JobDef lesnik = job("lesnik");
        assertTrue(JobPlanner.plan(lesnik, terrain(p -> false, p -> false), CENTER, lesnik.radius(), new Random(1), 1.0).isEmpty(), "brak ziemi");
        JobDef rybak = job("rybak");
        assertTrue(JobPlanner.plan(rybak, terrain(p -> true, p -> false), CENTER, rybak.radius(), new Random(1), 1.0).isEmpty(), "ziemia nie zastąpi wody");
        // kałuża: mniej niż pół kompletu miejsc na ryby
        assertTrue(JobPlanner.plan(rybak, terrain(p -> false, p -> p.flat(new P(20.5, 64, 0.5)) <= 1.2), CENTER, rybak.radius(), new Random(1), 1.0).isEmpty(), "kałuża za mała");
    }

    @Test
    void fishAreOnlyPlacedOnWaterAndAGoodLakeGivesAFullShoal() {
        JobDef j = job("rybak");
        P lake = new P(15.5, 64, 0.5);
        Terrain t = terrain(p -> false, p -> p.flat(lake) <= 9);
        List<Plan> plan = JobPlanner.plan(j, t, CENTER, j.radius(), new Random(3), 1.0);
        assertEquals(j.nodes(), plan.size());
        for (Plan a : plan) assertTrue(a.pos().flat(lake) <= 9.7, "ryba w jeziorze");
    }

    @Test
    void routeStopsAreFarApartAndHuntStaysAwayFromTheStart() {
        Terrain flat = terrain(p -> true, p -> false);
        JobDef kurier = job("kurier");
        List<Plan> stops = JobPlanner.plan(kurier, flat, CENTER, kurier.radius(), new Random(11), 1.0);
        assertEquals(kurier.quota(), stops.size());
        for (Plan a : stops) {
            assertTrue(a.pos().flat(CENTER) >= 13.5, "adres nie leży pod nosem NPC");
            for (Plan b : stops) if (a != b) assertTrue(a.pos().flat(b.pos()) >= 17.9, "adresy nie są obok siebie");
        }
        JobDef skarb = job("poszukiwacz");
        for (int seed = 0; seed < 20; seed++) {
            Plan p = JobPlanner.plan(skarb, flat, CENTER, skarb.radius(), new Random(seed), 1.0).get(0);
            assertTrue(p.pos().flat(CENTER) >= 24, "skarb jest daleko od startu");
        }
    }

    @Test
    void smallRadiusStillWorksAndNeverReturnsHalfWrongSpots() {
        Terrain both = terrain(p -> true, p -> true); // ziemia i woda naraz: rybak też ma gdzie pracować
        for (JobDef j : Jobs.ALL) {
            List<Plan> plan = JobPlanner.plan(j, both, CENTER, 10, new Random(5), 1.0);
            assertFalse(plan.isEmpty(), j.id() + " działa nawet w małym promieniu");
            for (Plan a : plan) assertTrue(a.pos().flat(CENTER) <= 10.8, j.id());
        }
    }

    @Test
    void spotAvoidsTheGivenPointsAndGivesUpWhenThereIsNoRoom() {
        JobDef j = job("lesnik");
        Terrain flat = terrain(p -> true, p -> false);
        List<P> taken = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Optional<P> p = JobPlanner.spot(j, flat, CENTER, 40, new Random(i), taken);
            assertTrue(p.isPresent());
            for (P o : taken) assertTrue(o.flat(p.get()) >= 2.5);
            taken.add(p.get());
        }
        // cały teren to jedno zajęte pole: nie ma miejsca
        Terrain one = terrain(p -> p.flat(CENTER) < 1, p -> false);
        assertTrue(JobPlanner.spot(j, one, CENTER, 40, new Random(1), List.of(CENTER)).isEmpty());
    }

    // ---------- losowanie rodzajów ----------

    private static double rareShare(JobDef j, double luck, int draws) {
        Random rng = new Random(99);
        int rare = 0;
        for (int i = 0; i < draws; i++) if (j.type(JobPlanner.pickType(j, luck, rng)).rare()) rare++;
        return rare / (double) draws;
    }

    @Test
    void luckRaisesTheShareOfRareTargetsAndEveryTypeCanAppear() {
        for (JobDef j : Jobs.ALL) {
            if (j.types().stream().noneMatch(NodeType::rare) || j.types().stream().allMatch(NodeType::rare)) continue;
            double base = rareShare(j, 1.0, 20_000);
            double lucky = rareShare(j, 3.0, 20_000);
            assertTrue(lucky > base + 0.05, j.id() + ": szczęście x3 daje więcej rzadkich (" + base + " -> " + lucky + ")");
            assertTrue(rareShare(j, 0.0, 2_000) == 0.0, j.id() + ": bez szczęścia nie ma rzadkich");
        }
        for (JobDef j : Jobs.ALL) {
            Set<Integer> seen = new HashSet<>();
            Random rng = new Random(5);
            for (int i = 0; i < 40_000; i++) seen.add(JobPlanner.pickType(j, 1.0, rng));
            assertEquals(j.types().size(), seen.size(), j.id() + ": każdy rodzaj się zdarza");
        }
    }

    // ---------- definicje i bilans ----------

    @Test
    void jobDefinitionsAreConsistent() {
        Set<String> ids = new HashSet<>();
        for (JobDef j : Jobs.ALL) {
            assertTrue(ids.add(j.id()), "unikalne id " + j.id());
            assertFalse(j.title().isBlank());
            assertFalse(j.npc().isBlank());
            assertFalse(j.types().isEmpty());
            assertTrue(j.radius() >= 10 && j.radius() <= WorkService.MAX_RADIUS, j.id());
            assertTrue(j.seconds() >= 60 && j.cooldownMs() >= 60_000, j.id() + ": czas rundy i przerwa");
            assertTrue(j.quota() >= 1 && j.nodes() >= 1, j.id());
            assertTrue(j.completionBonus() >= 0 && j.completionBonus() <= 0.5, j.id());
            Set<String> typeIds = new HashSet<>();
            for (NodeType t : j.types()) {
                assertTrue(typeIds.add(t.id()), j.id() + " unikalny rodzaj " + t.id());
                assertTrue(t.min() > 0 && t.max() >= t.min() && t.weight() > 0, t.id());
                assertTrue(t.glow().matches("#[0-9a-fA-F]{6}"), t.id() + ": kolor poświaty");
                assertNotNull(org.bukkit.Material.matchMaterial(t.icon()), j.id() + "/" + t.id() + ": materiał " + t.icon());
            }
            assertTrue(Jobs.isJob(j.id()));
            assertEquals(j, Jobs.find(j.id()));
        }
        assertEquals(Jobs.ALL.size(), Jobs.ids().size());
        assertEquals(Kind.ROUTE, job("kurier").kind());
        assertEquals(Kind.HUNT, job("poszukiwacz").kind());
        assertEquals(Kind.WATER, job("rybak").kind());
        // zbieranie i kopanie wymagają tych samych narzędzi, co istniejące zarobki
        assertEquals("las", job("lesnik").gatherKey());
        assertEquals("kopalnia", job("gornik").gatherKey());
        assertEquals("ryby", job("rybak").gatherKey());
    }

    @Test
    void roundPayStaysInTheIntendedRangeAndDoesNotBeatTheLateGameJobs() {
        for (JobDef j : Jobs.ALL) {
            double round = j.expectedRound();
            assertTrue(round >= 400 && round <= 1_100, j.id() + ": przeciętnie " + round + " $ za rundę");
            double perMinute = round / ((j.seconds() * 1000.0 + j.cooldownMs()) / 60_000.0);
            assertTrue(perMinute >= 60 && perMinute <= 260, j.id() + ": " + perMinute + " $/min razem z przerwą");
            // pojedynczy cel nie może być wart więcej niż kilka rund zwykłego zarobku
            for (NodeType t : j.types()) assertTrue(t.max() <= 1_000, t.id() + " max " + t.max());
        }
    }

    // ---------- kompas ----------

    @Test
    void arrowPointsRelativeToWhereThePlayerLooks() {
        // yaw 0 patrzy na południe (+z), zachód (-x) jest po prawej ręce
        assertEquals("↑", Compass.arrow(0, 0, 10));
        assertEquals("→", Compass.arrow(0, -10, 0));
        assertEquals("←", Compass.arrow(0, 10, 0));
        assertEquals("↓", Compass.arrow(0, 0, -10));
        assertEquals("↗", Compass.arrow(0, -10, 10));
        assertEquals("↖", Compass.arrow(0, 10, 10));
        assertEquals("↘", Compass.arrow(0, -10, -10));
        assertEquals("↙", Compass.arrow(0, 10, -10));
        // gracz patrzy na zachód (yaw 90): cel na zachodzie jest przed nim, na południu po lewej
        assertEquals("↑", Compass.arrow(90, -10, 0));
        assertEquals("←", Compass.arrow(90, 0, 10));
        assertEquals("→", Compass.arrow(90, 0, -10));
        // yaw poza zakresem (np. 450 = 90, -270 = 90) działa tak samo
        assertEquals("↑", Compass.arrow(450, -10, 0));
        assertEquals("↑", Compass.arrow(-270, -10, 0));
        assertEquals("•", Compass.arrow(0, 0, 0));
    }

    @Test
    void warmthGetsHotterAsYouGetCloserAndTheClockReadsWell() {
        int prev = -1;
        for (double d : new double[]{100, 55, 54, 38, 30, 24, 20, 13, 10, 6, 3, 0}) {
            int w = Compass.warmth(d);
            assertTrue(w >= prev, "nie robi się zimniej przy zbliżaniu: " + d);
            prev = w;
            assertFalse(Compass.warmthLabel(w).isBlank());
        }
        assertEquals(0, Compass.warmth(200));
        assertEquals(5, Compass.warmth(1));
        assertEquals("2:30", Compass.clock(3000));
        assertEquals("0:01", Compass.clock(1));
        assertEquals("0:00", Compass.clock(0));
        assertEquals("1:00", Compass.clock(1200));
        assertEquals("0:00", Compass.clock(-50));
    }

    @Test
    void courierTipRewardsSpeed() {
        assertEquals(0.25, WorkService.tipFactor(40, 0), 1e-9);
        double allowed = 40 / 4.3 * 20 + 160;
        assertEquals(0.25, WorkService.tipFactor(40, (long) allowed), 1e-9);
        assertEquals(0.10, WorkService.tipFactor(40, (long) allowed + 20), 1e-9);
        assertEquals(0.0, WorkService.tipFactor(40, (long) (allowed * 1.7)), 1e-9);
        assertTrue(WorkService.tipFactor(80, 400) >= WorkService.tipFactor(20, 400), "dalszy adres daje więcej czasu");
    }

    @Test
    void radiusIsClamped() {
        assertEquals(WorkService.MIN_RADIUS, WorkService.clampRadius(1));
        assertEquals(WorkService.MAX_RADIUS, WorkService.clampRadius(9_999));
        assertEquals(40, WorkService.clampRadius(40));
    }
}
