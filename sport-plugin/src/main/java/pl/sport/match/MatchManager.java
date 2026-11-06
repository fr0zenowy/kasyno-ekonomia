package pl.sport.match;

import org.bukkit.entity.Player;
import pl.sport.SportPlugin;
import pl.sport.arena.Arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Wszystkie mecze: po jednym na boisko oraz przypisanie graczy do meczów. Co tick posuwa każdy mecz o 1/20 sekundy. */
public final class MatchManager {
    public static final double TICK_SECONDS = 0.05;

    private final SportPlugin plugin;
    private final Map<String, Match> byArena = new HashMap<>();
    private final Map<UUID, Match> byPlayer = new HashMap<>();

    public MatchManager(SportPlugin plugin) {
        this.plugin = plugin;
    }

    private static String key(Arena a) {
        return a.name().toLowerCase(Locale.ROOT);
    }

    /** Mecz na boisku; tworzy go przy pierwszym użyciu. */
    public Match matchFor(Arena a) {
        Match old = byArena.get(key(a));
        if (old != null && old.worldMissing() && old.players().isEmpty()) byArena.remove(key(a));
        return byArena.computeIfAbsent(key(a), k -> switch (a.sport()) {
            case VOLLEYBALL -> new pl.sport.volley.VolleyMatch(plugin, a);
            case BASKETBALL -> new pl.sport.basket.BasketMatch(plugin, a);
            case PINGPONG -> new pl.sport.pingpong.PingPongMatch(plugin, a);
        });
    }

    public Match existing(Arena a) {
        return byArena.get(key(a));
    }

    public Match of(Player p) {
        return byPlayer.get(p.getUniqueId());
    }

    public Match of(UUID id) {
        return byPlayer.get(id);
    }

    void bind(UUID id, Match m) {
        byPlayer.put(id, m);
    }

    void unbind(UUID id) {
        byPlayer.remove(id);
    }

    public List<Match> all() {
        return new ArrayList<>(byArena.values());
    }

    /** Zamyka mecz boiska (np. przy usuwaniu boiska) i zapomina o nim. */
    public void drop(Arena a) {
        Match m = byArena.remove(key(a));
        if (m != null) m.reset();
    }

    /** Posuwa wszystkie mecze. Błąd w jednym nie zatrzymuje pozostałych: taki mecz jest zamykany, a gracze wracają. */
    public void tickAll() {
        for (Match m : new ArrayList<>(byArena.values())) {
            if (m.players().isEmpty() && m.stage() == Match.Stage.LOBBY) continue;
            try {
                m.tick(TICK_SECONDS);
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Błąd w meczu na boisku " + m.arena().name() + ", mecz został zamknięty: " + e);
                e.printStackTrace();
                try {
                    m.stop("Mecz przerwany przez błąd. Przepraszamy!");
                } catch (RuntimeException again) {
                    plugin.getLogger().severe("Nie udało się posprzątać po błędzie: " + again);
                }
            }
        }
    }

    public void shutdown() {
        for (Match m : new ArrayList<>(byArena.values())) m.reset();
        byArena.clear();
        byPlayer.clear();
    }
}
