package pl.kasyno.games.instant;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Games;
import pl.kasyno.games.GameGui;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

/** Kamień, papier, nożyce z botem: klikasz swój ruch, bot losuje, wygrana x1,9, remis zwraca stawkę. */
public final class KnpGui extends GameGui {
    private static final String[] NAMES = {"Kamień", "Papier", "Nożyce"};
    private static final Material[] ICONS = {Material.STONE, Material.PAPER, Material.SHEARS};

    private int mine = -1;
    private int bot = -1;
    private ItemStack last;

    public KnpGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "knp");
    }

    @Override
    protected Component title() {
        return T.c("<aqua><bold>Kamień, papier, nożyce");
    }

    private ItemStack icon(int i, boolean glow, String prefix) {
        return ItemBuilder.of(ICONS[i]).name(prefix + "<bold>" + NAMES[i]).glow(glow).build();
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        for (int i = 0; i < 3; i++) {
            final int m = i;
            set(19 + i * 2, ItemBuilder.of(ICONS[i]).name("<yellow><bold>" + NAMES[i]).glow(mine == i).lore(busy ? "<gray>Trwa runda…" : "<green>Kliknij, aby zagrać (stawka " + pl.kasyno.core.Fmt.money(stake) + ")").build(), t -> play(m));
        }
        set(13, bot >= 0 ? icon(bot, true, "<red>Bot: ") : ItemBuilder.of(Material.BARRIER).name("<gray>Bot wybiera…").build());
        if (last != null) set(4, last);
        betBar(ItemBuilder.of(Material.GRAY_DYE).name("<gray>Wybierz ruch powyżej").lore("<gray>Wygrana x1,9, remis zwraca stawkę.").build(), null,
                ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Kamień bije nożyce, nożyce papier,", "<gray>papier kamień. Wygrana x1,9.").build());
    }

    private void play(int choice) {
        if (busy || !takeStake()) return;
        busy = true;
        last = null;
        mine = choice;
        int b = eco().rng.nextInt(3);
        long payout;
        String text;
        // 0 kamień, 1 papier, 2 nożyce: i bije (i+2)%3
        if (b == choice) {
            payout = stake;
            text = "Remis!";
        } else if ((choice + 2) % 3 == b) {
            payout = (long) Math.floor(stake * Games.RPS_MULT);
            text = "Twój ruch wygrywa!";
        } else {
            payout = 0;
            text = "Bot wygrywa tę rundę.";
        }
        long stakeNow = stake;
        Eco.Settle s = eco().settle(id, stakeNow, payout);
        String full = NAMES[choice] + " vs " + NAMES[b] + ". " + text;
        deferAnnounce(() -> announce(s, stakeNow, full));
        int[] delays = slowing(10, 3, 7);
        sequence(delays, k -> {
            bot = k == delays.length - 1 ? b : k % 3;
            redraw();
            Fx.tick(player, 0.9f + 0.05f * k);
        }, () -> {
            bot = b;
            last = resultItem(s, stakeNow, "<white>" + full);
            endRound();
        });
    }
}
