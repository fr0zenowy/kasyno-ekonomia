package pl.kasyno.games.held;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Games;
import pl.kasyno.games.HeldGameGui;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Wspólny silnik gier krokowych (kurczak, wieża): za każdy bezpieczny krok rośnie mnożnik, w dowolnym momencie można wypłacić,
 * a tarcza anuluje pierwszy zły ruch. Podklasa dostarcza poziomy, wygląd planszy i sposób rozstrzygania kroku.
 */
abstract class StepGui extends HeldGameGui {
    /** Poziom trudności: klucz, nazwa, szansa bezpiecznego kroku i limit kroków. */
    protected record Level(String key, String name, double pSafe, int max, String detail) {}

    protected String levelKey;
    protected Level level;
    protected int steps;
    protected int maxSteps;
    protected long bet;
    protected boolean started;
    protected boolean won;
    protected String note = "";

    protected StepGui(KasynoPlugin plugin, Player player, String gameId, String defaultLevel) {
        super(plugin, player, gameId);
        levelKey = choice("level", defaultLevel);
    }

    protected abstract Map<String, Level> levels();

    protected Level currentLevel() {
        Map<String, Level> all = levels();
        return all.getOrDefault(levelKey, all.values().iterator().next());
    }

    protected double mult(int n) {
        return Games.stepMultiplier(level.pSafe(), n);
    }

    protected long payoutNow() {
        return (long) Math.floor(bet * mult(steps));
    }

    /** Wywoływane po rozpoczęciu gry (np. wylosowanie pułapek pierwszego piętra). */
    protected void onStart() {}

    /** Wywoływane po każdym bezpiecznym kroku, który nie kończy gry. */
    protected void onNextStep() {}

    protected String protectionLine() {
        List<Catalog.Protection> shield = eco().armed(id, "tarcza", started ? bet : stake);
        List<Catalog.Protection> pol = eco().armed(id, "polisa", started ? bet : stake);
        String s = "";
        if (!pol.isEmpty()) s += "<aqua>" + plugin.catalog().item(pol.get(0).id()).name() + " <gray>(zwrot stawki) ";
        if (!shield.isEmpty()) s += "<aqua>" + plugin.catalog().item(shield.get(0).id()).name() + " <gray>(pierwszy błąd się nie liczy)";
        return s;
    }

    /** Ikona statusu gry z mnożnikami. */
    protected ItemStack statusItem(String title) {
        List<String> lore = new ArrayList<>();
        if (level != null) lore.add("<gray>Poziom: <white>" + level.name());
        lore.add("<gray>Kroki: <white>" + steps + "<gray>/" + (started ? maxSteps : "?"));
        lore.add("<gray>Mnożnik: <gold>" + Fmt.mult(started ? mult(steps) : 1));
        if (started && !over) {
            lore.add("<gray>Następny krok: <gold>" + Fmt.mult(mult(steps + 1)));
            lore.add("<gray>Do wypłaty: <gold>" + Fmt.money(payoutNow()));
            String p = protectionLine();
            if (!p.isEmpty()) lore.add(p);
        }
        if (!note.isEmpty()) lore.add(note);
        Material m = over ? (won ? Material.LIME_CONCRETE : Material.RED_CONCRETE) : Material.GOLD_BLOCK;
        return ItemBuilder.of(m).name("<yellow><bold>" + title).lore(lore).build();
    }

    /** Przyciski wyboru poziomu (przed startem), w podanych slotach. */
    protected void levelButtons(int[] slots) {
        int i = 0;
        for (Level l : levels().values()) {
            if (i >= slots.length) break;
            boolean on = l.key().equals(levelKey);
            set(slots[i++], ItemBuilder.of(Material.PAPER).name((on ? "<green><bold>" : "<white>") + l.name()).glow(on)
                    .lore(l.detail(), "<gray>Szansa kroku: <white>" + Math.round(l.pSafe() * 100) + "%", on ? "<green>Wybrany" : "<yellow>Kliknij").build(), t -> {
                        if (started) return;
                        levelKey = l.key();
                        saveChoice("level", l.key());
                        Fx.click(player);
                        redraw();
                    });
        }
    }

    protected void start() {
        if (started || !takeStake()) return;
        level = currentLevel();
        maxSteps = Games.stepsLimit(level.pSafe(), level.max());
        started = true;
        busy = true;
        bet = stake;
        steps = 0;
        touch();
        onStart();
        redraw();
    }

    /** Rozstrzygnięcie kroku: true = bezpieczny. Zły krok z tarczą się nie liczy (zwraca false i wiadomość). */
    protected Optional<Catalog.Protection> shield() {
        return eco().consume(id, "tarcza", bet);
    }

    protected void safeStep() {
        steps++;
        note = "";
        Fx.ding(player, 0.8f + 0.04f * Math.min(steps, 20));
        if (steps >= maxSteps) {
            won = true;
            finishGame(payoutNow(), "Dotarłeś do końca (limit x1000)!");
        } else onNextStep();
        redraw();
    }

    protected void fail(String text) {
        won = false;
        Fx.sound(player, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1f);
        finishGame(0, text);
        redraw();
    }

    protected void shieldUsed(Catalog.Protection p, String what) {
        note = "<aqua>" + plugin.catalog().item(p.id()).name() + " " + what;
        Fx.sound(player, org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
        T.send(player, "<aqua>" + plugin.catalog().item(p.id()).name() + " " + what);
        redraw();
    }

    protected void cash() {
        if (!started || over) return;
        if (steps == 0) {
            Fx.deny(player);
            return;
        }
        won = true;
        finishGame(payoutNow(), "Wypłacasz wygraną w porę!");
        redraw();
    }

    @Override
    protected void abandon(boolean idle) {
        if (!started || over) return;
        if (steps == 0) refundGame();
        else {
            won = true;
            finishGame(payoutNow(), "Koniec gry: wygrana wypłacona automatycznie.");
        }
    }

    /** Dolny pasek zależny od fazy gry. */
    protected void controls(String startName, ItemStack info) {
        if (!started) {
            betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>" + startName).glow().lore("<gray>Stawka: <gold>" + Fmt.money(stake)).build(), t -> start(), info);
        } else if (!over) {
            betBar(ItemBuilder.of(Material.GOLD_INGOT).name(steps == 0 ? "<gray>Wypłata (zrób najpierw krok)" : "<gold><bold>Wypłać " + Fmt.money(payoutNow())).glow(steps > 0).build(), t -> cash(), info);
        } else {
            betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Zagraj ponownie").build(), t -> reopen(), null);
        }
    }

    protected static Map<String, Level> ordered() {
        return new LinkedHashMap<>();
    }
}
