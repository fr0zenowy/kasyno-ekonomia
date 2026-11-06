package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.List;

/** Ranking graczy: majątek, poziom i wygrane w hazardzie. Klik na głowę otwiera profil. */
public final class RankingGui extends Gui {
    private static final int[] SLOTS = {13, 21, 23, 29, 30, 31, 32, 33, 37, 38};
    private Eco.RankBy by = Eco.RankBy.WEALTH;

    public RankingGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String art() {
        return "ranking";
    }

    @Override
    protected Component title() {
        return T.c("<aqua><bold>Ranking");
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        tab(2, Eco.RankBy.WEALTH, Material.GOLD_INGOT, "Najbogatsi");
        tab(4, Eco.RankBy.LEVEL, Material.EXPERIENCE_BOTTLE, "Najwyższy poziom");
        tab(6, Eco.RankBy.GAMBLING, Material.DIAMOND, "Najwięksi hazardziści");

        List<Eco.RankRow> rows = eco().top(by, SLOTS.length);
        String[] medals = {"<gold><bold>#1", "<gray><bold>#2", "<#cd7f32><bold>#3"};
        for (int i = 0; i < rows.size(); i++) {
            Eco.RankRow r = rows.get(i);
            String value = switch (by) {
                case WEALTH -> "<gold>" + Fmt.money(r.wealth());
                case LEVEL -> "<white>poziom " + r.level();
                case GAMBLING -> "<green>" + Fmt.money(r.totalWon()) + " wygranych";
            };
            String place = i < 3 ? medals[i] : "<white>#" + (i + 1);
            set(SLOTS[i], ItemBuilder.of(Material.PLAYER_HEAD).head(Bukkit.getOfflinePlayer(r.id())).name(place + " <yellow>" + T.esc(r.name()))
                    .lore(value, "<gray>Poziom: <white>" + r.level(), "", "<yellow>Kliknij, aby zobaczyć profil").glow(i < 3).build(), t -> go(new ProfileGui(plugin, player, r.id())));
        }
        if (rows.isEmpty()) set(22, ItemBuilder.of(Material.BARRIER).name("<red>Nikt jeszcze nie gra").build());
        set(49, ItemBuilder.back(), t -> go(new MainMenu(plugin, player)));
    }

    private void tab(int slot, Eco.RankBy key, Material icon, String name) {
        boolean on = by == key;
        set(slot, ItemBuilder.of(icon).icon(switch (key) {
            case WEALTH -> "jackpot";
            case LEVEL -> "star";
            case GAMBLING -> "kosci";
        }).name((on ? "<green><bold>" : "<gray>") + name).glow(on).build(), t -> {
            by = key;
            redraw();
        });
    }
}
