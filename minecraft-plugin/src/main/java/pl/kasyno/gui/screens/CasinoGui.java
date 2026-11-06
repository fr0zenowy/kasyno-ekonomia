package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.games.GameRegistry;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;
import pl.kasyno.table.Table;

import java.util.ArrayList;
import java.util.List;

/** Hol kasyna: cztery sale z grami (automaty, karty, ryzyko, stoły na żywo) i pokój graczy. */
public final class CasinoGui extends Gui {
    private static final int[] SLOTS = {19, 21, 23, 25};

    public CasinoGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected Component title() {
        return T.c("<gold><bold>Kasyno");
    }

    @Override
    protected String art() {
        return "kasyno";
    }

    @Override
    public void open() {
        super.open();
        repeat(40, 40, this::redraw);
    }

    private int playersAtTables() {
        int n = 0;
        for (Table t : plugin.tables().all()) n += t.seats().size();
        return n;
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        for (int i = 0; i < GameRegistry.HALLS.size() && i < SLOTS.length; i++) {
            GameRegistry.Hall h = GameRegistry.HALLS.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + h.desc());
            lore.add("");
            for (GameRegistry.Def g : GameRegistry.inHall(h.id())) lore.add("<dark_gray> • <white>" + g.name());
            lore.add("");
            if (h.id().equals("live")) {
                int n = playersAtTables();
                lore.add(n > 0 ? "<green>Przy stołach gra teraz: " + n : "<dark_gray>Przy stołach jest cicho");
            }
            lore.add("<yellow>Kliknij, aby wejść");
            set(SLOTS[i], ItemBuilder.of(h.icon()).icon("hall_" + h.id()).name("<gold><bold>" + h.name()).glow(h.id().equals("live") && playersAtTables() > 0).lore(lore).build(), t -> go(new HallGui(plugin, player, h.id())));
        }
        set(31, ItemBuilder.of(Material.PLAYER_HEAD).icon("hall_pokoj").name("<red><bold>Pokój graczy").glow(plugin.rooms().hasLive())
                .lore("<gray>Jackpot, napad na bank, pojedynek i loteria.", plugin.rooms().hasLive() ? "<green>Trwa gra, dołącz!" : "<dark_gray>Teraz cicho", "", "<yellow>Kliknij, aby wejść").build(), t -> go(new LobbyGui(plugin, player)));
        backTo(49, () -> go(new MainMenu(plugin, player)));
    }
}
