# 🎰 Kasyno i Ekonomia: plugin na Paper 1.21.11

Cała ekonomia z bota Discord (portfel, bank, poziomy, sklep ze 113 przedmiotami, biznesy, farma, skrzynie, zarobki) i **21 gier hazardowych** w Minecrafcie. Wszystko klikasz w **GUI** (własne tła okien i ikony z paczki zasobów), gry są podzielone na **sale**, a cztery z nich to **stoły na żywo**: jedna runda dla wielu graczy naraz. **Wieśniacy-NPC** i niewidzialne **placeholdery** (pola kliknięcia nad Twoimi stołami i automatami) otwierają menu, sale i gry jednym kliknięciem.

* Serwer: **Paper 1.21.11** (Java 21). Inne wersje 1.21.x mogą działać, ale nie były testowane.
* Brak zależności od innych pluginów (ani Vault, ani Citizens, ani ItemsAdder). Dane trzyma w plikach JSON.

## Instalacja

1. Pobierz `KasynoEkonomia-1.0.0.jar` z zakładki Releases na GitHubie (albo zbuduj go komendą `mvn package`, plik pojawi się w `target/`) i skopiuj do folderu `plugins/` serwera.
2. Uruchom serwer. Przy pierwszym starcie powstanie folder `plugins/KasynoEkonomia/` z `config.yml`, danymi graczy i kopią paczki zasobów (`KasynoUI.zip`).
3. Wejdź na serwer i wpisz `/kasyno`.
4. Żeby gracze widzieli własne okna, muszą pobrać paczkę zasobów (patrz niżej). Bez paczki wszystko działa, tylko w zwykłym wyglądzie.

## Wygląd okien: paczka zasobów

Plugin ma w środku paczkę zasobów (źródło: [`resourcepack/`](resourcepack/), podgląd: [`dist/preview/okna.png`](dist/preview/okna.png), [`dist/preview/ikony.png`](dist/preview/ikony.png), [`dist/preview/karty.png`](dist/preview/karty.png) i [`dist/preview/tytuly.png`](dist/preview/tytuly.png) z wstęgami wszystkich okien):

* **59 własnych teł okien**, po jednym na każdy rodzaj okna (ekran, gra, każda scena akcji zarobkowej, każdy wybór gracza, trivia i matma), z wstęgą z tytułem i motywem kolorystycznym (czerwony dla menu, zielony dla kart, niebieski dla ryzyka, fioletowy dla stołów na żywo itd.). Wszystkie okna mają 6 rzędów, bo tła są rysowane pod okno o 54 slotach. Okno dostaje ramkę, dywanową fakturę i ciemne komórki jak na obrazkach z paczek typu „Custom UI”.
* **140 własnych ikon** 16x16: sale, wszystkie gry, żetony na pasku stawki (MIN, 1/2, x2, MAX), przyciski zarobków, banku i sklepu, strzałki, krzyżyki itd., a do tego **talia kart** (52 karty i rewers, patrz niżej). Ikony dla przedmiotów ze sklepu to nadal zwykłe materiały Minecrafta.
* **Każde okno ma własny napis na wstędze**, a dwa różne okna nie są podpisane tak samo: osobne tło mają np. sceny akcji (Przestępstwo, Okradanie, Praca, Zbieranie drobnych, Grajek uliczny, Łowienie ryb, Kopalnia, Polowanie, Grzybobranie, Śmietniki), wybór gracza do przelewu, okradania i pojedynku (Komu przelać?, Kogo okraść?, Kogo wyzwać?), Trivia, Matma i Zawód. Generator przerywa pracę, gdy dwa tła mają ten sam tytuł, a test otwiera prawdziwymi kliknięciami wszystkie rodzaje okien i pilnuje, żeby żadne nie dzieliło tła z innym. Nowe okno dodajesz wpisem w `SCREENS` w `tools/pack/gui.js` i nazwą tła zwróconą z `art()`.
* Podstawa okna (`generic_54.png`) jest ciemna z jasnym nagłówkiem, więc zwykłe skrzynie na serwerze nadal są czytelne.

Jak to działa: tło to „czcionka” z jednym wielkim znakiem na ekran (technika z ujemną spacją), wstawiona jako tytuł okna. Tło ma przezroczyste okienka dokładnie na slotach, więc przedmioty są widoczne bez względu na kolejność rysowania w kliencie. Ikony to modele przedmiotów (`item_model`), które plugin ustawia tylko graczom z załadowaną paczką.

**Kto widzi nowy wygląd:** domyślnie (`custom-gui: auto`) gracz, którego paczkę serwer widzi jako załadowaną. Odmowa albo nieudane pobranie nie psuje niczego: okna są wtedy zwykłe.

* `/kasyno pack` pokazuje stan (czy serwer widzi paczkę, jaki masz tryb).
* **Wgrałeś `KasynoUI.zip` ręcznie do folderu `resourcepacks` klienta** (albo paczka idzie z `server.properties`)? Serwer o tym nie wie, więc okna zostaną zwykłe (zobaczysz tylko ciemną podstawę okna). Wpisz `/kasyno pack on`, a okna dostaną własne tła i ikony. `/kasyno pack off` wraca do zwykłych, `/kasyno pack auto` do trybu automatycznego.
* `/kasyno pack wyslij` pobiera paczkę od serwera jeszcze raz, `/kasyno pack info` (admin) pokazuje adres, sumę SHA-1 i liczbę graczy z paczką. Status ładowania każdego gracza trafia też do konsoli serwera.
* `custom-gui: on` w `config.yml` wymusza nowy wygląd wszystkim graczom, `off` wyłącza go wszystkim.

**Skąd gracze pobierają paczkę** (`resource-pack` w `config.yml`):

1. **Wbudowany serwer** (domyślnie): plugin sam podaje paczkę pod `http://<adres>:8123/KasynoUI.zip`. Wpisz w `resource-pack.host.address` publiczny adres serwera (IP albo domenę) i otwórz port TCP 8123. Dla testów na jednym komputerze zadziała domyślne `127.0.0.1`.
2. **Własny hosting:** wgraj `plugins/KasynoEkonomia/KasynoUI.zip` na stronę z bezpośrednim linkiem i wpisz go w `resource-pack.url` (i, jeśli zmieniłeś plik, jego SHA-1 w `resource-pack.sha1`).
3. **Wyłączone:** `resource-pack.enabled: false` albo `custom-gui: off` (zwykłe okna dla wszystkich).

Paczkę generuje skrypt [`tools/gen-pack.js`](tools/gen-pack.js) (czysty Node.js, bez pakietów): `node minecraft-plugin/tools/gen-pack.js`. Kolory, tytuły i ikony są w `tools/pack/`. Po zmianach uruchom skrypt i zbuduj plugin od nowa.

### Karty do gry

W paczce jest cała **talia 52 kart i rewers**, każda karta w dwóch postaciach. Obie rysuje kod generatora, bez żadnych zewnętrznych obrazków:

* **Płaska ikona 16x16** do okien ([`tools/pack/cards.js`](tools/pack/cards.js), podgląd: [`dist/preview/karty.png`](dist/preview/karty.png)): biała karta 11x16 px z figurą w rogu, kolorem pod nią i złotą ramką wokół koloru na walecie, damie i królu, a rewers jest czerwony z kratką i złotym rombem.
* **Model 3D** do świata ([`tools/pack/cards3d.js`](tools/pack/cards3d.js), podgląd tekstur: [`dist/preview/karty3d.png`](dist/preview/karty3d.png)): cienki prostopadłościan (grubość pół jednostki modelu, proporcje 5:7 jak prawdziwa karta) z awersem od południa, rewersem od północy i jasnym brzegiem. Tekstury mają 50x70 px: figura i mały kolor w dwóch rogach (drugi obrócony), kolory ułożone jak na prawdziwych kartach (od dwójki do dziesiątki, dolna połowa do góry nogami), duży kolor na asie, a na walecie, damie i królu figura w złotej ramce (korona na damie i królu). Pliki: `assets/kasyno/models/card/<kolor>_<figura>.json`, `textures/card/` (53 grafiki i wspólny brzeg `edge.png`). Pozycje w ręku, na ziemi, w ramce i na głowie są skopiowane z vanilla `item/generated`, więc karta leży tak jak zwykły przedmiot.

Wybór między nimi robi plik przedmiotu `items/card_*.json` (`select` po `display_context`): **w oknie (`gui`) jest płaska ikona, we wszystkich innych miejscach model 3D**. Ten sam przedmiot z `item_model` `kasyno:card_h_k` wygląda więc dobrze i w oknie, i w ręku, i w ramce na przedmioty, i jako wyświetlacz przedmiotu (`ItemDisplay`).

* **Gdzie je widać:** w **Blackjacku** (ręka gracza, ręka krupiera, a zakryta karta krupiera to rewers) i w **Hi-Lo** (zakryta karta przed grą, bieżąca karta, historia i losowe karty w czasie odkrywania). Gracz bez paczki widzi to samo co dotąd: papier z nazwą karty, np. `K ♥`, a zakrytą kartę jako czarną szybę (blackjack) albo perłę Endu (Hi-Lo).
* **Nazwy ikon:** `card_<kolor>_<figura>` i `card_back`. Kolor to `s` (pik), `h` (kier), `d` (karo), `c` (trefl), figura to `a`, `2` do `10`, `j`, `q`, `k`, np. `card_h_k`, `card_s_10`. Kolejność kolorów jest taka sama jak numery kolorów w grach (0 pik, 1 kier, 2 karo, 3 trefl).
* **Jak ich użyć w nowej grze:** `ItemBuilder.of(Material.PAPER).name("...").icon(CardIcons.of(rank, suit)).build()` (figura 1 albo 14 to as, 11 do 13 to walet, dama, król). Podmianę na model karty robi okno tylko graczom z paczką, tak samo jak dla pozostałych ikon.
* **Karty poza oknem** (w ręku, w ramce, jako `ItemDisplay` w świecie): okno podmienia model tylko tym, którzy mają paczkę, a wyświetlacz w świecie widzą wszyscy. Gracz bez paczki zobaczy wtedy „brakujący model” (fioletowo-czarną kostkę), więc taki przedmiot ustawiaj z `item_model` `kasyno:card_h_k` (przez `PackManager.skin`) tylko tam, gdzie wszyscy mają paczkę (`resource-pack.required: true`). Plugin sam nie rozdaje graczom kart do ręki ani nie stawia ich w świecie, to model do użycia w pokazach.
* Każda karta to zwykła ikona 16x16 w indeksie paczki (`pack-index.json`), więc ma też własny model i wpis w `items/`. Testy pilnują, że wszystkie 53 grafiki istnieją i są różne, ikony mają 16x16 px, a modele 3D mają tekstury 50x70 px o proporcjach bryły, brzeg, awers i rewers oraz pozycje wyświetlania.
* **Nie sprawdzone w kliencie Minecrafta:** modele 3D i podział okno/świat są zgodne z formatem paczki 75 i sprawdzone testami plików, ale nie widziałem ich w prawdziwej grze. Jeśli karta w ręku albo w ramce pokazuje rewers zamiast awersu, trzeba zamienić strony `south` i `north` w `model()` w `tools/pack/cards3d.js` i uruchomić generator.

> **Ważne:** wygląd okien jest sprawdzony testami spójności paczki (pliki, wymiary, położenie okienek na slotach) i podglądami z generatora, a w prawdziwym kliencie 1.21.11 widać je na nagraniach w [głównym README](../README.md). Jeśli u Ciebie okno wygląda źle (np. tło zasłania przedmioty albo jest przesunięte), ustaw `custom-gui: off` i daj znać, co widzisz.

## Sale i stoły na żywo

`/kasyno` otwiera menu, a „Kasyno” prowadzi do holu z czterema salami i pokojem graczy:

| Sala (komenda) | Gry |
|---|---|
| **Automaty** (`/automaty`) | moneta, sloty, zdrapka, szansa |
| **Karty i kości** (`/karty`) | blackjack, wyżej czy niżej, kości, kamień-papier-nożyce |
| **Ryzyko i mnożniki** (`/ryzyko`) | miny, Chicken Road, wieża, rewolwer, limbo, plinko |
| **Stoły na żywo** (`/live`) | **crash, ruletka, koło fortuny, wyścigi konne** |
| **Pokój graczy** (`/pokoj`) | jackpot, napad na bank, pojedynek, loteria |

**Stoły na żywo** działają tak, jak w prawdziwym kasynie online:

1. Pierwszy zakład otwiera odliczanie (15-20 s) i wysyła reszcie graczy zaproszenie z przyciskiem **[DOŁĄCZ]** (wyłączysz to w `tables.announce`).
2. Wszyscy, którzy dołączą w tym czasie, grają **tę samą rundę**: jeden wynik, jedna animacja, ten sam wykres. W oknie widać głowy i stawki pozostałych graczy.
3. **Crash:** wspólny mnożnik rośnie, a każdy wypłaca się sam albo ustawia auto-wypłatę (x1,5 do x10 000). Zamknięcie okna w trakcie rundy bez auto-wypłaty = wypłata przy aktualnym mnożniku. Kto zamknie okno przed startem bez auto-wypłaty, dostaje stawkę z powrotem.
4. **Ruletka:** jedno koło, żetony na dowolnych polach (do 12 pól na gracza). Klik w pole stawia aktualną stawkę.
5. **Koło fortuny:** jeden obrót, mnożnik segmentu liczy się każdemu zakładowi. **Wyścigi:** jeden wyścig, można obstawić kilka koni.
6. Przed startem można cofnąć zakład (stawka wraca). Wynik przychodzi na czat także graczom, którzy zamknęli okno albo wyszli.

**Pieniądze przy stołach są bezpieczne:** stawka zostaje „zatrzymana” przy stole i rozlicza się dokładnie raz. Przy wyłączeniu pluginu wszystkie nierozliczone stawki wracają do graczy, a po awarii serwera wracają przy następnym starcie. Polisa, koniczyna i poziomy działają przy stołach tak samo jak w grach solo. Zwroty: crash 97%, ruletka 97,3%, koło i wyścigi jak w bocie.

## Napad na żywo w świecie: Twój skin wchodzi do banku

Napad na bank rozgrywa się **w prawdziwym świecie**, na oczach wszystkich, którzy stoją w pobliżu. Każdy z ekipy ma swoją **figurę ze swoim skinem** (manekin z modelem gracza, bez dodatkowych pluginów). Ekipa podjeżdża pod bank, idzie przez drzwi i hol, omija kamery, wierci skarbiec, pakuje złoto i ucieka do vana. Po drodze **ochroniarze zakuwają kolejnych członków ekipy** (figura staje w kajdankach i jest eskortowana do radiowozu), a przy alarmie **miga czerwone światło i wyją dzwony**. Widzowie dostają link **[ZOBACZ NA ŻYWO]** (przenosi na miejsce dla publiczności przed bankiem), a ekipa tytuły, napisy i dźwięki.

**Jednorazowa konfiguracja (administrator):**

1. Stań na płaskim terenie w północno-zachodnim rogu, gdzie ma stać bank. Potrzeba miejsca **33 x 38 bloków i ok. 28 bloków wolnej wysokości**: bank powstanie **na południe i na wschód** od Ciebie (plac, ulica z vanem i radiowozem, chodnik, schody, budynek z halą, ladami i skarbcem pełnym złota). Wszystko w tej bryle zostaje wyczyszczone i zabudowane, więc wybierz miejsce z głową (teren pod spodem zostaje taki, jaki jest).
2. Wpisz `/kasyno bank zbuduj`. Plugin stawia bank i zapisuje punkty sceny w `plugins/KasynoEkonomia/stage.yml`. Budowa to kilkadziesiąt tysięcy bloków, więc serwer może na chwilę (sekundę, dwie) zwolnić.
3. Wpisz `/kasyno bank test` (albo `/kasyno bank test 6`): **pokaz próbny z Twoim skinem i kilkoma kolegami**, bez pieniędzy. Od razu stajesz na miejscu dla widzów.

Od tej pory każdy napad (min. 2 osoby) odbywa się przy tym banku. Gdy sceny nie ma, napad gra się w oknie z mapą (opis niżej).

**Jak wygląda ten bank.** To gmach w stylu klasycystycznym (Greek Revival), wzorowany na Second Bank of the United States w Filadelfii i na europejskich bankach z hallą pod kopułą (podglądy: `dist/preview/bank-*.png`):

* **Fasada:** szerokie schody z płyt (każdy stopień to pół bloku, więc da się po nich iść bez skakania), portyk z sześcioma kolumnami z kwarcu (baza, 7-blokowy trzon, głowica), belkowanie z tryglifami, czarna tablica z napisem **BANK** (złote litery, encja `TextDisplay`), trójkątny fronton ze złotą monetą i akroterionami, między kolumnami wysokie okna z łukiem, a pod portykiem drzwi ze szklanym naświetlem i złotym nadprożem. Ściany z kamiennych cegieł, biały gzyms z zębami i attyka, dach z łupku, po bokach pilastry i okna.
* **Hala operacyjna:** szachownica z kwarcu i andezytu, czerwony dywan ze złotymi brzegami, boazeria z ciemnego dębu, lady z szybami po obu stronach (stanowiska kasjerów z biurkami i krzesłami), kasetonowy sufit, żyrandole na łańcuchach, złoty gzyms, a nad środkiem **kopuła** z patynowanej miedzi na bębnie z oknami i szklanym oczkiem, ze złotym zwieńczeniem.
* **Skarbiec:** stalowa wykładzina, depozytowe skrytki (beczki) po bokach, złoto pod tylną ścianą i skrzynie, a na drzwiach koło ze złota i żelaza w złotej ramie. Środek skarbca zostaje wolny dla ekipy.
* **Ulica:** plac z drzewami po drugiej stronie (miejsce dla widzów), jezdnia z przerywaną linią, van po prawej, radiowóz po lewej, latarnie, chodnik, trawniki po bokach.
* **Dla pokazu:** oś budynku (x = 16 licząc od rogu) jest wolna od ulicy do skarbca, żelazne drzwi wejścia i skarbca otwierają się w trakcie pokazu, a cztery lampy alarmu nad oknami bocznych ścian migają na czerwono. Wnętrza są oświetlone (żyrandole, latarnie, morskie latarnie), więc nic wrogiego tam się nie zrodzi. Figury **same dopasowują wysokość do podłoża** (schody, podest, dywan), bo ekipa idzie gęsiego, a punkty sceny mają jedną wysokość na odcinek.
* Budowla powstaje z gotowego planu (klasa `BankPlan`), który testy sprawdzają bez serwera: czy ekipa przejdzie całą trasę pokazu (także długą kolejką), czy punkty stoją na podłodze, czy drzwi i lampy leżą tam, gdzie pokaz ich szuka, czy fasada jest symetryczna, czy nic nie wisi w powietrzu i czy każde wnętrze jest oświetlone.

* `/kasyno bank tp`: każdy teleportuje się na miejsce dla widzów. `/kasyno bank stop`: przerywa pokaz (figury znikają). `/kasyno bank status`: stan sceny. `/kasyno bank usun`: usuwa scenę z konfiguracji (budynek zostaje).
* **Własny budynek** zamiast gotowego: `/kasyno bank punkt <nazwa>` ustawia punkt sceny w miejscu, w którym stoisz. Wymagane: `start` (gdzie wysiada ekipa), `street` (przed drzwiami), `door` (drzwi), `hall` (hol), `front` (przed skarbcem), `safe` (wnętrze skarbca), `guard` (skąd wychodzą ochroniarze), `police` (skąd przyjeżdża policja), `jail` (radiowóz), `view` (miejsce dla widzów, patrz w stronę banku). Opcjonalne: `bankdoor`, `vaultdoor` (żelazne drzwi, które się otwierają), `lamp1`-`lamp4` (lampy redstone, które migają przy alarmie).
* Skórki: na serwerze online-mode figury mają dokładnie skiny graczy. Na serwerze offline-mode plugin prosi o skórkę po nicku (zadziała, jeśli taki nick ma konto Mojang), a w razie braku figura ma domyślny skin.
* Pieniądze liczą się tak jak wcześniej (rozliczone na starcie), a pokaz tylko je odgrywa. Przy wyłączeniu pluginu figury znikają, a zgubione figury po awarii są sprzątane przy starcie.

## Pojedynek i jackpot na żywo w świecie: arena

Pojedynek i losowanie jackpota też mogą rozgrywać się **w prawdziwym świecie**, na oczach wszystkich, którzy stoją w pobliżu, z figurami ze **skinami graczy** (manekiny, tak samo jak przy napadzie).

* **Pojedynek:** obaj gracze (wyzywający na stanowisku A, przyjmujący na B) wchodzą na arenę i stają naprzeciw siebie. Napięcie (przycupnięte figury, napis „Dłonie przy kaburach”), **odliczanie 3-2-1 jako duże napisy**, losowa chwila ciszy, **STRZAŁ** (błysk i dym z obu luf, dwa strzały naraz), po którym **przegrany pada** (poza leżąca, jak śpiący), a zwycięzca zostaje na nogach i dostaje złote iskry. Na końcu obaj znikają w dymie.
* **Jackpot:** uczestnicy stają jako figury **na okręgu** wokół złotego środka areny i patrzą do środka. **Podświetlenie (obrys figury i złoty słup cząsteczek) krąży po nich** z tykaniem, które zwalnia (z 2 do 9 ticków na krok), i staje na zwycięzcy. Zwycięzca dostaje **fajerwerki** (trzy salwy) i fanfary, potem wszyscy znikają w dymie.
* Napisy (odliczanie, kto jest podświetlony, zwycięzca) dostają uczestnicy (także gdy stoją daleko od areny, wtedy z dodatkowym dźwiękiem) i wszyscy w pobliżu areny. Reszta serwera dostaje na czacie zaproszenie z linkiem **[ZOBACZ NA ŻYWO]** (przenosi na trybunę), uczestnicy też.
* **Pieniądze liczą się tak jak wcześniej:** wynik jest rozliczony przed sceną (pojedynek przy przyjęciu, jackpot przy losowaniu), scena niczego nie dotyka, a podsumowanie na czacie (wygrana, przegrana, fanfary) przychodzi dopiero po pokazie. Przy wyłączeniu pluginu albo `/kasyno arena stop` figury znikają, a gracze i tak dostają podsumowanie.
* **Zapas:** gdy areny nie ma (albo trwa na niej inny pokaz: arena ma jedno miejsce), pojedynek i jackpot grają się jak dotąd w oknach (`DuelGui`, `RoomGui`). Sama scena banku nie włącza areny i odwrotnie.

**Jednorazowa konfiguracja (administrator):**

1. Stań na płaskim terenie w rogu, gdzie ma stać arena. Potrzeba miejsca 31 x 31 bloków: arena powstanie **na południe i na wschód** od Ciebie (okrągły plac z piasku z niskim murkiem i latarniami, dwa pomarańczowe stanowiska 8 bloków od siebie, złoty środek z okręgiem, trybuna z trzema rzędami od południa).
2. Wpisz `/kasyno arena zbuduj`. Plugin stawia arenę i zapisuje jej punkty w `plugins/KasynoEkonomia/stage.yml` (razem ze sceną banku, ale niezależnie od niej: bank i arena mogą stać w różnych światach).
3. Wpisz `/kasyno arena test` (pojedynek próbny: Ty kontra „Kolega”) albo `/kasyno arena test jackpot 6` (losowanie próbne, 2-20 osób). Pokazy próbne **nie ruszają pieniędzy**; od razu stajesz na trybunie.

Komendy:

| Komenda | Działanie |
|---|---|
| `/kasyno arena tp` | każdy teleportuje się na trybunę areny |
| `/kasyno arena zbuduj` | (admin) stawia gotową arenę w miejscu, w którym stoisz |
| `/kasyno arena test [duel\|jackpot [2-20]]` | (admin) pokaz próbny z Twoim skinem, bez pieniędzy |
| `/kasyno arena punkt <a\|b\|center\|view>` | (admin) własna arena: stanowisko A, stanowisko B (patrz na siebie), środek okręgu jackpota, trybuna (patrz w stronę areny) |
| `/kasyno arena stop` | (admin) przerywa trwające pokazy, figury znikają |
| `/kasyno arena usun` | (admin) usuwa arenę z konfiguracji (plac w świecie zostaje), pojedynki i jackpot wracają do okien |
| `/kasyno arena status` | (admin) stan areny i brakujące punkty |

* **Sprzątanie:** każda figura i fajerwerk pokazu ma znacznik `kasyno:show` w danych encji. Po pokazie nie zostaje nic, a figury zostawione po awarii serwera są usuwane przy starcie pluginu i przy jego wyłączeniu (w świecie banku i w świecie areny).
* **Okrąg jackpota** ma promień co najmniej 4 bloki i rośnie z liczbą osób, tak by figury stały co najmniej 1,25 bloku od siebie (20 osób mieści się na okręgu o promieniu 4).
* **Skórki** działają tak samo jak przy napadzie (online-mode: dokładne skiny; offline-mode: skórka po nicku).

## Animacje i sceny

Akcje z fabułą są odgrywane jako sceny: dźwięki, napisy nad paskiem życia, tytuły na ekranie i okno, które zmienia się krok po kroku. Wynik jest zawsze policzony i rozliczony **zanim** scena ruszy (albo, przy akcjach solo, dopiero w jej ostatnim kroku), więc zamknięcie okna niczego nie psuje.

* **Napad na bank bez sceny w świecie:** w chwili startu **okno z mapą banku otwiera się samo każdemu z ekipy**. To animowany film złożony z kilkudziesięciu klatek (ok. 12-20 s): czarny **van podjeżdża** pod bank, ekipa wysiada i idzie pionem przez mapę (ulica, drzwi, hol, skarbiec), drzwi się otwierają, **kamery omiatają hol wiązkami**, wiertło iskrzy na drzwiach skarbca, a po otwarciu leży w nim złoto, które wędruje do paska łupu. Na każdym z pięciu etapów (**wejście, ochrona i kamery, skarbiec, łup, ucieczka**) los rzuca jedno zdarzenie: wszystko idzie gładko, **ochroniarz rusza na kogoś z ekipy i go zakuwa** (portret w skrajnej kolumnie zmienia się w kajdanki, a mapa mignie na czerwono), **alarm** (cała mapa miga na czerwono, syreny, koniec akcji), dodatkowy łup (złote iskry) albo **radiowóz zajeżdża drogę** przy ucieczce. Na końcu van odjeżdża w prawo. Do tego każdy z ekipy dostaje dźwięki, napisy nad paskiem życia i tytuły (**ZŁAPANY!**, **ALARM!**). Łup to wpisowe całej ekipy razy mnożnik (x1,2 plus dodatki), a dzielą go ci, którzy dotrwali do końca, więc złapani tracą wpisowe, a koledzy zyskują. Małe ekipy mają mniej wpadek, a zwrot dla każdej wielkości ekipy jest poniżej 100% (od 84% dla dwóch osób do 96% dla dużej, liczy to test).
* **Jackpot (bez areny w świecie):** okno otwiera się samo każdemu uczestnikowi. Złote podświetlenie krąży po głowach graczy, zwalnia i staje na zwycięzcy, z dźwiękiem tykania i fanfarami.
* **Pojedynek (bez areny w świecie):** dwóch rewolwerowców naprzeciw siebie, napięcie, odliczanie 3-2-1, losowa chwila ciszy, **STRZAŁ** i jeden z nich pada.
* **Loteria:** przy losowaniu każdy, kto kupił bilety, dostaje otwarte okno z głowami uczestników i krążącym podświetleniem, które zwalnia na zwycięzcy. Reszta serwera widzi nicki nad paskiem życia. Pula trafia do zwycięzcy od razu.
* **Zarobki:** przestępstwo, okradanie gracza, praca, zbieranie drobnych, grajek uliczny, ryby, kopalnia, polowanie, grzybobranie i śmietniki zaczynają się krótką sceną (ok. 4 s): Twoja głowa **idzie ścieżką** przez dziewięć pól, na przystankach staje, przystanek mruga, pojawia się opis kroku (3-4 kroki z dźwiękami), a dopiero potem następuje wynik. Nagrody dzienna, tygodniowa i godzinna są natychmiastowe. Komendy (`/praca`, `/ryby`...) nadal działają od razu, bez sceny.

## NPC

Jako operator (uprawnienie `kasyno.admin`) stań tam, gdzie ma stać NPC, i wpisz:

```
/kasyno npc spawn <typ> [promień pracy]
```

NPC to wieśniak bez AI, niezniszczalny, z napisem nad głową. Prawy przycisk myszy otwiera przypisane menu albo grę. Typ zostaje zapisany w danych NPC, więc przetrwa restart serwera.

| Typ | Co otwiera |
|---|---|
| `menu` | główne menu |
| `kasyno` | hol kasyna (cztery sale) |
| `automaty` `karty` `ryzyko` `live` | konkretna sala: automaty, karty i kości, ryzyko, stoły na żywo |
| `zarobki` | nagrody, praca, zbieranie, quizy |
| `sklep` | sklep (10 kategorii) |
| `ekwipunek` | przedmioty, eliksiry, polisy i tarcze |
| `bank` | wpłaty, wypłaty, ulepszenia limitu, przelewy |
| `biznes` | biznesy i farma |
| `skrzynie` | skrzynie z animacją otwierania |
| `ranking` | ranking graczy |
| `profil` | profil gracza |
| `pokoj` | jackpot, napad, pojedynek, loteria |
| `pomoc` | instrukcja |
| `moneta` `sloty` `zdrapka` `szansa` `blackjack` `hilo` `kosci` `knp` `miny` `kurczak` `wieza` `rewolwer` `limbo` `plinko` `crash` `ruletka` `kolo` `wyscigi` | konkretna gra albo stół |
| `lesnik` `gornik` `rybak` `sprzatacz` `kurier` `poszukiwacz` | praca w terenie ze świecącymi celami (następna sekcja) |

Pozostałe komendy NPC: `/kasyno npc remove` (usuwa NPC, na którego patrzysz albo najbliższego), `/kasyno npc list`, `/kasyno npc types`, `/kasyno npc removeall potwierdz`.

## Prace w terenie: świecące cele w prawdziwym świecie

NPC od pracy zlecają rundy, w których gracz **chodzi po okolicy i szuka świecących celów**: grzybów w lesie, rud, ryb, śmieci, adresów paczek albo zakopanego skarbu. Cele widzi **tylko ten, kto zaczął rundę** (każdy ma własne, nikt nikomu niczego nie zbierze), klika je prawym albo lewym przyciskiem myszy i **dostaje pieniądze od razu**. Wypłata liczy się jak przy innych zarobkach (karta VIP, zwierzaki, eliksir zarobków), do tego XP, a na końcu premia za komplet.

Postaw NPC w miejscu pracy, np. w środku swojego lasu:

```
/kasyno npc spawn lesnik 40
```

Liczba to **promień pracy** w blokach (10-120, bez niej obowiązuje domyślny dla danej pracy). Cele pojawiają się w tym kole wokół NPC, na powierzchni terenu: w lesie na ziemi pod drzewami (liście, pnie i kapelusze wielkich grzybów są pomijane, a miejsca ze zwisającymi liśćmi tuż nad głową też). Stawiaj NPC pod gołym niebem: pod dachem cele wylądowałyby na dachu. Promień zmienisz później komendą `/kasyno praca promien <bloki>` przy NPC (`0` przywraca domyślny).

| Typ NPC | Praca | Co się dzieje | Wymaga | Runda / przerwa | Przeciętnie za rundę |
|---|---|---|---|---|---|
| `lesnik` Leśniczy | Grzybobranie | 10 grzybów: kurki, borowiki, trufle i złote grzyby (świecą każdy swoim kolorem) | nic, koszyk zwiększa szansę na rzadkie | 2:30 / 2:30 | ok. 840 $ |
| `gornik` Kierownik kopalni | Kopalnia | 10 żył rudy: węgiel, żelazo, złoto, diament, szmaragd | kilof ze sklepu | 2:30 / 3:00 | ok. 860 $ |
| `rybak` Rybak | Łowienie ryb | ryby pływają tuż pod powierzchnią i uciekają po 12-17 s (wtedy płyną nowe), trzeba złowić 12 | wędka ze sklepu i woda w promieniu pracy | 2:30 / 2:30 | ok. 800 $ |
| `sprzatacz` Dozorca parku | Sprzątanie | 12 śmieci, czasem telefon, zegarek albo złota obrączka | nic | 2:00 / 2:30 | ok. 820 $ |
| `kurier` Kierownik poczty | Dostawa paczek | 3 adresy jeden po drugim, **napiwek za szybkość** (do +25%) | nic | 3:00 / 3:00 | ok. 550-680 $ |
| `poszukiwacz` Poszukiwacz skarbów | Poszukiwanie skarbu | ukryty skarb: pasek akcji mówi „lodowato … parzy” oraz „cieplej / zimniej”, skrzynia zaczyna świecić z 12 bloków, 25% szans na skrzynkę | nic | 4:00 / 5:00 | ok. 700 $ |

* **Start:** kliknij NPC, w oknie pracy zobaczysz, co się trafia i ile płaci, czy masz narzędzie, ile zostało do końca przerwy i przycisk **Zacznij pracę** (trzeba stać blisko NPC, w promieniu pracy plus 25 bloków). To samo otwierają komendy `/lesnik`, `/gornik`, `/rybak`, `/sprzatacz`, `/kurier`, `/poszukiwacz` oraz `/prace` (lista) i okno Zarobki (kompas w prawym rzędzie). Administrator bez NPC w pobliżu może próbować pracy tam, gdzie stoi.
* **W trakcie:** pasek akcji pokazuje nazwę pracy, postęp, zarobek, czas i **strzałkę z odległością** do najbliższego celu (względem tego, w którą stronę patrzysz). Cele się obracają i iskrzą, a po zebraniu nad miejscem unosi się napis z kwotą.
* **Koniec rundy:** komplet (premia 20-30% od zarobku, a skarb jej nie ma; tytuł i fajerwerk), upływ czasu (zostaje to, co zebrane, bez premii), przerwanie w oknie NPC albo `/kasyno praca anuluj`, oddalenie się od miejsca pracy (ponad 1,4 promienia plus 30 bloków) i wyjście z serwera albo zmiana świata. **Przerwa zaczyna się po każdej rundzie**, także przerwanej, żeby nie dało się losować układu od nowa. Wyjątek to przerwanie przez restart albo przeładowanie pluginu: bez przerwy, a zarobione pieniądze zostają zawsze.
* **Technika:** cel to świecący przedmiot (`ItemDisplay` z kolorową poświatą) i niewidzialne pole kliknięcia (`Interaction`), oba ukryte dla wszystkich poza właścicielem rundy. Nie zapisują się w świecie, mają znacznik `kasyno:work` i są usuwane po rundzie, przy wyłączeniu pluginu i przy starcie (gdyby serwer padł w trakcie).
* **Komendy:** `/kasyno praca` (lista prac), `/kasyno praca info` (zarobki i przerwy), `/kasyno praca anuluj`; administrator: `/kasyno praca promien <bloki>` (przy NPC), `/kasyno praca stop <gracz>`.

## Śmietniki w świecie: modele z bloków, każdy z własną przerwą

Administrator stawia w mieście śmietniki: **modele zbudowane z bloków** (encje `BlockDisplay`, więc nie potrzeba paczki zasobów) z niewidzialnym polem kliknięcia. Gracz klika prawym albo lewym przyciskiem, wieko się unosi i wypada łup z tej samej tabeli, co przy zbieraniu ze śmietnika w menu: puszki, butelki, a czasem stary telefon, zepsuty zegarek, złota obrączka albo skrzynka. Łup trafia do ekwipunku (sprzedasz go `/sprzedaj wszystko`), a za znalezisko jest XP. Zielone iskry nad śmietnikiem widzi tylko ten, kto może go teraz przeszukać.

**Każdy śmietnik ma własną przerwę, liczoną osobno dla każdego gracza.** Przeszukanie jednego nie blokuje innych, a dwóch graczy nie wchodzi sobie w drogę. Nowy śmietnik dostaje przerwę z rozrzutem od -20% do +25% wokół domyślnej dla rodzaju (co 5 s), więc dwa takie same kosze w mieście mają trochę inne przerwy. Dokładną ustawisz komendą (10-3600 s). Przerwa startuje przy każdym przeszukaniu, także gdy w środku były same śmieci, i skraca ją eliksir cooldownów.

| Typ | Model | Domyślna przerwa | Szczęście / ilość | Pusty strzał | Przeciętnie za przeszukanie |
|---|---|---|---|---|---|
| `kosz` | uliczny kosz: szary walec z zielonym pasem i wiekiem | 1:30 | x1 / x1 | 15% | ok. 34 $ |
| `beczka` | beczka z żelaznymi obręczami i klapą | 2:15 | x1,4 / x1,3 | 12% | ok. 47 $ |
| `kontener` | zielony kontener na kółkach z dwiema klapami | 3:20 | x1,8 / x1,8 | 8% | ok. 61 $ |
| `wiata` | trzy pojemniki (niebieski, żółty, zielony) pod dachem | 5:00 | x2,5 / x2,2 | 5% | ok. 78 $ |

Dłuższa przerwa to lepszy łup: szczęście mnoży wagę rzadkich znalezisk, a ilość dotyczy tylko rzeczy sypanych garściami (puszki, butelki), bo telefon, zegarek, obrączka i skrzynka zawsze są jedną sztuką. Wartość w przeliczeniu na minutę przerwy jest podobna dla każdego rodzaju (ok. 16-23 $/min), a menu daje do 50 $/min, tyle że bez chodzenia po mieście. Wartości to ceny sprzedaży łupu, liczy je test.

Jako operator stań tam, gdzie ma stać śmietnik (środek jego podstawy to środek bloku, na którym stoisz), **twarzą w stronę, z której ma być widać jego przód**, i wpisz:

```
/kasyno smietnik postaw kosz
```

Model obraca się przodem do Ciebie (do 90 stopni). Opcjonalnie dopisz przerwę w sekundach: `/kasyno smietnik postaw kontener 240`.

| Komenda | Działanie |
|---|---|
| `/kasyno smietnik typy` | lista rodzajów z przerwami (każdy gracz) |
| `/kasyno smietnik postaw <typ> [sekundy]` | (admin) stawia śmietnik w Twoim miejscu |
| `/kasyno smietnik cooldown <sekundy>` | (admin) zmienia przerwę śmietnika, na który patrzysz albo najbliższego w promieniu 5 bloków |
| `/kasyno smietnik usun` | (admin) usuwa go razem z modelem |
| `/kasyno smietnik lista` | (admin) rodzaj, identyfikator, współrzędne i przerwa każdego w załadowanych chunkach |
| `/kasyno smietnik wyczysc potwierdz` | (admin) usuwa wszystkie, także osierocone bryły po śmietnikach, których nie dało się usunąć komendą (np. po `/kill`) |

Wszystko zapisuje się w danych encji (rodzaj, identyfikator, przerwa, obrót), więc śmietniki przeżywają restart serwera. Przerwa gracza jest w jego danych pod kluczem `bin:<identyfikator>`, więc po usunięciu śmietnika zostaje po niej tylko nieszkodliwy wpis.

## Waypointy: napis z nazwą i odległością jak w modach

Waypointy to **własne punkty widoczne w świecie u każdego gracza**, tak jak w modach z waypointami: **nazwa pogrubiona w kolorze punktu i pod nią odległość po polsku** („Skałka”, „76 metrów”) na ciemnym tle, widoczne przez ściany i z dowolnej odległości. Napis ma **stały rozmiar**: nie rośnie ani nie maleje, gdy się zbliżasz albo oddalasz. Nie ma tu żadnego paska ani boss bara. Ty je tworzysz komendą, a plugin każdemu graczowi pokazuje jego własne znaczniki najbliższych punktów, więc nikt nie musi mieć moda.

Stań tam, gdzie ma być punkt, i wpisz:

```
/kasyno waypoint dodaj Bank czerwony
```

Nazwa to litery (także polskie), cyfry, `_` i `-`, do 32 znaków. Spację zastąp podkreśleniem (`Moj_Dom` wyświetla się jako „Moj Dom”). Kolor to nazwa po polsku albo po angielsku (`zloty`, `czerwony`, `zielony`, `niebieski`, `fioletowy`, `rozowy`, `turkusowy`, `pomaranczowy`, `zolty`, `bialy`, `szary`, `czarny`, `brazowy`, ciemne odmiany i `red`, `gold`...) albo `#rrggbb`. Bez koloru jest złoty (zmienisz to w `waypoints.default-color`).

| Komenda | Działanie |
|---|---|
| `/kasyno waypoint lista` | (każdy) wszystkie waypointy ze strzałką i odległością od Ciebie |
| `/kasyno waypoint info` | (każdy) stan funkcji i ustawienia |
| `/kasyno waypoint ukryj` / `pokaz` | (każdy) ukrywa znaczniki tylko Tobie i przywraca je (do restartu serwera) |
| `/kasyno waypoint dodaj <nazwa> [kolor]` | (admin) nowy waypoint w miejscu, w którym stoisz |
| `/kasyno waypoint usun <nazwa>` | (admin) usuwa waypoint |
| `/kasyno waypoint przenies <nazwa>` | (admin) przenosi go tam, gdzie stoisz (także do innego świata) |
| `/kasyno waypoint kolor <nazwa> <kolor>` | (admin) zmienia kolor |
| `/kasyno waypoint tp <nazwa>` | (admin) teleportuje Cię do waypointu |
| `/kasyno waypoint wlacz` / `wylacz` | (admin) włącza i wyłącza całą funkcję (zapisuje się w config.yml) |

**Wyłączenie w ustawieniach:** w `config.yml` jest sekcja `waypoints` (`enabled`, `label`, `label-scale`, `label-distance`, `beam`, `beam-height`, `marker-distance`, `max-visible`, `max-distance`, `default-color`). `enabled: false` albo `/kasyno waypoint wylacz` zdejmuje wszystkie znaczniki (nikt ich nie widzi), a zapisane punkty zostają w `plugins/KasynoEkonomia/waypoints.yml`, więc ponowne włączenie przywraca wszystko. `/kasyno reload` stosuje zmiany z config.yml od razu. Przy wyłączonej funkcji nadal możesz dodawać i poprawiać punkty, tylko nikt ich nie widzi.

* **Stały rozmiar napisu:** napis nie stoi przy samym waypoincie, tylko `label-distance` bloków od Ciebie (domyślnie 12) w kierunku waypointu i unosi się 2 bloki nad punktem. Na ekranie jest więc w tym samym miejscu co sam punkt, ale zawsze tej samej wielkości, jak etykieta z moda, niezależnie od tego, czy waypoint jest 30 czy 3000 bloków dalej. Gdy podejdziesz bliżej niż 12 bloków, napis przechodzi na prawdziwe miejsce punktu i maleje proporcjonalnie, więc na ekranie dalej wygląda tak samo. Rozmiar ustawiasz w `label-scale` (domyślnie 2,2; większa liczba to większy napis). **Napis jest wygładzony i stabilny:** plugin ustawia go względem stabilnego punktu odniesienia, a nie względem Twoich oczu. Skok, kucanie i drżenie kamery go nie ruszają (wysokość zmienia się tylko wtedy, gdy naprawdę zmieniasz poziom: stoisz na ziemi, lecisz, pływasz, wspinasz się albo spadasz o ponad 3 bloki, a schodek przesuwa napis łagodnie). Ruch gracza dochodzi do serwera nierówno (raz pół kroku, raz półtora, czasem dwa naraz), więc położenie jest wygładzone filtrem: napis płynie równo razem z Tobą i drga mniej więcej o połowę mniej niż surowy ruch, a po zatrzymaniu zbiega pod Ciebie bez przestrzelenia. Napis dostaje nową pozycję w każdym ticku, a klient wygładza ruch o jeden tick.
* **Odległość:** metry co 1 m z poprawną odmianą („1 metr”, „2 metry”, „5 metrów”, „12 metrów”, „22 metry”), od tysiąca kilometry („1,3 km”).
* **Kreska (opcjonalnie):** `beam: true` dodaje pionową, świecącą kreskę w kolorze punktu (widoczną przez ściany) w prawdziwym miejscu waypointu, a gdy jest dalej niż `marker-distance` bloków (80), „przykleja się” w jego kierunku na tej odległości. Domyślnie jest wyłączona, bo sam napis wygląda czyściej.
* **Każdy ma własne znaczniki:** to osobne encje dla każdego gracza i waypointu (`TextDisplay` widoczny przez ściany, a przy włączonej kresce `BlockDisplay` z poświatą), ukryte dla wszystkich poza właścicielem. Dzięki temu napis zna odległość tego jednego gracza. Znaczniki nie zapisują się w świecie, mają znacznik `kasyno:waypoint_marker` i znikają po restarcie, po wyjściu gracza z serwera i po wyłączeniu funkcji. Pozostałe po awarii plugin sprząta przy starcie.
* **Ile naraz:** każdy gracz widzi `max-visible` najbliższych waypointów (domyślnie 8) z własnego świata, opcjonalnie nie dalej niż `max-distance` bloków. Odświeżanie idzie w każdym ticku (20 razy na sekundę), ale przy stojącym graczu nic się nie wysyła.
* **Po zmianie punktu** (kolor, przeniesienie, usunięcie) znacznik od razu znika i pojawia się od nowa w nowym kolorze albo miejscu.
* **Po starej wersji:** wcześniejsza wersja pluginu stawiała niewidzialne manekiny pod pasek lokalizatora. Te manekiny plugin usuwa przy starcie i przy wczytywaniu chunków (zwykłych manekinów graczy nie rusza).

### Waypointy z sensem: miejsca kasyna, prowadzenie, nagrody i punkty osobiste

Same napisy nad punktami to dopiero połowa. Żeby waypointy miały sens w grze, dochodzą cztery rzeczy:

* **Miejsca kasyna pojawiają się same.** Bank (wejście), arena i więzienie są waypointami **automatycznie**, gdy je zbudujesz albo ustawisz (`/kasyno bank zbuduj`, `/kasyno arena zbuduj`, cele więzienia). Nie musisz ich dodawać ręcznie i nie da się ich usunąć komendą `usun`. Własny waypoint o nazwie „Bank” wygrywa z automatycznym. Wyłączysz to w `waypoints.auto-places: false`.
* **Prowadzenie do celu:** `/kasyno waypoint prowadz <nazwa>` (też `bank`, `arena`, `wiezienie`, `dom`, `smierc`) zostawia Ci na ekranie **tylko ten jeden cel** (z strzałką ➤) i Twoje osobiste punkty, żeby inne napisy nie przeszkadzały. Kończy się samo, gdy podejdziesz na `arrive-radius` bloków (6), albo komendą `prowadz wylacz`. Zapisuje się w danych gracza.
* **Nagrody za odkrycie:** administrator przypisuje punktowi nagrodę (`/kasyno waypoint nagroda <nazwa> <kwota>`, 0 zdejmuje). Nieodkryty punkt ma w napisie **★ i kwotę**. Kto pierwszy raz podejdzie na `discover-radius` bloków (8), dostaje ją **raz** (plus trochę doświadczenia, napis na ekranie i dźwięk), a gwiazdka znika tylko jemu. `/kasyno waypoint odkryte` pokazuje postęp (np. 3/7). Odkrycia i nagrody zapisują się w danych gracza, a nagroda w `waypoints.yml`.
* **Punkty osobiste** (widzisz je tylko Ty): **⌂ Dom** (`/kasyno waypoint dom ustaw`, `dom usun`, a samo `dom` prowadzi do domu) oraz **☠ Miejsce śmierci**: po każdej śmierci (także po rekinie) plugin zaznacza, gdzie zginąłeś, żebyś odnalazł rzeczy. Znika po `death-minutes` minutach (10), gdy do niego dojdziesz, albo po kolejnej śmierci.

Nowe klucze w `waypoints`: `auto-places`, `discover-radius`, `arrive-radius`, `death-minutes` (0 wyłącza punkt śmierci). Stare pliki `waypoints.yml` i zapisy graczy wczytują się bez zmian (brak nagrody, brak punktów osobistych).

## Więzienie: nieudane przestępstwo kończy się celą i zadaniami

Kto wpadnie, nie płaci już tylko grzywny: **trafia do więzienia** i wychodzi stamtąd, wykonując zadania (minigierki) albo po upływie czasu.

**Kto trafia za kratki:**

* nieudane **`/przestepstwo`** (szansa 50%, kominiarka +20%): zamiast grzywny wyrok 3 zadania (do 8 minut),
* nieudane **okradanie gracza** (`/okradnij`): odszkodowanie dla ofiary zostaje, do tego krótszy wyrok 2 zadania (do 5 minut). Kłódka ofiary kończy się tylko odszkodowaniem,
* złapani w **napadzie na bank**: po zakończeniu pokazu wyrok 4 zadania (do 12 minut). Dotyczy też tych, którzy zdążyli wyjść z serwera: trafią do celi przy wejściu.

Przez 4 sekundy po wpadce widzisz wynik (napis „ZATRZYMANY!”), potem plugin zabiera Cię do **losowej z trzech cel** i przełącza w tryb przygody. Wyrok zapisuje się razem z graczem, więc wyjście z serwera ani restart go nie kasują: po powrocie nadal siedzisz, a jeśli czas minął w międzyczasie, jesteś wolny.

**Cele** to domyślnie `-79 92 65`, `-82 92 65` i `-85 92 65` (współrzędne bloku; gracz staje na jego środku). **Uwaga:** przy trzeciej celi podałeś tylko „92 65”, bez pierwszej współrzędnej, więc przyjąłem `-85` (kolejne 3 bloki dalej, tak jak między pierwszą a drugą). Jeśli ma być inaczej, stań w środku trzeciej celi i wpisz `/kasyno wiezienie cela 3 ustaw` albo popraw `prison.cells` w `config.yml`.

**W celi:**

* nie odejdziesz dalej niż `prison.radius` bloków (3,5) od środka celi, strażnik zawraca Cię z komunikatem,
* nie otworzysz okien kasyna ani gier (także z NPC), a komendy są zablokowane poza `/kasyno wiezienie`, `/wiezienie` i czatem prywatnym (`prison.allowed-commands`),
* nie giniesz (obrażenia są anulowane), nie uciekniesz perłą ani owocem chóru, po śmierci odradzasz się w celi,
* pasek nad głową pokazuje zadania i czas do wolności, a lista zadań otwiera się sama (`/kasyno wiezienie`, `/wiezienie`, przycisk na czacie albo NPC strażnik: `/kasyno npc spawn wiezienie`).

**Cztery minigierki** (każda zaliczona zdejmuje jedno zadanie, porażka nic nie kosztuje, można próbować bez końca):

| Minigra | Zasady |
|---|---|
| **Podkop** | klikasz kilof 24 razy w 9 sekund od pierwszego uderzenia, ściana kruszy się na bieżąco |
| **Refleks** | w siatce zapala się klucz, trzeba go trafić, zanim zgaśnie (z każdym trafieniem krócej); 6 trafień, najwyżej 2 błędy |
| **Sekwencja** | cztery kolory mrugają w kolejności (5 kroków), Ty powtarzasz ją po strażniku; pomyłka kończy próbę |
| **Egzamin** | jedno pytanie z wiedzy albo matmy (bez nagrody pieniężnej, ten sam zestaw co w trivii) |

Po ostatnim zadaniu wychodzisz na punkt `prison.exit` (domyślnie spawn świata) i wracasz do poprzedniego trybu gry.

| Komenda | Działanie |
|---|---|
| `/kasyno wiezienie` (`/wiezienie`, `/cela`) | (każdy) lista zadań więźnia, a dla wolnego krótkie wyjaśnienie |
| `/kasyno wiezienie wsadz <gracz> [przestepstwo\|okradanie\|napad]` | (admin) wsadza gracza do losowej celi |
| `/kasyno wiezienie uwolnij <gracz>` | (admin) uwalnia, także offline (wyjście dopełnia się przy wejściu) |
| `/kasyno wiezienie status <gracz>` `cele` | (admin) wyrok gracza, lista cel i wyjście |
| `/kasyno wiezienie cela <nr> [ustaw]` `wyjscie [ustaw]` | (admin) teleport do celi albo ustawienie jej (numer do liczby cel + 1 dopisuje nową) i punktu wyjścia |

Konfiguracja w sekcji `prison` (włącznik, świat, cele, wyjście, promień, opóźnienie aresztu, zadania i minuty dla każdego powodu, dozwolone komendy). Uprawnienie `kasyno.prison.bypass` (domyślnie operatorzy) chroni przed więzieniem i ograniczeniami.

## Rekin: im dalej od brzegu, tym większe ryzyko

Pływasz daleko od lądu po głębokiej wodzie? Możesz spotkać rekina. Im dalej od **ostatniego miejsca, w którym stałeś na stałym gruncie**, tym większa szansa. Płynięcie łodzią ani latanie nie zerują odległości, wyspa po drodze tak.

* **Losowanie** co 4 sekundy pływania (`shark.check-seconds`), tylko gdy pod Tobą jest co najmniej 3 bloki wody (płycizny są bezpieczne). Szansa rośnie liniowo od **0 przy 20 blokach** do **20% na jedno losowanie przy 150 i więcej** (`min-distance`, `max-distance`, `max-chance`). Daje to w praktyce kilkadziesiąt procent na dłuższej wyprawie przez ocean.
* **Ostrzeżenie (5 sekund):** pojawia się napis „REKIN!” i płetwa grzbietowa, która krąży wokół Ciebie coraz ciaśniejszą spiralą (z pluskiem i coraz szybszym biciem serca). **Wyjdź z wody albo wsiądź do łodzi**, a rekin odpłynie i da Ci spokój na 2 minuty.
* **Atak:** rekin zanurza się, rzuca się spod wody, **wyskakuje z otwartą paszczą**, chwyta Cię (ślepota, krew, dźwięki) i trzęsie, a po około sekundzie **pożera**: tracisz **połowę gotówki z portfela** (`loss-fraction`; pieniądze w banku są bezpieczne) i giniesz, a na czacie pojawia się „… został pożarty przez rekina”. Miejsce śmierci zaznacza się waypointem ☠.
* Wyjście z serwera w trakcie rzutu albo chwytu nie ratuje gotówki. W trakcie ostrzeżenia kosztuje 0.
* **Nie atakuje:** trybu kreatywnego i obserwatora, graczy z uprawnieniem `kasyno.shark.bypass`, więźniów, innych światów niż zwykły, płytkiej wody.

**Model** to 16 bryłek `BlockDisplay`: szary grzbiet i biały brzuch, głowa z zębami i oczami, **ruchoma dolna szczęka z czerwoną paszczą**, płetwa grzbietowa i piersiowe oraz ogon z trzema stawami, który macha falą. Podgląd z prawdziwych przekształceń modelu (płynie, wyskok, ostrzeżenie, chwyt) jest w `dist/preview/rekin.png`.

| Komenda | Działanie |
|---|---|
| `/kasyno rekin info` | (każdy) ustawienia i Twoje ryzyko tu, gdzie jesteś |
| `/kasyno rekin pokaz` | (admin) model krąży przed Tobą 10 sekund i otwiera paszczę |
| `/kasyno rekin test [gracz]` | (admin) cały atak z animacją, **bez śmierci i bez utraty pieniędzy** |
| `/kasyno rekin zaatakuj <gracz>` | (admin) prawdziwy atak (kończy się śmiercią i utratą gotówki) |
| `/kasyno rekin status` `wlacz` `wylacz` | (admin) stan, włączanie i wyłączanie rekinów (zapisuje się w config.yml) |

Konfiguracja w sekcji `shark` (włącznik, odległości, szansa, częstość losowań, minimalna głębokość, czas ostrzeżenia, odpoczynek, część zabieranej gotówki).

## Placeholdery (niewidzialne pola kliknięcia)

Placeholder to niewidzialne pole, które stawiasz nad automatem, stołem albo makietą zbudowaną z bloków. Gdy gracz kliknie **prawym przyciskiem mniej więcej w to pole**, otwiera mu się okno przypisanej gry. To samo, co robi NPC, tylko bez wieśniaka: poza małym napisem z nazwą gry widać wyłącznie to, co sam zbudujesz. Technicznie to encja `Interaction` z wybraną szerokością i wysokością, a nazwa gry zapisuje się w jej danych, więc pole przetrwa restart serwera.

Jako operator (`kasyno.admin`) stań w środku podstawy pola (na przykład na środku stołu) i wpisz:

```
/kasyno placeholder spawn <gra> [szerokość] [wysokość]
```

* `<gra>` to ta sama nazwa co u NPC: każda gra (`blackjack`, `hilo`, `sloty`, `ruletka`, `crash`...) albo ekran (`menu`, `kasyno`, `karty`, `sklep`...). Lista: `/kasyno placeholder types`, a w komendzie działa podpowiadanie klawiszem Tab.
* Rozmiary w blokach, z przecinkiem albo kropką (`1,5`). Jedna liczba to szerokość i wysokość naraz, bez liczb pole ma 1 x 1. Dopuszczalny zakres to 0,1 do 16. Pole sięga na boki na pół szerokości od miejsca, w którym stoisz, i w górę na całą wysokość. Nie jest obrócone: to prostopadłościan równoległy do osi świata.
* **Napis nad polem:** nad każdym polem wisi napis z nazwą gry i podpowiedzią (np. **Blackjack**, „Kliknij prawym przyciskiem”), w kolorze z `npc.name-color`. Znika razem z polem. Zmienisz go komendą `/kasyno placeholder napis <tekst>` (działa na pole, na które patrzysz albo najbliższe): tekst może mieć kolory i nowe wiersze w MiniMessage, np. `/kasyno placeholder napis <gold><bold>Stół VIP</bold><newline><gray>Kliknij prawym przyciskiem`. `napis off` wyłącza napis (pole nadal działa), a `napis auto` przywraca domyślny. Własny napis zapisuje się w danych pola i przetrwa restart.
* Samo pole jest niewidzialne i gracze mogą przez nie przechodzić. **Obrys zobaczysz komendą** `/kasyno placeholder pokaz [sekundy]` (domyślnie 20 s, do 300): cząsteczki rysują krawędzie wszystkich pól w promieniu 48 bloków, więc łatwo je ustawić na oko. `/kasyno placeholder pokaz off` wyłącza podgląd wcześniej. Gracz w trybie F3+B widzi też zwykłe ramki hitboxów.
* Prawy przycisk mierzy się od oczu gracza jak przy każdej encji (zasięg zwykłego kliknięcia encji, około 3 bloków), więc pole trzeba postawić tak, żeby dało się je trafić z miejsca, w którym gracz zwykle stoi przy stole. Blok na drodze zasłania pole.

Pozostałe komendy: `/kasyno placeholder remove` (usuwa pole razem z napisem, na pole patrzysz albo najbliższe w promieniu 6 bloków), `/kasyno placeholder list` (gra, współrzędne i rozmiar każdego pola w załadowanych chunkach), `/kasyno placeholder types`, `/kasyno placeholder removeall potwierdz` (usuwa też osierocone napisy po polach, których nie dało się usunąć komendą, np. po `/kill`). Skrót komendy to `/kasyno ph ...`.

## Co jest w GUI

* **Menu główne** (`/kasyno`): kasyno, zarobki, sklep, bank, ekwipunek, biznes i farma, skrzynie, pokój graczy, ranking, profil, pomoc.
* **Gry solo z animacjami:** moneta, sloty z kręcącymi się bębnami, zdrapka (zdrapujesz pola), kości, kamień-papier-nożyce, blackjack, hi-lo, miny, Chicken Road, wieża, plinko, limbo, szansa, rewolwer.
* **Pasek stawki** w każdej grze: min, ½, własna kwota (wpisujesz na czacie), ×2, max. Stawka jest zapamiętana osobno dla każdej gry.
* **Pokój graczy:** jackpot (lobby 45 s), napad na bank (ekipa), pojedynek 50/50 z przyciskami [PRZYJMIJ]/[ODMÓW] na czacie, loteria.
* **Skrzynie:** pasek w stylu CS:GO dla jednej skrzyni albo odsłanianie nagród po kolei dla do 10 naraz.
* **Ochrona w grach:** polisa zwraca stawkę po całkowitej przegranej, tarcza anuluje pierwszy zły ruch (miny, kurczak, wieża, hi-lo, blackjack). Uzbrajasz je w ekwipunku.

Gdy gracz zamknie okno trwającej gry solo (miny, kurczak...), gra się kończy: jeśli coś już ugrał, dostaje wypłatę, w przeciwnym razie stawka wraca. Gry z animacją są rozliczane od razu, a okno tylko pokazuje wynik. Po awarii serwera stawka z przerwanej gry wraca graczowi przy wejściu.

## Komendy

Każda komenda jest też dostępna z prefiksem `kasyno:` (np. `/kasyno:saldo`), gdyby inny plugin zajął nazwę. Wyłączysz je wszystkie opcją `short-commands: false`.

| Komendy | Działanie |
|---|---|
| `/kasyno [ekran\|gra]` | menu główne albo dowolny ekran, sala lub gra po nazwie |
| `/kasyno bank zbuduj\|test\|punkt\|tp\|stop` | scena napadu w świecie: bank, pokaz próbny, własne punkty, miejsce dla widzów |
| `/kasyno arena zbuduj\|test\|punkt\|tp\|stop` | arena pojedynków i jackpota w świecie: plac, pokaz próbny, własne punkty, trybuna |
| `/kasyno pack` | wysyła paczkę z oknami jeszcze raz (`/kasyno pack info` dla admina) |
| `/saldo` `/profil [gracz]` `/top` | saldo na czacie, profil, ranking |
| `/bank` `/wplac <kwota>` `/wyplac <kwota>` `/przelew <gracz> <kwota>` | bank i przelewy (kwoty: `500`, `2k`, `1.5m`, `50%`, `all`) |
| `/sklep` `/kup <przedmiot> [ilość]` `/sprzedaj <przedmiot\|wszystko>` `/ekwipunek` `/uzyj <przedmiot>` `/mnozniki` | sklep i przedmioty |
| `/daily` `/weekly` `/godzinna` `/praca` `/zebraj` `/uliczny` `/przestepstwo` | zarobki jednym poleceniem |
| `/ryby` `/kopalnia` `/polowanie` `/las` `/smietnik` | zbieranie łupów |
| `/prace` `/lesnik` `/gornik` `/rybak` `/sprzatacz` `/kurier` `/poszukiwacz` `/kasyno praca [info\|anuluj]` | prace w terenie: świecące cele w świecie, zlecane przez NPC |
| `/trivia` `/matma` `/okradnij <gracz>` | quizy (GUI) i okradanie (nieudane kończy się więzieniem) |
| `/wiezienie` (`/cela`) | lista zadań więźnia (minigierki, po których się wychodzi) |
| `/zarobki` `/biznes` (`/farma`) `/skrzynie` | menu zarobków, biznesów i skrzyń |
| `/kasyno-gry` `/automaty` `/karty` `/ryzyko` `/live` (`/stoly`) | hol kasyna i sale |
| `/moneta [stawka]` `/sloty` `/zdrapka` `/szansa` `/blackjack` `/hilo` `/kosci` `/knp` `/miny` `/kurczak` `/wieza` `/rewolwer` `/limbo` `/plinko` | otwierają grę (z opcjonalną stawką) |
| `/crash` `/ruletka` `/kolo` `/wyscigi` | siadają przy stole na żywo |
| `/jackpot [stawka]` `/napad` `/pojedynek <gracz> <stawka>` `/loteria [kup <ile>]` | gry z innymi graczami |
| `/pomoc` | instrukcja |

Administrator (`kasyno.admin`): `/kasyno npc ...`, `/kasyno praca promien|stop ...`, `/kasyno smietnik ...`, `/kasyno waypoint ...`, `/kasyno wiezienie ...`, `/kasyno rekin ...`, `/kasyno placeholder ...`, `/kasyno admin give|take|set <gracz> <kwota>`, `/kasyno admin item <gracz> <id> [ile]`, `/kasyno admin cooldown <gracz>`, `/kasyno reload`.

## Uprawnienia i konfiguracja

| Uprawnienie | Domyślnie | Opis |
|---|---|---|
| `kasyno.use` | wszyscy | korzystanie z kasyna i ekonomii |
| `kasyno.admin` | operatorzy | NPC, placeholdery, dawanie monet i przedmiotów, reload, `/kasyno pack info`, więzienie, rekiny, nagrody waypointów |
| `kasyno.prison.bypass` | operatorzy | nie da się go uwięzić i nie obowiązują go ograniczenia w celi |
| `kasyno.shark.bypass` | nikt | rekiny nie atakują tego gracza |

`plugins/KasynoEkonomia/config.yml`:

| Klucz | Znaczenie |
|---|---|
| `short-commands` | krótkie komendy po polsku |
| `big-win-broadcast.enabled`, `.min-net` | informowanie czatu o dużych wygranych |
| `tables.announce` | zaproszenie z przyciskiem [DOŁĄCZ] przy otwarciu stołu |
| `npc.name-color` | kolor napisu NPC |
| `waypoints.enabled`, `.label`, `.label-scale`, `.label-distance`, `.beam`, `.beam-height`, `.marker-distance`, `.max-visible`, `.max-distance`, `.default-color` | własne waypointy (napis z nazwą i odległością o stałym rozmiarze, opcjonalnie kreska): włącznik, napis, jego rozmiar i odległość od gracza, kreska i jej wysokość, ile waypointów widzi gracz naraz, maksymalna odległość, domyślny kolor |
| `waypoints.auto-places`, `.discover-radius`, `.arrive-radius`, `.death-minutes` | miejsca kasyna jako waypointy, promień odkrycia (nagroda), promień dotarcia (prowadzenie), ile minut widać miejsce śmierci |
| `prison.enabled`, `.world`, `.cells`, `.exit`, `.radius`, `.arrest-delay-ticks`, `.sentences.<powód>.tasks`/`.minutes`, `.allowed-commands` | więzienie: włącznik, świat, cele („x y z”), wyjście, promień celi, opóźnienie aresztu, wyroki, dozwolone komendy |
| `shark.enabled`, `.min-distance`, `.max-distance`, `.max-chance`, `.check-seconds`, `.min-depth`, `.warning-seconds`, `.cooldown-seconds`, `.loss-fraction` | rekiny: włącznik, od ilu bloków od lądu ryzyko, maksymalna szansa, częstość losowań, głębokość, czas ostrzeżenia, odpoczynek, część zabieranej gotówki |
| `custom-gui` | `auto` (nowe okna dla graczy z załadowaną paczką), `on` (wszystkim) albo `off` (nikomu) |
| `resource-pack.enabled`, `.url`, `.sha1`, `.required`, `.prompt` | wysyłanie paczki przy wejściu, własny link i suma, wymuszenie, treść pytania |
| `resource-pack.host.enabled`, `.port`, `.address` | wbudowany serwer paczki |

## Ekonomia i uczciwość

Ceny, bonusy, tabele łupów, mnożniki i zwroty gier są **takie same jak w bocie Discord**: plik `src/main/resources/catalog.json` generuje skrypt `tools/gen-catalog.js` z katalogu bota (`node --disable-warning=ExperimentalWarning minecraft-plugin/tools/gen-catalog.js`). Każda gra ma zwrot poniżej 100% (moneta 97,5%, ruletka 97,3%, crash 97%, kurczak i wieża 96%, plinko 94-96%, sloty ok. 95%...), a testy liczą to symulacją. Crash jest uczciwy z natury: szansa dojścia do x2 to 48,5%, więc około połowy rund pada wcześnie.

Dane (portfele, ekwipunki, cooldowny) są **osobne od bota Discord**: gracze Minecrafta mają własne konta w `plugins/KasynoEkonomia/players/<uuid>.json`. Kopia zapasowa to skopiowanie folderu `plugins/KasynoEkonomia/`.

## Budowanie ze źródeł

Potrzebujesz JDK 21 i Maven 3.9+ (Node.js tylko, jeśli zmieniasz paczkę zasobów albo katalog):

```bash
cd minecraft-plugin
node tools/gen-pack.js   # opcjonalnie: odtwarza paczkę zasobów (resourcepack/, KasynoUI.zip, podglądy)
mvn package              # testy + plik target/KasynoEkonomia-1.0.0.jar
mvn test                 # same testy
```

Testy (ponad 400): reguły ekonomii, biznesów, farmy i skrzyń, księga stawek przy stołach, matematyka wszystkich gier (zwrot < 100%, rozkład crasha i limbo), scenariusze GUI na atrapie serwera Paper (MockBukkit): otwarcie każdego ekranu, zagranie w każdą grę solo i sprawdzenie, że pieniądze zgadzają się co do monety, zamykanie gier w toku, sklep, komendy, NPC, jackpot i pojedynek. Do tego **pojedynek i jackpot na arenie w świecie** (ruch figur, odliczanie 3-2-1, poza przegranego, okrąg i hamujące podświetlenie, fajerwerki zwycięzcy, na atrapie świata oraz z MockBukkit: arena stawiana komendą, pieniądze rozliczone przed sceną i nietknięte w trakcie, okno jako zapas, zajęta arena, przerwanie pokazu, sprzątanie po znaczniku), **pokaz napadu w świecie** (ruch ekipy po scenie, eskorta złapanych, drzwi i lampy, sprzątanie, bank stawiany komendą, pokaz na atrapie świata z figurami), **sceny** (napad: zwrot dla każdej wielkości ekipy i spójność scenariusza, sumy pieniędzy po napadzie, jackpot, pojedynek, loteria, sceny zarobków i ich anulowanie), **stoły na żywo** (kilku graczy w jednej rundzie, rozliczenie dokładnie raz, auto-wypłata, zwroty przy zamknięciu okna i wyłączeniu serwera, odzyskiwanie stawek po awarii) i **paczka zasobów** (spójność plików, wymiary i okienka na slotach, każda użyta ikona i każde tło istnieje, wygląd tylko dla graczy z paczką, talia 52 kart i rewers, modele 3D kart, karty w blackjacku i hi-lo, osobny tytuł każdego rodzaju okna) oraz **więzienie** (współrzędne cel i brak zgadywania trzeciej, losowanie wszystkich cel, wyrok i jego zapis, ograniczenia w celi: okna, komendy, obrażenia, perła, zawracanie, respawn, wyjście po zadaniach, po czasie i po wejściu na serwer, trzy minigierki i egzamin od logiki po kliknięcia w oknie, podpięcie pod przestępstwo, okradanie i napad), **rekin** (szansa rosnąca z odległością, model i jego animacja, cały scenariusz ataku tick po tiku, ucieczka w czasie ostrzeżenia, utrata połowy gotówki i śmierć, komendy, prawdziwe bryłki i wykrywanie wody), **waypointy z sensem** (miejsca kasyna, prowadzenie, nagrody za odkrycie raz na gracza, dom, miejsce śmierci, zgodność ze starymi plikami) oraz **placeholdery** (pole o zadanym rozmiarze, prawy przycisk otwiera każdą grę i ekran, druga ręka i cudze encje nic nie robią, pola przeżywają restart, napisy nad polami (domyślny, własny, wyłączony, sprzątanie osieroconych), komendy administratora, obrys, podpowiedzi Tab).

> **Stan sprawdzenia:** całość przechodzi testy na atrapie serwera Paper (MockBukkit). Na prawdziwym serwerze 1.21.11 (nagrania w [głównym README](../README.md)) oglądane były gry w GUI, arena pojedynków, śmietniki, praca kuriera i rekin. Pozostałe funkcje (m.in. napad na bank w świecie, więzienie z minigierkami, waypointy) mają na razie tylko testy automatyczne. Jeśli coś wygląda inaczej niż w opisie (czcionka znaków specjalnych, położenie tła okna, zachowanie NPC), daj znać.
