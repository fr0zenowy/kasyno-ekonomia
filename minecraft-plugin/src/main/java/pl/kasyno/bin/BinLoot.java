package pl.kasyno.bin;

import pl.kasyno.core.Catalog;

import java.util.random.RandomGenerator;

/**
 * Łup ze śmietnika: ta sama tabela co przy zbieraniu ze śmietnika w menu (puszki, butelki, a czasem telefon, zegarek, obrączka
 * albo skrzynka), ale jakość zależy od rodzaju śmietnika. Szczęście mnoży wagę rzadkich wpisów, ilość dotyczy tylko rzeczy
 * sypanych garściami (puszki, butelki), a pojedyncze skarby zawsze są jedną sztuką, żeby skrzynek nie dało się mnożyć.
 */
public final class BinLoot {
    private BinLoot() {}

    /** Wylosowany przedmiot i ilość. */
    public record Drop(String item, int qty) {}

    /** Jedno przeszukanie: null, gdy nic nie wyszło. `luckMult` i `lootMult` to mnożniki gracza (eliksiry, zwierzaki). */
    public static Drop roll(Catalog.Gather g, BinType t, double luckMult, double lootMult, RandomGenerator rng) {
        if (rng.nextDouble() < t.miss()) return null;
        double luck = t.luck() * luckMult;
        double total = 0;
        for (Catalog.LootEntry e : g.table()) total += e.rare() ? e.w() * luck : e.w();
        double r = rng.nextDouble() * total;
        Catalog.LootEntry pick = g.table().get(g.table().size() - 1);
        for (Catalog.LootEntry e : g.table()) {
            r -= e.rare() ? e.w() * luck : e.w();
            if (r < 0) {
                pick = e;
                break;
            }
        }
        int lo = pick.qty()[0];
        int hi = pick.qty()[1];
        int base = lo + rng.nextInt(hi - lo + 1);
        int qty = hi > 1 ? (int) Math.max(1, Math.round(base * lootMult * t.qty())) : Math.max(1, (int) Math.round(base * lootMult));
        return new Drop(pick.id(), qty);
    }
}
