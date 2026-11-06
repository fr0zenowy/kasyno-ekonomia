package pl.kasyno.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import pl.kasyno.KasynoPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Pytanie na czacie: okno się zamyka, gracz wpisuje odpowiedź (np. własną stawkę), a potem wraca do GUI. */
public final class ChatPrompt implements Listener {
    private record Pending(Consumer<String> onInput, Runnable onCancel, BukkitTask timeout) {}

    private final KasynoPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public ChatPrompt(KasynoPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isWaiting(Player p) {
        return pending.containsKey(p.getUniqueId());
    }

    /** Zadaje pytanie. Wpisanie „anuluj” albo 30 s ciszy wywołuje onCancel. */
    public void ask(Player p, String question, Consumer<String> onInput, Runnable onCancel) {
        cancel(p);
        p.closeInventory();
        T.send(p, question + " <gray>(wpisz <white>anuluj</white>, aby wrócić)");
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Pending pe = pending.remove(p.getUniqueId());
            if (pe != null) {
                T.send(p, "<gray>Czas na odpowiedź minął.");
                pe.onCancel().run();
            }
        }, 20L * 30);
        pending.put(p.getUniqueId(), new Pending(onInput, onCancel, timeout));
    }

    public void cancel(Player p) {
        Pending pe = pending.remove(p.getUniqueId());
        if (pe != null) pe.timeout().cancel();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Pending pe = pending.remove(p.getUniqueId());
        if (pe == null) return;
        e.setCancelled(true);
        pe.timeout().cancel();
        String text = T.plain(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("anuluj") || text.equalsIgnoreCase("cancel")) pe.onCancel().run();
            else pe.onInput().accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer());
    }
}
