package pl.kasyno.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * Napad na bank w pięciu etapach: wejście, ochrona i kamery, skarbiec, łup, ucieczka. Na każdym etapie dzieje się jedno losowe
 * zdarzenie: nic się nie dzieje, ktoś z ekipy wpada, alarm kładzie całą ekipę albo trafia się dodatkowy łup. Cała akcja jest
 * wyliczona z góry jako scenariusz (lista scen), a okno i dźwięki tylko go odtwarzają, więc pieniądze rozlicza się od razu.
 *
 * <p>Łup to wpisowe całej ekipy razy mnożnik (baza plus dodatki ze zdarzeń). Dzielą go ci, którzy dotrwali do końca, więc
 * złapani tracą stawkę, a ich koledzy zyskują. Małe ekipy mają mniej wpadek (zob. bustScale), a zwrot dla każdej wielkości ekipy
 * jest poniżej 100% (liczy to test).
 */
public final class Heist {
    private Heist() {}

    public enum Kind { CLEAN, BONUS, BUST, ALARM }

    /** Etap napadu (nazwa do pokazania w oknie). */
    public record Stage(String id, String name) {}

    public static final List<Stage> STAGES = List.of(new Stage("wejscie", "Wejście"), new Stage("kamery", "Ochrona i kamery"), new Stage("skarbiec", "Skarbiec"),
            new Stage("lup", "Łup"), new Stage("ucieczka", "Ucieczka"));

    /** Mnożnik łupu przed dodatkami ze zdarzeń. */
    public static final double BASE_MULT = 1.2;

    private record Event(Kind kind, int bust, double bonus, int weight, String[] texts) {}

    private static Event clean(int w, String... t) {
        return new Event(Kind.CLEAN, 0, 0, w, t);
    }

    private static Event bonus(int w, double b, String... t) {
        return new Event(Kind.BONUS, 0, b, w, t);
    }

    private static Event bust(int w, int k, String... t) {
        return new Event(Kind.BUST, k, 0, w, t);
    }

    private static Event alarm(int w, String... t) {
        return new Event(Kind.ALARM, 0, 0, w, t);
    }

    // {0} i {1} to nicki złapanych osób
    private static final List<List<Event>> TABLE = List.of(
            List.of(
                    clean(62, "Boczne drzwi były otwarte. Wchodzicie bez hałasu.", "Wentylacja okazała się wystarczająco szeroka. Jesteście w środku.", "Ochroniarz akurat poszedł na kawę. Wchodzicie głównym wejściem."),
                    bonus(12, 0.10, "Dozorca zostawił klucz w zamku. Wchodzicie od razu i macie czas na więcej.", "Plan budynku leżał w śmietniku. Idzie jak z płatka."),
                    bust(22, 1, "{0} potknął się o wiadro i ochroniarz go złapał!", "{0} został zauważony przy drzwiach. Zgarnęła go ochrona!"),
                    alarm(4, "Cichy alarm! Policja jest już w drodze.", "Czujnik przy drzwiach zawył. Wszyscy w nogi!")),
            List.of(
                    clean(50, "Kamery patrzą w drugą stronę. Przemykacie korytarzem.", "Zakleiliście obiektywy taśmą. Czysto.", "Patrol minął Was o krok. Idziecie dalej."),
                    bonus(8, 0.25, "Haker z ekipy puścił kamerom pętlę. Macie więcej czasu na łup!", "Przejęliście monitoring. Bank jest Wasz."),
                    bust(26, 1, "Kamera nagrała {0}. Ochrona go zgarnęła!", "{0} wpadł prosto na patrol."),
                    bust(8, 2, "Patrol wpadł na {0} i {1}. Obaj trafiają w kajdanki!", "{0} i {1} spanikowali przy drzwiach z czytnikiem. Złapani!"),
                    alarm(8, "Ktoś nadepnął na czujnik. Syreny wyją w całym budynku!", "Włączył się alarm przeciwwłamaniowy!")),
            List.of(
                    clean(54, "Wiertło idzie jak po maśle. Drzwi skarbca ustępują.", "Kod z notatnika dozorcy pasuje. Skarbiec otwarty.", "Ładunek wybucha cicho, jak trzeba. Drzwi skarbca puszczają."),
                    bonus(22, 0.30, "Skarbiec miał dodatkowy depozyt. Jest więcej, niż planowaliście!", "Za drzwiami czeka sejf z klejnotami!"),
                    bust(14, 1, "{0} został w skarbcu, gdy zatrzasnęły się drzwi.", "{0} dotknął nie tej dźwigni i wpadł w pułapkę."),
                    alarm(10, "Ładunek wybuchł za mocno. Cały bank wie, że tu jesteście!", "Wiertło przebiło rurę z alarmem. Syreny!")),
            List.of(
                    clean(50, "Pakujecie gotówkę do toreb. Idzie sprawnie.", "Banknoty lecą do toreb jeden po drugim."),
                    bonus(36, 0.25, "W sejfie leży sztabka złota! Torby ledwo się domykają.", "Znaleźliście kasetę z biżuterią. Pakujecie wszystko!"),
                    bust(14, 1, "{0} chciał jeszcze jedną paczkę i dał się złapać.", "{0} zahaczył o laserową siatkę. Złapany!")),
            List.of(
                    clean(48, "Wskakujecie do samochodu i znikacie w tunelu.", "Kierowca dociska gaz. Policja zostaje w tyle."),
                    bust(28, 1, "Policja zablokowała drogę. {0} nie zdążył wsiąść.", "{0} wypadł z auta na zakręcie. Radiowóz już przy nim."),
                    bust(10, 2, "Radiowóz wjechał w auto. {0} i {1} zostali złapani.", "Zasadzka na rondzie. {0} i {1} nie uciekli."),
                    alarm(14, "Blokada drogowa! Nikt nie ucieka.", "Helikopter policyjny namierzył auto. To koniec!")));

    /** Jak bardzo wpadki są rzadsze w małych ekipach (1 = pełna częstość od 7 osób). Brakujące szanse dostaje „nic się nie dzieje”. */
    static double bustScale(int crew) {
        return Math.min(1.0, 0.35 + 0.13 * (crew - 2));
    }

    /** Jedna scena scenariusza: co się stało na danym etapie. */
    public record Beat(int stage, Kind kind, String text, List<UUID> caught, double multiplier, int alive, boolean crewDown) {}

    /** Cały napad: sceny, ci, którzy dotarli do końca, i ci, którzy wpadli po drodze. */
    public record Result(List<Beat> beats, List<UUID> survivors, List<UUID> caught, double multiplier) {
        public boolean success() {
            return !survivors.isEmpty();
        }
    }

    private static Event pick(List<Event> events, int crew, RandomGenerator rng) {
        double scale = bustScale(crew);
        double[] w = new double[events.size()];
        double removed = 0;
        int cleanIdx = -1;
        for (int i = 0; i < w.length; i++) {
            Event e = events.get(i);
            w[i] = e.weight();
            if (e.kind() == Kind.BUST) {
                w[i] = e.weight() * scale;
                removed += e.weight() - w[i];
            }
            if (e.kind() == Kind.CLEAN) cleanIdx = i;
        }
        if (cleanIdx >= 0) w[cleanIdx] += removed;
        double total = 0;
        for (double x : w) total += x;
        double r = rng.nextDouble() * total;
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r < 0) return events.get(i);
        }
        return events.get(events.size() - 1);
    }

    private static String fill(String template, List<UUID> caught, Function<UUID, String> name) {
        String out = template;
        for (int i = 0; i < caught.size(); i++) out = out.replace("{" + i + "}", name.apply(caught.get(i)));
        return out;
    }

    /** Rozgrywa napad ekipy. Wywołujący płaci i dzieli łup (loot = pot * multiplier, po równo między ocalałych). */
    public static Result run(List<UUID> crew, Function<UUID, String> name, RandomGenerator rng) {
        List<UUID> alive = new ArrayList<>(crew);
        List<UUID> caughtAll = new ArrayList<>();
        List<Beat> beats = new ArrayList<>();
        double mult = BASE_MULT;
        for (int s = 0; s < STAGES.size() && !alive.isEmpty(); s++) {
            Event e = pick(TABLE.get(s), crew.size(), rng);
            List<UUID> caught = new ArrayList<>();
            switch (e.kind()) {
                case BONUS -> mult += e.bonus();
                case BUST -> {
                    for (int k = 0; k < e.bust() && !alive.isEmpty(); k++) caught.add(alive.remove(rng.nextInt(alive.size())));
                }
                case ALARM -> {
                    caught.addAll(alive);
                    alive.clear();
                }
                default -> {
                }
            }
            caughtAll.addAll(caught);
            String template = e.texts()[rng.nextInt(e.texts().length)];
            // dwie osoby w opisie, a wpadła jedna (został tylko jeden w ekipie): bierzemy opis dla jednej osoby
            if (e.kind() == Kind.BUST && e.bust() == 2 && caught.size() < 2) template = "{0} nie zdążył uciec. Złapany!";
            beats.add(new Beat(s, e.kind(), fill(template, caught, name), caught, mult, alive.size(), alive.isEmpty()));
        }
        return new Result(beats, alive, caughtAll, alive.isEmpty() ? 0 : mult);
    }
}
