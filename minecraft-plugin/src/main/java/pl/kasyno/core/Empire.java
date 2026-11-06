package pl.kasyno.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Biznesy (dochód pasywny liczony od ostatniego zbioru, z limitem magazynu) i farma (uprawy dojrzewają w czasie).
 * Czas liczymy ze znaczników, więc nic nie musi działać w tle, a zarobki rosną także wtedy, gdy gracza nie ma.
 */
public final class Empire {
    public static final long HOUR = 3600L * 1000;

    private final Eco eco;
    private final Catalog cat;

    public Empire(Eco eco) {
        this.eco = eco;
        this.cat = eco.cat;
    }

    // ---------- biznesy ----------

    public long hourlyAt(Catalog.Business b, int level) {
        return b.hourly() * level;
    }

    /** Ulepszenie z poziomu L na L+1 kosztuje cena × L × 1.5. */
    public long upgradeCost(Catalog.Business b, int level) {
        return (long) Math.floor(b.price() * level * 1.5);
    }

    public int storageHours(UUID id) {
        return cat.storageHours + (int) eco.effectSum(id, "bizStorage");
    }

    public double incomeMult(UUID id) {
        return 1 + eco.effectSum(id, "bizIncome");
    }

    public record Part(Catalog.Business biz, int level, double hours, long amount, boolean full) {}

    public record Pending(List<Part> parts, long total, int cap, double mult) {}

    public Pending pending(UUID id, long now) {
        int cap = storageHours(id);
        double mult = incomeMult(id);
        List<Part> parts = new ArrayList<>();
        long total = 0;
        for (Catalog.Business b : cat.businesses) {
            PlayerData.Biz row = eco.pd(id).businesses.get(b.id());
            if (row == null) continue;
            double hours = Math.min(cap, Math.max(0, (now - row.collectedAt) / (double) HOUR));
            long amount = (long) Math.floor(hourlyAt(b, row.level) * mult * hours);
            parts.add(new Part(b, row.level, hours, amount, hours >= cap));
            total += amount;
        }
        return new Pending(parts, total, cap, mult);
    }

    /** Wypłaca zaległy dochód wszystkich biznesów i zeruje liczniki. */
    public Pending collect(UUID id, long now) {
        Pending p = pending(id, now);
        if (p.total() > 0) eco.add(id, p.total());
        eco.pd(id).businesses.values().forEach(b -> b.collectedAt = now);
        eco.store.touch(id);
        return p;
    }

    public record Buy(boolean ok, String reason, boolean bought, long cost, int level, long collected, Catalog.Business biz) {}

    /** Kupuje biznes albo ulepsza go o poziom (przy ulepszeniu wypłaca zaległy dochód po starej stawce). */
    public Buy buyOrUpgrade(UUID id, String bizId, long now) {
        Catalog.Business biz = cat.business(bizId);
        if (biz == null) return new Buy(false, "unknown", false, 0, 0, 0, null);
        PlayerData d = eco.pd(id);
        PlayerData.Biz row = d.businesses.get(bizId);
        if (row == null) {
            if (!eco.take(id, biz.price())) return new Buy(false, "poor", false, biz.price(), 0, 0, biz);
            d.businesses.put(bizId, new PlayerData.Biz(1, now));
            eco.store.touch(id);
            return new Buy(true, null, true, biz.price(), 1, 0, biz);
        }
        if (row.level >= cat.businessMaxLevel) return new Buy(false, "max", false, 0, row.level, 0, biz);
        long cost = upgradeCost(biz, row.level);
        if (!eco.take(id, cost)) return new Buy(false, "poor", false, cost, row.level, 0, biz);
        double hours = Math.min(storageHours(id), Math.max(0, (now - row.collectedAt) / (double) HOUR));
        long collected = (long) Math.floor(hourlyAt(biz, row.level) * incomeMult(id) * hours);
        if (collected > 0) eco.add(id, collected);
        row.level++;
        row.collectedAt = now;
        eco.store.touch(id);
        return new Buy(true, null, false, cost, row.level, collected, biz);
    }

    /** Dochód na godzinę wszystkich biznesów gracza (z mnożnikiem menedżera). */
    public long hourlyTotal(UUID id) {
        long sum = 0;
        for (Catalog.Business b : cat.businesses) {
            PlayerData.Biz row = eco.pd(id).businesses.get(b.id());
            if (row != null) sum += hourlyAt(b, row.level);
        }
        return (long) Math.floor(sum * incomeMult(id));
    }

    // ---------- farma ----------

    /** Czas wzrostu w ms po uwzględnieniu podlewaczki. */
    public long growMs(UUID id, Catalog.Crop crop) {
        return Math.max(1000, Math.round(crop.minutes() * 60_000.0 * (1 - eco.effectSum(id, "farmSpeed"))));
    }

    public record SlotState(int slot, String state, Catalog.Crop crop, long left) {}

    public List<SlotState> status(UUID id, long now) {
        List<SlotState> out = new ArrayList<>();
        for (int slot = 1; slot <= cat.farmSlots; slot++) {
            PlayerData.Plot p = eco.pd(id).farm.get(String.valueOf(slot));
            if (p == null) out.add(new SlotState(slot, "empty", null, 0));
            else if (p.readyAt <= now) out.add(new SlotState(slot, "ready", cat.crop(p.crop), 0));
            else out.add(new SlotState(slot, "growing", cat.crop(p.crop), p.readyAt - now));
        }
        return out;
    }

    public record Plant(boolean ok, String reason, Catalog.Crop crop, int planted, List<Integer> slots, long cost, long readyIn, int skipped) {}

    private static Plant fail(String reason, Catalog.Crop crop, long cost) {
        return new Plant(false, reason, crop, 0, List.of(), cost, 0, 0);
    }

    /** Sadzi uprawę na wskazanym polu albo (gdy `slot` jest null) na wszystkich wolnych, na ile starczy pieniędzy. */
    public Plant plant(UUID id, String cropId, Integer slot, long now) {
        Catalog.Crop crop = cat.crop(cropId);
        if (crop == null) return fail("unknown", null, 0);
        List<Integer> free = new ArrayList<>();
        for (SlotState s : status(id, now)) if (s.state().equals("empty")) free.add(s.slot());
        List<Integer> targets;
        if (slot != null) {
            if (slot < 1 || slot > cat.farmSlots) return fail("slot", crop, 0);
            if (!free.contains(slot)) return fail("busy", crop, 0);
            targets = List.of(slot);
        } else {
            if (free.isEmpty()) return fail("full", crop, 0);
            targets = free;
        }
        long affordable = Math.min(targets.size(), eco.wallet(id) / crop.cost());
        if (affordable < 1) return fail("poor", crop, crop.cost());
        List<Integer> chosen = targets.subList(0, (int) affordable);
        long cost = crop.cost() * chosen.size();
        eco.take(id, cost);
        long ready = now + growMs(id, crop);
        for (int s : chosen) eco.pd(id).farm.put(String.valueOf(s), new PlayerData.Plot(crop.id(), ready));
        eco.store.touch(id);
        return new Plant(true, null, crop, chosen.size(), new ArrayList<>(chosen), cost, ready - now, targets.size() - chosen.size());
    }

    public record Harvest(long gain, Map<String, Integer> crops, int count) {}

    /** Zbiera wszystkie dojrzałe uprawy i wypłaca plon. */
    public Harvest harvest(UUID id, long now) {
        long gain = 0;
        int count = 0;
        Map<String, Integer> crops = new LinkedHashMap<>();
        for (SlotState s : status(id, now)) {
            if (!s.state().equals("ready")) continue;
            gain += s.crop().yield();
            count++;
            crops.merge(s.crop().id(), 1, Integer::sum);
            eco.pd(id).farm.remove(String.valueOf(s.slot()));
        }
        if (gain > 0) eco.add(id, gain);
        eco.store.touch(id);
        return new Harvest(gain, crops, count);
    }
}
