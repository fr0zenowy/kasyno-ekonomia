package pl.kasyno.gui;

import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.games.GameRegistry;
import pl.kasyno.gui.screens.BankGui;
import pl.kasyno.gui.screens.CasinoGui;
import pl.kasyno.gui.screens.CratesGui;
import pl.kasyno.gui.screens.EarnGui;
import pl.kasyno.gui.screens.EmpireGui;
import pl.kasyno.gui.screens.HallGui;
import pl.kasyno.gui.screens.HelpGui;
import pl.kasyno.gui.screens.InventoryGui;
import pl.kasyno.gui.screens.LobbyGui;
import pl.kasyno.gui.screens.MainMenu;
import pl.kasyno.gui.screens.ProfileGui;
import pl.kasyno.gui.screens.RankingGui;
import pl.kasyno.gui.screens.ShopGui;
import pl.kasyno.gui.screens.WorkGui;
import pl.kasyno.prison.PrisonGui;
import pl.kasyno.work.JobDef;
import pl.kasyno.work.Jobs;

import java.util.ArrayList;
import java.util.List;

/** Wejście do ekranów po nazwie: używają go komendy i NPC. */
public final class Screens {
    private Screens() {}

    /** Ekrany bez gier: menu, hol kasyna z czterema salami, pokój graczy i ekrany ekonomii. */
    public static final List<String> BASE = List.of("menu", "kasyno", "automaty", "karty", "ryzyko", "live", "zarobki", "sklep", "ekwipunek", "bank", "biznes", "skrzynie", "ranking", "profil", "pokoj", "pomoc");

    /** Ekrany bez własnego tła w paczce zasobów (więzienie), obok prac w terenie. */
    private static final List<String> PLAIN = List.of("wiezienie");

    /** Wszystkie dostępne nazwy (ekrany + gry), np. do podpowiedzi przy komendzie NPC. */
    public static List<String> keys() {
        List<String> all = new ArrayList<>(BASE);
        GameRegistry.all().forEach(g -> all.add(g.id()));
        return all;
    }

    /**
     * Nazwy dla NPC i placeholderów: ekrany, gry i prace w terenie (lesnik, gornik...). Prace nie wchodzą do keys(): to okna bez własnego
     * tła w paczce zasobów, więc korzystają z tła domyślnego.
     */
    public static List<String> allKeys() {
        List<String> all = keys();
        all.addAll(PLAIN);
        all.addAll(Jobs.ids());
        return all;
    }

    public static boolean exists(String key) {
        return BASE.contains(key) || PLAIN.contains(key) || GameRegistry.find(key) != null || Jobs.isJob(key);
    }

    /** Otwiera ekran. Zwraca false, gdy nazwa jest nieznana. */
    public static boolean open(KasynoPlugin plugin, Player p, String key) {
        Gui gui = create(plugin, p, key);
        if (gui == null) return false;
        gui.open();
        return true;
    }

    public static Gui create(KasynoPlugin plugin, Player p, String key) {
        return switch (key) {
            case "menu" -> new MainMenu(plugin, p);
            case "kasyno" -> new CasinoGui(plugin, p);
            case "automaty", "karty", "ryzyko", "live" -> new HallGui(plugin, p, key);
            case "zarobki" -> new EarnGui(plugin, p);
            case "sklep" -> new ShopGui(plugin, p);
            case "ekwipunek" -> new InventoryGui(plugin, p);
            case "bank" -> new BankGui(plugin, p);
            case "biznes" -> new EmpireGui(plugin, p);
            case "skrzynie" -> new CratesGui(plugin, p);
            case "ranking" -> new RankingGui(plugin, p);
            case "profil" -> new ProfileGui(plugin, p, p.getUniqueId());
            case "pokoj" -> new LobbyGui(plugin, p);
            case "pomoc" -> new HelpGui(plugin, p);
            case "wiezienie" -> new PrisonGui(plugin, p);
            default -> {
                JobDef job = Jobs.find(key);
                if (job != null) yield new WorkGui(plugin, p, job);
                GameRegistry.Def g = GameRegistry.find(key);
                yield g == null ? null : g.factory().apply(plugin, p);
            }
        };
    }
}
