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

import java.util.Locale;

/** Rzut na procent: sam wybierasz szansę (0,5-95%), a wypłata rośnie, gdy szansa maleje. Wygrywa wynik poniżej albo powyżej progu. */
public final class SzansaGui extends GameGui {
    private static final double[] PRESETS = {1, 5, 10, 25, 50, 75, 95};
    private static final int[] BAR = {27, 28, 29, 30, 31, 32, 33, 34, 35};

    private double chance;
    private boolean below;
    private double rolled = -1;
    private ItemStack last;

    public SzansaGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "szansa");
        chance = Double.parseDouble(choice("chance", "50"));
        below = choice("mode", "ponizej").equals("ponizej");
    }

    @Override
    protected Component title() {
        return T.c("<blue><bold>Szansa");
    }

    private boolean wins(double value) {
        return below ? value < chance : value >= 100 - chance;
    }

    private String fmtChance(double c) {
        return (c == Math.floor(c) ? String.valueOf((long) c) : String.format(Locale.ROOT, "%.2f", c)) + "%";
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < PRESETS.length; i++) {
            double p = PRESETS[i];
            boolean on = Math.abs(p - chance) < 1e-9;
            set(slots[i], ItemBuilder.of(Material.PAPER).amount((int) Math.max(1, Math.min(64, p))).name((on ? "<green><bold>" : "<white>") + fmtChance(p)).glow(on)
                    .lore("<gray>Wypłata: <gold>" + Fmt.mult(Games.chanceMult(p)), on ? "<green>Wybrane" : "<yellow>Kliknij").build(), t -> setChance(p));
        }
        set(19, ItemBuilder.of(Material.NAME_TAG).name("<white><bold>Własna szansa").lore("<gray>Wpisz od 0,5 do 95.", "<yellow>Kliknij").build(), t -> ask());
        set(25, ItemBuilder.of(below ? Material.ARROW : Material.SPECTRAL_ARROW).name("<yellow><bold>Wygrywa wynik " + (below ? "poniżej" : "powyżej"))
                .lore("<gray>Próg: <white>" + (below ? fmtChance(chance) : fmtChance(100 - chance)), "<yellow>Kliknij, aby zmienić tryb").build(), t -> toggle());
        set(22, ItemBuilder.of(Material.COMPARATOR).name("<aqua><bold>Szansa " + fmtChance(chance) + " <gray>• <gold>" + Fmt.mult(Games.chanceMult(chance)))
                .lore("<gray>Wygrana: <gold>" + Fmt.money((long) Math.floor(stake * Games.chanceMult(chance))), rolled >= 0 ? "<gray>Wynik: <white>" + String.format(Locale.ROOT, "%.2f", rolled) : "").build());
        for (int i = 0; i < 9; i++) {
            double center = (i + 0.5) * 100 / 9;
            boolean win = wins(center);
            boolean marker = rolled >= 0 && Math.min(8, (int) (rolled / (100.0 / 9))) == i;
            set(BAR[i], ItemBuilder.of(marker ? Material.WHITE_STAINED_GLASS_PANE : win ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE)
                    .name(marker ? "<white><bold>▼ " + String.format(Locale.ROOT, "%.2f", rolled) : win ? "<green>Wygrana" : "<red>Przegrana").glow(marker).build());
        }
        if (last != null) set(4, last);
        betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Rzuć!").glow(!busy).lore("<gray>Szansa: <white>" + fmtChance(chance), "<gray>Wypłata: <gold>" + Fmt.mult(Games.chanceMult(chance))).build(), t -> play(),
                ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Zwrot 96% przy każdej szansie.", "<gray>Mniejsza szansa = większa wypłata.", "<gray>Do x192 przy szansie 0,5%.").build());
    }

    private void setChance(double c) {
        if (busy) return;
        chance = Math.max(Games.CHANCE_MIN, Math.min(Games.CHANCE_MAX, Math.round(c * 100) / 100.0));
        saveChoice("chance", String.valueOf(chance));
        Fx.click(player);
        redraw();
    }

    private void toggle() {
        if (busy) return;
        below = !below;
        saveChoice("mode", below ? "ponizej" : "powyzej");
        Fx.click(player);
        redraw();
    }

    private void ask() {
        if (busy) return;
        plugin.prompts().ask(player, "<white>Jaka szansa na wygraną? <gray>(0,5 do 95)", text -> {
            try {
                double v = Double.parseDouble(text.trim().replace(',', '.').replace("%", ""));
                if (v < Games.CHANCE_MIN || v > Games.CHANCE_MAX) throw new NumberFormatException();
                saveChoice("chance", String.valueOf(Math.round(v * 100) / 100.0));
            } catch (NumberFormatException e) {
                T.send(player, "<red>Szansa musi być liczbą od 0,5 do 95.");
            }
            reopen();
        }, this::reopen);
    }

    private void play() {
        if (busy || !takeStake()) return;
        busy = true;
        last = null;
        rolled = -1;
        double roll = Math.floor(eco().rng.nextDouble() * 10000) / 100;
        boolean won = wins(roll);
        double mult = Games.chanceMult(chance);
        long stakeNow = stake;
        Eco.Settle s = eco().settle(id, stakeNow, won ? (long) Math.floor(stakeNow * mult) : 0);
        String text = "Wynik: " + String.format(Locale.ROOT, "%.2f", roll) + (won ? " (trafione)." : " (pudło).");
        deferAnnounce(() -> announce(s, stakeNow, text));
        int[] delays = slowing(14, 2, 6);
        sequence(delays, k -> {
            rolled = k == delays.length - 1 ? roll : eco().rng.nextDouble() * 100;
            redraw();
            Fx.tick(player, 0.9f + 0.04f * k);
        }, () -> {
            rolled = roll;
            last = resultItem(s, stakeNow, "<white>" + text);
            endRound();
        });
    }
}
