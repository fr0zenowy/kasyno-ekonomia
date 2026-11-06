package pl.kasyno.work;

import pl.kasyno.work.JobDef.Kind;
import pl.kasyno.work.JobDef.NodeType;

import java.util.List;

/**
 * Lista prac. Wypłaty są tak dobrane, żeby runda opłacała się lepiej niż klikanie w menu (trzeba ruszyć nogami), ale bez
 * przesady: ok. 400-900 $ za rundę co kilka minut. Mnożniki z przedmiotów (karty VIP, zwierzaki, eliksiry) działają jak przy
 * innych zarobkach, a narzędzia ze sklepu (kilof, wędka, kosz) zwiększają szansę na rzadkie cele.
 */
public final class Jobs {
    private Jobs() {}

    private static NodeType t(String id, String name, String icon, int w, boolean rare, long min, long max, String glow) {
        return new NodeType(id, name, icon, w, rare, min, max, glow);
    }

    public static final List<JobDef> ALL = List.of(
            new JobDef("lesnik", "Leśniczy", "Grzybobranie", "e_forest", Kind.COLLECT, 10, 10, 40, 150, 150_000, "las", 0.20,
                    "W lesie rosną grzyby. Zerwij je, zanim wyschną! Świecą, gdy tylko je zobaczysz.",
                    List.of("Kurki są pospolite, borowiki lepsze, a trufle i złote grzyby to prawdziwe skarby.", "Kosz ze sklepu zwiększa szansę na rzadkie grzyby."),
                    List.of(t("kurka", "Kurka", "BROWN_MUSHROOM", 60, false, 20, 40, "#e0a040"),
                            t("borowik", "Borowik", "RED_MUSHROOM", 28, false, 50, 80, "#ff4040"),
                            t("trufla", "Trufla", "CRIMSON_FUNGUS", 10, true, 150, 220, "#c040ff"),
                            t("zloty", "Złoty grzyb", "GOLDEN_CARROT", 2, true, 600, 900, "#ffd700"))),
            new JobDef("gornik", "Kierownik kopalni", "Kopalnia", "m_zarobki", Kind.COLLECT, 10, 10, 40, 150, 180_000, "kopalnia", 0.20,
                    "W okolicy odsłoniły się żyły rudy. Wykop je, zanim zawali się szyb!",
                    List.of("Potrzebujesz kilofa ze sklepu. Lepszy kilof to większa szansa na diamenty i szmaragdy."),
                    List.of(t("wegiel", "Węgiel", "COAL", 45, false, 15, 30, "#6a6a6a"),
                            t("zelazo", "Żelazo", "RAW_IRON", 30, false, 35, 60, "#d8c0a8"),
                            t("zloto", "Złoto", "RAW_GOLD", 15, true, 80, 120, "#ffd700"),
                            t("diament", "Diament", "DIAMOND", 7, true, 200, 300, "#40e0ff"),
                            t("szmaragd", "Szmaragd", "EMERALD", 3, true, 400, 600, "#40ff80"))),
            new JobDef("rybak", "Rybak", "Łowienie ryb", "e_fish", Kind.WATER, 5, 12, 40, 150, 150_000, "ryby", 0.20,
                    "Ryby pływają tuż pod powierzchnią i znikają po kilkunastu sekundach. Klikaj, zanim uciekną!",
                    List.of("Potrzebujesz wędki ze sklepu. Lepsza wędka to więcej szczupaków, sumów i rekinów.", "Potrzebna jest woda w promieniu pracy (staw, jezioro, rzeka)."),
                    List.of(t("sledz", "Śledź", "COD", 40, false, 10, 20, "#c0d0e0"),
                            t("karp", "Karp", "SALMON", 30, false, 25, 45, "#ff9060"),
                            t("szczupak", "Szczupak", "TROPICAL_FISH", 17, true, 60, 90, "#40c0ff"),
                            t("sum", "Sum", "PUFFERFISH", 9, true, 110, 160, "#c0ff40"),
                            t("tunczyk", "Tuńczyk", "HEART_OF_THE_SEA", 3, true, 200, 260, "#4060ff"),
                            t("rekin", "Rekin", "PRISMARINE_SHARD", 1, true, 600, 900, "#ff4040"))),
            new JobDef("sprzatacz", "Dozorca parku", "Sprzątanie", "e_trash", Kind.COLLECT, 12, 12, 30, 120, 150_000, null, 0.25,
                    "Ktoś zostawił po sobie bałagan. Pozbieraj śmieci, a przy okazji może znajdziesz coś cennego.",
                    List.of("Praca bez narzędzi: każdy może zacząć.", "Czasem w śmieciach leży telefon, zegarek albo obrączka."),
                    List.of(t("puszka", "Puszka", "IRON_NUGGET", 40, false, 8, 15, "#c0c0c0"),
                            t("butelka", "Butelka", "GLASS_BOTTLE", 35, false, 15, 25, "#80c0ff"),
                            t("telefon", "Stary telefon", "COMPARATOR", 12, true, 80, 120, "#ff80c0"),
                            t("zegarek", "Zepsuty zegarek", "CLOCK", 9, true, 100, 150, "#ffc040"),
                            t("obraczka", "Złota obrączka", "GOLD_NUGGET", 4, true, 400, 600, "#ffd700"))),
            new JobDef("kurier", "Kierownik poczty", "Dostawa paczek", "m_ekwipunek", Kind.ROUTE, 1, 3, 70, 180, 180_000, null, 0.30,
                    "Masz trzy paczki do dostarczenia. Kolejny adres pokazuje się, gdy odbierzesz poprzednie potwierdzenie. Im szybciej, tym większy napiwek!",
                    List.of("Pasek akcji pokazuje strzałkę i odległość do adresu.", "Podejdź do świecącej paczki i kliknij ją prawym przyciskiem."),
                    List.of(t("paczka", "Paczka", "CHEST", 1, false, 110, 170, "#40ff80"))),
            new JobDef("poszukiwacz", "Poszukiwacz skarbów", "Poszukiwanie skarbu", "m_skrzynie", Kind.HUNT, 1, 1, 70, 240, 300_000, null, 0.0,
                    "Gdzieś w okolicy zakopano skarb. Pasek akcji podpowie „ciepło” albo „zimno”. Gdy będziesz blisko, skrzynia zacznie świecić.",
                    List.of("Pasek akcji pokazuje, czy robi się cieplej, czy zimniej. Zmieniaj kierunek, aż zacznie parzyć.", "Skarb to pieniądze, a czasem także skrzynka."),
                    List.of(t("skarb", "Skarb", "CHEST", 1, true, 500, 900, "#ffd700"))));

    public static JobDef find(String id) {
        for (JobDef j : ALL) if (j.id().equals(id)) return j;
        return null;
    }

    public static boolean isJob(String id) {
        return find(id) != null;
    }

    public static List<String> ids() {
        return ALL.stream().map(JobDef::id).toList();
    }
}
