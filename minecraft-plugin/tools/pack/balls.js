'use strict';
// Modele 3D piłek do pluginu Sport (koszykówka i siatkówka). Kulę składamy z cienkich warstw ułożonych jedna na drugiej; każda warstwa to
// kwadrat i drugi, obrócony o 45 stopni, więc z boku piłka jest okrągła, a z góry ośmiokątna. Tekstura to mapa kuli (wzdłuż: długość
// geograficzna, w pionie: szerokość), rysowana kodem, bez zewnętrznych plików. Wzór powtarza się co 90 stopni długości, dzięki czemu
// ściany obrócone o 45 stopni zgadzają się ze sobą niezależnie od tego, w którą stronę klient liczy obrót elementów.
const { Img, hex, mix } = require('./img');

const TW = 80; // 64 px = 360 stopni; 80 px = 450 stopni (od -45 do 405), bo ściany bryły sięgają 45 stopni w obie strony od swojego środka
const EQ = 32; // 32 px = 180 stopni (od bieguna do bieguna)
const CAP = 20; // pod mapą kuli cztery kafelki 20x20: widok z góry i z dołu, osobno dla warstw prostych i obróconych o 45 stopni
const TH = 64; // wysokość tekstury podzielna przez 16, bo atlas tekstur układa sprite'y w wielokrotnościach rozmiaru mipmapy (reszta pod kafelkami jest wypełniona)
const PX_DEG = 360 / 64;
const R = 8;

const names = () => ['basket', 'volley'];
const key = (name) => name;

const rad = (d) => (d * Math.PI) / 180;

/** Pseudolosowa liczba z (x, y) bez stanu, stała między uruchomieniami: tekstura ma być powtarzalna. */
function hash(x, y) {
  let h = (x * 374761393 + y * 668265263) | 0;
  h = (h ^ (h >>> 13)) * 1274126177;
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}

/** Odległość kątowa (w stopniach) punktu kuli (lon, lat) od najbliższego z wielkich okręgów wzoru piłki do koszykówki. */
function basketSeam(lon, lat) {
  const cl = Math.cos(rad(lat));
  const px = cl * Math.sin(rad(lon));
  const py = Math.sin(rad(lat));
  const pz = cl * Math.cos(rad(lon));
  const deg = (v) => (Math.asin(Math.min(1, Math.abs(v))) * 180) / Math.PI;
  // równik i cztery południki (co 90 stopni długości): wzór ma być czterokrotnie symetryczny
  return Math.min(deg(py), deg(px), deg(pz));
}

const SAMPLES = 4;

/** Średnia kolorów z kilku próbek piksela, żeby linie nie były poszarpane. `at(i, j)` zwraca kolor dla współrzędnych w ułamku piksela. */
function sampled(x, y, at) {
  let r = 0;
  let g = 0;
  let b = 0;
  for (let sy = 0; sy < SAMPLES; sy++) {
    for (let sx = 0; sx < SAMPLES; sx++) {
      const c = hex(at(x + (sx + 0.5) / SAMPLES, y + (sy + 0.5) / SAMPLES));
      r += c[0];
      g += c[1];
      b += c[2];
    }
  }
  const n = SAMPLES * SAMPLES;
  return [Math.round(r / n), Math.round(g / n), Math.round(b / n), 255];
}

/**
 * Rysuje teksturę funkcją koloru (lon, lat). Górna część to mapa kuli; pod nią cztery kafelki widoku z góry (biegun północny,
 * warstwy proste i obrócone o 45 stopni) i z dołu (to samo), do odsłoniętych pierścieni między warstwami.
 */
function paint(colorAt) {
  const g = new Img(TW, TH);
  for (let y = 0; y < EQ; y++) {
    for (let x = 0; x < TW; x++) {
      g.px(x, y, sampled(x, y, (u, v) => colorAt((((u * PX_DEG - 45) % 360) + 360) % 360, 90 - (v / EQ) * 180)));
    }
  }
  for (let t = 0; t < 4; t++) {
    const top = t < 2;
    const turn = t % 2 === 1 ? 45 : 0;
    for (let j = 0; j < CAP; j++) {
      for (let i = 0; i < CAP; i++) {
        g.px(t * CAP + i, EQ + j, sampled(i, j, (u, v) => {
          const X = (u / CAP) * 2 - 1;
          const Z = (v / CAP) * 2 - 1;
          const up = Math.sqrt(Math.max(0, 1 - X * X - Z * Z));
          const lon = ((((Math.atan2(X, Z) * 180) / Math.PI + turn) % 360) + 360) % 360;
          return colorAt(lon, (top ? 1 : -1) * ((Math.asin(Math.min(1, up)) * 180) / Math.PI));
        }));
      }
    }
  }
  // pasek pod kafelkami: powtórzony ostatni rząd, żeby cała tekstura była nieprzezroczysta
  for (let y = EQ + CAP; y < TH; y++) for (let x = 0; x < TW; x++) g.px(x, y, g.get(x, EQ + CAP - 1));
  return g;
}

function basketTexture() {
  const ORANGE = '#e2701f';
  const DARK = '#c75d14';
  const LIGHT = '#ee8434';
  const SEAM = '#26160d';
  return paint((lon, lat) => {
    const d = basketSeam(lon, lat);
    if (d < 2.4) return SEAM;
    if (d < 4.2) return mix(ORANGE, SEAM, 0.35);
    // delikatna faktura skóry: drobne ciemniejsze i jaśniejsze punkty
    const h = hash(Math.floor(lon / 3.1), Math.floor((lat + 90) / 4.3));
    if (h < 0.16) return DARK;
    if (h > 0.9) return LIGHT;
    return ORANGE;
  });
}

function volleyTexture() {
  const WHITE = '#f6f1e2';
  const YELLOW = '#f3c531';
  const BLUE = '#2f68c7';
  const SEAM = '#8d8570';
  const colors = [WHITE, YELLOW, BLUE];
  return paint((lon, lat) => {
    // skośne pasy po trzy kolory, szerokość 30 stopni długości, przesuwane z szerokością geograficzną (jak panele piłki siatkarskiej)
    const s = lon / 30 + (lat / 90) * 3;
    const f = s - Math.floor(s);
    const idx = ((Math.floor(s) % 3) + 3) % 3;
    const edge = Math.min(f, 1 - f) * 30; // odległość od brzegu pasa w stopniach długości
    if (edge < 1.5) return SEAM;
    return colors[idx];
  });
}

function texture(name) {
  return name === 'basket' ? basketTexture() : volleyTexture();
}

// ---------- model ----------

/** Granice warstw względem środka kuli (jednostki modelu, 16 = blok). Gęściej przy biegunach, żeby czapki były małe. */
const BOUNDS = [-8, -7, -5.6, -3.8, -1.9, 0, 1.9, 3.8, 5.6, 7, 8];
const ROTATIONS = [0, 45];
const BASE_AZIMUTH = { south: 0, east: 90, north: 180, west: 270 };

function round(v) {
  return Math.round(v * 1000) / 1000;
}

/** Współrzędne UV modelu blokowego są zawsze w skali 0-16, niezależnie od rozmiaru tekstury (piksele tekstury 80x52 przeliczamy na tę skalę). */
function uv16(u0, v0, u1, v1) {
  return [round((u0 / TW) * 16), round((v0 / TH) * 16), round((u1 / TW) * 16), round((v1 / TH) * 16)];
}

function model(name, ns) {
  const tex = `${ns}:item/ball/${key(name)}`;
  const elements = [];
  for (let k = 0; k < BOUNDS.length - 1; k++) {
    const y0 = BOUNDS[k];
    const y1 = BOUNDS[k + 1];
    const ym = (y0 + y1) / 2;
    // promień okręgu opisanego na warstwie: pierwiastek z promienia kuli i wysokości środka warstwy, trochę powiększony,
    // bo obrócony kwadrat ma między czubkami wcięcia
    const tip = Math.sqrt(R * R - ym * ym) * 1.1;
    const half = tip / Math.SQRT2;
    const vTop = ((90 - (Math.asin(Math.min(1, Math.max(-1, y1 / R))) * 180) / Math.PI) / 180) * EQ;
    const vBottom = ((90 - (Math.asin(Math.min(1, Math.max(-1, y0 / R))) * 180) / Math.PI) / 180) * EQ;
    for (const angle of ROTATIONS) {
      const faces = {};
      for (const [dir, base] of Object.entries(BASE_AZIMUTH)) {
        const center = (((base + angle) % 360) + 360) % 360;
        const u0 = round(center / PX_DEG); // od (środek - 45) stopni, a kolumna 0 tekstury to -45 stopni
        const u1 = round((center + 90) / PX_DEG);
        faces[dir] = { uv: uv16(u0, vTop, u1, vBottom), texture: '#ball' };
      }
      // odsłonięty pierścień: w górnej półkuli patrzymy na górne ściany warstw, w dolnej na dolne. Ściana pokazuje środek kafelka widoku
      // z góry (z dołu), proporcjonalnie do swojego rozmiaru względem kuli
      const ringDir = ym > 0 ? 'up' : 'down';
      const tile = (ym > 0 ? 0 : 2) + (angle === 0 ? 0 : 1);
      const c0 = tile * CAP + CAP / 2;
      const r0 = EQ + CAP / 2;
      const span = (half / R) * (CAP / 2);
      faces[ringDir] = { uv: uv16(c0 - span, r0 - span, c0 + span, r0 + span), texture: '#ball' };
      const el = {
        from: [round(8 - half), round(8 + y0), round(8 - half)],
        to: [round(8 + half), round(8 + y1), round(8 + half)],
        faces,
      };
      if (angle !== 0) el.rotation = { angle, axis: 'y', origin: [8, 8, 8] };
      elements.push(el);
    }
  }
  return {
    textures: { ball: tex, particle: tex },
    elements,
    display: {
      gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.85, 0.85, 0.85] },
      ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.5, 0.5, 0.5] },
      fixed: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [1, 1, 1] },
      head: { rotation: [0, 0, 0], translation: [0, 12, 0], scale: [0.9, 0.9, 0.9] },
      thirdperson_righthand: { rotation: [0, 0, 0], translation: [0, 3, 1], scale: [0.55, 0.55, 0.55] },
      firstperson_righthand: { rotation: [0, 0, 0], translation: [1, 4, 1], scale: [0.6, 0.6, 0.6] },
    },
  };
}

/** Definicja przedmiotu (assets/<ns>/items/ball_<nazwa>.json): ten sam model w każdym kontekście. */
function itemDefinition(name, ns) {
  return { model: { type: 'minecraft:model', model: `${ns}:ball/${key(name)}` } };
}

module.exports = { names, key, texture, model, itemDefinition, TW, TH, EQ, CAP, PX_DEG, BOUNDS };
