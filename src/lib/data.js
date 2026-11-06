// Katalog przedmiotów, prac i pytań. Tu najłatwiej dopisać własne rzeczy.

const START_BALANCE = 500;

const catalog = require('./catalog');
const { SEC, MIN, HOUR, SEJF_BONUS } = catalog;

// --- cooldowny (w milisekundach) ---
// Tu zmienisz czas oczekiwania między użyciami komend. Opisy komend podchwycą nowe wartości po `npm run deploy`.
// (/daily i /weekly mają osobne liczniki: 20 godzin i 7 dni.)
const COOLDOWNS = {
  praca: 10 * MIN,
  zebraj: 30 * SEC,
  crime: 5 * MIN, // /przestepstwo
  rob: 7.5 * MIN, // /okradnij: jak często możesz okradać
  robbed: 15 * MIN, // ochrona ofiary po okradzeniu
  heist: 15 * MIN, // /napad
  ryby: 1.5 * MIN,
  kopalnia: 2 * MIN,
  polowanie: 2.5 * MIN,
  trivia: 45 * SEC,
  matma: 30 * SEC,
  godzinna: HOUR, // /godzinna
  las: 2 * MIN,
  smietnik: 45 * SEC,
  uliczny: 1 * MIN,
};

// --- bank ---
const BANK_BASE = 100_000; // limit na start
const BANK_PER_LEVEL = 5_000; // dodatek za każdy poziom ponad pierwszy

// Ulepszenia kupuje się po kolei: /bank ulepsz. `bonus` dodaje się do limitu na stałe.
const BANK_UPGRADES = [
  { name: 'Sejf domowy', emoji: '🗄️', cost: 50_000, bonus: 150_000 },
  { name: 'Skarbiec bankowy', emoji: '🔐', cost: 250_000, bonus: 400_000 },
  { name: 'Własny oddział banku', emoji: '🏦', cost: 1_000_000, bonus: 1_500_000 },
  { name: 'Podziemny skarbiec', emoji: '🏰', cost: 5_000_000, bonus: 6_000_000 },
  { name: 'Szwajcarskie konto', emoji: '🇨🇭', cost: 25_000_000, bonus: 30_000_000 },
  { name: 'Kosmiczny skarbiec', emoji: '🌌', cost: 150_000_000, bonus: 200_000_000 },
  { name: 'Bank Wszechświata', emoji: '♾️', cost: 1_000_000_000, bonus: 1_500_000_000 },
];

const JOBS = [
  { id: 'sprzatacz', name: 'Sprzątacz', emoji: '🧹', level: 1, min: 80, max: 180, lines: ['Wyszorowałeś całe biuro na błysk.', 'Znalazłeś w koszu coś, co ktoś ewidentnie wyrzucił przez pomyłkę.', 'Umyłeś okna na 12. piętrze. Nie patrzyłeś w dół.'] },
  { id: 'kurier', name: 'Kurier', emoji: '🚚', level: 3, min: 150, max: 320, lines: ['Dowiozłeś 40 paczek, a klienci byli nawet mili.', 'Paczka z kapciami dotarła cała. To już sukces.', 'Wjechałeś w korek, ale i tak zdążyłeś przed 18:00.'] },
  { id: 'kucharz', name: 'Kucharz', emoji: '🍳', level: 5, min: 250, max: 480, lines: ['Twoje pierogi zniknęły ze stołu w 5 minut.', 'Szef kuchni pochwalił Twój sos. Raz. Cicho.', 'Zrobiłeś zapiekankę stulecia.'] },
  { id: 'programista', name: 'Programista', emoji: '👨‍💻', level: 8, min: 400, max: 800, lines: ['Naprawiłeś buga, który tkwił w kodzie od 2014 roku.', 'Wrzuciłeś na produkcję w piątek o 16:59. Nic nie wybuchło.', 'Stack Overflow znów uratował Ci dzień.'] },
  { id: 'lekarz', name: 'Lekarz', emoji: '🩺', level: 12, min: 650, max: 1200, lines: ['Przyjąłeś 30 pacjentów i dalej masz siłę żyć.', 'Postawiłeś trafną diagnozę – „odpoczywaj i pij wodę”.', 'Dyżur nocny zaliczony, kawa to teraz Twoja krew.'] },
  { id: 'pilot', name: 'Pilot', emoji: '✈️', level: 16, min: 900, max: 1700, lines: ['Lądowanie 10/10. Pasażerowie klaskali.', 'Przeleciałeś nad Alpami w pięknej pogodzie.', 'Turbulencje były, ale kawa się nie wylała.'] },
  { id: 'ceo', name: 'Prezes', emoji: '👔', level: 25, min: 1500, max: 3000, lines: ['Odbyłeś spotkanie, które mogło być mailem. Ale zarobiłeś.', 'Wykupiłeś konkurencję. Pomyślnie.', 'Zatwierdziłeś budżet i pojechałeś na golfa.'] },
];

const CRIMES = [
  { win: 'Okradłeś kantor, nikt nawet nie mrugnął.', lose: 'Wpadłeś na kamerę monitoringu w kantorze.' },
  { win: 'Podmieniłeś obraz w galerii. Nikt nie zauważył różnicy.', lose: 'Alarm w galerii wył tak głośno, że słychać go było do Berlina.' },
  { win: 'Zhakowałeś bankomat i wypłacił Ci, ile chciałeś.', lose: 'Bankomat zjadł Twoją kartę i zadzwonił po policję.' },
  { win: 'Wyniosłeś z hotelu sejf w walizce.', lose: 'Walizka z sejfem okazała się za ciężka. Ochroniarz pomógł Ci ją zanieść... na komisariat.' },
  { win: 'Sprzedałeś turystom „oryginalną” wieżę Eiffla.', lose: 'Turysta okazał się policjantem po cywilnemu.' },
  { win: 'Obrobiłeś kasyno z żetonów w trakcie awarii prądu.', lose: 'Awaria prądu się skończyła. Dokładnie w tym momencie.' },
];

const TRIVIA = [
  { q: 'Ile planet ma Układ Słoneczny?', a: '8', w: ['7', '9', '10'] },
  { q: 'Jaki symbol chemiczny ma złoto?', a: 'Au', w: ['Ag', 'Go', 'Gd'] },
  { q: 'Najdłuższa rzeka w Polsce to...', a: 'Wisła', w: ['Odra', 'Warta', 'Bug'] },
  { q: 'Kto napisał „Pana Tadeusza”?', a: 'Adam Mickiewicz', w: ['Juliusz Słowacki', 'Henryk Sienkiewicz', 'Bolesław Prus'] },
  { q: 'W którym roku Polska wstąpiła do Unii Europejskiej?', a: '2004', w: ['1999', '2001', '2007'] },
  { q: 'Który ocean jest największy?', a: 'Spokojny', w: ['Atlantycki', 'Indyjski', 'Arktyczny'] },
  { q: 'Ile kości ma dorosły człowiek?', a: '206', w: ['186', '212', '250'] },
  { q: 'Stolicą Australii jest...', a: 'Canberra', w: ['Sydney', 'Melbourne', 'Perth'] },
  { q: 'Najwyższy szczyt w Polsce to...', a: 'Rysy', w: ['Śnieżka', 'Giewont', 'Babia Góra'] },
  { q: 'W którym roku odbyła się bitwa pod Grunwaldem?', a: '1410', w: ['1370', '1525', '1683'] },
  { q: 'Które zwierzę lądowe jest najszybsze?', a: 'Gepard', w: ['Lew', 'Antylopa', 'Struś'] },
  { q: 'Kraj o kształcie buta to...', a: 'Włochy', w: ['Hiszpania', 'Grecja', 'Portugalia'] },
  { q: 'Kto napisał serię o Wiedźminie?', a: 'Andrzej Sapkowski', w: ['Stanisław Lem', 'Jacek Dukaj', 'Remigiusz Mróz'] },
  { q: 'Ilu zawodników jednej drużyny gra na boisku w piłce nożnej?', a: '11', w: ['9', '10', '12'] },
  { q: 'Stolicą Japonii jest...', a: 'Tokio', w: ['Osaka', 'Kioto', 'Seul'] },
  { q: 'Jaka jest najmniejsza liczba pierwsza?', a: '2', w: ['0', '1', '3'] },
  { q: 'Kto jako pierwszy stanął na Księżycu?', a: 'Neil Armstrong', w: ['Buzz Aldrin', 'Jurij Gagarin', 'Michael Collins'] },
  { q: 'Ile strun ma standardowa gitara klasyczna?', a: '6', w: ['4', '5', '7'] },
  { q: 'Który kontynent jest największy?', a: 'Azja', w: ['Afryka', 'Ameryka Północna', 'Europa'] },
  { q: 'W którym roku wybuchła II wojna światowa?', a: '1939', w: ['1914', '1941', '1945'] },
  { q: 'Stolica Hiszpanii to...', a: 'Madryt', w: ['Barcelona', 'Walencja', 'Sewilla'] },
  { q: 'Kto napisał „Quo vadis”?', a: 'Henryk Sienkiewicz', w: ['Bolesław Prus', 'Stefan Żeromski', 'Adam Mickiewicz'] },
  { q: 'Ile to jest pierwiastek kwadratowy z 144?', a: '12', w: ['11', '14', '16'] },
  { q: 'Które jezioro jest najgłębsze na świecie?', a: 'Bajkał', w: ['Wiktorii', 'Tanganika', 'Górne'] },
  { q: 'Kto namalował „Mona Lisę”?', a: 'Leonardo da Vinci', w: ['Michał Anioł', 'Rafael', 'Van Gogh'] },
  { q: 'Którą planetę nazywamy Czerwoną Planetą?', a: 'Mars', w: ['Wenus', 'Jowisz', 'Merkury'] },
  { q: 'Jak nazywa się najwyższy szczyt świata?', a: 'Mount Everest', w: ['K2', 'Mont Blanc', 'Kilimandżaro'] },
  { q: 'Ile dni ma rok przestępny?', a: '366', w: ['364', '365', '367'] },
  { q: 'Walutą Japonii jest...', a: 'Jen', w: ['Won', 'Juan', 'Rupia'] },
  { q: 'Ile kolorów ma tęcza?', a: '7', w: ['5', '6', '8'] },
  { q: 'Który metal jest płynny w temperaturze pokojowej?', a: 'Rtęć', w: ['Ołów', 'Cynk', 'Cyna'] },
  { q: 'W którym mieście stoi Wawel?', a: 'Kraków', w: ['Warszawa', 'Gdańsk', 'Wrocław'] },
  { q: 'Jaki symbol chemiczny ma żelazo?', a: 'Fe', w: ['Fr', 'Ir', 'Fo'] },
  { q: 'Stolicą Francji jest...', a: 'Paryż', w: ['Lyon', 'Marsylia', 'Bruksela'] },
  { q: 'Największa planeta Układu Słonecznego to...', a: 'Jowisz', w: ['Saturn', 'Neptun', 'Ziemia'] },
  { q: 'Kto odkrył penicylinę?', a: 'Alexander Fleming', w: ['Ludwik Pasteur', 'Maria Skłodowska-Curie', 'Robert Koch'] },
  { q: 'Kto sformułował teorię względności?', a: 'Albert Einstein', w: ['Isaac Newton', 'Nikola Tesla', 'Galileusz'] },
  { q: 'W którym kraju leży Machu Picchu?', a: 'Peru', w: ['Meksyk', 'Chile', 'Kolumbia'] },
  { q: 'Ile klawiszy ma standardowy fortepian?', a: '88', w: ['61', '76', '100'] },
  { q: 'Jak nazywa się hydraulik w czerwonej czapce z gier Nintendo?', a: 'Mario', w: ['Luigi', 'Wario', 'Toad'] },
  { q: 'Z jakiej gry pochodzi Creeper?', a: 'Minecraft', w: ['Roblox', 'Terraria', 'Fortnite'] },
  { q: 'Jakiego koloru jest Pikachu?', a: 'Żółtego', w: ['Pomarańczowego', 'Czerwonego', 'Niebieskiego'] },
  { q: 'Jak nazywa się główny bohater serii The Legend of Zelda?', a: 'Link', w: ['Zelda', 'Ganon', 'Epona'] },
];

module.exports = { START_BALANCE, COOLDOWNS, BANK_BASE, BANK_PER_LEVEL, BANK_UPGRADES, JOBS, CRIMES, TRIVIA, ...catalog };
