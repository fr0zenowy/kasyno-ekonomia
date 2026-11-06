package pl.kasyno.games.instant;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Games;
import pl.kasyno.games.GameGui;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

/** Dwie kości: poniżej, powyżej albo dokładnie 7. Wartość kości to liczba przedmiotów w stosie. */
public final class KosciGui extends GameGui {
    private int d1 = 1;
    private int d2 = 1;
    private int sum;
    private ItemStack last;
    private String bet;

    public KosciGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "kosci");
        bet = choice("bet", "nizej");
    }

    @Override
    protected Component title() {
        return T.c("<green><bold>Kości");
    }

    private ItemStack die(int v, boolean glow) {
        return ItemBuilder.of(Material.WHITE_CONCRETE).amount(v).name("<white><bold>Kość: " + v).glow(glow).build();
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        set(20, die(d1, last != null));
        set(24, die(d2, last != null));
        set(22, ItemBuilder.of(Material.PAPER).name("<yellow><bold>Suma: " + (sum == 0 ? "?" : sum)).build());
        int[] slots = {29, 31, 33};
        for (int i = 0; i < Games.DICE.size(); i++) {
            Games.DiceBet b = Games.DICE.get(i);
            boolean on = b.id().equals(bet);
            set(slots[i], ItemBuilder.of(i == 0 ? Material.RED_DYE : i == 1 ? Material.LIME_DYE : Material.YELLOW_DYE).name((on ? "<green><bold>" : "<white>") + b.label()).glow(on)
                    .lore("<gray>Wypłata: <gold>" + Fmt.mult(b.mult()), on ? "<green>Wybrane" : "<yellow>Kliknij, aby wybrać").build(), t -> pick(b.id()));
        }
        if (last != null) set(4, last);
        Games.DiceBet cur = Games.DICE.stream().filter(b -> b.id().equals(bet)).findFirst().orElseThrow();
        betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Rzuć kośćmi!").glow(!busy).lore("<gray>Stawiasz: <white>" + cur.label(), "<gray>Wygrana: <gold>" + Fmt.money((long) Math.floor(stake * cur.mult()))).build(), t -> play(),
                ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Poniżej 7 i powyżej 7: x2,3", "<gray>Dokładnie 7: x5,7", "<gray>Zwrot ok. 96%.").build());
    }

    private void pick(String id) {
        if (busy) return;
        bet = id;
        saveChoice("bet", id);
        Fx.click(player);
        redraw();
    }

    private void play() {
        if (busy || !takeStake()) return;
        busy = true;
        last = null;
        sum = 0;
        Games.DiceBet cur = Games.DICE.stream().filter(b -> b.id().equals(bet)).findFirst().orElseThrow();
        int f1 = eco().randInt(1, 6);
        int f2 = eco().randInt(1, 6);
        boolean won = cur.wins(f1 + f2);
        long stakeNow = stake;
        Eco.Settle s = eco().settle(id, stakeNow, won ? (long) Math.floor(stakeNow * cur.mult()) : 0);
        String text = "Wypadło " + f1 + " + " + f2 + " = " + (f1 + f2) + ".";
        deferAnnounce(() -> announce(s, stakeNow, text));
        int[] delays = slowing(12, 2, 6);
        sequence(delays, k -> {
            d1 = k == delays.length - 1 ? f1 : eco().randInt(1, 6);
            d2 = k == delays.length - 1 ? f2 : eco().randInt(1, 6);
            set(20, die(d1, false));
            set(24, die(d2, false));
            Fx.tick(player, 0.9f + 0.05f * k);
        }, () -> {
            d1 = f1;
            d2 = f2;
            sum = f1 + f2;
            last = resultItem(s, stakeNow, "<white>" + text);
            endRound();
        });
    }
}
