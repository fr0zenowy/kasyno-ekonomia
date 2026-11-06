package pl.sport.ui;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Tablica wyników i pasek czasu meczu. Linie tablicy to prefiksy drużyn z unikalnymi, niewidocznymi wpisami (dzięki temu mogą mieć
 * dowolny tekst i kolory). Gdy atrapa serwera albo wersja nie obsługuje któregoś elementu, mecz działa dalej bez niego.
 */
public final class Hud {
    private static final String[] INVISIBLE = {"§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9", "§a", "§b", "§c", "§d", "§e"};

    private final Logger log;
    private Scoreboard board;
    private Objective objective;
    private final List<Team> lines = new ArrayList<>();
    private final BossBar bar;
    private final Set<UUID> viewers = new HashSet<>();
    private boolean broken;

    public Hud(Logger log, String barTitle) {
        this.log = log;
        this.bar = BossBar.bossBar(Msg.c(barTitle), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        try {
            this.board = Bukkit.getScoreboardManager().getNewScoreboard();
            this.objective = board.registerNewObjective("sport", Criteria.DUMMY, Msg.c("<gold>Sport"));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            try {
                objective.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
            } catch (RuntimeException | LinkageError ignored) {
                // starsze wersje pokażą numery linii obok tekstu
            }
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private void fail(RuntimeException e) {
        if (!broken) log.warning("Tablica wyników jest niedostępna, mecz działa bez niej: " + e);
        broken = true;
    }

    public void show(Player p) {
        viewers.add(p.getUniqueId());
        if (!broken) {
            try {
                p.setScoreboard(board);
            } catch (RuntimeException e) {
                fail(e);
            }
        }
        p.showBossBar(bar);
    }

    public void hide(Player p) {
        viewers.remove(p.getUniqueId());
        p.hideBossBar(bar);
    }

    public void hideAll() {
        for (UUID id : new ArrayList<>(viewers)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) hide(p);
        }
        viewers.clear();
    }

    /** Ustawia pasek: tekst, postęp 0-1 i kolor. */
    public void bar(String title, double progress, BossBar.Color color) {
        bar.name(Msg.c(title));
        bar.progress((float) Math.max(0, Math.min(1, progress)));
        bar.color(color);
    }

    /** Rysuje tablicę: tytuł i linie od góry do dołu (maksymalnie 15). */
    public void render(String title, List<String> text) {
        if (broken) return;
        try {
            objective.displayName(Msg.c(title));
            int n = Math.min(text.size(), INVISIBLE.length);
            while (lines.size() < n) {
                int i = lines.size();
                Team t = board.registerNewTeam("l" + i);
                t.addEntry(INVISIBLE[i]);
                objective.getScore(INVISIBLE[i]).setScore(INVISIBLE.length - i);
                lines.add(t);
            }
            for (int i = 0; i < lines.size(); i++) {
                Team t = lines.get(i);
                if (i < n) {
                    t.prefix(Msg.c(text.get(i)));
                    objective.getScore(INVISIBLE[i]).setScore(INVISIBLE.length - i);
                } else {
                    board.resetScores(INVISIBLE[i]);
                }
            }
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    public Component currentBarTitle() {
        return bar.name();
    }
}
