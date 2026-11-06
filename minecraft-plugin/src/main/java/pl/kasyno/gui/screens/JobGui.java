package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

/** Wybór zawodu: lepsze zawody odblokowuje poziom. */
public final class JobGui extends Gui {
    private static final Material[] ICONS = {Material.BUCKET, Material.MINECART, Material.COOKED_BEEF, Material.COMPARATOR, Material.GOLDEN_APPLE, Material.ELYTRA, Material.NETHERITE_HELMET};

    public JobGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String art() {
        return "zawod";
    }

    @Override
    protected Component title() {
        return T.c("<yellow><bold>Zawód");
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        int level = eco().pd(id).level;
        String current = eco().pd(id).job;
        int[] slots = {19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < plugin.catalog().jobs.size() && i < slots.length; i++) {
            Catalog.Job j = plugin.catalog().jobs.get(i);
            boolean unlocked = level >= j.level();
            boolean on = j.id().equals(current);
            set(slots[i], ItemBuilder.of(unlocked ? ICONS[i % ICONS.length] : Material.BARRIER).name((on ? "<green><bold>" : unlocked ? "<yellow>" : "<red>") + j.name()).glow(on)
                    .lore("<gray>Wypłata: <gold>" + Fmt.num(j.min()) + "-" + Fmt.num(j.max()) + " $", "<gray>Wymaga poziomu: <white>" + j.level(), "",
                            on ? "<green>Twój obecny zawód" : unlocked ? "<yellow>Kliknij, aby wybrać" : "<red>Za niski poziom (masz " + level + ")").build(), t -> {
                        if (plugin.earn().changeJob(id, j.id())) {
                            Fx.coin(player);
                            T.send(player, "<green>Teraz pracujesz jako <white>" + j.name() + "</white>.");
                            redraw();
                        } else Fx.deny(player);
                    });
        }
        backTo(49, () -> go(new EarnGui(plugin, player)));
    }
}
