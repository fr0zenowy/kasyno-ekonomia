package pl.kasyno.prison;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.ItemBuilder;

/** „Refleks”: zapalają się klucze w siatce okna, trzeba trafić w każdy, zanim zgaśnie. */
public final class ReflexGui extends PrisonGame {
    static final int HITS = 6;
    static final int MAX_MISSES = 2;
    static final int CELLS = 15;
    private static final long PAUSE_TICKS = 8;

    private final ReflexRound round;
    private boolean lit;
    private int token;

    public ReflexGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
        this.round = new ReflexRound(HITS, MAX_MISSES, CELLS, plugin.eco().rng);
    }

    /** Pole siatki w oknie: trzy rzędy po pięć, pośrodku. */
    static int slotOf(int cell) {
        return 9 * (1 + cell / 5) + 2 + cell % 5;
    }

    ReflexRound round() {
        return round;
    }

    @Override
    protected String heading() {
        return "Refleks";
    }

    @Override
    public void open() {
        super.open();
        later(20, this::light);
    }

    private void light() {
        if (finished()) return;
        lit = true;
        int mine = ++token;
        Fx.sound(player, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.4f);
        redraw();
        later(round.windowTicks(), () -> {
            if (mine != token || !lit || finished()) return;
            Fx.sound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
            settle(round.miss());
        });
    }

    private void pick(int cell) {
        if (!lit || finished()) return;
        token++;
        boolean good = cell == round.target();
        Fx.sound(player, good ? Sound.ENTITY_EXPERIENCE_ORB_PICKUP : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, good ? 1.2f : 0.6f);
        settle(round.hit(cell));
    }

    private void settle(ReflexRound.State s) {
        lit = false;
        if (s == ReflexRound.State.WON) {
            Fx.win(player);
            pass();
        } else if (s == ReflexRound.State.LOST) {
            failRound();
            Fx.lose(player);
        } else later(PAUSE_TICKS, this::light);
        redraw();
    }

    @Override
    protected void render() {
        ReflexRound.State s = round.state();
        set(4, ItemBuilder.of(Material.CLOCK).name("<yellow><bold>Refleks")
                .lore("<gray>Trafienia: <white>" + round.hits() + "/" + round.hitsToWin(), "<gray>Błędy: <white>" + round.misses() + "/" + (round.maxMisses() + 1) + " <gray>(przy ostatnim przegrywasz)").build());
        for (int i = 0; i < CELLS; i++) {
            final int cell = i;
            boolean target = lit && s == ReflexRound.State.RUNNING && i == round.target();
            if (target) set(slotOf(i), ItemBuilder.of(Material.TRIPWIRE_HOOK).name("<green><bold>TERAZ!").glow().build(), t -> pick(cell));
            else if (lit && s == ReflexRound.State.RUNNING) set(slotOf(i), ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name("<dark_gray>·").hidden().build(), t -> pick(cell));
            else set(slotOf(i), ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name("<dark_gray>·").hidden().build());
        }
        if (s == ReflexRound.State.WON) set(40, ItemBuilder.of(Material.EMERALD_BLOCK).name("<green><bold>Zamek otwarty!").lore("<gray>Zadanie zaliczone.").glow().build());
        else if (s == ReflexRound.State.LOST) {
            set(40, ItemBuilder.of(Material.REDSTONE_BLOCK).name("<red><bold>Za wolno!").lore("<gray>Klik, żeby spróbować jeszcze raz.").build(), t -> go(new ReflexGui(plugin, player)));
        } else set(40, ItemBuilder.of(Material.PAPER).name("<gray>Klikaj świecący klucz").hidden().build());
        hubButton(49);
        fill(ItemBuilder.filler());
    }
}
