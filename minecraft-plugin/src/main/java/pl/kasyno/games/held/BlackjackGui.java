package pl.kasyno.games.held;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Games;
import pl.kasyno.games.HeldGameGui;
import pl.kasyno.gui.CardIcons;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Blackjack (oczko): ugraj 21 i pokonaj krupiera. Blackjack płaci 3:2, double na pierwszym ruchu, tarcza cofa dobranie, które by spaliło. */
public final class BlackjackGui extends HeldGameGui {
    private final List<Games.Card> deck = new ArrayList<>();
    private final List<Games.Card> player_ = new ArrayList<>();
    private final List<Games.Card> dealer = new ArrayList<>();
    private boolean started;
    private boolean firstMove = true;
    private boolean animating;
    private int dealerShown = 1;
    private boolean won;
    private String note = "";
    private long bet;
    private String verdict = "";

    public BlackjackGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "blackjack");
    }

    @Override
    protected Component title() {
        return T.c("<dark_green><bold>Blackjack");
    }

    private void newDeck() {
        deck.clear();
        for (int s = 0; s < 4; s++) for (int r = 1; r <= 13; r++) deck.add(new Games.Card(r, s));
        Collections.shuffle(deck, new java.util.Random(eco().rng.nextLong()));
    }

    private Games.Card pop() {
        return deck.remove(deck.size() - 1);
    }

    private ItemStack cardItem(Games.Card c) {
        return ItemBuilder.of(Material.PAPER).name((c.red() ? "<red>" : "<white>") + "<bold>" + c.label() + " " + c.suitSymbol()).icon(CardIcons.of(c.rank(), c.suit())).build();
    }

    private ItemStack hidden() {
        return ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).name("<dark_gray><bold>Zakryta karta").icon("card_back").build();
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler(Material.GREEN_STAINED_GLASS_PANE));
        if (!started) {
            set(22, ItemBuilder.of(Material.GREEN_CONCRETE).name("<green><bold>Stół blackjacka").lore("<gray>Zacznij grę, żeby rozdać karty.", "<gray>Blackjack płaci 3:2.").build());
        } else {
            boolean reveal = over || dealerShown > 1;
            set(1, ItemBuilder.of(Material.VILLAGER_SPAWN_EGG).name("<yellow><bold>Krupier").lore("<gray>Wartość: <white>" + (reveal ? Games.handValue(dealer.subList(0, Math.min(dealer.size(), dealerShown))) : Games.handValue(dealer.subList(0, 1)) + "+?")).build());
            for (int i = 0; i < dealer.size() && i < 7; i++) set(10 + i, i < dealerShown ? cardItem(dealer.get(i)) : hidden());
            set(28 - 9 + 0, ItemBuilder.filler(Material.GREEN_STAINED_GLASS_PANE));
            set(27, ItemBuilder.of(Material.PLAYER_HEAD).head(player).name("<yellow><bold>Ty").lore("<gray>Wartość: <white>" + Games.handValue(player_)).build());
            for (int i = 0; i < player_.size() && i < 7; i++) set(28 + i, cardItem(player_.get(i)));
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Stawka: <gold>" + Fmt.money(bet));
            if (!over) {
                var shield = eco().armed(id, "tarcza", bet);
                if (!shield.isEmpty()) lore.add("<aqua>" + plugin.catalog().item(shield.get(0).id()).name() + " <gray>(cofnie dobranie, które by spaliło)");
            }
            if (!note.isEmpty()) lore.add(note);
            if (!verdict.isEmpty()) lore.add(verdict);
            set(4, ItemBuilder.of(over && !animating ? (won ? Material.LIME_CONCRETE : Material.RED_CONCRETE) : Material.GOLD_BLOCK).name("<yellow><bold>Blackjack").lore(lore).build());
        }
        if (started && !over) {
            boolean canDouble = firstMove && eco().wallet(id) >= bet;
            set(38, ItemBuilder.of(Material.LIME_DYE).name("<green><bold>Dobierz").lore("<gray>Weź kolejną kartę").build(), t -> hit());
            set(40, ItemBuilder.of(Material.ORANGE_DYE).name("<gold><bold>Pas").lore("<gray>Krupier dobiera do 17").build(), t -> stand());
            set(42, ItemBuilder.of(Material.GOLD_NUGGET).name(canDouble ? "<aqua><bold>Podwój" : "<gray>Podwój")
                    .lore(canDouble ? List.of("<gray>Podwajasz stawkę i dostajesz", "<gray>jedną kartę.") : List.of("<red>Tylko na pierwszym ruchu,", "<red>gdy masz dość monet.")).build(), canDouble ? t -> dbl() : null);
        }
        if (!started) {
            betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Rozdaj karty").glow().lore("<gray>Stawka: <gold>" + Fmt.money(stake)).build(), t -> start(),
                    ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Blackjack 3:2, wygrana 2:1.", "<gray>Krupier dobiera do 17.", "<gray>Zwrot ok. 99% (strategia podstawowa).").build());
        } else if (over && !animating) {
            betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Zagraj ponownie").build(), t -> reopen(), null);
        } else {
            betBar(ItemBuilder.of(Material.GRAY_DYE).name("<gray>Gra trwa").build(), null, null);
        }
    }

    private void start() {
        if (started || !takeStake()) return;
        started = true;
        busy = true;
        bet = stake;
        newDeck();
        player_.add(pop());
        player_.add(pop());
        dealer.add(pop());
        dealer.add(pop());
        dealerShown = 1;
        touch();
        Fx.sound(player, org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        if (Games.isBlackjack(player_) || Games.isBlackjack(dealer)) {
            if (Games.isBlackjack(player_) && Games.isBlackjack(dealer)) resolve(bet, "Obaj macie blackjacka: remis!", false);
            else if (Games.isBlackjack(player_)) resolve((long) Math.floor(bet * 2.5), "BLACKJACK! Wypłata 3:2.", true);
            else resolve(0, "Krupier ma blackjacka.", false);
            return;
        }
        redraw();
    }

    private void hit() {
        if (!started || over || animating) return;
        touch();
        firstMove = false;
        note = "";
        player_.add(pop());
        int v = Games.handValue(player_);
        if (v > 21) {
            var shield = eco().consume(id, "tarcza", bet);
            if (shield.isPresent()) {
                Games.Card back = player_.remove(player_.size() - 1);
                deck.add(eco().rng.nextInt(deck.size() + 1), back);
                note = "<aqua>" + plugin.catalog().item(shield.get().id()).name() + " anulowała dobranie, które by Cię spaliło.";
                T.send(player, note);
                Fx.sound(player, org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
                redraw();
                return;
            }
            dealerShown = dealer.size();
            resolve(0, "Przekroczyłeś 21 (" + v + ")!", false);
            return;
        }
        Fx.tick(player, 1.2f);
        if (v == 21) stand();
        else redraw();
    }

    private void dbl() {
        if (!started || over || animating || !firstMove) return;
        if (!eco().increaseBet(id, bet)) {
            Fx.deny(player);
            return;
        }
        touch();
        bet *= 2;
        firstMove = false;
        player_.add(pop());
        int v = Games.handValue(player_);
        if (v > 21) {
            var shield = eco().consume(id, "tarcza", bet);
            if (shield.isPresent()) {
                Games.Card back = player_.remove(player_.size() - 1);
                deck.add(eco().rng.nextInt(deck.size() + 1), back);
                T.send(player, "<aqua>" + plugin.catalog().item(shield.get().id()).name() + " cofnęła kartę po podwojeniu. Krupier gra od razu.");
                stand();
                return;
            }
            dealerShown = dealer.size();
            resolve(0, "Podwoiłeś i przekroczyłeś 21 (" + v + ")!", false);
            return;
        }
        stand();
    }

    private void stand() {
        if (!started || over || animating) return;
        touch();
        int before = dealer.size();
        while (Games.handValue(dealer) < 17) dealer.add(pop());
        int p = Games.handValue(player_);
        int d = Games.handValue(dealer);
        long payout;
        String text;
        if (d > 21) {
            payout = bet * 2;
            text = "Krupier przekroczył 21 (" + d + ")!";
        } else if (p > d) {
            payout = bet * 2;
            text = "Masz " + p + " przeciw " + d + ".";
        } else if (p == d) {
            payout = bet;
            text = "Remis: " + p + " do " + d + ".";
        } else {
            payout = 0;
            text = "Krupier ma " + d + ", a Ty " + p + ".";
        }
        boolean win = payout > bet;
        // wynik jest już rozliczony; krupier odkrywa karty po kolei jako pokaz
        Eco.Settle s = finishQuiet(payout);
        won = win;
        verdict = "<white>" + text;
        animating = true;
        deferAnnounce(() -> announce(s, lastBet, text));
        int total = dealer.size();
        dealerShown = 2;
        redraw();
        int[] delays = even(Math.max(1, total - 1), 10);
        sequence(delays, k -> {
            dealerShown = Math.min(total, 3 + k);
            Fx.tick(player, 1.0f + 0.1f * k);
            redraw();
        }, () -> {
            dealerShown = total;
            animating = false;
            runAnnounce();
            redraw();
        });
        if (before == total) dealerShown = total;
    }

    /** Kończy rundę od razu (blackjack przy rozdaniu albo spalenie), bez animacji krupiera. */
    private void resolve(long payout, String text, boolean win) {
        dealerShown = dealer.size();
        Eco.Settle s = finishQuiet(payout);
        won = win || payout > lastBet;
        verdict = "<white>" + text;
        announce(s, lastBet, text);
        redraw();
    }

    @Override
    protected void abandon(boolean idle) {
        if (!started || over) return;
        // Gra w toku: poddanie się oddaje tylko część. Bezpiecznie rozgrywamy rękę jak „pas”, żeby nie dało się uciec od przegranej.
        int before = dealer.size();
        while (Games.handValue(dealer) < 17) dealer.add(pop());
        int p = Games.handValue(player_);
        int d = Games.handValue(dealer);
        long payout = d > 21 || p > d ? bet * 2 : p == d ? bet : 0;
        won = payout > bet;
        Eco.Settle s = finishQuiet(payout);
        dealerShown = dealer.size();
        announce(s, lastBet, "Koniec gry (pas automatyczny): " + p + " do " + d + ".");
        if (before < 0) note = "";
    }
}
