package pl.kasyno.games;

import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.T;

/**
 * Gry, w których stawka jest „zatrzymana” do końca (miny, kurczak, wieża, hi-lo, blackjack, crash). Zamknięcie okna albo
 * brak aktywności przez minutę kończy grę: gdy gracz coś już ugrał, wypłaca się to, w przeciwnym razie stawka wraca.
 */
public abstract class HeldGameGui extends GameGui {
    private static final long IDLE_MS = 60_000;

    /** Gra się zakończyła (wygrana, przegrana, wypłata). */
    protected boolean over;
    private long lastAction = System.currentTimeMillis();

    protected HeldGameGui(KasynoPlugin plugin, Player player, String gameId) {
        super(plugin, player, gameId);
    }

    /** Wołane, gdy gracz coś kliknie: przedłuża czas bezczynności. */
    protected void touch() {
        lastAction = System.currentTimeMillis();
    }

    /**
     * Kończy trwającą grę poza zwykłym biegiem (zamknięcie okna, bezczynność, wyłączenie serwera). Musi rozliczyć stawkę:
     * wypłacić, co już ugrane, albo oddać stawkę, gdy nic się nie wydarzyło.
     */
    protected abstract void abandon(boolean idle);

    @Override
    public void open() {
        super.open();
        repeat(20, 20, () -> {
            if (busy && !over && System.currentTimeMillis() - lastAction > IDLE_MS) {
                T.send(player, "<yellow>Brak aktywności, gra została zakończona.");
                abandon(true);
                redraw();
            }
        });
    }

    @Override
    protected void onClose() {
        if (busy && !over) abandon(false);
        super.onClose();
    }

    /** Rozlicza i kończy grę. */
    protected Eco.Settle finishGame(long payout, String headline) {
        long bet = eco().activeStake(id);
        Eco.Settle s = eco().settle(id, bet, payout);
        over = true;
        busy = false;
        shownWallet = -1;
        announce(s, bet, headline);
        return s;
    }

    /** Rozlicza grę bez komunikatu na czacie (komunikat po animacji pokazuje wywołujący). */
    protected Eco.Settle finishQuiet(long payout) {
        long bet = eco().activeStake(id);
        Eco.Settle s = eco().settle(id, bet, payout);
        over = true;
        busy = false;
        shownWallet = -1;
        lastBet = bet;
        return s;
    }

    /** Stawka ostatnio rozliczonej gry (do komunikatów po animacji). */
    protected long lastBet;

    /** Oddaje stawkę (gra przerwana, zanim cokolwiek się stało). */
    protected void refundGame() {
        long back = eco().refund(id);
        over = true;
        busy = false;
        shownWallet = -1;
        if (back > 0) T.send(player, "<yellow>Gra anulowana. Stawka <gold>" + Fmt.money(back) + "</gold> wróciła do portfela.");
    }
}
