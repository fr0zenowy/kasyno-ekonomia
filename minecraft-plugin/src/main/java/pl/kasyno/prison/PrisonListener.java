package pl.kasyno.prison;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import pl.kasyno.gui.T;

/** Pilnuje więźniów: wejście na serwer odsyła do celi, komendy poza zadaniami są blokowane, w celi nie da się zginąć ani uciec perłą. */
public final class PrisonListener implements Listener {
    private final PrisonService prison;

    public PrisonListener(PrisonService prison) {
        this.prison = prison;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        prison.onJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prison.onQuit(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!prison.restricted(p.getUniqueId()) || p.hasPermission(PrisonService.BYPASS)) return;
        if (PrisonRules.commandAllowed(e.getMessage(), prison.allowedCommands())) return;
        e.setCancelled(true);
        T.send(p, "<red>Siedzisz w więzieniu. Tu działa tylko <white>/kasyno wiezienie</white> (lista zadań).");
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!prison.restricted(p.getUniqueId())) return;
        var st = prison.status(p.getUniqueId());
        Location cell = st == null ? null : prison.cellLocation(st.cell(), 0, 0);
        if (cell != null) e.setRespawnLocation(cell);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && prison.restricted(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerTeleportEvent.TeleportCause c = e.getCause();
        if ((c == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || c == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) && prison.restricted(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
}
