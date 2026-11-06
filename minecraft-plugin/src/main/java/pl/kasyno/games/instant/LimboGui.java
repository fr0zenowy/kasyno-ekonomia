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

/** Limbo: wybierasz cel (x1,01 do x1000), a rakieta wspina się do wylosowanego wyniku. Jeśli go dobije, wygrywasz cel × stawka. */
public final class LimboGui extends GameGui {
    private static final double[] PRESETS = {1.5, 2, 3, 5, 10, 25, 100, 1000};

    private double target;
    private double shown = 1;
    private boolean done;
    private ItemStack last;

    public LimboGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "limbo");
        target = Double.parseDouble(choice("target", "2"));
    }

    @Override
    protected Component title() {
        return T.c("<dark_purple><bold>Limbo");
    }

    private static String f(double v) {
        return v >= 1000 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.2f", v);
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19};
        for (int i = 0; i < PRESETS.length; i++) {
            double p = PRESETS[i];
            boolean on = Math.abs(p - target) < 1e-9;
            set(slots[i], ItemBuilder.of(Material.FIREWORK_ROCKET).name((on ? "<green><bold>" : "<white>") + "Cel " + Fmt.mult(p)).glow(on)
                    .lore("<gray>Szansa: <white>" + String.format(Locale.ROOT, "%.3f", 96 / p) + "%", on ? "<green>Wybrane" : "<yellow>Kliknij").build(), t -> setTarget(p));
        }
        set(25, ItemBuilder.of(Material.NAME_TAG).name("<white><bold>Własny cel").lore("<gray>Wpisz od 1,01 do 1000.", "<yellow>Kliknij").build(), t -> ask());
        boolean reached = shown >= target;
        set(22, ItemBuilder.of(done ? (reached ? Material.LIME_CONCRETE : Material.RED_CONCRETE) : Material.FIREWORK_ROCKET).name((reached ? "<green>" : "<white>") + "<bold>x" + f(shown))
                .glow(done || reached).lore("<gray>Cel: <gold>" + Fmt.mult(target), "<gray>Szansa: <white>" + String.format(Locale.ROOT, "%.3f", 96 / target) + "%").build());
        // wskaźnik wspinania: rakiety rosną w górę rzędu
        int lit = (int) Math.max(0, Math.min(9, Math.round(Math.log(Math.max(1, shown)) / Math.log(Math.max(1.0001, Math.min(1000, Math.max(target, 2)))) * 9)));
        for (int i = 0; i < 9; i++) set(27 + i, ItemBuilder.of(i < lit ? (shown >= target ? Material.LIME_STAINED_GLASS_PANE : Material.ORANGE_STAINED_GLASS_PANE) : Material.GRAY_STAINED_GLASS_PANE).name(i < lit ? "<yellow>▲" : " ").build());
        if (last != null) set(4, last);
        betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Start!").glow(!busy).lore("<gray>Cel: <gold>" + Fmt.mult(target), "<gray>Wygrana: <gold>" + Fmt.money((long) Math.floor(stake * target))).build(), t -> play(),
                ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Szansa na wygraną to 96% / cel.", "<gray>Zwrot 96% przy każdym celu.", "<gray>Cel do x1000.").build());
    }

    private void setTarget(double t) {
        if (busy) return;
        target = t;
        saveChoice("target", String.valueOf(t));
        Fx.click(player);
        redraw();
    }

    private void ask() {
        if (busy) return;
        plugin.prompts().ask(player, "<white>Jaki cel? <gray>(od 1,01 do 1000)", text -> {
            try {
                double v = Double.parseDouble(text.trim().toLowerCase().replace(',', '.').replace("x", ""));
                if (v < Games.LIMBO_MIN || v > Games.LIMBO_MAX) throw new NumberFormatException();
                saveChoice("target", String.valueOf(Math.round(v * 100) / 100.0));
            } catch (NumberFormatException e) {
                T.send(player, "<red>Cel musi być liczbą od 1,01 do 1000.");
            }
            reopen();
        }, this::reopen);
    }

    private void play() {
        if (busy || !takeStake()) return;
        busy = true;
        done = false;
        last = null;
        shown = 1;
        double result = Games.limboRoll(eco().rng);
        boolean won = result >= target;
        long stakeNow = stake;
        Eco.Settle s = eco().settle(id, stakeNow, won ? (long) Math.floor(stakeNow * target) : 0);
        String text = "Wynik: x" + f(result) + (won ? " (cel osiągnięty)." : " (rakieta nie doleciała).");
        deferAnnounce(() -> announce(s, stakeNow, text));
        int frames = 22;
        int[] delays = slowing(frames, 2, 5);
        sequence(delays, k -> {
            double t = (k + 1) / (double) frames;
            double eased = 1 - Math.pow(1 - t, 2.2);
            shown = Math.pow(result, eased);
            redraw();
            Fx.tick(player, 0.7f + (float) Math.min(1.3, eased * 1.3));
        }, () -> {
            shown = result;
            done = true;
            last = resultItem(s, stakeNow, "<white>" + text);
            endRound();
        });
    }
}
