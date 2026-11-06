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

/** Rzut monetą: orzeł albo reszka, wypłata x1,95. */
public final class MonetaGui extends GameGui {
    private String side;
    private String face = "orzel";
    private ItemStack last;

    public MonetaGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "moneta");
        side = choice("side", "orzel");
    }

    @Override
    protected Component title() {
        return T.c("<gold><bold>Moneta");
    }

    private static ItemStack coin(String f) {
        return f.equals("orzel") ? ItemBuilder.of(Material.FEATHER).name("<aqua><bold>Orzeł").glow().build() : ItemBuilder.of(Material.GOLD_NUGGET).name("<gold><bold>Reszka").glow().build();
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        set(20, ItemBuilder.of(Material.FEATHER).name("<aqua><bold>Orzeł").glow(side.equals("orzel")).lore("<gray>Postaw na orła", side.equals("orzel") ? "<green>Wybrane" : "<yellow>Kliknij, aby wybrać").build(), t -> pick("orzel"));
        set(24, ItemBuilder.of(Material.GOLD_NUGGET).name("<gold><bold>Reszka").glow(side.equals("reszka")).lore("<gray>Postaw na reszkę", side.equals("reszka") ? "<green>Wybrane" : "<yellow>Kliknij, aby wybrać").build(), t -> pick("reszka"));
        set(22, coin(face));
        if (last != null) set(13, last);
        betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Rzuć monetą!").lore("<gray>Stawiasz na: <white>" + (side.equals("orzel") ? "Orła" : "Reszkę"), "<gray>Wygrana: <gold>" + Fmt.money((long) Math.floor(stake * Games.COIN_PAYOUT))).glow(!busy).build(), t -> play(),
                ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Szansa 50%, wypłata x1,95", "<gray>(zwrot 97,5%).").build());
    }

    private void pick(String s) {
        if (busy) return;
        side = s;
        saveChoice("side", s);
        Fx.click(player);
        redraw();
    }

    private void play() {
        if (busy || !takeStake()) return;
        busy = true;
        last = null;
        String result = eco().chance(0.5) ? "orzel" : "reszka";
        boolean won = result.equals(side);
        long bet = stake;
        Eco.Settle s = eco().settle(id, bet, won ? (long) Math.floor(bet * Games.COIN_PAYOUT) : 0);
        String text = "Wypadło: " + (result.equals("orzel") ? "Orzeł" : "Reszka") + ".";
        deferAnnounce(() -> announce(s, bet, text));
        int[] delays = slowing(14, 2, 7);
        sequence(delays, k -> {
            face = k == delays.length - 1 ? result : (k % 2 == 0 ? "reszka" : "orzel");
            if (k == delays.length - 1 && !face.equals(result)) face = result;
            set(22, coin(face));
            Fx.tick(player, 0.8f + 0.04f * k);
        }, () -> {
            face = result;
            last = resultItem(s, bet, "<white>" + text);
            endRound();
        });
    }
}
