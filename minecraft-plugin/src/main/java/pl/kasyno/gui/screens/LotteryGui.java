package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Fmt;
import pl.kasyno.games.Lottery;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.Locale;

/** Loteria: kup bilety, sprawdź pulę, swoją szansę i czas do losowania. */
public final class LotteryGui extends Gui {
    private static final int[] AMOUNTS = {1, 5, 10, 50, 100};
    private static final int[] HEADS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 23, 24, 25};

    public LotteryGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String art() {
        return "loteria";
    }

    @Override
    protected Component title() {
        return T.c("<gold><bold>Loteria");
    }

    @Override
    public void open() {
        super.open();
        repeat(5, 5, this::redraw);
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        Lottery.Pool pool = plugin.lottery().pool();
        int mine = eco().pd(id).lotteryTickets;
        String odds = pool.tickets() > 0 ? String.format(Locale.ROOT, "%.1f%%", mine * 100.0 / pool.tickets()) : "-";
        long left = Math.max(0, plugin.lottery().nextDraw() - System.currentTimeMillis());
        set(4, ItemBuilder.of(Material.PAPER).name("<gold><bold>Loteria").glow()
                .lore("<gray>Pula dla zwycięzcy: <gold>" + Fmt.money(pool.pot()), "<gray>Sprzedanych biletów: <white>" + Fmt.num(pool.tickets()) + " <gray>(graczy: " + pool.players() + ")",
                        "<gray>Twoje bilety: <white>" + mine + " <gray>(szansa " + odds + ")", "<gray>Losowanie za: <white>" + Fmt.duration(left),
                        "<gray>Cena biletu: <gold>" + Fmt.money(Lottery.TICKET_PRICE) + " <gray>• limit " + Lottery.MAX_TICKETS + " na osobę",
                        plugin.store().global().lotteryLastName != null ? "<gray>Ostatni zwycięzca: <white>" + T.esc(plugin.store().global().lotteryLastName) + " <gold>" + Fmt.money(plugin.store().global().lotteryLastAmount) : "").build());
        for (int i = 0; i < AMOUNTS.length; i++) {
            final int n = AMOUNTS[i];
            set(28 + i, ItemBuilder.of(Material.GOLD_NUGGET).amount(Math.min(64, n)).name("<green><bold>Kup " + n + (n == 1 ? " bilet" : " biletów"))
                    .lore("<gray>Koszt: <gold>" + Fmt.money(n * Lottery.TICKET_PRICE), "<yellow>Kliknij").build(), t -> buy(n));
        }
        Lottery.Scene sc = plugin.lottery().scene();
        if (sc != null) {
            // losowanie: głowy uczestników, podświetlenie krąży i zwalnia na zwycięzcy, a pośrodku świeci imię
            int hot = sc.step >= 0 ? sc.spin[Math.min(sc.step, sc.spin.length - 1)] : -1;
            for (int i = 0; i < sc.participants.size() && i < HEADS.length; i++) {
                boolean on = i == hot && !sc.done;
                boolean win = sc.done && sc.names.get(i).equals(sc.winner);
                String nm = T.esc(sc.names.get(i));
                if (on) set(HEADS[i], ItemBuilder.of(Material.GOLD_BLOCK).icon("loteria").name("<gold><bold>▶ " + nm + " ◀").glow().build());
                else set(HEADS[i], ItemBuilder.of(Material.PLAYER_HEAD).head(org.bukkit.Bukkit.getOfflinePlayer(sc.participants.get(i))).name((win ? "<gold><bold>★ " : "<yellow>") + nm).glow(win).build());
            }
            if (!sc.done) {
                String now = sc.step >= 0 ? sc.nameAt(sc.step) : "?";
                set(22, ItemBuilder.of(Material.PAPER).icon("loteria").name("<gold><bold>Losowanie: <white>" + T.esc(now)).glow().lore("<gray>Pula: <gold>" + Fmt.money(sc.pot), "<gray>Kręcimy...").build());
            } else {
                set(22, ItemBuilder.of(Material.NETHER_STAR).icon("star").name("<gold><bold>Wygrywa: <white>" + T.esc(sc.winner)).glow().lore("<gray>Pula: <gold>" + Fmt.money(sc.pot)).build());
            }
        }
        backTo(49, () -> go(new LobbyGui(plugin, player)));
    }

    private void buy(int n) {
        String err = plugin.lottery().buy(id, n);
        if (err != null) {
            Fx.deny(player);
            T.send(player, "<red>" + err);
        } else {
            Fx.coin(player);
            T.send(player, "<green>Kupiłeś <white>" + n + "</white> biletów za <gold>" + Fmt.money(n * Lottery.TICKET_PRICE) + "</gold>. Masz ich razem <white>" + eco().pd(id).lotteryTickets + "</white>.");
        }
        redraw();
    }
}
