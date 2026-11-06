package pl.sport.command;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import pl.sport.SportPlugin;
import pl.sport.arena.Arena;
import pl.sport.core.Team;
import pl.sport.core.V3;
import pl.sport.match.Match;
import pl.sport.pingpong.PingPongTable;
import pl.sport.ui.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Komendy /siatkowka, /koszykowka i /sport. Pierwsze dwie działają na boiskach swojej dyscypliny, a /sport (bez dyscypliny)
 * obsługuje wszystkie boiska i wspólne polecenia (opuszczenie meczu, lista, pomoc).
 */
public final class SportCommand implements CommandExecutor, TabCompleter {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,24}");

    private final SportPlugin plugin;
    private final Arena.Sport sport;

    public SportCommand(SportPlugin plugin, String label) {
        this.plugin = plugin;
        this.sport = Arena.Sport.byKey(label);
    }

    private static boolean admin(CommandSender s) {
        return s.hasPermission("sport.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "pomoc" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "pomoc", "help", "?" -> help(sender);
            case "lista", "list" -> list(sender);
            case "stworz", "create" -> create(sender, args);
            case "usun", "delete" -> delete(sender, args);
            case "przebuduj", "rebuild" -> rebuild(sender, args);
            case "dolacz", "join" -> join(sender, args);
            case "opusc", "leave" -> leave(sender);
            case "start" -> start(sender, args);
            case "stop" -> stop(sender, args);
            case "tp" -> tp(sender, args);
            case "info" -> info(sender, args);
            case "reload" -> reload(sender);
            default -> Msg.error(sender, "Nieznane polecenie. Wpisz <white>/" + label + " pomoc</white>.");
        }
        return true;
    }

    // ---------- pomoc i lista ----------

    private void help(CommandSender s) {
        if (sport == null || sport == Arena.Sport.VOLLEYBALL) {
            s.sendMessage(Msg.c("<gold><bold>Siatkówka</bold> <gray>(/siatkowka)"));
            s.sendMessage(Msg.c("<gray> • <white>LPM</white> przy piłce: odbicie. Patrz tam, gdzie ma polecieć piłka (miejsce na boisku rywala albo kolega, któremu wystawiasz)."));
            s.sendMessage(Msg.c("<gray> • <white>Shift + LPM</white>: wystawienie. <white>Skok + LPM</white> przy wysokiej piłce: atak. Skok przy siatce, gdy piłka leci od rywala: blok."));
            s.sendMessage(Msg.c("<gray> • <white>Zagrywka</white>: LPM podrzuca piłkę, drugie LPM ją uderza, zza linii końcowej (wejście na boisko przed uderzeniem to punkt dla rywali). Do 3 odbić na drużynę, ten sam gracz nie odbija dwa razy z rzędu (w grze 1 na 1 wolno, możesz też wystawić sobie piłkę)."));
            s.sendMessage(Msg.c("<gray> • Nie wejdziesz na połowę rywali. Set do 25 punktów z przewagą 2, wygrywa pierwsza drużyna z 2 setami."));
        }
        if (sport == null || sport == Arena.Sport.BASKETBALL) {
            s.sendMessage(Msg.c("<gold><bold>Koszykówka</bold> <gray>(/koszykowka)"));
            s.sendMessage(Msg.c("<gray> • Podejdź do piłki, żeby ją wziąć. W ruchu kozłujesz. <white>LPM</white> z piłką: rzut (patrz w stronę kosza; rzut w wyskoku w najwyższym punkcie jest celniejszy, a obrońca blisko Ciebie psuje celność)."));
            s.sendMessage(Msg.c("<gray> • <white>F</white> z piłką: podanie do kolegi, na którego patrzysz. <white>LPM</white> przy rywalu z piłką: przechwyt (do 2 m, patrząc na niego). <white>LPM</white> przy luźnej piłce albo podaniu: łapiesz ją, a przy rzucie rywali w locie: blok."));
            s.sendMessage(Msg.c("<gray> • <white>Dwutakt</white>: bieg w stronę kosza i LPM z odległości do ok. 4,8 m. <white>Wsad</white>: bieg w stronę kosza, skok i LPM w powietrzu (trudny: wpada z szansą widoczną na pasku, obrońca w wyskoku może go zablokować, potem 10 s odpoczynku). <white>Shift</white> z piłką: osłona piłki."));
            s.sendMessage(Msg.c("<gray> • Faul daje dopiero kontakt: zahaczenie od tyłu, uderzenie po rękach rzucającego, najechanie na stojącego obrońcę. Rzuty osobiste: stoisz na linii i naciskasz <white>LPM</white>. Po 6 faulach osobistych wylatujesz z meczu."));
            s.sendMessage(Msg.c("<gray> • Rzut z odległości od 6,75 m wart jest 3 punkty. Zegar akcji 24 s, aut oddaje piłkę rywalom."));
        }
        if (sport == null || sport == Arena.Sport.PINGPONG) {
            s.sendMessage(Msg.c("<gold><bold>Ping-pong</bold> <gray>(/pingpong)"));
            s.sendMessage(Msg.c("<gray> • <white>Serwis</white>: LPM podrzuca piłeczkę, drugie LPM ją uderza, zza linii końcowej stołu. Piłeczka ma odbić się od Twojej połowy, przejść nad siatką i odbić od połowy rywala."));
            s.sendMessage(Msg.c("<gray> • <white>LPM</white> przy piłeczce: odbicie. Patrz w punkt na stole rywala, żeby tam ją posłać. Poczekaj, aż odbije się od Twojej połowy (odbicie przed odbiciem od stołu to błąd)."));
            s.sendMessage(Msg.c("<gray> • <white>Shift + LPM</white>: podcięcie (wolno i wysoko). <white>LPM w piłeczkę nad siatką, patrząc w dół</white>: smecz. W deblu odbijacie na zmianę."));
            s.sendMessage(Msg.c("<gray> • Gem do 11 punktów z przewagą 2, serwis zmienia się co 2 punkty, wygrywa pierwsza drużyna z 2 gemami. Nie wejdziesz na stół ani na połowę rywala."));
        }
        s.sendMessage(Msg.c("<gold><bold>Komendy</bold>"));
        String l = sport == null ? "sport" : sport.key();
        s.sendMessage(Msg.c("<gray> • <white>/" + l + " lista</white>, <white>dolacz <boisko> [a|b]</white>, <white>opusc</white>, <white>start</white>, <white>tp <boisko></white>, <white>info [boisko]</white>"));
        if (admin(s)) s.sendMessage(Msg.c("<gray> • Administrator: <white>/" + l + " stworz <nazwa> [dlugosc] [szerokosc]</white>, <white>usun</white>, <white>przebuduj</white>, <white>stop</white>, <white>start natychmiast</white>, <white>reload</white>"));
    }

    private void list(CommandSender s) {
        List<Arena> arenas = sport == null ? plugin.arenas().all() : plugin.arenas().of(sport);
        if (arenas.isEmpty()) {
            Msg.send(s, "Nie ma jeszcze żadnego boiska." + (admin(s) ? " Stań na środku linii końcowej, patrz w głąb boiska i wpisz <white>/" + (sport == null ? "siatkowka" : sport.key()) + " stworz <nazwa></white>." : ""));
            return;
        }
        Msg.send(s, "<gold>Boiska:");
        for (Arena a : arenas) {
            Match m = plugin.matches().existing(a);
            String state = m == null || m.players().isEmpty() ? "<green>wolne" : switch (m.stage()) {
                case LOBBY, COUNTDOWN -> "<yellow>zapisy " + m.teamSize(Team.A) + ":" + m.teamSize(Team.B);
                case PLAYING -> "<red>mecz trwa";
                case ENDING -> "<red>koniec meczu";
            };
            s.sendMessage(Msg.c("<gray> • <white>" + Msg.esc(a.name()) + " <dark_gray>(" + a.sport().label() + ", " + a.length() + "x" + a.width() + ") " + state));
        }
    }

    private void info(CommandSender s, String[] args) {
        Arena a = args.length > 1 ? find(s, args[1]) : null;
        if (args.length > 1 && a == null) return;
        if (a == null && s instanceof Player p) {
            Match m = plugin.matches().of(p);
            if (m != null) a = m.arena();
        }
        if (a == null) {
            Msg.error(s, "Podaj nazwę boiska: /" + (sport == null ? "sport" : sport.key()) + " info <boisko>");
            return;
        }
        Match m = plugin.matches().existing(a);
        Msg.send(s, "<gold>" + Msg.esc(a.name()) + " <gray>• " + a.sport().label() + " • " + a.length() + " x " + a.width() + " • świat " + a.worldName());
        if (m != null && !m.players().isEmpty()) {
            Msg.send(s, teamLine(m, Team.A));
            Msg.send(s, teamLine(m, Team.B));
        } else {
            Msg.send(s, "<green>Boisko jest wolne.");
        }
    }

    private String teamLine(Match m, Team t) {
        StringBuilder sb = new StringBuilder(Match.teamColor(t) + Match.teamName(t) + "<gray>: ");
        List<String> names = new ArrayList<>();
        for (var p : m.team(t)) names.add(Msg.esc(p.name()));
        sb.append(names.isEmpty() ? "<dark_gray>brak" : "<white>" + String.join(", ", names));
        return sb.toString();
    }

    // ---------- boiska ----------

    private Arena find(CommandSender s, String name) {
        Arena a = plugin.arenas().get(name);
        if (a == null) {
            Msg.error(s, "Nie ma boiska o nazwie „" + Msg.esc(name) + "”. Lista: /" + (sport == null ? "sport" : sport.key()) + " lista");
            return null;
        }
        if (sport != null && a.sport() != sport) {
            Msg.error(s, "To boisko jest do dyscypliny: " + a.sport().label() + " (użyj /" + a.sport().key() + ").");
            return null;
        }
        return a;
    }

    private void create(CommandSender s, String[] args) {
        if (!admin(s)) {
            Msg.error(s, "Nie masz uprawnień.");
            return;
        }
        if (!(s instanceof Player p)) {
            Msg.error(s, "Tylko gracz może postawić boisko.");
            return;
        }
        int shift = 0;
        Arena.Sport target = sport;
        if (target == null) {
            if (args.length < 3 || Arena.Sport.byKey(args[1]) == null) {
                Msg.error(s, "Użycie: /sport stworz <siatkowka|koszykowka|pingpong> <nazwa> [dlugosc] [szerokosc]");
                return;
            }
            target = Arena.Sport.byKey(args[1]);
            shift = 1;
        }
        if (args.length < 2 + shift) {
            Msg.error(s, "Użycie: /" + target.key() + " stworz <nazwa> [dlugosc] [szerokosc]");
            return;
        }
        String name = args[1 + shift];
        if (!NAME.matcher(name).matches()) {
            Msg.error(s, "Nazwa boiska może mieć litery bez polskich znaków, cyfry, _ i - (do 24 znaków).");
            return;
        }
        if (plugin.arenas().get(name) != null) {
            Msg.error(s, "Boisko o nazwie " + Msg.esc(name) + " już istnieje.");
            return;
        }
        int len = plugin.builder().defaultLength(target);
        int wid = plugin.builder().defaultWidth(target);
        try {
            if (args.length > 2 + shift) len = Integer.parseInt(args[2 + shift]);
            if (args.length > 3 + shift) wid = Integer.parseInt(args[3 + shift]);
        } catch (NumberFormatException e) {
            Msg.error(s, "Wymiary muszą być liczbami całkowitymi (w blokach).");
            return;
        }
        double scale = plugin.sportConfig().pingpongScale();
        int minLen = switch (target) {
            case VOLLEYBALL -> 10;
            case BASKETBALL -> 16;
            case PINGPONG -> PingPongTable.minLength(scale);
        };
        int minWid = switch (target) {
            case VOLLEYBALL -> 5;
            case BASKETBALL -> 9;
            case PINGPONG -> PingPongTable.minWidth(scale);
        };
        if (len < minLen || len > 60 || wid < minWid || wid > 40) {
            Msg.error(s, "Wymiary poza zakresem: długość " + minLen + "-60, szerokość " + minWid + "-40.");
            return;
        }
        if (target == Arena.Sport.VOLLEYBALL && len % 2 != 0) {
            Msg.error(s, "Długość boiska do siatkówki musi być parzysta (siatka stoi dokładnie na środku).");
            return;
        }
        Arena a = plugin.builder().create(p.getLocation(), target, name, len, wid);
        plugin.arenas().add(a);
        Msg.send(s, "<green>Postawiono boisko <white>" + Msg.esc(name) + "</white> (" + target.label() + ", " + len + " x " + wid + "). <gray>Gracze dołączają komendą /" + target.key() + " dolacz " + Msg.esc(name));
        Msg.send(s, "<gray>Teren do 8 bloków wysokości nad boiskiem i 3 bloki wokół zostały oczyszczone. Boisko stoi od Ciebie w głąb, w kierunku patrzenia."
                + (target == Arena.Sport.PINGPONG ? " Stół stoi na środku hali, siatka w połowie jego długości." : ""));
    }

    private void delete(CommandSender s, String[] args) {
        if (!admin(s)) {
            Msg.error(s, "Nie masz uprawnień.");
            return;
        }
        if (args.length < 2) {
            Msg.error(s, "Użycie: /" + (sport == null ? "sport" : sport.key()) + " usun <boisko>");
            return;
        }
        Arena a = find(s, args[1]);
        if (a == null) return;
        plugin.matches().drop(a);
        int removed = plugin.builder().clearDecor(a);
        plugin.arenas().remove(a.name());
        Msg.send(s, "<green>Usunięto boisko " + Msg.esc(a.name()) + " <gray>(siatka, tablice i słupki zniknęły, podłoga z bloków zostaje: " + removed + " elementów).");
    }

    private void rebuild(CommandSender s, String[] args) {
        if (!admin(s)) {
            Msg.error(s, "Nie masz uprawnień.");
            return;
        }
        if (args.length < 2) {
            Msg.error(s, "Użycie: /" + (sport == null ? "sport" : sport.key()) + " przebuduj <boisko>");
            return;
        }
        Arena a = find(s, args[1]);
        if (a == null) return;
        Match m = plugin.matches().existing(a);
        if (m != null && !m.players().isEmpty()) {
            Msg.error(s, "Na boisku trwa mecz albo zapisy. Użyj najpierw /" + a.sport().key() + " stop " + Msg.esc(a.name()));
            return;
        }
        plugin.builder().build(a);
        Msg.send(s, "<green>Przebudowano boisko " + Msg.esc(a.name()));
    }

    private void tp(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Tylko gracz.");
            return;
        }
        if (args.length < 2) {
            Msg.error(s, "Użycie: /" + (sport == null ? "sport" : sport.key()) + " tp <boisko>");
            return;
        }
        Arena a = find(s, args[1]);
        if (a == null) return;
        if (plugin.matches().of(p) != null) {
            Msg.error(s, "Jesteś w meczu. Opuść go komendą /sport opusc.");
            return;
        }
        World w = Bukkit.getWorld(a.worldName());
        if (w == null) {
            Msg.error(s, "Świat boiska nie jest załadowany.");
            return;
        }
        Location l = a.toWorld(w, new V3(1.5, 0.1, a.width() / 2.0));
        l.setYaw(a.yawOf(new V3(1, 0, 0)));
        p.teleport(l);
        Msg.send(s, "<green>Jesteś na boisku " + Msg.esc(a.name()));
    }

    // ---------- gra ----------

    private void join(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Tylko gracz.");
            return;
        }
        if (args.length < 2) {
            Msg.error(s, "Użycie: /" + (sport == null ? "sport" : sport.key()) + " dolacz <boisko> [a|b]");
            return;
        }
        if (plugin.matches().of(p) != null) {
            Msg.error(s, "Już jesteś w meczu. Opuść go komendą /sport opusc.");
            return;
        }
        Arena a = find(s, args[1]);
        if (a == null) return;
        Team want = null;
        if (args.length > 2) {
            String t = args[2].toLowerCase(Locale.ROOT);
            if (t.equals("a") || t.startsWith("czerw") || t.equals("red")) want = Team.A;
            else if (t.equals("b") || t.startsWith("niebi") || t.equals("blue")) want = Team.B;
            else {
                Msg.error(s, "Drużyna to <red>a</red> (Czerwoni) albo <aqua>b</aqua> (Niebiescy).");
                return;
            }
        }
        String err = plugin.matches().matchFor(a).join(p, want);
        if (err != null) Msg.error(s, err);
    }

    private void leave(CommandSender s) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Tylko gracz.");
            return;
        }
        Match m = plugin.matches().of(p);
        if (m == null) {
            Msg.error(s, "Nie jesteś w żadnym meczu.");
            return;
        }
        m.leave(p.getUniqueId(), true);
    }

    private Match matchOf(CommandSender s, String[] args, int nameIndex) {
        if (args.length > nameIndex) {
            Arena a = find(s, args[nameIndex]);
            return a == null ? null : plugin.matches().existing(a);
        }
        if (s instanceof Player p) return plugin.matches().of(p);
        return null;
    }

    private void start(CommandSender s, String[] args) {
        boolean force = args.length > 1 && (args[1].equalsIgnoreCase("natychmiast") || args[1].equalsIgnoreCase("force"));
        Match m = matchOf(s, args, force ? 2 : 1);
        if (m == null) {
            Msg.error(s, "Najpierw dołącz do meczu (/" + (sport == null ? "sport" : sport.key()) + " dolacz <boisko>) albo podaj boisko.");
            return;
        }
        if (force && !admin(s)) {
            Msg.error(s, "Natychmiastowy start jest tylko dla administratora.");
            return;
        }
        String err = force ? m.forceStart() : m.startCountdown();
        if (err != null) Msg.error(s, err);
    }

    private void stop(CommandSender s, String[] args) {
        Match m = matchOf(s, args, 1);
        if (m == null) {
            Msg.error(s, "Nie ma meczu do zatrzymania. Podaj boisko albo bądź w meczu.");
            return;
        }
        boolean member = s instanceof Player p && plugin.matches().of(p) == m;
        if (!admin(s) && !member) {
            Msg.error(s, "Nie masz uprawnień.");
            return;
        }
        if (!admin(s) && m.stage() == Match.Stage.PLAYING) {
            Msg.error(s, "Mecz w trakcie może zatrzymać tylko administrator. Możesz go opuścić: /sport opusc");
            return;
        }
        m.stop("Mecz został zatrzymany przez " + (s instanceof Player p ? p.getName() : "konsolę") + ".");
        Msg.send(s, "<green>Mecz zatrzymany.");
    }

    private void reload(CommandSender s) {
        if (!admin(s)) {
            Msg.error(s, "Nie masz uprawnień.");
            return;
        }
        plugin.reloadConfig();
        plugin.arenas().load();
        Msg.send(s, "<green>Konfiguracja i lista boisk wczytane ponownie.");
    }

    // ---------- podpowiedzi ----------

    @Override
    public List<String> onTabComplete(CommandSender s, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean adm = admin(s);
        if (args.length == 1) {
            out.addAll(List.of("pomoc", "lista", "dolacz", "opusc", "start", "tp", "info"));
            if (adm) out.addAll(List.of("stworz", "usun", "przebuduj", "stop", "reload"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (List.of("dolacz", "tp", "info", "stop").contains(sub) || (adm && List.of("usun", "przebuduj").contains(sub))) {
                for (Arena a : sport == null ? plugin.arenas().all() : plugin.arenas().of(sport)) out.add(a.name());
            } else if (sub.equals("start") && adm) {
                out.add("natychmiast");
                for (Arena a : sport == null ? plugin.arenas().all() : plugin.arenas().of(sport)) out.add(a.name());
            } else if (sub.equals("stworz") && adm && sport == null) {
                out.addAll(List.of("siatkowka", "koszykowka", "pingpong"));
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("dolacz")) {
            out.addAll(List.of("a", "b"));
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }
}
