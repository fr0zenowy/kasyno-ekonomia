package pl.kasyno.games;

import pl.kasyno.core.Heist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Klatki animacji napadu: z gotowego scenariusza (Heist.Result) robi film po komórkach okna. Mapa banku to siatka 7 x 5
 * (kolumny 1-7), a po bokach (kolumny 0 i 8) stoją portrety ekipy:
 *
 * <pre>
 *  r0:  [. kam V V V kam .]     skarbiec na górze, w środku
 *  r1:  [.  .  . ł .  .  .]     tył holu
 *  r2:  [k  .  .  .  .  . k]    hol z kamerami po bokach
 *  r3:  [.  .  . D .  .  .]     drzwi banku
 *  r4:  [.  .  . . . . . .]     ulica: van podjeżdża z lewej i odjeżdża w prawo
 * </pre>
 *
 * Ekipa idzie jednym pionem: ulica, drzwi, hol, drzwi skarbca, i z powrotem do vana. Po drodze pojawiają się ochroniarze, wiązki
 * kamer, iskry z wiertła, alarm (cała mapa miga na czerwono), kajdanki i radiowóz. Klatki są danymi (bez Bukkita), a okno je tylko
 * rysuje, więc scenę da się testować i odtwarza się tak samo u każdego gracza.
 */
public final class HeistFrames {
    private HeistFrames() {}

    public enum Prop { VAN, DOOR_CLOSED, DOOR_OPEN, CAMERA, BEAM, GUARD, POLICE, VAULT_CLOSED, VAULT_DRILL, VAULT_OPEN, GOLD, SPARK, CUFFS, SIREN }

    /** Dźwięk albo efekt klatki. */
    public enum Cue { NONE, ENGINE, STEP, DOOR, CAMERA, DRILL, BOOM, BELL, COIN, CUFF, GUARD, ESCAPE }

    /**
     * Jedna klatka: ile ticków trwa, gdzie stoi ekipa (token, -1 = nikt), ilu ich zostało, które portrety są „złapane”
     * (maska bitowa po indeksie w ekipie), co stoi na mapie, czy mapa miga (1 = czerwony, 2 = złoty), mnożnik łupu, dźwięk, narracja,
     * kto właśnie wpadł (indeksy) i ewentualny tytuł na ekran.
     */
    public record Frame(int ticks, int token, int alive, int caught, Map<Integer, Prop> props, int flash, double mult, Cue cue, String text, int[] newly, String title) {}

    /** Sloty portretów ekipy: dwie skrajne kolumny okna (do 10 osób). */
    public static final int[] ROSTER = {0, 9, 18, 27, 36, 8, 17, 26, 35, 44};

    public static final int VAN_START = 37;
    public static final int VAN_STOP = 39;
    public static final int STREET = 40;
    public static final int DOOR = 31;
    public static final int HALL1 = 22;
    public static final int HALL2 = 13;
    public static final int VAULT = 4;
    public static final int EXIT = 43;
    public static final int CAM_LEFT = 19;
    public static final int CAM_RIGHT = 25;

    private static final int[] NO_ONE = new int[0];

    private static Map<Integer, Prop> m(Object... kv) {
        Map<Integer, Prop> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put((Integer) kv[i], (Prop) kv[i + 1]);
        return out;
    }

    private static int bits(int[] idx) {
        int b = 0;
        for (int i : idx) b |= 1 << i;
        return b;
    }

    private static final class Builder {
        final List<Frame> out = new ArrayList<>();
        final Map<Integer, Prop> fixed = new LinkedHashMap<>();
        final List<UUID> crew;
        int token = -1;
        int alive;
        int caught;
        double mult = Heist.BASE_MULT;
        String text = "";

        Builder(List<UUID> crew) {
            this.crew = crew;
            this.alive = crew.size();
            fixed.put(DOOR, Prop.DOOR_CLOSED);
            fixed.put(VAULT, Prop.VAULT_CLOSED);
            fixed.put(CAM_LEFT, Prop.CAMERA);
            fixed.put(CAM_RIGHT, Prop.CAMERA);
        }

        void frame(int ticks, Cue cue, Map<Integer, Prop> extra) {
            frame(ticks, cue, 0, NO_ONE, null, extra);
        }

        void frame(int ticks, Cue cue, int flash, int[] newly, String title, Map<Integer, Prop> extra) {
            Map<Integer, Prop> props = new LinkedHashMap<>(fixed);
            props.putAll(extra);
            out.add(new Frame(ticks, token, alive, caught, props, flash, mult, cue, text, newly, title));
        }

        int[] indices(List<UUID> who) {
            int[] idx = new int[who.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = crew.indexOf(who.get(i));
            return idx;
        }

        /** Zdarzenie etapu rozgrywa się przy ekipie stojącej w komórce t. */
        void event(Heist.Beat b, int t) {
            int[] newly = indices(b.caught());
            text = b.text();
            switch (b.kind()) {
                case CLEAN -> frame(9, Cue.NONE, m());
                case BONUS -> {
                    mult = b.multiplier();
                    frame(7, Cue.COIN, 2, NO_ONE, null, m(t, Prop.SPARK));
                    frame(7, Cue.COIN, 0, NO_ONE, null, m(t, Prop.SPARK));
                }
                case BUST -> {
                    boolean street = t == STREET;
                    Prop who = street ? Prop.POLICE : Prop.GUARD;
                    int g = street ? t + 1 : t - 1;
                    int far = street ? t + 2 : t - 2;
                    frame(5, Cue.GUARD, m(far, who));
                    frame(5, Cue.GUARD, m(g, who));
                    caught |= bits(newly);
                    alive = b.alive();
                    if (alive == 0) token = -1;
                    frame(9, Cue.CUFF, 1, newly, null, m(t, Prop.CUFFS, g, who));
                    frame(6, Cue.NONE, m(far, who));
                }
                case ALARM -> {
                    for (int i = 0; i < 4; i++) frame(5, Cue.BELL, i % 2 == 0 ? 1 : 0, NO_ONE, i == 0 ? "<red><bold>ALARM!" : null, m(t - 1, Prop.SIREN, t + 1, Prop.SIREN));
                    caught |= bits(newly);
                    alive = 0;
                    token = -1;
                    frame(10, Cue.CUFF, 1, newly, null, m(t, Prop.CUFFS));
                }
            }
        }
    }

    /** Zamienia scenariusz napadu na listę klatek (kolejność uczestników jak w `crew`). */
    public static List<Frame> build(Heist.Result r, List<UUID> crew) {
        Builder b = new Builder(crew);

        // wstęp: van podjeżdża pod bank, ekipa wysiada
        b.text = "Podjeżdżacie pod bank. Czarny van, kominiarki, plan na kartce...";
        for (int s = VAN_START; s <= VAN_STOP; s++) b.frame(5, s == VAN_START ? Cue.ENGINE : Cue.NONE, m(s, Prop.VAN));
        b.fixed.put(VAN_STOP, Prop.VAN);
        b.token = STREET;
        b.frame(7, Cue.STEP, m());

        for (Heist.Beat beat : r.beats()) {
            switch (beat.stage()) {
                case 0 -> {
                    b.text = "Wejście: podchodzicie do drzwi banku...";
                    b.fixed.put(DOOR, Prop.DOOR_OPEN);
                    b.frame(6, Cue.DOOR, m());
                    b.token = DOOR;
                    b.frame(6, Cue.STEP, m());
                    b.event(beat, DOOR);
                }
                case 1 -> {
                    b.text = "Ochrona i kamery: cicho przez hol...";
                    b.token = HALL1;
                    b.frame(6, Cue.STEP, m());
                    b.frame(5, Cue.CAMERA, m(20, Prop.BEAM, 21, Prop.BEAM));
                    b.frame(5, Cue.CAMERA, m(24, Prop.BEAM, 23, Prop.BEAM));
                    b.event(beat, HALL1);
                    if (b.alive > 0) {
                        b.token = HALL2;
                        b.frame(6, Cue.STEP, m());
                    }
                }
                case 2 -> {
                    b.text = "Skarbiec: wiertło idzie w ruch...";
                    b.frame(6, Cue.DRILL, m(VAULT, Prop.VAULT_DRILL, 3, Prop.SPARK));
                    b.frame(6, Cue.DRILL, m(5, Prop.SPARK));
                    b.frame(6, Cue.DRILL, m(VAULT, Prop.VAULT_DRILL, 3, Prop.SPARK, 5, Prop.SPARK));
                    b.event(beat, HALL2);
                    if (b.alive > 0) {
                        b.text = "Drzwi skarbca puszczają. W środku złoto!";
                        b.fixed.put(VAULT, Prop.VAULT_OPEN);
                        b.fixed.put(3, Prop.GOLD);
                        b.fixed.put(5, Prop.GOLD);
                        b.frame(8, Cue.DOOR, m());
                    }
                }
                case 3 -> {
                    b.text = "Łup: pakujecie torby...";
                    b.token = VAULT;
                    b.frame(6, Cue.STEP, m());
                    if (beat.kind() == Heist.Kind.BONUS) {
                        b.fixed.put(12, Prop.GOLD);
                        b.fixed.put(14, Prop.GOLD);
                    }
                    b.event(beat, VAULT);
                    if (b.alive > 0) {
                        for (int cell : new int[]{3, 5, 12, 14}) {
                            if (b.fixed.get(cell) != Prop.GOLD) continue;
                            b.fixed.remove(cell);
                            b.frame(6, Cue.COIN, m());
                        }
                    }
                }
                default -> {
                    b.text = "Ucieczka: z powrotem do vana!";
                    for (int cell : new int[]{HALL2, HALL1, DOOR, STREET}) {
                        b.token = cell;
                        b.frame(6, Cue.STEP, m());
                    }
                    b.event(beat, STREET);
                    if (b.alive > 0) {
                        // ekipa wsiada, van odjeżdża w prawo
                        b.token = -1;
                        b.fixed.remove(VAN_STOP);
                        b.text = "Wskakujecie do vana. Gaz do dechy!";
                        for (int s = VAN_STOP; s <= EXIT; s++) b.frame(5, Cue.ENGINE, m(s, Prop.VAN));
                    }
                }
            }
            if (b.alive == 0) break;
        }

        if (b.alive > 0) {
            b.text = "Uciekliście z łupem!";
            b.frame(14, Cue.ESCAPE, 2, NO_ONE, null, m());
        } else {
            b.text = "Koniec akcji. Policja przejmuje bank.";
            b.frame(14, Cue.NONE, m());
        }
        return b.out;
    }

    /** Łączny czas filmu w tickach. */
    public static int totalTicks(List<Frame> frames) {
        int t = 0;
        for (Frame f : frames) t += f.ticks();
        return t;
    }
}
