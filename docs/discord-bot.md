# 🎰 Bot ekonomiczny na Discorda

> Opis bota Discord z tego repozytorium. Ogólny opis projektu: [README](../README.md).

Bot z wirtualną walutą (💰): **ponad 20 gier hazardowych** (w tym Chicken Road, Plinko, Limbo i Wieża), darmowe i płatne sposoby zarabiania (biznesy, farma), **113 przedmiotów** w sklepie, ochrona w grach, poziomy, ranking, loteria i napady z ekipą.
Wszystko na komendach slash (`/`) i z prefiksem `!`, z przyciskami i animacjami. Dane trzymane lokalnie w SQLite (plik `data/economy.db`).

## ⛏️ Wersja na Minecrafta

W folderze [`minecraft-plugin/`](../minecraft-plugin/README.md) jest plugin **Paper 1.21.11** z tą samą ekonomią i grami w GUI: gry podzielone na sale (automaty, karty, ryzyko), **stoły na żywo** (crash, ruletka, koło i wyścigi: jedna runda dla wielu graczy naraz), własne tła okien i ikony z paczki zasobów oraz NPC-wieśniacy, którzy otwierają menu (`/kasyno npc spawn <typ>`). Gotowy plik jest w zakładce **Releases** repozytorium (albo zbuduj go: `mvn package` w folderze `minecraft-plugin`).

## ⚡ Szybki start

### 1. Utwórz bota na Discordzie

1. Wejdź na <https://discord.com/developers/applications> → **New Application** → nadaj nazwę.
2. Zakładka **Bot** → **Reset Token** → skopiuj token (to Twoje hasło do bota, **nikomu go nie pokazuj**).
   Na tej samej stronie włącz **MESSAGE CONTENT INTENT** (sekcja *Privileged Gateway Intents*) – bez tego nie zadziałają komendy z prefiksem `!`.
   Jeśli zapomnisz, bot i tak wystartuje, ale tylko z komendami `/` (i napisze w konsoli, co włączyć).
3. Zakładka **General Information** → skopiuj **Application ID**.
4. Zaproś bota na serwer, podmieniając `TWOJE_APPLICATION_ID` w linku:

   ```
   https://discord.com/oauth2/authorize?client_id=TWOJE_APPLICATION_ID&scope=bot%20applications.commands&permissions=84992
   ```

5. (Zalecane) W Discordzie włącz *Ustawienia → Zaawansowane → Tryb dewelopera*, kliknij prawym przyciskiem swój serwer → **Kopiuj ID serwera**.
   Dzięki temu komendy pojawią się od razu, a nie po godzinie.

### 2. Uruchom

Potrzebujesz [Node.js](https://nodejs.org) w wersji **22.13 lub nowszej** (LTS).

**Windows:** kliknij dwukrotnie `start.bat` – zainstaluje paczki, zapyta o dane (kreator), zarejestruje komendy i włączy bota.

**Ręcznie** (każdy system):

```bash
npm install
npm run setup     # kreator: zapisze plik .env (token, ID aplikacji, ID serwera)
npm run deploy    # rejestruje komendy slash
npm start         # uruchamia bota
```

**Po każdej aktualizacji bota:** zatrzymaj go, uruchom `npm run deploy` (nowe komendy i opcje trzeba zarejestrować w Discordzie), a potem `npm start`.
Sam restart bez `deploy` wystarczy tylko wtedy, gdy zmieniły się liczby (ceny, mnożniki) albo logika komend, bez nowych komend i opcji.

> Bot działa tylko wtedy, gdy działa ten program. Żeby był online 24/7, uruchom go na VPS-ie, Raspberry Pi albo w hostingu dla botów.

## 🎮 Komendy (55)

| Kategoria | Komendy |
|---|---|
| 💳 **Konto** | `/saldo` `/profil` `/top` `/wplac` `/wyplac` `/przelew` `/ekwipunek` `/bank` |
| 🎁 **Darmowe** | `/daily` (seria dni + odsetki z banku) `/weekly` `/godzinna` (premia co godzinę) |
| 🆓 **Darmowe zarobki** | `/zebraj` `/las` 🍄 `/smietnik` 🗑️ `/uliczny` 🎸 `/trivia` `/matma` |
| 💼 **Praca i łowy** | `/praca` (7 zawodów odblokowywanych poziomem) `/ryby` `/kopalnia` `/polowanie` (narzędzia ze sklepu, łup sprzedajesz `/sprzedaj`) |
| 🏢 **Płatne zarobki** | `/biznes` (dochód pasywny, który się kumuluje) `/farma` (sadzisz, po czasie zbierasz więcej, niż włożyłeś) |
| 🦹 **Ryzyko** | `/przestepstwo` `/okradnij` `/napad` (ekipa do 10 osób) |
| 🎰 **Hazard** | `/moneta` `/sloty` `/kosci` `/ruletka` `/kolo` `/zdrapka` `/wyscigi` `/knp` |
| 🃏 **Hazard z przyciskami** | `/blackjack` `/miny` `/crash` `/hilo` `/kurczak` 🐔 `/wieza` 🗼 |
| 🎯 **Hazard na mnożniki** | `/plinko` `/limbo` `/szansa` `/rewolwer` (każdy może dać x100 albo więcej) |
| ⚔️ **Z graczami** | `/pojedynek` (PvP 50/50) `/jackpot` (wiele osób, jedna wygrywa) `/loteria` |
| 🛒 **Sklep** | `/sklep` `/kup` `/sprzedaj` `/uzyj` `/mnozniki` |
| 🎁 **Skrzynie** | `/skrzynie lista` `/skrzynie otworz` (też `/uzyj`) |
| ❓ **Inne** | `/pomoc` `/admin` (tylko właściciel) |

Stawki możesz podawać na wiele sposobów: `500`, `2k`, `1.5m`, `50%`, `half`, `all`.

## ⌨️ Komendy z prefiksem `!`

Każdą komendę możesz wpisać też zwykłą wiadomością: `!saldo`, `!moneta 100 orzel`, `!ruletka 500 czerwone`, `!miny 1k 5`, `!kurczak 500 trudny`, `!limbo 1k 10`, `!biznes kup kiosk`, `!farma zasadz marchew 2`.
Przyciski, animacje i wszystkie gry działają tak samo jak pod `/`.

* Argumenty podajesz po spacji. Kolejność bywa dowolna (`!moneta orzel 100` też zadziała), a wybory można skracać (`!moneta 100 o`, `!kosci 100 poniżej`, polskie znaki są opcjonalne).
* Skróty: `!bj` (blackjack), `!bal` (saldo), `!cf` (moneta), `!rr` (rewolwer), `!chicken` (kurczak), `!zbierz` (`/biznes zbierz`), `!zbiory` (`/farma zbierz`), `!sadz` (`/farma zasadz`), `!ulepsz` (`/bank ulepsz`), `!otworz` (`/skrzynie otworz`) i angielskie nazwy (`!slots`, `!roulette`, `!work`, `!hourly`...).
* Samo `!praca`, `!bank`, `!loteria`, `!skrzynie`, `!biznes` i `!farma` uruchamiają domyślną podkomendę. Przy błędnym użyciu bot podpowie, jak wpisać komendę.
* Wymaga włączenia **MESSAGE CONTENT INTENT** w Developer Portal (patrz *Szybki start*) i uprawnienia *Read Message History* dla bota (jest w linku z zaproszeniem).
* Własny prefiks: `PREFIX=?` w `.env`. Wyłączenie: `ENABLE_PREFIX=false`.
* Wiadomości prywatne (ephemeral) nie istnieją w wiadomościach tekstowych, więc komunikaty o błędach są pod `!` widoczne dla wszystkich na kanale.

## 🎲 Nowe gry na duże mnożniki

| Gra | Jak działa | Maksimum |
|---|---|---|
| 🐔 `/kurczak` (Chicken Road) | Przeprowadzasz kurczaka przez kolejne pasy. Każdy krok podnosi mnożnik i możesz wypłacić w dowolnej chwili. Poziomy: łatwy, średni, trudny, hardcore | do x1000 (hardcore x735, trudny x538) |
| 🗼 `/wieza` | Na każdym piętrze wybierasz pole bez pułapki (2-4 pola). Poziomy: łatwy, średni, trudny, ekstremalny | do x1000 (14 pięter) |
| 🎯 `/plinko` | Kulka spada przez 12 rzędów kołków do koszyka z mnożnikiem. Ryzyko: niskie, średnie, wysokie, szalone | x1000 (szalone) |
| 🚀 `/limbo` | Sam wybierasz cel (x1.01-x1000). Jeśli wynik rundy go dobije, wygrywasz cel × stawka | x1000 |
| 🎲 `/szansa` | Sam wybierasz szansę na wygraną (0,5-95%), a wypłata rośnie, gdy szansa maleje. Można stawiać na wynik poniżej albo powyżej progu | x192 |
| 🔫 `/rewolwer` | Rosyjska ruletka: 1-5 naboi w 6 komorach, im więcej, tym większa wypłata za przeżycie | x5.76 |
| 🎰 `/jackpot` | Wszyscy dorzucają tę samą stawkę (lobby 45 s), jedna osoba bierze całą pulę po 5% prowizji | pula wszystkich |
| 🎰 `/sloty`, 🎡 `/kolo`, 🎟️ `/zdrapka` | Dodane rzadkie wygrane: losowy jackpot x200, segment x200 na kole, wygrana x500 na zdrapce | x200 / x200 / x500 |

### Dlaczego `/crash` często pada wcześnie?

Crash nie jest „zepsuty”, tak wygląda rozkład każdej uczciwej gry tego typu. Szansa, że runda dojdzie do mnożnika x wynosi `97% / x`, więc:

| Mnożnik | Szansa dojścia |
|---|---|
| x1.5 | 64.7% |
| x2 | 48.5% |
| x5 | 19.4% |
| x10 | 9.7% |
| x100 | 0.97% |

Czyli **mniej więcej połowa rund kończy się poniżej x2**, a pasma po 5-6 szybkich crashów z rzędu zdarzają się regularnie. Nie da się tego zmienić bez zmiany zwrotu z gry (wtedy kasyno przestałoby być „na plusie”). Zmieniono więc to, co się dało: przewaga kasyna spadła z 4% do 3% (zwrot 97%), sufit wzrósł do **x10 000**, a ekran pokazuje **historię ostatnich rund** i **szanse dojścia** do x2, x10 i x100. Chcesz grać na duże mnożniki? `/limbo`, `/plinko` i `/kurczak` pozwalają wybrać ryzyko samemu.

## 🧾 Ochrona w grach

Polisy i tarcze kupujesz w `/sklep kategoria` → *Ochrona w grach*, a uzbrajasz je komendą `/uzyj`. Zużywają się dopiero wtedy, gdy faktycznie zadziałają, a to, co masz uzbrojone, widać w `/mnozniki` i na ekranie gry.

| Rodzaj | Co robi | Poziomy (cena → maksymalna chroniona stawka) |
|---|---|---|
| 🧾 **Polisa** | Gdy **całkowicie przegrasz** rundę (w dowolnej grze), cała stawka wraca | mała 2 000 → 2 000 • średnia 25 000 → 25 000 • duża 300 000 → 300 000 • platynowa 3 000 000 → 3 000 000 |
| 🛡️ **Tarcza** | Pierwszy **zły klik** w grze krokowej (mina, auto, pułapka, zła karta) się nie liczy, a ty grasz dalej | mała 10 000 → 5 000 • średnia 100 000 → 50 000 • duża 1 000 000 → 500 000 • tytanowa 10 000 000 → 5 000 000 |

Tarcza działa w: `/miny` (zablokowana mina zostaje wyłączona na planszy), `/kurczak` (samochód odbija się od kurczaka), `/wieza` (pułapka jest wyłączona, wybierasz inne pole), `/hilo` (zły typ jest anulowany) i `/blackjack` (dobranie, które by spaliło, wraca do talii).
Ochrona działa tylko przy stawce nieprzekraczającej jej limitu (większej nie zużywa), a gdy masz kilka, bot wybiera najtańszą wystarczającą. Limity są dobrane do cen: polisa chroni najwyżej tyle, ile kosztuje, tarcza połowę ceny, więc **nie da się na nich zarabiać na hazardzie**. `npm test` pilnuje tej zasady.

## 🆓 Darmowe i płatne zarobki

**Darmowe** (zero kosztów, tylko cooldowny; narzędzia ze sklepu zwiększają zyski):

| Komenda | Opis | Cooldown |
|---|---|---|
| `/godzinna` | Premia, która rośnie z poziomem (5% szans na skrzynkę) | 60 min |
| `/las` 🍄 | Grzyby i jagody, koszyk i wielki koszyk zwiększają szansę na rzadkie (trufle, złote grzyby) | 2 min |
| `/smietnik` 🗑️ | Przeszukiwanie śmietników: drobiazgi i czasem skarb | 45 s |
| `/uliczny` 🎸 | Napiwki do kapelusza, gitara ×1.5, skrzypce ×2.2, fortepian ×4 | 60 s |

**Płatne** (inwestujesz, potem pieniądze same się zbierają, także gdy bota nie ma online):

| Biznes | Cena | Dochód na godzinę | Zwrot po |
|---|---|---|---|
| 📰 Kiosk z gazetami | 20 000 | 400 | 50 h |
| 🍕 Pizzeria | 150 000 | 3 000 | 50 h |
| 🏪 Sklep osiedlowy | 600 000 | 12 500 | 48 h |
| 🚿 Myjnia samochodowa | 2 500 000 | 55 000 | 45 h |
| 🏨 Hotel | 12 000 000 | 280 000 | 43 h |
| 💻 Firma IT | 60 000 000 | 1 500 000 | 40 h |
| 🎰 Własne kasyno | 300 000 000 | 8 000 000 | 38 h |
| 🚀 Kosmiczna korporacja | 2 000 000 000 | 60 000 000 | 33 h |

Każdy biznes ulepszysz do poziomu 5 (`/biznes kup`, koszt = cena × poziom × 1,5, dochód rośnie liniowo z poziomem). Zarobki trafiają do magazynu na **8 godzin** (po tym czasie przestają się kumulować), odbierasz je `/biznes zbierz`. **Menedżer** (+20% dochodu) i **Księgowy** (magazyn na 16 h) ze `/sklep` to stałe ulepszenia.

**Farma** (`/farma`): 4 pola, 7 upraw. Plon jest wart 1,6-1,9× więcej niż nasiona, ale rośnie od 5 minut (marchew) do 24 godzin (złoty żeń-szeń). `/farma zasadz` bez numeru pola zapełnia wszystkie wolne pola (tyle, na ile starczy pieniędzy), a **Podlewaczka** skraca wzrost o 25%.

## 🛒 Sklep i mnożniki

`/sklep` pokazuje przegląd w 10 kategoriach (`/sklep kategoria` daje opisy jednej z nich). `/kup` i `/uzyj` podpowiadają przedmioty, gdy zaczniesz pisać (nazwa albo id). To, co masz aktywne, zobaczysz w `/mnozniki`.

| Kategoria | Co znajdziesz |
|---|---|
| 🎣 Wędki i kilofy | 4 wędki i 4 kilofy, aż do legendarnej wędki i kilofa kosmicznego |
| 🔫 Broń, koszyki, instrumenty | strzelby i snajperka (polowanie), koszyki (las), gitara, skrzypce, fortepian (uliczny) |
| 🛡️ Ochrona i sprzęt | kłódka, kominiarka, alarm, łom, laptop, garnitur, samochód, pas narzędziowy, talizman |
| 🐾 Zwierzaki | pies (obrona przed złodziejami), kot, papuga, sowa, jednorożec, smok (bonusy do zarobków, XP, szczęścia) |
| 📈 Karty VIP i wiedza | karty od brązu do diamentu (+10% … +200% zarobków), dyplom i doktorat (XP) |
| 🏦 Finanse i biznes | konto oszczędnościowe i premium (odsetki do 6%), menedżer, księgowy, podlewaczka |
| ⚡ Eliksiry czasowe | zarobki, XP, łup, szczęście, cooldowny (zwykłe i mega) |
| 🧾 Ochrona w grach | polisy i tarcze (patrz wyżej) |
| 🎁 Skrzynie | 6 rodzajów |
| 💎 Luksus i prestiż | od okularów po własną planetę (50 mld), kolekcja widoczna w `/profil` |

**Stałe mnożniki** sumują się: zarobki to `(1 + najlepsza karta VIP + zwierzaki) × najlepszy eliksir`. Przykład: karta diamentowa (+200%) i smok (+15%) dają ×3,15, a z mega eliksirem ×3 już ×9,45. Karty nie sumują się ze sobą (liczy się najlepsza). Bonusy do XP, szczęścia i obrony przed złodziejami sumują się tak samo.

**Eliksiry czasowe** (kupujesz, potem `/uzyj`; użycie drugiego tego samego przedłuża czas): działa zawsze najlepszy z aktywnych danego rodzaju (×3 nie mnoży się z ×2), a przyspieszacze cooldownów skracają czas aż do −75% (super przyspieszacz).

Mnożniki **nie** działają na hazard (żeby zwroty z gier zostały poniżej 100%), na `/okradnij` (zabrane pieniądze pochodzą od innego gracza) ani na `/daily` i `/weekly`. Koniczyna 🍀 ma sufit bonusu 300 monet na wygraną. Bez niego bogaty gracz mógłby za 1 500 monet zyskać setki tysięcy.

Ceny, bonusy i czasy zmienisz w [`src/lib/catalog.js`](../src/lib/catalog.js) (`BONUSES`, `EFFECTS`, `BOOSTS`, `PROTECTIONS`, `ITEMS`, biznesy i uprawy). Nowy przedmiot do sklepu dopisz też do `SHOP_CATEGORIES`.

## 🏦 Bank i jego limit

Bank jest bezpieczny przed `/okradnij`, ale ma limit. **Na start to 100 000**, a rośnie o 5 000 za każdy poziom i o ulepszenia, które kupujesz po kolei (`/bank ulepsz`, płacisz z portfela):

| # | Ulepszenie | Cena | Limit rośnie o | Limit po zakupie* |
|---|---|---|---|---|
| 1 | 🗄️ Sejf domowy | 50 000 | +150 000 | 250 000 |
| 2 | 🔐 Skarbiec bankowy | 250 000 | +400 000 | 650 000 |
| 3 | 🏦 Własny oddział banku | 1 000 000 | +1 500 000 | 2 150 000 |
| 4 | 🏰 Podziemny skarbiec | 5 000 000 | +6 000 000 | 8 150 000 |
| 5 | 🇨🇭 Szwajcarskie konto | 25 000 000 | +30 000 000 | 38 150 000 |
| 6 | 🌌 Kosmiczny skarbiec | 150 000 000 | +200 000 000 | 238 150 000 |
| 7 | ♾️ Bank Wszechświata | 1 000 000 000 | +1 500 000 000 | 1 738 150 000 |

\*na poziomie 1, bez małych sejfów.

`/bank info` pokazuje, z czego składa się Twój limit, a `/bank lista` wszystkie ulepszenia. Małe sejfy (+25 000 każdy) wypadają jeszcze ze skrzyń diamentowych i legendarnych.
Ceny i bonusy zmienisz w `BANK_UPGRADES` w [`src/lib/data.js`](../src/lib/data.js). Starsze bazy danych same dostają nową kolumnę przy pierwszym starcie.

## 🎁 Skrzynie i animacje

Sześć rodzajów skrzyń, a w każdej pieniądze, XP, przedmioty, a czasem… kolejna skrzynia:

| Skrzynia | Cena | Co może wypaść |
|---|---|---|
| 📦 Drewniana skrzynka | 1 000 | drobne nagrody, kłódki, kominiarki, koniczyny, szansa na skrzynię skarbów |
| 🎁 Skrzynia skarbów | 3 000 | do 60 000, szansa na skrzynię diamentową |
| 🧺 Skrzynia zbieracza | 5 000 | łupy z ryb, kopalni i polowania hurtem (po kilkadziesiąt sztuk, do diamentów, rubinów i meteorytów), szansa na skrzynię diamentową |
| 🎰 Skrzynia hazardzisty | 10 000 | gotówka, polisy (do platynowej), tarcze (do dużej), koniczyny i eliksir szczęścia, czyli wszystko do gry |
| 💠 Skrzynia diamentowa | 15 000 | do 220 000, sejfy, złote kilofy, szansa na skrzynię legendarną |
| 👑 Skrzynia legendarna | 100 000 | do 1 100 000, karabin, a nawet Złote trofeum 🏆 |

Każda nagroda ma rzadkość: ⚪ zwykła · 🟢 niezwykła · 🔵 rzadka · 🟣 epicka · 🟡 legendarna. Dokładne szanse zobaczysz w `/skrzynie lista`.
Skrzynie dostajesz też za serie `/daily` (7 i 30 dni, plus 30% szans co dzień na drewnianą), z `/weekly`, `/godzinna`, za każdy 5. poziom oraz z łupów.

Animacje, które robią robotę:

* **Skrzynie** – przewijany pasek w stylu CS:GO, który zwalnia, z „prawie legendarnym” dropem tuż obok wyniku. Wiele skrzyń naraz (do 10) kończy się zbiorczym podsumowaniem.
* **Sloty** – bębny zatrzymują się po kolei, a przy dwóch takich samych bot trzyma w napięciu.
* **Koło fortuny** – ten sam przewijany pasek co w skrzyniach.
* **Plinko, limbo, szansa, rewolwer** – kulka spadająca po kołkach, licznik wspinający się do wyniku, pasek z markerem, napięcie przed strzałem.
* **Jackpot** – losowanie zwycięzcy na oczach wszystkich uczestników.
* **Blackjack** – krupier odkrywa kartę i dobiera po jednej.
* **Moneta, kości, ruletka** – rzut z klatkami, wirująca kulka.
* **Napad** – wejście, odliczanie 3-2-1 i wybuch. **Pojedynek** – wymiana ciosów.
* **Ryby, kopalnia, polowanie, las, śmietnik** – krótka scenka przed wynikiem.

Wynik każdej gry jest zapisywany niezależnie od animacji. Jeśli Discord przytnie wiadomość w trakcie, nic nie przepada.

## 🧮 Uczciwość i ekonomia

Każda gra ma zwrot (RTP) poniżej 100%, więc w długim biegu kasyno „zarabia”, a pieniądze znikają z obiegu (podobnie jak w sklepie i przy grzywnach). W grach, w których sam wybierasz ryzyko (limbo, szansa, crash, plinko, kurczak, wieża), zwrot jest ten sam dla każdego wyboru, zmienia się tylko rozrzut wyników:

| Gra | Zwrot | | Gra | Zwrot |
|---|---|---|---|---|
| Moneta | 97.5% | | Kości | 95–96% |
| Ruletka | 97.3% | | Koło fortuny | 96% |
| Miny | do 97% | | Sloty | 94.9% (z jackpotem) |
| Crash | 97% | | Zdrapka | 94.5% |
| Hi-Lo | ~94–95% | | Wyścigi | 94–95% |
| KNP | 96.7% | | Blackjack | 99.4% przy strategii podstawowej |
| Plinko | 94.7–96.4% | | Limbo | 96% |
| Szansa | 96% | | Rewolwer | 96% (95.8% przy 1 naboju) |
| Kurczak | 96% | | Wieża | 96% |
| Jackpot | 95% (prowizja 5%) | | Skrzynie | 89–97% (przedmioty po cenie skupu) |

Sprawdzisz to sam: `npm run rtp` (albo `node --disable-warning=ExperimentalWarning test/rtp.js`). Skrypt kończy się błędem, gdy któraś z gier (poza blackjackiem i skrzyniami, które liczą się osobno) ma zwrot 100% lub więcej.
Stawka jest zdejmowana z portfela na początku gry, a jeśli Discord się wysypie w trakcie, wraca do gracza. Nie da się grać dwiema grami naraz.

## 🛠️ Dostosowanie

* **Przedmioty, efekty, eliksiry, ochrony, biznesy, uprawy, kategorie sklepu** – wszystko w [`src/lib/catalog.js`](../src/lib/catalog.js).
* **Zawody, pytania do trivii, scenki przestępstw, cooldowny, limit banku** – [`src/lib/data.js`](../src/lib/data.js) (tabela `COOLDOWNS`). `/daily` (20 h) i `/weekly` (7 dni) mają własne liczniki w swoich plikach.
* **Zawartość skrzyń** – tabele w [`src/lib/crates.js`](../src/lib/crates.js) (wagi każdej skrzyni mają sumować się do 100). `npm test` pilnuje, żeby zwrot został poniżej ceny.
* **Wypłaty gier** (tabele w `sloty.js`, `kolo.js`, `zdrapka.js`, `plinko.js`, `wyscigi.js`) – po zmianie odpal `npm run rtp`, żeby zobaczyć nowy zwrot.
* **Nowa komenda** – wrzuć plik do `src/commands/` z eksportem `{ data, execute }`, potem `npm run deploy`.
* **Loteria** – ustaw `LOTTERY_CHANNEL_ID` w `.env`, żeby bot ogłaszał zwycięzców na kanale (losowanie co 24 h).
* **/admin** – ustaw `OWNER_IDS` (Twoje ID użytkownika), żeby dodawać monety, przedmioty i zerować cooldowny.
* **Kopia zapasowa** – wystarczy skopiować folder `data/` (gdy bot jest wyłączony).

## 🧪 Testy

```bash
npm test          # pełny test dymny (ok. 15 s)
npm run rtp       # symulacja zwrotu każdej gry
```

Test dymny odpala każdą komendę na atrapie Discorda, rozgrywa tysiące partii (blackjack, miny, crash, hi-lo, pojedynki, napady...) i po każdej sprawdza, że pieniądze zgadzają się co do monety, a stawka wraca przy awarii.
Sprawdza też skrzynie: wagi, zwroty, rozkład rzadkości z setek tysięcy losowań, animację paska oraz nagrody za serie i awanse.

Osobny plik [`test/features.js`](../test/features.js) pokrywa nową mechanikę: spójność katalogu (każdy przedmiot ma opis, efekt i miejsce w sklepie), polisy i tarcze (w tym zasadę, że nie da się na nich zarabiać), Chicken Road i Wieżę (z wymuszonym losem, tarczą, limitem x1000 i timeoutem), Plinko, Limbo, Szansę, Rewolwer i Jackpot, biznesy i farmę z kontrolowanym zegarem, darmowe zarobki, efekty zwierzaków, kart i eliksirów oraz komendy z prefiksem `!`. Chcesz uruchomić tylko je?

```bash
ONLY_FEATURES=1 node --disable-warning=ExperimentalWarning test/smoke.js
```

## ❗ Bezpieczeństwo

* Plik `.env` zawiera token – nie wysyłaj go nikomu i nie wrzucaj do repozytorium (jest w `.gitignore`).
  Jeśli token wycieknie, zrób **Reset Token** w Developer Portal.
* To gra na wirtualnych monetach – bez prawdziwych pieniędzy.
