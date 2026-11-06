package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.List;
import java.util.UUID;

/**
 * Pojedynek jak na dzikim zachodzie: dwóch rewolwerowców naprzeciw siebie, napięcie, odliczanie 3-2-1, losowa chwila ciszy,
 * STRZAŁ i jeden z nich pada. Wynik jest już rozliczony, a okno tylko go odgrywa (u obu graczy tak samo).
 */
public final class DuelGui extends Gui {
    /** Wynik pojedynku: obaj gracze, zwycięzca, wypłata i stawka. */
    public record Result(UUID a, UUID b, UUID winner, long payout, long stake) {}

    /** Ile trwa scena (ticki). Rooms.accept wysyła wynik na czat dopiero po tym czasie. */
    public static final int DURATION = 20 * 10;

    private static final int PHASE_STAND = 0;
    private static final int PHASE_TENSION = 1;
    private static final int PHASE_COUNT = 2;
    private static final int PHASE_WAIT = 3;
    private static final int PHASE_FIRE = 4;
    private static final int PHASE_DONE = 5;

    private final Result result;
    private int phase = PHASE_STAND;
    private int count;

    public DuelGui(KasynoPlugin plugin, Player player, Result result) {
        super(plugin, player);
        this.result = result;
    }

    @Override
    protected String art() {
        return "pojedynek";
    }

    @Override
    protected Component title() {
        return T.c("<red><bold>Pojedynek");
    }

    /** Losowa cisza przed strzałem (14-39 ticków), taka sama u obu graczy, bo zależy tylko od wyniku. */
    private int waitTicks() {
        return waitTicks(result);
    }

    /** To samo losowanie ciszy dla pojedynku odgrywanego na arenie w świecie. */
    public static int waitTicks(Result result) {
        return 14 + (int) Math.floorMod(result.payout() + result.stake() * 7, 26L);
    }

    @Override
    public void open() {
        super.open();
        Fx.actionBar(player, "<yellow>Stajecie naprzeciw siebie na pustej ulicy...");
        Fx.sound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f);
        later(34, () -> {
            phase = PHASE_TENSION;
            Fx.actionBar(player, "<gray>Dłonie przy kaburach. Nikt nie mruga...");
            Fx.sound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
            redraw();
        });
        later(66, () -> countdown(3));
    }

    private void countdown(int n) {
        phase = PHASE_COUNT;
        count = n;
        Fx.ding(player, 0.7f + 0.2f * (3 - n));
        Fx.actionBar(player, "<gold><bold>" + n + "...");
        redraw();
        if (n > 1) later(18, () -> countdown(n - 1));
        else later(18, () -> {
            phase = PHASE_WAIT;
            Fx.actionBar(player, "<dark_gray>...");
            redraw();
            later(waitTicks(), this::fire);
        });
    }

    private void fire() {
        phase = PHASE_FIRE;
        Fx.sound(player, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 0.7f);
        Fx.sound(player, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.6f);
        Fx.actionBar(player, "<red><bold>STRZAŁ!");
        redraw();
        later(18, () -> {
            phase = PHASE_DONE;
            boolean won = result.winner().equals(player.getUniqueId());
            Fx.actionBar(player, won ? "<green><bold>Ty zostałeś na nogach!" : "<red><bold>Padasz na piasek...");
            if (!won) Fx.sound(player, Sound.ENTITY_PLAYER_DEATH, 0.8f, 1f);
            redraw();
        });
    }

    private boolean aWins() {
        return result.winner().equals(result.a());
    }

    private void duelist(int slot, UUID who, boolean wins) {
        String name = T.esc(eco().pd(who).name);
        if (phase == PHASE_DONE && !wins) {
            set(slot, ItemBuilder.of(Material.SKELETON_SKULL).icon("cross").name("<red><bold>" + name + " poległ").lore("<gray>Traci stawkę " + Fmt.money(result.stake())).build());
        } else {
            boolean ready = phase >= PHASE_TENSION;
            set(slot, ItemBuilder.of(Material.PLAYER_HEAD).head(Bukkit.getOfflinePlayer(who)).name((phase == PHASE_DONE ? "<gold><bold>★ " : ready ? "<red><bold>" : "<yellow>") + name).glow(ready).build());
        }
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        duelist(20, result.a(), aWins());
        duelist(24, result.b(), !aWins());

        // środek: napis sceny
        String name;
        String line;
        Material icon;
        switch (phase) {
            case PHASE_STAND -> {
                icon = Material.IRON_SWORD;
                name = "<yellow><bold>Pojedynek o " + Fmt.money(result.stake() * 2);
                line = "<gray>Wiatr gna po ulicy kulkę trawy...";
            }
            case PHASE_TENSION -> {
                icon = Material.CLOCK;
                name = "<gold><bold>Napięcie rośnie";
                line = "<gray>Dłonie przy kaburach.";
            }
            case PHASE_COUNT -> {
                icon = Material.PAPER;
                name = "<gold><bold>" + count;
                line = "<gray>Czekaj na sygnał!";
            }
            case PHASE_WAIT -> {
                icon = Material.ENDER_EYE;
                name = "<dark_gray><bold>...";
                line = "<gray>Cisza.";
            }
            case PHASE_FIRE -> {
                icon = Material.TNT;
                name = "<red><bold>STRZAŁ!";
                line = "<gray>Padły dwa strzały naraz.";
            }
            default -> {
                icon = Material.NETHER_STAR;
                name = "<gold><bold>Zwycięzca: " + T.esc(eco().pd(result.winner()).name);
                line = "<gray>Wypłata: <gold>" + Fmt.money(result.payout()) + " <gray>(prowizja 3%)";
            }
        }
        set(13, ItemBuilder.of(icon).icon(phase == PHASE_DONE ? "star" : "pojedynek").name(name).glow(phase >= PHASE_FIRE).lore(List.of(line)).build());
        set(22, ItemBuilder.of(phase >= PHASE_FIRE ? Material.FIREWORK_STAR : Material.IRON_SWORD).icon("pojedynek").name(phase >= PHASE_FIRE ? "<red><bold>✦" : "<red><bold>VS").build());
        if (phase == PHASE_FIRE) {
            set(21, ItemBuilder.of(Material.ARROW).name("<yellow>→").build());
            set(23, ItemBuilder.of(Material.ARROW).name("<yellow>←").build());
        }
        if (phase == PHASE_DONE) {
            set(31, ItemBuilder.of(Material.GOLD_INGOT).icon("jackpot").name("<gold><bold>Wypłata zwycięzcy").lore("<gray>" + Fmt.money(result.payout()), "<gray>Stawka: <white>" + Fmt.money(result.stake()) + " <gray>od każdego").build());
            backTo(49, () -> go(new LobbyGui(plugin, player)));
        }
    }
}
