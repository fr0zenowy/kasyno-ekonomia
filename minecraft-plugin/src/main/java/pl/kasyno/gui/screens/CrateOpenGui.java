package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Crates;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.Icons;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.Notify;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Otwieranie skrzyń. Jedna skrzynia: pasek w stylu CS:GO (kolorowe pola rzadkości przewijają się i zwalniają).
 * Wiele naraz: nagrody odsłaniają się po kolei. Nagrody są wręczane od razu, jeszcze przed animacją.
 */
public final class CrateOpenGui extends Gui {
    private static final double[] STEPS = {0.14, 0.33, 0.5, 0.63, 0.75, 0.84, 0.91, 0.96};
    private static final int[] DELAYS = {9, 9, 10, 12, 14, 16, 19, 22};
    private static final int TARGET = 24;
    private static final int HALF = 4;
    private static final int[] STRIP = {18, 19, 20, 21, 22, 23, 24, 25, 26};

    private final String crateId;
    private final int qty;
    private final List<Crates.Reward> rewards = new ArrayList<>();
    private final List<Crates.Applied> applied = new ArrayList<>();
    private String[] cells;
    private int pos = HALF;
    private boolean finished;
    private boolean ok;

    public CrateOpenGui(KasynoPlugin plugin, Player player, String crateId, int qty) {
        super(plugin, player);
        this.crateId = crateId;
        this.qty = Math.max(1, Math.min(Crates.MAX_OPEN, qty));
    }

    @Override
    protected String art() {
        return "skrzynia";
    }

    @Override
    protected Component title() {
        return T.c("<gold><bold>" + plugin.catalog().item(crateId).name());
    }

    private static Material paneFor(String rarity) {
        return switch (rarity) {
            case "uncommon" -> Material.LIME_STAINED_GLASS_PANE;
            case "rare" -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case "epic" -> Material.PURPLE_STAINED_GLASS_PANE;
            case "legendary" -> Material.YELLOW_STAINED_GLASS_PANE;
            default -> Material.WHITE_STAINED_GLASS_PANE;
        };
    }

    @Override
    public void open() {
        // Zdejmujemy skrzynie i wręczamy nagrody zanim ruszy animacja: zamknięcie okna niczego nie kosztuje.
        if (eco().qty(id, crateId) < qty || !eco().takeItem(id, crateId, qty)) {
            Fx.deny(player);
            T.send(player, "<red>Nie masz tylu skrzyń.");
            plugin.guis().next(() -> new CratesGui(plugin, player).open());
            return;
        }
        ok = true;
        for (int i = 0; i < qty; i++) {
            Crates.Reward r = plugin.crates().roll(crateId);
            rewards.add(r);
            applied.add(plugin.crates().apply(id, r));
        }
        if (qty == 1) buildStrip(rewards.get(0));
        super.open();
        if (qty == 1) animateStrip();
        else animateMulti();
    }

    private void buildStrip(Crates.Reward winner) {
        Map<String, Double> odds = plugin.crates().rarityOdds(crateId);
        List<String> pool = new ArrayList<>();
        odds.forEach((r, w) -> {
            for (int i = 0; i < Math.max(1, Math.round(w)); i++) pool.add(r);
        });
        cells = new String[TARGET + HALF + 2];
        for (int i = 0; i < cells.length; i++) cells[i] = pool.get(eco().rng.nextInt(pool.size()));
        cells[TARGET] = winner.rarity();
        // „Prawie!”: obok wygranej często ląduje coś lepszego.
        if (!winner.rarity().equals("legendary") && eco().chance(0.5)) cells[TARGET + (eco().chance(0.5) ? 1 : -1)] = "legendary";
        else if (!winner.rarity().equals("legendary") && !winner.rarity().equals("epic") && eco().chance(0.5)) cells[TARGET + (eco().chance(0.5) ? 2 : -2)] = "epic";
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        if (!ok) return;
        if (qty == 1) renderStrip();
        else renderMulti();
    }

    private void renderStrip() {
        set(4, ItemBuilder.of(Material.HOPPER).name("<yellow>▼").build());
        set(40, ItemBuilder.of(Material.HOPPER).name("<yellow>▲").build());
        for (int i = 0; i < 9; i++) {
            int idx = pos - HALF + i;
            String r = idx >= 0 && idx < cells.length ? cells[idx] : "common";
            boolean center = i == HALF;
            Crates.Rarity rar = Crates.RARITY.get(r);
            if (finished && center) {
                Crates.Reward w = rewards.get(0);
                set(STRIP[i], rewardIcon(w, applied.get(0).text()), null);
            } else {
                set(STRIP[i], ItemBuilder.of(paneFor(r)).name("<" + rar.color() + ">" + rar.name()).glow(center).build());
            }
        }
        if (finished) finishPanel();
        else set(31, ItemBuilder.of(Material.CLOCK).name("<gray>Losowanie...").build());
    }

    private void renderMulti() {
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 20, 22, 24};
        for (int i = 0; i < qty; i++) {
            if (i < revealed) set(slots[i], rewardIcon(rewards.get(i), applied.get(i).text()));
            else set(slots[i], ItemBuilder.of(Material.CHEST).name("<gray>Skrzynia " + (i + 1)).build());
        }
        if (finished) finishPanel();
    }

    private int revealed;

    private ItemStack rewardIcon(Crates.Reward r, String text) {
        Crates.Rarity rar = Crates.RARITY.get(r.rarity());
        Catalog cat = plugin.catalog();
        Material m = switch (r.kind()) {
            case "money" -> Material.GOLD_INGOT;
            case "xp" -> Material.EXPERIENCE_BOTTLE;
            default -> Icons.of(cat.item(r.item()));
        };
        return ItemBuilder.of(m).name("<" + rar.color() + "><bold>" + rar.name() + " drop!").glow(rar.rank() >= 3)
                .lore("<white>" + T.esc(text), "<gray>Rzadkość: <" + rar.color() + ">" + rar.name()).build();
    }

    private void finishPanel() {
        int left = eco().qty(id, crateId);
        long money = rewards.stream().filter(r -> r.kind().equals("money")).mapToLong(Crates.Reward::amount).sum();
        List<String> lore = new ArrayList<>();
        for (int i = 0; i < rewards.size(); i++) lore.add("<" + Crates.RARITY.get(rewards.get(i).rarity()).color() + ">● <white>" + T.esc(applied.get(i).text()));
        if (qty > 1) lore.add("<gray>Razem gotówki: <gold>" + Fmt.money(money));
        lore.add("<gray>Portfel: <gold>" + Fmt.money(eco().wallet(id)));
        set(qty == 1 ? 31 : 31, ItemBuilder.of(Material.NETHER_STAR).name("<gold><bold>Wynik").lore(lore).build());
        if (left > 0) set(30, ItemBuilder.of(Material.CHEST).name("<yellow>Otwórz kolejną").lore("<gray>Zostało: <white>" + left).build(), t -> go(new CrateOpenGui(plugin, player, crateId, 1)));
        backTo(32, () -> go(new CratesGui(plugin, player)));
    }

    private void animateStrip() {
        final int[] k = {0};
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            while (k[0] < STEPS.length) {
                int p = (int) Math.round(HALF + (TARGET - HALF) * STEPS[k[0]]);
                if (p > pos && p < TARGET) {
                    pos = p;
                    redraw();
                    Fx.tick(player, 0.7f + 0.08f * k[0]);
                    int delay = DELAYS[k[0]];
                    k[0]++;
                    later(delay, step[0]);
                    return;
                }
                k[0]++;
            }
            pos = TARGET;
            finished = true;
            Crates.Reward w = rewards.get(0);
            redraw();
            reveal(w);
        };
        later(5, step[0]);
    }

    private void animateMulti() {
        later(10, new Runnable() {
            @Override
            public void run() {
                if (revealed < qty) {
                    revealed++;
                    Fx.tick(player, 0.8f + 0.05f * revealed);
                    redraw();
                    later(6, this);
                } else {
                    finished = true;
                    Crates.Reward best = rewards.stream().max((a, b) -> Integer.compare(Crates.RARITY.get(a.rarity()).rank(), Crates.RARITY.get(b.rarity()).rank())).orElse(rewards.get(0));
                    redraw();
                    reveal(best);
                }
            }
        });
    }

    private void reveal(Crates.Reward best) {
        Crates.Rarity rar = Crates.RARITY.get(best.rarity());
        if (rar.rank() >= 4) Fx.jackpot(player);
        else if (rar.rank() >= 3) Fx.win(player);
        else Fx.sound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        for (Crates.Applied a : applied) {
            if (a.lv() != null) Notify.levelUp(player, a.lv());
        }
        for (int i = 0; i < rewards.size(); i++) {
            Crates.Reward r = rewards.get(i);
            Crates.Rarity rr = Crates.RARITY.get(r.rarity());
            T.send(player, "<" + rr.color() + ">" + rr.name() + " drop: <white>" + T.esc(applied.get(i).text()));
            if (r.kind().equals("item") && plugin.crates().isCrate(r.item())) T.send(player, "<yellow>Wypadła kolejna skrzynia! Otworzysz ją w menu skrzyń.");
        }
    }

    @Override
    protected void onClose() {
        // Nagrody są już wręczone, więc nic nie trzeba rozliczać. Wiadomość na czacie, jeśli gracz zamknął okno przed końcem animacji.
        if (ok && !finished) {
            for (int i = 0; i < rewards.size(); i++) T.send(player, "<gold>Wypadło: <white>" + T.esc(applied.get(i).text()));
        }
    }
}
