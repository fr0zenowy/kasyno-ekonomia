package pl.kasyno.games;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.DataStore;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.PlayerData;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Codzienna loteria: im więcej biletów, tym większa szansa na całą pulę (10% z każdej puli znika jako podatek). */
public final class Lottery {
    public static final long DAY_MS = 24L * 3600 * 1000;
    public static final long TICKET_PRICE = 100;
    public static final int MAX_TICKETS = 500;
    public static final double PAYOUT_RATE = 0.9;

    public record Pool(long tickets, int players, long pot) {}

    /** Losowanie w toku albo świeżo zakończone: uczestnicy, klatki podświetlenia (ostatnia stoi na zwycięzcy) i bieżąca klatka. */
    public static final class Scene {
        public final List<UUID> participants;
        public final List<String> names;
        public final int[] spin;
        public final String winner;
        public final long pot;
        public int step = -1;
        public boolean done;
        long endedMs;

        Scene(List<UUID> participants, List<String> names, int[] spin, String winner, long pot) {
            this.participants = participants;
            this.names = names;
            this.spin = spin;
            this.winner = winner;
            this.pot = pot;
        }

        /** Imię uczestnika, na którym stoi podświetlenie w danej klatce. */
        public String nameAt(int frame) {
            return names.get(spin[Math.max(0, Math.min(frame, spin.length - 1))]);
        }
    }

    private static final int FRAMES = 34;
    private final KasynoPlugin plugin;
    private Scene scene;

    /** Bieżące losowanie (do pokazania w oknie) albo null, gdy nic się nie dzieje. */
    public Scene scene() {
        if (scene != null && scene.done && System.currentTimeMillis() - scene.endedMs > 60_000) scene = null;
        return scene;
    }

    public Lottery(KasynoPlugin plugin) {
        this.plugin = plugin;
    }

    private DataStore.Global global() {
        return plugin.store().global();
    }

    public long nextDraw() {
        if (global().lotteryNext == 0) {
            global().lotteryNext = System.currentTimeMillis() + DAY_MS;
            plugin.store().touchGlobal();
        }
        return global().lotteryNext;
    }

    public Pool pool() {
        long tickets = 0;
        int players = 0;
        for (PlayerData d : plugin.store().all().values()) {
            if (d.lotteryTickets > 0) {
                tickets += d.lotteryTickets;
                players++;
            }
        }
        return new Pool(tickets, players, (long) Math.floor(tickets * TICKET_PRICE * PAYOUT_RATE));
    }

    /** Zwraca komunikat błędu albo null po udanym zakupie. */
    public String buy(UUID id, int qty) {
        PlayerData d = plugin.eco().pd(id);
        if (qty < 1) return "Podaj liczbę biletów.";
        if (d.lotteryTickets + qty > MAX_TICKETS) return "Możesz mieć maksymalnie " + MAX_TICKETS + " biletów (masz już " + d.lotteryTickets + ").";
        long cost = qty * TICKET_PRICE;
        if (!plugin.eco().take(id, cost)) return "Potrzebujesz " + Fmt.money(cost) + " w portfelu.";
        d.lotteryTickets += qty;
        plugin.store().touch(id);
        return null;
    }

    public void start() {
        nextDraw();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (System.currentTimeMillis() >= nextDraw()) draw();
        }, 20L * 30, 20L * 30);
    }

    /** Losuje zwycięzcę (szansa proporcjonalna do liczby biletów) i wypłaca całą pulę. */
    public void draw() {
        global().lotteryNext = System.currentTimeMillis() + DAY_MS;
        plugin.store().touchGlobal();
        Pool pool = pool();
        if (pool.tickets() == 0) return;
        double r = plugin.eco().rng.nextDouble() * pool.tickets();
        UUID winner = null;
        for (Map.Entry<UUID, PlayerData> e : plugin.store().all().entrySet()) {
            int t = e.getValue().lotteryTickets;
            if (t <= 0) continue;
            winner = e.getKey();
            r -= t;
            if (r < 0) break;
        }
        if (winner == null) return;
        // pieniądze od razu, a „losowanie” to scena (napisy nad paskiem życia, dźwięki, okno), która tylko odgrywa wynik
        List<UUID> holders = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Map.Entry<UUID, PlayerData> e : plugin.store().all().entrySet()) {
            if (e.getValue().lotteryTickets > 0) {
                holders.add(e.getKey());
                names.add(e.getValue().name);
            }
        }
        plugin.eco().add(winner, pool.pot());
        for (PlayerData d : plugin.store().all().values()) d.lotteryTickets = 0;
        for (UUID id : plugin.store().all().keySet()) plugin.store().touch(id);
        String name = plugin.eco().pd(winner).name;
        global().lotteryLastName = name;
        global().lotteryLastAmount = pool.pot();
        plugin.store().touchGlobal();
        play(winner, name, pool.pot(), names, holders);
    }

    private void play(UUID winner, String name, long pot, List<String> names, List<UUID> holders) {
        int[] spin = RoomScenes.spinFrames(holders.size(), holders.indexOf(winner));
        Scene sc = new Scene(new ArrayList<>(holders), new ArrayList<>(names), spin, name, pot);
        scene = sc;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Fx.title(p, "<gold><bold>LOTERIA", "<gray>Pula: <gold>" + Fmt.money(pot));
            Fx.actionBar(p, "<yellow>Losowanie loterii... komu trafi się pula?");
        }
        // kto brał udział, od razu widzi okno z losowaniem (reszta ma napisy nad paskiem życia)
        for (UUID id : holders) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) plugin.guis().next(() -> {
                if (p.isOnline()) new pl.kasyno.gui.screens.LotteryGui(plugin, p).open();
            });
        }
        int frames = spin.length;
        int t = 30;
        for (int k = 0; k < frames; k++) {
            final int frame = k;
            double q = k / (double) (frames - 1);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                sc.step = frame;
                float pitch = Math.max(0.6f, 0.8f + 0.8f * (float) Math.sin(Math.PI * Math.min(1, frame / (double) frames * 1.6)));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Fx.actionBar(p, "<gold>Loteria: <white><bold>" + T.esc(sc.nameAt(frame)));
                    if (holders.contains(p.getUniqueId())) Fx.tick(p, pitch);
                }
            }, t);
            t += Math.round(2 + 8 * q * q);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            sc.done = true;
            sc.endedMs = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                T.send(p, "<gold><bold>Losowanie loterii!</bold> <white>" + T.esc(name) + "</white> wygrywa <gold>" + Fmt.money(pot) + "</gold>. Nowa loteria już ruszyła!");
            }
            Player wp = Bukkit.getPlayer(winner);
            if (wp != null) {
                Fx.title(wp, "<gold><bold>WYGRYWASZ LOTERIĘ!", "<yellow>" + Fmt.money(pot));
                Fx.jackpot(wp);
            }
        }, t + 30L);
    }
}
