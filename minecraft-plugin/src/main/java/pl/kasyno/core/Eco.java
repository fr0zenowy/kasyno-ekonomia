package pl.kasyno.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * Reguły ekonomii: portfel, poziomy, bank, bonusy z przedmiotów, eliksiry, ochrony (polisa i tarcza) oraz rozliczanie gier.
 * Nie zna Bukkita, więc da się to testować zwykłymi testami jednostkowymi (zegar i losowość są wstrzykiwane).
 */
public final class Eco {
    public static final long MIN_BET = 10;
    public static final long MAX_BET = 1_000_000_000L;
    public static final long DAILY_MS = 20L * 3600 * 1000;
    public static final long WEEK_MS = 7L * 24 * 3600 * 1000;

    public final Catalog cat;
    public final DataStore store;
    public final LongSupplier clock;
    public final RandomGenerator rng;

    public Eco(Catalog cat, DataStore store, LongSupplier clock, RandomGenerator rng) {
        this.cat = cat;
        this.store = store;
        this.clock = clock;
        this.rng = rng;
    }

    // ---------- pomocnicze ----------

    public PlayerData pd(UUID id) {
        return store.get(id);
    }

    public long now() {
        return clock.getAsLong();
    }

    public int randInt(int min, int max) {
        return min + rng.nextInt(max - min + 1);
    }

    public long randLong(long min, long max) {
        return min + (long) Math.floor(rng.nextDouble() * (max - min + 1));
    }

    public boolean chance(double p) {
        return rng.nextDouble() < p;
    }

    public <T> T pick(List<T> list) {
        return list.get(rng.nextInt(list.size()));
    }

    // ---------- portfel ----------

    public long wallet(UUID id) {
        return pd(id).wallet;
    }

    public void add(UUID id, long amount) {
        PlayerData d = pd(id);
        d.wallet += amount;
        store.touch(id);
    }

    /** Zabiera z portfela, jeśli starcza. Zwraca true/false. */
    public boolean take(UUID id, long amount) {
        PlayerData d = pd(id);
        if (amount < 0 || d.wallet < amount) return false;
        d.wallet -= amount;
        store.touch(id);
        return true;
    }

    public boolean transfer(UUID from, UUID to, long amount) {
        if (amount < 1 || !take(from, amount)) return false;
        add(to, amount);
        return true;
    }

    // ---------- ekwipunek ----------

    public int qty(UUID id, String item) {
        return pd(id).inventory.getOrDefault(item, 0);
    }

    public void addItem(UUID id, String item, int n) {
        pd(id).inventory.merge(item, n, Integer::sum);
        store.touch(id);
    }

    public boolean takeItem(UUID id, String item, int n) {
        PlayerData d = pd(id);
        int have = d.inventory.getOrDefault(item, 0);
        if (n < 1 || have < n) return false;
        if (have == n) d.inventory.remove(item);
        else d.inventory.put(item, have - n);
        store.touch(id);
        return true;
    }

    // ---------- poziomy ----------

    public record LevelUp(int level, int ups, long bonus, int crates) {
        public boolean leveled() {
            return ups > 0;
        }
    }

    public static long xpNeeded(int level) {
        return 100 + (level - 1) * 75L;
    }

    public double xpMultiplier(UUID id) {
        return (1 + effectSum(id, "xp")) * boostValue(id, "xp");
    }

    /** Dodaje XP (domyślnie z mnożnikami). Awans daje bonus w monetach, a co 5. poziom skrzynię skarbów. */
    public LevelUp addXp(UUID id, long amount, boolean boost) {
        PlayerData d = pd(id);
        long xp = d.xp + Math.round(Math.max(0, amount) * (boost ? xpMultiplier(id) : 1));
        int level = d.level;
        int ups = 0;
        long bonus = 0;
        int crates = 0;
        while (xp >= xpNeeded(level)) {
            xp -= xpNeeded(level);
            level++;
            ups++;
            bonus += level * 200L;
            if (level % 5 == 0) crates++;
        }
        d.xp = xp;
        d.level = level;
        if (bonus > 0) d.wallet += bonus;
        if (crates > 0) addItem(id, "skrzynia", crates);
        store.touch(id);
        return new LevelUp(level, ups, bonus, crates);
    }

    public LevelUp addXp(UUID id, long amount) {
        return addXp(id, amount, true);
    }

    // ---------- bank ----------

    public record BankBreakdown(long base, long levels, long upgrades, int tier, int sejfs, long safes, long total) {}

    public BankBreakdown bank(UUID id) {
        PlayerData d = pd(id);
        long levels = (d.level - 1L) * cat.bank.perLevel();
        long upgrades = 0;
        for (int i = 0; i < Math.min(d.bankTier, cat.bankUpgrades.size()); i++) upgrades += cat.bankUpgrades.get(i).bonus();
        int sejfs = qty(id, "sejf");
        long safes = sejfs * cat.bank.sejfBonus();
        return new BankBreakdown(cat.bank.base(), levels, upgrades, d.bankTier, sejfs, safes, cat.bank.base() + levels + upgrades + safes);
    }

    public long deposit(UUID id, long amount) {
        PlayerData d = pd(id);
        long room = Math.max(0, bank(id).total() - d.bank);
        long amt = Math.min(Math.min(amount, d.wallet), room);
        if (amt < 1) return 0;
        d.wallet -= amt;
        d.bank += amt;
        store.touch(id);
        return amt;
    }

    public long withdraw(UUID id, long amount) {
        PlayerData d = pd(id);
        long amt = Math.min(amount, d.bank);
        if (amt < 1) return 0;
        d.bank -= amt;
        d.wallet += amt;
        store.touch(id);
        return amt;
    }

    public enum Upgrade { OK, MAX, POOR }

    public Upgrade buyBankUpgrade(UUID id) {
        PlayerData d = pd(id);
        if (d.bankTier >= cat.bankUpgrades.size()) return Upgrade.MAX;
        Catalog.BankUpgrade up = cat.bankUpgrades.get(d.bankTier);
        if (!take(id, up.cost())) return Upgrade.POOR;
        d.bankTier++;
        store.touch(id);
        return Upgrade.OK;
    }

    // ---------- bonusy z przedmiotów i eliksirów ----------

    /** Suma stałych bonusów danego rodzaju z posiadanych przedmiotów (tabela efektów w katalogu). */
    public double effectSum(UUID id, String effect) {
        double sum = 0;
        for (Map.Entry<String, Map<String, Double>> e : cat.effects.entrySet()) {
            Double v = e.getValue().get(effect);
            if (v != null && qty(id, e.getKey()) > 0) sum += v;
        }
        return sum;
    }

    public long boostLeft(UUID id, String key) {
        Long until = pd(id).boosts.get(key);
        return until == null ? 0 : Math.max(0, until - now());
    }

    /** Najlepszy aktywny eliksir danego rodzaju (dla cooldownu: najkrótszy czas). Bez eliksiru: 1. */
    public double boostValue(UUID id, String effect) {
        double best = 1;
        for (Catalog.Boost b : cat.boosts.values()) {
            if (!b.effect().equals(effect) || boostLeft(id, b.key()) <= 0) continue;
            best = effect.equals("cooldown") ? Math.min(best, b.value()) : Math.max(best, b.value());
        }
        return best;
    }

    public Optional<Catalog.Boost> activeBoost(UUID id, String effect) {
        Catalog.Boost best = null;
        for (Catalog.Boost b : cat.boosts.values()) {
            if (!b.effect().equals(effect) || boostLeft(id, b.key()) <= 0) continue;
            boolean better = best == null || (effect.equals("cooldown") ? b.value() < best.value() : b.value() > best.value());
            if (better) best = b;
        }
        return Optional.ofNullable(best);
    }

    public record Activation(long left, boolean extended) {}

    /** Włącza eliksir albo przedłuża aktywny (przedmiot zdejmuje wywołujący). */
    public Activation activateBoost(UUID id, String itemId) {
        Catalog.Boost b = cat.boosts.get(itemId);
        long left = boostLeft(id, b.key());
        pd(id).boosts.put(b.key(), now() + left + b.duration());
        store.touch(id);
        return new Activation(left + b.duration(), left > 0);
    }

    public Optional<String> vipCard(UUID id) {
        return cat.vipCards.stream().filter(c -> qty(id, c) > 0).findFirst();
    }

    public record Earned(long amount, double mult, List<String> notes) {}

    /** Zarobek z pracy, żebrania, quizów i przestępstw: (1 + karta VIP + zwierzaki) × eliksir. */
    public Earned applyEarn(UUID id, long base) {
        double bonus = 0;
        List<String> notes = new ArrayList<>();
        Optional<String> card = vipCard(id);
        if (card.isPresent()) {
            double v = cat.bonuses.vip().get(card.get());
            bonus += v;
            notes.add(cat.item(card.get()).name() + ": +" + Math.round(v * 100) + "%");
        }
        double perks = effectSum(id, "earn");
        if (perks > 0) {
            bonus += perks;
            notes.add("Zwierzaki: +" + Math.round(perks * 100) + "%");
        }
        Optional<Catalog.Boost> timed = activeBoost(id, "earn");
        timed.ifPresent(b -> notes.add(cat.item(b.id()).name() + ": x" + trim(b.value())));
        double mult = (1 + bonus) * timed.map(Catalog.Boost::value).orElse(1.0);
        return new Earned((long) Math.floor(base * mult), mult, notes);
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    public double luckMultiplier(UUID id) {
        return (1 + effectSum(id, "luck")) * boostValue(id, "luck");
    }

    public double lootMultiplier(UUID id) {
        return boostValue(id, "loot");
    }

    public double breakChance(UUID id) {
        return qty(id, "pas_narzedziowy") > 0 ? cat.bonuses.breakChanceBelt() : cat.bonuses.breakChance();
    }

    public record InterestTerms(double rate, long cap, boolean savings, String account) {}

    public InterestTerms interestTerms(UUID id) {
        for (String acc : List.of("konto_premium", "konto_oszczednosciowe")) {
            if (qty(id, acc) > 0) {
                Catalog.Interest i = cat.bonuses.interest().get(acc);
                return new InterestTerms(i.rate(), i.cap(), true, acc);
            }
        }
        Catalog.Interest i = cat.bonuses.interest().get("base");
        return new InterestTerms(i.rate(), i.cap(), false, null);
    }

    // ---------- cooldowny ----------

    public long cooldownLeft(UUID id, String key) {
        Long until = pd(id).cooldowns.get(key);
        return until == null ? 0 : Math.max(0, until - now());
    }

    public void setCooldown(UUID id, String key, long ms) {
        pd(id).cooldowns.put(key, now() + ms);
        store.touch(id);
    }

    public void clearCooldown(UUID id, String key) {
        pd(id).cooldowns.remove(key);
        store.touch(id);
    }

    public long scaledCooldown(UUID id, long ms) {
        return Math.round(ms * boostValue(id, "cooldown"));
    }

    /** Jeśli cooldown trwa, zwraca ile zostało (i nic nie zmienia). W przeciwnym razie ustawia nowy i zwraca 0. */
    public long guardCooldown(UUID id, String key, long ms) {
        long left = cooldownLeft(id, key);
        if (left > 0) return left;
        setCooldown(id, key, scaledCooldown(id, ms));
        return 0;
    }

    public long cooldownOf(String key) {
        return cat.cooldowns.getOrDefault(key, 60_000L);
    }

    // ---------- ochrona w grach ----------

    public int buffUses(UUID id, String key) {
        return pd(id).buffs.getOrDefault(key, 0);
    }

    public void addBuff(UUID id, String key, int uses) {
        pd(id).buffs.merge(key, uses, Integer::sum);
        store.touch(id);
    }

    public boolean useBuff(UUID id, String key) {
        int uses = buffUses(id, key);
        if (uses < 1) return false;
        if (uses == 1) pd(id).buffs.remove(key);
        else pd(id).buffs.put(key, uses - 1);
        store.touch(id);
        return true;
    }

    /** Uzbrojone ochrony danego rodzaju pokrywające stawkę, od najtańszej (najmniejszy sufit). */
    public List<Catalog.Protection> armed(UUID id, String kind, long stake) {
        return cat.protections.values().stream()
                .filter(p -> p.kind().equals(kind) && p.cap() >= stake && buffUses(id, p.id()) > 0)
                .sorted((a, b) -> Long.compare(a.cap(), b.cap()))
                .toList();
    }

    /** Zużywa jedną, najtańszą wystarczającą ochronę. */
    public Optional<Catalog.Protection> consume(UUID id, String kind, long stake) {
        List<Catalog.Protection> list = armed(id, kind, stake);
        if (list.isEmpty()) return Optional.empty();
        Catalog.Protection p = list.get(0);
        return useBuff(id, p.id()) ? Optional.of(p) : Optional.empty();
    }

    // ---------- gry: stawki i rozliczenie ----------

    public enum BetError { NONE, MIN, MAX, POOR, BUSY }

    /** Zdejmuje stawkę z portfela i zapisuje ją jako trwającą (po awarii serwera wróci do gracza). */
    public BetError takeBet(UUID id, long bet, String game) {
        PlayerData d = pd(id);
        if (d.activeStake > 0) return BetError.BUSY;
        if (bet < MIN_BET) return BetError.MIN;
        if (bet > MAX_BET) return BetError.MAX;
        if (d.wallet < bet) return BetError.POOR;
        d.wallet -= bet;
        d.activeStake = bet;
        d.activeGame = game;
        store.touch(id);
        return BetError.NONE;
    }

    /** Dokłada do trwającej stawki (np. double w blackjacku). */
    public boolean increaseBet(UUID id, long extra) {
        PlayerData d = pd(id);
        if (d.activeStake <= 0 || !take(id, extra)) return false;
        d.activeStake += extra;
        return true;
    }

    public long activeStake(UUID id) {
        return pd(id).activeStake;
    }

    /** Zwraca trwającą stawkę do portfela (anulowana gra, awaria). Zwraca kwotę. */
    public long refund(UUID id) {
        PlayerData d = pd(id);
        long stake = d.activeStake;
        if (stake > 0) d.wallet += stake;
        d.activeStake = 0;
        d.activeGame = null;
        store.touch(id);
        return stake;
    }

    public record Settle(long payout, long net, long bonus, LevelUp lv, Optional<Catalog.Protection> refunded) {}

    /**
     * Rozlicza grę: `payout` to całość, która wraca do portfela (0 = przegrana, bet = remis). `bet` to łączna stawka.
     * Polisa oddaje stawkę po całkowitej przegranej, koniczyna dodaje (ograniczony) bonus do wygranej.
     */
    public Settle settle(UUID id, long bet, long payout, long xp) {
        PlayerData d = pd(id);
        d.activeStake = 0;
        d.activeGame = null;
        return finish(id, bet, payout, xp);
    }

    private Settle finish(UUID id, long bet, long payout, long xp) {
        PlayerData d = pd(id);
        long fin = Math.max(0, payout);
        long bonus = 0;
        Optional<Catalog.Protection> refunded = Optional.empty();
        if (fin == 0 && bet > 0) {
            refunded = consume(id, "polisa", bet);
            if (refunded.isPresent()) fin = bet;
        } else if (fin > bet && useBuff(id, "koniczyna")) {
            bonus = Math.min((long) Math.floor((fin - bet) * cat.bonuses.cloverPct()), cat.bonuses.cloverMax());
            fin += bonus;
        }
        if (fin > 0) d.wallet += fin;
        long net = fin - bet;
        recordGame(id, net);
        LevelUp lv = addXp(id, xp);
        store.touch(id);
        return new Settle(fin, net, bonus, lv, refunded);
    }

    public Settle settle(UUID id, long bet, long payout) {
        return settle(id, bet, payout, 3);
    }

    // ---------- stawki przy wspólnych stołach ----------

    /**
     * Zdejmuje stawkę z portfela i zapisuje ją jako zatrzymaną przy stole. Nie dotyka stawki z gry solo (activeStake),
     * więc gracz może siedzieć przy stole i grać w coś innego.
     */
    public BetError hold(UUID id, String table, long bet) {
        PlayerData d = pd(id);
        if (bet < MIN_BET) return BetError.MIN;
        if (bet > MAX_BET) return BetError.MAX;
        if (d.wallet < bet) return BetError.POOR;
        d.wallet -= bet;
        d.held.merge(table, bet, Long::sum);
        store.touch(id);
        return BetError.NONE;
    }

    public long held(UUID id, String table) {
        return pd(id).held.getOrDefault(table, 0L);
    }

    /** Oddaje całą stawkę zatrzymaną przy stole (cofnięcie zakładu, odwołana runda). Zwraca kwotę. */
    public long refundHeld(UUID id, String table) {
        PlayerData d = pd(id);
        Long stake = d.held.remove(table);
        if (stake == null || stake <= 0) return 0;
        d.wallet += stake;
        store.touch(id);
        return stake;
    }

    /** Rozlicza zakłady gracza przy stole: `payout` to całość, która wraca do portfela (0 = przegrana). Zasady jak w settle. */
    public Settle settleHeld(UUID id, String table, long payout, long xp) {
        long bet = pd(id).held.getOrDefault(table, 0L);
        pd(id).held.remove(table);
        return finish(id, bet, payout, xp);
    }

    /** Po starcie serwera żaden stół nie trzyma stawek, więc wszystko, co zostało w zapisie, wraca do graczy. Zwraca sumę. */
    public long recoverHeld() {
        long total = 0;
        for (var e : store.all().entrySet()) {
            PlayerData d = e.getValue();
            if (d.held == null || d.held.isEmpty()) continue;
            for (long v : d.held.values()) total += v;
            d.wallet += d.held.values().stream().mapToLong(Long::longValue).sum();
            d.held.clear();
            store.touch(e.getKey());
        }
        return total;
    }

    public void recordGame(UUID id, long net) {
        PlayerData d = pd(id);
        d.games++;
        if (net > 0) {
            d.totalWon += net;
            d.biggestWin = Math.max(d.biggestWin, net);
        } else if (net < 0) {
            d.totalLost += -net;
        }
        store.touch(id);
    }

    // ---------- ranking ----------

    public enum RankBy { WEALTH, LEVEL, GAMBLING }

    public record RankRow(UUID id, String name, long wealth, int level, long totalWon) {}

    public List<RankRow> top(RankBy by, int limit) {
        List<RankRow> rows = new ArrayList<>();
        store.all().forEach((id, d) -> rows.add(new RankRow(id, d.name, d.wallet + d.bank, d.level, d.totalWon)));
        rows.sort((a, b) -> switch (by) {
            case WEALTH -> Long.compare(b.wealth(), a.wealth());
            case LEVEL -> b.level() != a.level() ? Integer.compare(b.level(), a.level()) : Long.compare(b.wealth(), a.wealth());
            case GAMBLING -> Long.compare(b.totalWon(), a.totalWon());
        });
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }

    public int rankOf(UUID id) {
        long mine = pd(id).wallet + pd(id).bank;
        int better = 0;
        for (PlayerData d : store.all().values()) if (d.wallet + d.bank > mine) better++;
        return better + 1;
    }
}
