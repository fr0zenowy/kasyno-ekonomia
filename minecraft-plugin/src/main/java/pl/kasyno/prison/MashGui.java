package pl.kasyno.prison;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.ItemBuilder;

import java.util.function.LongSupplier;

/** „Podkop”: klikaj kilof tak szybko, żeby przebić ścianę, zanim strażnik usłyszy. */
public final class MashGui extends PrisonGame {
    static final int NEEDED = 24;
    static final long WINDOW_MS = 9_000;

    private final MashRound round = new MashRound(NEEDED, WINDOW_MS);
    private final LongSupplier clock;

    public MashGui(KasynoPlugin plugin, Player player) {
        this(plugin, player, () -> plugin.eco().now());
    }

    /** Z własnym zegarem (milisekundy), żeby test mógł sterować upływem czasu. */
    MashGui(KasynoPlugin plugin, Player player, LongSupplier clock) {
        super(plugin, player);
        this.clock = clock;
    }

    @Override
    protected String heading() {
        return "Podkop";
    }

    @Override
    public void open() {
        super.open();
        repeat(10, 10, () -> {
            if (finished()) return;
            if (round.poll(clock.getAsLong()) == MashRound.State.LOST) lose();
            else if (round.state() == MashRound.State.RUNNING) redraw();
        });
    }

    private void click() {
        if (finished()) return;
        MashRound.State s = round.click(clock.getAsLong());
        Fx.sound(player, Sound.BLOCK_STONE_BREAK, 0.5f, 0.7f + (float) round.progress());
        if (s == MashRound.State.WON) {
            Fx.win(player);
            pass();
        } else if (s == MashRound.State.LOST) lose();
        redraw();
    }

    private void lose() {
        failRound();
        Fx.lose(player);
        redraw();
    }

    @Override
    protected void render() {
        int broken = (int) Math.floor(round.progress() * 9);
        for (int i = 0; i < 9; i++) {
            int slot = 18 + i;
            if (i < broken) set(slot, ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).name("<dark_gray>Wykopane").hidden().build());
            else set(slot, ItemBuilder.of(i == broken && round.clicks() > 0 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS).name("<gray>Ściana").hidden().build());
        }
        long left = round.msLeft(clock.getAsLong());
        MashRound.State s = round.state();
        if (s == MashRound.State.WON) {
            set(13, ItemBuilder.of(Material.EMERALD_BLOCK).name("<green><bold>Tunel gotowy!").lore("<gray>Zadanie zaliczone.").glow().build());
        } else if (s == MashRound.State.LOST) {
            set(13, ItemBuilder.of(Material.REDSTONE_BLOCK).name("<red><bold>Strażnik usłyszał!").lore("<gray>Nie zdążyłeś. Spróbuj jeszcze raz.").build());
            set(40, ItemBuilder.of(Material.IRON_PICKAXE).name("<yellow><bold>Kop od nowa").lore("<gray>Nowa próba.").build(), t -> go(new MashGui(plugin, player)));
        } else {
            set(13, ItemBuilder.of(Material.CLOCK).name(s == MashRound.State.WAITING ? "<yellow>Kliknij kilof, żeby zacząć" : "<yellow>Zostało: <white>" + String.format(java.util.Locale.ROOT, "%.1f", left / 1000.0) + " s")
                    .lore("<gray>Uderzeń: <white>" + round.clicks() + "/" + round.needed(), "<gray>Masz " + WINDOW_MS / 1000 + " s od pierwszego uderzenia.").build());
            set(40, ItemBuilder.of(Material.IRON_PICKAXE).name("<yellow><bold>KOP!").lore("<gray>Klikaj szybko.").glow().build(), t -> click());
        }
        hubButton(49);
        fill(ItemBuilder.filler());
    }
}
