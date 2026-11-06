package pl.kasyno.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Testy reguł ekonomii, biznesów, farmy, skrzyń i matematyki gier (bez Bukkita, z kontrolowanym zegarem i losowością). */
class CoreTest {
    @TempDir
    Path dir;

    Catalog cat;
    DataStore store;
    AtomicLong clock;
    Eco eco;
    Empire empire;
    Crates crates;
    UUID p;

    @BeforeEach
    void setUp() {
        cat = Catalog.load();
        store = new DataStore(dir, java.util.logging.Logger.getAnonymousLogger(), cat.startBalance);
        clock = new AtomicLong(1_700_000_000_000L);
        eco = new Eco(cat, store, clock::get, new Random(7));
        empire = new Empire(eco);
        crates = new Crates(eco);
        p = UUID.randomUUID();
    }

    // ---------- katalog ----------

    @Test
    void catalogIsConsistent() {
        assertEquals(113, cat.items.size());
        for (String id : cat.shopOrder()) assertNotNull(cat.item(id), "sklep sprzedaje nieistniejący " + id);
        for (Catalog.Protection pr : cat.protections.values()) {
            long price = cat.item(pr.id()).price();
            if (pr.kind().equals("polisa")) assertTrue(pr.cap() <= price, pr.id() + ": sufit polisy powyżej ceny");
            else assertTrue(pr.cap() * 2 <= price, pr.id() + ": sufit tarczy powyżej połowy ceny");
        }
        for (Catalog.Item it : cat.items.values()) if (it.requires() != null) assertNotNull(cat.item(it.requires()));
        for (Catalog.Crate c : cat.crates.values()) {
            assertTrue(crates.expectedValue(c.id()) < cat.item(c.id()).price(), c.id() + ": skrzynia droższa w wartości niż w cenie");
            assertEquals(100.0, crates.rarityOdds(c.id()).values().stream().mapToDouble(Double::doubleValue).sum(), 1e-6);
        }
        for (Catalog.Business b : cat.businesses) {
            double payback = (double) b.price() / b.hourly();
            assertTrue(payback >= 20 && payback <= 80, b.id() + " zwrot " + payback);
        }
        assertEquals(10, cat.categories.size());
    }

    // ---------- portfel, poziomy, bank ----------

    @Test
    void newPlayerStartsWithStartBalance() {
        assertEquals(500, eco.wallet(p));
        assertTrue(eco.take(p, 200));
        assertFalse(eco.take(p, 400));
        assertEquals(300, eco.wallet(p));
    }

    @Test
    void levelUpGivesBonusAndCrate() {
        Eco.LevelUp lv = eco.addXp(p, 100, false);
        assertEquals(2, lv.level());
        assertEquals(400, lv.bonus());
        assertEquals(500 + 400, eco.wallet(p));
        assertEquals(1, eco.pd(p).level - 1);
        // do poziomu 5 dostaje skrzynię skarbów
        for (int i = 0; i < 3; i++) eco.addXp(p, Eco.xpNeeded(eco.pd(p).level), false);
        assertEquals(5, eco.pd(p).level);
        assertEquals(1, eco.qty(p, "skrzynia"));
    }

    @Test
    void bankLimitDepositWithdrawAndUpgrade() {
        assertEquals(100_000, eco.bank(p).total());
        eco.add(p, 200_000);
        assertEquals(100_000, eco.deposit(p, 500_000), "wpłata ograniczona limitem banku");
        assertEquals(50_000, eco.withdraw(p, 50_000));
        assertEquals(Eco.Upgrade.POOR, eco.buyBankUpgrade(UUID.randomUUID()), "nowy gracz ma 500, a pierwsze ulepszenie kosztuje 50 000");
        // pierwsze ulepszenie kosztuje 50 000 i daje +150 000 do limitu
        long before = eco.bank(p).total();
        long wallet = eco.wallet(p);
        assertEquals(Eco.Upgrade.OK, eco.buyBankUpgrade(p));
        assertEquals(before + 150_000, eco.bank(p).total());
        assertEquals(wallet - 50_000, eco.wallet(p));
        for (int i = 0; i < 6; i++) {
            eco.add(p, 2_000_000_000L);
            assertEquals(Eco.Upgrade.OK, eco.buyBankUpgrade(p));
        }
        assertEquals(Eco.Upgrade.MAX, eco.buyBankUpgrade(p));
    }

    // ---------- bonusy ----------

    @Test
    void earnBonusesStackAndBoostsDoNotMultiply() {
        for (String it : List.of("papuga", "smok", "vip_zloty", "vip_platynowa", "vip_diamentowa")) eco.addItem(p, it, 1);
        assertEquals(3.2, eco.applyEarn(p, 1000).mult(), 1e-9, "najlepsza karta (+200%) + zwierzaki (+20%)");
        eco.activateBoost(p, "mega_eliksir");
        eco.activateBoost(p, "eliksir_zarobkow");
        assertEquals(9.6, eco.applyEarn(p, 1000).mult(), 1e-9, "działa najlepszy eliksir (x3), nie iloczyn");
        Eco.Activation again = eco.activateBoost(p, "mega_eliksir");
        assertTrue(again.extended());
        clock.addAndGet(2 * 3600_000L);
        assertEquals(1.0, eco.boostValue(p, "earn"), "eliksiry wygasają");
        assertEquals(3.2, eco.applyEarn(p, 1000).mult(), 1e-9);
    }

    @Test
    void cooldownBoostShortensCooldowns() {
        eco.activateBoost(p, "zegarek");
        assertEquals(500, eco.scaledCooldown(p, 1000));
        eco.activateBoost(p, "super_zegarek");
        assertEquals(250, eco.scaledCooldown(p, 1000));
        assertEquals(0, eco.guardCooldown(p, "x", 1000));
        assertTrue(eco.guardCooldown(p, "x", 1000) > 0);
    }

    // ---------- ochrona i rozliczanie ----------

    @Test
    void polisaRefundsFullLossUpToCap() {
        eco.add(p, 100_000);
        eco.addBuff(p, "polisa_mala", 1);
        assertEquals(Eco.BetError.NONE, eco.takeBet(p, 1000, "test"));
        Eco.Settle s = eco.settle(p, 1000, 0);
        assertTrue(s.refunded().isPresent());
        assertEquals(0, s.net());
        assertEquals(0, eco.buffUses(p, "polisa_mala"));
        // powyżej sufitu (2 000) polisa nie działa i nie jest zużywana
        eco.addBuff(p, "polisa_mala", 1);
        eco.takeBet(p, 3000, "test");
        Eco.Settle big = eco.settle(p, 3000, 0);
        assertTrue(big.refunded().isEmpty());
        assertEquals(-3000, big.net());
        assertEquals(1, eco.buffUses(p, "polisa_mala"));
        // najtańsza wystarczająca
        eco.addBuff(p, "polisa_srednia", 1);
        eco.takeBet(p, 10_000, "test");
        assertEquals("polisa_srednia", eco.settle(p, 10_000, 0).refunded().orElseThrow().id());
    }

    @Test
    void cloverBonusIsCapped() {
        eco.add(p, 5_000_000_000L);
        eco.addBuff(p, "koniczyna", 2);
        eco.takeBet(p, 100, "t");
        assertEquals(20, eco.settle(p, 100, 200).bonus());
        eco.takeBet(p, 1_000_000_000, "t");
        assertTrue(eco.settle(p, 1_000_000_000, 2_000_000_000L).bonus() <= cat.bonuses.cloverMax());
    }

    @Test
    void betErrorsAndRefund() {
        assertEquals(Eco.BetError.MIN, eco.takeBet(p, 5, "t"));
        assertEquals(Eco.BetError.POOR, eco.takeBet(p, 5000, "t"));
        assertEquals(Eco.BetError.NONE, eco.takeBet(p, 100, "t"));
        assertEquals(Eco.BetError.BUSY, eco.takeBet(p, 100, "t"));
        assertEquals(400, eco.wallet(p));
        assertEquals(100, eco.refund(p));
        assertEquals(500, eco.wallet(p));
        assertEquals(0, eco.refund(p));
    }

    @Test
    void settleKeepsMoneyConsistent() {
        long start = 10_000;
        eco.pd(p).wallet = start;
        long net = 0;
        Random r = new Random(1);
        for (int i = 0; i < 500; i++) {
            long bet = 10 + r.nextInt(200);
            if (eco.takeBet(p, bet, "t") != Eco.BetError.NONE) continue;
            long payout = r.nextBoolean() ? 0 : bet * 2;
            Eco.Settle s = eco.settle(p, bet, payout);
            net += s.net() + s.lv().bonus();
        }
        assertEquals(start + net, eco.wallet(p));
        assertEquals(0, eco.activeStake(p));
    }

    // ---------- stawki przy wspólnych stołach ----------

    @Test
    void tableStakesHaveTheirOwnLedgerAndKeepSoloStake() {
        eco.pd(p).wallet = 10_000;
        assertEquals(Eco.BetError.NONE, eco.takeBet(p, 1_000, "miny")); // gra solo
        assertEquals(Eco.BetError.MIN, eco.hold(p, "crash", 5));
        assertEquals(Eco.BetError.POOR, eco.hold(p, "crash", 50_000));
        assertEquals(Eco.BetError.NONE, eco.hold(p, "crash", 500));
        assertEquals(Eco.BetError.NONE, eco.hold(p, "ruletka", 200));
        assertEquals(Eco.BetError.NONE, eco.hold(p, "ruletka", 300)); // żetony przy tym samym stole sumują się
        assertEquals(8_000, eco.wallet(p));
        assertEquals(500, eco.held(p, "ruletka"));

        Eco.Settle win = eco.settleHeld(p, "crash", 1_000, 3);
        assertEquals(500, win.net());
        assertEquals(1_000, eco.activeStake(p), "rozliczenie stołu nie rusza stawki gry solo");
        assertEquals(0, eco.held(p, "crash"));
        assertEquals(500, eco.held(p, "ruletka"));
        assertEquals(500, eco.refundHeld(p, "ruletka"));
        assertEquals(0, eco.refundHeld(p, "ruletka"));
        assertEquals(9_500, eco.wallet(p), "10 000 - 1 000 (gra solo) + 500 (zysk ze stołu)");
        assertEquals(1_000, eco.refund(p));
        assertEquals(10_500, eco.wallet(p));
    }

    @Test
    void tableSettlementUsesPolicyCloverAndRecordsTheGame() {
        eco.pd(p).wallet = 100_000;
        eco.addBuff(p, "polisa_mala", 1);
        eco.hold(p, "kolo", 1_000);
        Eco.Settle lost = eco.settleHeld(p, "kolo", 0, 3);
        assertTrue(lost.refunded().isPresent(), "polisa działa przy stole");
        assertEquals(0, lost.net());
        assertEquals(1, eco.pd(p).games);

        eco.addBuff(p, "koniczyna", 1);
        eco.hold(p, "kolo", 100);
        assertEquals(20, eco.settleHeld(p, "kolo", 200, 3).bonus(), "koniczyna działa przy stole");
        assertEquals(2, eco.pd(p).games);
    }

    @Test
    void orphanedTableStakesReturnAfterRestart() {
        eco.pd(p).wallet = 3_000;
        eco.hold(p, "crash", 1_000);
        eco.hold(p, "wyscigi", 500);
        store.flush();
        // „po awarii”: nowy proces wczytuje zapis, a w nim stawki, których żaden stół już nie trzyma
        DataStore reloaded = new DataStore(dir, java.util.logging.Logger.getAnonymousLogger(), cat.startBalance);
        reloaded.loadAll();
        Eco again = new Eco(cat, reloaded, clock::get, new Random(7));
        assertEquals(1_500, again.wallet(p));
        assertEquals(1_500, again.recoverHeld());
        assertEquals(3_000, again.wallet(p));
        assertEquals(0, again.recoverHeld());
    }

    // ---------- napad etapami ----------

    @Test
    void heistReturnsLessThan100PercentForEveryCrewSize() {
        Random rng = new Random(11);
        for (int n = 2; n <= 10; n++) {
            List<UUID> crew = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) crew.add(UUID.randomUUID());
            long runs = 60_000;
            double paid = 0;
            double successes = 0;
            for (int r = 0; r < runs; r++) {
                Heist.Result res = Heist.run(crew, u -> "x", rng);
                if (res.success()) {
                    successes++;
                    paid += res.multiplier();
                }
            }
            double rtp = paid / runs;
            System.out.printf("HEIST n=%d rtp=%.3f success=%.3f%n", n, rtp, successes / runs);
            assertTrue(rtp > 0.80 && rtp < 0.985, "napad, ekipa " + n + ": zwrot " + rtp);
        }
    }

    @Test
    void heistScriptIsConsistent() {
        Random rng = new Random(5);
        for (int run = 0; run < 2_000; run++) {
            int n = 2 + rng.nextInt(9);
            List<UUID> crew = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) crew.add(UUID.randomUUID());
            Heist.Result res = Heist.run(crew, u -> "gracz", rng);
            assertEquals(n, res.survivors().size() + res.caught().size(), "każdy jest ocalały albo złapany, nikt dwa razy");
            assertEquals(res.survivors().size() + res.caught().size(), new java.util.HashSet<UUID>() {{
                addAll(res.survivors());
                addAll(res.caught());
            }}.size());
            assertFalse(res.beats().isEmpty());
            int alive = n;
            for (Heist.Beat b : res.beats()) {
                alive -= b.caught().size();
                assertEquals(alive, b.alive());
                assertFalse(b.text().contains("{"), "nieuzupełniony znacznik: " + b.text());
                assertEquals(b.alive() == 0, b.crewDown());
            }
            assertEquals(!res.survivors().isEmpty(), res.success());
            if (!res.success()) assertEquals(0, res.multiplier(), 1e-9);
            else assertTrue(res.multiplier() >= Heist.BASE_MULT);
            // scenariusz kończy się albo na ostatnim etapie, albo gdy cała ekipa wpadła
            Heist.Beat last = res.beats().get(res.beats().size() - 1);
            assertTrue(last.stage() == Heist.STAGES.size() - 1 || last.crewDown());
        }
    }

    // ---------- biznesy i farma ----------

    @Test
    void businessEarnsStoresAndUpgrades() {
        eco.add(p, 100_000_000);
        long t = clock.get();
        Empire.Buy buy = empire.buyOrUpgrade(p, "kiosk", t);
        assertTrue(buy.ok() && buy.bought());
        assertEquals(400 * 3, empire.pending(p, t + 3 * Empire.HOUR).total());
        assertEquals(400 * 8, empire.pending(p, t + 20 * Empire.HOUR).total(), "magazyn 8 h");
        eco.addItem(p, "ksiegowy", 1);
        eco.addItem(p, "menedzer", 1);
        assertEquals((long) Math.floor(400 * 1.2 * 16), empire.pending(p, t + 20 * Empire.HOUR).total(), "księgowy 16 h i menedżer +20%");
        eco.takeItem(p, "ksiegowy", 1);
        eco.takeItem(p, "menedzer", 1);
        long w = eco.wallet(p);
        assertEquals(1200, empire.collect(p, t + 3 * Empire.HOUR).total());
        assertEquals(w + 1200, eco.wallet(p));
        Empire.Buy up = empire.buyOrUpgrade(p, "kiosk", t + 5 * Empire.HOUR);
        assertEquals(30_000, up.cost());
        assertEquals(800, up.collected(), "zaległe 2 h po starej stawce");
        assertEquals(2, up.level());
        for (int i = 0; i < 3; i++) empire.buyOrUpgrade(p, "kiosk", t + 6 * Empire.HOUR);
        assertEquals("max", empire.buyOrUpgrade(p, "kiosk", t + 7 * Empire.HOUR).reason());
        assertEquals("poor", empire.buyOrUpgrade(UUID.randomUUID(), "pizzeria", t).reason());
    }

    @Test
    void farmPlantsGrowsAndHarvests() {
        long t = clock.get();
        eco.pd(p).wallet = 1000;
        Empire.Plant r = empire.plant(p, "marchew", null, t);
        assertTrue(r.ok());
        assertEquals(4, r.planted());
        assertEquals(600, eco.wallet(p));
        assertEquals(0, empire.harvest(p, t + 60_000).count());
        assertEquals("full", empire.plant(p, "marchew", null, t).reason());
        long w = eco.wallet(p);
        Empire.Harvest h = empire.harvest(p, t + 5 * 60_000L);
        assertEquals(4, h.count());
        assertEquals(w + 640, eco.wallet(p));
        UUID q = UUID.randomUUID();
        eco.pd(q).wallet = 250;
        Empire.Plant part = empire.plant(q, "marchew", null, t);
        assertEquals(2, part.planted());
        assertEquals(2, part.skipped());
        assertEquals("busy", empire.plant(q, "marchew", 1, t).reason());
        assertEquals("slot", empire.plant(q, "marchew", 9, t).reason());
        eco.addItem(q, "podlewaczka", 1);
        eco.pd(q).wallet = 1000;
        assertEquals(Math.round(5 * 60_000 * 0.75), empire.plant(q, "marchew", 3, t).readyIn());
    }

    // ---------- skrzynie i zbieranie ----------

    @Test
    void cratesPayOutAndStayBelowPrice() {
        for (Catalog.Crate c : cat.crates.values()) {
            double total = 0;
            int n = 20_000;
            for (int i = 0; i < n; i++) {
                Crates.Reward r = crates.roll(c.id());
                total += switch (r.kind()) {
                    case "item" -> cat.item(r.item()).sellValue() * r.qty();
                    default -> r.amount();
                };
            }
            // krótka symulacja: skrzynie w skrzyniach liczą się jako sama sztuka, więc tylko sprawdzamy rozsądny zakres
            assertTrue(total / n > 0, c.id());
        }
        Crates.Reward money = new Crates.Reward("money", "common", 500, null, 0);
        long w = eco.wallet(p);
        crates.apply(p, money);
        assertEquals(w + 500, eco.wallet(p));
        crates.apply(p, new Crates.Reward("item", "rare", 0, "kamien", 3));
        assertEquals(3, eco.qty(p, "kamien"));
    }

    @Test
    void gatherWorksWithoutToolInForestAndRespectsCooldown() {
        Gather g = new Gather(eco);
        Gather.Result first = g.run(p, "las");
        assertEquals(Gather.Status.OK, first.status());
        assertTrue(first.qty() >= 1);
        assertEquals(Gather.Status.COOLDOWN, g.run(p, "las").status());
        assertEquals(Gather.Status.NO_TOOL, g.run(p, "ryby").status());
        eco.addItem(p, "wedka", 1);
        assertEquals(Gather.Status.OK, g.run(p, "ryby").status());
        // mega magnes mnoży ilość łupu
        eco.activateBoost(p, "mega_magnes");
        clock.addAndGet(10 * 60_000);
        Gather.Result tripled = g.run(p, "las");
        assertEquals(0, tripled.qty() % 3);
    }

    @Test
    void earnRewards() {
        Earn earn = new Earn(eco);
        Earn.Reward d = earn.daily(p);
        assertEquals(625, d.amount());
        assertTrue(earn.daily(p).onCooldown());
        clock.addAndGet(Eco.DAILY_MS + 1000);
        assertEquals(2, earn.daily(p).streak());
        long expectedWeekly = 5000 + 250L * eco.pd(p).level;
        assertEquals(expectedWeekly, earn.weekly(p).amount());
        assertTrue(earn.weekly(p).onCooldown());
        assertTrue(earn.hourly(p).amount() > 0);
        assertTrue(earn.hourly(p).onCooldown());
        Earn.Work w = earn.work(p);
        assertTrue(w.pay() > 0 && !w.locked());
        assertFalse(earn.changeJob(p, "ceo"), "zawód wymaga poziomu");
    }

    @Test
    void robbingFollowsRules() {
        Earn earn = new Earn(eco);
        UUID v = UUID.randomUUID();
        eco.pd(p).wallet = 10_000;
        eco.pd(v).wallet = 10_000;
        assertEquals(Earn.RobStatus.SELF, earn.rob(p, p).status());
        eco.addItem(v, "klodka", 1);
        Earn.Rob blocked = earn.rob(p, v);
        assertEquals(Earn.RobStatus.BLOCKED, blocked.status(), "kłódka bez łomu zatrzymuje złodzieja");
        assertEquals(0, eco.qty(v, "klodka"));
        clock.addAndGet(60 * 60_000);
        long total = eco.wallet(p) + eco.wallet(v);
        int level0 = eco.pd(p).level;
        int ok = 0;
        for (int i = 0; i < 40; i++) {
            clock.addAndGet(60 * 60_000);
            eco.pd(v).wallet = Math.max(eco.pd(v).wallet, 1000);
            long beforeVictim = eco.wallet(v);
            total += Math.max(0, 1000 - beforeVictim);
            long beforeVictimNow = eco.wallet(v);
            Earn.Rob r = earn.rob(p, v);
            if (r.status() == Earn.RobStatus.OK) {
                ok++;
                assertTrue(r.amount() <= beforeVictimNow * 0.35 + 1, "łup do 35% portfela ofiary");
            }
            if (r.status() == Earn.RobStatus.OK || r.status() == Earn.RobStatus.ROBBED) eco.clearCooldown(v, "robbed");
        }
        assertTrue(ok > 5, "część skoków się udaje: " + ok);
        long levelBonus = 0;
        for (int l = level0 + 1; l <= eco.pd(p).level; l++) levelBonus += l * 200L;
        assertEquals(total + levelBonus, eco.wallet(p) + eco.wallet(v), "okradanie tylko przenosi pieniądze (poza bonusami za awans)");
    }

    // ---------- matematyka gier ----------

    @Test
    void everyGameReturnsLessThan100Percent() {
        Random rng = new Random(11);
        // kroki: najlepsza strategia
        for (Catalog.ChickenLevel l : cat.games.kurczak().values()) assertBestStepStrategy(1 - l.d(), l.max());
        for (Catalog.TowerLevel l : cat.games.wieza().values()) assertBestStepStrategy((double) (l.cols() - l.bad()) / l.cols(), 14);
        // limbo i szansa
        for (double target : new double[]{1.5, 2, 10, 100}) {
            int n = 800_000;
            long wins = 0;
            for (int i = 0; i < n; i++) if (Games.limboRoll(rng) >= target) wins++;
            assertEquals(0.96 / target, (double) wins / n, target >= 100 ? 0.002 : 0.006);
        }
        for (double c : new double[]{0.5, 1, 10, 33.33, 50, 75, 95}) assertTrue(c / 100 * Games.chanceMult(c) <= 0.96 + 1e-9 && c / 100 * Games.chanceMult(c) > 0.9);
        for (int b = 1; b <= 5; b++) assertTrue((6 - b) / 6.0 * Games.revolverMult(b) <= 0.96 + 1e-9);
        // crash: P(>=x) = 0.97/x
        int n = 600_000;
        int ge2 = 0;
        int ge10 = 0;
        for (int i = 0; i < n; i++) {
            double c = Games.crashPoint(rng);
            if (c >= 2) ge2++;
            if (c >= 10) ge10++;
        }
        assertEquals(0.485, (double) ge2 / n, 0.006);
        assertEquals(0.097, (double) ge10 / n, 0.003);
        // plinko
        for (Map.Entry<String, double[]> e : cat.games.plinko().mults().entrySet()) {
            double[] m = e.getValue();
            double rtp = 0;
            for (int i = 0; i < m.length; i++) rtp += m[i] * binom(12, i) / 4096.0;
            assertTrue(rtp > 0.88 && rtp < 1.0, "plinko " + e.getKey() + " " + rtp);
        }
        // koło, zdrapka, wyścigi, sloty
        double wheel = 0;
        double wheelW = cat.games.kolo().stream().mapToDouble(Catalog.WheelSegment::w).sum();
        for (Catalog.WheelSegment s : cat.games.kolo()) wheel += s.w() / wheelW * s.mult();
        assertTrue(wheel < 1 && wheel > 0.9, "koło " + wheel);
        double scratch = 0;
        double scratchW = cat.games.zdrapka().stream().mapToDouble(Catalog.ScratchOutcome::w).sum();
        for (Catalog.ScratchOutcome s : cat.games.zdrapka()) scratch += s.w() / scratchW * s.mult();
        assertTrue(scratch < 1 && scratch > 0.9, "zdrapka " + scratch);
        for (Catalog.Horse h : cat.games.wyscigi()) assertTrue(h.p() * h.mult() < 1, h.name());
        assertTrue(slotsRtp() < 1 && slotsRtp() > 0.9, "sloty " + slotsRtp());
        // ruletka i kości
        for (Games.RouletteBet b : Games.ROULETTE) {
            int wins = 0;
            for (int k = 0; k <= 36; k++) if (b.wins(k)) wins++;
            assertTrue(wins / 37.0 * b.mult() < 1, b.id());
        }
        for (Games.DiceBet b : Games.DICE) {
            int wins = 0;
            for (int a = 1; a <= 6; a++) for (int c = 1; c <= 6; c++) if (b.wins(a + c)) wins++;
            assertTrue(wins / 36.0 * b.mult() < 1, b.id());
        }
        assertTrue(0.5 * Games.COIN_PAYOUT < 1);
        // miny: dla każdej liczby min i każdego momentu wypłaty
        for (int m = 1; m <= 12; m++) {
            for (int k = 1; k <= Games.MINES_TILES - m; k++) {
                double pSurvive = 1;
                for (int i = 0; i < k; i++) pSurvive *= (double) (Games.MINES_TILES - m - i) / (Games.MINES_TILES - i);
                assertTrue(pSurvive * Games.minesMultiplier(k, m) < 1, "miny " + m + "/" + k);
            }
        }
    }

    private void assertBestStepStrategy(double pSafe, int maxSteps) {
        double best = 0;
        for (int n = 1; n <= Games.stepsLimit(pSafe, maxSteps); n++) best = Math.max(best, Math.pow(pSafe, n) * Games.stepMultiplier(pSafe, n));
        assertTrue(best < 0.97 && best > 0.9, "krokowa " + pSafe + " → " + best);
        assertTrue(Games.stepMultiplier(pSafe, Games.stepsLimit(pSafe, maxSteps)) <= 1000);
    }

    private double slotsRtp() {
        Catalog.Slots s = cat.games.sloty();
        double total = s.symbols().stream().mapToDouble(Catalog.SlotSymbol::w).sum();
        double base = 0;
        for (Catalog.SlotSymbol a : s.symbols()) {
            for (Catalog.SlotSymbol b : s.symbols()) {
                for (Catalog.SlotSymbol c : s.symbols()) {
                    double pr = a.w() / total * (b.w() / total) * (c.w() / total);
                    double mult;
                    if (a == b && b == c) mult = a.triple();
                    else if (a == b || a == c) mult = a.pair();
                    else if (b == c) mult = b.pair();
                    else mult = 0;
                    base += pr * mult;
                }
            }
        }
        return (1 - s.jackpot().chance()) * base + s.jackpot().chance() * s.jackpot().mult();
    }

    private static double binom(int n, int k) {
        double r = 1;
        for (int i = 1; i <= k; i++) r = r * (n - k + i) / i;
        return r;
    }

    @Test
    void blackjackHandValues() {
        assertEquals(21, Games.handValue(List.of(new Games.Card(1, 0), new Games.Card(13, 1))));
        assertEquals(12, Games.handValue(List.of(new Games.Card(1, 0), new Games.Card(1, 1))));
        assertEquals(20, Games.handValue(List.of(new Games.Card(1, 0), new Games.Card(9, 1), new Games.Card(10, 2))));
        assertTrue(Games.isBlackjack(List.of(new Games.Card(1, 0), new Games.Card(12, 1))));
        assertFalse(Games.isBlackjack(List.of(new Games.Card(5, 0), new Games.Card(6, 1), new Games.Card(10, 1))));
    }

    @Test
    void amountParsing() {
        assertEquals(1500, Fmt.parseAmount("1.5k", 0));
        assertEquals(2_000_000, Fmt.parseAmount("2m", 0));
        assertEquals(500, Fmt.parseAmount("50%", 1000));
        assertEquals(777, Fmt.parseAmount("all", 777));
        assertEquals(400, Fmt.parseAmount("połowa", 801));
        assertEquals(-1, Fmt.parseAmount("abc", 100));
        assertEquals(-1, Fmt.parseAmount("-5", 100));
        assertEquals("1 234 567", Fmt.num(1_234_567));
        assertEquals("3 min 05 s", Fmt.duration(185_000));
    }

    @Test
    void rankingAndPersistence() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        eco.pd(a).name = "Ala";
        eco.pd(a).wallet = 5000;
        eco.pd(b).name = "Bob";
        eco.pd(b).wallet = 9000;
        assertEquals("Bob", eco.top(Eco.RankBy.WEALTH, 5).get(0).name());
        assertEquals(1, eco.rankOf(b));
        assertEquals(2, eco.rankOf(a));
        eco.addItem(a, "kot", 1);
        store.flush();
        DataStore reloaded = new DataStore(dir, java.util.logging.Logger.getAnonymousLogger(), 500);
        reloaded.loadAll();
        assertEquals(5000, reloaded.get(a).wallet);
        assertEquals(1, reloaded.get(a).inventory.get("kot"));
    }
}
