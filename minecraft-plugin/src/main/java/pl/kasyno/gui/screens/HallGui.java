package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Fmt;
import pl.kasyno.games.GameRegistry;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;
import pl.kasyno.table.Table;

import java.util.ArrayList;
import java.util.List;

/** Sala kasyna: gry jednego rodzaju (automaty, karty, ryzyko albo stoły na żywo). Przy stołach widać, ilu graczy siedzi przy stole. */
public final class HallGui extends Gui {
    private final GameRegistry.Hall hall;

    public HallGui(KasynoPlugin plugin, Player player, String hallId) {
        super(plugin, player);
        this.hall = GameRegistry.hall(hallId);
        if (hall == null) throw new IllegalArgumentException("Nie ma sali " + hallId);
    }

    @Override
    protected Component title() {
        return T.c("<gold><bold>" + hall.name());
    }

    @Override
    protected String art() {
        return hall.id();
    }

    @Override
    public void open() {
        super.open();
        if (hall.id().equals("live")) repeat(20, 20, this::redraw);
    }

    /** Rozstawia ikony gier w środku okna: jeden rząd dla czterech gier, dwa rzędy dla sześciu. */
    static int[] slotsFor(int n) {
        return switch (n) {
            case 1 -> new int[]{22};
            case 2 -> new int[]{21, 23};
            case 3 -> new int[]{20, 22, 24};
            case 4 -> new int[]{19, 21, 23, 25};
            case 5 -> new int[]{11, 13, 15, 29, 33};
            default -> new int[]{11, 13, 15, 29, 31, 33};
        };
    }

    private String liveStatus(GameRegistry.Def g) {
        Table t = plugin.tables().get(g.id());
        if (t == null) return "";
        int n = t.seats().size();
        return switch (t.phase()) {
            case WAITING -> "<dark_gray>Stół czeka na pierwszy zakład";
            case BETTING -> "<green>Zakłady otwarte: " + n + (n == 1 ? " gracz" : " graczy") + ", start za " + t.secondsLeft() + " s";
            case RUNNING -> "<yellow>Runda trwa (" + n + " graczy, pula " + Fmt.money(t.totalStake()) + ")";
            case RESULT -> "<gray>Runda właśnie się skończyła";
        };
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        List<GameRegistry.Def> games = GameRegistry.inHall(hall.id());
        int[] slots = slotsFor(games.size());
        for (int i = 0; i < games.size() && i < slots.length; i++) {
            GameRegistry.Def g = games.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + g.desc());
            lore.add("");
            lore.add("<dark_gray>" + g.tags());
            if (g.live()) lore.add(liveStatus(g));
            lore.add("<green>Kliknij, aby zagrać");
            boolean busy = g.live() && plugin.tables().get(g.id()).phase() == Table.Phase.BETTING;
            ItemStack item = ItemBuilder.of(g.icon()).icon(g.id()).name("<yellow><bold>" + g.name()).glow(busy).lore(lore).build();
            set(slots[i], item, t -> go(g.factory().apply(plugin, player)));
        }
        set(4, ItemBuilder.of(hall.icon()).icon("hall_" + hall.id()).name("<gold><bold>" + hall.name()).lore("<gray>" + hall.desc()).build());
        backTo(49, () -> go(new CasinoGui(plugin, player)));
    }
}
