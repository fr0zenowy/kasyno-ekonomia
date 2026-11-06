# SportPlugin: siatkówka, koszykówka i ping-pong

Plugin dla **Paper 1.21.11** (Java 21). Trzy dyscypliny z prawdziwą fizyką piłki, zasadami, punktacją i gotowymi boiskami, które buduje komenda. Zastępuje stary `SiatkowkaPlugin` (ma te same komendy `/siatkowka`), więc **usuń** `SiatkowkaPlugin-1.0.0.jar` z folderu `plugins`, zanim wgrasz ten plugin.

> **Uczciwie o testach:** logika (fizyka piłki, zasady, rzuty, budowa boisk, przebieg całych meczów z botami) jest sprawdzona 260 testami na atrapie serwera Paper (MockBukkit). **Siatkówka, koszykówka i ping-pong były już oglądane na prawdziwym serwerze Paper 1.21.11 (nagrania w [głównym README](../README.md)).** Wygląd piłki (ItemDisplay) i płynność jej ruchu najlepiej ocenią Twoi gracze. Jeśli coś wygląda albo działa inaczej niż w opisie, daj znać.

## Instalacja

1. Usuń z `plugins/` stary `SiatkowkaPlugin-1.0.0.jar` (i jego folder, jeśli nie chcesz starych boisk).
2. Wgraj `SportPlugin-1.0.0.jar` do `plugins/` i zrestartuj serwer.
3. Jako operator stań na **środku linii końcowej** przyszłego boiska, **patrz w głąb boiska** i wpisz:
   * `/siatkowka stworz hala1` (boisko 18 x 9, siatka 2,43 m)
   * `/koszykowka stworz kosz1` (boisko 28 x 15, kosze na 3,05 m)
   * `/pingpong stworz stol1` (hala 12 x 8 ze stołem na środku, stół 4,1 x 2,3 m z siatką)

   Możesz podać wymiary: `/siatkowka stworz hala1 20 10` (długość parzysta), `/koszykowka stworz mala 20 11`. Boisko powstaje od Ciebie w głąb, w kierunku patrzenia (jeden z czterech kierunków świata). **Teren nad boiskiem (8 bloków w górę) i 3 bloki wokół zostają oczyszczone**, podłoga i linie powstają z bloków, a siatka, słupki, tablice i obręcze z encji `BlockDisplay` o dokładnych wymiarach.
4. Gracze: `/siatkowka dolacz hala1` (albo `/koszykowka dolacz kosz1`, `/pingpong dolacz stol1`). Drużyna jest dobierana tak, żeby były równe, albo wybierasz `a` (Czerwoni) / `b` (Niebiescy): `/siatkowka dolacz hala1 b`.
5. Gdy w obu drużynach jest co najmniej jeden gracz, po 10 sekundach mecz startuje sam (admin może od razu: `/siatkowka start natychmiast`).

Do czasu rozpoczęcia gracze stoją na swoich połowach boiska. Po meczu (albo po `/sport opusc`) wracają dokładnie tam, gdzie byli, z poprzednim trybem gry. W trakcie meczu mają tryb przygody, nie tracą życia ani głodu, nie niszczą bloków i nie wychodzą z boiska pieszo. Stan sprzed meczu jest zapisany na dysku, więc po awarii serwera gracz wraca na swoje miejsce przy wejściu.

## Siatkówka

Sterowanie (klawisze myszy działają, nawet gdy nic nie trzymasz):

| Akcja | Jak |
|---|---|
| Odbicie (bagger) | **LPM** przy piłce (maks. 3 bloki od oczu). Patrz tam, gdzie ma polecieć: w miejsce na boisku rywala (patrząc na podłogę) albo wprost w stronę siatki, a piłka sama przejdzie nad nią |
| Wystawienie | **Shift + LPM**. Patrz na kolegę (stożek 30 stopni): piłka poleci wysoko, miękko nad niego |
| Atak | **Skok + LPM**, gdy piłka jest wysoko (na wysokości oczu albo wyżej): mocne uderzenie w miejsce, na które patrzysz |
| Blok | **Skok + LPM** przy siatce (do 1,8 bloku), gdy piłka leci od rywala tuż przy siatce |
| Zagrywka | **LPM** podrzuca piłkę, **drugie LPM** ją uderza (masz 10 s na rozpoczęcie) |

**Kliknięcie z wyprzedzeniem:** jeśli klikniesz chwilę przed tym, jak piłka wejdzie w zasięg (do ćwierć sekundy), uderzenie czeka i wykona się samo, gdy piłka nadleci; po upływie tego czasu pasek nad skrótami mówi, dlaczego nic się nie stało (za daleko, piłka za plecami albo po drugiej stronie siatki). Piłkę tuż nad głową odbijesz też wtedy, gdy patrzysz w siatkę.

Jakość uderzenia (gwiazdki na pasku nad paskiem skrótów) zależy od tego, jak blisko idealnego punktu kontaktu jest piłka. Złapana na końcu zasięgu leci mniej celnie niż piłka tuż przed Tobą.

**Połowy i linia zagrywki:** nie da się wejść na połowę rywali (siatka jest granicą; skok przez siatkę też cofa). Zagrywasz **zza linii końcowej** (stoisz poza boiskiem, na wolnym pasie), a jeśli przed uderzeniem wejdziesz na boisko albo podrzucisz piłkę i w trakcie przekroczysz linię, to **błąd stóp: punkt dla rywali**.

**Gra 1 na 1 (albo gdy drużyna ma jednego gracza):** jeden gracz może odbić piłkę **do trzech razy z rzędu** i **wystawić ją sobie** (Shift + LPM albo odbicie z patrzeniem mocno w górę: piłka wznosi się wysoko tuż przed Tobą), a potem zaatakować. Zasada „ten sam gracz nie odbija dwa razy” działa tylko wtedy, gdy w drużynie jest więcej niż jedna osoba. Przy trzecim (ostatnim) odbiciu piłka zawsze idzie na drugą stronę, nawet gdy patrzysz w górę, żeby nie zrobić czwartego odbicia, a na pasku nad skrótami widzisz numer odbicia (np. „odbicie 2/3”). Wystawienie sobie wymaga tylko Shift + LPM albo spojrzenia w górę o ponad 20 stopni, a drugie kliknięcie możesz oddać już pół sekundy po pierwszym (wcześniej gra ignorowała je przez całą sekundę, więc piłka, która spadała do Ciebie po wystawieniu, bywała „nie do odbicia”).

Zasady (uproszczone z FIVB): do **3 odbić na drużynę**, ten sam gracz nie odbija dwa razy z rzędu (chyba że po bloku), blok nie liczy się do trzech odbić, przez siatkę nie sięga się po piłkę rywala (tylko blok), piłka na własnej połowie to punkt dla rywala, aut to punkt dla rywala drużyny, która dotknęła ją ostatnia, błąd zagrywki (brak uderzenia w czasie, piłka na własnej połowie) to punkt dla rywala. Rally point: set do **25** z przewagą 2, wygrywa pierwsza drużyna z **2 setami**, set decydujący do 15, po każdym secie zmiana stron. Serwis przechodzi na drużynę, która wygrała akcję, a zagrywają kolejni gracze w rotacji.

## Koszykówka

| Akcja | Jak |
|---|---|
| Wzięcie piłki | **podejdź** do niej albo kliknij **LPM**, gdy jest w zasięgu ręki (rzut sędziowski i wznowienie: tylko drużyna wznawiająca; gra mówi, kto wznawia) |
| Kozłowanie | automatycznie, gdy idziesz z piłką |
| Osłona piłki | trzymaj **Shift** z piłką: piłka schodzi nisko, po stronie przeciwnej do najbliższego obrońcy (przechwyt jest wtedy znacznie trudniejszy) |
| Rzut | **LPM** z piłką. Patrz w stronę kosza (stożek 42 stopnie): rzut jest wycelowany fizycznie poprawnie, z błędem zależnym od odległości |
| Rzut w wyskoku | skocz i naciśnij LPM w najwyższym punkcie: znacznie celniej |
| **Dwutakt** | **bieg w stronę kosza** i **LPM** z odległości do ok. 4,8 m: gracz robi najście (krótki wyskok) i oddaje miękki rzut z bliska, dużo celniejszy niż zwykły |
| **Wsad** | **bieg w stronę kosza + skok (spacja) + LPM** w powietrzu, bliżej niż ok. 2,6 m od obręczy. To trudny, efektowny rzut: pasek podaje szansę (typowy wsad ok. 60%, z obrońcą ok. 35%), która rośnie z wysokością wyskoku i prędkością biegu. Niecelny wsad odbija się od obręczy, a obrońca w wyskoku tuż obok może go zablokować. Po próbie wsad odpoczywa 10 s (w tym czasie wyskok pod koszem to dwutakt) |
| Podanie | **F** z piłką, do kolegi, na którego patrzysz (z wyprzedzeniem, jeśli biegnie) |
| Przechwyt | **LPM** przy rywalu, który trzyma piłkę (do 2 bloków, patrząc na niego); szansa zależy od sytuacji, a faul daje tylko kontakt (patrz niżej) |
| Złapanie piłki | **LPM** przy piłce, której nikt nie trzyma (podanie kolegi, zbiórka, podanie rywali, luźna piłka): łapiesz ją z zasięgu ręki, nie musisz wchodzić na nią nogami |
| Blok | **LPM** przy piłce z rzutu rywali w locie (najlepiej w wyskoku): blok zabiera im punkty. Rzut w locie nie wpada nikomu „w ręce”, łapie się go dopiero po odbiciu od obręczy |
| Rzut osobisty | **LPM**, gdy stoisz na linii rzutów osobistych (po faulu) |

**Przechwyt** liczy się jak w prawdziwej koszykówce: piłka nisko przy podłodze (dolna faza kozła) jest znacznie łatwiejsza do wybicia niż ta przy ręce, łatwiej od tyłu i z bliska, trudniej, gdy kozłujący osłania piłkę (Shift), biegnie sprintem albo jest w wyskoku. Szansa bazowa to `koszykowka.szansa-przechwytu`, Nieudana próba z przodu jest czysta i nie jest faulem (próba po złym kliknięciu z daleka prawie nie blokuje następnej, a gra mówi, dlaczego nic się nie stało).

**Faule** (można je wyłączyć: `koszykowka.faule: false`) wymagają kontaktu, a komunikat zawsze mówi, za co: **zahaczenie od tyłu** albo **wejście w osłoniętą piłkę** przy nieudanym przechwycie (faul osobisty), **uderzenie po rękach rzucającego** (nieudany przechwyt na graczu w trakcie dwutaktu albo wsadu, albo spóźniony blok: machnięcie, gdy piłka już wyleciała z rąk rzucającego, a ten stoi tuż przed Tobą, zaś blok trafiający w piłkę jest czysty), **faul w ataku** (bieg z piłką prosto w obrońcę, który stoi w miejscu i patrzy na atakującego: strata piłki, nie liczy się do premii drużyny). Samo stanie obok rywala, machnięcie w pustkę i przechwyt z przodu nie są faulem. Faul osobisty bez rzutów oddaje piłkę drużynie poszkodowanej z linii bocznej. Faul na rzucającym, a także **każdy faul osobisty po przekroczeniu premii drużyny** (`koszykowka.faule-premia`, domyślnie od 5. faulu) kończy się **rzutami osobistymi**: dwa, a trzy, gdy rzut był za trzy punkty i nie wpadł. Jeśli rzut wpadł mimo faulu, punkty się liczą i dochodzi jeden rzut osobisty. Po 6 faulach osobistych (`koszykowka.faule-limit`, 0 = bez limitu) gracz wylatuje z meczu.

**Rzuty osobiste:** wykonawca staje na linii (5,8 m od linii końcowej, na środku), pozostali stoją nieruchomo z boku. Po chwili wykonujesz rzut **LPM** (po 10 s rzut oddaje się sam). Rzut osobisty jest celniejszy niż zwykły z tej odległości (`celnosc-rzutow-osobistych`) i liczy się 1 punkt. Po chybionym ostatnim rzucie piłka jest w grze (zbiórka), a po celnym ostatnim wznawia rywal zza linii końcowej. Zegary stoją, dopóki trwają rzuty.

Rzut z odległości od **6,75 m** od obręczy jest wart **3 punkty**, bliższy 2. Obrońca tuż przy rzucającym i bieg psują celność, a dwutakt i wsad z bliska są najpewniejsze. Skuteczność dla przeciętnego gracza to ok. 100% z 1,5 m, ok. 50% z 4,5 m, ok. 30% zza łuku (zmienisz mnożnikiem `koszykowka.celnosc`). Piłka odbija się od tablicy i obręczy według prawdziwej fizyki, rzuty „na raz” i „po obręczy” wpadają albo nie.

Mecz trwa **5 minut** (czas liczy się tylko, gdy piłka jest w grze) albo do zadanej liczby punktów (`koszykowka.punkty-do-wygrania`). Zegar akcji **24 s** (po trafieniu w obręcz nie mniej niż 14 s), po jego upływie piłkę dostaje rywal. Aut (piłka albo gracz z piłką za linią) oddaje piłkę rywalom. Po koszu piłkę wznawia drużyna, która straciła punkty, zza własnej linii końcowej. Remis po czasie to dogrywka (60 s, a przy `dogrywka-sekundy: 0` złoty kosz). Rzut puszczony przed syreną jeszcze się liczy.

## Ping-pong

Hala z jednym stołem. Stół ma proporcje prawdziwego (2,74 x 1,525 m, blat na 76 cm, siatka 15,25 cm) pomnożone przez **skalę 1,5** (`pingpong.skala`), bo w skali 1:1 piłeczka jest za szybka na odbijanie kliknięciem. Gra 1 na 1, a gdy w drużynach jest po dwóch graczy, w deblu (odbijacie na zmianę).

| Akcja | Jak |
|---|---|
| Serwis | **LPM** podrzuca piłeczkę, **drugie LPM** ją uderza (gdy jest co najmniej 20 cm nad blatem). Stoisz **za linią końcową stołu**; uderzenie z piłeczką nad blatem to błąd |
| Odbicie | **LPM** przy piłeczce (do 2,4 bloku od oczu). Patrz w punkt **na stole rywala**, żeby tam ją posłać, albo po prostu w jego stronę (wtedy leci na środek jego połowy) |
| Podcięcie | **Shift + LPM**: wolna, wysoka piłeczka, wpada krótko |
| Smecz | **LPM** w piłeczkę wysoko nad siatką, **patrząc w dół**: bardzo szybkie uderzenie, ale mniej celne |

Kliknięcie chwilę przed tym, jak piłeczka wejdzie w zasięg, czeka do ćwierć sekundy i uderza samo, gdy ta nadleci. Jakość uderzenia (gwiazdki nad paskiem skrótów) zależy od tego, jak blisko idealnego punktu kontaktu (na wysokości pasa, tuż przed Tobą) jest piłeczka. Dobrze ustawiony gracz trafia niemal zawsze, spóźniony częściej gra w siatkę albo w aut.

Zasady (uproszczone z ITTF): **gem do 11 punktów z przewagą 2**, wygrywa pierwsza drużyna z **2 gemami** (`pingpong.gemy-do-wygrania`). Serwis zmienia się **co 2 punkty**, a od stanu 10:10 co punkt. W następnym gemie zaczyna druga drużyna i strony się zamieniają; w gemie decydującym strony zmieniają się też po 5 punktach.

* **Serwis** musi odbić się najpierw od **Twojej połowy**, przejść nad siatką i odbić od połowy rywala. Inaczej to błąd i punkt dla rywala. Jeśli piłeczka dotknie siatki, a poza tym serwis jest dobry, jest **powtórka** (let). Brak uderzenia w ciągu 15 s to też punkt dla rywala.
* **Odbierający** musi odbić piłeczkę **po jej odbiciu od swojej połowy**. Odbicie z powietrza (wolej) to błąd i punkt dla serwującego (można wyłączyć: `pingpong.blad-woleja: false`).
* Każde odbicie ma trafić najpierw w połowę rywala. Piłeczka, która odbije się od Twojej połowy, spadnie poza stół, uderzy w bok stołu albo w siatkę i wróci, to punkt dla rywala. Dotknięcie siatki w trakcie wymiany nic nie zmienia.
* Dwa odbicia z rzędu tej samej drużyny to błąd (w deblu partnerzy muszą się zmieniać).
* Nie wejdziesz na stół ani na połowę rywala (stół to przeszkoda, siatka jest granicą).

## Komendy

| Komenda | Co robi |
|---|---|
| `/siatkowka` `/koszykowka` `/pingpong` `/sport` | pomoc ze sterowaniem (`pomoc`) |
| `lista` | boiska i ich stan (wolne, zapisy, mecz trwa) |
| `dolacz <boisko> [a\|b]` | zapisz się do meczu |
| `opusc` (też `/sport opusc`) | wyjdź z meczu |
| `start` | rozpocznij odliczanie (gdy obie drużyny mają graczy) |
| `tp <boisko>`, `info [boisko]` | teleport na boisko, skład drużyn |
| `stworz <nazwa> [dlugosc] [szerokosc]` | (admin) zbuduj boisko (albo halę ze stołem) tam, gdzie stoisz i patrzysz |
| `usun <boisko>` | (admin) usuń boisko (podłoga z bloków zostaje) |
| `przebuduj <boisko>` | (admin) postaw je od nowa |
| `start natychmiast [boisko]`, `stop [boisko]` | (admin) wymuś start albo zakończ mecz |
| `reload` | (admin) wczytaj ponownie config i listę boisk |

Uprawnienia: `sport.use` (domyślnie dla wszystkich) i `sport.admin` (domyślnie operatorzy).

## Ustawienia (`plugins/SportPlugin/config.yml`)

Wszystkie klucze mają domyślne wartości w kodzie. Najważniejsze: czas odliczania, zasięg uderzenia (`pilka.zasieg`), wygładzanie ruchu piłki (`pilka.interpolacja-ticki`, 1 to płynnie i prawie bez opóźnienia), przedmioty piłek (domyślnie śnieżka i magmowy krem), punkty i sety siatkówki, czas i celność koszykówki, materiały podłóg i linii.

**Modele 3D piłek:** domyślnie `pilka.model-siatkowka` to `kasyno:ball_volley`, a `pilka.model-koszykowka` to `kasyno:ball_basket`. Te modele (niskopoligonowe kule z teksturą rysowaną kodem) są w paczce zasobów **`KasynoUI.zip` z pluginu Kasyno w tej samej wersji co ta**, więc musisz wgrać nową paczkę na hosting i zaktualizować jej adres i sumę SHA-1 w `server.properties`, a gracze muszą ją mieć załadowaną. **Bez tej paczki piłka wygląda jak fioletowo-czarna kostka „brak modelu”.** Jeśli nie chcesz paczki, wpisz `brak` w oba pola (puste pole, jakie miał config z pierwszej wersji pluginu, oznacza model domyślny): piłka jest wtedy zwykłym przedmiotem (`pilka.przedmiot-*`) rysowanym jako ikona zwrócona do gracza. Model 3D kręci się w locie i toczy po podłodze.

**Model nie działa?** (1) Paczka musi być załadowana u gracza (po wejściu serwer ma wysłać okno pobierania; sprawdź w logu, czy nie ma `FAILED_DOWNLOAD`). (2) Musi to być paczka z tej samej wersji pluginu Kasyno co ten plugin: modele i ich tekstury leżą w niej w `assets/kasyno/textures/item/ball/` i `models/ball/` (starsza wersja miała je w katalogu, którego gra nie ładuje do atlasu tekstur, i piłka była fioletowo-czarna). (3) Po zmianie `config.yml` wpisz `/sport reload`; nowe piłki dostają zmiany od następnej piłki w meczu.

**Ping-pong** (sekcja `pingpong`): `dlugosc-boiska`, `szerokosc-boiska` (hala), `skala` (rozmiar stołu; każdy stół zachowuje skalę z chwili budowy), `gemy-do-wygrania`, `punkty-w-gemie`, `max-graczy-w-druzynie` (1 = tylko single), `zasieg`, `czas-na-serwis-sekundy`, `celnosc` (mnożnik celności uderzeń), `blad-woleja`, `grawitacja` (mniej = wolniejsza, łatwiejsza gra; domyślnie 7) i materiały (`material-stolu` to domyślnie niebieski beton). Piłeczka to zwykły przedmiot (`pilka.przedmiot-pingpong`, domyślnie śnieżka), bez modelu z paczki.

Nowe ustawienia koszykówki (wszystkie w sekcji `koszykowka`): `techniki` (dwutakt i wsad), `celnosc-dwutaktu`, `wsad-trudnosc` (1 = normalnie, 2 = dwa razy trudniej, 0 = wsad zawsze wpada), `wsad-odpoczynek-sekundy`, `faule`, `szansa-faulu` (mnożnik szansy odgwizdania w sytuacjach z kontaktem: 1 = zawsze, 0,5 = co drugi kontakt, 0 = nigdy), `faule-premia`, `faule-limit`, `celnosc-rzutow-osobistych`, `szansa-przechwytu`.

## Jak to działa (dla ciekawych)

* **Fizyka** (`pl.sport.core`): kula z grawitacją i kwadratowym oporem powietrza (osobne wartości dla piłek), odbicia od podłogi, siatki, słupków, tablic i obręczy (torus), krokowanie z podkrokami, żeby szybka piłka nie przeskoczyła przez cienką przeszkodę. Wszystko liczy się w metrach (blok = metr) w układzie lokalnym boiska.
* **Rzuty** (`Ballistics`): ten sam całkownik, co w fizyce, wyznacza prędkość i kąt, które trafiają w cel z oporem powietrza. Model uderzenia siatkarskiego i rzutu do kosza dokłada do tego błąd zależny od jakości i sytuacji.
* **Zasady** (`VolleyGame`, `BasketGame`): czyste klasy bez Bukkita, dlatego każdy przypadek (czwarte odbicie, blok, zegar akcji, rzut w trakcie syreny, dogrywka, faul w trakcie lotu piłki, rzuty osobiste, premia, limit fauli, gra 1 na 1, błąd stóp przy zagrywce) jest przetestowany osobno.
* **Ping-pong** (`pl.sport.pingpong`): `PingPongTable` to geometria i fizyka stołu (blat sprężysty jak prawdziwy, cienka siatka), `PingPongGame` to czysty silnik zasad (maszyna stanów: serwis, odbicie od własnej połowy, od połowy rywala, odbicie rakietką), a `PingPongHitModel` zamienia spojrzenie gracza w tor lotu. Tor serwisu wyszukuje symulacja na prawdziwej fizyce (ma odbić się od własnej połowy, przejść nad siatką i spaść blisko celu), a zwykłe odbicia liczy ten sam całkownik z oporem powietrza co w innych dyscyplinach.
* **Przechwyt i faule** (`StealModel`): szansa przechwytu to jawny wzór z mnożnikami (piłka nisko, od tyłu, osłona, sprint, wyskok, odległość), a faul po nieudanej próbie zależy tylko od kontaktu (od tyłu, rzucający w akcji, osłonięta piłka), więc zachowanie jest przewidywalne i da się je przetestować.
* **Piłka** w świecie to `ItemDisplay` (z interpolacją ruchu) i cień na podłodze. Mecz liczy się w tickach, więc cooldowny i zegary nie zależą od zegara ściany.

## Znane ograniczenia

* Boiska budują się tylko w czterech kierunkach świata i tylko komendą (nie ma jeszcze wskazywania własnej hali).
* Gra nie wymusza ustawienia graczy w polach rotacji ani linii ataku w siatkówce. W koszykówce faule są uproszczone (nie ma fauli technicznych, kroków, podwójnego kozła, 3 sekund w „trapezie”), a sędzia zagwizdał albo nie na podstawie szansy, a nie pełnej analizy kontaktu.
* Model piłki to niskopoligonowa kula z cuboidów (ograniczenie formatu modeli Minecrafta): z góry widać lekko ośmiokątny kształt.
* Ping-pong nie ma wirowania piłeczki (topspin, backspin), a stół nie ma kolizji z graczami: pilnuje go ograniczenie ruchu. W deblu serwis nie jest przekątny (serwuje partner na zmianę, ale nie ma wskazanej połowy odbierającego). Rakietki nie ma w ręku, bo gra reaguje na LPM.
* Brak trybu obserwatora: widzowie po prostu stoją przy boisku.
* Wygląd i odczucia ze sterowania (zasięg, opóźnienie ruchu piłki przy dużym pingu) mogą wymagać dopasowania w `pilka.*`.
