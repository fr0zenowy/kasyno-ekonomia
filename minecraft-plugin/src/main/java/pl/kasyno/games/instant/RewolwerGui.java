package pl.kasyno.games.instant;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rosyjska ruletka: 1-5 naboi w sześciu komorach, im więcej, tym większa wypłata za przeżycie (do x5,76). */
public final class RewolwerGui extends GameGui {
    private static final int[] CHAMBERS = {20, 21, 22, 23, 24, 25};

    private int bullets;
    private int highlight = -1;
    private Set<Integer> loaded = new HashSet<>();
    private int fired = -1;
    private boolean revealed;
    private ItemStack last;

    public RewolwerGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "rewolwer");
        bullets = Integer.parseInt(choice("bullets", "1"));
    }

    @Override
    protected Component title() {
        return T.c("<dark_red><bold>Rewolwer");
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        for (int i = 1; i <= 5; i++) {
            final int n = i;
            boolean on = bullets == i;
            set(9 + i + 1, ItemBuilder.of(Material.GUNPOWDER).amount(i).name((on ? "<green><bold>" : "<white>") + i + " " + (i == 1 ? "nabój" : "naboje")).glow(on)
                    .lore("<gray>Szansa przeżycia: <white>" + Math.round((6 - i) / 6.0 * 100) + "%", "<gray>Wypłata: <gold>" + Fmt.mult(Games.revolverMult(i)), on ? "<green>Wybrane" : "<yellow>Kliknij").build(), t -> pick(n));
        }
        for (int c = 0; c < 6; c++) {
            boolean hi = highlight == c;
            ItemStack item;
            if (revealed) {
                boolean bullet = loaded.contains(c);
                boolean shot = fired == c;
                item = ItemBuilder.of(bullet ? Material.RED_CONCRETE : Material.LIGHT_GRAY_CONCRETE).name(bullet ? "<red><bold>Nabój" : "<gray>Pusta komora").glow(shot)
                        .lore(shot ? "<yellow>Tu padł strzał" : "").build();
            } else {
                item = ItemBuilder.of(hi ? Material.YELLOW_CONCRETE : Material.GRAY_CONCRETE).name(hi ? "<yellow><bold>▶ Komora " + (c + 1) : "<gray>Komora " + (c + 1)).glow(hi).build();
            }
            set(CHAMBERS[c], item);
        }
        set(13, ItemBuilder.of(Material.IRON_NUGGET).name("<gray>Bęben").lore("<gray>Naboje: <white>" + bullets + "<gray>/6").build());
        if (last != null) set(4, last);
        betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Pociągnij za spust").glow(!busy).lore("<gray>Naboje: <white>" + bullets, "<gray>Wypłata za przeżycie: <gold>" + Fmt.money((long) Math.floor(stake * Games.revolverMult(bullets)))).build(), t -> play(),
                ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Zwrot 96%. Pięć naboi to x5,76.").build());
    }

    private void pick(int n) {
        if (busy) return;
        bullets = n;
        saveChoice("bullets", String.valueOf(n));
        revealed = false;
        highlight = -1;
        Fx.click(player);
        redraw();
    }

    private void play() {
        if (busy || !takeStake()) return;
        busy = true;
        last = null;
        revealed = false;
        List<Integer> all = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5));
        Collections.shuffle(all, new java.util.Random(eco().rng.nextLong()));
        loaded = new HashSet<>(all.subList(0, bullets));
        fired = eco().rng.nextInt(6);
        boolean dead = loaded.contains(fired);
        double mult = Games.revolverMult(bullets);
        long stakeNow = stake;
        Eco.Settle s = eco().settle(id, stakeNow, dead ? 0 : (long) Math.floor(stakeNow * mult));
        String text = dead ? "BANG! Niestety..." : "Klik. Przeżyłeś!";
        deferAnnounce(() -> announce(s, stakeNow, text));
        // wirowanie bębna: podświetlenie krąży po komorach i zwalnia na komorze strzału
        int steps = 18 + Math.floorMod(fired - (18 % 6), 6);
        int[] delays = slowing(steps, 2, 7);
        sequence(delays, k -> {
            highlight = (k + 1) % 6;
            if (k == delays.length - 1) highlight = fired;
            redraw();
            Fx.tick(player, 0.8f + 0.03f * k);
        }, () -> {
            highlight = fired;
            revealed = true;
            Fx.sound(player, dead ? Sound.ENTITY_GENERIC_EXPLODE : Sound.BLOCK_DISPENSER_FAIL, 0.8f, dead ? 1.2f : 1.6f);
            last = resultItem(s, stakeNow, "<white>" + text);
            endRound();
        });
    }
}
