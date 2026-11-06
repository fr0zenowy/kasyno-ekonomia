package pl.kasyno.prison;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.ItemBuilder;

import java.util.List;

/** „Sekwencja”: cztery kolorowe przyciski mrugają w pewnej kolejności, a Ty powtarzasz ją po strażniku. */
public final class SimonGui extends PrisonGame {
    static final int LENGTH = 5;
    private static final int[] SLOTS = {20, 24, 38, 42};
    private static final Material[] LIT = {Material.RED_CONCRETE, Material.BLUE_CONCRETE, Material.LIME_CONCRETE, Material.YELLOW_CONCRETE};
    private static final Material[] DIM = {Material.RED_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE};
    private static final String[] NAMES = {"<red>Czerwony", "<blue>Niebieski", "<green>Zielony", "<yellow>Żółty"};
    private static final float[] PITCH = {0.7f, 0.9f, 1.2f, 1.5f};
    private static final int STEP_TICKS = 18;
    private static final int LIGHT_TICKS = 11;

    private enum Phase { WATCH, REPEAT, WON, LOST }

    private final SimonRound round;
    private Phase phase = Phase.WATCH;
    private int litColor = -1;

    public SimonGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
        this.round = new SimonRound(LENGTH, 4, plugin.eco().rng);
    }

    SimonRound round() {
        return round;
    }

    @Override
    protected String heading() {
        return "Sekwencja";
    }

    @Override
    public void open() {
        super.open();
        List<Integer> seq = round.sequence();
        for (int i = 0; i < seq.size(); i++) {
            final int color = seq.get(i);
            later(24L + (long) i * STEP_TICKS, () -> flash(color));
        }
        later(24L + (long) seq.size() * STEP_TICKS + 4, () -> {
            phase = Phase.REPEAT;
            Fx.actionBar(player, "<yellow>Twoja kolej: powtórz kolejność.");
            redraw();
        });
    }

    private void flash(int color) {
        litColor = color;
        Fx.sound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, PITCH[color]);
        redraw();
        later(LIGHT_TICKS, () -> {
            if (litColor == color) {
                litColor = -1;
                redraw();
            }
        });
    }

    private void press(int color) {
        if (phase != Phase.REPEAT) return;
        SimonRound.Result r = round.input(color);
        if (r == SimonRound.Result.WRONG) {
            phase = Phase.LOST;
            failRound();
            Fx.lose(player);
        } else if (r == SimonRound.Result.DONE) {
            phase = Phase.WON;
            Fx.win(player);
            pass();
        } else flash(color);
        redraw();
    }

    @Override
    protected void render() {
        for (int c = 0; c < 4; c++) {
            final int color = c;
            boolean on = litColor == c || phase == Phase.WON;
            ItemBuilder b = ItemBuilder.of(on ? LIT[c] : DIM[c]).name(NAMES[c]).hidden();
            if (on) b.glow();
            set(SLOTS[c], b.build(), phase == Phase.REPEAT ? t -> press(color) : null);
        }
        switch (phase) {
            case WATCH -> set(22, ItemBuilder.of(Material.ENDER_EYE).name("<yellow><bold>Patrz uważnie").lore("<gray>Zapamiętaj " + round.length() + " kolorów.").build());
            case REPEAT -> set(22, ItemBuilder.of(Material.NOTE_BLOCK).name("<green><bold>Teraz Ty").lore("<gray>Wpisano: <white>" + round.entered() + "/" + round.length()).glow().build());
            case WON -> set(22, ItemBuilder.of(Material.EMERALD_BLOCK).name("<green><bold>Zamek otwarty!").lore("<gray>Zadanie zaliczone.").glow().build());
            case LOST -> set(22, ItemBuilder.of(Material.REDSTONE_BLOCK).name("<red><bold>Pomyłka!").lore("<gray>Klik, żeby spróbować jeszcze raz.").build(), t -> go(new SimonGui(plugin, player)));
        }
        hubButton(49);
        fill(ItemBuilder.filler());
    }
}
