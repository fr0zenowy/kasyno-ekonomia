package pl.kasyno.games.held;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Games;
import pl.kasyno.games.HeldGameGui;
import pl.kasyno.gui.CardIcons;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.List;

/** Wyżej czy niżej: zgaduj kolejne karty i buduj serię. Remis (ta sama figura) to przegrana. Tarcza anuluje pierwszy zły typ. */
public final class HiloGui extends HeldGameGui {
    private record Card(int rank, int suit) {
        String label() {
            return switch (rank) {
                case 11 -> "J";
                case 12 -> "Q";
                case 13 -> "K";
                case 14 -> "A";
                default -> String.valueOf(rank);
            };
        }

        String suitSymbol() {
            return new String[]{"♠", "♥", "♦", "♣"}[suit];
        }

        boolean red() {
            return suit == 1 || suit == 2;
        }
    }

    private Card card;
    private double raw = 1;
    private int streak;
    private final List<Card> history = new ArrayList<>();
    private boolean started;
    private boolean won;
    private boolean flipping;
    private String note = "";
    private long bet;

    public HiloGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "hilo");
    }

    @Override
    protected Component title() {
        return T.c("<gold><bold>Wyżej czy niżej?");
    }

    private Card draw() {
        return new Card(eco().randInt(2, 14), eco().rng.nextInt(4));
    }

    private double currentMult() {
        return streak > 0 ? Games.hiloMult(raw) : 1;
    }

    private long payout() {
        return (long) Math.floor(bet * currentMult());
    }

    private ItemStack cardItem(Card c, boolean glow) {
        return ItemBuilder.of(Material.PAPER).name((c.red() ? "<red>" : "<white>") + "<bold>" + c.label() + " " + c.suitSymbol()).icon(CardIcons.of(c.rank(), c.suit())).glow(glow).build();
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        if (started) {
            set(22, cardItem(card, true));
            for (int i = 0; i < history.size() && i < 7; i++) set(10 + i, cardItem(history.get(history.size() - 1 - i), false));
        } else {
            set(22, ItemBuilder.of(Material.ENDER_PEARL).name("<gray>Zakryta karta").lore("<gray>Zacznij grę, aby ją odkryć").icon("card_back").build());
        }
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Seria: <white>" + streak);
        lore.add("<gray>Mnożnik: <gold>" + Fmt.mult(currentMult()));
        if (started && !over) {
            lore.add("<gray>Do wypłaty: <gold>" + Fmt.money(payout()));
            lore.add("<dark_gray>Ta sama figura = przegrana");
            var shield = eco().armed(id, "tarcza", bet);
            if (!shield.isEmpty()) lore.add("<aqua>" + plugin.catalog().item(shield.get(0).id()).name() + " <gray>(pierwszy błąd się nie liczy)");
        }
        if (!note.isEmpty()) lore.add(note);
        set(4, ItemBuilder.of(over ? (won ? Material.LIME_CONCRETE : Material.RED_CONCRETE) : Material.GOLD_BLOCK).name("<yellow><bold>Hi-Lo").lore(lore).build());

        if (started && !over) {
            double pHi = Games.pHigher(card.rank());
            double pLo = Games.pLower(card.rank());
            set(30, ItemBuilder.of(Material.LIME_DYE).name("<green><bold>Wyżej").glow(pHi > 0)
                    .lore(pHi > 0 ? "<gray>Szansa: <white>" + Math.round(pHi * 100) + "%" : "<red>Niemożliwe", pHi > 0 ? "<gray>Mnożnik: <gold>" + Fmt.mult(Games.hiloMult(raw / pHi)) : "").build(), pHi > 0 ? t -> guess(true) : null);
            set(32, ItemBuilder.of(Material.RED_DYE).name("<red><bold>Niżej").glow(pLo > 0)
                    .lore(pLo > 0 ? "<gray>Szansa: <white>" + Math.round(pLo * 100) + "%" : "<red>Niemożliwe", pLo > 0 ? "<gray>Mnożnik: <gold>" + Fmt.mult(Games.hiloMult(raw / pLo)) : "").build(), pLo > 0 ? t -> guess(false) : null);
        }
        if (!started) {
            betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Zacznij grę").glow().lore("<gray>Stawka: <gold>" + Fmt.money(stake)).build(), t -> start(),
                    ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Zgaduj, czy następna karta będzie", "<gray>wyższa czy niższa. As jest najwyższy.", "<gray>Zwrot ok. 95%.").build());
        } else if (!over) {
            betBar(ItemBuilder.of(Material.GOLD_INGOT).name(streak == 0 ? "<gray>Wypłata (zgadnij najpierw)" : "<gold><bold>Wypłać " + Fmt.money(payout())).glow(streak > 0).build(), t -> cash(), null);
        } else {
            betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Zagraj ponownie").build(), t -> reopen(), null);
        }
    }

    private void start() {
        if (started || !takeStake()) return;
        started = true;
        busy = true;
        bet = stake;
        card = draw();
        raw = 1;
        streak = 0;
        touch();
        Fx.sound(player, org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        redraw();
    }

    private void guess(boolean high) {
        if (!started || over || flipping) return;
        touch();
        flipping = true;
        Card next = draw();
        boolean win = high ? next.rank() > card.rank() : next.rank() < card.rank();
        double p = high ? Games.pHigher(card.rank()) : Games.pLower(card.rank());
        int[] delays = even(4, 3);
        sequence(delays, k -> {
            set(22, cardItem(new Card(eco().randInt(2, 14), eco().rng.nextInt(4)), false));
            Fx.tick(player, 0.8f + 0.1f * k);
        }, () -> {
            flipping = false;
            resolve(next, win, p);
        });
    }

    private void resolve(Card next, boolean win, double p) {
        note = "";
        if (!win) {
            var shield = eco().consume(id, "tarcza", bet);
            if (shield.isPresent()) {
                note = "<aqua>" + plugin.catalog().item(shield.get().id()).name() + " anulowała zły typ (wypadłoby " + next.label() + next.suitSymbol() + ").";
                T.send(player, note);
                Fx.sound(player, org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
                redraw();
                return;
            }
            history.add(card);
            card = next;
            won = false;
            Fx.sound(player, org.bukkit.Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
            finishGame(0, "Wypadło " + next.label() + next.suitSymbol() + ", pudło!");
            redraw();
            return;
        }
        history.add(card);
        raw /= p;
        streak++;
        card = next;
        Fx.ding(player, 0.8f + 0.06f * Math.min(streak, 15));
        if (currentMult() >= Games.MAX_MULT) {
            won = true;
            finishGame(payout(), "Osiągnąłeś limit x1000, automatyczna wypłata!");
        }
        redraw();
    }

    private void cash() {
        if (!started || over || flipping) return;
        if (streak == 0) {
            Fx.deny(player);
            return;
        }
        won = true;
        finishGame(payout(), "Wypłacasz wygraną!");
        redraw();
    }

    @Override
    protected void abandon(boolean idle) {
        if (!started || over) return;
        if (streak == 0) refundGame();
        else {
            won = true;
            finishGame(payout(), "Koniec gry: wygrana wypłacona automatycznie.");
        }
    }

}
