package pl.kasyno.table;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Games;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.List;

/** Ruletka na żywo: pole to przycisk, kliknięcie kładzie żeton w wysokości stawki. Koło kręci się jednocześnie dla wszystkich graczy. */
public final class RouletteView extends TableView {
    private static final int[] STRIP = {18, 19, 20, 21, 22, 23, 24, 25, 26};
    private static final int[] FIELDS = {36, 37, 38, 39, 40, 41, 42, 43, 44};

    private final RouletteTable roulette;

    public RouletteView(KasynoPlugin plugin, Player player) {
        super(plugin, player, plugin.tables().roulette());
        this.roulette = plugin.tables().roulette();
    }

    @Override
    protected String art() {
        return "ruletka";
    }

    private static Material colorOf(int n) {
        return switch (Games.rouletteColor(n)) {
            case "red" -> Material.RED_CONCRETE;
            case "black" -> Material.BLACK_CONCRETE;
            default -> Material.LIME_CONCRETE;
        };
    }

    private static ItemStack cell(int n, boolean center) {
        String c = Games.rouletteColor(n);
        String col = c.equals("red") ? "<red>" : c.equals("black") ? "<dark_gray>" : "<green>";
        return ItemBuilder.of(colorOf(n)).amount(Math.max(1, n)).name(col + "<bold>" + n).glow(center).build();
    }

    @Override
    protected void draw() {
        fill(ItemBuilder.filler());
        Table.Phase ph = table.phase();
        List<Integer> hist = roulette.history();
        for (int i = 0; i < RouletteTable.HISTORY; i++) {
            if (i < hist.size()) set(i, cell(hist.get(i), i == 0));
            else set(i, ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name("<dark_gray>Brak rundy").hidden().build());
        }
        set(13, ItemBuilder.of(Material.HOPPER).name("<yellow>▼").build());
        int[] cells = roulette.cells();
        for (int i = 0; i < 9; i++) set(STRIP[i], cell(cells[i], i == RouletteTable.HALF && ph == Table.Phase.RESULT));
        set(31, ItemBuilder.of(Material.HOPPER).name("<yellow>▲").build());

        set(11, ItemBuilder.of(ph == Table.Phase.BETTING ? Material.CLOCK : ph == Table.Phase.RUNNING ? Material.COMPASS : Material.PAPER)
                .name(switch (ph) {
                    case WAITING -> "<gray><bold>Stół czeka";
                    case BETTING -> "<green><bold>Start za " + table.secondsLeft() + " s";
                    case RUNNING -> "<yellow><bold>Koło się kręci...";
                    case RESULT -> "<white><bold>Wypadło " + roulette.result() + " (" + RouletteTable.colorName(roulette.result()) + ")";
                })
                .lore("<gray>Graczy: <white>" + table.seats().size() + " <gray>• pula <gold>" + Fmt.money(table.totalStake()), table.open() ? "<yellow>Kliknij pole, aby postawić " + Fmt.money(stake) : "").build());
        Table.Seat mine = mySeat();
        if (mine != null && mine.settled() && ph == Table.Phase.RESULT) {
            long net = mine.result.net();
            set(15, ItemBuilder.of(net > 0 ? Material.LIME_CONCRETE : net < 0 ? Material.RED_CONCRETE : Material.YELLOW_CONCRETE)
                    .name(net > 0 ? "<green><bold>Wygrana " + Fmt.signed(net) : net < 0 ? "<red><bold>Przegrana " + Fmt.signed(net) : "<yellow><bold>Remis").glow(net > 0)
                    .lore("<gray>Postawione: <white>" + Fmt.money(mine.stake()), "<gray>Wypłata: <gold>" + Fmt.money(mine.result.payout())).build());
        }

        // moje żetony
        List<String> mineLore = new ArrayList<>();
        if (mine == null || mine.bets.isEmpty()) mineLore.add("<dark_gray>Nic jeszcze nie postawiłeś.");
        else for (Table.Bet b : mine.bets) mineLore.add("<white>" + RouletteTable.field(b.pick).label() + " <gray>• <gold>" + Fmt.money(b.stake));
        set(28, ItemBuilder.of(Material.GOLD_NUGGET).name("<yellow><bold>Twoje zakłady").lore(mineLore).build());
        List<String> seats = new ArrayList<>();
        for (Table.Seat s : table.seats().values()) {
            if (seats.size() >= 10) break;
            seats.add((s.id.equals(id) ? "<yellow>" : "<white>") + T.esc(s.name) + " <gray>• " + s.bets.size() + " pól • <gold>" + Fmt.money(s.stake()) + statusOf(s));
        }
        if (seats.isEmpty()) seats.add("<dark_gray>Nikt jeszcze nie postawił.");
        set(27, ItemBuilder.of(Material.PLAYER_HEAD).name("<aqua><bold>Stół (" + table.seats().size() + ")").lore(seats).build());

        // pola
        boolean open = table.open();
        for (int i = 0; i < Games.ROULETTE.size(); i++) {
            Games.RouletteBet b = Games.ROULETTE.get(i);
            Table.Bet mineBet = mine == null ? null : mine.bet(b.id());
            Material m = switch (b.id()) {
                case "czerwone" -> Material.RED_DYE;
                case "czarne" -> Material.BLACK_DYE;
                case "parzyste", "nieparzyste" -> Material.PAPER;
                case "niskie", "wysokie" -> Material.OAK_SIGN;
                default -> Material.GOLD_NUGGET;
            };
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Wypłata: <gold>" + Fmt.mult(b.mult()));
            if (mineBet != null) lore.add("<gray>Twój żeton: <gold>" + Fmt.money(mineBet.stake));
            lore.add(open ? "<yellow>Kliknij: postaw " + Fmt.money(stake) : "<dark_gray>Zakłady zamknięte");
            set(FIELDS[i], ItemBuilder.of(m).name((mineBet != null ? "<green><bold>" : "<white>") + b.label()).glow(mineBet != null).lore(lore).build(), open ? t -> place(b.id(), 0) : null);
        }
        set(34, ItemBuilder.of(Material.NAME_TAG).name("<white><bold>Konkretny numer").lore("<gray>Wypłata: <gold>x36", open ? "<yellow>Kliknij i wpisz numer 0-36" : "<dark_gray>Zakłady zamknięte").build(), open ? t -> askNumber() : null);

        ItemStack info = ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Ruletka europejska 0-36.", "<gray>Możesz postawić na wiele pól naraz.", "<gray>Kolory, parzystość, niskie/wysokie x2,", "<gray>tuziny x3, numer x36.", "<gray>Zwrot 97,3%.").build();
        if (open && hasBet()) tableBar(ItemBuilder.of(Material.RED_DYE).icon("cross").name("<red><bold>Cofnij zakłady").lore("<gray>Stawka wróci do portfela.", "<yellow>Kliknij").build(), t -> cancelBets(), info);
        else tableBar(ItemBuilder.of(Material.CLOCK).name("<gray>" + (open ? "Postaw żeton na polu" : "Runda trwa")).lore(countdownLine()).build(), null, info);
    }

    private void askNumber() {
        plugin.prompts().ask(player, "<white>Na jaki numer stawiasz " + Fmt.money(stake) + "? <gray>(0-36)", text -> {
            try {
                int n = Integer.parseInt(text.trim());
                if (n < 0 || n > 36) throw new NumberFormatException();
                String err = table.bet(player, "num:" + n, stake, 0);
                if (err != null) T.send(player, "<red>" + err);
            } catch (NumberFormatException e) {
                T.send(player, "<red>Podaj liczbę od 0 do 36.");
            }
            reopen();
        }, this::reopen);
    }
}
