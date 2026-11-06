package pl.kasyno.core;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Katalog przedmiotów, bonusów, biznesów i tabel łupów. Dane pochodzą z catalog.json, który generuje
 * tools/gen-catalog.js z katalogu bota Discord, więc ceny i szanse są takie same w obu wersjach.
 */
public final class Catalog {
    public record Item(String id, String name, String type, long price, long sell, String desc, boolean unique, String requires) {
        /** Za ile można sprzedać (łup ma stałą cenę skupu, reszta 50% ceny sklepowej). */
        public long sellValue() {
            return sell > 0 ? sell : price / 2;
        }
    }

    public record Category(String id, String title, List<String> items) {}

    public record Boost(String id, String key, String effect, double value, long duration, String label) {}

    public record Protection(String id, String kind, long cap) {}

    public record Business(String id, String name, long price, long hourly) {}

    public record Crop(String id, String name, long cost, long yield, int minutes) {}

    public record Job(String id, String name, int level, long min, long max, List<String> lines) {}

    public record Crime(String win, String lose) {}

    public record Trivia(String q, String a, List<String> w) {}

    public record BankUpgrade(String name, long cost, long bonus) {}

    public record GatherTool(String id, double luck, double miss) {}

    public record LootEntry(String id, double w, int[] qty, boolean rare) {}

    public record Gather(String key, long cooldown, double missChance, List<GatherTool> tools, List<LootEntry> table) {}

    public record CrateEntry(String kind, String item, int qty, long min, long max, double w, String r) {}

    public record Crate(String id, List<CrateEntry> loot) {}

    public record BankInfo(long base, long perLevel, long sejfBonus) {}

    public record Interest(double rate, long cap) {}

    public record Bonuses(Map<String, Double> vip, double breakChance, double breakChanceBelt, Map<String, Interest> interest,
                          double cloverPct, long cloverMax) {}

    // ---------- tabele gier ----------
    public record WheelSegment(String label, double mult, double w) {}

    public record ScratchOutcome(double mult, double w, String sym) {}

    public record Horse(String name, double p, double mult) {}

    public record PlinkoTable(int rows, Map<String, double[]> mults) {}

    public record SlotSymbol(String e, double w, double triple, double pair) {}

    public record SlotJackpot(double chance, double mult) {}

    public record Slots(List<SlotSymbol> symbols, SlotJackpot jackpot) {}

    public record ChickenLevel(String name, double d, int max) {}

    public record TowerLevel(String name, int cols, int bad) {}

    public record Games(List<WheelSegment> kolo, List<ScratchOutcome> zdrapka, List<Horse> wyscigi, PlinkoTable plinko, Slots sloty,
                        Map<String, ChickenLevel> kurczak, Map<String, TowerLevel> wieza) {}

    /** Kształt pliku JSON. */
    private record Raw(Games games, List<Item> items, List<Category> categories, Map<String, Map<String, Double>> effects, List<Boost> boosts,
                       List<Protection> protections, Bonuses bonuses, List<String> vipCards, List<Business> businesses,
                       int businessMaxLevel, int storageHours, List<Crop> crops, int farmSlots, List<Job> jobs,
                       List<Crime> crimes, List<Trivia> trivia, List<BankUpgrade> bankUpgrades, BankInfo bank,
                       long startBalance, Map<String, Long> cooldowns, List<Gather> gather, List<Crate> crates) {}

    public final Games games;
    public final Map<String, Item> items = new LinkedHashMap<>();
    public final List<Category> categories;
    public final Map<String, Map<String, Double>> effects;
    public final Map<String, Boost> boosts = new LinkedHashMap<>();
    public final Map<String, Protection> protections = new LinkedHashMap<>();
    public final Bonuses bonuses;
    public final List<String> vipCards;
    public final List<Business> businesses;
    public final int businessMaxLevel;
    public final int storageHours;
    public final List<Crop> crops;
    public final int farmSlots;
    public final List<Job> jobs;
    public final List<Crime> crimes;
    public final List<Trivia> trivia;
    public final List<BankUpgrade> bankUpgrades;
    public final BankInfo bank;
    public final long startBalance;
    public final Map<String, Long> cooldowns;
    public final Map<String, Gather> gather = new HashMap<>();
    public final Map<String, Crate> crates = new LinkedHashMap<>();

    private Catalog(Raw r) {
        games = r.games();
        r.items().forEach(i -> items.put(i.id(), i));
        categories = r.categories();
        effects = r.effects();
        r.boosts().forEach(b -> boosts.put(b.id(), b));
        r.protections().forEach(p -> protections.put(p.id(), p));
        bonuses = r.bonuses();
        vipCards = r.vipCards();
        businesses = r.businesses();
        businessMaxLevel = r.businessMaxLevel();
        storageHours = r.storageHours();
        crops = r.crops();
        farmSlots = r.farmSlots();
        jobs = r.jobs();
        crimes = r.crimes();
        trivia = r.trivia();
        bankUpgrades = r.bankUpgrades();
        bank = r.bank();
        startBalance = r.startBalance();
        cooldowns = r.cooldowns();
        r.gather().forEach(g -> gather.put(g.key(), g));
        r.crates().forEach(c -> crates.put(c.id(), c));
    }

    /** Wczytuje catalog.json z zasobów pluginu. */
    public static Catalog load() {
        try (InputStream in = Catalog.class.getResourceAsStream("/catalog.json")) {
            if (in == null) throw new IllegalStateException("Brak catalog.json w zasobach pluginu");
            return new Catalog(new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Raw.class));
        } catch (IOException e) {
            throw new IllegalStateException("Nie udało się wczytać catalog.json", e);
        }
    }

    public Item item(String id) {
        return items.get(id);
    }

    public Business business(String id) {
        return businesses.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
    }

    public Crop crop(String id) {
        return crops.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    public Job job(String id) {
        return jobs.stream().filter(j -> j.id().equals(id)).findFirst().orElse(jobs.get(0));
    }

    /** Elementy sklepu w kolejności kategorii. */
    public List<String> shopOrder() {
        List<String> out = new ArrayList<>();
        categories.forEach(c -> out.addAll(c.items()));
        return out;
    }
}
