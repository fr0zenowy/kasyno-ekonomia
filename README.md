# 🎰 Kasyno, ekonomia i sport na serwer Minecraft

Zestaw trzech projektów, które razem tworzą ekonomię i rozrywkę na serwerze **Paper 1.21.11**: kasyno z 18 grami w GUI (w tym czterema stołami na żywo), prace i nagrody w prawdziwym świecie, napady i pojedynki z animacją w grze oraz siatkówka, koszykówka i ping-pong z fizyką piłki. Do tego bot Discord z tą samą ekonomią.

Wszystko jest napisane od zera (Java 21 i Node.js), ma **ponad 660 testów automatycznych w pluginach** (plus rozbudowane scenariusze bota) i opis konfiguracji po polsku. Poniżej nagrania z działającego serwera.

## Jak to wygląda na serwerze

Podglądy z nagrań z serwera (Minecraft 1.21.11, Paper). Każdą z tych funkcji da się włączyć, wyłączyć i ustawić w `config.yml`.

| | |
|---|---|
| ![Siatkówka](docs/media/siatkowka.gif)<br>**Siatkówka**: zagrywka, odbicia i atak z fizyką piłki, zasady i punktacja | ![Koszykówka](docs/media/koszykowka.gif)<br>**Koszykówka**: rzuty, faule, zabieranie piłki, wsady, zegar akcji |
| ![Ping-pong](docs/media/pingpong.gif)<br>**Ping-pong**: stół z siatką, serwis i wymiana | ![Pojedynek na arenie](docs/media/pojedynek.gif)<br>**Pojedynek na arenie**: odliczanie, walka i rozliczenie stawki w grze |
| ![Rekin](docs/media/rekin.gif)<br>**Rekin**: im dalej od brzegu, tym większe ryzyko; najpierw płetwa i czas na ucieczkę | ![Śmietniki](docs/media/smietniki.gif)<br>**Śmietniki w świecie**: modele z bloków, każdy z własną przerwą i łupem |
| ![Paczki](docs/media/paczki.gif)<br>**Kurier**: dostarczanie paczek po mieście z celami w świecie | |

Do tego okna kasyna z własnymi tłami i ikonami (moneta, sloty, ruletka, plinko, crash, kości, limbo, wieża i inne) oraz stoły na żywo (ruletka, crash, koło, wyścigi koni).

## Co jest w repozytorium

| Folder | Co to jest | Opis |
|---|---|---|
| [`minecraft-plugin/`](minecraft-plugin/README.md) | **Kasyno i Ekonomia**: plugin Paper (Java 21, ok. 23 tys. linii + 10 tys. testów) | gry, sklep, bank, biznesy, prace, napady, arena, więzienie, rekin, waypointy |
| [`sport-plugin/`](sport-plugin/README.md) | **SportPlugin**: siatkówka, koszykówka, ping-pong (Java 21, ok. 8 tys. linii + 5 tys. testów) | fizyka piłki, zasady, boiska budowane komendą |
| [`src/`](docs/discord-bot.md), [`test/`](test) | **Bot Discord** (Node.js, ok. 13 tys. linii razem z testami) | ta sama ekonomia na komendach `/` i `!`, wersja przeglądarkowa |
| [`docs/`](docs) | dokumentacja | [architektura](docs/ARCHITEKTURA.md), [opis bota](docs/discord-bot.md), nagrania |

## Najważniejsze funkcje

**Ekonomia i kasyno** (plugin i bot): portfel, bank z odsetkami, 113 przedmiotów w sklepie, poziomy, biznesy z dochodem pasywnym, farma, skrzynie z animacją jak w CS:GO, loteria. W pluginie 14 gier solo (moneta, sloty, zdrapka, kości, blackjack, hi-lo, miny, Chicken Road, wieża, plinko, limbo, rewolwer, szansa, kamień-papier-nożyce) i cztery **stoły na żywo**, przy których jedna runda obsługuje wielu graczy naraz (crash, ruletka, koło, wyścigi). Do tego jackpot, napad, pojedynki i loteria dla wielu graczy.

**Rozgrywka w prawdziwym świecie** (plugin):

* **Napad na bank**: ekipa wchodzi do zbudowanego banku w stylu klasycystycznym w skórkach prawdziwych graczy, scenariusz losują etapy, złapani idą do więzienia.
* **Arena**: pojedynki 50/50 i jackpot rozgrywane jako pokaz z figurami graczy, odliczaniem i fajerwerkami zwycięzcy.
* **Prace w terenie**: NPC zlecają zadania (zbieranie grzybów, kopalnia, ryby, sprzątanie, paczki), a cele świecą w świecie.
* **Śmietniki**: modele z bloków, z których się zbiera łup, każdy ma własną przerwę.
* **Waypointy**: napis z nazwą i odległością o stałym rozmiarze, widoczny przez ściany. Prowadzenie do wybranego miejsca, nagrody za odkrycie, własny dom i znacznik miejsca śmierci.
* **Więzienie**: nieudane przestępstwo lub napad kończy się celą (losową z trzech), z której wychodzi się, zaliczając minigierki (podkop, refleks, sekwencja, egzamin).
* **Rekin**: szansa ataku rośnie z odległością od lądu; płetwa krąży wokół pływaka, potem animowany rzut, połowa gotówki z portfela przepada.

**Sport** (osobny plugin): siatkówka (zagrywka, bagger, wystawienie, atak, blok), koszykówka (rzuty, faule, rzuty osobiste, wsad, zabieranie piłki), ping-pong. Piłki lecą według prawdziwej fizyki (opór powietrza, odbicia), a gracze sterują myszą.

## Dla administratora: jak to jest zrobione

Krótko, bo szczegóły są w [dokumencie o architekturze](docs/ARCHITEKTURA.md):

* **Logika oddzielona od Bukkita.** Zasady gier, fizyka, scenariusze (napad, atak rekina, wyrok więzienia) to czysty Java bez serwera, z wymiennymi „backendami” (stawianie encji, wykrywanie wody, teren). Dzięki temu da się je testować bez uruchamiania Minecrafta.
* **Testy na atrapie serwera.** Plugin Kasyno: **405 testów**, SportPlugin: **ok. 260**, bot: scenariusze z kilkoma tysiącami wywołań komend. Pieniądze w testach muszą się zgadzać co do monety, a każda gra ma sprawdzony zwrot poniżej 100%.
* **Bezpieczeństwo ekonomii.** Stawka jest pobierana i rozliczana zanim ruszy animacja. Po awarii serwera stawki z przerwanych gier i stołów wracają do graczy. Kliknięcia w GUI są zawsze anulowane, a ich tempo ograniczone.
* **Konfiguracja bez bólu.** Wszystko w `config.yml` z komentarzami, stare pliki konfiguracji i zapisy graczy wczytują się po aktualizacji (nowe pola mają wartości domyślne). Uprawnienia: `kasyno.use`, `kasyno.admin` oraz osobne wyjątki dla więzienia i rekinów.
* **Czystość.** Brak sekretów w repozytorium (token bota tylko w lokalnym `.env`), dane graczy poza gitem, każdy plugin buduje się jedną komendą (`mvn package`).
* **Własna paczka zasobów** generowana skryptami Node.js: tła okien, ikony, karty 3D, piłki.

## Szybki start

**Plugin Minecraft** (Paper 1.21.11, Java 21): pobierz plik `.jar` z zakładki **Releases** i wgraj do `plugins/`, albo zbuduj sam:

```bash
cd minecraft-plugin
mvn package        # testy + target/KasynoEkonomia-1.0.0.jar
```

Szczegóły instalacji, komend i konfiguracji: [`minecraft-plugin/README.md`](minecraft-plugin/README.md) oraz [`sport-plugin/README.md`](sport-plugin/README.md) (uwaga: SportPlugin zastępuje starszy `SiatkowkaPlugin`, więc nie wgrywaj ich razem).

**Bot Discord** (Node.js 22.13 lub nowszy):

```bash
npm install
npm run setup      # kreator zapisze lokalny plik .env (token nie trafia do repozytorium)
npm run deploy     # rejestruje komendy slash
npm start
```

Pełny opis: [`docs/discord-bot.md`](docs/discord-bot.md).

## Uczciwie o stanie projektu

* Nagrania powyżej pochodzą z **działającego serwera Paper 1.21.11**: widać na nich sport, arenę, śmietniki, kuriera, rekina i okna gier.
* Część funkcji (m.in. napad na bank w świecie, więzienie, waypointy z nagrodami) ma na razie **tylko testy automatyczne** na atrapie serwera (MockBukkit) i nie była jeszcze oglądana na żywo.
* Projekt nie był jeszcze sprawdzany przy dużym obciążeniu (dziesiątki graczy naraz).

## Jak powstał

Projekt rozwijam iteracyjnie z asystentem AI (Claude Code): zlecam funkcje, oglądam je na prawdziwym serwerze, a poprawki wynikają z tego, co tam zobaczę. Kod i testy powstają w tej współpracy, a decyzje o tym, co ma działać i jak ma wyglądać, są moje.

Autor: **fr0zenowy**
