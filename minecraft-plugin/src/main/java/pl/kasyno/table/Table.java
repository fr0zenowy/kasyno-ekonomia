package pl.kasyno.table;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Notify;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Wspólny stół: jedna runda dla wszystkich graczy naraz. Pierwszy zakład otwiera odliczanie (WAITING → BETTING), potem losuje się
 * wynik (RUNNING), pokazuje się go wszystkim (RESULT) i stół wraca do oczekiwania. Zegar stołu liczy tiki, a nie czas ścienny,
 * więc runda wygląda tak samo u każdego gracza i da się ją testować.
 *
 * <p>Pieniądze: stawka zatrzymuje się przy stole (Eco.hold), a rozliczenie (Eco.settleHeld) dzieje się dokładnie raz na gracza.
 * Gry bez interakcji w trakcie rundy (ruletka, koło, wyścigi) rozliczają się w chwili losowania, a animacja jest tylko pokazem.
 * Zamknięcie okna niczego nie odbiera: zakład zostaje przy stole, a wynik przychodzi na czat.
 */
public abstract class Table {
    public enum Phase { WAITING, BETTING, RUNNING, RESULT }

    /** Co ile ticków pracuje pętla stołów. */
    public static final int STEP = 2;

    /** Pojedynczy zakład: na co (pick) i ile. Crash dodatkowo pamięta auto-wypłatę i mnożnik wypłaty. */
    public static final class Bet {
        public final String pick;
        public long stake;
        public double auto;
        public double cashedAt;

        Bet(String pick, long stake) {
            this.pick = pick;
            this.stake = stake;
        }
    }

    /** Miejsce gracza przy stole w bieżącej rundzie. */
    public static final class Seat {
        public final UUID id;
        public final String name;
        public final List<Bet> bets = new ArrayList<>();
        public Eco.Settle result;
        /** Saldo sprzed rozliczenia: ekran nie zdradza wyniku, zanim skończy się animacja. */
        public long walletBefore = -1;

        Seat(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        public long stake() {
            long s = 0;
            for (Bet b : bets) s += b.stake;
            return s;
        }

        public boolean settled() {
            return result != null;
        }

        public Bet bet(String pick) {
            for (Bet b : bets) if (b.pick.equals(pick)) return b;
            return null;
        }
    }

    protected final KasynoPlugin plugin;
    public final String id;
    public final String name;
    private final int betTicks;
    private final int resultTicks;

    protected Phase phase = Phase.WAITING;
    /** Zegar stołu w tikach (rośnie tylko, gdy ktoś patrzy albo runda trwa). */
    protected long now;
    protected long phaseStart;
    protected long phaseEnd;
    protected int round;
    private long version;
    private long lastSecond = -1;
    protected final Map<UUID, Seat> seats = new LinkedHashMap<>();
    private final Set<TableView> views = new LinkedHashSet<>();

    protected Table(KasynoPlugin plugin, String id, String name, int betTicks, int resultTicks) {
        this.plugin = plugin;
        this.id = id;
        this.name = name;
        this.betTicks = betTicks;
        this.resultTicks = resultTicks;
    }

    protected Eco eco() {
        return plugin.eco();
    }

    // ---------- odczyt stanu (dla widoków) ----------

    public Phase phase() {
        return phase;
    }

    public long version() {
        return version;
    }

    public int round() {
        return round;
    }

    public Map<UUID, Seat> seats() {
        return seats;
    }

    public Seat seat(UUID id) {
        return seats.get(id);
    }

    /** Ticki od początku bieżącej fazy. */
    public long elapsed() {
        return now - phaseStart;
    }

    public long secondsLeft() {
        return Math.max(0, (phaseEnd - now + 19) / 20);
    }

    public int betSeconds() {
        return betTicks / 20;
    }

    public boolean open() {
        return phase == Phase.WAITING || phase == Phase.BETTING;
    }

    public long totalStake() {
        long s = 0;
        for (Seat seat : seats.values()) s += seat.stake();
        return s;
    }

    public boolean viewing(UUID player) {
        for (TableView v : views) if (v.player().getUniqueId().equals(player)) return true;
        return false;
    }

    protected void touch() {
        version++;
    }

    // ---------- widzowie ----------

    void attach(TableView v) {
        views.add(v);
        // pierwszy widz budzi pętlę; stan jest od razu aktualny
        touch();
    }

    void detach(TableView v) {
        views.remove(v);
    }

    /** Okno gracza się zamknęło (np. wyjście, rozłączenie). Stoły z interakcją w trakcie rundy mogą na to zareagować. */
    protected void viewClosed(TableView v) {}

    /** Wykonuje akcję dla każdego gracza, który ma teraz otwarty ten stół (dźwięki rundy). */
    protected final void eachViewer(java.util.function.Consumer<Player> action) {
        for (TableView v : new ArrayList<>(views)) action.accept(v.player());
    }

    private void pushViews() {
        for (TableView v : new ArrayList<>(views)) v.refreshIfChanged();
    }

    // ---------- zakłady ----------

    /** Sprawdza zakład przed pobraniem stawki. Zwraca komunikat błędu albo null. */
    protected abstract String validate(UUID player, String pick, long stake, double auto);

    /** Dodaje zakład do miejsca gracza (domyślnie: ten sam typ zakładu zwiększa stawkę). */
    protected void place(Seat seat, String pick, long stake, double auto) {
        Bet b = seat.bet(pick);
        if (b == null) {
            b = new Bet(pick, stake);
            b.auto = auto;
            seat.bets.add(b);
        } else b.stake += stake;
    }

    /** Stawia zakład. Zwraca komunikat błędu albo null, gdy się udało. */
    public final String bet(Player p, String pick, long stake, double auto) {
        if (!open()) return "Runda już trwa. Poczekaj na następną.";
        UUID uid = p.getUniqueId();
        String bad = validate(uid, pick, stake, auto);
        if (bad != null) return bad;
        Eco.BetError e = eco().hold(uid, id, stake);
        if (e != Eco.BetError.NONE) {
            return switch (e) {
                case MIN -> "Minimalna stawka to " + Fmt.money(Eco.MIN_BET) + ".";
                case MAX -> "Maksymalna stawka to " + Fmt.money(Eco.MAX_BET) + ".";
                case POOR -> "Masz tylko " + Fmt.money(eco().wallet(uid)) + " w portfelu. Pieniądze z banku wypłacisz w menu banku.";
                default -> "Nie udało się pobrać stawki.";
            };
        }
        Seat seat = seats.computeIfAbsent(uid, k -> new Seat(uid, p.getName()));
        place(seat, pick, stake, auto);
        if (phase == Phase.WAITING) openBetting(p);
        touch();
        return null;
    }

    /** Cofa wszystkie zakłady gracza przed startem rundy. Zwraca zwróconą kwotę (0 = nie było czego cofać). */
    public final long cancel(UUID player) {
        if (phase != Phase.BETTING) return 0;
        Seat s = seats.remove(player);
        if (s == null) return 0;
        long back = eco().refundHeld(player, id);
        if (seats.isEmpty()) {
            phase = Phase.WAITING;
            lastSecond = -1;
        }
        touch();
        return back;
    }

    private void openBetting(Player first) {
        phase = Phase.BETTING;
        phaseStart = now;
        phaseEnd = now + betTicks;
        round++;
        lastSecond = -1;
        if (!plugin.getConfig().getBoolean("tables.announce", true)) return;
        String msg = "<yellow>" + T.esc(first.getName()) + " otwiera stół <gold>" + name + "</gold>! Start za " + betSeconds() + " s. "
                + "<click:run_command:'/kasyno " + id + "'><green><bold>[DOŁĄCZ]</bold></green></click>";
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(first) && o.hasPermission("kasyno.use") && !viewing(o.getUniqueId())) T.send(o, msg);
    }

    // ---------- pętla ----------

    /** Jeden krok stołu (co STEP ticków). Pusty stół bez widzów nic nie robi. */
    public final void update() {
        if (phase == Phase.WAITING && views.isEmpty()) return;
        now += STEP;
        switch (phase) {
            case BETTING -> {
                long left = secondsLeft();
                if (left != lastSecond) {
                    lastSecond = left;
                    touch();
                }
                if (now >= phaseEnd) start();
            }
            case RUNNING -> run();
            case RESULT -> {
                if (now >= phaseEnd) reset();
            }
            default -> {
            }
        }
        pushViews();
    }

    private void start() {
        if (seats.isEmpty()) {
            reset();
            return;
        }
        begin();
        if (seats.isEmpty() && phase == Phase.BETTING) reset();
    }

    /** Losuje wynik i ustawia phase = RUNNING (albo odwołuje rundę przez reset()). */
    protected abstract void begin();

    /** Jeden krok trwającej rundy; na końcu woła finishRound(). */
    protected abstract void run();

    protected final void startRunning() {
        phase = Phase.RUNNING;
        phaseStart = now;
        touch();
    }

    /** Runda się skończyła: pokazujemy wynik i wysyłamy graczom podsumowanie. */
    protected final void finishRound() {
        phase = Phase.RESULT;
        phaseStart = now;
        phaseEnd = now + resultTicks;
        touch();
        for (Seat s : seats.values()) summary(s);
    }

    /** Podsumowanie dla jednego gracza (czat, dźwięk). Wołane po zakończeniu rundy. */
    protected abstract void summary(Seat seat);

    protected void reset() {
        seats.clear();
        phase = Phase.WAITING;
        lastSecond = -1;
        touch();
    }

    /** Rozlicza miejsce gracza dokładnie raz. */
    protected final Eco.Settle settle(Seat seat, long payout) {
        if (phase == Phase.BETTING) seat.walletBefore = eco().wallet(seat.id);
        Eco.Settle r = eco().settleHeld(seat.id, id, payout, 3);
        seat.result = r;
        return r;
    }

    /** Wyłączenie serwera: stawki z nierozliczonych miejsc wracają do graczy. */
    public final void shutdown() {
        for (Seat s : new ArrayList<>(seats.values())) {
            if (s.settled()) continue;
            long back = eco().refundHeld(s.id, id);
            Player p = Bukkit.getPlayer(s.id);
            if (p != null && back > 0) T.send(p, "<yellow>Stół " + name + " zamknięty. Stawka <gold>" + Fmt.money(back) + "</gold> wróciła do portfela.");
        }
        seats.clear();
        phase = Phase.WAITING;
        lastSecond = -1;
        touch();
    }

    // ---------- komunikaty ----------

    protected final void tell(UUID player, String mm) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) T.send(p, mm);
    }

    /** Wynik gracza na czacie: wygrana albo przegrana, polisa, koniczyna, awans i efekty. */
    protected final void deliver(Seat seat, String headline) {
        Eco.Settle s = seat.result;
        if (s == null) return;
        Player p = Bukkit.getPlayer(seat.id);
        long bet = s.payout() - s.net();
        double mult = bet > 0 ? (double) s.payout() / bet : 0;
        if (p == null) return;
        String line;
        if (s.net() > 0) line = "<green>" + headline + " Wygrywasz <gold>" + Fmt.money(s.net()) + "</gold> <gray>(" + Fmt.mult(Math.round(mult * 100) / 100.0) + ")";
        else if (s.net() < 0) line = "<red>" + headline + " Tracisz <gold>" + Fmt.money(-s.net()) + "</gold>.";
        else line = "<yellow>" + headline + " Stawka wraca do Ciebie.";
        T.send(p, line);
        if (s.refunded().isPresent()) T.send(p, "<aqua>" + plugin.catalog().item(s.refunded().get().id()).name() + " zwróciła Ci stawkę!");
        if (s.bonus() > 0) T.send(p, "<gold>Bonus z koniczyny: +" + Fmt.money(s.bonus()));
        Notify.levelUp(p, s.lv());
        Fx.outcome(p, s.net(), mult);
        bigWin(p, s.net(), mult);
    }

    private void bigWin(Player winner, long net, double mult) {
        if (!plugin.getConfig().getBoolean("big-win-broadcast.enabled", true)) return;
        if (net < plugin.getConfig().getLong("big-win-broadcast.min-net", 100_000)) return;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(winner)) continue;
            T.send(o, "<gold>" + T.esc(winner.getName()) + " <yellow>wygrywa <green>" + Fmt.money(net) + "</green> <gray>(" + name + ", " + Fmt.mult(Math.round(mult * 100) / 100.0) + ")");
        }
    }
}
