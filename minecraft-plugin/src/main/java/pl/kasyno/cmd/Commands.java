package pl.kasyno.cmd;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.PlayerData;
import pl.kasyno.games.GameGui;
import pl.kasyno.games.GameRegistry;
import pl.kasyno.games.Rooms;
import pl.kasyno.gui.Actions;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Notify;
import pl.kasyno.gui.Screens;
import pl.kasyno.gui.T;
import pl.kasyno.gui.screens.CrateOpenGui;
import pl.kasyno.gui.screens.LotteryGui;
import pl.kasyno.gui.screens.ProfileGui;
import pl.kasyno.gui.screens.QuizGui;
import pl.kasyno.gui.screens.RoomGui;
import pl.kasyno.gui.screens.WorkHubGui;
import pl.kasyno.work.JobDef;
import pl.kasyno.work.Jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Wszystkie komendy po polsku (jak w bocie Discord): /saldo, /sklep, /moneta, /daily itd. Każda komenda otwiera swoje GUI
 * albo wykonuje akcję i pisze wynik na czacie. Główna komenda /kasyno jest w RootCommand.
 */
public final class Commands {
    private Commands() {}

    @FunctionalInterface
    interface Run {
        void run(Player p, String[] args);
    }

    private static final class Cmd extends Command {
        private final Run run;
        private final BiFunction<Player, String[], List<String>> complete;

        Cmd(String name, String description, String usage, List<String> aliases, Run run, BiFunction<Player, String[], List<String>> complete) {
            super(name, description, usage, aliases);
            this.run = run;
            this.complete = complete;
            setPermission("kasyno.use");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Ta komenda jest tylko dla graczy.");
                return true;
            }
            if (!p.hasPermission("kasyno.use")) {
                T.send(p, "<red>Nie masz uprawnień do kasyna.");
                return true;
            }
            try {
                run.run(p, args);
            } catch (RuntimeException e) {
                T.send(p, "<red>Coś poszło nie tak. Spróbuj ponownie.");
                Bukkit.getLogger().severe("Błąd komendy /" + label + ": " + e);
                e.printStackTrace();
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!(sender instanceof Player p) || complete == null) return List.of();
            return complete.apply(p, args);
        }
    }

    private static List<String> filter(List<String> options, String typed) {
        String t = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(t)).limit(40).toList();
    }

    private static List<String> players(Player self) {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (!p.equals(self)) out.add(p.getName());
        return out;
    }

    private static void reg(KasynoPlugin plugin, String name, String desc, String usage, List<String> aliases, Run run, BiFunction<Player, String[], List<String>> complete) {
        if (!plugin.getConfig().getBoolean("short-commands", true)) return;
        Bukkit.getCommandMap().register("kasyno", new Cmd(name, desc, usage, aliases, run, complete));
    }

    private static void screen(KasynoPlugin plugin, String name, String desc, String key, String... aliases) {
        reg(plugin, name, desc, "/" + name, List.of(aliases), (p, a) -> Screens.open(plugin, p, key), null);
    }

    /** Gracz po nazwie: online albo znany z zapisów (offline). */
    static UUID findPlayer(KasynoPlugin plugin, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<UUID, PlayerData> e : plugin.store().all().entrySet()) if (e.getValue().name.equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    public static void registerAll(KasynoPlugin plugin) {
        RootCommand root = new RootCommand(plugin);
        var kasyno = plugin.getCommand("kasyno");
        if (kasyno != null) {
            kasyno.setExecutor(root);
            kasyno.setTabCompleter(root);
        }

        // ekrany
        screen(plugin, "sklep", "Sklep z przedmiotami", "sklep", "shop");
        screen(plugin, "ekwipunek", "Twój ekwipunek ekonomii", "ekwipunek", "inv");
        screen(plugin, "bank", "Bank: wpłaty, wypłaty i ulepszenia", "bank");
        screen(plugin, "biznes", "Biznesy i farma", "biznes", "farma", "business", "farm");
        screen(plugin, "skrzynie", "Skrzynie z nagrodami", "skrzynie", "crates", "skrzynka");
        screen(plugin, "top", "Ranking graczy", "ranking", "ranking", "leaderboard");
        screen(plugin, "pomoc", "Pomoc kasyna", "pomoc");
        screen(plugin, "zarobki", "Zarobki: nagrody, praca, zbieranie", "zarobki", "earn");
        screen(plugin, "kasyno-gry", "Lista sal kasyna", "kasyno", "gry");
        screen(plugin, "automaty", "Sala automatów: sloty, zdrapki, moneta, szansa", "automaty", "slotsroom");
        screen(plugin, "karty", "Sala kart i kości: blackjack, hi-lo, kości, KNP", "karty", "cardsroom");
        screen(plugin, "ryzyko", "Sala ryzyka: miny, kurczak, wieża, plinko, limbo, rewolwer", "ryzyko", "riskroom");
        screen(plugin, "live", "Stoły na żywo: crash, ruletka, koło, wyścigi", "live", "stoly", "tables");
        screen(plugin, "pokoj", "Pokój graczy: jackpot, napad, pojedynek", "pokoj", "lobby");

        // prace w terenie: świecące cele w prawdziwym świecie, zlecane przez NPC
        reg(plugin, "prace", "Prace w terenie: zlecenia od NPC", "/prace", List.of("jobs"), (p, a) -> new WorkHubGui(plugin, p).open(), null);
        for (JobDef job : Jobs.ALL) screen(plugin, job.id(), "Praca w terenie: " + job.title() + " (NPC: " + job.npc() + ")", job.id());

        // saldo i konto
        reg(plugin, "saldo", "Pokaż saldo", "/saldo", List.of("bal", "balance"), (p, a) -> saldo(plugin, p), null);
        reg(plugin, "profil", "Profil gracza", "/profil [gracz]", List.of("profile"), (p, a) -> {
            UUID target = p.getUniqueId();
            if (a.length > 0) {
                target = findPlayer(plugin, a[0]);
                if (target == null) {
                    T.send(p, "<red>Nie znam gracza „" + T.esc(a[0]) + "”.");
                    return;
                }
            }
            new ProfileGui(plugin, p, target).open();
        }, (p, a) -> a.length == 1 ? filter(players(p), a[0]) : List.of());
        reg(plugin, "wplac", "Wpłać do banku", "/wplac <kwota>", List.of("dep", "deposit"), (p, a) -> bankMove(plugin, p, a, true), (p, a) -> a.length == 1 ? filter(List.of("all", "50%", "1k", "10k"), a[0]) : List.of());
        reg(plugin, "wyplac", "Wypłać z banku", "/wyplac <kwota>", List.of("withdraw"), (p, a) -> bankMove(plugin, p, a, false), (p, a) -> a.length == 1 ? filter(List.of("all", "50%", "1k", "10k"), a[0]) : List.of());
        reg(plugin, "przelew", "Przelej pieniądze graczowi", "/przelew <gracz> <kwota>", List.of("pay"), (p, a) -> przelew(plugin, p, a), (p, a) -> a.length == 1 ? filter(players(p), a[0]) : List.of());

        // sklep i przedmioty
        List<String> shopIds = plugin.catalog().shopOrder();
        reg(plugin, "kup", "Kup przedmiot", "/kup <przedmiot> [ilość]", List.of("buy"), (p, a) -> kup(plugin, p, a), (p, a) -> a.length == 1 ? filter(shopIds, a[0]) : List.of());
        reg(plugin, "sprzedaj", "Sprzedaj łup", "/sprzedaj <przedmiot|wszystko> [ilość]", List.of("sell"), (p, a) -> sprzedaj(plugin, p, a), (p, a) -> {
            if (a.length != 1) return List.of();
            List<String> own = new ArrayList<>(List.of("wszystko"));
            own.addAll(plugin.eco().pd(p.getUniqueId()).inventory.keySet());
            return filter(own, a[0]);
        });
        reg(plugin, "uzyj", "Użyj eliksiru, polisy, tarczy albo skrzyni", "/uzyj <przedmiot>", List.of("use"), (p, a) -> uzyj(plugin, p, a), (p, a) -> {
            if (a.length != 1) return List.of();
            List<String> own = new ArrayList<>();
            for (String id : plugin.eco().pd(p.getUniqueId()).inventory.keySet()) {
                Catalog.Item it = plugin.catalog().item(id);
                if (it != null && Actions.usable(plugin, it)) own.add(id);
            }
            return filter(own, a[0]);
        });
        reg(plugin, "mnozniki", "Twoje mnożniki i aktywne bonusy", "/mnozniki", List.of("boosty", "bonusy"), (p, a) -> mnozniki(plugin, p), null);

        reg(plugin, "wiezienie", "Więzienie: lista zadań, dzięki którym wychodzisz", "/wiezienie", List.of("cela", "jail"), (p, a) -> Screens.open(plugin, p, "wiezienie"), null);

        // zarobki: akcje
        for (var e : Map.of("daily", "daily", "weekly", "weekly", "godzinna", "godzinna", "praca", "praca", "zebraj", "zebraj", "uliczny", "uliczny", "przestepstwo", "przestepstwo").entrySet()) {
            String key = e.getKey();
            List<String> alias = key.equals("godzinna") ? List.of("hourly") : key.equals("praca") ? List.of("work") : key.equals("uliczny") ? List.of("busk") : key.equals("przestepstwo") ? List.of("crime") : List.of();
            reg(plugin, key, "Zarobki: " + key, "/" + key, alias, (p, a) -> Actions.claim(plugin, p, e.getValue()), null);
        }
        for (String g : List.of("ryby", "kopalnia", "polowanie", "las", "smietnik")) {
            reg(plugin, g, "Zbieranie: " + g, "/" + g, g.equals("ryby") ? List.of("fish") : g.equals("kopalnia") ? List.of("mine") : g.equals("polowanie") ? List.of("hunt") : List.of(), (p, a) -> Actions.gather(plugin, p, g), null);
        }
        reg(plugin, "trivia", "Quiz wiedzy", "/trivia", List.of(), (p, a) -> quiz(plugin, p, true), null);
        reg(plugin, "matma", "Szybkie liczenie", "/matma", List.of(), (p, a) -> quiz(plugin, p, false), null);
        reg(plugin, "okradnij", "Okradnij gracza", "/okradnij <gracz>", List.of("rob"), (p, a) -> {
            Player victim = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : null;
            if (victim == null) {
                T.send(p, "<red>Użycie: /okradnij <gracz online>");
                return;
            }
            Actions.rob(plugin, p, victim);
        }, (p, a) -> a.length == 1 ? filter(players(p), a[0]) : List.of());

        // gry z innymi graczami
        reg(plugin, "napad", "Napad na bank z ekipą", "/napad", List.of("heist"), (p, a) -> new RoomGui(plugin, p, Rooms.Kind.HEIST).open(), null);
        reg(plugin, "jackpot", "Jackpot: wszyscy dorzucają, jedna osoba wygrywa", "/jackpot [stawka]", List.of(), (p, a) -> {
            if (a.length > 0) {
                long s = Fmt.parseAmount(a[0], plugin.eco().wallet(p.getUniqueId()));
                String err = s < 1 ? "Nie rozumiem stawki." : plugin.rooms().lobby(Rooms.Kind.JACKPOT) != null && plugin.rooms().lobby(Rooms.Kind.JACKPOT).open() ? plugin.rooms().join(p, Rooms.Kind.JACKPOT) : plugin.rooms().start(p, Rooms.Kind.JACKPOT, s);
                if (err != null) {
                    Fx.deny(p);
                    T.send(p, "<red>" + err);
                    return;
                }
            }
            new RoomGui(plugin, p, Rooms.Kind.JACKPOT).open();
        }, null);
        reg(plugin, "pojedynek", "Wyzwij gracza na pojedynek", "/pojedynek <gracz> <stawka>", List.of("duel"), (p, a) -> {
            if (a.length < 2) {
                T.send(p, "<red>Użycie: /pojedynek <gracz> <stawka>");
                return;
            }
            Player op = Bukkit.getPlayerExact(a[0]);
            long s = Fmt.parseAmount(a[1], plugin.eco().wallet(p.getUniqueId()));
            if (op == null || s < 1) {
                T.send(p, "<red>Podaj gracza online i poprawną stawkę.");
                return;
            }
            String err = plugin.rooms().challenge(p, op, s);
            if (err != null) {
                Fx.deny(p);
                T.send(p, "<red>" + err);
            } else T.send(p, "<green>Wyzwanie wysłane do <yellow>" + T.esc(op.getName()) + "</yellow>. Ma minutę na odpowiedź.");
        }, (p, a) -> a.length == 1 ? filter(players(p), a[0]) : List.of());
        reg(plugin, "loteria", "Codzienna loteria", "/loteria [kup <ile>]", List.of("lottery"), (p, a) -> {
            if (a.length >= 2 && a[0].equalsIgnoreCase("kup")) {
                int n;
                try {
                    n = Integer.parseInt(a[1]);
                } catch (NumberFormatException e) {
                    T.send(p, "<red>Podaj liczbę biletów.");
                    return;
                }
                String err = plugin.lottery().buy(p.getUniqueId(), n);
                T.send(p, err != null ? "<red>" + err : "<green>Kupiłeś <white>" + n + "</white> biletów.");
                return;
            }
            new LotteryGui(plugin, p).open();
        }, (p, a) -> a.length == 1 ? filter(List.of("kup"), a[0]) : List.of());

        // gry z GUI: /moneta [stawka] itd.
        Map<String, List<String>> gameAliases = Map.of("sloty", List.of("slots"), "kosci", List.of("dice"), "ruletka", List.of("roulette"), "kolo", List.of("wheel"), "zdrapka", List.of("scratch"),
                "wyscigi", List.of("horses"), "knp", List.of("rps"), "blackjack", List.of("bj"), "miny", List.of("mines"), "kurczak", List.of("chicken", "chickenroad"));
        Map<String, List<String>> more = Map.of("wieza", List.of("tower"), "rewolwer", List.of("rr", "revolver"), "szansa", List.of("chance"), "moneta", List.of("coinflip", "cf"));
        for (GameRegistry.Def g : GameRegistry.all()) {
            List<String> alias = gameAliases.getOrDefault(g.id(), more.getOrDefault(g.id(), List.of()));
            reg(plugin, g.id(), g.name() + ": " + g.desc(), "/" + g.id() + " [stawka]", alias, (p, a) -> {
                if (a.length > 0) {
                    long s = Fmt.parseAmount(a[0], plugin.eco().wallet(p.getUniqueId()));
                    if (s >= Eco.MIN_BET) GameGui.presetStake(p.getUniqueId(), g.id(), s);
                }
                g.factory().apply(plugin, p).open();
            }, (p, a) -> a.length == 1 ? filter(List.of("100", "500", "1k", "10k", "50%", "all"), a[0]) : List.of());
        }
    }

    // ---------- implementacje ----------

    private static void saldo(KasynoPlugin plugin, Player p) {
        UUID id = p.getUniqueId();
        PlayerData d = plugin.eco().pd(id);
        T.send(p, "<gray>Portfel: <gold>" + Fmt.money(d.wallet) + " <gray>• Bank: <gold>" + Fmt.money(d.bank) + "<gray>/<gold>" + Fmt.money(plugin.eco().bank(id).total())
                + " <gray>• Poziom <white>" + d.level + " <gray>(" + Fmt.shortNum(d.xp) + "/" + Fmt.shortNum(Eco.xpNeeded(d.level)) + " XP) • Ranking <white>#" + plugin.eco().rankOf(id));
    }

    private static void bankMove(KasynoPlugin plugin, Player p, String[] a, boolean deposit) {
        UUID id = p.getUniqueId();
        PlayerData d = plugin.eco().pd(id);
        long amt = a.length > 0 ? Fmt.parseAmount(a[0], deposit ? d.wallet : d.bank) : -1;
        if (amt < 1) {
            T.send(p, "<red>Użycie: /" + (deposit ? "wplac" : "wyplac") + " <kwota> <gray>(np. 500, 2k, 50%, all)");
            return;
        }
        long moved = deposit ? plugin.eco().deposit(id, amt) : plugin.eco().withdraw(id, amt);
        if (moved < 1) {
            Fx.deny(p);
            T.send(p, deposit ? "<red>Nic nie wpłacono: brak monet albo bank jest pełny." : "<red>Nie masz nic w banku.");
        } else {
            Fx.coin(p);
            T.send(p, "<green>" + (deposit ? "Wpłacono <gold>" : "Wypłacono <gold>") + Fmt.money(moved) + "</gold>" + (deposit ? " do banku." : " z banku."));
        }
    }

    private static void przelew(KasynoPlugin plugin, Player p, String[] a) {
        if (a.length < 2) {
            T.send(p, "<red>Użycie: /przelew <gracz> <kwota>");
            return;
        }
        Player target = Bukkit.getPlayerExact(a[0]);
        UUID tid = target != null ? target.getUniqueId() : findPlayer(plugin, a[0]);
        if (tid == null) {
            T.send(p, "<red>Nie znam gracza „" + T.esc(a[0]) + "”.");
            return;
        }
        if (tid.equals(p.getUniqueId())) {
            T.send(p, "<red>Przelew do samego siebie? Nic by to nie zmieniło.");
            return;
        }
        long amt = Fmt.parseAmount(a[1], plugin.eco().wallet(p.getUniqueId()));
        if (amt < 1) {
            T.send(p, "<red>Podaj poprawną kwotę, np. 500, 2k, 50%.");
            return;
        }
        if (!plugin.eco().transfer(p.getUniqueId(), tid, amt)) {
            Fx.deny(p);
            T.send(p, "<red>Masz tylko <gold>" + Fmt.money(plugin.eco().wallet(p.getUniqueId())) + "</gold> w portfelu.");
            return;
        }
        Fx.coin(p);
        T.send(p, "<green>Przelano <gold>" + Fmt.money(amt) + "</gold> graczowi " + T.esc(plugin.eco().pd(tid).name) + ".");
        if (target != null) T.send(target, "<green>" + T.esc(p.getName()) + " przelał Ci <gold>" + Fmt.money(amt) + "</gold>.");
    }

    private static void kup(KasynoPlugin plugin, Player p, String[] a) {
        if (a.length == 0) {
            T.send(p, "<red>Użycie: /kup <przedmiot> [ilość]. Katalog: /sklep");
            return;
        }
        Catalog.Item it = Actions.resolveItem(plugin.catalog(), a[0], plugin.catalog().shopOrder());
        if (it == null) {
            T.send(p, "<red>Nie ma w sklepie przedmiotu „" + T.esc(a[0]) + "”. Zobacz: /sklep");
            return;
        }
        int qty = 1;
        if (a.length > 1) {
            try {
                qty = Math.max(1, Math.min(100, Integer.parseInt(a[1])));
            } catch (NumberFormatException e) {
                T.send(p, "<red>Ilość musi być liczbą.");
                return;
            }
        }
        Actions.buy(plugin, p, it, qty);
    }

    private static void sprzedaj(KasynoPlugin plugin, Player p, String[] a) {
        if (a.length == 0) {
            T.send(p, "<red>Użycie: /sprzedaj <przedmiot|wszystko> [ilość]");
            return;
        }
        if (a[0].equalsIgnoreCase("wszystko")) {
            Actions.sellAllLoot(plugin, p);
            return;
        }
        Catalog.Item it = Actions.resolveItem(plugin.catalog(), a[0], plugin.catalog().items.keySet());
        if (it == null) {
            T.send(p, "<red>Nie znam przedmiotu „" + T.esc(a[0]) + "”.");
            return;
        }
        int qty = 0;
        if (a.length > 1) {
            try {
                qty = Integer.parseInt(a[1]);
            } catch (NumberFormatException e) {
                T.send(p, "<red>Ilość musi być liczbą.");
                return;
            }
        }
        Actions.sell(plugin, p, it, qty);
    }

    private static void uzyj(KasynoPlugin plugin, Player p, String[] a) {
        if (a.length == 0) {
            T.send(p, "<red>Użycie: /uzyj <przedmiot>");
            return;
        }
        Catalog.Item it = Actions.resolveItem(plugin.catalog(), a[0], plugin.eco().pd(p.getUniqueId()).inventory.keySet());
        if (it == null) it = Actions.resolveItem(plugin.catalog(), a[0], plugin.catalog().items.keySet());
        if (it == null) {
            T.send(p, "<red>Nie wiem, co chcesz użyć („" + T.esc(a[0]) + "”).");
            return;
        }
        if (it.type().equals("crate")) {
            if (plugin.eco().qty(p.getUniqueId(), it.id()) < 1) T.send(p, "<red>Nie masz: " + it.name());
            else new CrateOpenGui(plugin, p, it.id(), 1).open();
            return;
        }
        Actions.use(plugin, p, it);
    }

    private static void quiz(KasynoPlugin plugin, Player p, boolean trivia) {
        String key = trivia ? "trivia" : "matma";
        long left = plugin.eco().cooldownLeft(p.getUniqueId(), key);
        if (left > 0) {
            Notify.cooldown(p, left);
            return;
        }
        plugin.eco().guardCooldown(p.getUniqueId(), key, plugin.eco().cooldownOf(key));
        (trivia ? QuizGui.trivia(plugin, p) : QuizGui.math(plugin, p)).open();
    }

    private static void mnozniki(KasynoPlugin plugin, Player p) {
        UUID id = p.getUniqueId();
        Eco eco = plugin.eco();
        T.send(p, "<gold><bold>Twoje mnożniki");
        T.raw(p, "<gray> Zarobki: <white>x" + String.format(Locale.ROOT, "%.2f", eco.applyEarn(id, 1000).mult()) + " <gray>• XP: <white>x" + String.format(Locale.ROOT, "%.2f", eco.xpMultiplier(id))
                + " <gray>• Łup: <white>x" + String.format(Locale.ROOT, "%.2f", eco.lootMultiplier(id)) + " <gray>• Szczęście: <white>x" + String.format(Locale.ROOT, "%.2f", eco.luckMultiplier(id))
                + " <gray>• Cooldowny: <white>x" + String.format(Locale.ROOT, "%.2f", eco.boostValue(id, "cooldown")));
        for (Catalog.Boost b : plugin.catalog().boosts.values()) {
            long left = eco.boostLeft(id, b.key());
            if (left > 0) T.raw(p, "<green> • " + plugin.catalog().item(b.id()).name() + ": " + b.label() + " <gray>(" + Fmt.duration(left) + ")");
        }
        for (Catalog.Protection pr : plugin.catalog().protections.values()) {
            int uses = eco.buffUses(id, pr.id());
            if (uses > 0) T.raw(p, "<aqua> • " + plugin.catalog().item(pr.id()).name() + " x" + uses + " <gray>(do " + Fmt.money(pr.cap()) + ")");
        }
        int clover = eco.buffUses(id, "koniczyna");
        if (clover > 0) T.raw(p, "<gold> • Koniczyna: " + clover + " wygranych z bonusem");
        eco.vipCard(id).ifPresent(c -> T.raw(p, "<yellow> • " + plugin.catalog().item(c).name() + ": +" + Math.round(plugin.catalog().bonuses.vip().get(c) * 100) + "% zarobków"));
    }
}
