package pl.kasyno.prison;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;
import pl.kasyno.gui.screens.MainMenu;
import pl.kasyno.gui.screens.QuizGui;

/** Lista zadań więziennych: wyrok, czas do końca i cztery minigierki, z których każda zaliczona skraca pobyt o jedno zadanie. */
public final class PrisonGui extends Gui {
    public PrisonGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected boolean allowedInPrison() {
        return true;
    }

    @Override
    protected Component title() {
        return T.c("<dark_red><bold>Więzienie");
    }

    @Override
    public void open() {
        super.open();
        repeat(20, 20, this::redraw);
    }

    @Override
    protected void render() {
        PrisonService.Status st = plugin.prison().status(id);
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        if (st == null) {
            set(22, ItemBuilder.of(Material.IRON_BARS).name("<green><bold>Jesteś wolny").lore(T.wrap("Za kratki trafiają ci, którym nie uda się przestępstwo, okradanie gracza albo napad na bank. "
                    + "Z celi wychodzi się, wykonując zadania.", 36, "<gray>")).build());
            backTo(49, () -> go(new MainMenu(plugin, player)));
            fill(ItemBuilder.filler());
            return;
        }
        set(4, ItemBuilder.of(Material.IRON_BARS).name("<red><bold>" + st.reason().title())
                .lore("<gray>Cela: <white>" + (st.cell() + 1), "<gray>Zadania: <white>" + (st.tasksTotal() - st.tasksLeft()) + "/" + st.tasksTotal() + " <gray>(zostało " + st.tasksLeft() + ")",
                        "<gray>Wolność za: <white>" + PrisonRules.clock(st.secondsLeft()), "", "<gray>Każde zaliczone zadanie to o jedno mniej.", "<gray>Kto nic nie robi, wychodzi po czasie.").glow().build());
        task(19, Material.IRON_PICKAXE, "Podkop", "Klikaj kilof, zanim strażnik usłyszy: " + MashGui.NEEDED + " uderzeń w " + MashGui.WINDOW_MS / 1000 + " s.", () -> new MashGui(plugin, player));
        task(21, Material.TRIPWIRE_HOOK, "Refleks", "Klikaj zapalające się klucze. Trafiasz " + ReflexGui.HITS + " razy, mylisz się najwyżej " + ReflexGui.MAX_MISSES + ".", () -> new ReflexGui(plugin, player));
        task(23, Material.NOTE_BLOCK, "Sekwencja", "Powtórz ciąg " + SimonGui.LENGTH + " kolorów w tej samej kolejności.", () -> new SimonGui(plugin, player));
        task(25, Material.BOOK, "Egzamin", "Odpowiedz na pytanie (wiedza albo matma), żeby strażnik uwierzył, że się poprawiłeś.", () -> QuizGui.exam(plugin, player));
        closeButton(49);
        fill(ItemBuilder.filler());
    }

    private void task(int slot, Material m, String name, String desc, java.util.function.Supplier<Gui> open) {
        java.util.List<String> lore = new java.util.ArrayList<>(T.wrap(desc, 34, "<gray>"));
        lore.add("");
        lore.add("<yellow>Kliknij, aby zacząć");
        set(slot, ItemBuilder.of(m).name("<yellow><bold>" + name).lore(lore).build(), t -> {
            Fx.click(player);
            go(open.get());
        });
    }
}
