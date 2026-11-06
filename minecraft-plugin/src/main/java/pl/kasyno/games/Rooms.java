package pl.kasyno.games;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Heist;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.T;
import pl.kasyno.gui.screens.DuelGui;
import pl.kasyno.gui.screens.RoomGui;
import pl.kasyno.prison.PrisonRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gry z wieloma graczami: jackpot (wszyscy dorzucają stawkę, jedna osoba bierze pulę), napad na bank (ekipa dzieli łup)
 * i pojedynki (50/50). Stan trzymamy w pamięci, a pieniądze rozliczamy od razu przy zapisie wyniku.
 */
public final class Rooms {
    public enum Kind { JACKPOT, HEIST }

    public static final double JACKPOT_FEE = 0.05;
    public static final double DUEL_FEE = 0.03;
    public static final int JACKPOT_MAX_PLAYERS = 20;
    public static final int HEIST_MAX_PLAYERS = 10;
    public static final long HEIST_STAKE = 500;
    public static final long JOIN_MS = 45_000;
    public static final long DUEL_WAIT_MS = 60_000;
    /** Jak długo po losowaniu wynik jest dostępny w oknie lobby (scena napadu trwa ok. 20 s). */
    public static final long RESULT_KEEP_MS = 60_000;

    /** Otwarte lobby albo świeżo rozstrzygnięty wynik (do animacji u graczy). */
    public static final class Lobby {
        public final Kind kind;
        public final UUID starter;
        public final long stake;
        public final Set<UUID> players = new LinkedHashSet<>();
        public final long endsAt;
        public boolean resolved;
        public boolean cancelled;
        public long resolvedAt;
        public UUID winner;
        public boolean success;
        public long pot;
        public long payout;
        public long share;
        /** Uczestnicy w chwili losowania (kolejność jak w lobby). */
        public List<UUID> crew = List.of();
        /** Scenariusz napadu (etapy i wpadki) albo null. */
        public Heist.Result heist;
        /** Film napadu: klatki animacji mapy banku (z scenariusza). */
        public List<HeistFrames.Frame> frames = List.of();
        /** Klatki losowania jackpota: indeks uczestnika, na którym stoi podświetlenie. */
        public int[] spin;
        /** Ostatnia odegrana scena napadu albo klatka losowania (-1 = jeszcze nic). */
        public int step = -1;
        public boolean sceneDone;
        BukkitTask task;

        Lobby(Kind kind, UUID starter, long stake, long endsAt) {
            this.kind = kind;
            this.starter = starter;
            this.stake = stake;
            this.endsAt = endsAt;
            players.add(starter);
        }

        public boolean open() {
            return !resolved && !cancelled;
        }
    }

    /** Wyzwanie na pojedynek. */
    public static final class Duel {
        public final UUID challenger;
        public final UUID opponent;
        public final long stake;
        public final long expiresAt;
        BukkitTask task;

        Duel(UUID challenger, UUID opponent, long stake, long expiresAt) {
            this.challenger = challenger;
            this.opponent = opponent;
            this.stake = stake;
            this.expiresAt = expiresAt;
        }
    }

    private final KasynoPlugin plugin;
    private final Map<Kind, Lobby> lobbies = new HashMap<>();
    private final Map<UUID, Duel> duels = new HashMap<>(); // klucz: przeciwnik (jedno wyzwanie naraz)

    public Rooms(KasynoPlugin plugin) {
        this.plugin = plugin;
    }

    private Eco eco() {
        return plugin.eco();
    }

    public Lobby lobby(Kind kind) {
        Lobby l = lobbies.get(kind);
        if (l != null && l.resolved && System.currentTimeMillis() - l.resolvedAt > RESULT_KEEP_MS) {
            lobbies.remove(kind);
            return null;
        }
        return l;
    }

    public boolean hasLive() {
        return lobby(Kind.JACKPOT) != null && lobby(Kind.JACKPOT).open() || lobby(Kind.HEIST) != null && lobby(Kind.HEIST).open();
    }

    public Duel duelFor(UUID opponent) {
        Duel d = duels.get(opponent);
        if (d != null && System.currentTimeMillis() > d.expiresAt) {
            duels.remove(opponent);
            return null;
        }
        return d;
    }

    public void shutdown() {
        for (Lobby l : new ArrayList<>(lobbies.values())) {
            if (l.open()) cancel(l, "Serwer się wyłącza.");
            else if (l.resolved && !l.cancelled && !l.sceneDone) {
                // pieniądze są już rozliczone; gracze dostają wynik od razu, bez dokańczania sceny
                l.sceneDone = true;
                announce(l);
            }
        }
        for (Duel d : new ArrayList<>(duels.values())) if (d.task != null) d.task.cancel();
        duels.clear();
    }

    private static String name(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : Bukkit.getOfflinePlayer(id).getName() == null ? "?" : Bukkit.getOfflinePlayer(id).getName();
    }

    private void broadcast(String mm) {
        for (Player p : Bukkit.getOnlinePlayers()) T.send(p, mm);
    }

    // ---------- lobby: jackpot i napad ----------

    /** Zwraca komunikat błędu albo null, gdy lobby się otworzyło. */
    public String start(Player starter, Kind kind, long stake) {
        UUID id = starter.getUniqueId();
        Lobby cur = lobby(kind);
        if (cur != null && cur.open()) return "Lobby już trwa. Dołącz do niego!";
        if (kind == Kind.HEIST) {
            stake = HEIST_STAKE;
            long left = eco().cooldownLeft(id, "heist");
            if (left > 0) return "Policja jeszcze Cię szuka. Następny napad za " + Fmt.duration(left) + ".";
        } else {
            if (stake < Eco.MIN_BET) return "Minimalna stawka to " + Fmt.money(Eco.MIN_BET) + ".";
            if (stake > Eco.MAX_BET) return "Maksymalna stawka to " + Fmt.money(Eco.MAX_BET) + ".";
        }
        if (!eco().take(id, stake)) return "Masz za mało w portfelu na stawkę " + Fmt.money(stake) + ".";
        if (kind == Kind.HEIST) eco().setCooldown(id, "heist", eco().scaledCooldown(id, eco().cooldownOf("heist")));
        Lobby l = new Lobby(kind, id, stake, System.currentTimeMillis() + JOIN_MS);
        lobbies.put(kind, l);
        l.task = Bukkit.getScheduler().runTaskLater(plugin, () -> resolve(l), JOIN_MS / 50);
        String what = kind == Kind.JACKPOT ? "jackpot" : "napad na bank";
        broadcast("<yellow>" + T.esc(starter.getName()) + " otwiera <gold>" + what + "</gold>! Stawka: <gold>" + Fmt.money(stake) + "</gold>. "
                + "<click:run_command:'/kasyno room join " + kind.name().toLowerCase() + "'><green><bold>[DOŁĄCZ]</bold></green></click> <gray>(45 s, min. 2 osoby)");
        return null;
    }

    public String join(Player p, Kind kind) {
        Lobby l = lobby(kind);
        UUID id = p.getUniqueId();
        if (l == null || !l.open()) return "Nie ma otwartego lobby.";
        if (l.players.contains(id)) return "Już jesteś w grze.";
        if (l.players.size() >= (kind == Kind.JACKPOT ? JACKPOT_MAX_PLAYERS : HEIST_MAX_PLAYERS)) return "Lobby jest pełne.";
        if (kind == Kind.HEIST) {
            long left = eco().cooldownLeft(id, "heist");
            if (left > 0) return "Odczekaj jeszcze " + Fmt.duration(left) + " po poprzednim napadzie.";
        }
        if (!eco().take(id, l.stake)) return "Nie masz " + Fmt.money(l.stake) + " w portfelu.";
        if (kind == Kind.HEIST) eco().setCooldown(id, "heist", eco().scaledCooldown(id, eco().cooldownOf("heist")));
        l.players.add(id);
        T.send(p, "<green>Dołączyłeś do gry. Stawka <gold>" + Fmt.money(l.stake) + "</gold> pobrana.");
        for (UUID other : l.players) {
            Player op = Bukkit.getPlayer(other);
            if (op != null && !other.equals(id)) T.send(op, "<gray>" + T.esc(p.getName()) + " dołączył (" + l.players.size() + " graczy).");
        }
        return null;
    }

    private void cancel(Lobby l, String why) {
        for (UUID id : l.players) {
            eco().add(id, l.stake);
            if (l.kind == Kind.HEIST) eco().clearCooldown(id, "heist");
            Player p = Bukkit.getPlayer(id);
            if (p != null) T.send(p, "<yellow>" + why + " Stawka <gold>" + Fmt.money(l.stake) + "</gold> wróciła do portfela.");
        }
        l.cancelled = true;
        l.resolved = true;
        l.resolvedAt = System.currentTimeMillis();
        if (l.task != null) l.task.cancel();
    }

    private void resolve(Lobby l) {
        if (l.resolved) return;
        if (l.players.size() < 2) {
            cancel(l, "Nikt nie dołączył, gra odwołana.");
            return;
        }
        int n = l.players.size();
        List<UUID> list = new ArrayList<>(l.players);
        l.crew = list;
        l.pot = l.stake * n;
        if (l.kind == Kind.JACKPOT) {
            l.payout = l.pot - (long) Math.ceil(l.pot * JACKPOT_FEE);
            l.winner = list.get(eco().rng.nextInt(n));
            eco().add(l.winner, l.payout);
            for (UUID id : list) {
                eco().recordGame(id, id.equals(l.winner) ? l.payout - l.stake : -l.stake);
                eco().addXp(id, id.equals(l.winner) ? 25 : 8);
            }
            l.spin = RoomScenes.spinFrames(n, list.indexOf(l.winner));
        } else {
            // napad: scenariusz etapami; łup dzielą ci, którzy dotarli do końca, a złapani tracą wpisowe
            l.heist = Heist.run(list, Rooms::name, eco().rng);
            l.frames = HeistFrames.build(l.heist, list);
            l.success = l.heist.success();
            if (l.success) {
                l.payout = (long) Math.floor(l.pot * l.heist.multiplier());
                l.share = l.payout / l.heist.survivors().size();
                for (UUID id : l.heist.survivors()) {
                    eco().add(id, l.share);
                    eco().recordGame(id, l.share - l.stake);
                    eco().addXp(id, 30);
                }
            }
            for (UUID id : l.heist.caught()) {
                eco().recordGame(id, -l.stake);
                eco().addXp(id, 5);
            }
        }
        l.resolved = true;
        l.resolvedAt = System.currentTimeMillis();
        // wynik jest już rozliczony; scena (dźwięki, napisy, okno albo pokaz w świecie) tylko go odgrywa, a podsumowanie
        // na czacie przychodzi na końcu
        if (l.kind == Kind.JACKPOT) {
            // bez areny w świecie (albo gdy zajęta) losowanie gra się w oknie z głowami graczy
            if (!(plugin.show().arenaReady() && RoomScenes.jackpotWorld(plugin, this, l))) {
                openWindows(l);
                RoomScenes.jackpot(plugin, this, l);
            }
        } else if (!(plugin.show().ready() && RoomScenes.heistWorld(plugin, this, l))) {
            // bez sceny w świecie (albo gdy się nie uda) napad gra się w oknie z mapą
            openWindows(l);
            RoomScenes.heist(plugin, this, l);
        }
    }

    /** Okno sceny otwiera się samo każdemu z ekipy (kto już je ma, ten ogląda od razu), więc sceny nie da się przegapić. */
    private void openWindows(Lobby l) {
        for (UUID id : l.crew) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            var top = p.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof RoomGui rg && rg.kind() == l.kind) continue;
            plugin.guis().next(() -> {
                if (p.isOnline()) new RoomGui(plugin, p, l.kind).open();
            });
        }
    }

    /** Podsumowanie na czacie po zakończeniu sceny: wygrana, przegrana, fanfary. */
    void announce(Lobby l) {
        List<UUID> list = new ArrayList<>(l.players);
        for (UUID id : list) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (l.kind == Kind.JACKPOT) {
                if (id.equals(l.winner)) {
                    T.send(p, "<green><bold>WYGRYWASZ JACKPOT!</bold> <gold>" + Fmt.money(l.payout) + "</gold> <gray>(pula " + Fmt.money(l.pot) + ", szansa " + String.format(java.util.Locale.ROOT, "%.1f", 100.0 / list.size()) + "%)");
                    Fx.jackpot(p);
                } else {
                    T.send(p, "<red>Jackpot wygrywa <white>" + T.esc(name(l.winner)) + "</white> (<gold>" + Fmt.money(l.payout) + "</gold>). Tracisz <gold>" + Fmt.money(l.stake) + "</gold>.");
                    Fx.lose(p);
                }
            } else if (l.heist != null) {
                boolean safe = l.heist.survivors().contains(id);
                String caughtNames = String.join(", ", l.heist.caught().stream().map(u -> T.esc(name(u))).toList());
                if (safe) {
                    T.send(p, "<green><bold>Napad udany!</bold> Łup <gold>" + Fmt.money(l.payout) + "</gold> (x" + String.format(java.util.Locale.ROOT, "%.2f", l.heist.multiplier()).replace('.', ',') + " wpisowego) dzielicie na "
                            + l.heist.survivors().size() + " os. Twój udział: <gold>" + Fmt.money(l.share) + "</gold> <gray>(wpisowe " + Fmt.money(l.stake) + ")");
                    if (!l.heist.caught().isEmpty()) T.send(p, "<gray>Po drodze wpadli: <white>" + caughtNames);
                    Fx.win(p);
                } else {
                    Heist.Beat where = l.heist.beats().stream().filter(b -> b.caught().contains(id)).findFirst().orElse(null);
                    T.send(p, "<red><bold>Wpadłeś!</bold> " + (where == null ? "" : "Etap: <white>" + Heist.STAGES.get(where.stage()).name() + "</white>. ") + "Tracisz wpisowe <gold>" + Fmt.money(l.stake) + "</gold>."
                            + (l.success ? " <gray>Reszta ekipy uciekła z łupem." : " <gray>Cała ekipa trafiła za kratki."));
                    if (plugin.prison().canJail(p)) T.send(p, "<red>Policja zabiera Cię do więzienia.");
                    Fx.lose(p);
                }
            }
        }
        // złapani idą za kratki także wtedy, gdy zdążyli już wyjść z serwera (trafią tam przy wejściu)
        if (l.heist != null) for (UUID id : l.heist.caught()) plugin.prison().jail(id, PrisonRules.Reason.HEIST);
    }

    // ---------- pojedynek ----------

    public String challenge(Player challenger, Player opponent, long stake) {
        if (challenger.getUniqueId().equals(opponent.getUniqueId())) return "Pojedynek z samym sobą? Zawsze wygrasz i przegrasz jednocześnie.";
        if (stake < Eco.MIN_BET) return "Minimalna stawka to " + Fmt.money(Eco.MIN_BET) + ".";
        if (stake > Eco.MAX_BET) return "Maksymalna stawka to " + Fmt.money(Eco.MAX_BET) + ".";
        if (eco().wallet(challenger.getUniqueId()) < stake) return "Nie masz tyle w portfelu.";
        if (eco().wallet(opponent.getUniqueId()) < stake) return opponent.getName() + " nie ma tyle w portfelu.";
        if (duelFor(opponent.getUniqueId()) != null) return opponent.getName() + " ma już inne wyzwanie.";
        Duel d = new Duel(challenger.getUniqueId(), opponent.getUniqueId(), stake, System.currentTimeMillis() + DUEL_WAIT_MS);
        duels.put(opponent.getUniqueId(), d);
        long pot = stake * 2;
        T.send(opponent, "<gold>" + T.esc(challenger.getName()) + "</gold> wyzywa Cię na pojedynek! Stawka <gold>" + Fmt.money(stake) + "</gold> od każdego, wygrany dostaje <gold>"
                + Fmt.money(pot - (long) Math.ceil(pot * DUEL_FEE)) + "</gold>. <click:run_command:'/kasyno duel accept'><green><bold>[PRZYJMIJ]</bold></green></click> <click:run_command:'/kasyno duel decline'><red><bold>[ODMÓW]</bold></red></click>");
        d.task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (duels.remove(opponent.getUniqueId(), d)) {
                Player c = Bukkit.getPlayer(d.challenger);
                if (c != null) T.send(c, "<gray>Wyzwanie dla " + T.esc(opponent.getName()) + " wygasło.");
            }
        }, DUEL_WAIT_MS / 50);
        return null;
    }

    public String accept(Player opponent) {
        Duel d = duelFor(opponent.getUniqueId());
        if (d == null) return "Nie masz żadnego wyzwania.";
        duels.remove(opponent.getUniqueId());
        if (d.task != null) d.task.cancel();
        Player challenger = Bukkit.getPlayer(d.challenger);
        if (challenger == null) return "Wyzywający już nie jest online.";
        // obie stawki pobieramy dopiero teraz; jeśli komuś zabrakło, pojedynek jest anulowany
        if (!eco().take(d.challenger, d.stake)) return "Wyzywający nie ma już tylu monet.";
        if (!eco().take(d.opponent, d.stake)) {
            eco().add(d.challenger, d.stake);
            return "Nie masz już tylu monet w portfelu.";
        }
        boolean challengerWins = eco().chance(0.5);
        UUID winner = challengerWins ? d.challenger : d.opponent;
        UUID loser = challengerWins ? d.opponent : d.challenger;
        long pot = d.stake * 2;
        long payout = pot - (long) Math.ceil(pot * DUEL_FEE);
        eco().add(winner, payout);
        eco().recordGame(winner, payout - d.stake);
        eco().recordGame(loser, -d.stake);
        Eco.LevelUp lv = eco().addXp(winner, 15);
        eco().addXp(loser, 5);
        Player w = Bukkit.getPlayer(winner);
        Player lp = Bukkit.getPlayer(loser);
        DuelGui.Result result = new DuelGui.Result(challenger.getUniqueId(), opponent.getUniqueId(), winner, payout, d.stake);
        // komunikaty po animacji
        Runnable announce = () -> {
            if (w != null) {
                T.send(w, "<green><bold>Wygrywasz pojedynek!</bold> <gold>" + Fmt.money(payout) + "</gold> <gray>(po prowizji 3%)");
                pl.kasyno.gui.Notify.levelUp(w, lv);
                Fx.win(w);
            }
            if (lp != null) {
                T.send(lp, "<red>Przegrywasz pojedynek. Tracisz <gold>" + Fmt.money(d.stake) + "</gold>.");
                Fx.lose(lp);
            }
        };
        // pieniądze są już rozliczone: scena na arenie w świecie tylko odgrywa wynik, a bez areny (albo gdy zajęta) gra się okno
        if (plugin.show().arenaReady() && RoomScenes.duelWorld(plugin, result, announce)) return null;
        plugin.guis().next(() -> {
            new DuelGui(plugin, challenger, result).open();
            new DuelGui(plugin, opponent, result).open();
        });
        Bukkit.getScheduler().runTaskLater(plugin, announce, DuelGui.DURATION + 10L);
        return null;
    }

    public String decline(Player opponent) {
        Duel d = duelFor(opponent.getUniqueId());
        if (d == null) return "Nie masz żadnego wyzwania.";
        duels.remove(opponent.getUniqueId());
        if (d.task != null) d.task.cancel();
        Player c = Bukkit.getPlayer(d.challenger);
        if (c != null) T.send(c, "<gray>" + T.esc(opponent.getName()) + " nie przyjął wyzwania.");
        return null;
    }
}
