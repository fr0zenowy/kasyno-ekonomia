package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.Icons;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Ekwipunek ekonomii: przedmioty ze sklepu, eliksiry, ochrony, skrzynie i łup. Klik używa albo sprzedaje. */
public final class InventoryGui extends Gui {
    private static final List<String> ORDER = List.of("tool", "passive", "protection", "boost", "consumable", "crate", "luxury", "trophy", "loot");
    private int page;

    public InventoryGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String art() {
        return "ekwipunek";
    }

    @Override
    protected Component title() {
        return T.c("<light_purple><bold>Ekwipunek");
    }

    @Override
    protected void render() {
        Catalog cat = plugin.catalog();
        fill(ItemBuilder.filler());
        List<Map.Entry<String, Integer>> owned = new ArrayList<>(eco().pd(id).inventory.entrySet().stream().filter(e -> cat.item(e.getKey()) != null).toList());
        owned.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> ORDER.indexOf(cat.item(e.getKey()).type())).thenComparing(Map.Entry::getKey));

        int per = 45;
        int pages = Math.max(1, (owned.size() + per - 1) / per);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < per; i++) {
            int idx = page * per + i;
            if (idx >= owned.size()) break;
            Catalog.Item it = cat.item(owned.get(idx).getKey());
            int qty = owned.get(idx).getValue();
            set(i, view(it, qty), t -> click(it, qty, t));
        }
        if (owned.isEmpty()) set(22, ItemBuilder.of(Material.BARRIER).icon("cross").name("<red>Pusto").lore("<gray>Zajrzyj do sklepu albo zdobądź", "<gray>łupy w zarobkach.").build());

        // dolny pasek
        long loot = lootValue();
        set(46, ItemBuilder.of(Material.GOLD_NUGGET).icon("jackpot").name("<gold><bold>Sprzedaj cały łup").lore("<gray>Wartość łupu: <gold>" + Fmt.money(loot), "", loot > 0 ? "<yellow>Kliknij, aby sprzedać" : "<red>Nie masz łupu").build(), t -> sellAll());
        set(48, effectsItem());
        if (page > 0) set(45, ItemBuilder.of(Material.ARROW).icon("prev").name("<yellow>« Poprzednia strona").build(), t -> {
            page--;
            redraw();
        });
        if (page < pages - 1) set(53, ItemBuilder.of(Material.ARROW).icon("next").name("<yellow>Następna strona »").build(), t -> {
            page++;
            redraw();
        });
        backTo(49, () -> go(new MainMenu(plugin, player)));
    }

    private org.bukkit.inventory.ItemStack effectsItem() {
        List<String> lore = new ArrayList<>();
        for (Catalog.Boost b : plugin.catalog().boosts.values()) {
            long left = eco().boostLeft(id, b.key());
            if (left > 0) lore.add("<green>" + b.label() + " <gray>(" + Fmt.duration(left) + ")");
        }
        for (Catalog.Protection p : plugin.catalog().protections.values()) {
            int uses = eco().buffUses(id, p.id());
            if (uses > 0) lore.add("<aqua>" + plugin.catalog().item(p.id()).name() + " x" + uses + " <gray>(do " + Fmt.money(p.cap()) + ")");
        }
        int clover = eco().buffUses(id, "koniczyna");
        if (clover > 0) lore.add("<gold>Koniczyna: " + clover + " wygranych z bonusem");
        if (lore.isEmpty()) lore.add("<gray>Brak aktywnych eliksirów ani ochron.");
        return ItemBuilder.of(Material.BEACON).icon("star").name("<white><bold>Aktywne bonusy").lore(lore).build();
    }

    private long lootValue() {
        long sum = 0;
        for (Map.Entry<String, Integer> e : eco().pd(id).inventory.entrySet()) {
            Catalog.Item it = plugin.catalog().item(e.getKey());
            if (it != null && it.type().equals("loot")) sum += it.sellValue() * e.getValue();
        }
        return sum;
    }

    private org.bukkit.inventory.ItemStack view(Catalog.Item it, int qty) {
        List<String> lore = new ArrayList<>(T.wrap(it.desc().isEmpty() ? "Łup do sprzedania." : it.desc(), 36, "<gray>"));
        lore.add("");
        lore.add("<gray>Masz: <white>" + qty);
        switch (it.type()) {
            case "boost" -> lore.add("<yellow>Kliknij, aby użyć");
            case "protection" -> lore.add("<yellow>Kliknij, aby uzbroić");
            case "crate" -> lore.add("<yellow>Kliknij, aby otworzyć");
            case "loot" -> {
                lore.add("<gray>Cena skupu: <gold>" + Fmt.money(it.sellValue()));
                lore.add("<yellow>Klik: sprzedaj 1 <dark_gray>| <yellow>Shift: sprzedaj wszystkie");
            }
            default -> {
                if (it.id().equals("koniczyna")) lore.add("<yellow>Kliknij, aby użyć");
                else lore.add("<dark_gray>Działa automatycznie");
            }
        }
        return ItemBuilder.of(Icons.of(it)).name("<white><bold>" + it.name()).amount(Math.min(qty, 64)).glow(Icons.glows(it.id())).lore(lore).build();
    }

    private void click(Catalog.Item it, int qty, ClickType type) {
        switch (it.type()) {
            case "boost" -> {
                if (!eco().takeItem(id, it.id(), 1)) return;
                Fx.sound(player, org.bukkit.Sound.ENTITY_GENERIC_DRINK, 1f, 1f);
                var act = eco().activateBoost(id, it.id());
                T.send(player, "<green>" + it.name() + " aktywny: <white>" + plugin.catalog().boosts.get(it.id()).label() + "</white> <gray>(jeszcze " + Fmt.duration(act.left()) + ")"
                        + (act.extended() ? " <yellow>przedłużono" : ""));
                redraw();
            }
            case "protection" -> {
                if (!eco().takeItem(id, it.id(), 1)) return;
                eco().addBuff(id, it.id(), 1);
                Fx.sound(player, org.bukkit.Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 1f);
                Catalog.Protection p = plugin.catalog().protections.get(it.id());
                T.send(player, "<aqua>" + it.name() + " uzbrojona. <gray>" + (p.kind().equals("polisa") ? "Zwróci stawkę po całkowitej przegranej do " : "Anuluje pierwszy błąd w grach krokowych przy stawkach do ")
                        + "<gold>" + Fmt.money(p.cap()));
                redraw();
            }
            case "crate" -> go(new CrateOpenGui(plugin, player, it.id(), type == ClickType.SHIFT_LEFT || type == ClickType.SHIFT_RIGHT ? Math.min(qty, 10) : 1));
            case "loot" -> {
                int n = type == ClickType.SHIFT_LEFT || type == ClickType.SHIFT_RIGHT ? qty : 1;
                if (!eco().takeItem(id, it.id(), n)) return;
                long value = it.sellValue() * n;
                eco().add(id, value);
                Fx.coin(player);
                T.send(player, "<green>Sprzedano <white>" + n + "x " + it.name() + "</white> za <gold>" + Fmt.money(value) + "</gold>.");
                redraw();
            }
            default -> {
                if (it.id().equals("koniczyna") && eco().takeItem(id, "koniczyna", 1)) {
                    eco().addBuff(id, "koniczyna", 5);
                    Fx.win(player);
                    T.send(player, "<green>Szczęście Ci sprzyja! Twoje następne <white>5</white> wygranych w hazardzie da więcej zysku (maks. <gold>"
                            + Fmt.money(plugin.catalog().bonuses.cloverMax()) + "</gold> na wygraną).");
                    redraw();
                }
            }
        }
    }

    private void sellAll() {
        long total = 0;
        int kinds = 0;
        for (Map.Entry<String, Integer> e : new ArrayList<>(eco().pd(id).inventory.entrySet())) {
            Catalog.Item it = plugin.catalog().item(e.getKey());
            if (it == null || !it.type().equals("loot")) continue;
            int qty = e.getValue();
            if (eco().takeItem(id, it.id(), qty)) {
                total += it.sellValue() * qty;
                kinds++;
            }
        }
        if (total <= 0) {
            Fx.deny(player);
            return;
        }
        eco().add(id, total);
        Fx.coin(player);
        T.send(player, "<green>Sprzedano cały łup (" + kinds + " rodzajów) za <gold>" + Fmt.money(total) + "</gold>.");
        redraw();
    }
}
