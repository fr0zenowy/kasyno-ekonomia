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
import java.util.List;

/** Jednoręki bandyta: trzy bębny z animacją zatrzymywania, trzy takie same płacą, a czasem pada jackpot x200. */
public final class SlotyGui extends GameGui {
    private static final Material[] ICONS = {Material.SWEET_BERRIES, Material.HONEYCOMB, Material.AMETHYST_SHARD, Material.BELL, Material.DIAMOND, Material.GOLD_BLOCK};
    private static final String[] NAMES = {"Wiśnia", "Cytryna", "Winogrona", "Dzwonek", "Diament", "Siódemka"};
    private static final int JACKPOT = 6;
    private static final int[][] SLOTS = {{12, 21, 30}, {13, 22, 31}, {14, 23, 32}};

    private final int[][] visible = new int[3][3];
    private ItemStack last;
    private boolean winLine;

    public SlotyGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "sloty");
        for (int[] reel : visible) for (int i = 0; i < 3; i++) reel[i] = eco().rng.nextInt(6);
    }

    @Override
    protected Component title() {
        return T.c("<light_purple><bold>Sloty");
    }

    private ItemStack symbol(int idx, boolean glow) {
        if (idx == JACKPOT) return ItemBuilder.of(Material.NETHER_STAR).name("<gold><bold>JACKPOT").glow().build();
        return ItemBuilder.of(ICONS[idx]).name("<yellow>" + NAMES[idx]).glow(glow).build();
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        set(20,ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE).name("<yellow>▶").build());
        set(24, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE).name("<yellow>◀").build());
        drawReels();
        if (last != null) set(4, last);
        List<String> pay = new ArrayList<>();
        Catalog.Slots cfg = plugin.catalog().games.sloty();
        for (int i = cfg.symbols().size() - 1; i >= 0; i--) pay.add("<gray>3x " + NAMES[i] + ": <gold>" + Fmt.mult(cfg.symbols().get(i).triple()));
        pay.add("<gray>2 takie same: <white>zwrot stawki");
        pay.add("<gray>Losowy jackpot (1 na " + Math.round(1 / cfg.jackpot().chance()) + "): <gold>" + Fmt.mult(cfg.jackpot().mult()));
        betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Zakręć!").glow(!busy).lore("<gray>Środkowy rząd to linia wypłat.").build(), t -> play(),
                ItemBuilder.of(Material.BOOK).name("<white>Tabela wypłat").lore(pay).build());
    }

    private void drawReels() {
        for (int r = 0; r < 3; r++) {
            for (int row = 0; row < 3; row++) {
                boolean center = row == 1;
                inv.setItem(SLOTS[r][row], symbol(visible[r][row], center && winLine));
            }
        }
    }

    private int spinSymbol() {
        return eco().rng.nextInt(6);
    }

    private void play() {
        if (busy || !takeStake()) return;
        busy = true;
        last = null;
        winLine = false;
        Catalog.Slots cfg = plugin.catalog().games.sloty();
        boolean jackpot = eco().chance(cfg.jackpot().chance());
        int[] target = new int[3];
        for (int i = 0; i < 3; i++) {
            Catalog.SlotSymbol drawn = Games.weighted(cfg.symbols(), Catalog.SlotSymbol::w, eco().rng);
            target[i] = jackpot ? JACKPOT : cfg.symbols().indexOf(drawn);
        }
        double mult;
        String kind;
        if (jackpot) {
            mult = cfg.jackpot().mult();
            kind = "JACKPOT!";
        } else if (target[0] == target[1] && target[1] == target[2]) {
            mult = cfg.symbols().get(target[0]).triple();
            kind = "Trzy " + NAMES[target[0]] + "!";
        } else if (target[0] == target[1] || target[0] == target[2]) {
            mult = cfg.symbols().get(target[0]).pair();
            kind = "Dwa takie same: stawka wraca.";
        } else if (target[1] == target[2]) {
            mult = cfg.symbols().get(target[1]).pair();
            kind = "Dwa takie same: stawka wraca.";
        } else {
            mult = 0;
            kind = "Nic z tego...";
        }
        long bet = stake;
        Eco.Settle s = eco().settle(id, bet, (long) Math.floor(bet * mult));
        deferAnnounce(() -> announce(s, bet, kind));
        int[] stop = {14, 22, 30};
        int[] delays = even(32, 2);
        sequence(delays, f -> {
            for (int r = 0; r < 3; r++) {
                if (f < stop[r]) {
                    visible[r][2] = visible[r][1];
                    visible[r][1] = visible[r][0];
                    visible[r][0] = spinSymbol();
                } else if (f == stop[r]) {
                    visible[r][0] = spinSymbol();
                    visible[r][1] = target[r];
                    visible[r][2] = spinSymbol();
                    Fx.ding(player, 0.8f + 0.25f * r);
                }
            }
            drawReels();
            if (f < stop[2]) Fx.tick(player, 1.2f);
        }, () -> {
            winLine = mult > 0;
            last = resultItem(s, bet, "<white>" + kind);
            endRound();
        });
    }
}
