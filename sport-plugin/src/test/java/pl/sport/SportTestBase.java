package pl.sport;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.sport.arena.Arena;
import pl.sport.match.FakeBallView;

import java.util.ArrayList;
import java.util.List;

/** Wspólna konfiguracja testów na atrapie serwera: plugin z widokiem piłki bez encji, świat, dwóch graczy i operator. */
abstract class SportTestBase {
    ServerMock server;
    SportPlugin plugin;
    World world;
    PlayerMock admin;
    PlayerMock a;
    PlayerMock b;
    final List<FakeBallView> views = new ArrayList<>();

    @BeforeEach
    void setUpServer() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(SportPlugin.class);
        world = server.addSimpleWorld("world");
        plugin.setBallFactory((where, kind, radius) -> {
            FakeBallView v = new FakeBallView(where);
            views.add(v);
            return v;
        });
        admin = server.addPlayer("Admin");
        admin.setOp(true);
        a = server.addPlayer("Ala");
        b = server.addPlayer("Bartek");
        for (PlayerMock p : new PlayerMock[]{admin, a, b}) p.setGameMode(GameMode.SURVIVAL);
    }

    @AfterEach
    void tearDownServer() {
        MockBukkit.unmock();
    }

    /** Stawia boisko komendą administratora w zadanym miejscu, patrząc w zadanym kierunku (yaw). */
    Arena build(String sport, String name, double x, double z, float yaw) {
        admin.teleport(new Location(world, x, 64, z, yaw, 0));
        server.dispatchCommand(admin, sport + " stworz " + name);
        Arena arena = plugin.arenas().get(name);
        if (arena == null) throw new AssertionError("boisko " + name + " nie powstało");
        return arena;
    }

    void ticks(int n) {
        server.getScheduler().performTicks(n);
    }

    FakeBallView lastView() {
        return views.get(views.size() - 1);
    }
}
