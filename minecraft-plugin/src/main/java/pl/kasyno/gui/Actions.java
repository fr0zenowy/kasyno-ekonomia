package pl.kasyno.gui;

import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Earn;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Gather;
import pl.kasyno.prison.PrisonRules;

import java.util.ArrayList;
import java.util.List;

/**
 * Akcje zarobkowe wykonywane jednym kliknięciem albo komendą (/daily, /praca, /ryby...): liczą wynik, wysyłają go na czat,
 * dodają dźwięk i zwracają linie do pokazania w GUI. Zwracają null, gdy akcja jest jeszcze na cooldownie (komunikat już poszedł).
 */
public final class Actions {
    private Actions() {}

    public static final List<String> CLAIMS = List.of("daily", "weekly", "godzinna", "praca", "zebraj", "uliczny", "przestepstwo");

    private static List<String> done(Player p, List<String> lines, Eco.LevelUp lv) {
        for (String l : lines) T.raw(p, "<dark_gray>[<gold>Kasyno</gold>] " + l);
        Notify.levelUp(p, lv);
        return lines;
    }

    private static List<String> notes(List<String> notes, String color) {
        List<String> out = new ArrayList<>();
        for (String n : notes) out.add(color + T.esc(n));
        return out;
    }

    public static List<String> claim(KasynoPlugin plugin, Player p, String what) {
        Earn earn = plugin.earn();
        java.util.UUID id = p.getUniqueId();
        switch (what) {
            case "daily" -> {
                Earn.Reward r = earn.daily(id);
                if (r.onCooldown()) return cooldown(p, r.left());
                Fx.coin(p);
                List<String> l = new ArrayList<>(List.of("<green>Nagroda dzienna: <gold>" + Fmt.money(r.amount()), "<gray>Odsetki z banku: <gold>" + Fmt.money(r.interest()), "<gray>Seria: <white>" + r.streak() + " dni"));
                l.addAll(notes(r.notes(), "<yellow>"));
                return done(p, l, r.lv());
            }
            case "weekly" -> {
                Earn.Reward r = earn.weekly(id);
                if (r.onCooldown()) return cooldown(p, r.left());
                Fx.coin(p);
                return done(p, List.of("<green>Nagroda tygodniowa: <gold>" + Fmt.money(r.amount()), "<yellow>+ skrzynia skarbów"), r.lv());
            }
            case "godzinna" -> {
                Earn.Reward r = earn.hourly(id);
                if (r.onCooldown()) return cooldown(p, r.left());
                Fx.coin(p);
                List<String> l = new ArrayList<>(List.of("<green>Premia godzinna: <gold>" + Fmt.money(r.amount())));
                l.addAll(notes(r.notes(), "<gray>"));
                return done(p, l, r.lv());
            }
            case "praca" -> {
                Earn.Work w = earn.work(id);
                if (w.locked()) {
                    Fx.deny(p);
                    return done(p, List.of("<red>Ten zawód jest niedostępny na Twoim poziomie.", "<gray>Przywrócono Sprzątacza."), null);
                }
                if (w.left() > 0) return cooldown(p, w.left());
                Fx.coin(p);
                List<String> l = new ArrayList<>(List.of("<green>" + w.job().name() + ": <gold>" + Fmt.money(w.pay()), "<gray>" + T.esc(w.line())));
                l.addAll(notes(w.notes(), "<yellow>"));
                return done(p, l, w.lv());
            }
            case "zebraj" -> {
                Earn.Beg b = earn.beg(id);
                if (b.left() > 0) return cooldown(p, b.left());
                if (!b.success()) {
                    Fx.deny(p);
                    return done(p, List.of("<red>" + b.giver() + " udał, że Cię nie widzi.", "<gray>Dziś bez zysku."), null);
                }
                Fx.coin(p);
                List<String> l = new ArrayList<>(List.of("<green>" + b.giver() + " rzucił Ci <gold>" + Fmt.money(b.amount())));
                l.addAll(notes(b.notes(), "<gray>"));
                return done(p, l, b.lv());
            }
            case "uliczny" -> {
                Earn.Busk b = earn.busk(id);
                if (b.left() > 0) return cooldown(p, b.left());
                Fx.ding(p, 1.3f);
                String ins = b.instrument().id() == null ? "głos" : plugin.catalog().item(b.instrument().id()).name();
                List<String> l = new ArrayList<>(List.of("<green>W kapeluszu: <gold>" + Fmt.money(b.amount()), "<gray>Instrument: <white>" + ins + " <gray>(x" + b.instrument().mult() + ")"));
                l.addAll(notes(b.notes(), "<gray>"));
                return done(p, l, b.lv());
            }
            case "przestepstwo" -> {
                Earn.Crime c = earn.crime(id, plugin.prison().canJail(p));
                if (c.left() > 0) return cooldown(p, c.left());
                List<String> l = new ArrayList<>();
                if (c.success()) {
                    Fx.coin(p);
                    l.add("<green>Udało się! <gold>" + Fmt.money(c.amount()));
                } else if (c.jailed()) {
                    Fx.lose(p);
                    l.add("<red><bold>Wpadłeś!</bold> <gray>Policja zabiera Cię do więzienia.");
                    plugin.prison().jail(id, PrisonRules.Reason.CRIME);
                } else {
                    Fx.lose(p);
                    l.add("<red>Wpadka! Grzywna: <gold>" + Fmt.money(c.amount()));
                }
                l.add("<gray>" + T.esc(c.line()));
                l.addAll(notes(c.notes(), "<yellow>"));
                return done(p, l, c.lv());
            }
            default -> {
                return List.of("<red>Nieznana akcja.");
            }
        }
    }

    public static List<String> gather(KasynoPlugin plugin, Player p, String key) {
        Gather.Result r = plugin.gather().run(p.getUniqueId(), key);
        switch (r.status()) {
            case NO_TOOL -> {
                Fx.deny(p);
                return done(p, List.of("<red>Nie masz potrzebnego narzędzia.", "<gray>Kup je w sklepie (menu → Sklep)."), null);
            }
            case COOLDOWN -> {
                return cooldown(p, r.left());
            }
            case MISS -> {
                Fx.lose(p);
                List<String> l = new ArrayList<>(List.of("<red>Tym razem nic nie wyszło."));
                if (r.broke()) l.add("<red>Twoje narzędzie się zepsuło!");
                return done(p, l, r.lv());
            }
            default -> {
                Fx.coin(p);
                Catalog.Item it = plugin.catalog().item(r.item());
                List<String> l = new ArrayList<>(List.of("<green>Zdobywasz: <white>" + r.qty() + "x " + it.name(), "<gray>Wartość: <gold>" + Fmt.money(it.sellValue() * r.qty())));
                if (it.type().equals("crate")) l.add("<yellow>To skrzynia! Otworzysz ją w menu skrzyń.");
                if (r.broke()) l.add("<red>Twoje narzędzie się zepsuło!");
                return done(p, l, r.lv());
            }
        }
    }

    public static List<String> rob(KasynoPlugin plugin, Player p, Player victim) {
        Earn.Rob r = plugin.earn().rob(p.getUniqueId(), victim.getUniqueId());
        String vn = T.esc(victim.getName());
        List<String> l = new ArrayList<>();
        switch (r.status()) {
            case OK -> {
                Fx.coin(p);
                l.add("<green>Skok udany! Zabierasz <gold>" + Fmt.money(r.amount()) + "</gold> graczowi " + vn);
                T.send(victim, "<red>" + T.esc(p.getName()) + " okradł Cię na <gold>" + Fmt.money(r.amount()) + "</gold>!");
            }
            case ROBBED -> {
                Fx.lose(p);
                l.add("<red>Przyłapany! Płacisz " + vn + " <gold>" + Fmt.money(r.amount()) + "</gold>.");
                if (plugin.prison().canJail(p) && plugin.prison().jail(p.getUniqueId(), PrisonRules.Reason.ROB)) l.add("<red><bold>Policja zabiera Cię do więzienia.");
                T.send(victim, "<green>" + T.esc(p.getName()) + " próbował Cię okraść, ale zapłacił Ci <gold>" + Fmt.money(r.amount()) + "</gold>.");
            }
            case BLOCKED -> {
                Fx.lose(p);
                l.add("<red>Kłódka! Płacisz odszkodowanie <gold>" + Fmt.money(r.amount()) + "</gold>.");
                l.add("<gray>Z łomem ze sklepu przebiłbyś kłódkę.");
                T.send(victim, "<green>Twoja kłódka powstrzymała złodzieja " + T.esc(p.getName()) + ". Dostałeś <gold>" + Fmt.money(r.amount()) + "</gold>.");
            }
            case SELF -> l.add("<red>Okradanie samego siebie to dziwny pomysł.");
            case THIEF_POOR -> l.add("<red>Musisz mieć przy sobie min. 200 $, żeby mieć z czego zapłacić grzywnę.");
            case VICTIM_POOR -> l.add("<red>" + vn + " ma za mało w portfelu.");
            case VICTIM_PROTECTED -> l.add("<red>" + vn + " jest pod ochroną jeszcze " + Fmt.duration(r.left()) + ".");
            case COOLDOWN -> {
                return cooldown(p, r.left());
            }
            default -> l.add("<red>Nie da się.");
        }
        l.addAll(notes(r.notes(), "<yellow>"));
        return done(p, l, null);
    }

    // ---------- sklep i ekwipunek ----------

    private static String norm(String s) {
        return java.text.Normalizer.normalize(s.toLowerCase().replace('ł', 'l'), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^a-z0-9]", "");
    }

    /** Znajduje przedmiot po id, nazwie albo jednoznacznym początku (np. "wedka", "Super wędka", "super"). */
    public static Catalog.Item resolveItem(Catalog cat, String input, java.util.Collection<String> pool) {
        String k = norm(input == null ? "" : input);
        if (k.isEmpty()) return null;
        List<Catalog.Item> starts = new ArrayList<>();
        for (String id : pool) {
            Catalog.Item it = cat.item(id);
            if (it == null) continue;
            if (norm(id).equals(k) || norm(it.name()).equals(k)) return it;
            if (norm(id).startsWith(k) || norm(it.name()).startsWith(k)) starts.add(it);
        }
        return starts.size() == 1 ? starts.get(0) : null;
    }

    private static boolean single(Catalog.Item it) {
        return it.unique() || it.type().equals("tool") || it.type().equals("trophy");
    }

    /** Zakup przedmiotu ze sklepu. Zwraca true, gdy kupiono. */
    public static boolean buy(KasynoPlugin plugin, Player p, Catalog.Item it, int qty) {
        Eco eco = plugin.eco();
        java.util.UUID id = p.getUniqueId();
        if (single(it)) {
            qty = 1;
            if (eco.qty(id, it.id()) > 0) {
                Fx.deny(p);
                T.send(p, "<red>Już masz: " + it.name());
                return false;
            }
        }
        if (it.requires() != null && eco.qty(id, it.requires()) < 1) {
            Fx.deny(p);
            T.send(p, "<red>Najpierw kup: <white>" + plugin.catalog().item(it.requires()).name());
            return false;
        }
        long cost = it.price() * qty;
        if (!eco.take(id, cost)) {
            Fx.deny(p);
            T.send(p, "<red>Potrzebujesz <gold>" + Fmt.money(cost) + "</gold>, a masz <gold>" + Fmt.money(eco.wallet(id)) + "</gold>.");
            return false;
        }
        eco.addItem(id, it.id(), qty);
        Fx.coin(p);
        T.send(p, "<green>Kupiłeś <white>" + qty + "x " + it.name() + "</white> za <gold>" + Fmt.money(cost) + "</gold>.");
        return true;
    }

    /** Sprzedaż łupu (qty <= 0 = wszystko). */
    public static boolean sell(KasynoPlugin plugin, Player p, Catalog.Item it, int qty) {
        Eco eco = plugin.eco();
        java.util.UUID id = p.getUniqueId();
        int have = eco.qty(id, it.id());
        if (have < 1) {
            Fx.deny(p);
            T.send(p, "<red>Nie masz: " + it.name());
            return false;
        }
        int n = qty <= 0 ? have : Math.min(qty, have);
        if (!eco.takeItem(id, it.id(), n)) return false;
        long value = it.sellValue() * n;
        eco.add(id, value);
        Fx.coin(p);
        T.send(p, "<green>Sprzedano <white>" + n + "x " + it.name() + "</white> za <gold>" + Fmt.money(value) + "</gold>.");
        return true;
    }

    public static boolean sellAllLoot(KasynoPlugin plugin, Player p) {
        Eco eco = plugin.eco();
        java.util.UUID id = p.getUniqueId();
        long total = 0;
        int kinds = 0;
        for (java.util.Map.Entry<String, Integer> e : new ArrayList<>(eco.pd(id).inventory.entrySet())) {
            Catalog.Item it = plugin.catalog().item(e.getKey());
            if (it == null || !it.type().equals("loot")) continue;
            if (eco.takeItem(id, it.id(), e.getValue())) {
                total += it.sellValue() * e.getValue();
                kinds++;
            }
        }
        if (total <= 0) {
            Fx.deny(p);
            T.send(p, "<red>Nie masz nic do sprzedania.");
            return false;
        }
        eco.add(id, total);
        Fx.coin(p);
        T.send(p, "<green>Sprzedano cały łup (" + kinds + " rodzajów) za <gold>" + Fmt.money(total) + "</gold>.");
        return true;
    }

    /** Używa eliksiru, ochrony albo koniczyny. Skrzynie otwiera wywołujący (potrzebują okna). */
    public static boolean use(KasynoPlugin plugin, Player p, Catalog.Item it) {
        Eco eco = plugin.eco();
        java.util.UUID id = p.getUniqueId();
        if (eco.qty(id, it.id()) < 1) {
            Fx.deny(p);
            T.send(p, "<red>Nie masz: " + it.name());
            return false;
        }
        if (plugin.catalog().boosts.containsKey(it.id())) {
            eco.takeItem(id, it.id(), 1);
            Fx.sound(p, org.bukkit.Sound.ENTITY_GENERIC_DRINK, 1f, 1f);
            Eco.Activation a = eco.activateBoost(id, it.id());
            T.send(p, "<green>" + it.name() + " aktywny: <white>" + plugin.catalog().boosts.get(it.id()).label() + "</white> <gray>(jeszcze " + Fmt.duration(a.left()) + ")" + (a.extended() ? " <yellow>przedłużono" : ""));
            return true;
        }
        Catalog.Protection pr = plugin.catalog().protections.get(it.id());
        if (pr != null) {
            eco.takeItem(id, it.id(), 1);
            eco.addBuff(id, it.id(), 1);
            Fx.sound(p, org.bukkit.Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 1f);
            T.send(p, "<aqua>" + it.name() + " uzbrojona. <gray>" + (pr.kind().equals("polisa") ? "Zwróci stawkę po całkowitej przegranej do " : "Anuluje pierwszy błąd w grach krokowych przy stawkach do ") + "<gold>" + Fmt.money(pr.cap()));
            return true;
        }
        if (it.id().equals("koniczyna")) {
            eco.takeItem(id, "koniczyna", 1);
            eco.addBuff(id, "koniczyna", 5);
            Fx.win(p);
            T.send(p, "<green>Szczęście Ci sprzyja! Twoje następne <white>5</white> wygranych w hazardzie da więcej zysku (maks. <gold>" + Fmt.money(plugin.catalog().bonuses.cloverMax()) + "</gold> na wygraną).");
            return true;
        }
        T.send(p, "<red>Tego nie da się użyć. Użyć da się eliksiry, polisy, tarcze, koniczynę i skrzynie.");
        return false;
    }

    public static boolean usable(KasynoPlugin plugin, Catalog.Item it) {
        return plugin.catalog().boosts.containsKey(it.id()) || plugin.catalog().protections.containsKey(it.id()) || it.id().equals("koniczyna") || it.type().equals("crate");
    }

    private static List<String> cooldown(Player p, long left) {
        Notify.cooldown(p, left);
        return null;
    }
}
