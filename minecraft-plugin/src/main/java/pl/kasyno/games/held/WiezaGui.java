package pl.kasyno.games.held;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Wieża: na każdym piętrze wybierz pole bez pułapki. Tarcza blokuje trafioną pułapkę (pole zostaje wyłączone). */
public final class WiezaGui extends StepGui {
    private static final int MAX_FLOORS = 14;

    /** Ukończone piętro: które pole wybrano i gdzie były pułapki. */
    private record Floor(int pick, Set<Integer> bad, int cols) {}

    private final List<Floor> floors = new ArrayList<>();
    private Set<Integer> bad = new HashSet<>();
    private final Set<Integer> blockedTiles = new HashSet<>();
    private int cols = 3;
    private int lastTrap = -1;

    public WiezaGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "wieza", "sredni");
        level = currentLevel();
    }

    @Override
    protected Component title() {
        return T.c("<blue><bold>Wieża");
    }

    @Override
    protected Map<String, Level> levels() {
        Map<String, Level> out = ordered();
        for (Map.Entry<String, Catalog.TowerLevel> e : plugin.catalog().games.wieza().entrySet()) {
            Catalog.TowerLevel l = e.getValue();
            out.put(e.getKey(), new Level(e.getKey(), l.name(), (double) (l.cols() - l.bad()) / l.cols(), MAX_FLOORS, "<gray>" + l.cols() + " pola, " + l.bad() + (l.bad() == 1 ? " pułapka" : " pułapki")));
        }
        return out;
    }

    private Catalog.TowerLevel tower() {
        return plugin.catalog().games.wieza().get(level == null ? levelKey : level.key());
    }

    @Override
    protected void onStart() {
        floors.clear();
        nextFloor();
    }

    @Override
    protected void onNextStep() {
        nextFloor();
    }

    private void nextFloor() {
        Catalog.TowerLevel t = tower();
        cols = t.cols();
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < cols; i++) all.add(i);
        Collections.shuffle(all, new java.util.Random(eco().rng.nextLong()));
        bad = new HashSet<>(all.subList(0, t.bad()));
        blockedTiles.clear();
        lastTrap = -1;
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        if (!started) levelButtons(new int[]{10, 11, 12, 13});
        set(4, statusItem("Wieża"));
        if (started) {
            // historia: trzy ostatnie ukończone piętra (od najstarszego), potem bieżące
            int shown = Math.min(3, floors.size());
            for (int r = 0; r < shown; r++) {
                Floor f = floors.get(floors.size() - shown + r);
                int floorNo = floors.size() - shown + r + 1;
                for (int c = 0; c < f.cols(); c++) {
                    int slot = 9 + r * 9 + 2 + c;
                    boolean picked = c == f.pick();
                    boolean trap = f.bad().contains(c);
                    set(slot, ItemBuilder.of(picked ? Material.LIME_CONCRETE : trap ? Material.RED_CONCRETE : Material.GRAY_CONCRETE).name(picked ? "<green>Piętro " + floorNo + ": wybrane" : trap ? "<red>Pułapka" : "<gray>Puste pole").build());
                }
            }
            int cur = 9 + 3 * 9;
            for (int c = 0; c < cols; c++) {
                final int tile = c;
                int slot = cur + 2 + c;
                if (over && bad.contains(c)) set(slot, ItemBuilder.of(c == lastTrap ? Material.TNT : Material.RED_CONCRETE).name("<red><bold>Pułapka").glow(c == lastTrap).build());
                else if (blockedTiles.contains(c)) set(slot, ItemBuilder.of(Material.SHIELD).name("<aqua>Zablokowana pułapka").lore("<gray>Wybierz inne pole").build());
                else if (over) set(slot, ItemBuilder.of(Material.GRAY_CONCRETE).name("<gray>Pole").build());
                else set(slot, ItemBuilder.of(Material.WHITE_CONCRETE).name("<white><bold>Piętro " + (steps + 1) + ": pole " + (c + 1)).lore("<yellow>Kliknij, aby wejść").build(), t -> choose(tile));
            }
        }
        controls("Zacznij wspinaczkę", ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Na każdym piętrze są pułapki.", "<gray>Wypłać w dowolnej chwili.", "<gray>Do 14 pięter, mnożnik do x1000.").build());
    }

    private void choose(int tile) {
        if (!started || over || blockedTiles.contains(tile)) return;
        touch();
        if (!bad.contains(tile)) {
            floors.add(new Floor(tile, new HashSet<>(bad), cols));
            safeStep();
            return;
        }
        var s = shield();
        if (s.isPresent()) {
            blockedTiles.add(tile);
            shieldUsed(s.get(), "zablokowała pułapkę! To pole jest wyłączone, wybierz inne.");
            return;
        }
        lastTrap = tile;
        fail("Pułapka! Wieża się zawaliła.");
    }
}
