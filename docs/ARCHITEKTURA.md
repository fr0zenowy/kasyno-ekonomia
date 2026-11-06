# Architektura

Krótko o tym, jak jest poukładany plugin Kasyno (największy z trzech) i czym różnią się SportPlugin i bot.

## Pakiety (`pl.kasyno`)

| Pakiet | Co w nim jest |
|---|---|
| `core` | ekonomia bez Bukkita: portfel, bank, cooldowny, losowanie (`Eco`), zarobki (`Earn`), napad jako sam scenariusz (`Heist`), zapis graczy (`DataStore`), katalog przedmiotów |
| `gui` | okna. Bazowa klasa `Gui` ma zawsze 6 rzędów i anuluje wszystkie kliknięcia, `Screens` otwiera okno po nazwie |
| `games` | rejestr gier, lobby napadu i jackpota (`Rooms`), sceny |
| `table` | stoły na żywo: jedna runda, wielu graczy, wspólne rozliczenie |
| `show` | pokazy w świecie: bank do napadu, arena, figury graczy ze skórkami |
| `work`, `bin` | prace w terenie z celami w świecie i śmietniki |
| `waypoint` | waypointy: napisy o stałym rozmiarze, miejsca kasyna, prowadzenie do celu, nagrody, dom i miejsce śmierci |
| `prison`, `shark` | więzienie z minigierkami i rekin z modelem i animacją |
| `npc`, `pack` | NPC otwierający okna, paczka zasobów z małym serwerem HTTP |
| `cmd` | komendy (`/kasyno ...` i krótkie, np. `/saldo`) |

## Zasady, które się powtarzają

**Logika osobno, Bukkit osobno.** Każda większa funkcja ma część z samą logiką (zwykła Java, bez serwera) i cienką część, która robi rzeczy w świecie. Łączy je mały interfejs, który w testach można podmienić.

| Funkcja | Sama logika | Część w świecie |
|---|---|---|
| Rekin | `SharkRules`, `SharkModel`, `SharkChoreo` | `SharkBackend`, `WaterProbe` |
| Prace w terenie | `JobPlanner`, `Compass` | `NodeBackend`, `Terrain` |
| Waypointy | `WaypointView`, `ViewAnchor` | `MarkerBackend` |
| Więzienie | `PrisonRules`, `MashRound`, `ReflexRound`, `SimonRound` | `PrisonService` |
| Napad | `Heist`, `HeistFrames` | `ShowService` |

Dzięki temu np. cały atak rekina da się przeliczyć tick po ticku w teście jednostkowym, a testy na MockBukkit sprawdzają tylko, czy wszystko dobrze współpracuje z serwerem.

**Czas jest podawany z zewnątrz.** Cooldowny i wyroki liczą się od zegara w `Eco`, a animacje od ticków serwera. W testach można więc przewinąć minuty w ułamku sekundy i wynik zawsze jest taki sam.

**Pieniądze mają się zgadzać.** Gra najpierw pobiera stawkę i rozlicza wynik, a dopiero potem pokazuje animację, która tylko odgrywa to, co już się stało. Stawka w trakcie gry jest zapisana w danych gracza, więc po awarii serwera wraca do portfela. Testy sprawdzają sumy portfeli co do monety, a symulacje pilnują, żeby każda gra miała zwrot poniżej 100%.

**Dane graczy.** Gracz to jeden obiekt `PlayerData` zapisywany jako JSON. Nowe pole nie psuje starych zapisów, bo brakujące pola dostają wartość domyślną. Zapis idzie w tle co 30 sekund (obiekt jest serializowany w głównym wątku, a plik zapisuje osobny).

**Konfiguracja.** Każdy klucz z `config.yml` jest czytany z wartością domyślną w kodzie, więc nowy klucz działa też na starym pliku konfiguracji.

**Encje w świecie są tymczasowe.** Napisy waypointów, śmietniki, model rekina i pokazy to encje `Display` z oznaczeniem w danych encji. Nie zapisują się w świecie, a po awarii plugin sprząta zabłąkane przy starcie. Napisy waypointów widzi tylko ich właściciel, każdy gracz ma własne, więc odległość na napisie jest zawsze jego.

**Uprawnienia.** Komendy administratora są za `kasyno.admin`, a więzienie i rekiny mają osobne wyjątki (`kasyno.prison.bypass`, `kasyno.shark.bypass`). Każdą funkcję można wyłączyć w `config.yml`, dane zostają.

## Jak dodać coś nowego

* nowa gra solo: klasa okna w `games/instant` albo `games/held`, wpis w `GameRegistry` i ikona z tłem w paczce zasobów (`tools/pack`). Test w `GuiFlowTest` zagra ją i sprawdzi rozliczenie
* nowa funkcja w świecie: najpierw sama logika z testami, potem cienka część do świata, na końcu komenda i sekcja w `config.yml`. Wzorem jest pakiet `shark`
* nowa komenda: krótka w `cmd/Commands`, podkomenda `/kasyno ...` w `RootCommand`

## SportPlugin

Ta sama zasada. `pl.sport.core` to sama fizyka (opór powietrza, odbicia, wyliczanie rzutów), a `volley.VolleyGame` i `basket.BasketGame` to silniki zasad bez Bukkita. `match.*`, `arena.*` i `listener` łączą je z serwerem. Mecz biegnie na własnym zegarze co 50 ms, a testy rozgrywają całe mecze z botami.

## Bot Discord

Każda komenda ma swój plik w `src/commands`, wspólne reguły (ekonomia, katalog, gry) są w `src/lib`, dane trzyma SQLite, a wersja przeglądarkowa jest w `src/web`. `npm test` wywołuje tysiące komend i sprawdza rozliczenia co do monety (`test/smoke.js`). Zwroty gier sprawdza `test/rtp.js`, a stronę `test/web.js`.

## Czego jeszcze nie sprawdziłem

* zachowania przy dużej liczbie graczy naraz
* na żywo napadu na bank w świecie, więzienia i waypointów z nagrodami (mają tylko testy na atrapie serwera)
* okna mają własny wygląd tylko u graczy z wgraną paczką zasobów, bez niej wyglądają zwyczajnie, ale działają
