package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.PlayerData;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.Icons;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.List;
import java.util.UUID;

/** Profil gracza: poziom, majątek, statystyki hazardu, kolekcja luksusu i zwierzaki. */
public final class ProfileGui extends Gui {
    private final UUID target;

    public ProfileGui(KasynoPlugin plugin, Player viewer, UUID target) {
        super(plugin, viewer);
        this.target = target;
    }

    @Override
    protected String art() {
        return "profil";
    }

    @Override
    protected Component title() {
        return T.c("<dark_aqua><bold>Profil: " + T.esc(eco().pd(target).name));
    }

    private static String bar(long value, long max) {
        int filled = (int) Math.max(0, Math.min(20, max == 0 ? 0 : value * 20 / max));
        return "<green>" + "█".repeat(filled) + "<dark_gray>" + "█".repeat(20 - filled);
    }

    @Override
    protected void render() {
        PlayerData d = eco().pd(target);
        Catalog cat = plugin.catalog();
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        boolean trophy = eco().qty(target, "trofeum") > 0;
        long need = Eco.xpNeeded(d.level);
        set(4, ItemBuilder.of(Material.PLAYER_HEAD).head(Bukkit.getOfflinePlayer(target)).name("<yellow><bold>" + T.esc(d.name) + (trophy ? " <gold>★" : ""))
                .lore("<gray>Poziom <white>" + d.level, bar(d.xp, need) + " <gray>" + Fmt.shortNum(d.xp) + "/" + Fmt.shortNum(need) + " XP",
                        "<gray>Ranking: <white>#" + eco().rankOf(target), "<gray>Zawód: <white>" + cat.job(d.job).name(), "<gray>Seria daily: <white>" + d.streak + " dni").build());
        set(20, ItemBuilder.of(Material.GOLD_INGOT).name("<gold><bold>Majątek").lore("<gray>Portfel: <gold>" + Fmt.money(d.wallet), "<gray>Bank: <gold>" + Fmt.money(d.bank),
                "<gray>Razem: <gold>" + Fmt.money(d.wallet + d.bank)).build());
        long net = d.totalWon - d.totalLost;
        set(22, ItemBuilder.of(Material.DIAMOND).name("<aqua><bold>Hazard").lore("<gray>Rozegranych gier: <white>" + Fmt.num(d.games), "<gray>Wygrane: <green>" + Fmt.money(d.totalWon),
                "<gray>Przegrane: <red>" + Fmt.money(d.totalLost), "<gray>Bilans: " + (net >= 0 ? "<green>+" : "<red>") + Fmt.money(net),
                "<gray>Rekord wygranej: <gold>" + Fmt.money(d.biggestWin)).build());
        set(24, ItemBuilder.of(Material.WHEAT).name("<green><bold>Imperium").lore("<gray>Biznesy: <white>" + d.businesses.size() + "<gray>/" + cat.businesses.size(),
                "<gray>Dochód: <gold>" + Fmt.money(plugin.empire().hourlyTotal(target)) + "<gray>/h").build());

        // Kolekcja luksusu i zwierzaki: wszystko, co gracz ma, jako ikony.
        int slot = 28;
        for (Catalog.Item it : cat.items.values()) {
            if (!it.type().equals("luxury") || eco().qty(target, it.id()) == 0) continue;
            if (slot > 34) break;
            set(slot++, ItemBuilder.of(Icons.of(it)).name("<light_purple>" + it.name()).lore("<gray>Wartość: <gold>" + Fmt.money(it.price())).glow().build());
        }
        List<String> petIds = cat.categories.stream().filter(c -> c.id().equals("zwierzaki")).findFirst().orElseThrow().items();
        int pslot = 37;
        for (String pid : petIds) {
            if (eco().qty(target, pid) == 0) continue;
            Catalog.Item it = cat.item(pid);
            set(pslot++, ItemBuilder.of(Icons.of(it)).name("<green>" + it.name()).lore("<gray>" + it.desc()).build());
        }
        set(27, ItemBuilder.of(Material.AMETHYST_SHARD).name("<light_purple>Kolekcja luksusu").lore("<gray>Najdroższe zdobycze gracza.").build());
        set(36, ItemBuilder.of(Material.BONE).name("<green>Zwierzaki").lore("<gray>Dają stałe bonusy.").build());
        backTo(49, () -> go(new MainMenu(plugin, player)));
    }
}
