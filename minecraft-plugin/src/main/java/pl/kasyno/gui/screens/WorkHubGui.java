package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;
import pl.kasyno.work.JobDef;
import pl.kasyno.work.Jobs;
import pl.kasyno.work.WorkService;

import java.util.ArrayList;
import java.util.List;

/** Prace w terenie: lista wszystkich zleceń od NPC ze stanem (gotowe, przerwa, brak narzędzia) i przejściem do okna pracy. */
public final class WorkHubGui extends Gui {
    private static final int[] SLOTS = {11, 13, 15, 29, 31, 33};

    public WorkHubGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String art() {
        return "praca";
    }

    @Override
    protected Component title() {
        return T.c("<green><bold>Prace w terenie");
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        set(4, ItemBuilder.of(Material.MAP).icon("info").name("<aqua><bold>Jak to działa")
                .lore(T.wrap("Podejdź do NPC od pracy, zacznij rundę, a wokół niego pojawią się świecące cele widoczne tylko dla Ciebie. Znajdź je i kliknij, żeby dostać pieniądze od razu. Po rundzie jest przerwa.", 38, "<gray>")).build());

        WorkService work = plugin.work();
        WorkService.Status active = work.status(id);
        for (int i = 0; i < Jobs.ALL.size() && i < SLOTS.length; i++) {
            JobDef job = Jobs.ALL.get(i);
            long left = eco().cooldownLeft(id, "work:" + job.id());
            Catalog.GatherTool tool = job.gatherKey() == null ? null : plugin.gather().bestTool(id, job.gatherKey());
            boolean needTool = job.gatherKey() != null && tool == null;
            List<String> lore = new ArrayList<>(T.wrap(job.intro(), 38, "<gray>"));
            lore.add("");
            lore.add("<gray>Zlecający: <white>" + T.esc(job.npc()));
            lore.add("<gray>Przeciętnie: <gold>ok. " + Fmt.num(Math.round(job.expectedRound())) + " $ <gray>za rundę");
            if (active != null && active.job().equals(job)) lore.add("<green>W toku: " + active.collected() + "/" + active.goal());
            else if (needTool) lore.add("<red>Brak narzędzia ze sklepu");
            else if (left > 0) lore.add("<red>Przerwa: " + Fmt.duration(left));
            else lore.add("<green>Gotowe!");
            lore.add("");
            lore.add("<yellow>Kliknij, aby zobaczyć szczegóły");
            set(SLOTS[i], ItemBuilder.of(WorkGui.headOf(job)).icon(job.icon()).glow(left == 0 && !needTool).name("<green><bold>" + T.esc(job.title())).lore(lore).build(),
                    t -> go(new WorkGui(plugin, player, job)));
        }
        backTo(49, () -> go(new EarnGui(plugin, player)));
    }
}
