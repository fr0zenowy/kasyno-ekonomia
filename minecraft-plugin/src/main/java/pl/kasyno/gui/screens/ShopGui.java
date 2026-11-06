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
import java.util.List;

/** Sklep: najpierw kategorie, potem przedmioty z opisami. Lewy klik kupuje jedną sztukę, shift+klik dziesięć. */
public final class ShopGui extends Gui {
    private static final Material[] CATEGORY_ICONS = {Material.FISHING_ROD, Material.CROSSBOW, Material.TRIPWIRE_HOOK, Material.BONE, Material.GOLD_INGOT, Material.GOLD_BLOCK,
            Material.POTION, Material.SHIELD, Material.CHEST, Material.DIAMOND};
    /** Własne ikony kategorii z paczki zasobów (id kategorii z katalogu). */
    private static final java.util.Map<String, String> CATEGORY_ICON_IDS = java.util.Map.of("wedki", "s_wedki", "bron", "e_hunt", "ochrona", "s_ochrona", "zwierzaki", "s_zwierzaki", "vip", "s_vip",
            "finanse", "m_bank", "eliksiry", "s_eliksiry", "gry", "kosci", "skrzynie", "m_skrzynie", "luksus", "s_luksus");
    private static final int[] CATEGORY_SLOTS = {10, 11, 12, 13, 14, 15, 16, 20, 22, 24};

    private final Catalog.Category category;

    public ShopGui(KasynoPlugin plugin, Player player) {
        this(plugin, player, null);
    }

    public ShopGui(KasynoPlugin plugin, Player player, String categoryId) {
        super(plugin, player);
        this.category = categoryId == null ? null : plugin.catalog().categories.stream().filter(c -> c.id().equals(categoryId)).findFirst().orElse(null);
    }

    @Override
    protected String art() {
        return "sklep";
    }

    @Override
    protected Component title() {
        return T.c("<aqua><bold>Sklep" + (category == null ? "" : " <dark_gray>» <white>" + category.title()));
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        set(4, ItemBuilder.of(Material.GOLD_NUGGET).icon("moneta").name("<gold>Portfel: " + Fmt.money(eco().wallet(id))).lore("<gray>Lewy klik: kup 1", "<gray>Shift + klik: kup 10").build());
        if (category == null) categories();
        else items();
    }

    private void categories() {
        List<Catalog.Category> cats = plugin.catalog().categories;
        for (int i = 0; i < cats.size() && i < CATEGORY_SLOTS.length; i++) {
            Catalog.Category c = cats.get(i);
            set(CATEGORY_SLOTS[i], ItemBuilder.of(CATEGORY_ICONS[i % CATEGORY_ICONS.length]).icon(CATEGORY_ICON_IDS.get(c.id())).name("<yellow><bold>" + c.title())
                    .lore("<gray>Przedmiotów: <white>" + c.items().size(), "", "<green>Kliknij, aby przeglądać").build(), t -> go(new ShopGui(plugin, player, c.id())));
        }
        backTo(49, () -> go(new MainMenu(plugin, player)));
    }

    private void items() {
        Catalog cat = plugin.catalog();
        int slot = 9;
        for (String itemId : category.items()) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 45) break;
            Catalog.Item it = cat.item(itemId);
            final int s = slot++;
            set(s, view(it), t -> buy(it, t == ClickType.SHIFT_LEFT || t == ClickType.SHIFT_RIGHT ? 10 : 1));
        }
        backTo(45, () -> go(new ShopGui(plugin, player)));
        backTo(49, () -> go(new MainMenu(plugin, player)));
    }

    private boolean single(Catalog.Item it) {
        return it.unique() || it.type().equals("tool") || it.type().equals("trophy");
    }

    private org.bukkit.inventory.ItemStack view(Catalog.Item it) {
        boolean owned = single(it) && eco().qty(id, it.id()) > 0;
        boolean locked = it.requires() != null && eco().qty(id, it.requires()) < 1;
        boolean afford = eco().wallet(id) >= it.price();
        List<String> lore = new ArrayList<>(T.wrap(it.desc(), 36, "<gray>"));
        lore.add("");
        lore.add("<gray>Cena: <gold>" + Fmt.money(it.price()));
        int have = eco().qty(id, it.id());
        if (have > 0) lore.add("<gray>Masz: <white>" + have);
        if (locked) lore.add("<red>Wymaga: " + plugin.catalog().item(it.requires()).name());
        else if (owned) lore.add("<green>Już to masz");
        else if (!afford) lore.add("<red>Za mało monet");
        else lore.add(single(it) ? "<yellow>Kliknij, aby kupić" : "<yellow>Kliknij: kup 1 <dark_gray>| <yellow>Shift: kup 10");
        return ItemBuilder.of(Icons.of(it)).name((owned ? "<green>" : locked ? "<red>" : "<white>") + "<bold>" + it.name()).glow(Icons.glows(it.id()) || owned).lore(lore).build();
    }

    private void buy(Catalog.Item it, int qty) {
        if (single(it)) {
            if (qty > 1) qty = 1;
            if (eco().qty(id, it.id()) > 0) {
                Fx.deny(player);
                T.send(player, "<red>Już masz: " + it.name());
                return;
            }
        }
        if (it.requires() != null && eco().qty(id, it.requires()) < 1) {
            Fx.deny(player);
            T.send(player, "<red>Najpierw kup: <white>" + plugin.catalog().item(it.requires()).name());
            return;
        }
        long cost = it.price() * qty;
        if (!eco().take(id, cost)) {
            Fx.deny(player);
            T.send(player, "<red>Potrzebujesz <gold>" + Fmt.money(cost) + "</gold>, a masz <gold>" + Fmt.money(eco().wallet(id)) + "</gold>.");
            return;
        }
        eco().addItem(id, it.id(), qty);
        Fx.coin(player);
        T.send(player, "<green>Kupiłeś <white>" + qty + "x " + it.name() + "</white> za <gold>" + Fmt.money(cost) + "</gold>.");
        redraw();
    }
}
