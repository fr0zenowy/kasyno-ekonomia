package pl.kasyno.work;

import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Kliknięcia w cele pracy (prawy przycisk: interakcja, lewy: uderzenie w pole Interaction) i wyjście gracza z serwera. */
public final class WorkListener implements Listener {
    private final WorkService work;

    public WorkListener(WorkService work) {
        this.work = work;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction) || !work.isNode(e.getRightClicked().getUniqueId())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        work.click(e.getPlayer(), e.getRightClicked().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Interaction) || !work.isNode(e.getEntity().getUniqueId())) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) work.click(p, e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        work.quit(e.getPlayer());
    }
}
