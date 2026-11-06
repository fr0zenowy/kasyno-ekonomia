package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.PlayerData;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.List;

/** Bank: wpłaty i wypłaty, limit z ulepszeniami i przelewy dla innych graczy. Pieniądze w banku są bezpieczne przed okradaniem. */
public final class BankGui extends Gui {
    private static final long[] AMOUNTS = {100, 1_000, 10_000, 100_000, 1_000_000};

    public BankGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String art() {
        return "bank";
    }

    @Override
    protected Component title() {
        return T.c("<yellow><bold>Bank");
    }

    private static String bar(long value, long max) {
        int filled = (int) Math.max(0, Math.min(20, max == 0 ? 0 : value * 20 / max));
        return "<gold>" + "█".repeat(filled) + "<dark_gray>" + "█".repeat(20 - filled);
    }

    @Override
    protected void render() {
        PlayerData d = eco().pd(id);
        Eco.BankBreakdown b = eco().bank(id);
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));

        set(4, ItemBuilder.of(Material.GOLD_BLOCK).icon("m_bank").name("<gold><bold>Twój bank")
                .lore("<gray>W banku: <gold>" + Fmt.money(d.bank), "<gray>Limit: <gold>" + Fmt.money(b.total()), bar(d.bank, b.total()),
                        "<gray>Portfel: <gold>" + Fmt.money(d.wallet), "", "<dark_gray>Skąd limit: baza " + Fmt.shortNum(b.base()) + ", poziom +" + Fmt.shortNum(b.levels()),
                        "<dark_gray>ulepszenia +" + Fmt.shortNum(b.upgrades()) + (b.sejfs() > 0 ? ", sejfy +" + Fmt.shortNum(b.safes()) : "")).build());

        set(9, ItemBuilder.of(Material.HOPPER).icon("b_deposit").name("<green><bold>Wpłać").lore("<gray>Z portfela do banku.").build());
        set(18, ItemBuilder.of(Material.DISPENSER).icon("b_withdraw").name("<red><bold>Wypłać").lore("<gray>Z banku do portfela.").build());
        for (int i = 0; i < AMOUNTS.length; i++) {
            final long amt = AMOUNTS[i];
            set(10 + i, ItemBuilder.of(Material.LIME_DYE).icon("b_deposit").name("<green>Wpłać " + Fmt.shortNum(amt)).amount(i + 1).lore("<yellow>Kliknij").build(), t -> deposit(amt));
            set(19 + i, ItemBuilder.of(Material.RED_DYE).icon("b_withdraw").name("<red>Wypłać " + Fmt.shortNum(amt)).amount(i + 1).lore("<yellow>Kliknij").build(), t -> withdraw(amt));
        }
        set(15, ItemBuilder.of(Material.EMERALD).icon("b_deposit").name("<green><bold>Wpłać wszystko").lore("<yellow>Kliknij").build(), t -> deposit(Long.MAX_VALUE / 4));
        set(24, ItemBuilder.of(Material.REDSTONE).icon("b_withdraw").name("<red><bold>Wypłać wszystko").lore("<yellow>Kliknij").build(), t -> withdraw(Long.MAX_VALUE / 4));
        set(16, ItemBuilder.of(Material.NAME_TAG).name("<white>Własna kwota (wpłata)").lore("<gray>Wpiszesz kwotę na czacie.", "<gray>Np. 500, 2k, 50%, all").build(), t -> ask(true));
        set(25, ItemBuilder.of(Material.NAME_TAG).name("<white>Własna kwota (wypłata)").lore("<gray>Wpiszesz kwotę na czacie.", "<gray>Np. 500, 2k, 50%, all").build(), t -> ask(false));

        // ulepszenia limitu
        List<Catalog.BankUpgrade> ups = plugin.catalog().bankUpgrades;
        for (int i = 0; i < ups.size() && i < 7; i++) {
            Catalog.BankUpgrade up = ups.get(i);
            boolean done = i < d.bankTier;
            boolean next = i == d.bankTier;
            set(28 + i, ItemBuilder.of(done ? Material.LIME_STAINED_GLASS_PANE : next ? Material.GOLD_INGOT : Material.GRAY_STAINED_GLASS_PANE)
                    .name((done ? "<green>" : next ? "<yellow><bold>" : "<gray>") + up.name()).glow(next)
                    .lore("<gray>Limit: <white>+" + Fmt.num(up.bonus()), "<gray>Cena: <gold>" + Fmt.money(up.cost()), "",
                            done ? "<green>Kupione" : next ? (eco().wallet(id) >= up.cost() ? "<yellow>Kliknij, aby kupić" : "<red>Za mało monet w portfelu") : "<dark_gray>Kupuj po kolei").build(),
                    next ? t -> upgrade() : null);
        }
        set(40, ItemBuilder.of(Material.PLAYER_HEAD).icon("b_transfer").name("<aqua><bold>Przelew").lore("<gray>Wyślij pieniądze z portfela", "<gray>innemu graczowi online.", "", "<yellow>Kliknij, aby wybrać gracza").build(),
                t -> go(new PlayerPicker(plugin, player, "Komu przelać?", "gracz_przelew", p -> true, this::transferTo, () -> go(new BankGui(plugin, player)))));
        backTo(49, () -> go(new MainMenu(plugin, player)));
    }

    private void deposit(long amount) {
        long moved = eco().deposit(id, amount);
        if (moved < 1) {
            Fx.deny(player);
            T.send(player, "<red>Nic nie wpłacono: brak monet w portfelu albo bank jest pełny (limit <gold>" + Fmt.money(eco().bank(id).total()) + "</gold>).");
        } else {
            Fx.coin(player);
            T.send(player, "<green>Wpłacono <gold>" + Fmt.money(moved) + "</gold> do banku.");
        }
        redraw();
    }

    private void withdraw(long amount) {
        long moved = eco().withdraw(id, amount);
        if (moved < 1) {
            Fx.deny(player);
            T.send(player, "<red>Nie masz nic w banku.");
        } else {
            Fx.coin(player);
            T.send(player, "<green>Wypłacono <gold>" + Fmt.money(moved) + "</gold> z banku.");
        }
        redraw();
    }

    private void ask(boolean deposit) {
        plugin.prompts().ask(player, "<white>Ile chcesz " + (deposit ? "wpłacić" : "wypłacić") + "?", text -> {
            PlayerData d = eco().pd(id);
            long amt = Fmt.parseAmount(text, deposit ? d.wallet : d.bank);
            if (amt < 1) {
                T.send(player, "<red>Nie rozumiem kwoty „" + T.esc(text) + "”. Podaj np. 500, 2k, 50% albo all.");
                new BankGui(plugin, player).open();
                return;
            }
            if (deposit) deposit(amt);
            else withdraw(amt);
            new BankGui(plugin, player).open();
        }, () -> new BankGui(plugin, player).open());
    }

    private void upgrade() {
        Eco.Upgrade r = eco().buyBankUpgrade(id);
        switch (r) {
            case OK -> {
                Fx.win(player);
                T.send(player, "<green>Ulepszenie kupione! Nowy limit banku: <gold>" + Fmt.money(eco().bank(id).total()));
            }
            case MAX -> T.send(player, "<gold>Masz już wszystkie ulepszenia banku!");
            case POOR -> {
                Fx.deny(player);
                T.send(player, "<red>Za mało monet w portfelu. Pieniądze z banku wypłać najpierw.");
            }
        }
        redraw();
    }

    private void transferTo(Player target) {
        plugin.prompts().ask(player, "<white>Ile przelać graczowi <yellow>" + T.esc(target.getName()) + "</yellow>?", text -> {
            long amt = Fmt.parseAmount(text, eco().wallet(id));
            if (amt < 1) T.send(player, "<red>Podaj poprawną kwotę, np. 500, 2k, 50%.");
            else if (amt > eco().wallet(id)) T.send(player, "<red>Masz tylko <gold>" + Fmt.money(eco().wallet(id)) + "</gold> w portfelu.");
            else if (eco().transfer(id, target.getUniqueId(), amt)) {
                Fx.coin(player);
                T.send(player, "<green>Przelano <gold>" + Fmt.money(amt) + "</gold> graczowi " + T.esc(target.getName()) + ".");
                T.send(target, "<green>" + T.esc(player.getName()) + " przelał Ci <gold>" + Fmt.money(amt) + "</gold>.");
            } else T.send(player, "<red>Przelew się nie udał.");
            new BankGui(plugin, player).open();
        }, () -> new BankGui(plugin, player).open());
    }
}
