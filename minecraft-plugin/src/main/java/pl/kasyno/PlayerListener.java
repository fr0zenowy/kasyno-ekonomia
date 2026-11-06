package pl.kasyno;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.PlayerData;
import pl.kasyno.gui.T;

/** Wejście gracza: zapamiętujemy nick, a stawka z gry przerwanej awarią serwera wraca do portfela. */
public final class PlayerListener implements Listener {
    private final KasynoPlugin plugin;

    public PlayerListener(KasynoPlugin plugin) {
        this.plugin = plugin;
    }

    public static void prepare(KasynoPlugin plugin, Player p) {
        PlayerData d = plugin.eco().pd(p.getUniqueId());
        d.name = p.getName();
        long refunded = plugin.eco().refund(p.getUniqueId());
        if (refunded > 0) T.send(p, "<yellow>Twoja poprzednia gra została przerwana. Stawka <gold>" + Fmt.money(refunded) + "</gold> wróciła do portfela.");
        plugin.store().touch(p.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        prepare(plugin, e.getPlayer());
        if (plugin.eco().pd(e.getPlayer().getUniqueId()).games == 0 && plugin.eco().pd(e.getPlayer().getUniqueId()).lastDaily == 0) {
            T.send(e.getPlayer(), "<green>Witaj w kasynie! Wpisz <white>/kasyno</white> albo kliknij NPC, żeby otworzyć menu. Na start masz <gold>"
                    + Fmt.money(plugin.eco().wallet(e.getPlayer().getUniqueId())) + "</gold>.");
        }
    }
}
