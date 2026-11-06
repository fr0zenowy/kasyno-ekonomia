package pl.kasyno.gui;

import org.bukkit.Material;
import org.bukkit.Sound;
import pl.kasyno.gui.screens.SceneGui.Beat;

import java.util.List;
import java.util.random.RandomGenerator;

/** Scenariusze krótkich scen przed wynikiem akcji zarobkowych. Opisy mają po kilka wariantów, żeby się nie nudziły. */
public final class Scenes {
    private Scenes() {}

    private static String pick(RandomGenerator rng, String... options) {
        return options[rng.nextInt(options.length)];
    }

    /** Scenariusz dla klucza akcji (przestepstwo, okradanie, praca, zebraj, uliczny, ryby, kopalnia, polowanie, las, smietnik) albo null. */
    public static List<Beat> script(String key, RandomGenerator rng, String target) {
        return switch (key) {
            case "przestepstwo" -> List.of(
                    new Beat(Material.COMPASS, "Wybierasz cel", pick(rng, "Sklep jubilerski na rogu wygląda smakowicie.", "Kasa biletowa bez ochrony? Biorę.", "Magazyn z elektroniką, jeden stary zamek."), Sound.UI_BUTTON_CLICK),
                    new Beat(Material.SPYGLASS, "Obserwujesz okolicę", pick(rng, "Liczysz obchody ochroniarza: co siedem minut.", "Latarnia mruga, na ulicy ani żywej duszy.", "Kamera kręci się w prawo, wolno."), Sound.BLOCK_NOTE_BLOCK_HAT),
                    new Beat(Material.TRIPWIRE_HOOK, "Wchodzisz", pick(rng, "Zamek puszcza po trzecim ruchu wytrycha.", "Okno było uchylone. Za łatwo?", "Drzwi tylne skrzypią cicho."), Sound.BLOCK_IRON_DOOR_OPEN),
                    new Beat(Material.CHEST, "Chwytasz łup i uciekasz", pick(rng, "W oddali słychać syrenę...", "Kroki za rogiem. Biegniesz!", "Ktoś zapalił światło w oknie naprzeciwko."), Sound.ENTITY_ITEM_PICKUP));
            case "rob" -> List.of(
                    new Beat(Material.COMPASS, "Śledzisz " + target, pick(rng, "Idziesz kilka kroków za nim.", "Udajesz, że oglądasz wystawę.", "Trzymasz dystans trzech metrów."), Sound.UI_BUTTON_CLICK),
                    new Beat(Material.SPYGLASS, "Wybierasz moment", pick(rng, "Ktoś się potknął, wszyscy patrzą w tamtą stronę.", "Tłum gęstnieje. Teraz.", "On sprawdza telefon. Czas."), Sound.BLOCK_NOTE_BLOCK_HAT),
                    new Beat(Material.TRIPWIRE_HOOK, "Dłoń w kieszeni", pick(rng, "Zamek błyskawiczny, powoli...", "Sakiewka jest tuż-tuż.", "Palce muskają materiał."), Sound.BLOCK_WOOL_STEP),
                    new Beat(Material.GOLD_NUGGET, "Wyciągasz...", pick(rng, "Serce wali jak młot.", "Czy on coś poczuł?", "Ruch nadgarstka i po wszystkim."), Sound.ENTITY_ITEM_PICKUP));
            case "praca" -> List.of(
                    new Beat(Material.LEATHER_BOOTS, "Idziesz do pracy", pick(rng, "Poranna kawa i do roboty.", "Autobus się spóźnił, ale zdążasz.", "Szef już czeka w drzwiach."), Sound.BLOCK_WOODEN_DOOR_OPEN),
                    new Beat(Material.IRON_AXE, "Robota wre", pick(rng, "Pot na czole, tempo rośnie.", "Zadanie za zadaniem, bez przerw.", "Wszystko idzie zgodnie z planem."), Sound.BLOCK_ANVIL_USE),
                    new Beat(Material.CLOCK, "Koniec zmiany", pick(rng, "Szef kiwa głową z uznaniem.", "Czas odbić kartę.", "Kasjerka już przelicza wypłatę."), Sound.BLOCK_NOTE_BLOCK_CHIME));
            case "zebraj" -> List.of(
                    new Beat(Material.BOWL, "Siadasz na chodniku", pick(rng, "Miska przed Tobą, uśmiech na twarzy.", "Plac pełen ludzi, dobry punkt.", "Tablica: „Każdy grosz się liczy”."), Sound.BLOCK_WOOL_STEP),
                    new Beat(Material.SPYGLASS, "Wypatrujesz hojnych", pick(rng, "Pan w garniturze zwalnia kroku...", "Dzieci z babcią idą w Twoją stronę.", "Ktoś grzebie w portfelu."), Sound.BLOCK_NOTE_BLOCK_HAT),
                    new Beat(Material.GOLD_NUGGET, "Brzęk w misce", pick(rng, "Coś brzęknęło o dno.", "Czy to szelest banknotu?", "Ktoś się zatrzymał..."), Sound.ENTITY_ITEM_PICKUP));
            case "uliczny" -> List.of(
                    new Beat(Material.NOTE_BLOCK, "Rozkładasz kapelusz", pick(rng, "Plac gwarny, akustyka świetna.", "Kapelusz do góry dnem, jak przystało.", "Stroisz instrument."), Sound.BLOCK_NOTE_BLOCK_BASS),
                    new Beat(Material.JUKEBOX, "Grasz", pick(rng, "Pierwsze takty przyciągają ludzi.", "Ktoś zaczyna klaskać w rytm.", "Przechodnie zwalniają kroku."), Sound.BLOCK_NOTE_BLOCK_GUITAR),
                    new Beat(Material.GOLD_INGOT, "Tłum bije brawo", pick(rng, "Monety lecą do kapelusza!", "Ktoś nagrywa Cię telefonem.", "Bis! Bis!"), Sound.ENTITY_EXPERIENCE_ORB_PICKUP));
            case "ryby" -> List.of(
                    new Beat(Material.FISHING_ROD, "Zarzucasz wędkę", pick(rng, "Spławik ląduje z pluskiem.", "Woda gładka jak lustro.", "Ptaki zrywają się znad trzcin."), Sound.ENTITY_FISHING_BOBBER_THROW),
                    new Beat(Material.CLOCK, "Czekasz", pick(rng, "Cisza. Tylko wiatr w trzcinach.", "Spławik leniwie się kołysze.", "Termos z herbatą w dłoni."), Sound.BLOCK_NOTE_BLOCK_HAT),
                    new Beat(Material.COD, "Branie!", pick(rng, "Spławik znika pod wodą!", "Wędka wygina się w łuk!", "Coś mocno ciągnie!"), Sound.ENTITY_FISHING_BOBBER_SPLASH));
            case "kopalnia" -> List.of(
                    new Beat(Material.LANTERN, "Schodzisz do kopalni", pick(rng, "Winda skrzypi, robi się ciemno.", "Latarka oświetla mokre ściany.", "Z głębi dochodzi echo."), Sound.BLOCK_IRON_DOOR_OPEN),
                    new Beat(Material.IRON_PICKAXE, "Kilof w ruch", pick(rng, "Uderzenie za uderzeniem w skałę.", "Odłamki lecą na wszystkie strony.", "Ściana ustępuje pod kilofem."), Sound.BLOCK_STONE_BREAK),
                    new Beat(Material.DIAMOND, "Coś błysnęło", pick(rng, "W świetle latarki coś się iskrzy!", "Żyła rudy odsłania się w ścianie.", "Kilof trafia w coś twardszego."), Sound.ENTITY_EXPERIENCE_ORB_PICKUP));
            case "polowanie" -> List.of(
                    new Beat(Material.LEATHER_BOOTS, "Tropisz zwierzynę", pick(rng, "Ślady na mokrej ziemi prowadzą w głąb lasu.", "Wiatr wieje pod wiatr, dobrze.", "Gałązka trzasnęła tuż obok."), Sound.BLOCK_GRASS_STEP),
                    new Beat(Material.BOW, "Naciągasz cięciwę", pick(rng, "Oddech, cel, cisza.", "Zwierzę stoi nieruchomo.", "Cięciwa drży w palcach."), Sound.ENTITY_ARROW_SHOOT),
                    new Beat(Material.ARROW, "Strzał!", pick(rng, "Strzała gwiżdże w powietrzu.", "Pada! Trafione?", "Szelest w zaroślach."), Sound.ENTITY_ARROW_HIT));
            case "las" -> List.of(
                    new Beat(Material.OAK_SAPLING, "Wchodzisz w las", pick(rng, "Mech miękko ugina się pod butami.", "Poranna mgła wisi między pniami.", "Koszyk na ramieniu, humor dobry."), Sound.BLOCK_GRASS_STEP),
                    new Beat(Material.BROWN_MUSHROOM, "Rozglądasz się", pick(rng, "Spod liści wygląda kapelusz...", "Mijasz paprocie i poziomki.", "Gdzieś tu muszą rosnąć."), Sound.BLOCK_NOTE_BLOCK_HAT),
                    new Beat(Material.RED_MUSHROOM, "Jest coś!", pick(rng, "Pod świerkiem cały krąg grzybów!", "Duży okaz, nie ruszaj się!", "Czy to nie borowik?"), Sound.ENTITY_ITEM_PICKUP));
            case "smietnik" -> List.of(
                    new Beat(Material.BUCKET, "Podchodzisz do kontenera", pick(rng, "Zapach nie zachęca, ale kto nie ryzykuje...", "Pokrywa skrzypi.", "Rozglądasz się, czy nikt nie patrzy."), Sound.BLOCK_IRON_DOOR_OPEN),
                    new Beat(Material.STICK, "Grzebiesz", pick(rng, "Pudełka, butelki, jakieś szmaty.", "Szkło brzęczy w workach.", "Dłoń trafia na coś twardego."), Sound.BLOCK_COMPOSTER_FILL),
                    new Beat(Material.GOLD_NUGGET, "Jest coś!", pick(rng, "Coś błyszczy na dnie.", "Ktoś wyrzucił coś naprawdę wartościowego.", "Zawartość wygląda obiecująco."), Sound.ENTITY_ITEM_PICKUP));
            default -> null;
        };
    }

    /** Tytuł okna, ikona kroków i tło z paczki zasobów dla klucza akcji. */
    public static String[] heading(String key) {
        return switch (key) {
            case "przestepstwo" -> new String[]{"Przestępstwo", "napad", "scena_przestepstwo"};
            case "rob" -> new String[]{"Okradanie", "e_rob", "scena_rob"};
            case "praca" -> new String[]{"Praca", "e_work", "scena_praca"};
            case "zebraj" -> new String[]{"Zbieranie drobnych", "e_beg", "scena_zebraj"};
            case "uliczny" -> new String[]{"Grajek uliczny", "e_busk", "scena_uliczny"};
            case "ryby" -> new String[]{"Łowienie ryb", "e_fish", "scena_ryby"};
            case "kopalnia" -> new String[]{"Kopalnia", "m_zarobki", "scena_kopalnia"};
            case "polowanie" -> new String[]{"Polowanie", "e_hunt", "scena_polowanie"};
            case "las" -> new String[]{"Grzybobranie", "e_forest", "scena_las"};
            case "smietnik" -> new String[]{"Śmietniki", "e_trash", "scena_smietnik"};
            default -> new String[]{"Akcja", "napad", "scena"};
        };
    }
}
