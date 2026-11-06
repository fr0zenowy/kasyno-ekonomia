package pl.kasyno.cmd;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.bin.BinManager;
import pl.kasyno.bin.BinModel;
import pl.kasyno.bin.BinType;
import pl.kasyno.waypoint.Waypoint;
import pl.kasyno.waypoint.WaypointColors;
import pl.kasyno.waypoint.WaypointView;
import pl.kasyno.work.Compass;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.PlayerData;
import pl.kasyno.games.Rooms;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Screens;
import pl.kasyno.gui.T;
import pl.kasyno.gui.screens.WorkHubGui;
import pl.kasyno.npc.PlaceholderManager;
import pl.kasyno.work.JobDef;
import pl.kasyno.work.Jobs;
import pl.kasyno.work.WorkService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /kasyno: menu główne, otwieranie dowolnego ekranu albo gry po nazwie (to samo, co robią NPC), przyciski z czatu
 * (dołączanie do jackpota, pojedynek) oraz komendy administratora: NPC, monety, przedmioty.
 */
final class RootCommand implements CommandExecutor, TabCompleter {
    private final KasynoPlugin plugin;

    RootCommand(KasynoPlugin plugin) {
        this.plugin = plugin;
    }

    private static void msg(CommandSender s, String mm) {
        if (s instanceof Player p) T.send(p, mm);
        else s.sendMessage(T.plain(T.MM.deserialize(mm)));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "npc" -> npc(sender, args);
            case "praca", "prace" -> praca(sender, args);
            case "smietnik", "smietniki" -> smietnik(sender, args);
            case "waypoint", "waypointy", "wp" -> waypoint(sender, args);
            case "wiezienie", "cela" -> wiezienie(sender, args);
            case "rekin", "rekiny", "shark" -> rekin(sender, args);
            case "placeholder", "ph" -> placeholder(sender, args);
            case "admin" -> admin(sender, args);
            case "reload" -> {
                if (!sender.hasPermission("kasyno.admin")) return noPerm(sender);
                plugin.reloadConfig();
                plugin.waypoints().reload();
                msg(sender, "<green>Konfiguracja przeładowana.");
            }
            case "pack" -> pack(sender, args);
            case "bank" -> bank(sender, args);
            case "arena" -> arena(sender, args);
            case "room" -> {
                if (!(sender instanceof Player p) || args.length < 3) return true;
                Rooms.Kind kind = args[2].equalsIgnoreCase("heist") ? Rooms.Kind.HEIST : Rooms.Kind.JACKPOT;
                if (args[1].equalsIgnoreCase("view")) {
                    new pl.kasyno.gui.screens.RoomGui(plugin, p, kind).open();
                    return true;
                }
                if (!args[1].equalsIgnoreCase("join")) return true;
                String err = plugin.rooms().join(p, kind);
                if (err != null) {
                    Fx.deny(p);
                    T.send(p, "<red>" + err);
                } else new pl.kasyno.gui.screens.RoomGui(plugin, p, kind).open();
            }
            case "duel" -> {
                if (!(sender instanceof Player p) || args.length < 2) return true;
                String err = args[1].equalsIgnoreCase("accept") ? plugin.rooms().accept(p) : plugin.rooms().decline(p);
                if (err != null) {
                    Fx.deny(p);
                    T.send(p, "<red>" + err);
                }
            }
            default -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tę komendę mogą wykonać tylko gracze.");
                    return true;
                }
                if (!p.hasPermission("kasyno.use")) return noPerm(sender);
                if (!Screens.open(plugin, p, sub)) {
                    T.send(p, "<red>Nie ma takiego ekranu. Dostępne: <white>" + String.join(", ", Screens.keys()));
                }
            }
        }
        return true;
    }

    /**
     * /kasyno pack: stan paczki z oknami i ikonami; on / off / auto ustawia wygląd okien dla gracza (np. gdy wgrał paczkę ręcznie do
     * folderu resourcepacks, a serwer o tym nie wie); wyslij ponownie pobiera paczkę od serwera; info (admin): adres, suma, liczba graczy.
     */
    private void pack(CommandSender sender, String[] args) {
        var packs = plugin.packs();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("info")) {
            if (!sender.hasPermission("kasyno.admin")) {
                noPerm(sender);
                return;
            }
            msg(sender, "<gold>Paczka zasobów: <white>" + (packs.available() ? "w pluginie" : "brak") + "<gray> • udostępniana: <white>" + (packs.serving() ? packs.url() : "nie")
                    + "<gray> • SHA-1: <white>" + (packs.sha1Hex().isEmpty() ? "-" : packs.sha1Hex()) + "<gray> • gracze z paczką: <white>" + packs.loadedPlayers().size()
                    + "<gray> • tryb serwera: <white>" + plugin.getConfig().getString("custom-gui", "auto"));
            return;
        }
        if (!(sender instanceof Player p)) {
            msg(sender, "<red>Tę komendę mogą wykonać tylko gracze.");
            return;
        }
        if (!packs.available()) {
            msg(sender, "<red>Ten plugin nie ma paczki zasobów.");
            return;
        }
        switch (sub) {
            case "on", "wlacz" -> {
                packs.setMode(p, "on");
                msg(sender, "<green>Okna w nowym wyglądzie włączone. <gray>Zakładam, że masz załadowaną paczkę zasobów kasyna (KasynoUI.zip). Wyłączysz to: /kasyno pack off");
            }
            case "off", "wylacz" -> {
                packs.setMode(p, "off");
                msg(sender, "<yellow>Okna w zwykłym wyglądzie. <gray>Przywrócisz nowy: /kasyno pack on");
            }
            case "auto" -> {
                packs.setMode(p, null);
                msg(sender, "<green>Wygląd okien automatyczny: nowy tylko wtedy, gdy serwer widzi, że załadowałeś paczkę.");
            }
            case "wyslij", "send" -> {
                if (packs.serving()) {
                    packs.send(p);
                    msg(sender, "<green>Wysłano paczkę z oknami kasyna. <gray>Zaakceptuj ją w oknie, które się pojawi.");
                } else msg(sender, "<yellow>Serwer nie udostępnia paczki (resource-pack.url albo resource-pack.host w config.yml).");
            }
            default -> {
                msg(sender, "<gold>Paczka zasobów kasyna: <gray>serwer " + (packs.hasPack(p) ? "<green>widzi, że ją masz" : "<yellow>nie widzi, żebyś ją miał")
                        + "<gray> • wygląd okien: <white>" + packs.mode(p) + "<gray> • teraz okna są <white>" + (packs.custom(p) ? "w nowym wyglądzie" : "zwykłe"));
                msg(sender, "<gray>Masz paczkę wgraną ręcznie (folder resourcepacks)? Wpisz <white>/kasyno pack on</white>. Chcesz pobrać ją z serwera? <white>/kasyno pack wyslij</white>.");
            }
        }
    }

    /**
     * /kasyno bank: pokaz napadu w prawdziwym świecie. Wszyscy: tp (na miejsce dla widzów). Administrator: zbuduj (stawia gotowy
     * bank z ulicą, vanem i skarbcem), test [liczba] (próbny napad z Twoim skinem), punkt <nazwa> (własny budynek), status, usun, stop.
     */
    private void bank(CommandSender sender, String[] args) {
        var show = plugin.show();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if (sub.equals("tp")) {
            if (!(sender instanceof Player p)) {
                msg(sender, "<red>Tylko gracz.");
                return;
            }
            if (!p.hasPermission("kasyno.use")) {
                noPerm(sender);
                return;
            }
            msg(sender, show.teleportToView(p) ? "<green>Jesteś na miejscu dla widzów, bank masz przed sobą." : "<red>Scena napadu nie jest jeszcze ustawiona. Administrator: /kasyno bank zbuduj.");
            return;
        }
        if (!sender.hasPermission("kasyno.admin")) {
            noPerm(sender);
            return;
        }
        switch (sub) {
            case "zbuduj" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                var st = show.build(p.getLocation());
                msg(sender, "<green>Postawiono bank (" + pl.kasyno.show.BankPrefab.WIDTH + " x " + pl.kasyno.show.BankPrefab.DEPTH + " bloków) na południe i wschód od Ciebie. "
                        + "<gray>Zobacz go: <white>/kasyno bank tp</white>, a pokaz na próbę: <white>/kasyno bank test</white>.");
                msg(sender, "<gray>Punkty sceny zapisane (" + st.points().size() + "). Od teraz każdy napad z ekipą rozgrywa się przy tym banku, z figurami ze skórkami graczy.");
            }
            case "test" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                if (show.running()) {
                    msg(sender, "<red>Pokaz już trwa. Poczekaj albo wpisz /kasyno bank stop.");
                    return;
                }
                int n = 4;
                if (args.length > 2) {
                    try {
                        n = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        msg(sender, "<red>Liczba osób w ekipie to liczba od 2 do 8.");
                        return;
                    }
                }
                if (!show.demo(p, n)) {
                    msg(sender, "<red>Scena nie jest gotowa. " + (show.stage().missing().isEmpty() ? "Świat sceny nie jest załadowany." : "Brakuje punktów: " + String.join(", ", show.stage().missing()) + ". Użyj /kasyno bank zbuduj."));
                    return;
                }
                show.teleportToView(p);
                msg(sender, "<green>Pokaz próbny rusza. <gray>Ekipa " + Math.max(2, Math.min(8, n)) + " osób: Ty (Twój skin) i kilku kolegów. Pieniądze nie biorą udziału.");
            }
            case "punkt" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                String name = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
                if (!pl.kasyno.show.Stage.REQUIRED.contains(name) && !pl.kasyno.show.Stage.OPTIONAL.contains(name)) {
                    msg(sender, "<red>Użycie: /kasyno bank punkt <nazwa>. Wymagane: <white>" + String.join(", ", pl.kasyno.show.Stage.REQUIRED) + "</white>. Opcjonalne: <white>" + String.join(", ", pl.kasyno.show.Stage.OPTIONAL));
                    return;
                }
                show.setPoint(name, p.getLocation());
                var missing = show.stage().missing();
                msg(sender, "<green>Ustawiono punkt <white>" + name + "</white>. " + (missing.isEmpty() ? "<gray>Scena kompletna." : "<gray>Brakuje jeszcze: " + String.join(", ", missing)));
            }
            case "usun" -> {
                show.clear();
                msg(sender, "<yellow>Scena usunięta z konfiguracji (budynek w świecie zostaje). Napady wrócą do okna z mapą.");
            }
            case "stop" -> {
                show.stopAll();
                msg(sender, "<yellow>Przerwano trwające pokazy, figury usunięte.");
            }
            default -> {
                var st = show.stage();
                msg(sender, "<gold>Scena napadu: <white>" + (show.ready() ? "gotowa (świat " + st.world() + ")" : "niegotowa") + (st.missing().isEmpty() ? "" : "<gray> • brakuje: " + String.join(", ", st.missing())));
                msg(sender, "<gray>/kasyno bank zbuduj • test [2-8] • punkt <nazwa> • tp • stop • usun");
            }
        }
    }

    /**
     * /kasyno arena: pojedynki i jackpot w prawdziwym świecie. Wszyscy: tp (na trybunę dla widzów). Administrator: zbuduj (stawia gotową
     * arenę z piaskiem, murkiem i trybuną), test [duel|jackpot [liczba]] (pokaz próbny z Twoim skinem), punkt <a|b|center|view>
     * (własna arena), status, usun, stop.
     */
    private void arena(CommandSender sender, String[] args) {
        var show = plugin.show();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if (sub.equals("tp")) {
            if (!(sender instanceof Player p)) {
                msg(sender, "<red>Tylko gracz.");
                return;
            }
            if (!p.hasPermission("kasyno.use")) {
                noPerm(sender);
                return;
            }
            msg(sender, show.teleportToArena(p) ? "<green>Jesteś na trybunie areny, plac masz przed sobą." : "<red>Arena nie jest jeszcze ustawiona. Administrator: /kasyno arena zbuduj.");
            return;
        }
        if (!sender.hasPermission("kasyno.admin")) {
            noPerm(sender);
            return;
        }
        switch (sub) {
            case "zbuduj" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                var st = show.buildArena(p.getLocation());
                msg(sender, "<green>Postawiono arenę (" + pl.kasyno.show.ArenaPrefab.WIDTH + " x " + pl.kasyno.show.ArenaPrefab.DEPTH + " bloków) na południe i wschód od Ciebie. "
                        + "<gray>Zobacz ją: <white>/kasyno arena tp</white>, a pokaz na próbę: <white>/kasyno arena test</white>.");
                msg(sender, "<gray>Punkty areny zapisane (" + st.arenaPoints().size() + "). Od teraz każdy pojedynek i jackpot rozgrywa się na tej arenie, z figurami ze skórkami graczy.");
            }
            case "test" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                if (!show.arenaReady()) {
                    msg(sender, "<red>Arena nie jest gotowa. " + (show.stage().arenaMissing().isEmpty() ? "Świat areny nie jest załadowany." : "Brakuje punktów: " + String.join(", ", show.stage().arenaMissing()) + ". Użyj /kasyno arena zbuduj."));
                    return;
                }
                if (show.arenaBusy()) {
                    msg(sender, "<red>Na arenie trwa już pokaz. Poczekaj albo wpisz /kasyno arena stop.");
                    return;
                }
                String kind = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "duel";
                boolean ok;
                switch (kind) {
                    case "duel", "pojedynek" -> {
                        ok = show.demoDuel(p);
                        if (ok) msg(sender, "<green>Pojedynek próbny rusza. <gray>Ty (Twój skin) kontra Kolega, losowy zwycięzca. Pieniądze nie biorą udziału.");
                    }
                    case "jackpot" -> {
                        int n = 6;
                        if (args.length > 3) {
                            try {
                                n = Integer.parseInt(args[3]);
                            } catch (NumberFormatException e) {
                                msg(sender, "<red>Liczba osób to liczba od 2 do 20.");
                                return;
                            }
                        }
                        ok = show.demoJackpot(p, n);
                        if (ok) msg(sender, "<green>Jackpot próbny rusza. <gray>" + Math.max(2, Math.min(20, n)) + " osób: Ty (Twój skin) i koledzy. Pieniądze nie biorą udziału.");
                    }
                    default -> {
                        msg(sender, "<red>Użycie: /kasyno arena test [duel|jackpot [2-20]].");
                        return;
                    }
                }
                if (ok) show.teleportToArena(p);
                else msg(sender, "<red>Nie udało się odpalić pokazu.");
            }
            case "punkt" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                String name = args.length > 2 ? pl.kasyno.show.Stage.arenaPointName(args[2]) : null;
                if (name == null) {
                    msg(sender, "<red>Użycie: /kasyno arena punkt <a|b|center|view>. <gray>a i b to stanowiska pojedynku (patrz na siebie), center to środek okręgu jackpota, view to trybuna (patrz w stronę areny).");
                    return;
                }
                show.setArenaPoint(name, p.getLocation());
                var missing = show.stage().arenaMissing();
                msg(sender, "<green>Ustawiono punkt <white>" + name + "</white>. " + (missing.isEmpty() ? "<gray>Arena kompletna." : "<gray>Brakuje jeszcze: " + String.join(", ", missing)));
            }
            case "usun" -> {
                show.clearArena();
                msg(sender, "<yellow>Arena usunięta z konfiguracji (plac w świecie zostaje). Pojedynki i jackpot wrócą do okien.");
            }
            case "stop" -> {
                show.stopAll();
                msg(sender, "<yellow>Przerwano trwające pokazy, figury usunięte.");
            }
            default -> {
                var st = show.stage();
                msg(sender, "<gold>Arena: <white>" + (show.arenaReady() ? "gotowa (świat " + st.arenaWorld() + ")" : "niegotowa") + (st.arenaMissing().isEmpty() ? "" : "<gray> • brakuje: " + String.join(", ", st.arenaMissing())));
                msg(sender, "<gray>/kasyno arena zbuduj • test [duel|jackpot [2-20]] • punkt <a|b|center|view> • tp • stop • usun");
            }
        }
    }

    private boolean noPerm(CommandSender s) {
        msg(s, "<red>Nie masz uprawnień.");
        return true;
    }

    // ---------- NPC ----------

    private void npc(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kasyno.admin")) {
            noPerm(sender);
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "spawn" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz może postawić NPC.");
                    return;
                }
                if (args.length < 3) {
                    msg(sender, "<red>Użycie: /kasyno npc spawn <typ> [promień pracy]. Typy: <white>" + String.join(", ", plugin.npcs().types()));
                    return;
                }
                String type = args[2].toLowerCase(Locale.ROOT);
                int radius = 0;
                if (args.length > 3) {
                    try {
                        radius = Integer.parseInt(args[3]);
                    } catch (NumberFormatException e) {
                        msg(sender, "<red>Promień pracy to liczba bloków (" + WorkService.MIN_RADIUS + "-" + WorkService.MAX_RADIUS + "), np. /kasyno npc spawn " + type + " 40");
                        return;
                    }
                }
                Location loc = p.getLocation();
                Villager v = plugin.npcs().spawn(loc, type, loc.getYaw() + 180, radius);
                if (v == null) msg(sender, "<red>Nieznany typ „" + T.esc(type) + "”. Typy: <white>" + String.join(", ", plugin.npcs().types()));
                else {
                    msg(sender, "<green>Postawiono NPC: <white>" + plugin.npcs().labelOf(type) + "</white> <gray>(" + type + "). Usuniesz go komendą /kasyno npc remove.");
                    JobDef job = Jobs.find(type);
                    if (job != null) {
                        int r = plugin.npcs().radiusOf(v) > 0 ? plugin.npcs().radiusOf(v) : job.radius();
                        msg(sender, "<gray>To NPC od pracy w terenie: cele pojawiają się w promieniu <white>" + r + " m</white> od niego"
                                + (job.kind() == JobDef.Kind.WATER ? " (muszą być tam woda i miejsce na ryby)" : "") + ". Zmienisz to komendą <white>/kasyno praca promien <bloki></white> przy NPC.");
                    }
                }
            }
            case "remove" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                Villager v = plugin.npcs().nearest(p, 5);
                if (v == null) msg(sender, "<red>Nie ma NPC w pobliżu. Podejdź bliżej albo spójrz na niego.");
                else {
                    String type = plugin.npcs().typeOf(v);
                    plugin.npcs().remove(v);
                    msg(sender, "<green>Usunięto NPC: <white>" + plugin.npcs().labelOf(type));
                }
            }
            case "list" -> {
                List<Villager> all = plugin.npcs().all();
                msg(sender, "<gold>NPC w załadowanych chunkach: <white>" + all.size());
                for (Villager v : all) {
                    Location l = v.getLocation();
                    msg(sender, "<gray> • <white>" + plugin.npcs().typeOf(v) + " <gray>" + l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
                }
            }
            case "removeall" -> {
                if (args.length < 3 || !args[2].equalsIgnoreCase("potwierdz")) {
                    msg(sender, "<yellow>To usunie wszystkich NPC w załadowanych chunkach. Potwierdź: <white>/kasyno npc removeall potwierdz");
                    return;
                }
                int n = 0;
                for (Villager v : plugin.npcs().all()) {
                    plugin.npcs().remove(v);
                    n++;
                }
                msg(sender, "<green>Usunięto NPC: <white>" + n);
            }
            case "types" -> msg(sender, "<gold>Typy NPC: <white>" + String.join(", ", plugin.npcs().types()));
            default -> msg(sender, "<red>Użycie: /kasyno npc <spawn <typ> [promień] | remove | list | removeall | types>");
        }
    }

    // ---------- rekin ----------

    /**
     * /kasyno rekin: info (Twoje ryzyko tu, gdzie stoisz). Administrator: pokaz (model rekina krąży 10 s przed Tobą), test [gracz] (cały atak, ale
     * bez śmierci i bez utraty gotówki), zaatakuj <gracz> (prawdziwy atak), status, wlacz i wylacz (zapisuje się w config.yml).
     */
    private void rekin(CommandSender sender, String[] args) {
        var shark = plugin.shark();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
        boolean admin = sender.hasPermission("kasyno.admin");
        if (sub.equals("info")) {
            var c = shark.config();
            msg(sender, "<gold>Rekiny: <white>" + (c.enabled() ? "włączone" : "wyłączone")
                    + "<gray> • bezpiecznie do <white>" + Math.round(c.minDistance()) + "<gray> bloków od lądu, największe ryzyko od <white>" + Math.round(c.maxDistance())
                    + "<gray> • głębokość min. <white>" + c.minDepth() + "<gray> • rekin zabiera <white>" + Math.round(c.lossFraction() * 100) + "%<gray> gotówki z portfela");
            if (sender instanceof Player p) {
                double d = shark.distanceFromLand(p);
                if (d >= 0) msg(sender, "<gray>Jesteś <white>" + Math.round(d) + "<gray> bloków od lądu: ryzyko <white>" + String.format(Locale.ROOT, "%.0f", shark.risk(d) * 100) + "%<gray> na każde losowanie co <white>" + c.checkSeconds() + " s<gray> w głębokiej wodzie.");
            }
            return;
        }
        if (!admin) {
            noPerm(sender);
            return;
        }
        switch (sub) {
            case "pokaz", "show" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tę komendę mogą wykonać tylko gracze.");
                    return;
                }
                Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(5)).add(0, 0.5, 0);
                shark.preview(at, 200, null);
                msg(sender, "<green>Model rekina krąży przed Tobą przez 10 sekund (co chwilę otwiera paszczę).");
            }
            case "test", "zaatakuj", "atak" -> {
                Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
                if (target == null) {
                    msg(sender, "<red>Użycie: /kasyno rekin " + sub + " <gracz online>");
                    return;
                }
                boolean harmless = sub.equals("test");
                if (!shark.start(target, harmless)) msg(sender, "<yellow>Ten gracz już jest atakowany.");
                else msg(sender, harmless ? "<green>Test ataku rozpoczęty (bez śmierci i bez utraty gotówki)." : "<red>Prawdziwy atak rozpoczęty na " + T.esc(target.getName()) + ".");
            }
            case "status" -> msg(sender, "<gold>Rekiny: <white>" + (shark.enabled() ? "włączone" : "wyłączone") + "<gray> • trwających ataków: <white>" + shark.activeAttacks());
            case "wlacz", "on" -> {
                shark.setEnabled(true);
                msg(sender, "<green>Rekiny włączone.");
            }
            case "wylacz", "off" -> {
                shark.setEnabled(false);
                msg(sender, "<yellow>Rekiny wyłączone: nikt już nie zostanie zaatakowany.");
            }
            default -> msg(sender, "<red>Nie znam takiej komendy. Dostępne: info, pokaz, test, zaatakuj, status, wlacz, wylacz.");
        }
    }

    // ---------- więzienie ----------

    private static UUID prisonTarget(KasynoPlugin plugin, String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online.getUniqueId() : Commands.findPlayer(plugin, name);
    }

    /**
     * /kasyno wiezienie: lista zadań więźnia. Administrator: wsadz <gracz> [przestepstwo|okradanie|napad], uwolnij <gracz>, status <gracz>,
     * cele (lista), cela <nr> [ustaw] (teleport albo ustawienie celi na swoim bloku), wyjscie [ustaw].
     */
    private void wiezienie(CommandSender sender, String[] args) {
        var prison = plugin.prison();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean admin = sender.hasPermission("kasyno.admin");
        if (sub.isEmpty() || sub.equals("zadania") || sub.equals("lista")) {
            if (!(sender instanceof Player p)) {
                msg(sender, "<red>Tę komendę mogą wykonać tylko gracze.");
                return;
            }
            if (!p.hasPermission("kasyno.use")) {
                noPerm(sender);
                return;
            }
            new pl.kasyno.prison.PrisonGui(plugin, p).open();
            return;
        }
        if (!admin) {
            noPerm(sender);
            return;
        }
        switch (sub) {
            case "wsadz", "jail" -> {
                Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : null;
                if (target == null) {
                    msg(sender, "<red>Użycie: /kasyno wiezienie wsadz <gracz online> [przestepstwo|okradanie|napad]");
                    return;
                }
                var reason = args.length > 3 ? pl.kasyno.prison.PrisonRules.Reason.byId(args[3]) : pl.kasyno.prison.PrisonRules.Reason.CRIME;
                if (reason == null) {
                    msg(sender, "<red>Nieznany powód. Dostępne: przestepstwo, okradanie, napad.");
                    return;
                }
                if (!prison.enabled()) msg(sender, "<red>Więzienie jest wyłączone albo nie ma żadnej celi (prison.enabled i prison.cells w config.yml).");
                else if (!prison.jail(target.getUniqueId(), reason)) msg(sender, "<red>Tego gracza nie da się uwięzić (ma uprawnienie kasyno.prison.bypass).");
                else msg(sender, "<green>" + T.esc(target.getName()) + " trafia do więzienia: <white>" + reason.title());
            }
            case "uwolnij", "release" -> {
                UUID id = args.length > 2 ? prisonTarget(plugin, args[2]) : null;
                if (id == null) {
                    msg(sender, "<red>Użycie: /kasyno wiezienie uwolnij <gracz>");
                    return;
                }
                if (!prison.isJailed(id)) msg(sender, "<yellow>Ten gracz nie siedzi.");
                else {
                    prison.release(id, "Strażnik Cię wypuścił.");
                    msg(sender, "<green>Gracz uwolniony.");
                }
            }
            case "status" -> {
                UUID id = args.length > 2 ? prisonTarget(plugin, args[2]) : null;
                var st = id == null ? null : prison.status(id);
                if (st == null) msg(sender, "<yellow>Ten gracz nie siedzi (albo go nie znam).");
                else msg(sender, "<gold>" + st.reason().title() + "<gray> • cela <white>" + (st.cell() + 1) + "<gray> • zadania <white>" + st.tasksLeft() + "/" + st.tasksTotal()
                        + "<gray> • do wolności <white>" + pl.kasyno.prison.PrisonRules.clock(st.secondsLeft()));
            }
            case "cele" -> {
                msg(sender, "<gold>Więzienie: <white>" + (prison.enabled() ? "działa" : "wyłączone lub bez cel") + "<gray> • świat: <white>" + (prison.world() == null ? "-" : prison.world().getName()));
                int i = 1;
                for (var s : prison.spots()) msg(sender, "<gray> • cela " + i++ + ": <white>" + pl.kasyno.prison.PrisonRules.format(s));
                var exit = prison.exitLocation();
                if (exit != null) msg(sender, "<gray> • wyjście: <white>" + exit.getBlockX() + " " + exit.getBlockY() + " " + exit.getBlockZ());
            }
            case "cela" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tę komendę mogą wykonać tylko gracze.");
                    return;
                }
                int n;
                try {
                    n = args.length > 2 ? Integer.parseInt(args[2]) : 0;
                } catch (NumberFormatException e) {
                    n = 0;
                }
                if (n < 1) {
                    msg(sender, "<red>Użycie: /kasyno wiezienie cela <numer> [ustaw]");
                    return;
                }
                if (args.length > 3 && args[3].equalsIgnoreCase("ustaw")) {
                    if (!prison.setCell(n, p.getLocation())) msg(sender, "<red>Numer musi być od 1 do " + (prison.cellCount() + 1) + ".");
                    else msg(sender, "<green>Cela " + n + " ustawiona na Twoim bloku.");
                } else {
                    Location to = prison.cellLocation(n - 1, p.getLocation().getYaw(), p.getLocation().getPitch());
                    if (to == null || n > prison.cellCount()) msg(sender, "<red>Nie ma takiej celi.");
                    else p.teleport(to);
                }
            }
            case "wyjscie" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tę komendę mogą wykonać tylko gracze.");
                    return;
                }
                if (args.length > 2 && args[2].equalsIgnoreCase("ustaw")) {
                    prison.setExit(p.getLocation());
                    msg(sender, "<green>Wyjście z więzienia ustawione na Twoim bloku.");
                } else if (prison.exitLocation() != null) p.teleport(prison.exitLocation());
            }
            default -> msg(sender, "<red>Nie znam takiej komendy. Dostępne: wsadz, uwolnij, status, cele, cela, wyjscie.");
        }
    }

    // ---------- waypointy ----------

    private static String waypointLine(Waypoint w, Player viewer, boolean discovered) {
        String icon = switch (w.kind()) {
            case HOME -> "⌂";
            case DEATH -> "☠";
            default -> "◆";
        };
        String extra = switch (w.kind()) {
            case AUTO -> " <dark_gray>(miejsce kasyna)";
            case HOME -> " <dark_gray>(Twój dom)";
            case DEATH -> " <dark_gray>(tu zginąłeś)";
            default -> w.reward() > 0 ? (discovered ? " <green>✔ odkryte" : " <yellow>★ nagroda " + Fmt.money(w.reward())) : "";
        };
        String head = "<#" + WaypointColors.hex(w.color()) + ">" + icon + " <white>" + T.esc(w.title()) + "<gray> (" + T.esc(w.world()) + ")" + extra;
        if (viewer == null || !viewer.getWorld().getName().equals(w.world())) return head;
        double dx = w.x() - viewer.getLocation().getX();
        double dz = w.z() - viewer.getLocation().getZ();
        double dist = Math.sqrt(dx * dx + (w.y() - viewer.getLocation().getY()) * (w.y() - viewer.getLocation().getY()) + dz * dz);
        return head + " <green>" + Compass.arrow(viewer.getLocation().getYaw(), dx, dz) + " " + WaypointView.distance(dist);
    }

    /**
     * /kasyno waypoint: lista (każdy: nazwy, kierunek i odległość), info (stan funkcji), ukryj i pokaz (każdy sobie, do restartu);
     * administrator: dodaj <nazwa> [kolor], usun, przenies, kolor, tp, wlacz i wylacz (całą funkcję, zapisuje się w config.yml).
     */
    private void waypoint(CommandSender sender, String[] args) {
        var wps = plugin.waypoints();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "lista";
        switch (sub) {
            case "lista", "list", "gdzie" -> {
                Player viewer = sender instanceof Player p ? p : null;
                var all = viewer != null ? wps.visibleTo(viewer) : new ArrayList<>(wps.all());
                msg(sender, "<gold>Waypointy: <white>" + all.size() + (wps.active() ? "" : "<yellow> (wyłączone w ustawieniach, nie pokazują się graczom)"));
                for (Waypoint w : all) msg(sender, "<gray> • " + waypointLine(w, viewer, viewer != null && wps.discovered(viewer.getUniqueId(), w.id())));
                if (all.isEmpty()) msg(sender, "<gray>Jeszcze żadnego nie ma. Administrator tworzy je komendą /kasyno waypoint dodaj <nazwa> [kolor].");
                if (viewer != null) {
                    Waypoint t = wps.tracked(viewer);
                    if (t != null) msg(sender, "<gray>Prowadzi Cię do: <white>" + T.esc(t.title()) + "<gray> (wyłączysz: /kasyno waypoint prowadz wylacz)");
                    else if (!all.isEmpty()) msg(sender, "<gray>Chcesz dojść w jakieś miejsce? <white>/kasyno waypoint prowadz <nazwa></white> pokaże tylko ten cel. Dom: <white>/kasyno waypoint dom ustaw");
                }
                return;
            }
            case "info", "status" -> {
                var c = plugin.getConfig();
                msg(sender, "<gold>Waypointy: <white>" + (wps.active() ? "włączone" : "wyłączone") + "<gray> • sztuk: <white>" + wps.count() + "<gray> • kreska: <white>" + (c.getBoolean("waypoints.beam", false) ? "tak" : "nie")
                        + "<gray> • napis: <white>" + (c.getBoolean("waypoints.label", true) ? "tak" : "nie") + "<gray> • w prawdziwym miejscu do: <white>" + Math.round(wps.markerDistance()) + " bl.<gray> • naraz na gracza: <white>" + wps.maxVisible());
                if (sender instanceof Player p) msg(sender, "<gray>Dla Ciebie znaczniki są: <white>" + (wps.hiddenFor(p) ? "ukryte (/kasyno waypoint pokaz)" : "widoczne (ukryjesz je: /kasyno waypoint ukryj)"));
                msg(sender, "<gray>Wyłączysz wszystko w config.yml (waypoints.enabled: false) albo komendą /kasyno waypoint wylacz.");
                return;
            }
            case "prowadz", "track", "idz" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                if (args.length < 3) {
                    Waypoint t = wps.tracked(p);
                    msg(sender, t == null ? "<gray>Nikt Cię teraz nie prowadzi. Użycie: <white>/kasyno waypoint prowadz <nazwa></white> (np. bank, arena, wiezienie, dom, smierc) albo <white>wylacz</white>."
                            : "<gray>Prowadzi Cię do: <white>" + T.esc(t.title()));
                    return;
                }
                String err = wps.track(p, args[2]);
                if (err != null) msg(sender, "<red>" + err);
                else if (args[2].equalsIgnoreCase("wylacz") || args[2].equalsIgnoreCase("off")) msg(sender, "<yellow>Prowadzenie wyłączone: znów widzisz wszystkie waypointy.");
                else msg(sender, "<green>Prowadzę Cię do <white>" + T.esc(wps.tracked(p).title()) + "</white>. Widzisz tylko ten cel i swoje osobiste punkty, dopóki nie dojdziesz (albo /kasyno waypoint prowadz wylacz).");
                return;
            }
            case "dom", "home" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                String what = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
                if (what.equals("ustaw") || what.equals("set")) {
                    wps.setHome(p);
                    msg(sender, "<green>Dom ustawiony w tym miejscu. Widzisz go tylko Ty, a drogę znajdziesz komendą /kasyno waypoint prowadz dom.");
                } else if (what.equals("usun") || what.equals("remove")) {
                    msg(sender, wps.clearHome(p) ? "<yellow>Dom usunięty." : "<gray>Nie masz ustawionego domu.");
                } else {
                    String err = wps.track(p, "dom");
                    msg(sender, err != null ? "<gray>Nie masz jeszcze domu. Ustaw go tam, gdzie stoisz: <white>/kasyno waypoint dom ustaw" : "<green>Prowadzę Cię do domu.");
                }
                return;
            }
            case "odkryte", "odkrycia" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                int[] prog = wps.discoveryProgress(p.getUniqueId());
                msg(sender, prog[1] == 0 ? "<gray>Nie ma jeszcze miejsc z nagrodą za odkrycie." : "<gold>Odkryte miejsca z nagrodą: <white>" + prog[0] + "/" + prog[1] + "<gray>. Podejdź do świecącej gwiazdki ★, żeby odebrać nagrodę.");
                return;
            }
            case "ukryj", "hide" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                wps.setHidden(p, true);
                msg(sender, "<yellow>Znaczniki waypointów ukryte dla Ciebie (do restartu serwera). Przywrócisz je komendą /kasyno waypoint pokaz.");
                return;
            }
            case "pokaz", "show" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                wps.setHidden(p, false);
                msg(sender, "<green>Znaczniki waypointów znów widoczne.");
                return;
            }
            default -> {
            }
        }
        if (!sender.hasPermission("kasyno.admin")) {
            noPerm(sender);
            return;
        }
        switch (sub) {
            case "dodaj", "add" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz: waypoint powstaje tam, gdzie stoisz.");
                    return;
                }
                if (args.length < 3) {
                    msg(sender, "<red>Użycie: /kasyno waypoint dodaj <nazwa> [kolor]. Spację w nazwie zastąp podkreśleniem. Kolory: <white>" + String.join(", ", WaypointColors.names()) + "<red> albo #rrggbb.");
                    return;
                }
                Integer color = null;
                if (args.length > 3) {
                    color = WaypointColors.parse(args[3]);
                    if (color == null) {
                        msg(sender, "<red>Nie znam koloru „" + T.esc(args[3]) + "”. Kolory: <white>" + String.join(", ", WaypointColors.names()) + "<red> albo #rrggbb.");
                        return;
                    }
                }
                String err = wps.add(p.getLocation(), args[2], color);
                if (err != null) {
                    msg(sender, "<red>" + err);
                    return;
                }
                Waypoint w = wps.find(args[2]);
                msg(sender, "<green>Dodano waypoint <white>" + T.esc(w.title()) + "</white> <gray>(" + WaypointColors.describe(w.color()) + "). Wszyscy gracze widzą go jako świecącą kreskę z nazwą i odległością."
                        + (wps.active() ? "" : " <yellow>Uwaga: waypointy są wyłączone w ustawieniach, więc na razie nikt go nie widzi (/kasyno waypoint wlacz)."));
            }
            case "usun", "remove" -> {
                if (args.length < 3) {
                    msg(sender, "<red>Użycie: /kasyno waypoint usun <nazwa>");
                    return;
                }
                msg(sender, wps.remove(args[2]) ? "<green>Usunięto waypoint " + T.esc(args[2]) + "." : "<red>Nie ma waypointu „" + T.esc(args[2]) + "”. Lista: /kasyno waypoint lista");
            }
            case "przenies", "move" -> {
                if (!(sender instanceof Player p) || args.length < 3) {
                    msg(sender, "<red>Użycie (jako gracz): /kasyno waypoint przenies <nazwa>. Przenosi go tam, gdzie stoisz.");
                    return;
                }
                msg(sender, wps.move(args[2], p.getLocation()) ? "<green>Przeniesiono waypoint " + T.esc(args[2]) + " do Twojego miejsca." : "<red>Nie ma waypointu „" + T.esc(args[2]) + "”.");
            }
            case "nagroda", "reward" -> {
                if (args.length < 4) {
                    msg(sender, "<red>Użycie: /kasyno waypoint nagroda <nazwa> <kwota> (0 zdejmuje nagrodę). Gracz, który pierwszy raz podejdzie na " + (int) wps.discoverRadius() + " bloków, dostaje tę kwotę raz.");
                    return;
                }
                long amount = Fmt.parseAmount(args[3], 0);
                if (amount < 0 || (amount == 0 && !args[3].equals("0"))) {
                    msg(sender, "<red>Nie rozumiem kwoty „" + T.esc(args[3]) + "”.");
                    return;
                }
                String err = wps.setReward(args[2], amount);
                msg(sender, err != null ? "<red>" + err : amount == 0 ? "<yellow>Zdjęto nagrodę z " + T.esc(args[2]) + "." : "<green>Nagroda za odkrycie <white>" + T.esc(args[2]) + "</white>: <gold>" + Fmt.money(amount) + "</gold> (raz na gracza).");
            }
            case "kolor", "color" -> {
                if (args.length < 4) {
                    msg(sender, "<red>Użycie: /kasyno waypoint kolor <nazwa> <kolor>. Kolory: <white>" + String.join(", ", WaypointColors.names()) + "<red> albo #rrggbb.");
                    return;
                }
                Integer color = WaypointColors.parse(args[3]);
                if (color == null) {
                    msg(sender, "<red>Nie znam koloru „" + T.esc(args[3]) + "”.");
                    return;
                }
                msg(sender, wps.recolor(args[2], color) ? "<green>Kolor waypointu " + T.esc(args[2]) + ": <white>" + WaypointColors.describe(color) : "<red>Nie ma waypointu „" + T.esc(args[2]) + "”.");
            }
            case "tp" -> {
                if (!(sender instanceof Player p) || args.length < 3) {
                    msg(sender, "<red>Użycie (jako gracz): /kasyno waypoint tp <nazwa>");
                    return;
                }
                Waypoint w = wps.find(args[2]);
                World world = w == null ? null : Bukkit.getWorld(w.world());
                if (w == null || world == null) {
                    msg(sender, "<red>Nie ma takiego waypointu albo jego świat nie jest załadowany.");
                    return;
                }
                p.teleport(new Location(world, w.x(), w.y(), w.z(), p.getLocation().getYaw(), p.getLocation().getPitch()));
                msg(sender, "<green>Jesteś przy waypoincie <white>" + T.esc(w.title()));
            }
            case "wlacz", "wylacz", "on", "off" -> {
                boolean on = sub.equals("wlacz") || sub.equals("on");
                plugin.getConfig().set("waypoints.enabled", on);
                plugin.saveConfig();
                wps.reload();
                msg(sender, on ? "<green>Waypointy włączone (" + wps.count() + "). Każdy gracz widzi je jako świecące kreski z nazwą." : "<yellow>Waypointy wyłączone: znikły ze świata, a zapisane waypointy zostają w pliku. Przywrócisz je komendą /kasyno waypoint wlacz.");
            }
            default -> msg(sender, "<red>Użycie: /kasyno waypoint <lista | prowadz <nazwa> | dom [ustaw|usun] | odkryte | info | ukryj | pokaz | dodaj <nazwa> [kolor] | usun <nazwa> | przenies <nazwa> | kolor <nazwa> <kolor> | nagroda <nazwa> <kwota> | tp <nazwa> | wlacz | wylacz>");
        }
    }

    // ---------- śmietniki w świecie ----------

    private static String binLine(BinType t) {
        return "<gray> • <white>" + t.id() + " <gray>(" + T.esc(t.name()) + "): przerwa ok. <white>" + Fmt.duration(t.cooldownSec() * 1000L)
                + "<gray>, szczęście <white>x" + String.format(Locale.ROOT, "%.1f", t.luck()) + "<gray>, ilość <white>x" + String.format(Locale.ROOT, "%.1f", t.qty());
    }

    /**
     * /kasyno smietnik: typy (każdy); administrator: postaw <typ> [sekundy] (model w miejscu, w którym stoisz, przodem do Ciebie),
     * usun (najbliższy), cooldown <sekundy> (najbliższy), lista i wyczysc potwierdz.
     */
    private void smietnik(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "typy";
        var bins = plugin.bins();
        if (sub.equals("typy") || sub.equals("info")) {
            msg(sender, "<gold>Śmietniki w świecie <gray>(klikasz je prawym przyciskiem, każdy ma własną przerwę):");
            for (BinType t : BinType.ALL) msg(sender, binLine(t));
            msg(sender, "<gray>Stawia je administrator: /kasyno smietnik postaw <typ> [sekundy przerwy]");
            return;
        }
        if (!sender.hasPermission("kasyno.admin")) {
            noPerm(sender);
            return;
        }
        switch (sub) {
            case "postaw", "spawn" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                BinType type = args.length > 2 ? BinType.find(args[2].toLowerCase(Locale.ROOT)) : null;
                if (type == null) {
                    msg(sender, "<red>Użycie: /kasyno smietnik postaw <typ> [sekundy przerwy]. Typy: <white>" + String.join(", ", BinType.ids()));
                    return;
                }
                int seconds = 0;
                if (args.length > 3) {
                    try {
                        seconds = Integer.parseInt(args[3]);
                    } catch (NumberFormatException e) {
                        msg(sender, "<red>Przerwa to liczba sekund (" + BinManager.MIN_COOLDOWN + "-" + BinManager.MAX_COOLDOWN + "), np. /kasyno smietnik postaw " + type.id() + " 120");
                        return;
                    }
                }
                Interaction in = bins.place(p.getLocation(), type, BinModel.facing(p.getLocation().getYaw()), seconds);
                msg(sender, "<green>Postawiono: <white>" + T.esc(type.name()) + "</white> <gray>(" + type.id() + "), przerwa tego śmietnika: <white>" + Fmt.duration(bins.cooldownOf(in) * 1000L)
                        + "<gray>. Każdy ma własną: zmienisz ją komendą /kasyno smietnik cooldown <sekundy>, usuniesz komendą /kasyno smietnik usun (patrz na niego).");
            }
            case "usun", "remove" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                Interaction in = bins.nearest(p, 5);
                if (in == null) msg(sender, "<red>Nie ma śmietnika w pobliżu. Podejdź bliżej albo spójrz na niego.");
                else {
                    BinType t = bins.typeOf(in);
                    bins.remove(in);
                    msg(sender, "<green>Usunięto śmietnik: <white>" + T.esc(t == null ? "?" : t.name()));
                }
            }
            case "cooldown", "przerwa" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                Interaction in = bins.nearest(p, 5);
                if (in == null) {
                    msg(sender, "<red>Nie ma śmietnika w pobliżu. Podejdź bliżej albo spójrz na niego.");
                    return;
                }
                if (args.length < 3) {
                    msg(sender, "<gray>Przerwa tego śmietnika: <white>" + Fmt.duration(bins.cooldownOf(in) * 1000L) + "<gray>. Zmiana: /kasyno smietnik cooldown <sekundy> (" + BinManager.MIN_COOLDOWN + "-" + BinManager.MAX_COOLDOWN + ").");
                    return;
                }
                int seconds;
                try {
                    seconds = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    msg(sender, "<red>Podaj liczbę sekund, np. 120.");
                    return;
                }
                bins.setCooldown(in, seconds);
                msg(sender, "<green>Przerwa tego śmietnika: <white>" + Fmt.duration(bins.cooldownOf(in) * 1000L));
            }
            case "lista", "list" -> {
                var all = bins.all();
                msg(sender, "<gold>Śmietniki w załadowanych chunkach: <white>" + all.size());
                for (Interaction in : all) {
                    Location l = in.getLocation();
                    BinType t = bins.typeOf(in);
                    msg(sender, "<gray> • <white>" + (t == null ? "?" : t.id()) + " <gray>#" + bins.idOf(in) + " " + l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                            + ", przerwa " + Fmt.duration(bins.cooldownOf(in) * 1000L));
                }
            }
            case "wyczysc", "removeall" -> {
                if (args.length < 3 || !args[2].equalsIgnoreCase("potwierdz")) {
                    msg(sender, "<yellow>To usunie wszystkie śmietniki w załadowanych chunkach. Potwierdź: <white>/kasyno smietnik wyczysc potwierdz");
                    return;
                }
                msg(sender, "<green>Usunięto śmietników: <white>" + bins.removeAll());
            }
            default -> msg(sender, "<red>Użycie: /kasyno smietnik <typy | postaw <typ> [sekundy] | usun | cooldown <sekundy> | lista | wyczysc potwierdz>");
        }
    }

    // ---------- prace w terenie ----------

    /**
     * /kasyno praca: okno z listą prac (każdy), info (zarobki i przerwy), anuluj (przerywa trwającą rundę); administrator: promien
     * <bloki> ustawia promień pracy NPC, przy którym stoi, a stop <gracz> kończy rundę gracza.
     */
    private void praca(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "promien", "radius" -> {
                if (!sender.hasPermission("kasyno.admin")) {
                    noPerm(sender);
                    return;
                }
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                Villager v = plugin.npcs().nearest(p, 5);
                JobDef job = v == null ? null : Jobs.find(plugin.npcs().typeOf(v));
                if (job == null) {
                    msg(sender, "<red>Podejdź do NPC od pracy (Leśniczy, Kierownik kopalni, Rybak...) albo spójrz na niego.");
                    return;
                }
                if (args.length < 3) {
                    int r = plugin.npcs().radiusOf(v);
                    msg(sender, "<gray>Promień tego NPC: <white>" + (r > 0 ? r : job.radius()) + " m" + (r > 0 ? "" : " (domyślny)") + "<gray>. Zmiana: /kasyno praca promien <bloki> (" + WorkService.MIN_RADIUS + "-" + WorkService.MAX_RADIUS + ") albo 0 = domyślny.");
                    return;
                }
                int r;
                try {
                    r = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    msg(sender, "<red>Podaj liczbę bloków, np. 40.");
                    return;
                }
                plugin.npcs().setRadius(v, r <= 0 ? 0 : WorkService.clampRadius(r));
                msg(sender, "<green>Promień pracy NPC <white>" + T.esc(plugin.npcs().labelOf(job.id())) + "</white>: <white>" + (r <= 0 ? job.radius() + " m (domyślny)" : WorkService.clampRadius(r) + " m"));
            }
            case "stop" -> {
                if (!sender.hasPermission("kasyno.admin")) {
                    noPerm(sender);
                    return;
                }
                Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
                if (target == null) {
                    msg(sender, "<red>Użycie: /kasyno praca stop <gracz online>");
                    return;
                }
                msg(sender, plugin.work().cancel(target) ? "<green>Przerwano pracę gracza " + T.esc(target.getName()) + "." : "<yellow>Ten gracz nie ma trwającej pracy.");
            }
            case "anuluj", "cancel" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                if (!plugin.work().cancel(p)) msg(sender, "<yellow>Nie masz trwającej pracy.");
            }
            case "info" -> {
                msg(sender, "<gold>Prace w terenie <gray>(NPC stawia administrator: /kasyno npc spawn <typ> [promień])");
                for (JobDef job : Jobs.ALL) {
                    long cd = sender instanceof Player p ? plugin.eco().cooldownLeft(p.getUniqueId(), "work:" + job.id()) : 0;
                    String left = cd > 0 ? " <red>przerwa " + Fmt.duration(cd) : "";
                    msg(sender, "<gray> • <white>" + job.id() + " <gray>(" + T.esc(job.npc()) + "): " + T.esc(job.title()) + ", ok. <gold>" + Fmt.money(Math.round(job.expectedRound())) + "<gray> za rundę" + left);
                }
            }
            default -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                if (!p.hasPermission("kasyno.use")) {
                    noPerm(sender);
                    return;
                }
                new WorkHubGui(plugin, p).open();
            }
        }
    }

    // ---------- placeholdery (niewidzialne pola kliknięcia) ----------

    /** Wymiar pola do wiadomości: do dwóch miejsc po przecinku, bez zbędnych zer ("1,5", "2"). */
    private static String dim(float v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
        return s.replace('.', ',');
    }

    private static Double number(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void placeholder(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kasyno.admin")) {
            noPerm(sender);
            return;
        }
        var phs = plugin.placeholders();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "spawn" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz może postawić placeholder.");
                    return;
                }
                if (args.length < 3) {
                    msg(sender, "<red>Użycie: /kasyno placeholder spawn <gra> [szerokość] [wysokość]. Gry i ekrany: <white>" + String.join(", ", phs.types()));
                    return;
                }
                String game = args[2].toLowerCase(Locale.ROOT);
                double width = PlaceholderManager.DEFAULT_SIZE;
                double height = PlaceholderManager.DEFAULT_SIZE;
                if (args.length > 3) {
                    Double w = number(args[3]);
                    if (w == null) {
                        msg(sender, "<red>Szerokość „" + T.esc(args[3]) + "” nie jest liczbą. Przykład: /kasyno placeholder spawn " + game + " 2 1,5");
                        return;
                    }
                    width = w;
                    height = w;
                }
                if (args.length > 4) {
                    Double h = number(args[4]);
                    if (h == null) {
                        msg(sender, "<red>Wysokość „" + T.esc(args[4]) + "” nie jest liczbą. Przykład: /kasyno placeholder spawn " + game + " 2 1,5");
                        return;
                    }
                    height = h;
                }
                var in = phs.spawn(p.getLocation(), game, PlaceholderManager.clamp(width), PlaceholderManager.clamp(height));
                if (in == null) {
                    msg(sender, "<red>Nieznana gra „" + T.esc(game) + "”. Gry i ekrany: <white>" + String.join(", ", phs.types()));
                    return;
                }
                msg(sender, "<green>Postawiono placeholder: <white>" + game + "</white> <gray>(szerokość " + dim(in.getInteractionWidth()) + ", wysokość " + dim(in.getInteractionHeight())
                        + "). Prawy przycisk w to pole otwiera grę, a nad nim wisi napis z nazwą (zmienisz go komendą /kasyno placeholder napis). Obrys zobaczysz komendą /kasyno placeholder pokaz, usuniesz pole komendą /kasyno placeholder remove.");
            }
            case "remove" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                var in = phs.nearest(p, 6);
                if (in == null) msg(sender, "<red>Nie ma placeholdera w pobliżu. Podejdź bliżej albo spójrz na niego (pomaga /kasyno placeholder pokaz).");
                else {
                    String game = phs.gameOf(in);
                    phs.remove(in);
                    msg(sender, "<green>Usunięto placeholder: <white>" + game);
                }
            }
            case "list" -> {
                var all = phs.all();
                msg(sender, "<gold>Placeholdery w załadowanych chunkach: <white>" + all.size());
                for (var in : all) {
                    Location l = in.getLocation();
                    msg(sender, "<gray> • <white>" + phs.gameOf(in) + " <gray>" + l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                            + " (" + dim(in.getInteractionWidth()) + " x " + dim(in.getInteractionHeight()) + ")");
                }
            }
            case "pokaz", "show" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                if (args.length > 2 && args[2].equalsIgnoreCase("off")) {
                    phs.hide(p);
                    msg(sender, "<green>Obrys placeholderów wyłączony.");
                    return;
                }
                Double secs = args.length > 2 ? number(args[2]) : Double.valueOf(20);
                int seconds = secs == null ? 20 : (int) Math.max(1, Math.min(300, secs));
                phs.show(p, seconds);
                msg(sender, "<green>Obrys placeholderów w promieniu 48 bloków przez <white>" + seconds + " s<green>. Wyłączysz go komendą /kasyno placeholder pokaz off.");
            }
            case "removeall" -> {
                if (args.length < 3 || !args[2].equalsIgnoreCase("potwierdz")) {
                    msg(sender, "<yellow>To usunie wszystkie placeholdery w załadowanych chunkach. Potwierdź: <white>/kasyno placeholder removeall potwierdz");
                    return;
                }
                msg(sender, "<green>Usunięto placeholderów: <white>" + phs.removeAll());
            }
            case "napis", "label" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "<red>Tylko gracz.");
                    return;
                }
                var in = phs.nearest(p, 6);
                if (in == null) {
                    msg(sender, "<red>Nie ma placeholdera w pobliżu. Podejdź bliżej albo spójrz na niego (pomaga /kasyno placeholder pokaz).");
                    return;
                }
                if (args.length < 3) {
                    msg(sender, "<red>Użycie: /kasyno placeholder napis <tekst | auto | off>. Tekst może mieć kolory i nowe wiersze (MiniMessage), np. "
                            + T.esc("<gold><bold>Stół VIP</bold><newline><gray>Kliknij prawym przyciskiem"));
                    return;
                }
                String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                if (text.equalsIgnoreCase("auto")) {
                    phs.setText(in, null);
                    msg(sender, "<green>Napis placeholdera wrócił do domyślnego: <white>" + T.esc(phs.titleOf(phs.gameOf(in))));
                } else if (text.equalsIgnoreCase("off")) {
                    phs.setText(in, PlaceholderManager.LABEL_OFF);
                    msg(sender, "<green>Napis nad placeholderem wyłączony (pole nadal działa). Przywrócisz go komendą /kasyno placeholder napis auto.");
                } else {
                    phs.setText(in, text);
                    msg(sender, "<green>Ustawiono napis placeholdera: <reset>" + text);
                }
            }
            case "types" -> msg(sender, "<gold>Gry i ekrany dla placeholderów: <white>" + String.join(", ", phs.types()));
            default -> msg(sender, "<red>Użycie: /kasyno placeholder <spawn <gra> [szerokość] [wysokość] | napis <tekst|auto|off> | remove | list | pokaz [sekundy|off] | removeall | types>");
        }
    }

    // ---------- administracja ----------

    private UUID find(String name) {
        return Commands.findPlayer(plugin, name);
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kasyno.admin")) {
            noPerm(sender);
            return;
        }
        if (args.length < 3) {
            msg(sender, "<red>Użycie: /kasyno admin <give|take|set> <gracz> <kwota> | item <gracz> <id> [ile] | cooldown <gracz>");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        UUID target = find(args[2]);
        if (target == null) {
            msg(sender, "<red>Nie znam gracza „" + T.esc(args[2]) + "”.");
            return;
        }
        PlayerData d = plugin.eco().pd(target);
        switch (action) {
            case "give", "take", "set" -> {
                long amt = args.length > 3 ? Fmt.parseAmount(args[3], d.wallet) : -1;
                if (amt < 0) {
                    msg(sender, "<red>Podaj kwotę, np. 5000 albo 2k.");
                    return;
                }
                if (action.equals("give")) plugin.eco().add(target, amt);
                else if (action.equals("take")) d.wallet = Math.max(0, d.wallet - amt);
                else d.wallet = amt;
                plugin.store().touch(target);
                msg(sender, "<green>Portfel gracza <white>" + T.esc(d.name) + "</white>: <gold>" + Fmt.money(d.wallet));
            }
            case "item" -> {
                if (args.length < 4) {
                    msg(sender, "<red>Użycie: /kasyno admin item <gracz> <id> [ile]");
                    return;
                }
                Catalog.Item it = plugin.catalog().item(args[3].toLowerCase(Locale.ROOT));
                if (it == null) {
                    msg(sender, "<red>Nie ma takiego przedmiotu: " + T.esc(args[3]));
                    return;
                }
                int n = 1;
                if (args.length > 4) {
                    try {
                        n = Math.max(1, Integer.parseInt(args[4]));
                    } catch (NumberFormatException e) {
                        msg(sender, "<red>Ilość musi być liczbą.");
                        return;
                    }
                }
                plugin.eco().addItem(target, it.id(), n);
                msg(sender, "<green>Dano <white>" + n + "x " + it.name() + "</white> graczowi " + T.esc(d.name));
            }
            case "cooldown" -> {
                d.cooldowns.clear();
                d.lastDaily = 0;
                d.lastWeekly = 0;
                plugin.store().touch(target);
                msg(sender, "<green>Wyzerowano cooldowny gracza " + T.esc(d.name));
            }
            default -> msg(sender, "<red>Nieznana akcja.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("kasyno.admin");
        if (args.length == 1) {
            out.addAll(Screens.keys());
            out.add("pack");
            out.add("bank");
            out.add("arena");
            out.add("praca");
            out.add("smietnik");
            out.add("waypoint");
            out.add("wiezienie");
            out.add("rekin");
            if (admin) out.addAll(List.of("npc", "placeholder", "admin", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("rekin")) {
            out.add("info");
            if (admin) out.addAll(List.of("pokaz", "test", "zaatakuj", "status", "wlacz", "wylacz"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("rekin") && admin && List.of("test", "zaatakuj").contains(args[1].toLowerCase(Locale.ROOT))) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("wiezienie") || args[0].equalsIgnoreCase("cela"))) {
            out.add("zadania");
            if (admin) out.addAll(List.of("wsadz", "uwolnij", "status", "cele", "cela", "wyjscie"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("wiezienie") && admin && List.of("wsadz", "uwolnij", "status").contains(args[1].toLowerCase(Locale.ROOT))) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("wiezienie") && admin && args[1].equalsIgnoreCase("wsadz")) {
            out.addAll(List.of("przestepstwo", "okradanie", "napad"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("wiezienie") && admin && args[1].equalsIgnoreCase("cela")) {
            out.addAll(List.of("1", "2", "3", "4"));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("wiezienie") && admin && args[1].equalsIgnoreCase("cela")) {
            out.add("ustaw");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("wiezienie") && admin && args[1].equalsIgnoreCase("wyjscie")) {
            out.add("ustaw");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("waypoint") || args[0].equalsIgnoreCase("wp"))) {
            out.addAll(List.of("lista", "prowadz", "dom", "odkryte", "info", "ukryj", "pokaz"));
            if (admin) out.addAll(List.of("dodaj", "usun", "przenies", "kolor", "nagroda", "tp", "wlacz", "wylacz"));
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("waypoint") || args[0].equalsIgnoreCase("wp")) && args[1].equalsIgnoreCase("prowadz")) {
            out.addAll(plugin.waypoints().names());
            out.addAll(List.of("dom", "smierc", "bank", "arena", "wiezienie", "wylacz"));
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("waypoint") || args[0].equalsIgnoreCase("wp")) && args[1].equalsIgnoreCase("dom")) {
            out.addAll(List.of("ustaw", "usun"));
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("waypoint") || args[0].equalsIgnoreCase("wp")) && admin
                && List.of("usun", "przenies", "kolor", "tp", "nagroda").contains(args[1].toLowerCase(Locale.ROOT))) {
            out.addAll(plugin.waypoints().names());
        } else if (args.length == 4 && (args[0].equalsIgnoreCase("waypoint") || args[0].equalsIgnoreCase("wp")) && admin && args[1].equalsIgnoreCase("kolor")) {
            out.addAll(WaypointColors.names());
        } else if (args.length == 4 && (args[0].equalsIgnoreCase("waypoint") || args[0].equalsIgnoreCase("wp")) && admin && args[1].equalsIgnoreCase("dodaj")) {
            out.addAll(WaypointColors.names());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("smietnik")) {
            out.add("typy");
            if (admin) out.addAll(List.of("postaw", "usun", "cooldown", "lista", "wyczysc"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("smietnik") && args[1].equalsIgnoreCase("postaw") && admin) {
            out.addAll(BinType.ids());
        } else if (args.length == 4 && args[0].equalsIgnoreCase("smietnik") && args[1].equalsIgnoreCase("postaw") && admin) {
            out.addAll(List.of("60", "120", "180", "300"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("smietnik") && args[1].equalsIgnoreCase("cooldown") && admin) {
            out.addAll(List.of("60", "120", "180", "300"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("praca")) {
            out.addAll(List.of("info", "anuluj"));
            if (admin) out.addAll(List.of("promien", "stop"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("praca") && args[1].equalsIgnoreCase("promien") && admin) {
            out.addAll(List.of("20", "40", "60", "0"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("praca") && args[1].equalsIgnoreCase("stop") && admin) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("bank") || args[0].equalsIgnoreCase("arena"))) {
            out.add("tp");
            if (admin) out.addAll(List.of("zbuduj", "test", "punkt", "status", "usun", "stop"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("test") && admin) {
            out.addAll(List.of("duel", "jackpot"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("punkt") && admin) {
            out.addAll(List.of("a", "b", "center", "view"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("bank") && args[1].equalsIgnoreCase("punkt") && admin) {
            out.addAll(pl.kasyno.show.Stage.REQUIRED);
            out.addAll(pl.kasyno.show.Stage.OPTIONAL);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("pack")) {
            out.addAll(List.of("on", "off", "auto", "wyslij"));
            if (admin) out.add("info");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("npc") && admin) {
            out.addAll(List.of("spawn", "remove", "list", "removeall", "types"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("placeholder") || args[0].equalsIgnoreCase("ph")) && admin) {
            out.addAll(List.of("spawn", "napis", "remove", "list", "pokaz", "removeall", "types"));
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("placeholder") || args[0].equalsIgnoreCase("ph")) && args[1].equalsIgnoreCase("spawn") && admin) {
            out.addAll(plugin.placeholders().types());
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("placeholder") || args[0].equalsIgnoreCase("ph")) && args[1].equalsIgnoreCase("napis") && admin) {
            out.addAll(List.of("auto", "off"));
        } else if (args.length >= 4 && args.length <= 5 && (args[0].equalsIgnoreCase("placeholder") || args[0].equalsIgnoreCase("ph")) && args[1].equalsIgnoreCase("spawn") && admin) {
            out.addAll(List.of("1", "1,5", "2", "3"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("spawn") && admin) {
            out.addAll(plugin.npcs().types());
        } else if (args.length == 4 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("spawn") && admin && Jobs.isJob(args[2].toLowerCase(Locale.ROOT))) {
            out.addAll(List.of("20", "30", "40", "60", "80"));
        } else if (args[0].equalsIgnoreCase("admin") && admin) {
            if (args.length == 2) out.addAll(List.of("give", "take", "set", "item", "cooldown"));
            else if (args.length == 3) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            else if (args.length == 4 && args[1].equalsIgnoreCase("item")) out.addAll(plugin.catalog().items.keySet());
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(typed)).limit(50).toList();
    }

}
