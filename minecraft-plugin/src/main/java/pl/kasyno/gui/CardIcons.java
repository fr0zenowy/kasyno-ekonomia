package pl.kasyno.gui;

/** Nazwy ikon kart z paczki zasobów (card_s_a, card_h_10, ..., card_back). Gracz bez paczki zobaczy zwykły papier z nazwą karty. */
public final class CardIcons {
    private CardIcons() {}

    /** Rewers: karta zakryta. */
    public static final String BACK = "card_back";

    /** Kolory w kolejności numerów używanych w grach: 0 pik, 1 kier, 2 karo, 3 trefl. */
    private static final String SUITS = "shdc";

    /** Ikona karty: figura 1 albo 14 = as, 2-10, 11-13 = walet, dama, król; kolor 0-3. Poza zakresem zwraca null (zwykły przedmiot). */
    public static String of(int rank, int suit) {
        if (suit < 0 || suit >= SUITS.length()) return null;
        String r = switch (rank) {
            case 1, 14 -> "a";
            case 11 -> "j";
            case 12 -> "q";
            case 13 -> "k";
            default -> rank >= 2 && rank <= 10 ? String.valueOf(rank) : null;
        };
        return r == null ? null : "card_" + SUITS.charAt(suit) + "_" + r;
    }
}
