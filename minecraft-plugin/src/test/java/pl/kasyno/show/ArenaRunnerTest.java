package pl.kasyno.show;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.kasyno.show.ArenaBackend.Hold;
import pl.kasyno.show.ArenaBackend.Pose;
import pl.kasyno.show.ArenaBackend.Sfx;
import pl.kasyno.show.ArenaShow.Caption;
import pl.kasyno.show.ArenaShow.Kind;
import pl.kasyno.show.Stage.P;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pojedynek i jackpot na arenie: ruch, napisy, poza przegranego, fajerwerki i sprzątanie, sprawdzone na atrapie świata. */
class ArenaRunnerTest {
    static final P A = new P(11.5, 0, 15.5);
    static final P B = new P(19.5, 0, 15.5);
    static final P CENTER = new P(15.5, 0, 15.5);

    /** Stage jak z prefabrykatu areny (współrzędne względne, świat „w”). */
    static Stage stage() {
        Stage s = new Stage();
        s.arenaWorld("w");
        s.set(Stage.ARENA_A, A);
        s.set(Stage.ARENA_B, B);
        s.set(Stage.ARENA_CENTER, CENTER);
        s.set(Stage.ARENA_VIEW, new P(15.5, 3, 29.5));
        s.arenaViewYaw(180);
        return s;
    }

    /** Atrapa świata: liczy figury, pamięta pozycje, pozy, obrysy, dźwięki i efekty z numerem ticku. */
    static final class Fake implements ArenaBackend {
        int now;
        int nextId;
        boolean failSpawn;
        final Map<Integer, P> pos = new HashMap<>();
        final Map<Integer, Float> yaw = new HashMap<>();
        final Map<Integer, UUID> skin = new HashMap<>();
        final Map<Integer, String> name = new HashMap<>();
        final Map<Integer, Hold> hold = new HashMap<>();
        final Set<Integer> alive = new HashSet<>();
        final Map<Integer, Pose> pose = new HashMap<>();
        final Set<Integer> everLay = new HashSet<>();
        final Set<Integer> glowing = new HashSet<>();
        final List<Integer> glowOn = new ArrayList<>();
        final Map<Integer, Integer> swings = new HashMap<>();
        final Map<Sfx, List<Float>> sounds = new HashMap<>();
        final Map<String, Integer> fx = new HashMap<>();
        final List<P> fireworks = new ArrayList<>();
        final Map<String, List<P>> fxAt = new HashMap<>();
        double maxStep;
        int spawns;

        @Override
        public Object spawn(UUID s, String n, P at, float y, Hold h) {
            spawns++;
            if (failSpawn) return null;
            int id = nextId++;
            pos.put(id, at);
            yaw.put(id, y);
            skin.put(id, s);
            name.put(id, n);
            hold.put(id, h);
            alive.add(id);
            pose.put(id, Pose.STAND);
            return id;
        }

        @Override
        public void teleport(Object h, P at, float y) {
            if (h == null) return;
            int id = (Integer) h;
            assertTrue(alive.contains(id), "teleport usuniętej figury " + id);
            P old = pos.get(id);
            maxStep = Math.max(maxStep, Math.hypot(at.x() - old.x(), at.z() - old.z()));
            assertTrue(Double.isFinite(at.x()) && Double.isFinite(at.z()) && Double.isFinite(y));
            pos.put(id, at);
            yaw.put(id, y);
        }

        @Override
        public void pose(Object h, Pose p) {
            if (h == null) return;
            int id = (Integer) h;
            assertTrue(alive.contains(id), "poza usuniętej figury " + id);
            pose.put(id, p);
            if (p == Pose.LIE) everLay.add(id);
        }

        @Override
        public void swing(Object h) {
            if (h == null) return;
            swings.merge((Integer) h, 1, Integer::sum);
        }

        @Override
        public void glow(Object h, boolean on) {
            if (h == null) return;
            int id = (Integer) h;
            assertTrue(alive.contains(id), "obrys usuniętej figury " + id);
            if (on) {
                glowing.add(id);
                glowOn.add(id);
            } else glowing.remove(id);
        }

        @Override
        public void remove(Object h) {
            if (h == null) return;
            alive.remove((Integer) h);
            glowing.remove((Integer) h);
        }

        @Override
        public void sound(Sfx s, P at, float pitch) {
            sounds.computeIfAbsent(s, k -> new ArrayList<>()).add(pitch);
        }

        @Override
        public void fx(String kind, P at) {
            fx.merge(kind, 1, Integer::sum);
            fxAt.computeIfAbsent(kind, k -> new ArrayList<>()).add(at);
            if (kind.equals("firework")) fireworks.add(at);
        }

        int idOf(UUID u) {
            for (Map.Entry<Integer, UUID> e : skin.entrySet()) if (e.getValue().equals(u)) return e.getKey();
            throw new AssertionError("brak figury ze skórką " + u);
        }
    }

    /** Napis razem z numerem ticku i zdjęciem pozycji figur w chwili jego wyświetlenia. */
    record Seen(Caption c, int tick, Map<Integer, P> pos, Map<Integer, Float> yaw) {}

    private static int run(ArenaShow show, Fake f) {
        int ticks = 0;
        while (!show.tick()) {
            f.now++;
            ticks++;
            assertTrue(ticks < ArenaShow.MAX_TICKS + 10, "pokaz się nie kończy");
        }
        return ticks;
    }

    private static java.util.function.Consumer<Caption> capture(Fake f, List<Seen> out) {
        return c -> out.add(new Seen(c, f.now, new HashMap<>(f.pos), new HashMap<>(f.yaw)));
    }

    private static List<UUID> crew(int n) {
        List<UUID> c = new ArrayList<>();
        for (int i = 0; i < n; i++) c.add(UUID.randomUUID());
        return c;
    }

    // ---------- pojedynek ----------

    @Test
    void duelPlaysTheWholeStoryForEitherWinner() {
        for (boolean aWins : new boolean[]{true, false}) {
            for (int wait : new int[]{0, 14, 26, 39}) {
                UUID a = UUID.randomUUID();
                UUID b = UUID.randomUUID();
                UUID winner = aWins ? a : b;
                Fake f = new Fake();
                List<Seen> seen = new ArrayList<>();
                int[] done = {0};
                DuelRunner d = new DuelRunner(f, stage(), a, "Ala", b, "Bob", winner, wait, capture(f, seen), () -> done[0]++);
                int ticks = run(d, f);

                // napisy po kolei: stajecie, napięcie, 3-2-1, cisza, strzał, wynik
                List<String> story = seen.stream().map(s -> s.c().kind() + (s.c().kind() == Kind.COUNT ? ":" + s.c().n() : "")).toList();
                assertEquals(List.of("STAND", "TENSION", "COUNT:3", "COUNT:2", "COUNT:1", "WAIT", "FIRE", "RESULT"), story, "wait=" + wait);
                assertEquals(List.of("3", "2", "1"), seen.stream().filter(s -> s.c().kind() == Kind.COUNT).map(s -> s.c().text()).toList());
                Seen result = seen.get(7);
                assertEquals(winner, result.c().who());
                assertEquals(aWins ? "Ala" : "Bob", result.c().text());

                // odliczanie co 18 ticków, cisza dokładnie tyle, ile wyznaczył wynik (minimum jeden tick)
                assertEquals(18, seen.get(3).tick() - seen.get(2).tick());
                assertEquals(18, seen.get(4).tick() - seen.get(3).tick());
                assertEquals(18, seen.get(5).tick() - seen.get(4).tick());
                assertEquals(Math.max(1, wait), seen.get(6).tick() - seen.get(5).tick(), "chwila ciszy");
                assertEquals(18, result.tick() - seen.get(6).tick(), "przegrany pada 18 ticków po strzale");

                // dwie figury ze skórkami obu graczy, z rewolwerami
                assertEquals(2, f.spawns);
                int ia = f.idOf(a);
                int ib = f.idOf(b);
                assertEquals("Ala", f.name.get(ia));
                assertEquals("Bob", f.name.get(ib));
                assertEquals(Hold.GUN, f.hold.get(ia));
                assertEquals(Hold.GUN, f.hold.get(ib));

                // w chwili strzału stoją na swoich stanowiskach, twarzą do siebie
                Seen fire = seen.get(6);
                assertTrue(fire.pos().get(ia).flat(A) < 0.05, "A na stanowisku");
                assertTrue(fire.pos().get(ib).flat(B) < 0.05, "B na stanowisku");
                assertEquals(-90f, fire.yaw().get(ia), 0.5f, "A patrzy na wschód");
                assertEquals(90f, fire.yaw().get(ib), 0.5f, "B patrzy na zachód");

                // przegrany leży, zwycięzca nie; obaj strzelali
                int win = f.idOf(winner);
                int lose = aWins ? ib : ia;
                assertTrue(f.everLay.contains(lose), "przegrany pada");
                assertFalse(f.everLay.contains(win), "zwycięzca zostaje na nogach");
                assertEquals(1, f.swings.get(ia));
                assertEquals(1, f.swings.get(ib));
                assertEquals(2, f.fx.get("shot"));
                assertEquals(1, f.fx.get("hit"));
                assertTrue(f.fxAt.get("hit").get(0).flat(lose == ia ? A : B) < 0.05, "trafienie w przegranego");

                // dźwięki: odliczanie rośnie, jeden strzał, jedno upadnięcie
                List<Float> count = f.sounds.get(Sfx.COUNT);
                assertEquals(3, count.size());
                assertTrue(count.get(0) < count.get(1) && count.get(1) < count.get(2), "ton rośnie: " + count);
                assertEquals(1, f.sounds.get(Sfx.SHOT).size());
                assertEquals(1, f.sounds.get(Sfx.FALL).size());

                // koniec: nic nie zostaje, zakończenie raz, nikt nie skacze
                assertEquals(1, done[0], "onDone dokładnie raz");
                assertTrue(d.done());
                assertTrue(f.alive.isEmpty(), "po pokazie nie zostaje żadna figura");
                assertEquals(2, f.fx.get("smoke"), "oboje znikają w dymie");
                assertTrue(f.maxStep <= ArenaShow.WALK + 1e-9, "nikt nie teleportuje się skokiem: " + f.maxStep);
                assertTrue(ticks > 250 && ticks < 500, "czas pokazu: " + ticks);
                assertTrue(d.tick(), "po końcu tick nic nie robi");
                assertEquals(1, done[0]);
            }
        }
    }

    @Test
    void duelFiguresWalkInFromOutsideTheirMarks() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Fake f = new Fake();
        List<Seen> seen = new ArrayList<>();
        DuelRunner d = new DuelRunner(f, stage(), a, "Ala", b, "Bob", a, 20, capture(f, seen), () -> {
        });
        double startA = -1;
        double startB = -1;
        while (!d.tick()) {
            f.now++;
            if (f.spawns == 2 && startA < 0) {
                startA = f.pos.get(f.idOf(a)).flat(CENTER);
                startB = f.pos.get(f.idOf(b)).flat(CENTER);
                assertTrue(f.pos.get(f.idOf(a)).x() < A.x(), "A wchodzi od zachodu");
                assertTrue(f.pos.get(f.idOf(b)).x() > B.x(), "B wchodzi od wschodu");
            }
        }
        assertEquals(10, startA, 0.01, "wchodzą 6 bloków za stanowiskiem");
        assertEquals(10, startB, 0.01);
    }

    @Test
    void duelAbortRemovesEveryoneAndSkipsDone() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Fake f = new Fake();
        int[] done = {0};
        DuelRunner d = new DuelRunner(f, stage(), a, "Ala", b, "Bob", b, 20, c -> {
        }, () -> done[0]++);
        for (int t = 0; t < 150; t++) d.tick();
        assertEquals(2, f.alive.size(), "w trakcie pokazu obaj stoją na arenie");
        d.abort();
        assertTrue(d.done());
        assertTrue(f.alive.isEmpty());
        assertEquals(0, done[0], "przerwany pokaz nie woła onDone");
        assertTrue(d.tick());
        d.abort();
        assertEquals(0, done[0]);
    }

    @Test
    void duelSurvivesFiguresThatCouldNotBeSpawned() {
        Fake f = new Fake();
        f.failSpawn = true;
        int[] done = {0};
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<Seen> seen = new ArrayList<>();
        DuelRunner d = new DuelRunner(f, stage(), a, "Ala", b, "Bob", a, 30, capture(f, seen), () -> done[0]++);
        run(d, f);
        assertEquals(1, done[0]);
        assertEquals(8, seen.size(), "napisy idą nawet bez figur");
    }

    // ---------- jackpot ----------

    @Test
    void jackpotCirclesThePlayersAndSlowsDownOnTheWinner() {
        for (int n : new int[]{2, 3, 5, 8, 13, 20}) {
            for (int winner : new int[]{0, n / 2, n - 1}) {
                List<UUID> crew = crew(n);
                List<String> names = new ArrayList<>();
                for (int i = 0; i < n; i++) names.add("g" + i);
                int[] spin = JackpotRunner.spinFrames(n, winner);
                Fake f = new Fake();
                List<Seen> seen = new ArrayList<>();
                int[] done = {0};
                List<Integer> frames = new ArrayList<>();
                int[] maxGlow = {0};
                JackpotRunner j = new JackpotRunner(f, stage(), crew, names, spin, capture(f, seen), frames::add, () -> done[0]++);
                int ticks = 0;
                while (!j.tick()) {
                    f.now++;
                    ticks++;
                    maxGlow[0] = Math.max(maxGlow[0], f.glowing.size());
                    assertTrue(ticks < ArenaShow.MAX_TICKS + 10);
                }
                String ctx = "n=" + n + " zwycięzca=" + winner;

                // figury w komplecie, ze skórkami uczestników w kolejności lobby, ze złotem w ręku
                assertEquals(n, f.spawns, ctx);
                for (int i = 0; i < n; i++) {
                    assertEquals(crew.get(i), f.skin.get(i), ctx);
                    assertEquals(names.get(i), f.name.get(i), ctx);
                    assertEquals(Hold.GOLD, f.hold.get(i), ctx);
                }

                // losowanie: 30 napisów w kolejności klatek, podświetlona jest zawsze najwyżej jedna figura
                List<Seen> spins = seen.stream().filter(s -> s.c().kind() == Kind.SPIN).toList();
                assertEquals(JackpotRunner.FRAMES, spins.size(), ctx);
                for (int k = 0; k < spins.size(); k++) {
                    assertEquals(k, spins.get(k).c().n(), ctx);
                    assertEquals(names.get(spin[k]), spins.get(k).c().text(), ctx);
                    assertEquals(crew.get(spin[k]), spins.get(k).c().who(), ctx);
                }
                assertEquals(java.util.stream.IntStream.range(0, spin.length).boxed().toList(), frames, "onFrame po kolei " + ctx);
                assertEquals(java.util.Arrays.stream(spin).boxed().toList(), f.glowOn, "obrys przechodzi po figurach jak klatki " + ctx);
                assertEquals(1, maxGlow[0], ctx);

                // na początku losowania wszyscy stoją równo na okręgu i patrzą do środka
                double r = JackpotRunner.radius(n);
                Seen first = spins.get(0);
                for (int i = 0; i < n; i++) {
                    P p = first.pos().get(i);
                    assertEquals(r, p.flat(CENTER), 0.05, "promień " + ctx);
                    double ang = 2 * Math.PI * i / n;
                    assertEquals(CENTER.x() + Math.sin(ang) * r, p.x(), 0.05, ctx);
                    assertEquals(CENTER.z() + Math.cos(ang) * r, p.z(), 0.05, ctx);
                    float expected = (float) Math.toDegrees(Math.atan2(-(CENTER.x() - p.x()), CENTER.z() - p.z()));
                    float diff = Math.abs(((first.yaw().get(i) - expected + 540) % 360) - 180);
                    assertTrue(diff < 0.5, "figura " + i + " patrzy do środka " + ctx);
                }
                if (n >= 3) {
                    P p0 = first.pos().get(0);
                    P p1 = first.pos().get(1);
                    assertTrue(p0.flat(p1) >= JackpotRunner.SPACING - 0.01, "figury się nie zlewają: " + p0.flat(p1) + " " + ctx);
                }

                // hamowanie: odstępy między klatkami nie maleją, od 2 do 9 ticków
                List<Integer> gaps = new ArrayList<>();
                for (int k = 1; k < spins.size(); k++) gaps.add(spins.get(k).tick() - spins.get(k - 1).tick());
                for (int k = 1; k < gaps.size(); k++) assertTrue(gaps.get(k) >= gaps.get(k - 1), "odstępy rosną " + gaps + " " + ctx);
                assertEquals(JackpotRunner.delayAfter(0, spin.length), gaps.get(0), ctx);
                assertEquals(2, gaps.get(0), ctx);
                assertEquals(9, gaps.get(gaps.size() - 1), ctx);

                // zwycięzca: ostatni obrys, napis, fanfary i fajerwerki nad jego głową
                assertEquals(winner, f.glowOn.get(f.glowOn.size() - 1), ctx);
                Seen win = seen.stream().filter(s -> s.c().kind() == Kind.WINNER).findFirst().orElseThrow();
                assertEquals(crew.get(winner), win.c().who(), ctx);
                assertEquals(names.get(winner), win.c().text(), ctx);
                assertEquals(1, seen.stream().filter(s -> s.c().kind() == Kind.WINNER).count(), ctx);
                assertEquals(1, f.sounds.get(Sfx.FANFARE).size(), ctx);
                assertEquals(JackpotRunner.FRAMES, f.sounds.get(Sfx.TICK).size(), ctx);
                for (float pitch : f.sounds.get(Sfx.TICK)) assertTrue(pitch >= 0.6f && pitch <= 1.7f, ctx);
                assertEquals(3, f.fireworks.size(), "trzy salwy " + ctx);
                P wp = win.pos().get(winner);
                for (P fw : f.fireworks) {
                    assertTrue(fw.flat(wp) <= 1.6, "fajerwerk przy zwycięzcy " + ctx);
                    assertTrue(fw.y() >= wp.y() + 2, ctx);
                }

                // koniec: wszyscy znikają w dymie, zwycięzca ostatni, nic nie zostaje
                assertEquals(1, done[0], ctx);
                assertTrue(j.done());
                assertTrue(f.alive.isEmpty(), ctx);
                assertEquals(n, f.fx.get("smoke"), ctx);
                assertTrue(f.maxStep <= ArenaShow.WALK + 1e-9, "nikt nie skacze " + ctx);
                assertTrue(ticks > 250 && ticks < 700, "czas pokazu: " + ticks + " " + ctx);
            }
        }
    }

    @Test
    void jackpotFiguresStayInsideTheArenaFloor() {
        int n = 20;
        List<UUID> crew = crew(n);
        List<String> names = crew.stream().map(u -> "g").toList();
        Fake f = new Fake();
        JackpotRunner j = new JackpotRunner(f, stage(), crew, names, JackpotRunner.spinFrames(n, 7), c -> {
        }, k -> {
        }, () -> {
        });
        double far = 0;
        while (!j.tick()) {
            f.now++;
            for (int id : f.alive) far = Math.max(far, f.pos.get(id).flat(CENTER));
        }
        assertTrue(far <= JackpotRunner.radius(n) + 5.01, "najdalej od środka: " + far);
        assertTrue(far < 10.5, "w obrębie podłogi arenaPrefab: " + far);
    }

    @Test
    void jackpotAbortRemovesEveryoneAndSkipsDone() {
        List<UUID> crew = crew(6);
        List<String> names = crew.stream().map(u -> "g").toList();
        Fake f = new Fake();
        int[] done = {0};
        JackpotRunner j = new JackpotRunner(f, stage(), crew, names, JackpotRunner.spinFrames(6, 2), c -> {
        }, k -> {
        }, () -> done[0]++);
        for (int t = 0; t < 140; t++) {
            f.now++;
            j.tick();
        }
        assertEquals(6, f.alive.size());
        assertEquals(1, f.glowing.size(), "w trakcie losowania świeci jedna figura");
        j.abort();
        assertTrue(j.done());
        assertTrue(f.alive.isEmpty());
        assertTrue(f.glowing.isEmpty());
        assertEquals(0, done[0]);
    }

    @Test
    void jackpotSurvivesFiguresThatCouldNotBeSpawned() {
        Fake f = new Fake();
        f.failSpawn = true;
        List<UUID> crew = crew(4);
        List<String> names = crew.stream().map(u -> "g").toList();
        int[] done = {0};
        List<Seen> seen = new ArrayList<>();
        JackpotRunner j = new JackpotRunner(f, stage(), crew, names, JackpotRunner.spinFrames(4, 3), capture(f, seen), k -> {
        }, () -> done[0]++);
        run(j, f);
        assertEquals(1, done[0]);
        assertEquals(JackpotRunner.FRAMES, seen.stream().filter(s -> s.c().kind() == Kind.SPIN).count());
    }

    @Test
    void spinFramesEndOnTheWinnerAndGoAroundTheCircle() {
        for (int n = 2; n <= 20; n++) {
            for (int w = 0; w < n; w++) {
                int[] s = JackpotRunner.spinFrames(n, w);
                assertEquals(JackpotRunner.FRAMES, s.length);
                assertEquals(w, s[s.length - 1]);
                for (int k = 1; k < s.length; k++) assertEquals((s[k - 1] + 1) % n, s[k]);
            }
        }
        assertEquals(JackpotRunner.MIN_RADIUS, JackpotRunner.radius(2));
        assertTrue(JackpotRunner.radius(20) * 2 * Math.PI / 20 >= JackpotRunner.SPACING - 1e-9, "20 osób mieści się na okręgu");
        assertTrue(JackpotRunner.radius(200) > JackpotRunner.radius(20), "większa ekipa dostaje większy okrąg");
    }

    // ---------- scena ----------

    @Test
    void arenaStageSavesLoadsAndStaysApartFromTheBank(@TempDir File dir) throws IOException {
        Stage s = ShowRunnerTest.stage();
        assertTrue(s.complete());
        assertFalse(s.arenaComplete());
        assertEquals(Stage.ARENA_REQUIRED, s.arenaMissing());

        Stage arena = stage();
        s.replaceArena(arena);
        assertTrue(s.arenaComplete());
        assertTrue(s.complete(), "bank został");
        assertEquals("w", s.arenaWorld());
        assertEquals(4, s.arenaPoints().size());
        assertTrue(s.bankPoints().keySet().stream().noneMatch(Stage::isArenaPoint), "w grupie bank nie ma punktów areny");
        assertTrue(s.missing().isEmpty(), "punkty areny nie liczą się do banku");

        File f = new File(dir, "stage.yml");
        s.save(f);
        Stage back = Stage.load(f);
        assertEquals("w", back.arenaWorld());
        assertEquals(180f, back.arenaViewYaw());
        assertEquals(A, back.get(Stage.ARENA_A));
        assertEquals(B, back.get(Stage.ARENA_B));
        assertEquals(CENTER, back.get(Stage.ARENA_CENTER));
        assertTrue(back.arenaComplete());
        assertTrue(back.complete());

        // podmiana banku nie rusza areny, a usunięcie areny nie rusza banku
        Stage newBank = ShowRunnerTest.stage();
        newBank.set("hall", new P(1, 2, 3));
        back.replaceBank(newBank);
        assertEquals(new P(1, 2, 3), back.get("hall"));
        assertTrue(back.arenaComplete());
        back.clearArena();
        assertFalse(back.arenaComplete());
        assertNull(back.arenaWorld());
        assertTrue(back.complete());
        assertEquals("w", back.world());
        back.clearBank();
        assertFalse(back.complete());
        assertNull(back.world());

        // arena i bank mogą stać w różnych światach
        Stage two = ShowRunnerTest.stage();
        Stage other = stage();
        other.arenaWorld("inny");
        two.replaceArena(other);
        assertEquals("w", two.world());
        assertEquals("inny", two.arenaWorld());

        assertNotNull(Stage.load(new File(dir, "nie-ma.yml")));
    }

    @Test
    void arenaPointNamesAcceptShortcuts() {
        assertEquals("arena-a", Stage.arenaPointName("a"));
        assertEquals("arena-b", Stage.arenaPointName("B"));
        assertEquals("arena-center", Stage.arenaPointName("arena-center"));
        assertEquals("arena-view", Stage.arenaPointName("VIEW"));
        assertNull(Stage.arenaPointName("hall"));
        assertNull(Stage.arenaPointName(""));
    }
}
