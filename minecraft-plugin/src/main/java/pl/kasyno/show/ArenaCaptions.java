package pl.kasyno.show;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.T;
import pl.kasyno.show.ArenaShow.Caption;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Napisy pokazów areny (odliczanie 3-2-1, STRZAŁ, kto jest podświetlony, zwycięzca) na ekranach graczy: dostają je uczestnicy
 * (nawet gdy stoją daleko od areny, wtedy z dodatkowym dźwiękiem) i wszyscy, którzy stoją w pobliżu areny jako widzowie.
 */
public final class ArenaCaptions {
    private ArenaCaptions() {}

    /** Odbiorca napisów: `participants` to gracze biorący udział w grze (reszta dostaje je tylko w pobliżu areny). */
    public static Consumer<Caption> to(ShowService show, Collection<UUID> participants) {
        return c -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean mine = participants.contains(p.getUniqueId());
                boolean near = show.nearArena(p);
                if (!mine && !near) continue;
                try {
                    render(p, c, mine, mine && !near);
                } catch (RuntimeException ignored) {
                    // napisy są tylko dodatkiem do sceny
                }
            }
        };
    }

    /** `mine`: gracz bierze udział w grze; `direct`: bierze udział, ale stoi poza zasięgiem dźwięków areny, więc sygnał słyszy bezpośrednio. */
    static void render(Player p, Caption c, boolean mine, boolean direct) {
        String t = T.esc(c.text());
        boolean winner = c.who() != null && c.who().equals(p.getUniqueId());
        switch (c.kind()) {
            case INTRO, STAND -> Fx.actionBar(p, "<yellow>" + t);
            case TENSION -> Fx.actionBar(p, "<gray>" + t);
            case COUNT -> {
                Fx.title(p, "<gold><bold>" + t, "<gray>Czekaj na sygnał!");
                Fx.actionBar(p, "<gold><bold>" + t + "...");
                if (direct) Fx.ding(p, 0.7f + 0.2f * (3 - c.n()));
            }
            case WAIT -> Fx.actionBar(p, "<dark_gray>...");
            case FIRE -> {
                Fx.title(p, "<red><bold>STRZAŁ!", "<gray>Padły dwa strzały naraz.");
                Fx.actionBar(p, "<red><bold>STRZAŁ!");
                if (direct) {
                    Fx.sound(p, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 0.7f);
                    Fx.sound(p, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.6f);
                }
            }
            case RESULT -> {
                if (winner) Fx.actionBar(p, "<green><bold>Ty zostałeś na nogach!");
                else if (mine) {
                    Fx.actionBar(p, "<red><bold>Padasz na piasek...");
                    if (direct) Fx.sound(p, Sound.ENTITY_PLAYER_DEATH, 0.8f, 1f);
                } else Fx.actionBar(p, "<gold><bold>Zwycięzca: " + t);
            }
            case SPIN -> {
                Fx.actionBar(p, "<gold>▶ <white><bold>" + t + "</bold> <gold>◀");
                if (direct) Fx.tick(p, JackpotRunner.pitch(c.n(), JackpotRunner.FRAMES));
            }
            case WINNER -> {
                Fx.title(p, winner ? "<green><bold>WYGRYWASZ!" : "<gold><bold>JACKPOT", winner ? "<gray>Cała pula jest Twoja" : "<gray>" + t + " zgarnia pulę!");
                Fx.actionBar(p, "<gold><bold>" + t + " zgarnia pulę!");
                if (direct) Fx.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
        }
    }
}
