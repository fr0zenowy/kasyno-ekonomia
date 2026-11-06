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
import java.util.Locale;

/** Koło fortuny na żywo: jedno koło dla całego stołu, każdy stawia swoją kwotę, a mnożnik segmentu liczy się wszystkim. */
public final class KoloView extends TableView {
    private static final int[] STRIP = {18, 19, 20, 21, 22, 23, 24, 25, 26};
    private static final int[] HEADS = {27, 28, 29, 30, 32, 33, 34, 35};

    private final KoloTable wheel;

    public KoloView(KasynoPlugin plugin, Player player) {
        super(plugin, player, plugin.tables().wheel());
        this.wheel = plugin.tables().wheel();
    }

    @Override
    protected String art() {
        return "kolo";
    }

    private static Material paneFor(double mult) {
        if (mult <= 0) return Material.RED_STAINED_GLASS_PANE;
        if (mult < 1) return Material.ORANGE_STAINED_GLASS_PANE;
        if (mult == 1) return Material.YELLOW_STAINED_GLASS_PANE;
        if (mult < 5) return Material.LIME_STAINED_GLASS_PANE;
        if (mult < 10) return Material.LIGHT_BLUE_STAINED_GLASS_PANE;
        if (mult < 50) return Material.PURPLE_STAINED_GLASS_PANE;
        return Material.MAGENTA_STAINED_GLASS_PANE;
    }

    private ItemStack cell(int idx, boolean center) {
        Catalog.WheelSegment seg = wheel.segments().get(idx);
        return ItemBuilder.of(paneFor(seg.mult())).name("<white><bold>" + seg.label()).glow(center).build();
    }

    @Override
    protected void draw() {
        fill(ItemBuilder.filler());
        Table.Phase ph = table.phase();
        List<Integer> hist = wheel.history();
        for (int i = 0; i < KoloTable.HISTORY; i++) {
            if (i < hist.size()) set(i, cell(hist.get(i), i == 0));
            else set(i, ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name("<dark_gray>Brak obrotu").hidden().build());
        }
        set(13, ItemBuilder.of(Material.HOPPER).name("<yellow>▼").build());
        set(31, ItemBuilder.of(Material.HOPPER).name("<yellow>▲").build());
        int[] cells = wheel.cells();
        for (int i = 0; i < 9; i++) set(STRIP[i], cell(cells[i], i == KoloTable.HALF && ph == Table.Phase.RESULT));

        String status = switch (ph) {
            case WAITING -> "<gray><bold>Stół czeka";
            case BETTING -> "<green><bold>Start za " + table.secondsLeft() + " s";
            case RUNNING -> "<yellow><bold>Koło się kręci...";
            case RESULT -> "<white><bold>Wypadło: " + wheel.segments().get(wheel.winner()).label();
        };
        set(11, ItemBuilder.of(ph == Table.Phase.BETTING ? Material.CLOCK : Material.COMPASS).name(status)
                .lore("<gray>Graczy: <white>" + table.seats().size() + " <gray>• pula <gold>" + Fmt.money(table.totalStake())).build());
        Table.Seat mine = mySeat();
        if (mine != null && mine.settled() && ph == Table.Phase.RESULT) {
            long net = mine.result.net();
            set(15, ItemBuilder.of(net > 0 ? Material.LIME_CONCRETE : net < 0 ? Material.RED_CONCRETE : Material.YELLOW_CONCRETE)
                    .name(net > 0 ? "<green><bold>Wygrana " + Fmt.signed(net) : net < 0 ? "<red><bold>Przegrana " + Fmt.signed(net) : "<yellow><bold>Remis").glow(net > 0)
                    .lore("<gray>Stawka: <white>" + Fmt.money(mine.stake()), "<gray>Wypłata: <gold>" + Fmt.money(mine.result.payout())).build());
        }

        int i = 0;
        for (Table.Seat s : table.seats().values()) {
            if (i >= HEADS.length) break;
            set(HEADS[i++], headOf(s));
        }
        if (mine != null && !mine.bets.isEmpty()) {
            set(38, ItemBuilder.of(Material.GOLD_NUGGET).name("<yellow><bold>Twój zakład").lore("<gray>Razem: <gold>" + Fmt.money(mine.stake())).build());
        }
        if (table.phase() == Table.Phase.BETTING && hasBet()) {
            set(40, ItemBuilder.of(Material.RED_DYE).icon("cross").name("<red><bold>Cofnij zakład").lore("<gray>Stawka wróci do portfela.", "<yellow>Kliknij").build(), t -> cancelBets());
        }

        List<String> odds = new ArrayList<>();
        double total = wheel.segments().stream().mapToDouble(Catalog.WheelSegment::w).sum();
        for (Catalog.WheelSegment s : wheel.segments()) odds.add("<gray>" + s.label() + ": <white>" + String.format(Locale.ROOT, "%.2f", s.w() / total * 100) + "%");
        ItemStack info = ItemBuilder.of(Material.BOOK).name("<white>Szanse segmentów").lore(odds).build();
        if (table.open()) {
            tableBar(ItemBuilder.of(Material.EMERALD).icon("play").name("<green><bold>" + (hasBet() ? "Dołóż " : "Postaw ") + Fmt.money(stake)).glow().lore("<gray>Od x0 do x200", countdownLine()).build(), t -> place(KoloTable.PICK, 0), info);
        } else {
            tableBar(ItemBuilder.of(Material.CLOCK).name("<gray>Runda trwa").lore(countdownLine()).build(), null, info);
        }
    }
}
