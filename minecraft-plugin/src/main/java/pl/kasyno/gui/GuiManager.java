package pl.kasyno.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import pl.kasyno.KasynoPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Nasłuch zdarzeń okien: kliknięcia i przeciąganie w GUI są zawsze anulowane, a zamknięcie rozlicza trwające gry. */
public final class GuiManager implements Listener {
    /** Minimalny odstęp między kliknięciami jednego gracza (ochrona przed podwójnymi zdarzeniami). Testy ustawiają 0. */
    public static volatile long clickGapMs = 90;

    private final KasynoPlugin plugin;
    private final Map<UUID, Gui> open = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public GuiManager(KasynoPlugin plugin) {
        this.plugin = plugin;
    }

    void track(Gui gui) {
        open.put(gui.player().getUniqueId(), gui);
    }

    void untrack(Gui gui) {
        open.remove(gui.player().getUniqueId(), gui);
    }

    public Gui current(Player p) {
        return open.get(p.getUniqueId());
    }

    /** Zamyka wszystkie okna (wyłączanie pluginu): gry w toku się rozliczają. */
    public void closeAll() {
        for (Gui g : new ArrayList<>(open.values())) {
            g.handleClose();
            Player p = g.player();
            if (p.isOnline() && p.getOpenInventory().getTopInventory() != null && p.getOpenInventory().getTopInventory().getHolder() == g) p.closeInventory();
        }
        open.clear();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Gui gui)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        long now = System.currentTimeMillis();
        Long last = lastClick.put(p.getUniqueId(), now);
        if (last != null && now - last < clickGapMs) return;
        gui.handleClick(e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Gui gui) {
            // Przy przejściu do innego okna Bukkit zamyka stare, więc rozliczamy je od razu.
            gui.handleClose();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Gui g = open.get(e.getPlayer().getUniqueId());
        if (g != null) g.handleClose();
        lastClick.remove(e.getPlayer().getUniqueId());
    }

    /** Odpala zadanie w następnym ticku (np. otwarcie nowego okna po kliknięciu, żeby nie zamykać okna w jego własnym zdarzeniu). */
    public void next(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }
}
