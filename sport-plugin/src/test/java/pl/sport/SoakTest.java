package pl.sport;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.sport.arena.Arena;
import pl.sport.basket.BasketMatch;
import pl.sport.core.Team;
import pl.sport.core.V3;
import pl.sport.match.Match;
import pl.sport.match.Participant;
import pl.sport.pingpong.PingPongGame;
import pl.sport.pingpong.PingPongMatch;
import pl.sport.pingpong.PingPongTable;
import pl.sport.volley.VolleyMatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Przebiegi całych meczów z „botami”, które biegną do piłki i uderzają z losowymi błędami. Sprawdzamy, że mecz zawsze się kończy,
 * nie zgłasza błędów, nie gubi piłki ani graczy i że wszyscy wracają na swoje miejsce.
 */
class SoakTest extends SportTestBase {
    private final List<String> severe = new ArrayList<>();

    private void watchLog() {
        plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.SEVERE.intValue()) severe.add(r.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
    }

    private List<PlayerMock> addPlayers(int n) {
        List<PlayerMock> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            PlayerMock p = server.addPlayer("Bot" + i);
            p.setGameMode(GameMode.SURVIVAL);
            out.add(p);
        }
        return out;
    }

    private void swing(Player p) {
        server.getPluginManager().callEvent(new PlayerArmSwingEvent(p, EquipmentSlot.HAND));
    }

    // ---------- siatkówka ----------

    private void volleyBotsAct(Arena arena, VolleyMatch m, Random rnd, int[] sinceToss) {
        var game = m.game();
        V3 ball = m.ballPosition();
        if (game == null || ball == null) return;
        double netU = arena.length() / 2.0;
        for (Participant part : m.players().values()) {
            PlayerMock bot = (PlayerMock) part.player();
            boolean low = game.sideOwner(true) == part.team();
            double minU = low ? 0.8 : netU + 0.8;
            double maxU = low ? netU - 0.8 : arena.length() - 0.8;
            boolean isServer = part.id().equals(m.serverId());
            if (isServer) continue; // serwujący stoi tam, gdzie ustawił go mecz
            boolean ballOnMySide = low == (ball.x() < netU);
            // bot biegnie pod piłkę na swojej połowie, a w przeciwnym razie wraca na swoje miejsce
            double u = ballOnMySide ? Math.max(minU, Math.min(maxU, ball.x() + (low ? -0.4 : 0.4))) : (low ? netU - 4.5 : netU + 4.5);
            double v = ballOnMySide ? Math.max(0.5, Math.min(arena.width() - 0.5, ball.z())) : arena.width() / 2.0;
            Location to = arena.toWorld(world, new V3(u, 0, v));
            V3 toNet = new V3(low ? 1 : -1, 0, 0);
            to.setYaw(arena.yawOf(toNet) + (float) (rnd.nextGaussian() * 12));
            to.setPitch((float) (rnd.nextDouble() * 12 - 4));
            bot.teleport(to);
        }
        // zagrywka
        if (m.serverId() != null) {
            PlayerMock server = (PlayerMock) this.server.getPlayer(m.serverId());
            if (!m.tossed()) {
                sinceToss[0] = 0;
                if (rnd.nextInt(10) == 0) swing(server);
            } else {
                sinceToss[0]++;
                if (sinceToss[0] == 8 + rnd.nextInt(6)) swing(server);
            }
        } else if (m.rallyOn()) {
            for (Participant part : m.players().values()) {
                if (rnd.nextInt(100) < 35) swing(part.player());
            }
        }
    }

    @Test
    void volleyballBotsPlayAFullMatchWithoutErrors() {
        watchLog();
        Arena arena = build("siatkowka", "soak", 50, 50, -90);
        List<PlayerMock> bots = addPlayers(4);
        for (int i = 0; i < bots.size(); i++) server.dispatchCommand(bots.get(i), "siatkowka dolacz soak " + (i % 2 == 0 ? "a" : "b"));
        VolleyMatch m = (VolleyMatch) plugin.matches().matchFor(arena);
        m.seed(12345);
        server.dispatchCommand(admin, "siatkowka start natychmiast soak");
        assertEquals(Match.Stage.PLAYING, m.stage());
        Random rnd = new Random(77);
        int[] sinceToss = {0};
        int guard = 20 * 60 * 40; // czterdzieści minut gry to dużo więcej niż potrzeba
        int points = 0;
        int lastPointAt = 0;
        int longestGap = 0;
        int totalPoints = 0;
        for (int t = 0; t < guard && m.stage() == Match.Stage.PLAYING; t++) {
            volleyBotsAct(arena, m, rnd, sinceToss);
            ticks(1);
            if (m.game() != null) {
                int now = m.game().points(Team.A) + m.game().points(Team.B) + 100 * (m.game().sets(Team.A) + m.game().sets(Team.B));
                if (now != points) {
                    longestGap = Math.max(longestGap, t - lastPointAt);
                    lastPointAt = t;
                    totalPoints++;
                    points = now;
                }
            }
        }
        System.out.printf("SIATKOWKA bot-mecz: %d zmian wyniku, najdłuższa akcja %d s%n", totalPoints, longestGap / 20);
        assertTrue(severe.isEmpty(), "błędy w logu: " + severe);
        System.out.printf("SIATKOWKA bot-mecz: sety %d:%d, ostatni set %s, etap %s%n", m.game().sets(Team.A), m.game().sets(Team.B), m.game().points(Team.A) + ":" + m.game().points(Team.B), m.stage());
        assertTrue(m.stage() == Match.Stage.ENDING || m.stage() == Match.Stage.LOBBY, "mecz skończył się w limicie czasu, etap " + m.stage() + ", punkty " + points);
        assertTrue(m.game() == null || m.game().finished(), "gra rozstrzygnięta");
        ticks(20 * 8);
        assertEquals(Match.Stage.LOBBY, m.stage());
        for (PlayerMock p : bots) {
            assertNull(plugin.matches().of(p));
            assertEquals(GameMode.SURVIVAL, p.getGameMode());
        }
        assertTrue(views.stream().allMatch(v -> v.removed), "piłka usunięta");
    }

    // ---------- koszykówka ----------

    private void basketBotsAct(Arena arena, BasketMatch m, Random rnd) {
        var game = m.game();
        V3 ball = m.ballPosition();
        if (game == null || ball == null || game.ended()) return;
        PlayerMock holder = m.holder() == null ? null : (PlayerMock) server.getPlayer(m.holder());
        if (m.inFreeThrows()) {
            // rzuty osobiste: wykonawca rzuca, reszta stoi
            if (game.freeThrowShooter() != null && rnd.nextInt(8) == 0) swing((PlayerMock) server.getPlayer(game.freeThrowShooter()));
            return;
        }
        for (Participant part : new java.util.ArrayList<>(m.players().values())) {
            PlayerMock bot = (PlayerMock) part.player();
            if (bot == null) continue;
            V3 pos = arena.toLocal(bot.getLocation());
            int hoop = game.attackedHoop(part.team());
            V3 rim = m.court().rimCenter(hoop);
            V3 target;
            boolean hasBall = part.id().equals(m.holder());
            if (hasBall) {
                // niesie piłkę pod kosz do ok. 5 m i rzuca
                V3 spot = new V3(rim.x() + (hoop == 0 ? 4.5 : -4.5), 0, rim.z());
                target = spot;
            } else if (holder != null && holder.getUniqueId() != part.id() && m.participant(holder.getUniqueId()).team() != part.team()) {
                target = new V3(arena.toLocal(holder.getLocation()).x() + 0.5, 0, arena.toLocal(holder.getLocation()).z());
            } else {
                target = new V3(ball.x(), 0, ball.z());
            }
            double dx = target.x() - pos.x();
            double dz = target.z() - pos.z();
            double dist = Math.hypot(dx, dz);
            double step = Math.min(dist, 0.35);
            V3 next = dist < 1e-6 ? new V3(pos.x(), 0, pos.z()) : new V3(pos.x() + dx / dist * step, 0, pos.z() + dz / dist * step);
            next = new V3(Math.max(0.3, Math.min(arena.length() - 0.3, next.x())), 0, Math.max(0.3, Math.min(arena.width() - 0.3, next.z())));
            Location to = arena.toWorld(world, next);
            V3 look = hasBall ? new V3(rim.x() - next.x(), 0, rim.z() - next.z()) : new V3(target.x() - next.x(), 0, target.z() - next.z());
            if (look.horizontalLength() < 1e-6) look = new V3(1, 0, 0);
            to.setYaw(arena.yawOf(look) + (float) (rnd.nextGaussian() * 6));
            to.setPitch(hasBall ? -15f : 0f);
            bot.teleport(to);
            double rimDist = Math.hypot(rim.x() - next.x(), rim.z() - next.z());
            if (hasBall && rimDist < 5.2 && rnd.nextInt(6) == 0) swing(bot);
            else if (!hasBall && holder != null && rnd.nextInt(20) == 0) swing(bot);
            if (hasBall && rnd.nextInt(400) == 0) {
                server.getPluginManager().callEvent(new PlayerSwapHandItemsEvent(bot, new ItemStack(org.bukkit.Material.AIR), new ItemStack(org.bukkit.Material.AIR)));
            }
        }
    }

    @Test
    void basketballBotsPlayAFullGameWithoutErrors() {
        watchLog();
        plugin.getConfig().set("koszykowka.punkty-do-wygrania", 12);
        plugin.getConfig().set("koszykowka.celnosc", 1.4);
        plugin.getConfig().set("koszykowka.faule-limit", 0); // boty faulują często: bez wyrzucania z boiska, żeby mecz dobiegł końca
        plugin.getConfig().set("koszykowka.faule-premia", 3);
        Arena arena = build("koszykowka", "soakb", 50, 50, -90);
        List<PlayerMock> bots = addPlayers(4);
        for (int i = 0; i < bots.size(); i++) server.dispatchCommand(bots.get(i), "koszykowka dolacz soakb " + (i % 2 == 0 ? "a" : "b"));
        BasketMatch m = (BasketMatch) plugin.matches().matchFor(arena);
        m.seed(2024);
        server.dispatchCommand(admin, "koszykowka start natychmiast soakb");
        assertEquals(Match.Stage.PLAYING, m.stage());
        Random rnd = new Random(99);
        int guard = 20 * 60 * 40;
        for (int t = 0; t < guard && m.stage() == Match.Stage.PLAYING; t++) {
            basketBotsAct(arena, m, rnd);
            ticks(1);
        }
        assertTrue(severe.isEmpty(), "błędy w logu: " + severe);
        int a = m.game().score(Team.A);
        int b = m.game().score(Team.B);
        System.out.printf("KOSZYKOWKA bot-mecz: wynik %d:%d, faule drużyn %d:%d%n", a, b, m.game().teamFouls(Team.A), m.game().teamFouls(Team.B));
        assertTrue(m.stage() == Match.Stage.ENDING || m.stage() == Match.Stage.LOBBY, "mecz skończył się w limicie czasu: " + a + ":" + b);
        assertTrue(Math.max(a, b) >= 12, "ktoś doszedł do 12 punktów: " + a + ":" + b);
        ticks(20 * 8);
        assertEquals(Match.Stage.LOBBY, m.stage());
        for (PlayerMock p : bots) assertNull(plugin.matches().of(p));
        assertTrue(views.stream().allMatch(v -> v.removed), "piłka usunięta");
    }

    // ---------- ping-pong ----------

    private void placeBot(PlayerMock bot, Arena arena, V3 feet, V3 at, Random rnd) {
        Location l = arena.toWorld(world, feet);
        V3 d = at.minus(feet.plus(new V3(0, 1.62, 0)));
        l.setYaw(arena.yawOf(d.horizontal()) + (float) (rnd.nextGaussian() * 2));
        l.setPitch((float) Math.toDegrees(Math.atan2(-d.y(), d.horizontalLength())));
        bot.teleport(l);
    }

    private void pingpongBotsAct(Arena arena, PingPongMatch m, Random rnd) {
        PingPongGame game = m.game();
        V3 ball = m.ballPosition();
        if (game == null || ball == null || game.finished()) return;
        PingPongTable t = m.table();
        double width = arena.width();
        for (Participant part : new ArrayList<>(m.players().values())) {
            PlayerMock bot = (PlayerMock) part.player();
            if (bot == null) continue;
            boolean low = game.sideOwner(true) == part.team();
            double sign = low ? 1 : -1;
            double endU = low ? t.u0() : t.u1();
            V3 target = new V3(t.netU() + sign * (0.8 + rnd.nextDouble() * 1.0), t.topY(), width / 2.0 + (rnd.nextDouble() - 0.5) * (t.tableWidth() - 0.5));
            V3 eyeBall;
            if (part.id().equals(m.serverId())) {
                // serwujący stoi tam, gdzie postawił go mecz: tylko celuje i uderza
                V3 feet = arena.toLocal(bot.getLocation());
                placeBot(bot, arena, feet, target, rnd);
                if (!m.tossed()) {
                    if (rnd.nextInt(8) == 0) swing(bot);
                } else if (ball.y() >= t.topY() + 0.3 && m.ballVelocity().y() < 0 && rnd.nextInt(2) == 0) {
                    swing(bot);
                }
                continue;
            }
            if (!m.rallyOn()) continue;
            boolean ballMine = low == (ball.x() < t.netU());
            boolean partnerHitLast = game.teamSize(part.team()) > 1 && part.id().equals(game.lastHitterOf(part.team()));
            boolean myTurn = game.stage() == PingPongGame.Stage.GOOD && game.lastHitTeam() != part.team() && !partnerHitLast;
            // bot stoi za końcem stołu na wysokości piłeczki, a gdy to nie jego strona, wraca na środek
            double v = ballMine ? Math.max(1.0, Math.min(width - 1.0, ball.z() + rnd.nextGaussian() * 0.3)) : width / 2.0;
            if (game.teamSize(part.team()) > 1) v = Math.max(1.0, Math.min(width - 1.0, v + (part.id().hashCode() % 2 == 0 ? -0.8 : 0.8)));
            V3 feet = new V3(endU - sign * (0.9 + (ballMine ? rnd.nextDouble() * 0.5 : 0.3)), 0, v);
            placeBot(bot, arena, feet, target, rnd);
            eyeBall = ball.minus(feet.plus(new V3(0, 1.62, 0)));
            boolean reach = eyeBall.length() < 2.3;
            if (ballMine && reach && myTurn && rnd.nextInt(3) != 0) swing(bot);
            else if (ballMine && reach && !myTurn && rnd.nextInt(80) == 0) swing(bot); // zdarza się źle odbić (wolej)
        }
    }

    private void pingpongMatch(int perTeam, long seed, String name) {
        watchLog();
        Arena arena = build("pingpong", name, 50, 50, -90);
        List<PlayerMock> bots = addPlayers(perTeam * 2);
        for (int i = 0; i < bots.size(); i++) server.dispatchCommand(bots.get(i), "pingpong dolacz " + name + " " + (i % 2 == 0 ? "a" : "b"));
        PingPongMatch m = (PingPongMatch) plugin.matches().matchFor(arena);
        m.seed(seed);
        server.dispatchCommand(admin, "pingpong start natychmiast " + name);
        assertEquals(Match.Stage.PLAYING, m.stage());
        Random rnd = new Random(seed + 1);
        int guard = 20 * 60 * 40;
        int t = 0;
        for (; t < guard && m.stage() == Match.Stage.PLAYING; t++) {
            pingpongBotsAct(arena, m, rnd);
            ticks(1);
        }
        assertTrue(severe.isEmpty(), "błędy w logu: " + severe);
        System.out.printf("PING-PONG bot-mecz %dv%d: gemy %d:%d, czas gry %d s, etap %s%n", perTeam, perTeam, m.game().games(Team.A), m.game().games(Team.B), t / 20, m.stage());
        assertTrue(m.stage() == Match.Stage.ENDING || m.stage() == Match.Stage.LOBBY, "mecz skończył się w limicie czasu, etap " + m.stage());
        assertTrue(m.game() == null || m.game().finished(), "gra rozstrzygnięta");
        assertEquals(2, Math.max(m.game().games(Team.A), m.game().games(Team.B)), "ktoś wygrał dwa gemy");
        ticks(20 * 8);
        assertEquals(Match.Stage.LOBBY, m.stage());
        for (PlayerMock p : bots) {
            assertNull(plugin.matches().of(p));
            assertEquals(GameMode.SURVIVAL, p.getGameMode());
        }
        assertTrue(views.stream().allMatch(v -> v.removed), "piłeczka usunięta");
    }

    @Test
    void pingpongBotsPlayAFullSinglesMatchWithoutErrors() {
        plugin.getConfig().set("pingpong.celnosc", 1.0);
        pingpongMatch(1, 31337, "soakp");
    }

    @Test
    void pingpongBotsPlayAFullDoublesMatchWithoutErrors() {
        plugin.getConfig().set("pingpong.celnosc", 1.0);
        pingpongMatch(2, 4242, "soakd");
    }
}
