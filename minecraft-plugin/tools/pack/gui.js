'use strict';
// Tła okien: jedna grafika 176 x (222 + EXTRA) na ekran. Rysuje ją czcionka kasyna jako „tytuł” okna (technika z ujemną spacją),
// więc ma przezroczyste okienka dokładnie na slotach (16x16), żeby przedmioty były widoczne niezależnie od kolejności rysowania.
const { Img, mix } = require('./img');
const { drawText5, width5 } = require('./font');

const W = 176;
const CH = 222; // wysokość okna z 6 rzędami (vanilla)
const EXTRA = 16; // ile grafika wystaje ponad okno (wstęga z tytułem)
const H = CH + EXTRA;

const GOLD = { hi: '#fff0a0', mid: '#e8b230', lo: '#a8730e', dk: '#5c3a06' };
const SILVER = { hi: '#f2f6ff', mid: '#b8c4dc', lo: '#7482a0', dk: '#3a4560' };

// kolor bazowy panelu i rodzaj wykończenia
const THEMES = {
  red: { base: '#8f1626', trim: GOLD },
  gold: { base: '#9a5a12', trim: GOLD },
  amber: { base: '#b0501a', trim: GOLD },
  green: { base: '#14683a', trim: GOLD },
  blue: { base: '#1d3f85', trim: SILVER },
  purple: { base: '#5b2a8e', trim: GOLD },
  teal: { base: '#12687a', trim: GOLD },
  emerald: { base: '#1a7a3c', trim: GOLD },
  brown: { base: '#7a4a24', trim: GOLD },
  violet: { base: '#6a2e7a', trim: GOLD },
  slate: { base: '#36445f', trim: SILVER },
  dark: { base: '#2a2230', trim: SILVER },
  crimson: { base: '#7a1a40', trim: GOLD },
};

// ekran → [tytuł, motyw]. Nazwy ekranów to te same identyfikatory, które zwraca Gui.art() w pluginie.
const SCREENS = {
  default: ['KASYNO I EKONOMIA', 'red'],
  menu: ['MENU', 'red'],
  kasyno: ['KASYNO', 'gold'],
  automaty: ['AUTOMATY', 'amber'],
  karty: ['KARTY I KOŚCI', 'green'],
  ryzyko: ['RYZYKO', 'blue'],
  live: ['STOŁY NA ŻYWO', 'purple'],
  pokoj: ['POKÓJ GRACZY', 'crimson'],
  jackpot: ['JACKPOT', 'amber'],
  napad: ['NAPAD NA BANK', 'dark'],
  scena: ['AKCJA', 'dark'],
  pojedynek: ['POJEDYNEK', 'slate'],
  loteria: ['LOTERIA', 'teal'],
  gracz: ['WYBIERZ GRACZA', 'slate'],
  zarobki: ['ZAROBKI', 'emerald'],
  zawod: ['ZAWÓD', 'emerald'],
  trivia: ['TRIVIA', 'teal'],
  matma: ['MATMA', 'violet'],
  sklep: ['SKLEP', 'teal'],
  ekwipunek: ['EKWIPUNEK', 'violet'],
  bank: ['BANK', 'slate'],
  biznes: ['BIZNES', 'brown'],
  farma: ['FARMA', 'brown'],
  skrzynie: ['SKRZYNIE', 'brown'],
  skrzynia: ['OTWIERANIE', 'brown'],
  ranking: ['RANKING', 'gold'],
  profil: ['PROFIL', 'slate'],
  pomoc: ['POMOC', 'slate'],
  moneta: ['MONETA', 'amber'],
  sloty: ['SLOTY', 'amber'],
  zdrapka: ['ZDRAPKA', 'amber'],
  szansa: ['SZANSA', 'amber'],
  blackjack: ['BLACKJACK', 'green'],
  hilo: ['WYŻEJ CZY NIŻEJ', 'green'],
  kosci: ['KOŚCI', 'green'],
  knp: ['KAMIEŃ PAPIER NOŻYCE', 'green'],
  miny: ['MINY', 'blue'],
  kurczak: ['CHICKEN ROAD', 'blue'],
  wieza: ['WIEŻA', 'blue'],
  rewolwer: ['REWOLWER', 'blue'],
  limbo: ['LIMBO', 'blue'],
  plinko: ['PLINKO', 'blue'],
  crash: ['CRASH', 'purple'],
  ruletka: ['RULETKA', 'purple'],
  kolo: ['KOŁO FORTUNY', 'purple'],
  wyscigi: ['WYŚCIGI KONNE', 'purple'],
  // sceny akcji zarobkowych: każda ma własny tytuł (generyczna "scena" zostaje dla nieznanych akcji)
  scena_przestepstwo: ['PRZESTĘPSTWO', 'dark'],
  scena_rob: ['OKRADANIE', 'crimson'],
  scena_praca: ['PRACA', 'slate'],
  scena_zebraj: ['ZBIERANIE DROBNYCH', 'amber'],
  scena_uliczny: ['GRAJEK ULICZNY', 'violet'],
  scena_ryby: ['ŁOWIENIE RYB', 'blue'],
  scena_kopalnia: ['KOPALNIA', 'brown'],
  scena_polowanie: ['POLOWANIE', 'green'],
  scena_las: ['GRZYBOBRANIE', 'emerald'],
  scena_smietnik: ['ŚMIETNIKI', 'teal'],
  // wybór gracza do przelewu, okradania i pojedynku (generyczny "gracz" zostaje dla pozostałych)
  gracz_przelew: ['KOMU PRZELAĆ?', 'slate'],
  gracz_rob: ['KOGO OKRAŚĆ?', 'crimson'],
  gracz_pojedynek: ['KOGO WYZWAĆ?', 'red'],
};

/** Kolory stref mapy w oknie napadu: portrety ekipy po bokach, skarbiec na górze, hol i ulica. */
function heistTint(idx) {
  const r = Math.floor(idx / 9);
  const c = idx % 9;
  if (r > 4) return null;
  if (c === 0 || c === 8) return '#5a6c9a';
  if (r === 0 && c >= 3 && c <= 5) return '#d6a432';
  if (r === 4) return '#6b7080';
  return '#a89a7c';
}

const TINTS = { napad: heistTint };

/** Pozycje lewych górnych rogów slotów (16x16) w układzie grafiki: 54 sloty okna, potem 27 ekwipunku i 9 paska. */
function slots() {
  const out = [];
  for (let r = 0; r < 6; r++) for (let c = 0; c < 9; c++) out.push({ x: 8 + 18 * c, y: EXTRA + 18 + 18 * r, kind: 'top', index: r * 9 + c });
  for (let r = 0; r < 3; r++) for (let c = 0; c < 9; c++) out.push({ x: 8 + 18 * c, y: EXTRA + 139 + 18 * r, kind: 'inv', index: r * 9 + c });
  for (let c = 0; c < 9; c++) out.push({ x: 8 + 18 * c, y: EXTRA + 197, kind: 'hotbar', index: c });
  return out;
}

// deterministyczny „szum” do delikatnej faktury panelu
function hash(x, y, seed) {
  let h = (x * 374761393 + y * 668265263 + seed * 2147483647) | 0;
  h = (h ^ (h >>> 13)) * 1274126177;
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}

function seedOf(s) {
  let h = 7;
  for (const ch of s) h = (h * 31 + ch.charCodeAt(0)) | 0;
  return h;
}

/** Czy punkt leży w prostokącie ze ściętymi rogami (promień r pikseli). */
function inBox(x, y, x0, y0, w, h, r) {
  if (x < x0 || y < y0 || x >= x0 + w || y >= y0 + h) return false;
  const dx = Math.min(x - x0, x0 + w - 1 - x);
  const dy = Math.min(y - y0, y0 + h - 1 - y);
  return dx + dy >= r - 1;
}

function paintPanel(img, th, seed) {
  const top = mix(th.base, '#ffffff', 0.1);
  const bot = mix(th.base, '#000000', 0.32);
  for (let y = 0; y < CH; y++) {
    for (let x = 0; x < W; x++) {
      if (!inBox(x, y, 0, 0, W, CH, 3)) continue;
      const t = y / (CH - 1);
      let c = mix(top, bot, t);
      // romby jak na dywanie w kasynie: jasniejsza kratka co 8 pikseli
      const d1 = (x + y) % 8;
      const d2 = (x - y + 800) % 8;
      if (d1 === 0 || d2 === 0) c = mix(c, '#ffffff', 0.05);
      const n = hash(x, y, seed);
      if (n < 0.12) c = mix(c, '#000000', 0.06);
      else if (n > 0.9) c = mix(c, '#ffffff', 0.04);
      img.px(x, EXTRA + y, c);
    }
  }
}

function paintFrame(img, th) {
  const t = th.trim;
  const outline = mix(th.base, '#000000', 0.8);
  const inner = mix(th.base, '#000000', 0.55);
  // warstwy od zewnątrz: obrys, złota ramka (2 px) z fazą, ciemna linia
  for (let y = 0; y < CH; y++) {
    for (let x = 0; x < W; x++) {
      if (!inBox(x, y, 0, 0, W, CH, 3)) continue;
      const d = Math.min(x, y, W - 1 - x, CH - 1 - y);
      let c = null;
      if (d === 0) c = outline;
      else if (d === 1 || d === 2) {
        const lit = Math.min(x, y) <= Math.min(W - 1 - x, CH - 1 - y); // lewa i góra jaśniej, prawa i dół ciemniej
        c = d === 1 ? (lit ? t.hi : t.lo) : lit ? t.mid : t.dk;
      } else if (d === 3) c = inner;
      if (c) img.px(x, EXTRA + y, c);
    }
  }
  // nity w rogach
  for (const [x, y] of [[5, 5], [W - 6, 5], [5, CH - 6], [W - 6, CH - 6]]) {
    img.px(x, EXTRA + y, t.hi);
    img.px(x + 1, EXTRA + y + 1, t.dk);
    img.px(x + 1, EXTRA + y, t.mid);
    img.px(x, EXTRA + y + 1, t.mid);
  }
}

function paintCells(img, th, tint) {
  const dark = mix(th.base, '#000000', 0.7);
  const light = mix(th.base, '#ffffff', 0.16);
  const trayDark = mix(th.base, '#000000', 0.82);
  // tace: obrys całego bloku slotów okna i ekwipunku
  img.frame(6, EXTRA + 16, 164, 110, trayDark, 1);
  img.frame(6, EXTRA + 137, 164, 56, trayDark, 1);
  img.frame(6, EXTRA + 195, 164, 20, trayDark, 1);
  for (const s of slots()) {
    const fx = s.x - 1;
    const fy = s.y - 1;
    // strefy mapy (np. ulica, hol, skarbiec w napadzie) mają ramki komórek w innym kolorze
    const zone = tint && s.kind === 'top' ? tint(s.index) : null;
    const d = zone ? mix(zone, '#000000', 0.5) : dark;
    const l = zone ? mix(zone, '#ffffff', 0.25) : light;
    for (let i = 0; i < 18; i++) {
      img.px(fx + i, fy, d); // góra i lewo ciemne
      img.px(fx, fy + i, d);
      img.px(fx + i, fy + 17, i === 0 ? d : l); // dół i prawo jasne
      img.px(fx + 17, fy + i, i === 0 ? d : l);
    }
    for (let j = 0; j < 16; j++) for (let i = 0; i < 16; i++) img.px(s.x + i, s.y + j, [0, 0, 0, 0]);
  }
}

function paintBands(img, th) {
  const trim = th.trim;
  const dark = mix(th.base, '#000000', 0.55);
  // nagłówek: złota listewka pod wstęgą
  img.hline(7, EXTRA + 14, 162, trim.dk);
  img.hline(7, EXTRA + 13, 162, trim.mid);
  img.hline(7, EXTRA + 12, 162, mix(th.base, '#000000', 0.2));
  // pas między oknem a ekwipunkiem: ciemny, żeby jasne litery „Ekwipunek” z gry (ciemnoszare) prawie nie były widać
  img.rect(7, EXTRA + 127, 162, 10, dark);
  img.hline(7, EXTRA + 126, 162, mix(th.base, '#000000', 0.7));
  img.hline(7, EXTRA + 137, 162, trim.dk);
}

function paintRibbon(img, th, title) {
  const t = th.trim;
  const wide = width5(title, 2) <= 128;
  const scale = wide ? 2 : 1;
  const tw = width5(title, scale);
  const pad = wide ? 14 : 12;
  const w = Math.min(W - 24, tw + pad * 2);
  const h = wide ? 25 : 17;
  const cx = Math.floor(W / 2);
  const x0 = cx - Math.floor(w / 2);
  const y0 = wide ? 1 : 5;
  const body = mix(th.base, '#000000', 0.45);
  const bodyLo = mix(th.base, '#000000', 0.7);

  // ogony wstęgi z tyłu
  const tail = mix(th.base, '#000000', 0.6);
  for (const side of [-1, 1]) {
    const ex = side < 0 ? x0 - 8 : x0 + w;
    img.rect(ex, y0 + 4, 8, h - 6, tail);
    for (let i = 0; i < 4; i++) {
      // zęby jaskółczego ogona
      img.px(side < 0 ? ex + i : ex + 7 - i, y0 + 4 + Math.floor((h - 6) / 2) + (i - 1), [0, 0, 0, 0]);
    }
    img.frame(ex, y0 + 4, 8, h - 6, mix(th.base, '#000000', 0.85), 1);
  }
  // korpus
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (!inBox(x, y, 0, 0, w, h, 3)) continue;
      const c = mix(body, bodyLo, y / (h - 1));
      img.px(x0 + x, y0 + y, c);
    }
  }
  // złoty rant
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (!inBox(x, y, 0, 0, w, h, 3)) continue;
      const d = Math.min(x, y, w - 1 - x, h - 1 - y);
      if (d === 0) img.px(x0 + x, y0 + y, mix(th.base, '#000000', 0.85));
      else if (d === 1) img.px(x0 + x, y0 + y, Math.min(x, y) <= Math.min(w - 1 - x, h - 1 - y) ? t.hi : t.mid);
      else if (d === 2) img.px(x0 + x, y0 + y, Math.min(x, y) <= Math.min(w - 1 - x, h - 1 - y) ? t.mid : t.lo);
    }
  }
  // tekst: jasny na ciemnym, z cieniem
  const ty = y0 + Math.floor((h - 7 * scale) / 2) + (scale === 2 ? 1 : 0);
  drawText5(img, cx - Math.floor(tw / 2), ty, title, { color: '#fff8dc', scale, outline: mix(th.base, '#000000', 0.9) });
  // klejnoty po bokach wstęgi
  for (const gx of [x0 - 13, x0 + w + 12]) {
    const gy = y0 + Math.floor(h / 2) + 4;
    img.poly([[gx, gy - 4], [gx + 4, gy], [gx, gy + 4], [gx - 4, gy]], t.mid);
    img.poly([[gx, gy - 3], [gx + 2, gy], [gx, gy + 1], [gx - 2, gy]], t.hi);
    img.px(gx, gy + 3, t.dk);
  }
}

/** Pełne tło okna dla ekranu o tytule `title` i motywie `theme`. */
function drawGui(id, title, themeName, tint) {
  const th = THEMES[themeName] || THEMES.red;
  const img = new Img(W, H);
  const seed = seedOf(id);
  paintPanel(img, th, seed);
  paintFrame(img, th);
  paintBands(img, th);
  paintCells(img, th, tint);
  paintRibbon(img, th, title);
  return img;
}

/**
 * Podstawa pod okienkami (zastępuje generic_54.png): ciemne komórki, jasny nagłówek na czytelny tytuł vanilla i pasek ekwipunku.
 * Układ jak w vanilla: góra 176x125 od (0,0), dół 176x96 od (0,126) w teksturze 256x256.
 */
function drawBase() {
  const img = new Img(256, 256);
  const parch = '#d9c9a3';
  const wood = '#3a2620';
  const cellIn = '#251a1c';
  const hi = '#5a423a';
  const lo = '#150e10';
  const block = (x, y, w, h) => {
    img.rect(x, y, w, h, wood);
    img.frame(x, y, w, h, '#1a100d', 1);
  };
  // część górna (do 6 rzędów): nagłówek 17 px i rzędy po 18 px
  block(0, 0, 176, 125);
  img.rect(1, 1, 174, 15, parch);
  img.hline(1, 16, 174, '#8a7550');
  const cells = (ox, oy, rows) => {
    for (let r = 0; r < rows; r++) for (let c = 0; c < 9; c++) {
      const fx = ox + 7 + 18 * c;
      const fy = oy + 17 + 18 * r;
      img.rect(fx, fy, 18, 18, cellIn);
      img.hline(fx, fy, 18, lo);
      img.vline(fx, fy, 18, lo);
      img.hline(fx + 1, fy + 17, 17, hi);
      img.vline(fx + 17, fy + 1, 17, hi);
    }
  };
  cells(0, 0, 6);
  // część dolna (ekwipunek): 96 px od v = 126
  block(0, 126, 176, 96);
  img.rect(1, 127, 174, 12, parch);
  img.hline(1, 139, 174, '#8a7550');
  const inv = (rowY, rows) => {
    for (let r = 0; r < rows; r++) for (let c = 0; c < 9; c++) {
      const fx = 7 + 18 * c;
      const fy = rowY + 18 * r;
      img.rect(fx, fy, 18, 18, cellIn);
      img.hline(fx, fy, 18, lo);
      img.vline(fx, fy, 18, lo);
      img.hline(fx + 1, fy + 17, 17, hi);
      img.vline(fx + 17, fy + 1, 17, hi);
    }
  };
  inv(126 + 13, 3);
  inv(126 + 71, 1);
  return img;
}

module.exports = { TINTS, drawGui, drawBase, slots, SCREENS, THEMES, W, H, CH, EXTRA };
