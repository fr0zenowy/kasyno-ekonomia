package pl.sport.listener;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import pl.sport.SportPlugin;
import pl.sport.match.Match;
import pl.sport.match.Participant;
import pl.sport.ui.Msg;

/** Zdarzenia serwera istotne dla meczów: kliknięcia, wyjścia, ochrona graczy i boiska. */
public final class SportListener implements Listener {
    /** Dopuszczalna odległość od boiska (bloki), po której gracz jest cofany. */
    private static final double LEASH = 8;
    private static final double HEIGHT = 40;

    private final SportPlugin plugin;

    public SportListener(SportPlugin plugin) {
        this.plugin = plugin;
    }

    private Match match(Player p) {
        return plugin.matches().of(p);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onSwing(PlayerArmSwingEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Match m = match(e.getPlayer());
        if (m != null) m.onSwing(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Match m = match(e.getPlayer());
        if (m == null) return;
        if (m.onSwap(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Match m = match(e.getPlayer());
        if (m != null) m.leave(e.getPlayer().getUniqueId(), false);
    }

    /** Po awarii serwera gracz, który był w meczu, wraca na swoje miejsce i do swojego trybu gry. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!plugin.snapshots().has(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && match(p) == null && plugin.snapshots().restoreAfterCrash(p)) Msg.send(p, "Mecz został przerwany, wróciłeś tam, gdzie byłeś.");
        }, 10L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && match(p) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && match(p) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (plugin.sportConfig().protectBlocks() && match(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.sportConfig().protectBlocks() && match(e.getPlayer()) != null) e.setCancelled(true);
    }

    /** Teleport poza boisko (komenda, inny plugin) oznacza wyjście z meczu. Nasze własne teleporty są pomijane. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Match m = match(p);
        if (m == null) return;
        Participant part = m.participant(p.getUniqueId());
        if (part == null) return;
        Location to = e.getTo();
        if (part.isOurTeleport(to)) return;
        if (to != null && m.arena().contains(to, LEASH + 6, HEIGHT)) return;
        m.leave(p.getUniqueId(), false);
    }

    /** Zostawia gracza tam, gdzie był, ale pozwala mu dalej obracać głowę (inaczej widok „szarpałby”). */
    private static Location stay(Location from, Location to) {
        Location l = from.clone();
        l.setYaw(to.getYaw());
        l.setPitch(to.getPitch());
        return l;
    }

    /** Gracz nie wychodzi z boiska pieszo: poza marginesem jest cofany. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null) return;
        Location from = e.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ() && from.getBlockY() == to.getBlockY()) return;
        Match m = match(e.getPlayer());
        if (m == null) return;
        Participant part = m.participant(e.getPlayer().getUniqueId());
        if (!m.arena().contains(to, LEASH, HEIGHT)) {
            e.setTo(stay(from, to));
            Msg.actionBar(e.getPlayer(), "<red>To koniec boiska. <gray>Wyjście z meczu: /sport opusc");
        } else if (part != null && !m.allowMove(part, from, to)) {
            e.setTo(stay(from, to));
            Msg.actionBar(e.getPlayer(), m.moveBlockedMessage());
        }
    }
}
