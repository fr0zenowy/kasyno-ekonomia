package pl.kasyno.games;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.games.held.BlackjackGui;
import pl.kasyno.games.held.HiloGui;
import pl.kasyno.games.held.KurczakGui;
import pl.kasyno.games.held.MinyGui;
import pl.kasyno.games.held.WiezaGui;
import pl.kasyno.games.instant.KnpGui;
import pl.kasyno.games.instant.KosciGui;
import pl.kasyno.games.instant.LimboGui;
import pl.kasyno.games.instant.MonetaGui;
import pl.kasyno.games.instant.PlinkoGui;
import pl.kasyno.games.instant.RewolwerGui;
import pl.kasyno.games.instant.SlotyGui;
import pl.kasyno.games.instant.SzansaGui;
import pl.kasyno.games.instant.ZdrapkaGui;
import pl.kasyno.gui.Gui;
import pl.kasyno.table.CrashView;
import pl.kasyno.table.KoloView;
import pl.kasyno.table.RaceView;
import pl.kasyno.table.RouletteView;

import java.util.List;
import java.util.function.BiFunction;

/** Lista gier i sal kasyna: nazwa, ikona, opis, sala i fabryka ekranu. Używają jej menu, komendy i NPC. */
public final class GameRegistry {
    private GameRegistry() {}

    /** Sala kasyna: osobny ekran z grami jednego rodzaju. */
    public record Hall(String id, String name, Material icon, String desc) {}

    public record Def(String id, String name, Material icon, String desc, String hall, String tags, boolean live, BiFunction<KasynoPlugin, Player, Gui> factory) {}

    public static final List<Hall> HALLS = List.of(
            new Hall("automaty", "Automaty", Material.NOTE_BLOCK, "Sloty, zdrapki, moneta i szansa: szybkie losowania."),
            new Hall("karty", "Karty i kości", Material.PAPER, "Blackjack, hi-lo, kości i kamień-papier-nożyce."),
            new Hall("ryzyko", "Ryzyko i mnożniki", Material.TNT, "Miny, kurczak, wieża, plinko, limbo i rewolwer."),
            new Hall("live", "Stoły na żywo", Material.PLAYER_HEAD, "Crash, ruletka, koło i wyścigi: jedna runda dla wszystkich."));

    private static final List<Def> ALL = List.of(
            new Def("moneta", "Moneta", Material.GOLD_INGOT, "Orzeł czy reszka? Wygrana x1,95.", "automaty", "x1,95 • 50%", false, MonetaGui::new),
            new Def("sloty", "Sloty", Material.NOTE_BLOCK, "Trzy bębny, trzy siódemki i jackpot x200.", "automaty", "do x200", false, SlotyGui::new),
            new Def("zdrapka", "Zdrapka", Material.PAPER, "Zdrap pola, trzy takie same to wygrana.", "automaty", "do x500", false, ZdrapkaGui::new),
            new Def("szansa", "Szansa", Material.COMPARATOR, "Sam ustawiasz szansę na wygraną (0,5-95%).", "automaty", "do x192", false, SzansaGui::new),

            new Def("blackjack", "Blackjack", Material.BOOK, "Ugraj 21. Blackjack płaci 3:2.", "karty", "3:2 • double", false, BlackjackGui::new),
            new Def("hilo", "Wyżej czy niżej", Material.ENDER_PEARL, "Zgaduj kolejne karty i buduj serię.", "karty", "do x1000", false, HiloGui::new),
            new Def("kosci", "Kości", Material.WHITE_CONCRETE, "Poniżej, powyżej albo dokładnie 7.", "karty", "x2,3 • x5,7", false, KosciGui::new),
            new Def("knp", "Kamień, papier, nożyce", Material.SHEARS, "Pojedynek z botem o stawkę.", "karty", "x1,9", false, KnpGui::new),

            new Def("miny", "Miny", Material.TNT, "Odkrywaj diamenty, omijaj miny.", "ryzyko", "do x1000 • tarcza", false, MinyGui::new),
            new Def("kurczak", "Chicken Road", Material.EGG, "Przeprowadź kurczaka przez ulicę.", "ryzyko", "do x1000 • tarcza", false, KurczakGui::new),
            new Def("wieza", "Wieża", Material.STONE_BRICKS, "Wybierz bezpieczne pole na każdym piętrze.", "ryzyko", "do x1000 • tarcza", false, WiezaGui::new),
            new Def("rewolwer", "Rewolwer", Material.CROSSBOW, "Rosyjska ruletka: więcej naboi, większa wypłata.", "ryzyko", "do x5,76", false, RewolwerGui::new),
            new Def("limbo", "Limbo", Material.END_CRYSTAL, "Wybierz cel. Jeśli wynik go dobije, wygrywasz.", "ryzyko", "do x1000", false, LimboGui::new),
            new Def("plinko", "Plinko", Material.SLIME_BALL, "Kulka spada przez kołki do koszyka.", "ryzyko", "do x1000", false, PlinkoGui::new),

            new Def("crash", "Crash", Material.FIREWORK_ROCKET, "Wspólny mnożnik rośnie na żywo. Wypłać przed crashem.", "live", "do x10 000 • razem", true, CrashView::new),
            new Def("ruletka", "Ruletka", Material.RED_CONCRETE, "Jedno koło dla stołu: kolory, tuziny i numery.", "live", "x2 • x3 • x36 • razem", true, RouletteView::new),
            new Def("kolo", "Koło fortuny", Material.COMPASS, "Jeden obrót dla wszystkich: od x0 do x200.", "live", "do x200 • razem", true, KoloView::new),
            new Def("wyscigi", "Wyścigi konne", Material.SADDLE, "Obstaw konia, wyścig biegnie dla całego stołu.", "live", "x2,7 - x11,8 • razem", true, RaceView::new));

    public static List<Def> all() {
        return ALL;
    }

    public static Def find(String id) {
        for (Def d : ALL) if (d.id().equals(id)) return d;
        return null;
    }

    public static List<Def> inHall(String hall) {
        return ALL.stream().filter(g -> g.hall().equals(hall)).toList();
    }

    public static Hall hall(String id) {
        for (Hall h : HALLS) if (h.id().equals(id)) return h;
        return null;
    }

    /** Sala, do której należy gra (do przycisku „wróć”). */
    public static String hallOf(String gameId) {
        Def d = find(gameId);
        return d == null ? null : d.hall();
    }
}
