package pl.kasyno.games;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.Notify;
import pl.kasyno.gui.T;
import pl.kasyno.gui.screens.CasinoGui;
import pl.kasyno.gui.screens.HallGui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wspólna baza ekranów gier: pasek stawki (min, połowa, własna kwota, podwojenie, max), pobranie stawki,
 * rozliczenie z polisą, koniczyną i awansem oraz komunikaty z efektami. Dolny rząd (sloty 45-53) należy do tej klasy.
 */
public abstract class GameGui extends Gui {
    private static final Map<String, Long> LAST_STAKE = new HashMap<>();

    protected final String gameId;
    protected long stake;
    /** Trwa runda (animacja albo gra z przyciskami): pasek stawki jest zablokowany. */
    protected boolean busy;
    /** Saldo „sprzed rundy”, żeby ekran nie zdradzał wyniku przed końcem animacji. -1 = pokazuj aktualne. */
    protected long shownWallet = -1;
    private Runnable pendingAnnounce;

    protected GameGui(KasynoPlugin plugin, Player player, String gameId) {
        super(plugin, player);
        this.gameId = gameId;
        this.stake = Math.max(Eco.MIN_BET, Math.min(Eco.MAX_BET, LAST_STAKE.getOrDefault(id + ":" + gameId, 100L)));
    }

    // ---------- stawka ----------

    /** Ustawia stawkę, z jaką gracz otworzy grę (komenda w rodzaju /moneta 500). */
    public static void presetStake(java.util.UUID player, String gameId, long stake) {
        LAST_STAKE.put(player + ":" + gameId, Math.max(Eco.MIN_BET, Math.min(Eco.MAX_BET, stake)));
    }

    protected void setStake(long s) {
        stake = Math.max(Eco.MIN_BET, Math.min(Eco.MAX_BET, s));
        LAST_STAKE.put(id + ":" + gameId, stake);
    }

    protected long wallet() {
        return busy && shownWallet >= 0 ? shownWallet : eco().wallet(id);
    }

    /** Rysuje dolny pasek: wróć, min, ½, stawka (klik = własna kwota), przycisk gry, ×2, max, info. */
    protected void betBar(ItemStack play, Click onPlay, ItemStack info) {
        fillRow(5, ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        // Wyjście jest zawsze możliwe: gry z animacją są już rozliczone, a gry z przyciskami rozliczają się przy zamknięciu okna.
        set(45, ItemBuilder.back(), t -> go(hallScreen()));
        set(46, ItemBuilder.of(Material.RED_DYE).name("<red>Minimum").lore("<gray>Stawka " + Fmt.money(Eco.MIN_BET)).icon("bet_min").build(), t -> adjust(Eco.MIN_BET));
        set(47, ItemBuilder.of(Material.ORANGE_DYE).name("<gold>Połowa").lore("<gray>Stawka ÷ 2").icon("bet_half").build(), t -> adjust(stake / 2));
        set(48, ItemBuilder.of(Material.GOLD_BLOCK).name("<gold><bold>Stawka: " + Fmt.money(stake))
                .lore("<gray>Portfel: <white>" + Fmt.money(wallet()), "", "<yellow>Kliknij, aby wpisać własną kwotę").icon("bet_stake").build(), t -> askStake());
        // przycisk główny: zielony „graj” dla szmaragdu, złota wypłata dla sztabki (własne ikony z paczki)
        set(49, play == null ? null : ItemBuilder.withIcon(play, switch (play.getType()) {
            case EMERALD -> "play";
            case GOLD_INGOT -> "cashout";
            default -> null;
        }), onPlay);
        set(50, ItemBuilder.of(Material.LIME_DYE).name("<green>Podwój").lore("<gray>Stawka × 2").icon("bet_double").build(), t -> adjust(stake * 2));
        set(51, ItemBuilder.of(Material.EMERALD).name("<aqua>Maksimum").lore("<gray>Cały portfel").icon("bet_max").build(), t -> adjust(Math.max(Eco.MIN_BET, eco().wallet(id))));
        if (info != null) set(53, ItemBuilder.withIcon(info, info.getType() == Material.BOOK ? "info" : null));
    }

    private void fillRow(int row, ItemStack item) {
        ItemStack skinned = skin(item);
        for (int c = 0; c < 9; c++) inv.setItem(row * 9 + c, skinned);
    }

    /** Sala, do której wraca przycisk „wróć” (a gdy gra nie jest w żadnej sali, hol kasyna). */
    private Gui hallScreen() {
        String hall = GameRegistry.hallOf(gameId);
        return hall == null ? new CasinoGui(plugin, player) : new HallGui(plugin, player, hall);
    }

    @Override
    protected String art() {
        return gameId;
    }

    private void adjust(long s) {
        if (busy) return;
        Fx.click(player);
        setStake(s);
        redraw();
    }

    private void askStake() {
        if (busy) return;
        plugin.prompts().ask(player, "<white>Podaj stawkę <gray>(np. 500, 2k, 50%, all)", text -> {
            long v = Fmt.parseAmount(text, eco().wallet(id));
            if (v < Eco.MIN_BET) T.send(player, "<red>Nie rozumiem stawki „" + T.esc(text) + "” albo jest poniżej minimum (" + Fmt.money(Eco.MIN_BET) + ").");
            else setStake(v);
            reopen();
        }, this::reopen);
    }

    /** Otwiera ten sam ekran od nowa (po wpisaniu stawki na czacie). */
    protected void reopen() {
        GameRegistry.Def def = GameRegistry.find(gameId);
        if (def != null) def.factory().apply(plugin, player).open();
    }

    /** Pobiera stawkę z portfela. Przy błędzie pokazuje komunikat i zwraca false. */
    protected boolean takeStake() {
        shownWallet = eco().wallet(id);
        Eco.BetError e = eco().takeBet(id, stake, gameId);
        if (e == Eco.BetError.NONE) return true;
        shownWallet = -1;
        Fx.deny(player);
        T.send(player, switch (e) {
            case MIN -> "<red>Minimalna stawka to <gold>" + Fmt.money(Eco.MIN_BET) + "</gold>.";
            case MAX -> "<red>Maksymalna stawka to <gold>" + Fmt.money(Eco.MAX_BET) + "</gold>.";
            case POOR -> "<red>Masz tylko <gold>" + Fmt.money(eco().wallet(id)) + "</gold> w portfelu. Pieniądze z banku wypłacisz w menu banku.";
            case BUSY -> "<red>Masz już rozpoczętą grę. Dokończ ją najpierw.";
            default -> "<red>Nie udało się pobrać stawki.";
        });
        return false;
    }

    // ---------- zapamiętane wybory i animacje klatkowe ----------

    private static final Map<String, String> CHOICES = new HashMap<>();

    /** Zapamiętany wybór gracza w tej grze (np. rodzaj zakładu), żeby przetrwał ponowne otwarcie okna. */
    protected String choice(String key, String def) {
        return CHOICES.getOrDefault(id + ":" + gameId + ":" + key, def);
    }

    protected void saveChoice(String key, String value) {
        CHOICES.put(id + ":" + gameId + ":" + key, value);
    }

    /** Odtwarza klatki: frame(i) po kolejnych opóźnieniach (w tickach), na końcu done. */
    protected void sequence(int[] delays, java.util.function.IntConsumer frame, Runnable done) {
        int[] i = {0};
        Runnable[] r = new Runnable[1];
        r[0] = () -> {
            if (i[0] < delays.length) {
                int k = i[0]++;
                frame.accept(k);
                later(delays[k], r[0]);
            } else done.run();
        };
        later(2, r[0]);
    }

    /** Stała liczba klatek co `every` ticków. */
    protected static int[] even(int frames, int every) {
        int[] d = new int[frames];
        java.util.Arrays.fill(d, every);
        return d;
    }

    /** Klatki zwalniające: zaczynają co `from` ticków, a kończą co `to`. */
    protected static int[] slowing(int frames, int from, int to) {
        int[] d = new int[frames];
        for (int i = 0; i < frames; i++) d[i] = (int) Math.round(from + (to - from) * Math.pow((double) i / Math.max(1, frames - 1), 2));
        return d;
    }

    /** Kończy rundę z animacją: odblokowuje stawkę, pokazuje saldo i komunikat. */
    protected void endRound() {
        busy = false;
        shownWallet = -1;
        runAnnounce();
        redraw();
    }

    // ---------- rozliczenie i komunikaty ----------

    /** Wynik gry jako ikona do pokazania w GUI. */
    protected ItemStack resultItem(Eco.Settle s, long bet, String headline, String... extra) {
        Material m = s.net() > 0 ? Material.LIME_CONCRETE : s.net() < 0 ? Material.RED_CONCRETE : Material.YELLOW_CONCRETE;
        List<String> lore = new ArrayList<>();
        lore.add(headline);
        lore.add("<gray>Stawka: <white>" + Fmt.money(bet));
        lore.add("<gray>Wypłata: <gold>" + Fmt.money(s.payout()));
        for (String e : extra) lore.add(e);
        if (s.refunded().isPresent()) lore.add("<aqua>" + plugin.catalog().item(s.refunded().get().id()).name() + " zwróciła stawkę!");
        if (s.bonus() > 0) lore.add("<gold>Bonus z koniczyny: +" + Fmt.money(s.bonus()));
        return ItemBuilder.of(m).name(s.net() > 0 ? "<green><bold>Wygrana " + Fmt.signed(s.net()) : s.net() < 0 ? "<red><bold>Przegrana " + Fmt.signed(s.net()) : "<yellow><bold>Remis").glow(s.net() > 0).lore(lore).build();
    }

    /** Komunikat na czacie, dźwięk i efekt zależnie od wyniku. */
    protected void announce(Eco.Settle s, long bet, String headline) {
        double mult = bet > 0 ? (double) s.payout() / bet : 0;
        String line;
        if (s.net() > 0) line = "<green>" + headline + " <green>Wygrywasz <gold>" + Fmt.money(s.net()) + "</gold> <gray>(" + Fmt.mult(Math.round(mult * 100) / 100.0) + ")";
        else if (s.net() < 0) line = "<red>" + headline + " <red>Tracisz <gold>" + Fmt.money(-s.net()) + "</gold>.";
        else line = "<yellow>" + headline + " <yellow>Stawka wraca do Ciebie.";
        T.send(player, line);
        if (s.refunded().isPresent()) T.send(player, "<aqua>" + plugin.catalog().item(s.refunded().get().id()).name() + " zwróciła Ci stawkę!");
        if (s.bonus() > 0) T.send(player, "<gold>Bonus z koniczyny: +" + Fmt.money(s.bonus()));
        Notify.levelUp(player, s.lv());
        Fx.outcome(player, s.net(), mult);
        bigWin(s.net(), mult);
    }

    /** Duże wygrane widać na czacie dla wszystkich (można wyłączyć w config.yml). */
    private void bigWin(long net, double mult) {
        if (!plugin.getConfig().getBoolean("big-win-broadcast.enabled", true)) return;
        if (net < plugin.getConfig().getLong("big-win-broadcast.min-net", 100_000)) return;
        GameRegistry.Def def = GameRegistry.find(gameId);
        String game = def == null ? gameId : def.name();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p.equals(player)) continue;
            T.send(p, "<gold>" + T.esc(player.getName()) + " <yellow>wygrywa <green>" + Fmt.money(net) + "</green> <gray>(" + game + ", " + Fmt.mult(Math.round(mult * 100) / 100.0) + ")");
        }
    }

    /** Odkłada komunikat o wyniku na koniec animacji; jeśli gracz zamknie okno wcześniej, pojawi się na czacie od razu. */
    protected void deferAnnounce(Runnable r) {
        pendingAnnounce = r;
    }

    protected void runAnnounce() {
        Runnable r = pendingAnnounce;
        pendingAnnounce = null;
        if (r != null) r.run();
    }

    @Override
    protected void onClose() {
        runAnnounce();
        shownWallet = -1;
    }

}
