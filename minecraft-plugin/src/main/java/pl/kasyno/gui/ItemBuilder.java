package pl.kasyno.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Wygodne budowanie ikon w GUI. Teksty w MiniMessage (np. "<gold>Moneta</gold>"). */
public final class ItemBuilder {
    /** Znacznik własnej ikony z paczki zasobów; okno podmienia go na model przedmiotu tylko graczom, którzy paczkę mają. */
    public static final NamespacedKey ICON_KEY = NamespacedKey.fromString("kasyno:icon");

    private final ItemStack stack;
    private final ItemMeta meta;
    private final List<Component> lore = new ArrayList<>();

    private ItemBuilder(Material m) {
        this.stack = new ItemStack(m == null || m.isAir() ? Material.BARRIER : m);
        this.meta = stack.getItemMeta();
    }

    public static ItemBuilder of(Material m) {
        return new ItemBuilder(m);
    }

    public ItemBuilder name(String mm) {
        meta.displayName(T.c(mm));
        return this;
    }

    public ItemBuilder amount(int n) {
        stack.setAmount(Math.max(1, Math.min(64, n)));
        return this;
    }

    public ItemBuilder lore(String... lines) {
        for (String l : lines) lore.add(T.c(l));
        return this;
    }

    public ItemBuilder lore(List<String> lines) {
        for (String l : lines) lore.add(T.c(l));
        return this;
    }

    public ItemBuilder glow(boolean on) {
        if (on) meta.setEnchantmentGlintOverride(true);
        return this;
    }

    public ItemBuilder glow() {
        return glow(true);
    }

    /** Ikona bez dymka (wypełniacze). */
    public ItemBuilder hidden() {
        meta.setHideTooltip(true);
        return this;
    }

    /** Własna ikona z paczki zasobów (np. "bank"). Gracz bez paczki zobaczy zwykły materiał ustawiony w of(). */
    public ItemBuilder icon(String id) {
        if (id != null && ICON_KEY != null) meta.getPersistentDataContainer().set(ICON_KEY, PersistentDataType.STRING, id);
        return this;
    }

    public ItemBuilder head(org.bukkit.OfflinePlayer owner) {
        if (meta instanceof SkullMeta sm) sm.setOwningPlayer(owner);
        return this;
    }

    /** Dokłada znacznik ikony do gotowego przedmiotu, jeśli nie ma jeszcze żadnego (kopia; oryginał się nie zmienia). */
    public static ItemStack withIcon(ItemStack item, String id) {
        if (item == null || id == null || ICON_KEY == null) return item;
        ItemMeta m = item.getItemMeta();
        if (m == null || m.getPersistentDataContainer().has(ICON_KEY, PersistentDataType.STRING)) return item;
        ItemStack copy = item.clone();
        ItemMeta cm = copy.getItemMeta();
        cm.getPersistentDataContainer().set(ICON_KEY, PersistentDataType.STRING, id);
        copy.setItemMeta(cm);
        return copy;
    }

    public ItemStack build() {
        meta.lore(lore.isEmpty() ? null : lore);
        meta.addItemFlags(ItemFlag.values());
        stack.setItemMeta(meta);
        return stack;
    }

    // ---------- gotowce ----------

    public static ItemStack filler(Material m) {
        return of(m).name(" ").hidden().icon("blank").build();
    }

    public static ItemStack filler() {
        return filler(Material.GRAY_STAINED_GLASS_PANE);
    }

    public static ItemStack back() {
        return of(Material.ARROW).name("<yellow>« Wróć").lore("<gray>Wróć do poprzedniego menu").icon("back").build();
    }

    public static ItemStack close() {
        return of(Material.BARRIER).name("<red>Zamknij").icon("close").build();
    }

    public static ItemStack of(Material m, String name, String... lore) {
        return of(m).name(name).lore(Arrays.asList(lore)).build();
    }
}
