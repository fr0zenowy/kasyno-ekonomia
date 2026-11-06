package pl.kasyno.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Eco;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bazowa klasa okna GUI. Podklasa opisuje wygląd w render() (set/fill), a kliknięcia obsługują uchwyty podpięte do slotów.
 * Zadania zaplanowane przez okno (animacje) kończą się same, gdy gracz je zamknie.
 */
public abstract class Gui implements InventoryHolder {
    @FunctionalInterface
    public interface Click {
        void on(ClickType type);
    }

    protected final KasynoPlugin plugin;
    protected final Player player;
    protected final UUID id;
    protected Inventory inv;
    private final Map<Integer, Click> handlers = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean closed;
    private boolean custom;

    protected Gui(KasynoPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.id = player.getUniqueId();
    }

    protected abstract Component title();

    /**
     * Nazwa tła z paczki zasobów dla tego okna (np. "sklep"). Gracz z paczką widzi wtedy własną grafikę zamiast zwykłego okna,
     * a bez paczki okno wygląda po staremu.
     */
    protected String art() {
        return "default";
    }

    /** Zawsze 6 rzędów: tła z paczki zasobów są rysowane pod okno o 54 slotach, więc inny rozmiar rozjechałby okienka na przedmioty. */
    protected final int rows() {
        return 6;
    }

    /** Czy to okno wolno otworzyć więźniowi (tylko lista zadań, minigierki i egzamin). */
    protected boolean allowedInPrison() {
        return false;
    }

    /** Czy ten gracz widzi paczkę zasobów kasyna (własne tła i ikony). Ustalane przy otwarciu okna. */
    protected boolean custom() {
        return custom;
    }

    /** Wypełnia okno ikonami. Wywoływane przy otwarciu i przy każdym redraw(). */
    protected abstract void render();

    /** Sprzątanie po zamknięciu (np. rozliczenie gry w toku). */
    protected void onClose() {}

    protected Eco eco() {
        return plugin.eco();
    }

    public Player player() {
        return player;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public void open() {
        if (!allowedInPrison() && plugin.prison() != null && plugin.prison().restricted(id)) {
            // w celi działa tylko lista zadań; zamknięte okno nie odpala już swoich opóźnionych zadań
            closed = true;
            T.send(player, "<red>Siedzisz w więzieniu. Wyjdziesz po wykonaniu zadań: <white>/kasyno wiezienie");
            Fx.deny(player);
            return;
        }
        custom = plugin.packs().custom(player);
        inv = Bukkit.createInventory(this, rows() * 9, custom ? plugin.packs().title(art(), title()) : title());
        closed = false;
        render();
        player.openInventory(inv);
        plugin.guis().track(this);
    }

    /** Przerysowuje zawartość bez zamykania okna. */
    public void redraw() {
        if (inv == null) return;
        handlers.clear();
        inv.clear();
        render();
    }

    /** Podmienia ikonę na własną z paczki zasobów, jeśli gracz ją ma (inaczej zostaje zwykły przedmiot). */
    protected ItemStack skin(ItemStack item) {
        return custom ? plugin.packs().skin(item) : item;
    }

    protected void set(int slot, ItemStack item, Click click) {
        if (slot < 0 || slot >= inv.getSize()) return;
        inv.setItem(slot, skin(item));
        if (click != null) handlers.put(slot, click);
        else handlers.remove(slot);
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected void fill(ItemStack item) {
        ItemStack skinned = skin(item);
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, skinned);
    }

    protected void border(ItemStack item) {
        ItemStack skinned = skin(item);
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            int row = i / 9;
            int col = i % 9;
            if (row == 0 || row == rows() - 1 || col == 0 || col == 8) inv.setItem(i, skinned);
        }
    }

    protected void clearSlot(int slot) {
        inv.setItem(slot, null);
        handlers.remove(slot);
    }

    protected BukkitTask later(long ticks, Runnable r) {
        BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!closed) r.run();
        }, ticks);
        tasks.add(t);
        return t;
    }

    protected BukkitTask repeat(long delay, long period, Runnable r) {
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!closed) r.run();
        }, delay, period);
        tasks.add(t);
        return t;
    }

    protected void cancelTasks() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }

    /** Przechodzi do innego okna w następnym ticku (nie zamykamy okna w jego własnym zdarzeniu kliknięcia). */
    protected void go(Gui next) {
        Fx.click(player);
        plugin.guis().next(next::open);
    }

    /** Przycisk „wróć” w rogu okna. */
    protected void backTo(int slot, Runnable open) {
        set(slot, ItemBuilder.back(), t -> {
            Fx.click(player);
            open.run();
        });
    }

    protected void closeButton(int slot) {
        set(slot, ItemBuilder.close(), t -> player.closeInventory());
    }

    // ---------- zdarzenia (wołane przez GuiListener) ----------

    void handleClick(InventoryClickEvent e) {
        Click c = handlers.get(e.getRawSlot());
        if (c == null) return;
        try {
            c.on(e.getClick());
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Błąd w GUI " + getClass().getSimpleName() + ": " + ex);
            ex.printStackTrace();
            player.sendMessage(T.c("<red>Coś poszło nie tak. Spróbuj ponownie."));
        }
    }

    void handleClose() {
        if (closed) return;
        closed = true;
        cancelTasks();
        try {
            onClose();
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Błąd przy zamykaniu GUI " + getClass().getSimpleName() + ": " + ex);
            ex.printStackTrace();
        }
        plugin.guis().untrack(this);
    }

    /** Czy to okno jest teraz otwarte u gracza (a nie zastąpione innym). */
    protected boolean isOpen() {
        return !closed && player.isOnline() && player.getOpenInventory().getTopInventory() != null && player.getOpenInventory().getTopInventory().getHolder() == this;
    }

    // ---------- pomocnicze ikony ----------

    protected static ItemStack icon(Material m, String name, String... lore) {
        return ItemBuilder.of(m, name, lore);
    }
}
