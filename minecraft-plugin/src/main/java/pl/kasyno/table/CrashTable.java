package pl.kasyno.table;

import org.bukkit.Sound;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Games;
import pl.kasyno.gui.Fx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Crash na żywo: wszyscy stawiają przed startem, mnożnik rośnie naraz dla wszystkich, a każdy wypłaca się sam (albo ustawia
 * auto-wypłatę). Punkt crashu losuje się na starcie i jest tajny do końca rundy. Rozkład jest uczciwy: auto-wypłata przy x wygrywa
 * z szansą 97% / x, więc zwrot to 97%.
 *
 * <p>Kto zamknie okno w trakcie rundy bez auto-wypłaty, wypłaca się przy aktualnym mnożniku. Kto zamknie okno przed startem bez
 * auto-wypłaty, dostaje stawkę z powrotem (nie mógłby wypłacić, więc nie gra).
 */
public final class CrashTable extends Table {
    public static final double[] AUTO = {0, 1.5, 2, 3, 5, 10};
    public static final int HISTORY = 8;
    private static final int SAMPLE_TICKS = 9;

    private double crashAt;
    private double mult = 1;
    private long lastSample;
    private final List<Double> trail = new ArrayList<>();

    public CrashTable(KasynoPlugin plugin) {
        super(plugin, "crash", "Crash", 15 * 20, 6 * 20);
    }

    public double mult() {
        return mult;
    }

    /** Punkt crashu znamy dopiero po rundzie (w trakcie to sekret, więc zwraca 0). */
    public double crashAt() {
        return phase == Phase.RESULT ? crashAt : 0;
    }

    public List<Double> trail() {
        return trail;
    }

    public List<Double> history() {
        return plugin.store().global().crashHistory;
    }

    public static String x(double m) {
        return String.format(Locale.ROOT, "x%.2f", m);
    }

    @Override
    protected String validate(UUID player, String pick, long stake, double auto) {
        Seat s = seats.get(player);
        if (s != null && !s.bets.isEmpty()) return "Masz już zakład w tej rundzie. Możesz go cofnąć przed startem.";
        if (auto != 0 && (auto < 1.1 || auto > Games.CRASH_MAX)) return "Auto-wypłata: od x1,1 do x10 000 (0 = ręcznie).";
        return null;
    }

    @Override
    protected void begin() {
        // kto nie ma okna ani auto-wypłaty, nie może się wypłacić, więc jego stawka wraca przed startem
        for (Seat s : new ArrayList<>(seats.values())) {
            if (s.bets.get(0).auto == 0 && !viewing(s.id)) {
                long back = eco().refundHeld(s.id, id);
                seats.remove(s.id);
                tell(s.id, "<yellow>Nie było Cię przy stole Crash w chwili startu, a nie ustawiłeś auto-wypłaty. Stawka <gold>" + pl.kasyno.core.Fmt.money(back) + "</gold> wróciła do portfela.");
            }
        }
        if (seats.isEmpty()) {
            reset();
            return;
        }
        crashAt = Games.crashPoint(eco().rng);
        mult = 1;
        trail.clear();
        lastSample = now;
        startRunning();
        eachViewer(p -> Fx.sound(p, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f));
    }

    @Override
    protected void run() {
        double m = Games.crashAt((now - phaseStart) / 20.0);
        // auto-wypłata wygrywa, gdy cel nie przekracza punktu crashu (tak liczy się 97% / x)
        for (Seat s : seats.values()) {
            if (s.settled()) continue;
            Bet b = s.bets.get(0);
            if (b.auto > 0 && b.auto <= m && b.auto <= crashAt) cash(s, b, b.auto);
        }
        if (m >= crashAt) {
            crash();
            return;
        }
        mult = m;
        if (now - lastSample >= SAMPLE_TICKS) {
            lastSample = now;
            trail.add(m);
            if (trail.size() > 30) trail.remove(0);
            float pitch = (float) Math.min(2.0, 0.7 + Math.log(Math.max(1, m)) * 0.5);
            eachViewer(p -> Fx.tick(p, pitch));
        }
        touch();
    }

    private void cash(Seat s, Bet b, double at) {
        b.cashedAt = at;
        settle(s, (long) Math.floor(b.stake * at));
        deliver(s, "Wypłacasz przy " + x(at).replace('.', ',') + "!");
    }

    private void crash() {
        mult = crashAt;
        List<Double> h = history();
        h.add(0, crashAt);
        while (h.size() > HISTORY) h.remove(h.size() - 1);
        plugin.store().touchGlobal();
        for (Seat s : seats.values()) if (!s.settled()) settle(s, 0);
        eachViewer(p -> Fx.sound(p, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1f));
        finishRound();
    }

    @Override
    protected void summary(Seat seat) {
        Bet b = seat.bets.get(0);
        if (b.cashedAt > 0) tell(seat.id, "<gray>Crash nastąpił dopiero przy <white>" + x(crashAt).replace('.', ',') + "</white>.");
        else deliver(seat, "CRASH przy " + x(crashAt).replace('.', ',') + "! Nie zdążyłeś wypłacić.");
    }

    /** Ręczna wypłata w trakcie rundy. Zwraca komunikat błędu albo null. */
    public String cashOut(UUID player) {
        if (phase != Phase.RUNNING) return "Teraz nie ma czego wypłacać.";
        Seat s = seats.get(player);
        if (s == null || s.settled()) return "Nie masz aktywnego zakładu.";
        if (mult < 1.01) return "Poczekaj, aż mnożnik ruszy.";
        cash(s, s.bets.get(0), mult);
        return null;
    }

    @Override
    protected void viewClosed(TableView v) {
        if (phase != Phase.RUNNING) return;
        Seat s = seats.get(v.player().getUniqueId());
        if (s == null || s.settled() || s.bets.get(0).auto > 0) return;
        cash(s, s.bets.get(0), mult);
        tell(s.id, "<gray>Zamknąłeś stół w trakcie rundy, więc wypłata nastąpiła automatycznie.");
    }
}
