package pl.kasyno.games.held;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Games;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.Map;

/** Chicken Road: przeprowadź kurczaka przez kolejne pasy ulicy, każdy bezpieczny krok podnosi mnożnik (do x1000). */
public final class KurczakGui extends StepGui {
    private static final int[] LANES = {18, 19, 20, 21, 22, 23, 24, 25, 26};
    private boolean hit;

    public KurczakGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "kurczak", "sredni");
        level = currentLevel();
    }

    @Override
    protected Component title() {
        return T.c("<yellow><bold>Chicken Road");
    }

    @Override
    protected Map<String, Level> levels() {
        Map<String, Level> out = ordered();
        for (Map.Entry<String, Catalog.ChickenLevel> e : plugin.catalog().games.kurczak().entrySet()) {
            Catalog.ChickenLevel l = e.getValue();
            out.put(e.getKey(), new Level(e.getKey(), l.name(), 1 - l.d(), l.max(), "<gray>Ryzyko na pas: <white>" + Math.round(l.d() * 100) + "%"));
        }
        return out;
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        if (!started) levelButtons(new int[]{10, 11, 12, 13});
        set(4, statusItem("Chicken Road"));

        // droga: okno dziewięciu pasów przesuwa się za kurczakiem
        int total = started ? maxSteps : 20;
        int start = Math.max(0, Math.min(steps - 3, total - 8));
        for (int i = 0; i < 9; i++) {
            int lane = start + i;
            Material m;
            String name;
            if (lane < steps) {
                m = Material.LIME_STAINED_GLASS_PANE;
                name = "<green>Pas " + lane + " (przebyty)";
            } else if (lane == steps) {
                m = hit ? Material.SKELETON_SKULL : Material.EGG;
                name = hit ? "<red><bold>Potrącony!" : "<yellow><bold>Kurczak";
            } else if (hit && lane == steps + 1) {
                m = Material.MINECART;
                name = "<red><bold>Samochód!";
            } else if (lane == total && started) {
                m = Material.GOLD_BLOCK;
                name = "<gold>Meta";
            } else {
                m = Material.GRAY_STAINED_GLASS_PANE;
                name = "<gray>Pas " + lane;
            }
            set(LANES[i], ItemBuilder.of(m).name(name).glow(lane == steps && !hit).build());
        }
        if (started && !over) {
            set(31, ItemBuilder.of(Material.LIME_DYE).name("<green><bold>Idź dalej!").glow().lore("<gray>Następny pas: <gold>" + Fmt.mult(mult(steps + 1)),
                    "<gray>Szansa przeżycia: <white>" + Math.round(level.pSafe() * 100) + "%", "", "<yellow>Kliknij").build(), t -> go1());
        }
        controls("Zacznij grę", ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Każdy pas to ryzyko potrącenia.", "<gray>Wypłać w dowolnej chwili.", "<gray>Tarcza odpycha pierwszy samochód.").build());
    }

    private void go1() {
        if (!started || over) return;
        touch();
        hit = false;
        if (eco().chance(level.pSafe())) {
            safeStep();
            return;
        }
        var s = shield();
        if (s.isPresent()) {
            shieldUsed(s.get(), "odepchnęła samochód! Kurczak stoi tam, gdzie stał.");
            return;
        }
        hit = true;
        fail("Potrącony! Kurczak nie przeżył tego pasa.");
    }
}
