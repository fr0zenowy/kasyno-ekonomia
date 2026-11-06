package pl.kasyno.prison;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

/** Wspólna podstawa minigierek więziennych: dozwolone w celi, zaliczenie liczy się raz, a przycisk wraca do listy zadań. */
abstract class PrisonGame extends Gui {
    private boolean finished;

    protected PrisonGame(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    protected abstract String heading();

    @Override
    protected boolean allowedInPrison() {
        return true;
    }

    @Override
    protected Component title() {
        return T.c("<dark_gray><bold>" + heading());
    }

    protected boolean finished() {
        return finished;
    }

    /** Zadanie zaliczone (tylko raz na okno). Ostatnie zadanie wypuszcza gracza, a to zamyka okno. */
    protected void pass() {
        if (finished) return;
        finished = true;
        plugin.prison().completeTask(id);
    }

    /** Porażka w tej próbie; zadanie nadal czeka. */
    protected void failRound() {
        finished = true;
    }

    protected void hubButton(int slot) {
        set(slot, ItemBuilder.back(), t -> go(new PrisonGui(plugin, player)));
    }
}
