// Katalog przedmiotów i ich efektów. Dopisz tu nowy przedmiot, a potem dodaj go do SHOP_CATEGORIES.
// Liczby (ceny, bonusy, czasy) w jednym miejscu: opisy w sklepie same się z nich liczą.

const SEC = 1000;
const MIN = 60 * SEC;
const HOUR = 60 * MIN;
const SEJF_BONUS = 25_000; // mały sejf (wypada tylko ze skrzyń)

const pct = (x) => Math.round(x * 100);
const spaced = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');

// --- mnożniki i bonusy ---
const BONUSES = {
  // Dodatek do zarobków z karty VIP. Liczy się najlepsza karta, jaką gracz ma.
  vip: { vip_brazowy: 0.1, vip_srebrny: 0.25, vip_zloty: 0.5, vip_platynowa: 1, vip_diamentowa: 2 },
  breakChance: 0.05, // szansa na zepsucie narzędzia przy użyciu
  breakChanceBelt: 0.01, // ...z pasem narzędziowym
  // Dzienne odsetki z banku w /daily; działa najlepsze konto, jakie gracz ma.
  interest: {
    base: { rate: 0.02, cap: 25_000 },
    konto_oszczednosciowe: { rate: 0.04, cap: 50_000 },
    konto_premium: { rate: 0.06, cap: 150_000 },
  },
  cloverPct: 0.2, // koniczyna: dodatek do zysku z wygranej
  cloverMax: 300, // ...ale nie więcej niż tyle na wygraną (inaczej bonus rósłby w nieskończoność razem ze stawką)
};

// Karty VIP od najlepszej do najgorszej – działa najlepsza, jaką gracz ma.
const VIP_CARDS = ['vip_diamentowa', 'vip_platynowa', 'vip_zloty', 'vip_srebrny', 'vip_brazowy'];

// Stałe efekty przedmiotów. Efekty tego samego rodzaju sumują się.
//   praca: dodatek do wypłaty z /praca      earn: dodatek do zarobków (praca, żebranie, quizy, przestępstwa)
//   xp: dodatek do XP                       luck: dodatek do szansy na rzadkie łupy
//   robDefense: o tyle spada szansa złodzieja na okradzenie właściciela
//   bizIncome: dodatek do dochodu biznesów  bizStorage: dodatkowe godziny magazynu biznesów
//   farmSpeed: o tyle krócej rosną uprawy
const EFFECTS = {
  laptop: { praca: 0.25 },
  garnitur: { praca: 0.15 },
  samochod: { praca: 0.25 },
  dyplom: { xp: 0.25 },
  doktorat: { xp: 0.5 },
  sowa: { xp: 0.1 },
  talizman: { luck: 0.25 },
  kot: { luck: 0.1 },
  jednorozec: { luck: 0.3, xp: 0.1 },
  papuga: { earn: 0.05 },
  smok: { earn: 0.15, xp: 0.15, luck: 0.1 },
  pies: { robDefense: 0.15 },
  menedzer: { bizIncome: 0.2 },
  ksiegowy: { bizStorage: 8 },
  podlewaczka: { farmSpeed: 0.25 },
};

// Czasowe mnożniki aktywowane przez /uzyj. Ponowne użycie przedłuża czas.
// effect: earn | xp | loot | luck (mnożnik, działa najlepszy) albo cooldown (mnożnik czasu, działa najkrótszy)
const BOOSTS = {
  eliksir_zarobkow: { key: 'zarobki', effect: 'earn', value: 2, duration: 30 * MIN, label: '×2 zarobki' },
  mega_eliksir: { key: 'zarobki_mega', effect: 'earn', value: 3, duration: 15 * MIN, label: '×3 zarobki' },
  podrecznik: { key: 'xp', effect: 'xp', value: 2, duration: 30 * MIN, label: '×2 XP' },
  mega_podrecznik: { key: 'xp_mega', effect: 'xp', value: 3, duration: 15 * MIN, label: '×3 XP' },
  magnes_lupu: { key: 'lup', effect: 'loot', value: 2, duration: 30 * MIN, label: '×2 łup' },
  mega_magnes: { key: 'lup_mega', effect: 'loot', value: 3, duration: 15 * MIN, label: '×3 łup' },
  eliksir_szczescia: { key: 'szczescie', effect: 'luck', value: 2, duration: 30 * MIN, label: '×2 szansa na rzadkie łupy' },
  zegarek: { key: 'zegarek', effect: 'cooldown', value: 0.5, duration: 15 * MIN, label: 'cooldowny −50%' },
  super_zegarek: { key: 'zegarek_super', effect: 'cooldown', value: 0.25, duration: 10 * MIN, label: 'cooldowny −75%' },
};

// Ochrona w grach (/uzyj ją uzbraja, zużywa się dopiero, gdy zadziała).
//   polisa: gdy całkowicie przegrasz rundę, stawka wraca (do `cap`)
//   tarcza: pierwszy błąd w grze krokowej (mina, auto, zła karta...) po prostu się nie liczy (stawki do `cap`)
// Sufity są dobrane do cen: polisa ma cap = cena, tarcza cap = połowa ceny, więc nie da się na nich zarabiać na hazardzie.
const PROTECTIONS = {
  polisa_mala: { kind: 'polisa', cap: 2_000 },
  polisa_srednia: { kind: 'polisa', cap: 25_000 },
  polisa_duza: { kind: 'polisa', cap: 300_000 },
  polisa_platynowa: { kind: 'polisa', cap: 3_000_000 },
  tarcza_mala: { kind: 'tarcza', cap: 5_000 },
  tarcza_srednia: { kind: 'tarcza', cap: 50_000 },
  tarcza_duza: { kind: 'tarcza', cap: 500_000 },
  tarcza_tytanowa: { kind: 'tarcza', cap: 5_000_000 },
};

// --- biznesy (/biznes): dochód pasywny ---
// Dochód to hourly × poziom, magazyn mieści BUSINESS_STORAGE_HOURS godzin. Ulepszenie z poziomu L na L+1 kosztuje price × L × 1.5.
const BUSINESSES = [
  { id: 'kiosk', name: 'Kiosk z gazetami', emoji: '📰', price: 20_000, hourly: 400 },
  { id: 'pizzeria', name: 'Pizzeria', emoji: '🍕', price: 150_000, hourly: 3_000 },
  { id: 'sklep', name: 'Sklep osiedlowy', emoji: '🏪', price: 600_000, hourly: 12_500 },
  { id: 'myjnia', name: 'Myjnia samochodowa', emoji: '🚿', price: 2_500_000, hourly: 55_000 },
  { id: 'hotel', name: 'Hotel', emoji: '🏨', price: 12_000_000, hourly: 280_000 },
  { id: 'it', name: 'Firma IT', emoji: '💻', price: 60_000_000, hourly: 1_500_000 },
  { id: 'kasyno', name: 'Własne kasyno', emoji: '🎰', price: 300_000_000, hourly: 8_000_000 },
  { id: 'korporacja', name: 'Kosmiczna korporacja', emoji: '🚀', price: 2_000_000_000, hourly: 60_000_000 },
];
const BUSINESS_MAX_LEVEL = 5;
const BUSINESS_STORAGE_HOURS = 8;

// --- farma (/farma): uprawy rosną w czasie, plon jest wart więcej niż nasiona ---
const FARM_SLOTS = 4;
const FARM_CROPS = [
  { id: 'marchew', name: 'Marchew', emoji: '🥕', cost: 100, yield: 160, minutes: 5 },
  { id: 'ziemniak', name: 'Ziemniaki', emoji: '🥔', cost: 500, yield: 850, minutes: 20 },
  { id: 'kukurydza', name: 'Kukurydza', emoji: '🌽', cost: 2_000, yield: 3_600, minutes: 60 },
  { id: 'pomidor', name: 'Pomidory', emoji: '🍅', cost: 10_000, yield: 18_500, minutes: 180 },
  { id: 'dynia', name: 'Dynie', emoji: '🎃', cost: 50_000, yield: 96_000, minutes: 480 },
  { id: 'winogrona', name: 'Winnica', emoji: '🍇', cost: 250_000, yield: 480_000, minutes: 720 },
  { id: 'zen_szen', name: 'Złoty żeń-szeń', emoji: '🌿', cost: 2_000_000, yield: 3_800_000, minutes: 1440 },
];

// type: tool (narzędzie do zarabiania), passive (działa samo), boost / consumable / protection (/uzyj), crate,
// loot (do sprzedania), luxury (kolekcja, pokazuje się na profilu), trophy
// unique: wystarczy mieć jedną sztukę. requires: trzeba mieć wcześniej inny przedmiot.
const ITEMS = {
  // --- wędki i kilofy ---
  wedka: { name: 'Wędka', emoji: '🎣', price: 500, type: 'tool', desc: 'Pozwala łowić ryby (`/ryby`).' },
  super_wedka: { name: 'Super wędka', emoji: '🎣', price: 5000, type: 'tool', desc: 'Lepsza wędka – dwa razy większa szansa na rzadkie ryby.' },
  wedka_mistrza: { name: 'Wędka mistrza', emoji: '🎣', price: 40_000, type: 'tool', desc: '3,5× większa szansa na rzadkie ryby.' },
  wedka_legendarna: { name: 'Wędka legendarna', emoji: '🎣', price: 400_000, type: 'tool', desc: '6× większa szansa na rzadkie ryby. Rekiny same się garną.' },
  kilof: { name: 'Kilof', emoji: '⛏️', price: 800, type: 'tool', desc: 'Pozwala kopać w kopalni (`/kopalnia`).' },
  zloty_kilof: { name: 'Złoty kilof', emoji: '⛏️', price: 8000, type: 'tool', desc: 'Dwa razy większa szansa na cenne rudy.' },
  diamentowy_kilof: { name: 'Diamentowy kilof', emoji: '⚒️', price: 60_000, type: 'tool', desc: '3,5× większa szansa na cenne rudy.' },
  kilof_kosmiczny: { name: 'Kilof kosmiczny', emoji: '🌠', price: 600_000, type: 'tool', desc: '6× większa szansa na cenne rudy i meteoryty.' },

  // --- broń, koszyki, instrumenty ---
  strzelba: { name: 'Strzelba', emoji: '🔫', price: 1500, type: 'tool', desc: 'Pozwala polować (`/polowanie`).' },
  karabin: { name: 'Karabin snajperski', emoji: '🎯', price: 9000, type: 'tool', desc: 'Rzadziej pudłujesz (12%) i częściej trafiasz grubą zwierzynę.' },
  luneta: { name: 'Karabin z lunetą', emoji: '🔭', price: 70_000, type: 'tool', desc: 'Pudłujesz tylko w 6% strzałów, 3,5× szansa na grubą zwierzynę.' },
  snajperka: { name: 'Snajperka elitarna', emoji: '💥', price: 700_000, type: 'tool', desc: 'Pudłujesz w 3% strzałów, 6× szansa na grubą zwierzynę.' },
  koszyk: { name: 'Koszyk grzybiarza', emoji: '🧺', price: 3000, type: 'tool', desc: '1,5× lepszy zbiór w lesie (`/las`).' },
  wielki_koszyk: { name: 'Wielki koszyk', emoji: '🧺', price: 30_000, type: 'tool', desc: '3× lepszy zbiór w lesie (`/las`).' },
  gitara: { name: 'Gitara', emoji: '🎸', price: 5000, type: 'tool', desc: 'Grajek uliczny (`/uliczny`) zarabia o 50% więcej.' },
  skrzypce: { name: 'Skrzypce', emoji: '🎻', price: 50_000, type: 'tool', desc: 'Grajek uliczny zarabia 2,2× więcej.' },
  fortepian: { name: 'Fortepian', emoji: '🎹', price: 500_000, type: 'tool', desc: 'Grajek uliczny zarabia 4× więcej. Ktoś musi go jeszcze dowieźć.' },

  // --- ochrona i sprzęt ---
  klodka: { name: 'Kłódka', emoji: '🔒', price: 700, type: 'passive', desc: 'Chroni portfel przed jednym okradzeniem, potem się zużywa.' },
  maska: { name: 'Kominiarka', emoji: '🎭', price: 600, type: 'passive', desc: '+20% szansy w `/przestepstwo` i `/okradnij`. Zużywa się przy użyciu.' },
  alarm: { name: 'Alarm', emoji: '🚨', price: 15_000, type: 'passive', unique: true, desc: 'Złodziej, który na Tobie wpadnie, płaci podwójną grzywnę.' },
  lom: { name: 'Łom', emoji: '🪓', price: 3000, type: 'consumable', desc: 'Przy `/okradnij` łamie kłódkę ofiary (jednorazowo) i skok trwa dalej.' },
  laptop: { name: 'Laptop', emoji: '💻', price: 3000, type: 'passive', unique: true, desc: `+${pct(EFFECTS.laptop.praca)}% zarobków z \`/praca\` na stałe.` },
  garnitur: { name: 'Garnitur', emoji: '🤵', price: 8000, type: 'passive', unique: true, desc: `+${pct(EFFECTS.garnitur.praca)}% zarobków z \`/praca\` (sumuje się z laptopem).` },
  samochod: { name: 'Samochód', emoji: '🚗', price: 60_000, type: 'passive', unique: true, desc: `+${pct(EFFECTS.samochod.praca)}% zarobków z \`/praca\` (dojeżdżasz szybciej).` },
  pas_narzedziowy: { name: 'Pas narzędziowy', emoji: '🧰', price: 30_000, type: 'passive', unique: true, desc: `Narzędzia psują się 5× rzadziej (${pct(BONUSES.breakChanceBelt)}% zamiast ${pct(BONUSES.breakChance)}% na użycie).` },
  talizman: { name: 'Talizman szczęścia', emoji: '🧿', price: 75_000, type: 'passive', unique: true, desc: `Na stałe +${pct(EFFECTS.talizman.luck)}% szans na rzadkie łupy.` },
  sejf: { name: 'Mały sejf', emoji: '💼', price: 12000, type: 'passive', desc: `+${spaced(SEJF_BONUS)} limitu banku (kumuluje się). Wypada tylko ze skrzyń; duże ulepszenia kupisz w \`/bank\`.` },

  // --- zwierzaki ---
  pies: { name: 'Pies obronny', emoji: '🐕', price: 25_000, type: 'passive', unique: true, desc: `Złodziejom trudniej Cię okraść (−${pct(EFFECTS.pies.robDefense)} pkt szansy).` },
  kot: { name: 'Kot', emoji: '🐈', price: 20_000, type: 'passive', unique: true, desc: `+${pct(EFFECTS.kot.luck)}% szans na rzadkie łupy. Przynosi myszy i szczęście.` },
  papuga: { name: 'Papuga', emoji: '🦜', price: 40_000, type: 'passive', unique: true, desc: `+${pct(EFFECTS.papuga.earn)}% zarobków. Powtarza „kasa, kasa”.` },
  sowa: { name: 'Sowa', emoji: '🦉', price: 30_000, type: 'passive', unique: true, desc: `+${pct(EFFECTS.sowa.xp)}% XP. Mądra bestia.` },
  jednorozec: { name: 'Jednorożec', emoji: '🦄', price: 1_000_000, type: 'passive', unique: true, desc: `+${pct(EFFECTS.jednorozec.luck)}% szans na rzadkie łupy i +${pct(EFFECTS.jednorozec.xp)}% XP.` },
  smok: { name: 'Smok', emoji: '🐉', price: 5_000_000, type: 'passive', unique: true, desc: `+${pct(EFFECTS.smok.earn)}% zarobków, +${pct(EFFECTS.smok.xp)}% XP i +${pct(EFFECTS.smok.luck)}% szans na rzadkie łupy.` },

  // --- karty VIP i wiedza ---
  vip_brazowy: { name: 'Karta VIP: brąz', emoji: '🥉', price: 100_000, type: 'passive', unique: true, desc: `Na stałe +${pct(BONUSES.vip.vip_brazowy)}% zarobków z pracy, żebrania, quizów i przestępstw.` },
  vip_srebrny: { name: 'Karta VIP: srebro', emoji: '🥈', price: 500_000, type: 'passive', unique: true, requires: 'vip_brazowy', desc: `Na stałe +${pct(BONUSES.vip.vip_srebrny)}% zarobków (zamiast brązu). Wymaga karty brązowej.` },
  vip_zloty: { name: 'Karta VIP: złoto', emoji: '🥇', price: 2_500_000, type: 'passive', unique: true, requires: 'vip_srebrny', desc: `Na stałe +${pct(BONUSES.vip.vip_zloty)}% zarobków (zamiast srebra). Wymaga karty srebrnej.` },
  vip_platynowa: { name: 'Karta VIP: platyna', emoji: '🏅', price: 25_000_000, type: 'passive', unique: true, requires: 'vip_zloty', desc: `Na stałe +${pct(BONUSES.vip.vip_platynowa)}% zarobków (podwajasz!). Wymaga karty złotej.` },
  vip_diamentowa: { name: 'Karta VIP: diament', emoji: '🔷', price: 250_000_000, type: 'passive', unique: true, requires: 'vip_platynowa', desc: `Na stałe +${pct(BONUSES.vip.vip_diamentowa)}% zarobków (×3 w sumie). Wymaga karty platynowej.` },
  dyplom: { name: 'Dyplom', emoji: '🎓', price: 50_000, type: 'passive', unique: true, desc: `Na stałe +${pct(EFFECTS.dyplom.xp)}% XP ze wszystkiego.` },
  doktorat: { name: 'Doktorat', emoji: '📜', price: 500_000, type: 'passive', unique: true, requires: 'dyplom', desc: `Na stałe dodatkowe +${pct(EFFECTS.doktorat.xp)}% XP. Wymaga dyplomu.` },

  // --- finanse i biznes ---
  konto_oszczednosciowe: { name: 'Konto oszczędnościowe', emoji: '🏧', price: 250_000, type: 'passive', unique: true, desc: `Odsetki w /daily ${pct(BONUSES.interest.konto_oszczednosciowe.rate)}% zamiast ${pct(BONUSES.interest.base.rate)}% (limit ${spaced(BONUSES.interest.konto_oszczednosciowe.cap)}).` },
  konto_premium: { name: 'Konto premium', emoji: '💳', price: 2_000_000, type: 'passive', unique: true, requires: 'konto_oszczednosciowe', desc: `Odsetki w /daily ${pct(BONUSES.interest.konto_premium.rate)}% (limit ${spaced(BONUSES.interest.konto_premium.cap)}). Wymaga konta oszczędnościowego.` },
  menedzer: { name: 'Menedżer', emoji: '🧑‍💼', price: 2_000_000, type: 'passive', unique: true, desc: `Twoje biznesy (\`/biznes\`) zarabiają o ${pct(EFFECTS.menedzer.bizIncome)}% więcej.` },
  ksiegowy: { name: 'Księgowy', emoji: '🧮', price: 500_000, type: 'passive', unique: true, desc: `Magazyn biznesów mieści o ${EFFECTS.ksiegowy.bizStorage} godzin więcej, więc rzadziej musisz zbierać.` },
  podlewaczka: { name: 'Podlewaczka', emoji: '🚿', price: 40_000, type: 'passive', unique: true, desc: `Uprawy na farmie (\`/farma\`) rosną o ${pct(EFFECTS.podlewaczka.farmSpeed)}% szybciej.` },

  // --- eliksiry czasowe ---
  eliksir_zarobkow: { name: 'Eliksir zarobków', emoji: '⚡', price: 25_000, type: 'boost', desc: `\`/uzyj\` – ×2 zarobki z pracy, żebrania, quizów i przestępstw na ${BOOSTS.eliksir_zarobkow.duration / MIN} min.` },
  mega_eliksir: { name: 'Mega eliksir zarobków', emoji: '🌩️', price: 120_000, type: 'boost', desc: `\`/uzyj\` – ×3 zarobki na ${BOOSTS.mega_eliksir.duration / MIN} min.` },
  podrecznik: { name: 'Podręcznik', emoji: '📚', price: 10_000, type: 'boost', desc: `\`/uzyj\` – ×2 XP na ${BOOSTS.podrecznik.duration / MIN} min.` },
  mega_podrecznik: { name: 'Mega podręcznik', emoji: '📖', price: 60_000, type: 'boost', desc: `\`/uzyj\` – ×3 XP na ${BOOSTS.mega_podrecznik.duration / MIN} min.` },
  magnes_lupu: { name: 'Magnes na łup', emoji: '🧲', price: 20_000, type: 'boost', desc: `\`/uzyj\` – ×2 łup z ryb, kopalni, polowania i lasu na ${BOOSTS.magnes_lupu.duration / MIN} min.` },
  mega_magnes: { name: 'Mega magnes', emoji: '🌀', price: 80_000, type: 'boost', desc: `\`/uzyj\` – ×3 łup na ${BOOSTS.mega_magnes.duration / MIN} min.` },
  eliksir_szczescia: { name: 'Eliksir szczęścia', emoji: '🍵', price: 20_000, type: 'boost', desc: `\`/uzyj\` – ×2 szansa na rzadkie łupy na ${BOOSTS.eliksir_szczescia.duration / MIN} min.` },
  zegarek: { name: 'Przyspieszacz', emoji: '⏩', price: 30_000, type: 'boost', desc: `\`/uzyj\` – cooldowny o połowę krótsze na ${BOOSTS.zegarek.duration / MIN} min.` },
  super_zegarek: { name: 'Super przyspieszacz', emoji: '⏭️', price: 150_000, type: 'boost', desc: `\`/uzyj\` – cooldowny o 75% krótsze na ${BOOSTS.super_zegarek.duration / MIN} min.` },
  koniczyna: { name: 'Czterolistna koniczyna', emoji: '🍀', price: 1500, type: 'consumable', desc: `\`/uzyj\` – 5 kolejnych wygranych w hazardzie: +${pct(BONUSES.cloverPct)}% zysku (maks. ${BONUSES.cloverMax} na wygraną).` },

  // --- ochrona w grach ---
  polisa_mala: { name: 'Polisa mała', emoji: '🧾', price: 2000, type: 'protection', desc: `\`/uzyj\` – gdy przegrasz rundę, stawka wraca (do ${spaced(PROTECTIONS.polisa_mala.cap)}).` },
  polisa_srednia: { name: 'Polisa średnia', emoji: '📃', price: 25_000, type: 'protection', desc: `\`/uzyj\` – gdy przegrasz rundę, stawka wraca (do ${spaced(PROTECTIONS.polisa_srednia.cap)}).` },
  polisa_duza: { name: 'Polisa duża', emoji: '📑', price: 300_000, type: 'protection', desc: `\`/uzyj\` – gdy przegrasz rundę, stawka wraca (do ${spaced(PROTECTIONS.polisa_duza.cap)}).` },
  polisa_platynowa: { name: 'Polisa platynowa', emoji: '📋', price: 3_000_000, type: 'protection', desc: `\`/uzyj\` – gdy przegrasz rundę, stawka wraca (do ${spaced(PROTECTIONS.polisa_platynowa.cap)}).` },
  tarcza_mala: { name: 'Tarcza błędu', emoji: '🛡️', price: 10_000, type: 'protection', desc: `\`/uzyj\` – pierwszy zły klik (mina, auto, zła karta) się nie liczy, grasz dalej (stawki do ${spaced(PROTECTIONS.tarcza_mala.cap)}).` },
  tarcza_srednia: { name: 'Tarcza wzmocniona', emoji: '🔰', price: 100_000, type: 'protection', desc: `\`/uzyj\` – pierwszy zły klik się nie liczy (stawki do ${spaced(PROTECTIONS.tarcza_srednia.cap)}).` },
  tarcza_duza: { name: 'Tarcza pancerna', emoji: '🪬', price: 1_000_000, type: 'protection', desc: `\`/uzyj\` – pierwszy zły klik się nie liczy (stawki do ${spaced(PROTECTIONS.tarcza_duza.cap)}).` },
  tarcza_tytanowa: { name: 'Tarcza tytanowa', emoji: '🔱', price: 10_000_000, type: 'protection', desc: `\`/uzyj\` – pierwszy zły klik się nie liczy (stawki do ${spaced(PROTECTIONS.tarcza_tytanowa.cap)}).` },

  // --- skrzynie ---
  skrzynka: { name: 'Drewniana skrzynka', emoji: '📦', price: 1000, type: 'crate', desc: 'Tania skrzynka: drobne nagrody i szansa na coś lepszego.' },
  skrzynia: { name: 'Skrzynia skarbów', emoji: '🎁', price: 3000, type: 'crate', desc: 'Pieniądze, ulepszenia i szansa na skrzynię diamentową.' },
  skrzynia_zbieracza: { name: 'Skrzynia zbieracza', emoji: '🗃️', price: 5000, type: 'crate', desc: 'Pełna rzadkich ryb, rud i zwierzyny, które od razu sprzedasz.' },
  skrzynia_hazardzisty: { name: 'Skrzynia hazardzisty', emoji: '🎲', price: 10_000, type: 'crate', desc: 'Polisy, tarcze, koniczyny i gotówka dla ludzi z nerwami.' },
  skrzynia_diamentowa: { name: 'Skrzynia diamentowa', emoji: '💠', price: 15_000, type: 'crate', desc: 'Duże wygrane, sejfy i złote kilofy. Może ukrywać legendarną skrzynię.' },
  skrzynia_legendarna: { name: 'Skrzynia legendarna', emoji: '👑', price: 100_000, type: 'crate', desc: 'Dla odważnych: do miliona monet, a nawet Złote trofeum.' },

  // --- luksus i prestiż (sama kolekcja: pokazuje się na /profil) ---
  luks_okulary: { name: 'Okulary przeciwsłoneczne', emoji: '😎', price: 10_000, type: 'luxury', unique: true, desc: 'Najtańszy sposób, żeby wyglądać na bogatego.' },
  luks_zegarek: { name: 'Złoty zegarek', emoji: '⌚', price: 25_000, type: 'luxury', unique: true, desc: 'Pokazuje tylko godzinę „czas na pieniądze”.' },
  luks_cylinder: { name: 'Cylinder', emoji: '🎩', price: 60_000, type: 'luxury', unique: true, desc: 'Dla dżentelmenów i magików.' },
  luks_pierscien: { name: 'Pierścień z brylantem', emoji: '💍', price: 150_000, type: 'luxury', unique: true, desc: 'Błyszczy nawet w nocy.' },
  luks_samochod: { name: 'Sportowy samochód', emoji: '🏎️', price: 1_000_000, type: 'luxury', unique: true, desc: 'Szybki, głośny, bezużyteczny w korkach.' },
  luks_jacht: { name: 'Jacht', emoji: '🛥️', price: 5_000_000, type: 'luxury', unique: true, desc: 'Ma basen. I drugi basen.' },
  luks_wyspa: { name: 'Prywatna wyspa', emoji: '🏝️', price: 25_000_000, type: 'luxury', unique: true, desc: 'Palmy w komplecie, sąsiadów brak.' },
  luks_zamek: { name: 'Zamek', emoji: '🏯', price: 100_000_000, type: 'luxury', unique: true, desc: 'Z fosą i własnym duchem.' },
  luks_odrzutowiec: { name: 'Prywatny odrzutowiec', emoji: '🛩️', price: 400_000_000, type: 'luxury', unique: true, desc: 'Na śniadanie do Paryża, na obiad do Tokio.' },
  luks_satelita: { name: 'Własny satelita', emoji: '🛰️', price: 1_500_000_000, type: 'luxury', unique: true, desc: 'Krąży nad Twoim domem i nikt nie wie po co.' },
  luks_ksiezyc: { name: 'Kawałek Księżyca', emoji: '🌙', price: 5_000_000_000, type: 'luxury', unique: true, desc: 'Akt notarialny w komplecie (nieważny).' },
  luks_planeta: { name: 'Własna planeta', emoji: '🪐', price: 50_000_000_000, type: 'luxury', unique: true, desc: 'Absolutny szczyt. Nazwij ją jak chcesz.' },
  trofeum: { name: 'Złote trofeum', emoji: '🏆', price: 1_000_000, type: 'trophy', desc: 'Dla prawdziwych milionerów. Wyświetla się na profilu.' },

  // --- ryby ---
  but: { name: 'Stary but', emoji: '👢', sell: 2, type: 'loot' },
  sledz: { name: 'Śledź', emoji: '🐟', sell: 25, type: 'loot' },
  karp: { name: 'Karp', emoji: '🐠', sell: 60, type: 'loot' },
  szczupak: { name: 'Szczupak', emoji: '🐡', sell: 130, type: 'loot' },
  sum: { name: 'Sum', emoji: '🐋', sell: 260, type: 'loot' },
  tunczyk: { name: 'Tuńczyk', emoji: '🍣', sell: 450, type: 'loot' },
  rekin: { name: 'Rekin', emoji: '🦈', sell: 1800, type: 'loot' },

  // --- kopalnia ---
  kamien: { name: 'Kamień', emoji: '🪨', sell: 4, type: 'loot' },
  wegiel: { name: 'Węgiel', emoji: '⚫', sell: 20, type: 'loot' },
  zelazo: { name: 'Żelazo', emoji: '🔩', sell: 55, type: 'loot' },
  zloto: { name: 'Złoto', emoji: '🥇', sell: 140, type: 'loot' },
  diament: { name: 'Diament', emoji: '💎', sell: 480, type: 'loot' },
  rubin: { name: 'Rubin', emoji: '❤️‍🔥', sell: 900, type: 'loot' },
  meteoryt: { name: 'Meteoryt', emoji: '☄️', sell: 2500, type: 'loot' },

  // --- polowanie ---
  krolik: { name: 'Królik', emoji: '🐇', sell: 35, type: 'loot' },
  kaczka: { name: 'Kaczka', emoji: '🦆', sell: 60, type: 'loot' },
  jelen: { name: 'Jeleń', emoji: '🦌', sell: 200, type: 'loot' },
  dzik: { name: 'Dzik', emoji: '🐗', sell: 340, type: 'loot' },
  niedzwiedz: { name: 'Niedźwiedź', emoji: '🐻', sell: 1200, type: 'loot' },
  zubr: { name: 'Żubr', emoji: '🦬', sell: 2200, type: 'loot' },

  // --- las (grzybobranie) ---
  szyszka: { name: 'Szyszka', emoji: '🌰', sell: 6, type: 'loot' },
  jagody: { name: 'Jagody', emoji: '🫐', sell: 30, type: 'loot' },
  kurki: { name: 'Kurki', emoji: '🍄', sell: 75, type: 'loot' },
  borowik: { name: 'Borowik', emoji: '🍄‍🟫', sell: 150, type: 'loot' },
  trufla: { name: 'Trufla', emoji: '🟤', sell: 700, type: 'loot' },
  zloty_grzyb: { name: 'Złoty grzyb', emoji: '✨', sell: 3000, type: 'loot' },

  // --- śmietnik ---
  puszka: { name: 'Puszka', emoji: '🥫', sell: 3, type: 'loot' },
  butelka: { name: 'Butelka', emoji: '🍾', sell: 8, type: 'loot' },
  stary_telefon: { name: 'Stary telefon', emoji: '📱', sell: 140, type: 'loot' },
  zepsuty_zegarek: { name: 'Zepsuty zegarek', emoji: '🕰️', sell: 320, type: 'loot' },
  zlota_obraczka: { name: 'Złota obrączka', emoji: '💛', sell: 1500, type: 'loot' },
};

// Sklep w kategoriach. /sklep pokazuje przegląd, /sklep <kategoria> szczegóły z opisami.
const SHOP_CATEGORIES = [
  { id: 'wedki', title: '🎣 Wędki i kilofy', items: ['wedka', 'super_wedka', 'wedka_mistrza', 'wedka_legendarna', 'kilof', 'zloty_kilof', 'diamentowy_kilof', 'kilof_kosmiczny'] },
  { id: 'bron', title: '🔫 Broń, koszyki, instrumenty', items: ['strzelba', 'karabin', 'luneta', 'snajperka', 'koszyk', 'wielki_koszyk', 'gitara', 'skrzypce', 'fortepian'] },
  { id: 'ochrona', title: '🛡️ Ochrona i sprzęt', items: ['klodka', 'maska', 'alarm', 'lom', 'laptop', 'garnitur', 'samochod', 'pas_narzedziowy', 'talizman'] },
  { id: 'zwierzaki', title: '🐾 Zwierzaki', items: ['pies', 'kot', 'papuga', 'sowa', 'jednorozec', 'smok'] },
  { id: 'vip', title: '📈 Karty VIP i wiedza', items: ['vip_brazowy', 'vip_srebrny', 'vip_zloty', 'vip_platynowa', 'vip_diamentowa', 'dyplom', 'doktorat'] },
  { id: 'finanse', title: '🏦 Finanse i biznes', items: ['konto_oszczednosciowe', 'konto_premium', 'menedzer', 'ksiegowy', 'podlewaczka'] },
  { id: 'eliksiry', title: '⚡ Eliksiry czasowe', items: ['eliksir_zarobkow', 'mega_eliksir', 'podrecznik', 'mega_podrecznik', 'magnes_lupu', 'mega_magnes', 'eliksir_szczescia', 'zegarek', 'super_zegarek', 'koniczyna'] },
  { id: 'gry', title: '🧾 Ochrona w grach', items: ['polisa_mala', 'polisa_srednia', 'polisa_duza', 'polisa_platynowa', 'tarcza_mala', 'tarcza_srednia', 'tarcza_duza', 'tarcza_tytanowa'] },
  { id: 'skrzynie', title: '🎁 Skrzynie', items: ['skrzynka', 'skrzynia', 'skrzynia_zbieracza', 'skrzynia_hazardzisty', 'skrzynia_diamentowa', 'skrzynia_legendarna'] },
  { id: 'luksus', title: '💎 Luksus i prestiż', items: ['luks_okulary', 'luks_zegarek', 'luks_cylinder', 'luks_pierscien', 'luks_samochod', 'luks_jacht', 'luks_wyspa', 'luks_zamek', 'luks_odrzutowiec', 'luks_satelita', 'luks_ksiezyc', 'luks_planeta', 'trofeum'] },
];
const SHOP_ORDER = SHOP_CATEGORIES.flatMap((c) => c.items);

module.exports = {
  SEC,
  MIN,
  HOUR,
  SEJF_BONUS,
  BONUSES,
  VIP_CARDS,
  EFFECTS,
  BOOSTS,
  PROTECTIONS,
  BUSINESSES,
  BUSINESS_MAX_LEVEL,
  BUSINESS_STORAGE_HOURS,
  FARM_SLOTS,
  FARM_CROPS,
  ITEMS,
  SHOP_CATEGORIES,
  SHOP_ORDER,
};
