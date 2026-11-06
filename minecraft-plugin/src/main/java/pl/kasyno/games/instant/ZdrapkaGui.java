package pl.kasyno.games.instant;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Games;
import pl.kasyno.games.GameGui;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Zdrapka: kupujesz losy i zdrapujesz dziewięć pól, trzy takie same symbole to wygrana (do x500). Wynik jest znany od zakupu. */
public final class ZdrapkaGui extends GameGui {
    private static final String[] NAMES = {"Wiśnia", "Cytryna", "Koniczyna", "Gwiazda", "Diament", "Korona", "Puchar", "Automat"};
    private static final Material[] ICONS = {Material.SWEET_BERRIES, Material.HONEYCOMB, Material.FERN, Material.NETHER_STAR, Material.DIAMOND, Material.GOLDEN_HELMET, Material.GOLD_BLOCK, Material.NOTE_BLOCK};
    private static final String[] SYMS = {"🍒", "🍋", "🍀", "⭐", "💎", "👑", "🏆", "🎰"};
    private static final int[] GRID = {11, 12, 13, 20, 21, 22, 29, 30, 31};

    private int[] cells;
    private boolean[] scratched = new boolean[9];
    private Eco.Settle settle;
    private long bet;
    private String verdict;
    private boolean ticket;

    public ZdrapkaGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "zdrapka");
    }

    @Override
    protected Component title() {
        return T.c("<aqua><bold>Zdrapka");
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        for (int i = 0; i < 9; i++) {
            final int cell = i;
            if (!ticket) set(GRID[i], ItemBuilder.of(Material.GRAY_CONCRETE).name("<gray>?").lore("<gray>Kup zdrapkę, żeby zdrapać").build());
            else if (scratched[i]) set(GRID[i], ItemBuilder.of(ICONS[cells[i]]).name("<yellow><bold>" + NAMES[cells[i]]).glow().build());
            else set(GRID[i], ItemBuilder.of(Material.GRAY_CONCRETE).name("<white><bold>Zdrap!").lore("<yellow>Kliknij, aby zdrapać").build(), t -> scratch(cell));
        }
        if (ticket) {
            set(40, ItemBuilder.of(Material.FLINT).name("<yellow><bold>Zdrap wszystko").glow(!allScratched()).lore("<gray>Odkrywa wszystkie pola od razu.").build(), t -> scratchAll());
        }
        if (verdict != null && allScratched()) set(4, resultItem(settle, bet, verdict));
        List<String> pay = new ArrayList<>();
        Catalog.ScratchOutcome[] outs = plugin.catalog().games.zdrapka().toArray(new Catalog.ScratchOutcome[0]);
        double total = 0;
        for (Catalog.ScratchOutcome o : outs) total += o.w();
        for (Catalog.ScratchOutcome o : outs) {
            if (o.mult() > 0) pay.add("<gray>3x " + NAMES[indexOf(o.sym())] + ": <gold>" + Fmt.mult(o.mult()) + " <dark_gray>(" + String.format(java.util.Locale.ROOT, "%.2f", o.w() / total * 100) + "%)");
        }
        betBar(ItemBuilder.of(Material.EMERALD).name(busy ? "<gray>Zdrap pola…" : "<green><bold>Kup zdrapkę").glow(!busy).lore("<gray>Cena: <gold>" + Fmt.money(stake)).build(), t -> buy(),
                ItemBuilder.of(Material.BOOK).name("<white>Wygrane").lore(pay).build());
    }

    private static int indexOf(String sym) {
        for (int i = 0; i < SYMS.length; i++) if (SYMS[i].equals(sym)) return i;
        return 0;
    }

    private boolean allScratched() {
        for (boolean b : scratched) if (!b) return false;
        return true;
    }

    private void buy() {
        if (busy || !takeStake()) return;
        busy = true;
        ticket = true;
        scratched = new boolean[9];
        verdict = null;
        List<Catalog.ScratchOutcome> outs = plugin.catalog().games.zdrapka();
        Catalog.ScratchOutcome outcome = Games.weighted(outs, Catalog.ScratchOutcome::w, eco().rng);
        // plansza: przy wygranej dokładnie trzy symbole wygrywającego, żaden inny nie występuje trzy razy
        List<Integer> list = new ArrayList<>();
        int winSym = outcome.sym() == null ? -1 : indexOf(outcome.sym());
        if (winSym >= 0) for (int i = 0; i < 3; i++) list.add(winSym);
        int[] counts = new int[8];
        while (list.size() < 9) {
            int s = eco().rng.nextInt(8);
            if (s == winSym || counts[s] >= 2) continue;
            counts[s]++;
            list.add(s);
        }
        Collections.shuffle(list, new java.util.Random(eco().rng.nextLong()));
        cells = list.stream().mapToInt(Integer::intValue).toArray();
        bet = stake;
        settle = eco().settle(id, bet, (long) Math.floor(bet * outcome.mult()));
        verdict = outcome.mult() > 0 ? "<white>Trzy " + NAMES[winSym] + "! " + Fmt.mult(outcome.mult()) : "<white>Pudło, tym razem bez wygranej.";
        String plain = outcome.mult() > 0 ? "Trzy " + NAMES[winSym] + "!" : "Pudło, tym razem bez wygranej.";
        deferAnnounce(() -> announce(settle, bet, plain));
        Fx.sound(player, org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        redraw();
    }

    private void scratch(int cell) {
        if (!ticket || scratched[cell]) return;
        scratched[cell] = true;
        Fx.sound(player, org.bukkit.Sound.BLOCK_GRAVEL_BREAK, 0.8f, 1.2f);
        afterScratch();
    }

    private void scratchAll() {
        if (!ticket) return;
        for (int i = 0; i < 9; i++) scratched[i] = true;
        Fx.sound(player, org.bukkit.Sound.BLOCK_GRAVEL_BREAK, 1f, 0.8f);
        afterScratch();
    }

    private void afterScratch() {
        if (allScratched() && busy) {
            busy = false;
            shownWallet = -1;
            runAnnounce();
        }
        redraw();
    }

}
