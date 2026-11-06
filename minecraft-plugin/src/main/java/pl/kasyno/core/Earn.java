package pl.kasyno.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Sposoby zarabiania poza hazardem: nagrody czasowe, praca, zbieractwo, uliczny grajek, przestępstwa i okradanie. */
public final class Earn {
    private final Eco eco;
    private final Catalog cat;

    public Earn(Eco eco) {
        this.eco = eco;
        this.cat = eco.cat;
    }

    /** Wynik akcji z cooldownem: left > 0 oznacza, że trzeba jeszcze poczekać (nic się nie wydarzyło). */
    public record Reward(long left, long amount, long interest, int streak, List<String> notes, Eco.LevelUp lv) {
        public boolean onCooldown() {
            return left > 0;
        }
    }

    private static Reward cooling(long left) {
        return new Reward(left, 0, 0, 0, List.of(), null);
    }

    // ---------- nagrody czasowe ----------

    public Reward daily(UUID id) {
        PlayerData d = eco.pd(id);
        long now = eco.now();
        long left = d.lastDaily + Eco.DAILY_MS - now;
        if (left > 0) return cooling(left);
        int streak = d.lastDaily != 0 && now - d.lastDaily <= 48L * 3600 * 1000 ? d.streak + 1 : 1;
        long base = 500 + Math.min(streak, 30) * 75L + d.level * 50L;
        Eco.InterestTerms terms = eco.interestTerms(id);
        long interest = Math.min((long) Math.floor(d.bank * terms.rate()), terms.cap());
        eco.add(id, base + interest);
        d.lastDaily = now;
        d.streak = streak;
        List<String> notes = new ArrayList<>();
        if (streak % 30 == 0) {
            eco.addItem(id, "skrzynia_diamentowa", 1);
            notes.add("Seria 30 dni! Skrzynia diamentowa.");
        } else if (streak % 7 == 0) {
            eco.addItem(id, "skrzynia", 1);
            notes.add("Seria 7 dni! Skrzynia skarbów.");
        }
        if (eco.chance(0.3)) {
            eco.addItem(id, "skrzynka", 1);
            notes.add("Szczęściarz! Drewniana skrzynka.");
        }
        Eco.LevelUp lv = eco.addXp(id, 20);
        eco.store.touch(id);
        return new Reward(0, base, interest, streak, notes, lv);
    }

    public Reward weekly(UUID id) {
        PlayerData d = eco.pd(id);
        long now = eco.now();
        long left = d.lastWeekly + Eco.WEEK_MS - now;
        if (left > 0) return cooling(left);
        long reward = 5000 + d.level * 250L;
        eco.add(id, reward);
        eco.addItem(id, "skrzynia", 1);
        d.lastWeekly = now;
        Eco.LevelUp lv = eco.addXp(id, 60);
        eco.store.touch(id);
        return new Reward(0, reward, 0, 0, List.of("Skrzynia skarbów w ekwipunku."), lv);
    }

    public Reward hourly(UUID id) {
        long left = eco.guardCooldown(id, "godzinna", eco.cooldownOf("godzinna"));
        if (left > 0) return cooling(left);
        int level = eco.pd(id).level;
        long base = (long) Math.floor((300 + level * 40) * (0.8 + eco.rng.nextDouble() * 0.4));
        Eco.Earned earned = eco.applyEarn(id, base);
        eco.add(id, earned.amount());
        List<String> notes = new ArrayList<>(earned.notes());
        if (eco.chance(0.05)) {
            eco.addItem(id, "skrzynka", 1);
            notes.add("W kopercie była też drewniana skrzynka!");
        }
        Eco.LevelUp lv = eco.addXp(id, eco.randInt(8, 14));
        return new Reward(0, earned.amount(), 0, 0, notes, lv);
    }

    // ---------- praca ----------

    public record Work(long left, boolean locked, long pay, List<String> notes, String line, Catalog.Job job, Eco.LevelUp lv) {}

    public Work work(UUID id) {
        PlayerData d = eco.pd(id);
        Catalog.Job job = cat.job(d.job);
        if (d.level < job.level()) {
            d.job = cat.jobs.get(0).id();
            eco.store.touch(id);
            return new Work(0, true, 0, List.of(), "", job, null);
        }
        long left = eco.guardCooldown(id, "praca", eco.cooldownOf("praca"));
        if (left > 0) return new Work(left, false, 0, List.of(), "", job, null);

        long pay = eco.randLong(job.min(), job.max());
        List<String> notes = new ArrayList<>();
        double perks = 0;
        for (var e : cat.effects.entrySet()) {
            Double v = e.getValue().get("praca");
            if (v != null && eco.qty(id, e.getKey()) > 0) {
                perks += v;
                notes.add(cat.item(e.getKey()).name() + ": +" + Math.round(v * 100) + "%");
            }
        }
        if (perks > 0) pay = (long) Math.floor(pay * (1 + perks));
        if (eco.chance(0.08)) {
            pay *= 2;
            notes.add("Premia od szefa! Podwójna wypłata.");
        } else if (eco.chance(0.08)) {
            pay = pay / 2;
            notes.add("Szef miał zły dzień. Wypłata o połowę mniejsza.");
        }
        Eco.Earned earned = eco.applyEarn(id, pay);
        notes.addAll(earned.notes());
        eco.add(id, earned.amount());
        Eco.LevelUp lv = eco.addXp(id, eco.randInt(15, 30));
        return new Work(0, false, earned.amount(), notes, eco.pick(job.lines()), job, lv);
    }

    public boolean changeJob(UUID id, String jobId) {
        Catalog.Job job = cat.jobs.stream().filter(j -> j.id().equals(jobId)).findFirst().orElse(null);
        if (job == null || eco.pd(id).level < job.level()) return false;
        eco.pd(id).job = jobId;
        eco.store.touch(id);
        return true;
    }

    // ---------- drobne zarobki ----------

    private static final List<String> GIVERS = List.of("Starsza pani", "Zabiegany biznesmen", "Student po sesji", "Zagubiony turysta",
            "Pan z psem", "Mała dziewczynka", "Kierowca autobusu", "Dziadek z gazetą", "Influencer", "Tajemniczy nieznajomy");

    public record Beg(long left, boolean success, String giver, long amount, List<String> notes, Eco.LevelUp lv) {}

    public Beg beg(UUID id) {
        long left = eco.guardCooldown(id, "zebraj", eco.cooldownOf("zebraj"));
        if (left > 0) return new Beg(left, false, "", 0, List.of(), null);
        String giver = eco.pick(GIVERS);
        if (eco.chance(0.2)) return new Beg(0, false, giver, 0, List.of(), null);
        Eco.Earned earned = eco.applyEarn(id, eco.randInt(15, 120));
        eco.add(id, earned.amount());
        return new Beg(0, true, giver, earned.amount(), earned.notes(), eco.addXp(id, eco.randInt(2, 5)));
    }

    public record Instrument(String id, double mult) {}

    public static final List<Instrument> INSTRUMENTS = List.of(new Instrument("fortepian", 4), new Instrument("skrzypce", 2.2),
            new Instrument("gitara", 1.5), new Instrument(null, 1));

    public record Busk(long left, long amount, Instrument instrument, List<String> notes, Eco.LevelUp lv) {}

    /** Grajek uliczny: napiwki zależą od instrumentu, pogody i hojnych turystów. */
    public Busk busk(UUID id) {
        long left = eco.guardCooldown(id, "uliczny", eco.cooldownOf("uliczny"));
        if (left > 0) return new Busk(left, 0, null, List.of(), null);
        Instrument ins = INSTRUMENTS.stream().filter(i -> i.id() == null || eco.qty(id, i.id()) > 0).findFirst().orElseThrow();
        long base = eco.randInt(20, 110) + eco.pd(id).level * 2L;
        List<String> notes = new ArrayList<>();
        if (eco.chance(0.1)) {
            base = (long) Math.floor(base * 0.3);
            notes.add("Zaczęło padać i ludzie się rozeszli.");
        } else if (eco.chance(0.05)) {
            base *= 3;
            notes.add("Hojny turysta! Potrójny napiwek.");
        }
        base = (long) Math.floor(base * ins.mult());
        Eco.Earned earned = eco.applyEarn(id, base);
        eco.add(id, earned.amount());
        notes.addAll(earned.notes());
        return new Busk(0, earned.amount(), ins, notes, eco.addXp(id, eco.randInt(3, 7)));
    }

    // ---------- przestępstwa i okradanie ----------

    /** Wynik przestępstwa. `jailed`: nieudane i zamiast grzywny gracz idzie do więzienia (kwota wtedy 0). */
    public record Crime(long left, boolean success, long amount, String line, List<String> notes, Eco.LevelUp lv, boolean jailed) {
        public Crime(long left, boolean success, long amount, String line, List<String> notes, Eco.LevelUp lv) {
            this(left, success, amount, line, notes, lv, false);
        }
    }

    public Crime crime(UUID id) {
        return crime(id, false);
    }

    /** Przestępstwo; przy `jail` nieudana próba kończy się więzieniem, a nie grzywną. */
    public Crime crime(UUID id, boolean jail) {
        long left = eco.guardCooldown(id, "crime", eco.cooldownOf("crime"));
        if (left > 0) return new Crime(left, false, 0, "", List.of(), null);
        PlayerData d = eco.pd(id);
        double p = 0.5;
        List<String> notes = new ArrayList<>();
        if (eco.takeItem(id, "maska", 1)) {
            p += 0.2;
            notes.add("Kominiarka zużyta (+20% szansy).");
        }
        Catalog.Crime c = eco.pick(cat.crimes);
        if (eco.chance(p)) {
            Eco.Earned earned = eco.applyEarn(id, eco.randInt(300, 1500) + d.level * 25L);
            notes.addAll(earned.notes());
            eco.add(id, earned.amount());
            return new Crime(0, true, earned.amount(), c.win(), notes, eco.addXp(id, eco.randInt(12, 25)));
        }
        if (jail) return new Crime(0, false, 0, c.lose(), notes, eco.addXp(id, 3), true);
        long fine = Math.min(d.wallet, eco.randInt(150, 700));
        eco.take(id, fine);
        return new Crime(0, false, fine, c.lose(), notes, eco.addXp(id, 3));
    }

    public enum RobStatus { OK, ROBBED, FAILED, BLOCKED, SELF, THIEF_POOR, VICTIM_POOR, VICTIM_PROTECTED, COOLDOWN }

    public record Rob(RobStatus status, long amount, long left, List<String> notes) {}

    private static final long ROB_MIN_WALLET = 200;

    public Rob rob(UUID thief, UUID victim) {
        if (thief.equals(victim)) return new Rob(RobStatus.SELF, 0, 0, List.of());
        PlayerData t = eco.pd(thief);
        PlayerData v = eco.pd(victim);
        if (t.wallet < ROB_MIN_WALLET) return new Rob(RobStatus.THIEF_POOR, 0, 0, List.of());
        if (v.wallet < ROB_MIN_WALLET) return new Rob(RobStatus.VICTIM_POOR, 0, 0, List.of());
        long safe = eco.cooldownLeft(victim, "robbed");
        if (safe > 0) return new Rob(RobStatus.VICTIM_PROTECTED, 0, safe, List.of());
        long left = eco.guardCooldown(thief, "rob", eco.cooldownOf("rob"));
        if (left > 0) return new Rob(RobStatus.COOLDOWN, 0, left, List.of());

        List<String> notes = new ArrayList<>();
        if (eco.qty(victim, "klodka") > 0) {
            if (eco.qty(thief, "lom") > 0) {
                eco.takeItem(thief, "lom", 1);
                eco.takeItem(victim, "klodka", 1);
                notes.add("Łom przebił kłódkę ofiary (łom zużyty).");
            } else {
                eco.takeItem(victim, "klodka", 1);
                long fine = Math.min(Math.max(100, (long) Math.floor(t.wallet * 0.1)), t.wallet);
                eco.transfer(thief, victim, fine);
                return new Rob(RobStatus.BLOCKED, fine, 0, notes);
            }
        }
        double p = 0.45;
        if (eco.takeItem(thief, "maska", 1)) {
            p += 0.2;
            notes.add("Kominiarka zużyta (+20% szansy).");
        }
        double guard = eco.effectSum(victim, "robDefense");
        if (guard > 0) {
            p = Math.max(0.1, p - guard);
            notes.add("Pies ofiary: -" + Math.round(guard * 100) + " pkt szansy.");
        }
        if (eco.chance(p)) {
            long stolen = Math.min(v.wallet, (long) Math.floor(v.wallet * eco.randInt(10, 35) / 100.0));
            if (!eco.transfer(victim, thief, stolen)) return new Rob(RobStatus.FAILED, 0, 0, notes);
            eco.setCooldown(victim, "robbed", eco.cooldownOf("robbed"));
            eco.addXp(thief, eco.randInt(10, 20));
            return new Rob(RobStatus.OK, stolen, 0, notes);
        }
        long fine = Math.min(t.wallet, Math.max(100, (long) Math.floor(t.wallet * eco.randInt(10, 20) / 100.0)));
        if (eco.qty(victim, "alarm") > 0) {
            fine = Math.min(t.wallet, fine * 2);
            notes.add("Alarm ofiary zawył: grzywna podwójna!");
        }
        eco.transfer(thief, victim, fine);
        return new Rob(RobStatus.ROBBED, fine, 0, notes);
    }
}
