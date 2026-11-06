package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Crates;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.Icons;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Skrzynie: lista z szansami na rzadkości i wartością oczekiwaną. Klik otwiera jedną z animacją, shift+klik do dziesięciu naraz. */
public final class CratesGui extends Gui {
    private static final int[] SLOTS = {19, 20, 21, 23, 24, 25};

    public CratesGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String art() {
        return "skrzynie";
    }

    @Override
    protected Component title() {
        return T.c("<gold><bold>Skrzynie");
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        int i = 0;
        for (Catalog.Crate c : plugin.catalog().crates.values()) {
            if (i >= SLOTS.length) break;
            Catalog.Item it = plugin.catalog().item(c.id());
            int have = eco().qty(id, c.id());
            List<String> lore = new ArrayList<>(T.wrap(it.desc(), 36, "<gray>"));
            lore.add("");
            Map<String, Double> odds = plugin.crates().rarityOdds(c.id());
            for (Crates.Rarity r : Crates.RARITY.values()) {
                double v = odds.getOrDefault(r.id(), 0.0);
                if (v > 0) lore.add("<" + r.color() + ">" + r.name() + ": <white>" + String.format(java.util.Locale.ROOT, "%.1f", v) + "%");
            }
            double ev = plugin.crates().expectedValue(c.id());
            lore.add("");
            lore.add("<gray>Średnia nagroda: <gold>" + Fmt.money(Math.round(ev)) + " <dark_gray>(" + Math.round(ev / it.price() * 100) + "% ceny)");
            lore.add("<gray>Cena w sklepie: <gold>" + Fmt.money(it.price()));
            lore.add("<gray>Masz: <white>" + have);
            lore.add(have > 0 ? "<yellow>Klik: otwórz <dark_gray>| <yellow>Shift: otwórz do 10" : "<red>Nie masz tej skrzyni");
            set(SLOTS[i++], ItemBuilder.of(Icons.of(it)).name("<yellow><bold>" + it.name()).amount(Math.max(1, Math.min(have, 64))).glow(have > 0).lore(lore).build(), t -> open(c.id(), have, t));
        }
        backTo(49, () -> go(new MainMenu(plugin, player)));
    }

    private void open(String crateId, int have, ClickType t) {
        if (have < 1) {
            Fx.deny(player);
            T.send(player, "<red>Nie masz tej skrzyni. Kupisz ją w sklepie albo zdobędziesz w grze.");
            return;
        }
        int qty = t == ClickType.SHIFT_LEFT || t == ClickType.SHIFT_RIGHT ? Math.min(have, Crates.MAX_OPEN) : 1;
        go(new CrateOpenGui(plugin, player, crateId, qty));
    }
}
