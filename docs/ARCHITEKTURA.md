# Architektura

Dokument dla osoby, która ma zrozumieć, jak to jest zbudowane i jak to utrzymywać. Skupia się na plugin Kasyno (największy) i zaznacza różnice w SportPlugin i bocie.

## Mapa pakietów (plugin Kasyno, `pl.kasyno`)

| Pakiet | Za co odpowiada |
|---|---|
| `core` | ekonomia bez Bukkita: portfel, bank, cooldowny, szansa i losowanie (`Eco`), zarobki (`Earn`), napad jako czysty scenariusz (`Heist`), zapis graczy (`DataStore`, JSON przez Gson), katalog przedmiotów (`catalog.json`) |
| `gui` | okna: bazowa klasa `Gui` (zawsze 6 rzędów, kliknięcia zawsze anulowane), `Screens` (wejście po nazwie), budowanie ikon, dźwięki i efekty |
| `games` | rejestr gier, lobby napadu i jackpota (`Rooms`), sceny |
| `table` | stoły na żywo: jedna runda, wielu graczy, wspólne rozliczenie |
| `show` | pokazy w świecie: bank (napad), arena (pojedynek, jackpot), figury graczy ze skórkami |
| `work`, `bin` | prace w terenie z celami w świecie, śmietniki |
| `waypoint` | waypointy: napisy o stałym rozmiarze, miejsca kasyna, prowadzenie, nagrody, punkty osobiste |
| `prison`, `shark` | więzienie z minigierkami, rekin z modelem i animacją |
| `npc`, `pack` | NPC i placeholdery otwierające okna, paczka zasobów z wbudowanym serwerem HTTP |
| `cmd` | komendy (`/kasyno ...` i krótkie po polsku) |

## Zasady, które się powtarzają

**1. Logika bez Bukkita, Bukkit przez cienkie interfejsy.** Każda większa funkcja ma część „mózg” (czysty Java) i część „ręce” (encje, bloki, wiadomości), połączone małym interfejsem:

| Funkcja | Mózg (testowany bez serwera) | Ręce (podmieniane w testach) |
|---|---|---|
| Rekin | `SharkRules`, `SharkModel`, `SharkChoreo` | `SharkBackend`, `WaterProbe` |
| Prace w terenie | `JobPlanner`, `Compass` | `NodeBackend`, `Terrain` |
| Waypointy | `WaypointView`, `ViewAnchor` | `MarkerBackend` |
| Więzienie | `PrisonRules`, `MashRound`, `ReflexRound`, `SimonRound` | `PrisonService` (Bukkit) |
| Napad | `Heist`, `HeistFrames` | `ShowService` |

Testy jednostkowe liczą np. cały scenariusz ataku rekina tick po ticku (kto, gdzie, kiedy gryzie), a testy integracyjne na atrapie serwera (MockBukkit) sprawdzają klejenie z Bukkitem.

**2. Czas jest wstrzykiwany.** Plugin nie czeka „na prawdziwy zegar” w logice: cooldowny i wyroki liczą się od zegara `Eco`, a animacje od ticków serwera. Dzięki temu testy przewijają minuty w milisekundy i są powtarzalne.

**3. Pieniądze zawsze się zgadzają.** Gra pobiera stawkę, rozlicza wynik i dopiero pokazuje animację (animacja tylko odgrywa wynik). Stawka w toku jest zapisana w danych gracza, więc po awarii serwera wraca do portfela. Testy sprawdzają sumy portfeli co do monety, także przy napadach z różną wielkością ekipy, a symulacje sprawdzają, że każda gra ma zwrot poniżej 100%.

**4. Dane graczy da się rozbudowywać.** Gracz to jeden obiekt `PlayerData` zapisywany do JSON. Dodanie pola nie psuje starych zapisów (brakujące pola dostają wartość domyślną), a zapis idzie w tle: serializacja w wątku głównym, plik w osobnym wątku, co 30 sekund.

**5. Konfiguracja ma wartości domyślne w kodzie i w pliku.** Nowy klucz w `config.yml` działa od razu także na starej konfiguracji serwera, bo kod czyta go z wartością domyślną.

**6. Encje w świecie są tymczasowe i oznaczone.** Napisy waypointów, śmietniki, model rekina i pokazy to encje `Display` z znacznikiem w danych (PDC). Znikają przy restarcie, nie zapisują się w świecie, a po awarii plugin sprząta zabłąkane przy starcie. Napisy waypointów są widoczne tylko dla właściciela (każdy gracz ma własne), więc odległość w napisie jest zawsze jego.

**7. Wszystko, co robi administrator, jest za uprawnieniem.** `kasyno.admin` dla komend zarządzających, osobne wyjątki `kasyno.prison.bypass` i `kasyno.shark.bypass`. Funkcje da się wyłączyć w `config.yml` bez usuwania danych.

## Jak coś dodać

* **Nowa gra solo:** klasa okna w `games/instant` albo `games/held`, wpis w `GameRegistry`, ikona i tło w paczce zasobów (`tools/pack`). Test w `GuiFlowTest` zagra ją i sprawdzi rozliczenie.
* **Nowa funkcja w świecie:** najpierw czysta logika z testami, potem cienki backend, na końcu komenda administratora i sekcja w `config.yml`. Wzór: pakiet `shark`.
* **Nowa komenda krótka:** wpis w `cmd/Commands`, a podkomenda `/kasyno ...` w `RootCommand`.

## SportPlugin

Ta sama zasada: `pl.sport.core` to czysta fizyka (opór powietrza, odbicia, rozwiązywanie rzutów balistycznych), `volley.VolleyGame` i `basket.BasketGame` to silniki zasad bez Bukkita, a `match.*`, `arena.*` i `listener` kleją je z serwerem. Mecz biegnie na własnym zegarze 50 ms. Testy obejmują całe mecze rozgrywane przez boty.

## Bot Discord

Moduły w `src/commands` (jedna komenda na plik), wspólne reguły w `src/lib` (ekonomia, katalog, gry), SQLite jako magazyn, wersja przeglądarkowa w `src/web`. `npm test` odpala scenariusze, które wywołują tysiące komend i sprawdzają rozliczenia co do monety (`test/smoke.js`), osobny test zwrotów (`test/rtp.js`) i testy strony (`test/web.js`).

## Znane ograniczenia

* Brak testów obciążeniowych na dużej liczbie graczy.
* Część funkcji w świecie (napad, więzienie, waypointy z nagrodami) była sprawdzona tylko na atrapie serwera.
* Paczka zasobów musi być wgrana po stronie klienta, żeby okna miały własny wygląd (bez niej okna wyglądają zwyczajnie, ale działają).
