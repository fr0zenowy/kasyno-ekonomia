package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;
import pl.kasyno.show.Stage.P;
import pl.kasyno.work.Compass;
import pl.kasyno.work.JobDef;
import pl.kasyno.work.JobDef.NodeType;
import pl.kasyno.work.WorkService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Okno NPC od pracy w terenie: co się zbiera, ile płacą, czy masz narzędzie, ile jeszcze do końca przerwy i przycisk startu. */
public final class WorkGui extends Gui {
    /** Materiał ikony pracy w oknie (gracz z paczką zasobów widzi własną ikonę). */
    static final Map<String, Material> HEAD = Map.of(
            "lesnik", Material.RED_MUSHROOM,
            "gornik", Material.IRON_PICKAXE,
            "rybak", Material.FISHING_ROD,
            "sprzatacz", Material.BUCKET,
            "kurier", Material.CHEST,
            "poszukiwacz", Material.COMPASS);

    private final JobDef job;

    public WorkGui(KasynoPlugin plugin, Player player, JobDef job) {
        super(plugin, player);
        this.job = job;
    }

    @Override
    protected String art() {
        return "praca";
    }

    @Override
    protected Component title() {
        return T.c("<green><bold>" + T.esc(job.title()));
    }

    static Material headOf(JobDef job) {
        return HEAD.getOrDefault(job.id(), Material.IRON_AXE);
    }

    @Override
    protected void render() {
        WorkService work = plugin.work();
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));

        List<String> intro = new ArrayList<>(T.wrap(job.intro(), 38, "<gray>"));
        intro.add("");
        intro.add("<gray>Zlecający: <white>" + T.esc(job.npc()));
        set(4, ItemBuilder.of(headOf(job)).icon(job.icon()).name("<green><bold>" + T.esc(job.title())).lore(intro).build());

        // co się trafia
        int total = 0;
        for (NodeType t : job.types()) total += t.weight();
        int slot = 10;
        for (NodeType t : job.types()) {
            if (slot > 16) break;
            Material m = Material.matchMaterial(t.icon());
            double pct = 100.0 * t.weight() / total;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Wypłata: <gold>" + Fmt.num(t.min()) + (t.max() > t.min() ? "-" + Fmt.num(t.max()) : "") + " $");
            if (job.types().size() > 1) lore.add("<gray>Jak często: <white>" + (pct < 1 ? "bardzo rzadko" : String.format(Locale.ROOT, "%.0f%%", pct)));
            if (t.rare()) lore.add("<light_purple>Rzadki: lepsze narzędzie zwiększa szansę");
            set(slot++, ItemBuilder.of(m == null ? Material.PAPER : m).name("<white><bold>" + T.esc(t.name())).lore(lore).build());
        }

        WorkService.Site site = work.siteFor(player, job);
        WorkService.Status status = work.status(id);
        long left = eco().cooldownLeft(id, "work:" + job.id());
        Catalog.GatherTool tool = job.gatherKey() == null ? null : plugin.gather().bestTool(id, job.gatherKey());
        boolean needTool = job.gatherKey() != null && tool == null;

        // miejsce pracy
        List<String> where = new ArrayList<>();
        boolean near = false;
        if (site == null) {
            where.add("<red>W tym świecie nie ma NPC od tej pracy.");
            where.add("<gray>Administrator stawia go komendą:");
            where.add("<white>/kasyno npc spawn " + job.id() + " [promień]");
        } else {
            P c = site.center();
            double dist = new P(player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ()).flat(c);
            near = player.getWorld().getName().equals(site.world()) && dist <= site.radius() + 25;
            where.add("<gray>NPC: <white>" + Math.round(c.x()) + " " + Math.round(c.y()) + " " + Math.round(c.z()) + "<gray> • promień <white>" + site.radius() + " m");
            where.add(near ? "<green>Jesteś na miejscu." : "<red>Jesteś za daleko, podejdź do NPC.");
            if (site.source().equals("admin")) where.add("<yellow>Tryb administratora: praca w miejscu, w którym stoisz.");
        }
        set(20, ItemBuilder.of(Material.FILLED_MAP).icon("info").name("<aqua><bold>Miejsce pracy").lore(where).build());

        // narzędzie
        List<String> toolLore = new ArrayList<>();
        if (job.gatherKey() == null) toolLore.add("<green>Bez narzędzi: każdy może zacząć.");
        else if (tool == null) {
            toolLore.add("<red>Brak narzędzia ze sklepu.");
            toolLore.add("<gray>Kliknij, aby przejść do sklepu.");
        } else {
            toolLore.add("<gray>Masz: <white>" + (tool.id() == null ? "gołe ręce" : plugin.catalog().item(tool.id()).name()));
            toolLore.add("<gray>Szansa na rzadkie: <white>x" + String.format(Locale.ROOT, "%.1f", tool.luck()));
        }
        set(24, ItemBuilder.of(needTool ? Material.BARRIER : Material.IRON_PICKAXE).name(needTool ? "<red><bold>Narzędzie" : "<white><bold>Narzędzie").lore(toolLore).build(),
                needTool ? t -> go(new ShopGui(plugin, player)) : null);

        // zasady
        List<String> rules = new ArrayList<>();
        rules.add("<gray>Cel: <white>" + job.goal() + (job.kind() == JobDef.Kind.ROUTE ? " dostaw" : job.kind() == JobDef.Kind.HUNT ? " skarb" : " sztuk"));
        rules.add("<gray>Czas rundy: <white>" + Compass.clock(job.seconds() * 20L));
        rules.add("<gray>Premia za komplet: <green>+" + Math.round(job.completionBonus() * 100) + "%");
        rules.add("<gray>Przeciętnie: <gold>ok. " + Fmt.num(Math.round(job.expectedRound())) + " $ <gray>za rundę");
        rules.add("<gray>Przerwa po rundzie: <white>" + Fmt.duration(job.cooldownMs()));
        set(22, ItemBuilder.of(Material.CLOCK).name("<yellow><bold>Zasady").lore(rules).build());

        // przycisk
        if (status != null && status.job().equals(job)) {
            set(31, ItemBuilder.of(Material.REDSTONE_BLOCK).icon("close").name("<red><bold>Przerwij pracę").lore("<gray>Zebrano: <white>" + status.collected() + "/" + status.goal(),
                    "<gray>Zarobiono: <gold>" + Fmt.money(status.earned()), "<gray>Zostało: <white>" + Compass.clock(status.ticksLeft()), "", "<yellow>Kliknij, aby przerwać (przerwa i tak obowiązuje)").build(), t -> {
                work.cancel(player);
                redraw();
            });
        } else if (status != null) {
            set(31, ItemBuilder.of(Material.BARRIER).name("<red><bold>Masz już inną pracę").lore("<gray>Dokończ: <white>" + T.esc(status.job().title())).build());
        } else if (site == null) {
            set(31, ItemBuilder.of(Material.BARRIER).name("<red><bold>Brak miejsca pracy").lore("<gray>Znajdź NPC: <white>" + T.esc(job.npc())).build());
        } else if (needTool) {
            set(31, ItemBuilder.of(Material.BARRIER).name("<red><bold>Brak narzędzia").lore("<gray>Kup je w sklepie i wróć.").build(), t -> go(new ShopGui(plugin, player)));
        } else if (left > 0) {
            set(31, ItemBuilder.of(Material.CLOCK).name("<red><bold>Przerwa").lore("<gray>Następna runda za <white>" + Fmt.duration(left)).build());
        } else {
            boolean ready = near;
            set(31, ItemBuilder.of(Material.EMERALD).icon("play").glow(ready).name((ready ? "<green><bold>" : "<yellow><bold>") + "Zacznij pracę")
                    .lore("<gray>Cele pojawią się wokół NPC i będą świecić.", "<gray>Widzisz je tylko Ty.", "", ready ? "<yellow>Kliknij, aby zacząć" : "<red>Najpierw podejdź do NPC").build(), t -> startRound(site));
        }

        List<String> tips = new ArrayList<>();
        for (String tip : job.tips()) tips.addAll(T.wrap(tip, 38, "<gray>"));
        set(29, ItemBuilder.of(Material.WRITABLE_BOOK).icon("e_last").name("<white><bold>Wskazówki").lore(tips).build());

        WorkService.Summary last = work.lastSummary(id);
        if (last != null && last.job().equals(job)) {
            set(33, ItemBuilder.of(Material.PAPER).name("<white><bold>Ostatnia runda").lore("<gray>Zebrano: <white>" + last.collected() + "/" + last.goal(), "<gray>Zarobiono: <gold>" + Fmt.money(last.earned())
                    + (last.bonus() > 0 ? "<gray> (premia " + Fmt.money(last.bonus()) + ")" : "")).build());
        }

        backTo(49, () -> go(new WorkHubGui(plugin, player)));
    }

    /** Odświeża okno co sekundę, żeby przerwa i licznik rundy schodziły na bieżąco. */
    @Override
    public void open() {
        super.open();
        repeat(20L, 20L, this::redraw);
    }

    private void startRound(WorkService.Site site) {
        String err = plugin.work().start(player, job, site);
        if (err != null) {
            Fx.deny(player);
            T.send(player, "<red>" + err);
            redraw();
            return;
        }
        plugin.guis().next(player::closeInventory);
    }
}
