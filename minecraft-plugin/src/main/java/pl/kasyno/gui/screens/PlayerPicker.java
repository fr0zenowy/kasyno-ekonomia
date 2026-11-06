package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Wybór gracza online (przelew, okradanie, pojedynek): głowy graczy z podziałem na strony. */
public final class PlayerPicker extends Gui {
    private final String titleText;
    private final String art;
    private final Predicate<Player> filter;
    private final Consumer<Player> onPick;
    private final Runnable onBack;
    private int page;

    public PlayerPicker(KasynoPlugin plugin, Player player, String titleText, Predicate<Player> filter, Consumer<Player> onPick, Runnable onBack) {
        this(plugin, player, titleText, "gracz", filter, onPick, onBack);
    }

    /** @param art tło z paczki zasobów (np. "gracz_przelew"); każdy wybór gracza ma mieć własny tytuł, "gracz" to tytuł ogólny */
    public PlayerPicker(KasynoPlugin plugin, Player player, String titleText, String art, Predicate<Player> filter, Consumer<Player> onPick, Runnable onBack) {
        super(plugin, player);
        this.titleText = titleText;
        this.art = art;
        this.filter = filter;
        this.onPick = onPick;
        this.onBack = onBack;
    }

    @Override
    protected String art() {
        return art;
    }

    @Override
    protected Component title() {
        return T.c("<dark_aqua><bold>" + titleText);
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        List<Player> players = Bukkit.getOnlinePlayers().stream().map(p -> (Player) p)
                .filter(p -> !p.getUniqueId().equals(id) && filter.test(p)).sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName())).toList();
        int per = 45;
        int pages = Math.max(1, (players.size() + per - 1) / per);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < per; i++) {
            int idx = page * per + i;
            if (idx >= players.size()) break;
            Player target = players.get(idx);
            set(i, ItemBuilder.of(Material.PLAYER_HEAD).head(target).name("<yellow>" + T.esc(target.getName()))
                    .lore("<gray>Poziom: <white>" + eco().pd(target.getUniqueId()).level, "", "<green>Kliknij, aby wybrać").build(), t -> {
                        onPick.accept(target);
                    });
        }
        if (players.isEmpty()) set(22, ItemBuilder.of(Material.BARRIER).name("<red>Nikogo tu nie ma").lore("<gray>Żaden inny gracz nie jest teraz online.").build());
        if (page > 0) set(45, ItemBuilder.of(Material.ARROW).name("<yellow>« Poprzednia strona").build(), t -> {
            page--;
            redraw();
        });
        if (page < pages - 1) set(53, ItemBuilder.of(Material.ARROW).name("<yellow>Następna strona »").build(), t -> {
            page++;
            redraw();
        });
        set(49, ItemBuilder.back(), t -> onBack.run());
    }
}
