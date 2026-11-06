package pl.sport.match;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

/** Stan gracza sprzed meczu: gdzie stał, jaki miał tryb gry i tablicę wyników. Po meczu wszystko wraca na swoje miejsce. */
public record PlayerSnapshot(Location location, GameMode gameMode, boolean allowFlight, boolean flying, Scoreboard scoreboard) {
    public static PlayerSnapshot of(Player p) {
        return new PlayerSnapshot(p.getLocation().clone(), p.getGameMode(), p.getAllowFlight(), p.isFlying(), p.getScoreboard());
    }

    public void restore(Player p, boolean teleport) {
        if (teleport && location != null && location.getWorld() != null) p.teleport(location);
        p.setGameMode(gameMode);
        p.setAllowFlight(allowFlight);
        if (allowFlight) p.setFlying(flying);
        if (scoreboard != null) p.setScoreboard(scoreboard);
    }
}
