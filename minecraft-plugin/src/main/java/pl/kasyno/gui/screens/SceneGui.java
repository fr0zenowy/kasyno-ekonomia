package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Krótka scena przed wynikiem akcji zarobkowej (przestępstwo, okradanie, praca, łowienie...): Twoja głowa idzie ścieżką przez
 * dziewięć pól, na każdym przystanku (rząd wyżej) staje, przystanek mruga, a nad nim pojawia się opis kroku fabuły z dźwiękiem.
 * Na końcu następuje rozstrzygnięcie. Nic nie jest rozliczane przed końcem sceny: akcję wykonuje dopiero ostatni krok (resolve),
 * więc zamknięcie okna w trakcie po prostu anuluje akcję i nie kosztuje niczego (ani cooldownu).
 */
public final class SceneGui extends Gui {
    /** Jeden krok fabuły: ikona, tytuł, jedna linia opisu i dźwięk. */
    public record Beat(Material icon, String title, String line, Sound sound) {}

    private static final int PATH_ROW = 18;
    private static final int STATION_ROW = 9;
    private static final int WALK_TICKS = 3;
    private static final int BLINK_TICKS = 4;

    private final String heading;
    private final String iconId;
    private final String art;
    private final List<Beat> beats;
    private final int[] stops;
    private final Supplier<List<String>> resolve;
    private final Consumer<List<String>> back;
    private int token = 0;
    private int step = -1;
    private int visited;
    private boolean blink;
    private boolean done;
    private List<String> lines;

    /**
     * @param heading tytuł okna (np. "Przestępstwo")
     * @param iconId  ikona z paczki zasobów dla przystanków (np. "napad")
     * @param art     tło z paczki zasobów dla tej sceny (np. "scena_przestepstwo"); każda akcja ma własny tytuł
     * @param resolve wykonuje akcję i zwraca linie wyniku (null, gdy akcja się nie odbyła, np. cooldown)
     * @param back    wywoływane po zakończeniu, z liniami wyniku (null = brak)
     */
    public SceneGui(KasynoPlugin plugin, Player player, String heading, String iconId, String art, List<Beat> beats, Supplier<List<String>> resolve, Consumer<List<String>> back) {
        super(plugin, player);
        this.heading = heading;
        this.iconId = iconId;
        this.art = art;
        this.beats = beats;
        this.resolve = resolve;
        this.back = back;
        // przystanki równomiernie wzdłuż dziewięciu pól ścieżki
        this.stops = new int[beats.size()];
        for (int i = 0; i < stops.length; i++) stops[i] = Math.min(8, (i + 1) * 9 / (stops.length + 1));
    }

    @Override
    protected String art() {
        return art;
    }

    @Override
    protected Component title() {
        return T.c("<gold><bold>" + heading);
    }

    @Override
    public void open() {
        super.open();
        later(8, () -> beat(0));
    }

    // ---------- przebieg sceny ----------

    private void beat(int i) {
        if (i >= beats.size()) {
            // koniec drogi, a dopiero potem akcja naprawdę się odbywa (pieniądze, cooldown, wiadomości na czacie)
            walk(8, () -> {
                step = -1;
                lines = resolve.get();
                done = true;
                if (lines == null) {
                    back.accept(null);
                    return;
                }
                redraw();
            });
            return;
        }
        walk(stops[i], () -> {
            step = i;
            Beat b = beats.get(i);
            if (b.sound() != null) Fx.sound(player, b.sound(), 0.8f, 1f);
            Fx.actionBar(player, "<yellow>" + T.esc(b.title()) + ": <gray>" + T.esc(b.line()));
            stay(i, 0);
        });
    }

    private void walk(int target, Runnable then) {
        if (token >= target) {
            then.run();
            return;
        }
        token++;
        Fx.sound(player, Sound.BLOCK_STONE_STEP, 0.4f, 0.9f + 0.05f * (token % 4));
        redraw();
        later(WALK_TICKS, () -> walk(target, then));
    }

    private void stay(int i, int blinks) {
        blink = !blink;
        redraw();
        if (blinks < 3) later(BLINK_TICKS, () -> stay(i, blinks + 1));
        else {
            visited = i + 1;
            blink = false;
            redraw();
            later(2, () -> beat(i + 1));
        }
    }

    private boolean good() {
        return lines != null && !lines.isEmpty() && lines.get(0).startsWith("<green>");
    }

    // ---------- rysowanie ----------

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        // ścieżka: pola, które już minąłeś, są zielone; na polu z Twoją głową stoisz Ty
        for (int c = 0; c < 9; c++) {
            if (c == token) {
                set(PATH_ROW + c, ItemBuilder.of(Material.PLAYER_HEAD).head(player).name("<yellow><bold>" + T.esc(player.getName())).glow().lore("<gray>To Ty.").build());
            } else {
                Material pane = c < token ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
                set(PATH_ROW + c, ItemBuilder.of(pane).name(c < token ? "<green>Przeszedłeś" : "<dark_gray>Przed Tobą").hidden().icon("blank").build());
            }
        }
        // przystanki nad ścieżką: zaliczone świecą, bieżący mruga, kolejne są przygaszone
        for (int i = 0; i < beats.size(); i++) {
            Beat b = beats.get(i);
            int slot = STATION_ROW + stops[i];
            if (i < visited) set(slot, ItemBuilder.of(b.icon()).icon(iconId).name("<green>✔ " + b.title()).glow().build());
            else if (i == step) set(slot, ItemBuilder.of(blink ? b.icon() : Material.FIREWORK_STAR).icon(blink ? iconId : null).name("<yellow><bold>▶ " + b.title()).glow().lore("<gray>" + b.line()).build());
            else set(slot, ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name("<dark_gray>" + b.title()).hidden().icon("blank").build());
        }
        // opis bieżącego kroku albo wynik
        if (done && lines != null) {
            boolean ok = good();
            set(13, ItemBuilder.of(ok ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK).icon(ok ? "check" : "cross").name(ok ? "<green><bold>Udało się!" : "<red><bold>Wynik").glow(ok).lore(new ArrayList<>(lines)).build());
            set(49, ItemBuilder.back(), t -> {
                Fx.click(player);
                back.accept(lines);
            });
        } else if (step >= 0 && step < beats.size()) {
            Beat b = beats.get(step);
            set(31, ItemBuilder.of(b.icon()).icon(iconId).name("<yellow><bold>" + b.title()).glow().lore(T.wrap(b.line(), 32, "<gray>")).build());
            set(13, ItemBuilder.of(Material.CLOCK).name("<gray>Krok " + (step + 1) + " z " + beats.size()).hidden().build());
        } else {
            set(31, ItemBuilder.of(Material.LEATHER_BOOTS).name("<gray>Idziesz dalej...").hidden().build());
        }
    }
}
