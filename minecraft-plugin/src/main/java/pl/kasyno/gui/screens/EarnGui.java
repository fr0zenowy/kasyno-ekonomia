package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.PlayerData;
import pl.kasyno.gui.Actions;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.Notify;
import pl.kasyno.gui.Scenes;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Zarobki: nagrody czasowe, praca, zbieractwo, uliczny grajek, quizy, przestępstwa i okradanie graczy. */
public final class EarnGui extends Gui {
    private List<String> last = List.of("<gray>Tu zobaczysz wynik ostatniej akcji.");

    public EarnGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String art() {
        return "zarobki";
    }

    @Override
    protected Component title() {
        return T.c("<green><bold>Zarobki");
    }

    private String cd(long left) {
        return left > 0 ? "<red>Cooldown: " + Fmt.duration(left) : "<green>Gotowe!";
    }

    private void tile(int slot, Material m, String icon, String name, long left, String desc, Click click) {
        set(slot, ItemBuilder.of(m).icon(icon).name(name).glow(left == 0).lore("<gray>" + desc, "", cd(left), left == 0 ? "<yellow>Kliknij, aby wykonać" : "").build(), click);
    }

    /**
     * Akcje z fabułą (przestępstwo, praca, zbieranie, grajek, łowienie...) najpierw odgrywają krótką scenę, a dopiero jej ostatni krok
     * wykonuje akcję. Zamknięcie okna w trakcie sceny anuluje akcję bez kosztów.
     */
    private void scene(String key, String cooldownKey, String target, Supplier<List<String>> action) {
        long left = eco().cooldownLeft(id, cooldownKey);
        if (left > 0) {
            Notify.cooldown(player, left);
            return;
        }
        String[] h = Scenes.heading(key);
        List<String> before = last;
        go(new SceneGui(plugin, player, h[0], h[1], h[2], Scenes.script(key, eco().rng, target), action, lines -> new EarnGui(plugin, player).openWith(lines != null ? lines : before)));
    }

    private void claim(String what) {
        if (Scenes.script(what, eco().rng, "") != null) {
            scene(what, what.equals("przestepstwo") ? "crime" : what, "", () -> Actions.claim(plugin, player, what));
            return;
        }
        List<String> lines = Actions.claim(plugin, player, what);
        if (lines != null) last = lines;
        redraw();
    }

    @Override
    protected void render() {
        PlayerData d = eco().pd(id);
        Catalog cat = plugin.catalog();
        long now = eco().now();
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));

        tile(10, Material.GOLD_INGOT, "e_daily", "<gold><bold>Nagroda dzienna", Math.max(0, d.lastDaily + Eco.DAILY_MS - now), "Seria dni, odsetki z banku. Co 7 dni skrzynia!", t -> claim("daily"));
        tile(11, Material.DIAMOND, "e_weekly", "<aqua><bold>Nagroda tygodniowa", Math.max(0, d.lastWeekly + Eco.WEEK_MS - now), "Gotówka i skrzynia skarbów.", t -> claim("weekly"));
        tile(12, Material.CLOCK, "e_hourly", "<yellow><bold>Premia godzinna", eco().cooldownLeft(id, "godzinna"), "Rośnie z poziomem, czasem skrzynka.", t -> claim("godzinna"));
        Catalog.Job job = cat.job(d.job);
        tile(13, Material.IRON_AXE, "e_work", "<green><bold>Praca: " + job.name(), eco().cooldownLeft(id, "praca"), "Wypłata " + Fmt.num(job.min()) + "-" + Fmt.num(job.max()) + " $.", t -> claim("praca"));
        tile(14, Material.BOWL, "e_beg", "<gray><bold>Zbieranie drobnych", eco().cooldownLeft(id, "zebraj"), "Poproś przechodniów o drobne.", t -> claim("zebraj"));
        tile(15, Material.NOTE_BLOCK, "e_busk", "<light_purple><bold>Grajek uliczny", eco().cooldownLeft(id, "uliczny"), "Instrument zwiększa napiwki (gitara, skrzypce, fortepian).", t -> claim("uliczny"));
        tile(16, Material.IRON_BARS, "napad", "<red><bold>Przestępstwo", eco().cooldownLeft(id, "crime"), "Szansa 50%. Kominiarka zwiększa szansę.", t -> claim("przestepstwo"));

        gatherTile(19, "ryby", Material.FISHING_ROD, "Łowienie ryb", "Wymaga wędki ze sklepu.");
        gatherTile(20, "kopalnia", Material.IRON_PICKAXE, "Kopalnia", "Wymaga kilofa ze sklepu.");
        gatherTile(21, "polowanie", Material.BOW, "Polowanie", "Wymaga broni ze sklepu.");
        gatherTile(22, "las", Material.RED_MUSHROOM, "Grzybobranie", "Za darmo. Koszyk zwiększa zbiory.");
        gatherTile(23, "smietnik", Material.BUCKET, "Śmietniki", "Za darmo. Czasem trafi się skarb.");
        set(25, ItemBuilder.of(Material.COMPASS).icon("e_forest").glow().name("<green><bold>Prace w terenie").lore("<gray>Zlecenia od NPC: grzyby w lesie, kopalnia,", "<gray>ryby, sprzątanie, paczki i skarby.", "<gray>Świecące cele szukasz w świecie.", "", "<yellow>Kliknij, aby zobaczyć").build(), t -> go(new WorkHubGui(plugin, player)));

        tile(28, Material.BOOK, "e_trivia", "<aqua><bold>Trivia", eco().cooldownLeft(id, "trivia"), "Pytanie z czterema odpowiedziami. Nagroda 200-450 $.", t -> quiz(true));
        tile(29, Material.COMPARATOR, "e_math", "<aqua><bold>Matma", eco().cooldownLeft(id, "matma"), "Szybkie liczenie. Nagroda 120-300 $.", t -> quiz(false));
        tile(30, Material.TRIPWIRE_HOOK, "e_rob", "<red><bold>Okradnij gracza", eco().cooldownLeft(id, "rob"), "Szansa 45%. Ofiara może mieć kłódkę, psa i alarm.",
                t -> go(new PlayerPicker(plugin, player, "Kogo okraść?", "gracz_rob", p -> true, this::rob, () -> go(new EarnGui(plugin, player)))));
        set(31, ItemBuilder.of(Material.TNT).icon("napad").name("<gold><bold>Napad na bank").lore("<gray>Napad z ekipą organizujesz w", "<gray>pokoju graczy.", "", "<yellow>Kliknij, aby przejść").build(), t -> go(new LobbyGui(plugin, player)));
        set(33, ItemBuilder.of(Material.LEATHER_HELMET).icon("e_job").name("<yellow><bold>Zmień zawód").lore("<gray>Obecny: <white>" + job.name(), "<gray>Lepsze zawody odblokowuje poziom.", "", "<yellow>Kliknij, aby wybrać").build(), t -> go(new JobGui(plugin, player)));

        set(40, ItemBuilder.of(Material.WRITABLE_BOOK).icon("e_last").name("<white><bold>Ostatni wynik").lore(new ArrayList<>(last)).build());
        backTo(49, () -> go(new MainMenu(plugin, player)));
    }

    private void gatherTile(int slot, String key, Material m, String name, String desc) {
        Catalog.GatherTool tool = plugin.gather().bestTool(id, key);
        long left = eco().cooldownLeft(id, key);
        String toolLine = tool == null ? "<red>Brak narzędzia!" : "<gray>Narzędzie: <white>" + (tool.id() == null ? "ręce" : plugin.catalog().item(tool.id()).name());
        set(slot, ItemBuilder.of(m).icon(switch (key) {
            case "ryby" -> "e_fish";
            case "kopalnia" -> "m_zarobki";
            case "polowanie" -> "e_hunt";
            case "las" -> "e_forest";
            default -> "e_trash";
        }).name("<green><bold>" + name).glow(left == 0 && tool != null).lore("<gray>" + desc, toolLine, "", cd(left), "<yellow>Kliknij, aby wykonać").build(), t -> {
            if (tool == null) {
                // bez narzędzia nie ma czego odgrywać: od razu komunikat
                List<String> lines = Actions.gather(plugin, player, key);
                if (lines != null) last = lines;
                redraw();
                return;
            }
            scene(key, key, "", () -> Actions.gather(plugin, player, key));
        });
    }

    private void rob(Player victim) {
        scene("rob", "rob", victim.getName(), () -> Actions.rob(plugin, player, victim));
    }

    /** Otwiera okno z ustawionym ostatnim wynikiem (po wyborze gracza w osobnym oknie). */
    void openWith(List<String> lines) {
        last = lines;
        open();
    }

    private void quiz(boolean trivia) {
        String key = trivia ? "trivia" : "matma";
        long left = eco().cooldownLeft(id, key);
        if (left > 0) {
            Notify.cooldown(player, left);
            return;
        }
        eco().guardCooldown(id, key, eco().cooldownOf(key));
        Fx.click(player);
        go(trivia ? QuizGui.trivia(plugin, player) : QuizGui.math(plugin, player));
    }
}
