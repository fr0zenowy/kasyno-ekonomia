package pl.kasyno.table;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Games;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Crash na żywo: lobby z graczami i auto-wypłatą przed startem, potem wspólny wykres mnożnika, na którym każdy wypłaca się sam. */
public final class CrashView extends TableView {
    private static final int[] AUTO_SLOTS = {36, 37, 38, 39, 40, 41};
    private static final int[] HEADS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int[] HISTORY_SLOTS = {0, 1, 2, 3, 5, 6, 7, 8};

    private static final Map<Material, ItemStack> BARS = new EnumMap<>(Material.class);

    private final CrashTable crash;
    private double auto;

    public CrashView(KasynoPlugin plugin, Player player) {
        super(plugin, player, plugin.tables().crash());
        this.crash = plugin.tables().crash();
        this.auto = Double.parseDouble(choice("auto", "0"));
    }

    @Override
    protected String art() {
        return "crash";
    }

    private static ItemStack bar(Material m) {
        return BARS.computeIfAbsent(m, k -> ItemBuilder.of(k).name(" ").hidden().build());
    }

    private static String color(double m) {
        return m >= 5 ? "<light_purple>" : m >= 2 ? "<green>" : "<yellow>";
    }

    @Override
    protected void draw() {
        fill(ItemBuilder.filler());
        Table.Phase ph = table.phase();
        boolean live = ph == Table.Phase.RUNNING || ph == Table.Phase.RESULT;
        drawHistory();
        drawMultiplier(ph);
        if (live) drawGraph(ph == Table.Phase.RESULT);
        else drawLobby();
        drawBar(ph);
    }

    private void drawHistory() {
        List<Double> hist = crash.history();
        for (int i = 0; i < HISTORY_SLOTS.length; i++) {
            if (i >= hist.size()) {
                set(HISTORY_SLOTS[i], ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name("<dark_gray>Brak rundy").hidden().build());
                continue;
            }
            double h = hist.get(i);
            Material m = h < 2 ? Material.RED_CONCRETE : h < 10 ? Material.YELLOW_CONCRETE : Material.LIME_CONCRETE;
            set(HISTORY_SLOTS[i], ItemBuilder.of(m).name((h < 2 ? "<red>" : h < 10 ? "<yellow>" : "<green>") + "<bold>" + CrashTable.x(h)).lore(i == 0 ? "<gray>Ostatnia runda" : "<gray>" + (i + 1) + " rund temu").build());
        }
    }

    private void drawMultiplier(Table.Phase ph) {
        List<String> lore = new ArrayList<>();
        Material icon;
        String name;
        switch (ph) {
            case WAITING -> {
                icon = Material.CLOCK;
                name = "<gray><bold>Stół czeka";
                lore.add("<gray>Pierwszy zakład uruchamia odliczanie.");
                lore.add("<gray>Wtedy każdy może dołączyć.");
            }
            case BETTING -> {
                icon = Material.CLOCK;
                name = "<green><bold>Start za " + table.secondsLeft() + " s";
                lore.add("<gray>Graczy: <white>" + table.seats().size() + " <gray>• pula <gold>" + Fmt.money(table.totalStake()));
                lore.add("<gray>Dołącz, zanim runda wystartuje.");
            }
            case RUNNING -> {
                icon = Material.FIREWORK_ROCKET;
                name = color(crash.mult()) + "<bold>" + CrashTable.x(crash.mult());
                lore.add("<gray>Graczy: <white>" + table.seats().size() + " <gray>• pula <gold>" + Fmt.money(table.totalStake()));
                Table.Seat s = mySeat();
                if (s != null && !s.settled()) lore.add("<gray>Twoja wypłata teraz: <gold>" + Fmt.money((long) Math.floor(s.stake() * crash.mult())));
            }
            default -> {
                icon = Material.TNT;
                name = "<red><bold>CRASH " + CrashTable.x(crash.crashAt());
                lore.add("<gray>Runda zakończona.");
            }
        }
        Table.Seat s = mySeat();
        if (ph != Table.Phase.RUNNING && s != null && !s.bets.isEmpty()) {
            Table.Bet b = s.bets.get(0);
            lore.add("<gray>Twój zakład: <gold>" + Fmt.money(b.stake) + (b.auto > 0 ? " <gray>(auto " + Fmt.mult(b.auto) + ")" : ""));
        }
        set(4, ItemBuilder.of(icon).icon("crash").name(name).glow(ph == Table.Phase.RUNNING).lore(lore).build());
    }

    private void drawGraph(boolean over) {
        double mult = crash.mult();
        List<Double> trail = crash.trail();
        double scale = Math.max(2, mult * 1.4);
        int cols = 9;
        for (int c = 0; c < cols; c++) {
            int idx = trail.size() - cols + c;
            double m = idx >= 0 && idx < trail.size() ? trail.get(idx) : 1;
            if (c == cols - 1) m = mult;
            int h = (int) Math.max(0, Math.min(4, Math.round(Math.log(Math.max(1, m)) / Math.log(scale) * 4)));
            for (int r = 0; r < 4; r++) {
                int slot = (4 - r) * 9 + c;
                boolean filled = r < h;
                Material mat = !filled ? Material.GRAY_STAINED_GLASS_PANE : over ? Material.RED_STAINED_GLASS_PANE
                        : m >= 5 ? Material.MAGENTA_STAINED_GLASS_PANE : m >= 2 ? Material.LIME_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE;
                set(slot, bar(mat));
            }
        }
    }

    private void drawLobby() {
        int i = 0;
        for (Table.Seat s : table.seats().values()) {
            if (i >= HEADS.length) break;
            set(HEADS[i++], headOf(s));
        }
        boolean canPick = table.open() && !hasBet();
        for (int k = 0; k < CrashTable.AUTO.length; k++) {
            double a = CrashTable.AUTO[k];
            boolean on = Math.abs(a - auto) < 1e-9;
            ItemStack item = ItemBuilder.of(Material.CLOCK).name((on ? "<green><bold>" : "<white>") + (a == 0 ? "Wypłacam ręcznie" : "Auto-wypłata " + Fmt.mult(a))).glow(on)
                    .lore(a > 0 ? "<gray>Szansa dojścia: <white>" + String.format(Locale.ROOT, "%.1f", Games.crashReach(a) * 100) + "%" : "<gray>Sam klikasz „Wypłać”.", canPick ? (on ? "<green>Wybrane" : "<yellow>Kliknij") : "<dark_gray>Ustawiasz przed zakładem").build();
            set(AUTO_SLOTS[k], item, canPick ? t -> setAuto(a) : null);
        }
        set(42, ItemBuilder.of(Material.NAME_TAG).name("<white>Własna auto-wypłata").lore("<gray>Wpisz mnożnik od 1,1 do 10000.", canPick ? "<yellow>Kliknij i wpisz" : "<dark_gray>Ustawiasz przed zakładem").build(), canPick ? t -> askAuto() : null);
    }

    private void drawBar(Table.Phase ph) {
        List<String> info = new ArrayList<>(List.of("<white>Jak to działa:", "<gray>Wszyscy grają tę samą rundę.", "<gray>Mnożnik rośnie, a Ty wypłacasz się", "<gray>zanim „crashnie”.", "<gray>Zwrot 97%.",
                "", "<gray>Szanse dojścia: x2 <white>48,5%<gray>, x10 <white>9,7%", "<gray>Zamknięcie okna w trakcie rundy = wypłata."));
        ItemStack book = ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore(info).build();
        Table.Seat s = mySeat();
        if (ph == Table.Phase.RUNNING && hasBet() && !s.settled()) {
            tableBar(ItemBuilder.of(Material.GOLD_INGOT).icon("cashout").name("<gold><bold>Wypłać " + Fmt.money((long) Math.floor(s.stake() * crash.mult()))).glow().lore("<gray>Kliknij teraz!").build(), t -> cashOut(), book);
        } else if (ph == Table.Phase.RUNNING && hasBet()) {
            Table.Bet b = s.bets.get(0);
            tableBar(ItemBuilder.of(Material.LIME_DYE).name("<green><bold>Wypłacono przy " + CrashTable.x(b.cashedAt)).lore("<gray>Wypłata: <gold>" + Fmt.money(s.result.payout())).build(), null, book);
        } else if (table.open() && hasBet()) {
            tableBar(ItemBuilder.of(Material.RED_DYE).icon("cross").name("<red><bold>Cofnij zakład").lore("<gray>Stawka wróci do portfela.", "<yellow>Kliknij").build(), t -> cancelBets(), book);
        } else if (table.open()) {
            tableBar(ItemBuilder.of(Material.EMERALD).icon("play").name("<green><bold>Postaw " + Fmt.money(stake)).glow()
                    .lore("<gray>" + (auto > 0 ? "Auto-wypłata: <white>" + Fmt.mult(auto) : "Wypłacasz ręcznie"), countdownLine()).build(), t -> place("crash", auto), book);
        } else if (ph == Table.Phase.RESULT && hasBet() && s.settled()) {
            tableBar(resultItem(s), null, book);
        } else {
            tableBar(ItemBuilder.of(Material.CLOCK).name("<gray>Runda trwa").lore("<gray>Zagrasz w następnej rundzie.", countdownLine()).build(), null, book);
        }
    }

    private ItemStack resultItem(Table.Seat s) {
        long net = s.result.net();
        Material m = net > 0 ? Material.LIME_CONCRETE : net < 0 ? Material.RED_CONCRETE : Material.YELLOW_CONCRETE;
        return ItemBuilder.of(m).name(net > 0 ? "<green><bold>Wygrana " + Fmt.signed(net) : net < 0 ? "<red><bold>Przegrana " + Fmt.signed(net) : "<yellow><bold>Remis").glow(net > 0)
                .lore("<gray>Stawka: <white>" + Fmt.money(s.stake()), "<gray>Wypłata: <gold>" + Fmt.money(s.result.payout())).build();
    }

    private void cashOut() {
        String err = crash.cashOut(id);
        if (err != null) {
            Fx.deny(player);
            T.send(player, "<red>" + err);
        }
        redraw();
    }

    private void setAuto(double a) {
        auto = a;
        saveChoice("auto", String.valueOf(a));
        Fx.click(player);
        redraw();
    }

    private void askAuto() {
        plugin.prompts().ask(player, "<white>Przy jakim mnożniku wypłacić automatycznie? <gray>(1,1 do 10000, 0 = ręcznie)", text -> {
            try {
                double v = Double.parseDouble(text.trim().toLowerCase().replace(',', '.').replace("x", ""));
                if (v != 0 && (v < 1.1 || v > Games.CRASH_MAX)) throw new NumberFormatException();
                saveChoice("auto", String.valueOf(v));
            } catch (NumberFormatException e) {
                T.send(player, "<red>Podaj liczbę od 1,1 do 10000 albo 0.");
            }
            reopen();
        }, this::reopen);
    }
}
