package pl.kasyno.core;

import java.util.List;
import java.util.UUID;

/** Łowienie, kopanie, polowanie, las i śmietnik: narzędzie wybiera lepsze szanse na rzadkie łupy, a magnes mnoży ilość. */
public final class Gather {
    public enum Status { OK, NO_TOOL, COOLDOWN, MISS }

    public record Result(Status status, long left, Catalog.GatherTool tool, String item, int qty, boolean broke, Eco.LevelUp lv) {}

    private final Eco eco;
    private final Catalog cat;

    public Gather(Eco eco) {
        this.eco = eco;
        this.cat = eco.cat;
    }

    public static final List<String> KEYS = List.of("ryby", "kopalnia", "polowanie", "las", "smietnik");

    /** Najlepsze narzędzie, jakie gracz ma (wpis bez id to wariant darmowy). */
    public Catalog.GatherTool bestTool(UUID id, String key) {
        for (Catalog.GatherTool t : cat.gather.get(key).tools()) {
            if (t.id() == null || eco.qty(id, t.id()) > 0) return t;
        }
        return null;
    }

    public Result run(UUID id, String key) {
        Catalog.Gather g = cat.gather.get(key);
        Catalog.GatherTool tool = bestTool(id, key);
        if (tool == null) return new Result(Status.NO_TOOL, 0, null, null, 0, false, null);
        long left = eco.guardCooldown(id, key, g.cooldown());
        if (left > 0) return new Result(Status.COOLDOWN, left, tool, null, 0, false, null);

        String item = null;
        int qty = 0;
        Status status = Status.OK;
        double missChance = Math.max(g.missChance(), tool.miss());
        if (eco.chance(missChance)) {
            status = Status.MISS;
        } else {
            double luck = tool.luck() * eco.luckMultiplier(id);
            Catalog.LootEntry drop = draw(g.table(), luck);
            int lo = drop.qty()[0];
            int hi = drop.qty()[1];
            qty = (int) Math.round(eco.randInt(lo, hi) * eco.lootMultiplier(id));
            item = drop.id();
            eco.addItem(id, item, qty);
        }
        boolean broke = false;
        if (tool.id() != null && eco.chance(eco.breakChance(id))) {
            eco.takeItem(id, tool.id(), 1);
            broke = true;
        }
        Eco.LevelUp lv = eco.addXp(id, eco.randInt(6, 14));
        return new Result(status, 0, tool, item, qty, broke, lv);
    }

    /** Losowanie z wagami; `luck` mnoży wagę wpisów oznaczonych jako rzadkie. */
    public Catalog.LootEntry draw(List<Catalog.LootEntry> table, double luck) {
        double total = 0;
        for (Catalog.LootEntry e : table) total += e.rare() ? e.w() * luck : e.w();
        double r = eco.rng.nextDouble() * total;
        for (Catalog.LootEntry e : table) {
            r -= e.rare() ? e.w() * luck : e.w();
            if (r < 0) return e;
        }
        return table.get(table.size() - 1);
    }
}
