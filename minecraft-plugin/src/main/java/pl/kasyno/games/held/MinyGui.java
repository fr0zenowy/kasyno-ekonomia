package pl.kasyno.games.held;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Games;
import pl.kasyno.games.HeldGameGui;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Miny: odkrywaj diamenty na planszy 4x5, omijaj miny i wypłać w odpowiednim momencie. Tarcza anuluje pierwszą trafioną minę. */
public final class MinyGui extends HeldGameGui {
    private static final int ROWS = 4;
    private static final int COLS = 5;

    private int mineCount;
    private Set<Integer> mines = new HashSet<>();
    private final Set<Integer> revealed = new HashSet<>();
    private final Set<Integer> blocked = new HashSet<>();
    private boolean started;
    private boolean won;
    private String note = "";
    private long bet;

    public MinyGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "miny");
        mineCount = Integer.parseInt(choice("mines", "3"));
    }

    @Override
    protected Component title() {
        return T.c("<red><bold>Miny");
    }

    private static int slotOf(int idx) {
        return (1 + idx / COLS) * 9 + 2 + idx % COLS;
    }

    private long payout() {
        return (long) Math.floor(bet * Games.minesMultiplier(revealed.size(), mineCount));
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        int safeTotal = Games.MINES_TILES - mineCount;
        for (int i = 0; i < Games.MINES_TILES; i++) {
            final int idx = i;
            int slot = slotOf(i);
            if (!started) set(slot, ItemBuilder.of(Material.GRAY_CONCRETE).name("<gray>?").lore("<gray>Wybierz liczbę min i zacznij grę").build());
            else if (revealed.contains(i)) set(slot, ItemBuilder.of(Material.DIAMOND).name("<aqua><bold>Diament").glow().build());
            else if (blocked.contains(i)) set(slot, ItemBuilder.of(Material.SHIELD).name("<aqua>Zablokowana mina").lore("<gray>Tarcza ją unieszkodliwiła").build());
            else if (over && mines.contains(i)) set(slot, ItemBuilder.of(Material.TNT).name("<red><bold>Mina").build());
            else if (over) set(slot, ItemBuilder.of(Material.LIGHT_GRAY_CONCRETE).name("<gray>Bezpieczne pole").build());
            else set(slot, ItemBuilder.of(Material.GRAY_CONCRETE).name("<white><bold>Odkryj").lore("<yellow>Kliknij").build(), t -> reveal(idx));
        }
        // liczba min
        set(9, ItemBuilder.of(Material.TNT).amount(mineCount).name("<red><bold>Min: " + mineCount).lore("<gray>Więcej min = większe mnożniki", "<gray>Pierwszy diament: <gold>" + Fmt.mult(Games.minesMultiplier(1, mineCount))).build());
        if (!started) {
            set(18, ItemBuilder.of(Material.LIME_DYE).name("<green>+1 mina").build(), t -> setMines(mineCount + 1));
            set(27, ItemBuilder.of(Material.RED_DYE).name("<red>-1 mina").build(), t -> setMines(mineCount - 1));
        }
        // status
        long cash = started && !over ? payout() : 0;
        double mult = Games.minesMultiplier(revealed.size(), mineCount);
        String next = started && !over && revealed.size() < safeTotal ? "<gray>Następne pole: <gold>" + Fmt.mult(Games.minesMultiplier(revealed.size() + 1, mineCount)) : "";
        List<String> lore = new ArrayList<>(List.of("<gray>Odkryto: <white>" + revealed.size() + "<gray>/" + safeTotal, "<gray>Mnożnik: <gold>" + Fmt.mult(mult), "<gray>Do wypłaty: <gold>" + Fmt.money(cash), next));
        if (started && !over) lore.add(protectionLine());
        if (!note.isEmpty()) lore.add(note);
        set(4, ItemBuilder.of(over ? (won ? Material.LIME_CONCRETE : Material.RED_CONCRETE) : Material.CHEST).name("<yellow><bold>Miny").lore(lore).build());

        if (!started) {
            betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Zacznij grę").glow().lore("<gray>Stawka: <gold>" + Fmt.money(stake), "<gray>Min: <white>" + mineCount).build(), t -> start(),
                    ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Odkrywaj diamenty, nie klikaj w miny.", "<gray>Wypłać w dowolnej chwili.", "<gray>Zwrot do 97%.").build());
        } else if (!over) {
            betBar(ItemBuilder.of(Material.GOLD_INGOT).name(revealed.isEmpty() ? "<gray>Wypłać (odkryj najpierw pole)" : "<gold><bold>Wypłać " + Fmt.money(cash)).glow(!revealed.isEmpty()).build(), t -> cashOut(),
                    ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Trafiona mina kończy grę.").build());
        } else {
            betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Zagraj ponownie").build(), t -> go(new MinyGui(plugin, player)), null);
        }
    }

    private String protectionLine() {
        List<Catalog.Protection> shield = eco().armed(id, "tarcza", bet);
        List<Catalog.Protection> pol = eco().armed(id, "polisa", bet);
        String s = "";
        if (!pol.isEmpty()) s += "<aqua>" + plugin.catalog().item(pol.get(0).id()).name() + " <gray>(zwrot stawki) ";
        if (!shield.isEmpty()) s += "<aqua>" + plugin.catalog().item(shield.get(0).id()).name() + " <gray>(pierwszy błąd się nie liczy)";
        return s;
    }

    private void setMines(int n) {
        if (started) return;
        mineCount = Math.max(1, Math.min(12, n));
        saveChoice("mines", String.valueOf(mineCount));
        Fx.click(player);
        redraw();
    }

    private void start() {
        if (started || !takeStake()) return;
        started = true;
        busy = true;
        bet = stake;
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < Games.MINES_TILES; i++) all.add(i);
        Collections.shuffle(all, new java.util.Random(eco().rng.nextLong()));
        mines = new HashSet<>(all.subList(0, mineCount));
        touch();
        Fx.sound(player, Sound.BLOCK_CHEST_OPEN, 0.8f, 1.2f);
        redraw();
    }

    private void reveal(int idx) {
        if (!started || over || revealed.contains(idx) || blocked.contains(idx)) return;
        touch();
        note = "";
        if (mines.contains(idx)) {
            var shield = eco().consume(id, "tarcza", bet);
            if (shield.isPresent()) {
                blocked.add(idx);
                note = "<aqua>" + plugin.catalog().item(shield.get().id()).name() + " zablokowała minę!";
                Fx.sound(player, Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
                T.send(player, "<aqua>" + plugin.catalog().item(shield.get().id()).name() + " zablokowała minę! Grasz dalej.");
                redraw();
                return;
            }
            Fx.sound(player, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
            won = false;
            finishGame(0, "BOOM! Trafiłeś na minę.");
            redraw();
            return;
        }
        revealed.add(idx);
        Fx.ding(player, 0.8f + 0.04f * revealed.size());
        if (revealed.size() == Games.MINES_TILES - mineCount) {
            won = true;
            finishGame(payout(), "Odkryłeś wszystkie diamenty!");
        }
        redraw();
    }

    private void cashOut() {
        if (!started || over || revealed.isEmpty()) {
            Fx.deny(player);
            return;
        }
        won = true;
        finishGame(payout(), "Wypłacasz wygraną w porę!");
        redraw();
    }

    @Override
    protected void abandon(boolean idle) {
        if (!started || over) return;
        if (revealed.isEmpty()) refundGame();
        else {
            won = true;
            finishGame(payout(), "Koniec gry: wygrana wypłacona automatycznie.");
        }
    }
}
