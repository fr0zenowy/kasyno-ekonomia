package pl.kasyno.show;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.kasyno.core.Heist;
import pl.kasyno.games.HeistFrames;
import pl.kasyno.show.Stage.P;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pokaz napadu w świecie: ruch, eskorta złapanych, drzwi, lampy i sprzątanie, sprawdzone na atrapie świata. */
class ShowRunnerTest {
    /** Stage jak z prefabrykatu banku (współrzędne względne, świat „w”). */
    static Stage stage() {
        Stage s = new Stage();
        s.world("w");
        s.set("start", new P(13.5, 0, 3.5));
        s.set("street", new P(8.5, 0, 3.5));
        s.set("door", new P(8.5, 0, 4.5));
        s.set("hall", new P(8.5, 0, 8.0));
        s.set("front", new P(8.5, 0, 11.0));
        s.set("safe", new P(8.5, 0, 14.0));
        s.set("guard", new P(8.5, 0, 9.5));
        s.set("police", new P(4.5, 0, 3.5));
        s.set("jail", new P(2.5, 0, 3.0));
        s.set("view", new P(8.5, 0, -4.5));
        return s;
    }

    /** Atrapa świata: liczy postacie, pamięta ich pozycje i stan drzwi. */
    static final class Fake implements ShowRunner.Backend {
        int nextId;
        final Map<Integer, P> pos = new HashMap<>();
        final Set<Integer> alive = new HashSet<>();
        final Map<Integer, String> kind = new HashMap<>();
        final Map<String, Boolean> doors = new HashMap<>();
        final Set<String> everOpened = new HashSet<>();
        boolean lamps;
        final Map<String, Integer> fx = new HashMap<>();
        final Map<HeistFrames.Cue, Integer> sounds = new HashMap<>();
        double maxStep;
        int swings;

        @Override
        public Object spawnCrew(UUID skin, String name, P at, float yaw) {
            int id = nextId++;
            pos.put(id, at);
            alive.add(id);
            kind.put(id, "crew");
            return id;
        }

        @Override
        public Object spawnGuard(String name, boolean police, P at, float yaw) {
            int id = nextId++;
            pos.put(id, at);
            alive.add(id);
            kind.put(id, police ? "police" : "guard");
            return id;
        }

        @Override
        public void teleport(Object h, P at, float yaw) {
            int id = (Integer) h;
            assertTrue(alive.contains(id), "teleport usuniętej postaci " + id);
            P old = pos.get(id);
            maxStep = Math.max(maxStep, Math.hypot(at.x() - old.x(), at.z() - old.z()));
            assertTrue(Double.isFinite(at.x()) && Double.isFinite(at.z()) && Double.isFinite(yaw));
            pos.put(id, at);
        }

        @Override
        public void sneak(Object h, boolean on) {
        }

        @Override
        public void swing(Object h) {
            swings++;
        }

        @Override
        public void remove(Object h) {
            alive.remove((Integer) h);
        }

        @Override
        public void sound(HeistFrames.Cue cue, P at) {
            sounds.merge(cue, 1, Integer::sum);
        }

        @Override
        public void fx(String kind, P at) {
            fx.merge(kind, 1, Integer::sum);
        }

        @Override
        public void door(String which, boolean open) {
            doors.put(which, open);
            if (open) everOpened.add(which);
        }

        @Override
        public void lamps(boolean lit) {
            lamps = lit;
        }
    }

    private static List<UUID> crew(int n) {
        List<UUID> c = new ArrayList<>();
        for (int i = 0; i < n; i++) c.add(UUID.randomUUID());
        return c;
    }

    @Test
    void everyHeistPlaysOutAndLeavesNothingBehind() {
        Random rng = new Random(33);
        int escaped = 0;
        int caught = 0;
        for (int run = 0; run < 400; run++) {
            int n = 2 + rng.nextInt(9);
            List<UUID> crew = crew(n);
            List<String> names = crew.stream().map(u -> "g" + u.toString().substring(0, 3)).toList();
            Heist.Result res = Heist.run(crew, u -> "x", rng);
            List<HeistFrames.Frame> frames = HeistFrames.build(res, crew);
            Fake fake = new Fake();
            List<Integer> seen = new ArrayList<>();
            int[] done = {0};
            ShowRunner runner = new ShowRunner(fake, stage(), crew, names, frames, seen::add, () -> done[0]++);

            int ticks = 0;
            while (!runner.tick()) {
                ticks++;
                assertTrue(ticks < 6_000, "pokaz się nie kończy (klatka " + runner.frameIndex() + " z " + frames.size() + ")");
            }
            assertEquals(1, done[0], "onDone dokładnie raz");
            assertTrue(runner.done());
            assertTrue(fake.alive.isEmpty(), "wszystkie postacie usunięte po pokazie: " + fake.alive.size());
            assertEquals(Boolean.FALSE, fake.doors.getOrDefault("bank", false), "drzwi banku zamknięte");
            assertEquals(Boolean.FALSE, fake.doors.getOrDefault("vault", false), "drzwi skarbca zamknięte");
            assertFalse(fake.lamps, "lampy zgaszone");
            // każda klatka filmu zostaje pokazana, po kolei
            assertEquals(frames.size(), seen.size());
            for (int i = 0; i < seen.size(); i++) assertEquals(i, seen.get(i));
            // ekipa pojawia się w komplecie, a gwardia tylko wtedy, gdy ktoś wpadł
            long crewCount = fake.kind.values().stream().filter(k -> k.equals("crew")).count();
            assertEquals(n, crewCount);
            long guards = fake.kind.values().stream().filter(k -> !k.equals("crew")).count();
            if (res.caught().isEmpty()) assertEquals(0, guards, "bez wpadek nie ma ochroniarzy");
            else assertTrue(guards >= 1, "ktoś wpadł, więc ktoś go zakuł");
            assertTrue(fake.maxStep <= ShowRunner.RUN + 0.05, "nikt nie teleportuje się skokiem: " + fake.maxStep);
            assertTrue(ticks > 150 && ticks < 5_000, "czas pokazu: " + ticks);
            // dźwięki i efekty
            assertTrue(fake.sounds.getOrDefault(HeistFrames.Cue.DOOR, 0) >= 1 || res.beats().get(0).kind() == Heist.Kind.ALARM);
            if (res.success()) {
                escaped++;
                assertTrue(fake.fx.getOrDefault("smoke", 0) >= res.survivors().size(), "ocaleli znikają w dymie przy vanie");
                assertTrue(fake.sounds.getOrDefault(HeistFrames.Cue.ESCAPE, 0) >= 1);
            }
            if (!res.caught().isEmpty()) {
                caught++;
                assertTrue(fake.fx.getOrDefault("cuff", 0) >= 1);
            }
        }
        assertTrue(escaped > 100 && caught > 100, "uciekli " + escaped + ", wpadki " + caught);
    }

    @Test
    void crewWalksIntoTheBankAndOutAgain() {
        // ekipa bez wpadek: sprawdzamy, że rzeczywiście dochodzi do skarbca i wraca do vana
        Random rng = new Random(1);
        for (int attempt = 0; attempt < 200; attempt++) {
            List<UUID> crew = crew(3);
            Heist.Result res = Heist.run(crew, u -> "x", rng);
            if (!res.caught().isEmpty() || !res.success()) continue;
            List<HeistFrames.Frame> frames = HeistFrames.build(res, crew);
            Fake fake = new Fake();
            ShowRunner runner = new ShowRunner(fake, stage(), crew, List.of("a", "b", "c"), frames, i -> {
            }, () -> {
            });
            double deepest = 0;
            double nearest = 99;
            boolean wasInside = false;
            boolean cameBack = false;
            for (int t = 0; t < 6_000 && !runner.tick(); t++) {
                for (Map.Entry<Integer, P> e : fake.pos.entrySet()) {
                    if (!fake.alive.contains(e.getKey())) continue;
                    deepest = Math.max(deepest, e.getValue().z());
                    if (e.getValue().z() > 10) wasInside = true;
                    if (wasInside) nearest = Math.min(nearest, e.getValue().z());
                    if (wasInside && e.getValue().z() < 4 && e.getValue().x() > 11) cameBack = true;
                }
            }
            assertTrue(deepest > 13, "ekipa doszła do skarbca: " + deepest);
            assertTrue(cameBack, "i wróciła do vana");
            assertTrue(fake.everOpened.contains("vault") && fake.everOpened.contains("bank"), "drzwi banku i skarbca otworzyły się w trakcie");
            return;
        }
        throw new AssertionError("nie trafiono napadu bez wpadek");
    }

    @Test
    void abortRemovesEveryoneAndSkipsDone() {
        List<UUID> crew = crew(4);
        Random rng = new Random(8);
        Heist.Result res = Heist.run(crew, u -> "x", rng);
        List<HeistFrames.Frame> frames = HeistFrames.build(res, crew);
        Fake fake = new Fake();
        int[] done = {0};
        ShowRunner runner = new ShowRunner(fake, stage(), crew, List.of("a", "b", "c", "d"), frames, i -> {
        }, () -> done[0]++);
        for (int t = 0; t < 300; t++) runner.tick();
        assertFalse(fake.alive.isEmpty(), "w trakcie pokazu ktoś stoi na scenie");
        runner.abort();
        assertTrue(runner.done());
        assertTrue(fake.alive.isEmpty());
        assertEquals(0, done[0], "przerwany pokaz nie woła onDone");
        assertFalse(fake.lamps);
        assertEquals(Boolean.FALSE, fake.doors.getOrDefault("bank", false));
    }

    @Test
    void stageSavesAndLoads(@TempDir File dir) throws IOException {
        Stage s = stage();
        s.set("bankdoor", new P(8, 0, 4));
        s.viewYaw(135f);
        File f = new File(dir, "stage.yml");
        s.save(f);
        Stage back = Stage.load(f);
        assertEquals("w", back.world());
        assertTrue(back.complete());
        assertEquals(135f, back.viewYaw());
        assertEquals(s.get("jail"), back.get("jail"));
        assertEquals(new P(8, 0, 4), back.get("bankdoor"));
        // niekompletna scena nie jest gotowa i mówi, czego brakuje
        Stage partial = new Stage();
        partial.world("w");
        partial.set("start", new P(0, 0, 0));
        assertFalse(partial.complete());
        assertTrue(partial.missing().contains("hall"));
        assertNotNull(Stage.load(new File(dir, "nie-ma.yml")));
    }
}
