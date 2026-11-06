package pl.kasyno.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Skrzynie z nagrodami: losowanie, wręczanie, wartość oczekiwana i szanse na rzadkości. */
public final class Crates {
    public record Rarity(String id, String name, String color, int rank) {}

    public static final Map<String, Rarity> RARITY = new LinkedHashMap<>();

    static {
        RARITY.put("common", new Rarity("common", "Zwykły", "gray", 0));
        RARITY.put("uncommon", new Rarity("uncommon", "Niezwykły", "green", 1));
        RARITY.put("rare", new Rarity("rare", "Rzadki", "blue", 2));
        RARITY.put("epic", new Rarity("epic", "Epicki", "dark_purple", 3));
        RARITY.put("legendary", new Rarity("legendary", "Legendarny", "gold", 4));
    }

    public static final int MAX_OPEN = 10;

    /** Wylosowana nagroda: money i xp mają amount, item ma item i qty. */
    public record Reward(String kind, String rarity, long amount, String item, int qty) {}

    private final Eco eco;
    private final Catalog cat;

    public Crates(Eco eco) {
        this.eco = eco;
        this.cat = eco.cat;
    }

    public boolean isCrate(String id) {
        return cat.crates.containsKey(id);
    }

    public Reward roll(String crateId) {
        List<Catalog.CrateEntry> loot = cat.crates.get(crateId).loot();
        double total = loot.stream().mapToDouble(Catalog.CrateEntry::w).sum();
        double r = eco.rng.nextDouble() * total;
        Catalog.CrateEntry e = loot.get(loot.size() - 1);
        for (Catalog.CrateEntry c : loot) {
            r -= c.w();
            if (r < 0) {
                e = c;
                break;
            }
        }
        if (e.kind().equals("item")) return new Reward("item", e.r(), 0, e.item(), e.qty());
        long step = e.max() >= 100_000 ? 1000 : e.max() >= 10_000 ? 100 : e.max() >= 1000 ? 10 : 1;
        long amount = Math.round(eco.randLong(e.min(), e.max()) / (double) step) * step;
        return new Reward(e.kind(), e.r(), amount, null, 0);
    }

    public record Applied(String text, Eco.LevelUp lv) {}

    /** Wręcza nagrodę. XP ze skrzyni jest już wycenione w tabeli, więc bez mnożników. */
    public Applied apply(UUID id, Reward r) {
        switch (r.kind()) {
            case "money" -> {
                eco.add(id, r.amount());
                return new Applied(Fmt.money(r.amount()), null);
            }
            case "xp" -> {
                Eco.LevelUp lv = eco.addXp(id, r.amount(), false);
                return new Applied("+" + Fmt.num(r.amount()) + " XP", lv);
            }
            default -> {
                eco.addItem(id, r.item(), r.qty());
                return new Applied(cat.item(r.item()).name() + " x" + r.qty(), null);
            }
        }
    }

    /** Średnia wartość jednego otwarcia (przedmioty po cenie skupu, skrzynie po własnej wartości). */
    public double expectedValue(String crateId) {
        List<Catalog.CrateEntry> loot = cat.crates.get(crateId).loot();
        double total = loot.stream().mapToDouble(Catalog.CrateEntry::w).sum();
        double ev = 0;
        for (Catalog.CrateEntry e : loot) {
            double value;
            if (e.kind().equals("money") || e.kind().equals("xp")) value = (e.min() + e.max()) / 2.0;
            else if (isCrate(e.item())) value = expectedValue(e.item()) * e.qty();
            else value = cat.item(e.item()).sellValue() * e.qty();
            ev += e.w() / total * value;
        }
        return ev;
    }

    /** Szansa (w %) na każdą rzadkość. */
    public Map<String, Double> rarityOdds(String crateId) {
        List<Catalog.CrateEntry> loot = cat.crates.get(crateId).loot();
        double total = loot.stream().mapToDouble(Catalog.CrateEntry::w).sum();
        Map<String, Double> odds = new LinkedHashMap<>();
        for (Catalog.CrateEntry e : loot) odds.merge(e.r(), e.w() / total * 100, Double::sum);
        return odds;
    }
}
