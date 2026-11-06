package pl.kasyno.table;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.ItemBuilder;

import java.util.ArrayList;
import java.util.List;

/** Wyścigi konne na żywo: kliknięcie konia stawia na niego aktualną stawkę, a wyścig biegnie dla całego stołu. Każdy tor to jeden rząd. */
public final class RaceView extends TableView {
    private final RaceTable race;

    public RaceView(KasynoPlugin plugin, Player player) {
        super(plugin, player, plugin.tables().races());
        this.race = plugin.tables().races();
    }

    @Override
    protected String art() {
        return "wyscigi";
    }

    private static int colOf(int pos) {
        return 1 + (int) Math.round(pos * 7 / (double) RaceTable.TRACK);
    }

    @Override
    protected void draw() {
        fill(ItemBuilder.filler());
        Table.Phase ph = table.phase();
        List<Catalog.Horse> horses = race.horses();
        Material[] saddle = {Material.LEATHER_HORSE_ARMOR, Material.IRON_HORSE_ARMOR, Material.GOLDEN_HORSE_ARMOR, Material.DIAMOND_HORSE_ARMOR, Material.SADDLE};
        int[] pos = race.positions();
        int winner = race.winner();
        Table.Seat mine = mySeat();
        boolean open = table.open();
        for (int h = 0; h < RaceTable.HORSES; h++) {
            Catalog.Horse horse = horses.get(h);
            final int idx = h;
            Table.Bet bet = mine == null ? null : mine.bet(String.valueOf(h));
            boolean on = bet != null;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Wypłata: <gold>" + Fmt.mult(horse.mult()));
            lore.add("<gray>Szansa: <white>" + Math.round(horse.p() * 100) + "%");
            if (on) lore.add("<gray>Twoja stawka: <gold>" + Fmt.money(bet.stake));
            lore.add(open ? "<yellow>Kliknij: postaw " + Fmt.money(stake) : "<dark_gray>Zakłady zamknięte");
            set(h * 9, ItemBuilder.of(Material.HORSE_SPAWN_EGG).name((on ? "<green><bold>" : "<white>") + (h + 1) + ". " + horse.name()).glow(on).lore(lore).build(), open ? t -> place(String.valueOf(idx), 0) : null);
            for (int c = 1; c < 9; c++) set(h * 9 + c, ItemBuilder.of(c == 8 ? Material.RED_STAINED_GLASS_PANE : Material.LIGHT_GRAY_STAINED_GLASS_PANE).name(c == 8 ? "<red>Meta" : " ").hidden().build());
            int col = Math.min(8, colOf(pos[h]));
            boolean win = winner == h;
            set(h * 9 + col, ItemBuilder.of(saddle[h]).name((win ? "<gold><bold>" : "<yellow>") + horse.name()).glow(win).build());
        }
        if (mine != null && mine.settled() && ph == Table.Phase.RESULT) {
            long net = mine.result.net();
            set(40, ItemBuilder.of(net > 0 ? Material.LIME_CONCRETE : net < 0 ? Material.RED_CONCRETE : Material.YELLOW_CONCRETE)
                    .name(net > 0 ? "<green><bold>Wygrana " + Fmt.signed(net) : net < 0 ? "<red><bold>Przegrana " + Fmt.signed(net) : "<yellow><bold>Remis").glow(net > 0)
                    .lore("<gray>Postawione: <white>" + Fmt.money(mine.stake()), "<gray>Wypłata: <gold>" + Fmt.money(mine.result.payout())).build());
        } else {
            set(40, ItemBuilder.of(ph == Table.Phase.BETTING ? Material.CLOCK : Material.PAPER)
                    .name(switch (ph) {
                        case WAITING -> "<gray><bold>Stół czeka";
                        case BETTING -> "<green><bold>Start za " + table.secondsLeft() + " s";
                        case RUNNING -> "<yellow><bold>Wyścig trwa...";
                        case RESULT -> "<white><bold>Koniec wyścigu";
                    })
                    .lore("<gray>Graczy: <white>" + table.seats().size() + " <gray>• pula <gold>" + Fmt.money(table.totalStake())).build());
        }
        ItemStack info = ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Kliknij konia, aby postawić na niego.", "<gray>Możesz obstawić kilka koni.", "<gray>Zwrot 94-95%.").build();
        if (open && hasBet()) tableBar(ItemBuilder.of(Material.RED_DYE).icon("cross").name("<red><bold>Cofnij zakłady").lore("<gray>Stawka wróci do portfela.", "<yellow>Kliknij").build(), t -> cancelBets(), info);
        else tableBar(ItemBuilder.of(Material.CLOCK).name("<gray>" + (open ? "Wybierz konia" : "Wyścig trwa")).lore(countdownLine()).build(), null, info);
    }
}
