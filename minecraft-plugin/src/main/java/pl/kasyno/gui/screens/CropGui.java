package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Empire;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

/** Wybór uprawy do zasadzenia na jednym polu albo (gdy slot == null) na wszystkich wolnych. */
public final class CropGui extends Gui {
    private final Integer slot;

    public CropGui(KasynoPlugin plugin, Player player, Integer slot) {
        super(plugin, player);
        this.slot = slot;
    }

    @Override
    protected String art() {
        return "farma";
    }

    @Override
    protected Component title() {
        return T.c("<green><bold>Co sadzimy?" + (slot == null ? "" : " <dark_gray>(pole " + slot + ")"));
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        int[] cells = {19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < plugin.catalog().crops.size() && i < cells.length; i++) {
            Catalog.Crop c = plugin.catalog().crops.get(i);
            long grow = plugin.empire().growMs(id, c);
            double roi = (double) c.yield() / c.cost();
            set(cells[i], ItemBuilder.of(EmpireGui.CROP_ICONS[i]).name("<yellow><bold>" + c.name())
                    .lore("<gray>Koszt nasion: <gold>" + Fmt.money(c.cost()) + " <gray>za pole", "<gray>Plon: <gold>" + Fmt.money(c.yield()) + " <gray>(x" + String.format(java.util.Locale.ROOT, "%.2f", roi) + ")",
                            "<gray>Czas: <white>" + Fmt.duration(grow), "", eco().wallet(id) >= c.cost() ? "<green>Kliknij, aby zasadzić" : "<red>Za mało monet").build(), t -> plant(c));
        }
        backTo(49, () -> go(new EmpireGui(plugin, player)));
    }

    private void plant(Catalog.Crop c) {
        Empire.Plant r = plugin.empire().plant(id, c.id(), slot, eco().now());
        if (!r.ok()) {
            Fx.deny(player);
            T.send(player, switch (r.reason()) {
                case "busy" -> "<red>Na tym polu już coś rośnie.";
                case "full" -> "<red>Wszystkie pola są zajęte. Poczekaj na plon.";
                case "poor" -> "<red>Nasiona kosztują <gold>" + Fmt.money(r.cost()) + "</gold> za pole, a masz <gold>" + Fmt.money(eco().wallet(id)) + "</gold>.";
                default -> "<red>Nie da się zasadzić.";
            });
            return;
        }
        Fx.sound(player, org.bukkit.Sound.ITEM_CROP_PLANT, 1f, 1f);
        T.send(player, "<green>Zasadzono <white>" + r.planted() + "x " + c.name() + "</white> za <gold>" + Fmt.money(r.cost()) + "</gold>. Dojrzeją za <white>" + Fmt.duration(r.readyIn()) + "</white>."
                + (r.skipped() > 0 ? " <gray>(" + r.skipped() + " pól zostało pustych: zabrakło monet)" : ""));
        go(new EmpireGui(plugin, player));
    }
}
