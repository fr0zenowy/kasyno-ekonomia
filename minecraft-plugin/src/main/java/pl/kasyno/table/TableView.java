package pl.kasyno.table;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Fmt;
import pl.kasyno.games.GameGui;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.List;

/**
 * Okno wspólnego stołu. Stan rundy czyta ze stołu (ten sam dla wszystkich graczy), a odświeża się, gdy stół zgłosi zmianę.
 * Dolny pasek to pasek stawki z gier solo: pole „gracze” jest w slocie 52.
 */
public abstract class TableView extends GameGui {
    protected final Table table;
    private long seen = Long.MIN_VALUE;

    protected TableView(KasynoPlugin plugin, Player player, Table table) {
        super(plugin, player, table.id);
        this.table = table;
    }

    /** Rysuje okno na podstawie stanu stołu. */
    protected abstract void draw();

    @Override
    protected final void render() {
        seen = table.version();
        draw();
    }

    @Override
    public void open() {
        super.open();
        table.attach(this);
    }

    @Override
    protected void onClose() {
        table.detach(this);
        table.viewClosed(this);
        super.onClose();
    }

    final void refreshIfChanged() {
        if (!isClosed() && table.version() != seen) redraw();
    }

    @Override
    protected long wallet() {
        Table.Seat s = mySeat();
        if (s != null && s.settled() && s.walletBefore >= 0 && table.phase() == Table.Phase.RUNNING) return s.walletBefore;
        return eco().wallet(id);
    }

    protected Table.Seat mySeat() {
        return table.seat(id);
    }

    protected boolean hasBet() {
        Table.Seat s = mySeat();
        return s != null && !s.bets.isEmpty();
    }

    // ---------- wspólne akcje ----------

    /** Stawia zakład aktualną stawką z paska i odświeża okno. */
    protected void place(String pick, double auto) {
        String err = table.bet(player, pick, stake, auto);
        if (err != null) {
            Fx.deny(player);
            T.send(player, "<red>" + err);
            return;
        }
        Fx.coin(player);
        redraw();
    }

    protected void cancelBets() {
        long back = table.cancel(id);
        if (back > 0) {
            Fx.click(player);
            T.send(player, "<yellow>Cofnięto zakłady. Stawka <gold>" + Fmt.money(back) + "</gold> wróciła do portfela.");
        } else Fx.deny(player);
        redraw();
    }

    /** Dolny pasek: stawka i przycisk główny, a w slocie 52 lista graczy przy stole. */
    protected void tableBar(ItemStack play, Click onPlay, ItemStack info) {
        betBar(play, onPlay, info);
        set(52, playersItem());
    }

    // ---------- gracze przy stole ----------

    protected String statusOf(Table.Seat s) {
        if (!s.settled()) return "";
        long net = s.result.net();
        return net > 0 ? " <green>" + Fmt.signed(net) : net < 0 ? " <red>" + Fmt.signed(net) : " <yellow>remis";
    }

    protected ItemStack playersItem() {
        List<String> lore = new ArrayList<>();
        int n = table.seats().size();
        lore.add("<gray>Przy stole: <white>" + n + (n == 1 ? " gracz" : " graczy") + " <gray>• pula <gold>" + Fmt.money(table.totalStake()));
        lore.add("");
        int shown = 0;
        for (Table.Seat s : table.seats().values()) {
            if (shown++ >= 12) {
                lore.add("<gray>… i " + (n - 12) + " więcej");
                break;
            }
            lore.add((s.id.equals(id) ? "<yellow>" : "<white>") + T.esc(s.name) + " <gray>• <gold>" + Fmt.money(s.stake()) + statusOf(s));
        }
        if (n == 0) lore.add("<dark_gray>Nikt jeszcze nie postawił.");
        return ItemBuilder.of(Material.PLAYER_HEAD).icon("players").name("<aqua><bold>Gracze przy stole").lore(lore).build();
    }

    protected ItemStack headOf(Table.Seat s) {
        return ItemBuilder.of(Material.PLAYER_HEAD).head(Bukkit.getOfflinePlayer(s.id)).name((s.id.equals(id) ? "<yellow><bold>" : "<white>") + T.esc(s.name)).glow(s.id.equals(id))
                .lore("<gray>Stawka: <gold>" + Fmt.money(s.stake()) + statusOf(s)).build();
    }

    // ---------- odliczanie ----------

    protected String countdownLine() {
        return switch (table.phase()) {
            case WAITING -> "<gray>Stół czeka na pierwszy zakład.";
            case BETTING -> "<green>Start za <white>" + table.secondsLeft() + " s";
            case RUNNING -> "<yellow>Runda trwa...";
            case RESULT -> "<gray>Następna runda za chwilę.";
        };
    }

    @Override
    protected Component title() {
        return T.c("<gold><bold>" + table.name);
    }
}
