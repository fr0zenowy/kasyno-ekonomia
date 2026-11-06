'use strict';
// Modele 3D kart (poza oknem: w ręku, na ziemi, w ramce, na głowie). Karta to cienki prostopadłościan z awersem od południa (strona,
// którą widać na zwykłych przedmiotach), rewersem od północy i jasnym brzegiem. Tekstury mają 50x70 px (proporcje 5:7), rysowane kodem:
// figura i mały kolor w dwóch rogach, kolory ułożone jak na prawdziwych kartach, korona na królu i damie, rewers z kratką i medalionem.
// W oknie przedmiot nadal pokazuje płaską ikonę 16x16 z cards.js (wybór po display_context w items/<id>.json).
const { Img } = require('./img');
const { drawText5, width5 } = require('./font');
const { names, SUITS, OUT, FACE, FACE_SHADE, GOLD } = require('./cards');

const TW = 50;
const TH = 70;
const CX = TW / 2; // środek w układzie krawędzi pikseli
const PANEL = '#fdf3d0';

/** Rysuje kolor (pik, kier, karo, trefl) o połowie szerokości s ze środkiem (cx, cy); flip obraca go o 180 stopni (dolna połowa karty). */
function pip(g, cx, cy, s, kind, color, flip = false) {
  const f = flip ? -1 : 1;
  const X = (dx) => cx + dx * s;
  const Y = (dy) => cy + f * dy * s;
  // disc liczy środek piksela w liczbach całkowitych, a poly w krawędziach, stąd przesunięcie o pół piksela
  const disc = (dx, dy, r) => g.disc(X(dx) - 0.5, Y(dy) - 0.5, r * s, color);
  const poly = (pts) => g.poly(pts.map(([dx, dy]) => [X(dx), Y(dy)]), color);
  switch (kind) {
    case 'h':
      disc(-0.5, -0.4, 0.52);
      disc(0.5, -0.4, 0.52);
      poly([[-1.02, -0.2], [1.02, -0.2], [0, 1.0]]);
      break;
    case 'd':
      poly([[0, -1.15], [0.82, 0], [0, 1.15], [-0.82, 0]]);
      break;
    case 's':
      disc(-0.5, 0.15, 0.52);
      disc(0.5, 0.15, 0.52);
      poly([[-1.02, 0.0], [1.02, 0.0], [0, -1.05]]);
      poly([[-0.14, 0.4], [0.14, 0.4], [0.42, 1.05], [-0.42, 1.05]]);
      break;
    default: // trefl
      disc(0, -0.55, 0.44);
      disc(-0.58, 0.28, 0.44);
      disc(0.58, 0.28, 0.44);
      disc(0, 0.0, 0.3);
      poly([[-0.14, 0.3], [0.14, 0.3], [0.42, 1.05], [-0.42, 1.05]]);
  }
}

function rot180(src) {
  const out = new Img(src.w, src.h);
  for (let y = 0; y < src.h; y++) for (let x = 0; x < src.w; x++) out.px(src.w - 1 - x, src.h - 1 - y, src.get(x, y));
  return out;
}

/** Ułożenie kolorów na kartach liczbowych: [kolumna 0-2, wiersz 0-1 od góry do dołu]. */
const LAYOUT = {
  2: [[1, 0], [1, 1]],
  3: [[1, 0], [1, 0.5], [1, 1]],
  4: [[0, 0], [2, 0], [0, 1], [2, 1]],
  5: [[0, 0], [2, 0], [1, 0.5], [0, 1], [2, 1]],
  6: [[0, 0], [2, 0], [0, 0.5], [2, 0.5], [0, 1], [2, 1]],
  7: [[0, 0], [2, 0], [1, 0.25], [0, 0.5], [2, 0.5], [0, 1], [2, 1]],
  8: [[0, 0], [2, 0], [1, 0.25], [0, 0.5], [2, 0.5], [1, 0.75], [0, 1], [2, 1]],
  9: [[0, 0], [2, 0], [0, 1 / 3], [2, 1 / 3], [1, 0.5], [0, 2 / 3], [2, 2 / 3], [0, 1], [2, 1]],
  10: [[0, 0], [2, 0], [1, 1 / 6], [0, 1 / 3], [2, 1 / 3], [0, 2 / 3], [2, 2 / 3], [1, 5 / 6], [0, 1], [2, 1]],
};

const PIP_S = 3.6;
const COL_X = [CX - 6, CX, CX + 6];
const ROW_TOP = 13;
const ROW_BOT = TH - 13;

function frame(fill) {
  const g = new Img(TW, TH);
  g.rect(0, 0, TW, TH, OUT);
  g.rect(1, 1, TW - 2, TH - 2, fill);
  for (const [x, y] of [[1, 1], [TW - 2, 1], [1, TH - 2], [TW - 2, TH - 2]]) g.px(x, y, OUT); // lekko zaokrąglone rogi
  return g;
}

function crown(g, cx, y, w, peaks) {
  const h = 6;
  const pts = [[cx - w / 2, y + h], [cx - w / 2, y]];
  for (let i = 0; i < peaks; i++) {
    const x1 = cx - w / 2 + (w * (i + 0.5)) / peaks;
    pts.push([x1, y - 1], [cx - w / 2 + (w * (i + 1)) / peaks, y + 2]);
  }
  pts.pop();
  pts.push([cx + w / 2, y], [cx + w / 2, y + h]);
  g.poly(pts, GOLD[1]);
  g.rect(Math.round(cx - w / 2), y + h - 2, w, 2, GOLD[2]);
  g.px(Math.round(cx), y + h - 4, GOLD[0]);
}

/** Awers: figura i mały kolor w rogach (drugi róg obrócony), pośrodku kolory albo figura w złotej ramce. */
function face(rank, suit) {
  const g = frame(FACE);
  g.vline(TW - 2, 3, TH - 6, FACE_SHADE);
  g.hline(3, TH - 2, TW - 6, FACE_SHADE);
  const label = rank.toUpperCase();
  const tw = width5(label);
  const idx = new Img(13, 22);
  drawText5(idx, 0, 0, label, { color: suit.color });
  pip(idx, tw / 2 + 0.5, 14.5, 2.8, suit.key, suit.color);
  g.blit(idx, 4, 4);
  g.blit(rot180(idx), TW - 4 - idx.w, TH - 4 - idx.h);

  if (rank === 'a') {
    pip(g, CX, TH / 2, 11, suit.key, suit.color);
  } else if (rank === 'j' || rank === 'q' || rank === 'k') {
    g.rect(14, 8, 22, 54, PANEL);
    g.frame(14, 8, 22, 54, GOLD[1], 1);
    g.frame(15, 9, 20, 52, GOLD[2], 1);
    pip(g, CX, 17, 4.2, suit.key, suit.color);
    pip(g, CX, TH - 17, 4.2, suit.key, suit.color, true);
    if (rank !== 'j') crown(g, CX, 22, rank === 'k' ? 14 : 12, rank === 'k' ? 3 : 4);
    drawText5(g, Math.round(CX - 7.5), 29, label, { color: suit.color, scale: 3 });
  } else {
    for (const [c, t] of LAYOUT[Number(rank)]) {
      const y = ROW_TOP + t * (ROW_BOT - ROW_TOP);
      pip(g, COL_X[c], y, PIP_S, suit.key, suit.color, t > 0.5);
    }
  }
  return g;
}

/** Rewers: biała ramka, czerwona kratka z ukośnych linii i złoty medalion. */
function back() {
  const g = frame('#ffffff');
  const x0 = 4;
  const y0 = 4;
  for (let y = y0; y < TH - y0; y++) {
    for (let x = x0; x < TW - x0; x++) {
      const line = (x + y) % 6 === 0 || (x - y + 600) % 6 === 0;
      g.px(x, y, line ? '#e0525e' : '#a32232');
    }
  }
  g.frame(x0, y0, TW - 2 * x0, TH - 2 * y0, '#7a1420', 1);
  g.disc(CX - 0.5, TH / 2 - 0.5, 10, GOLD[2]);
  g.disc(CX - 0.5, TH / 2 - 0.5, 9, GOLD[1]);
  g.disc(CX - 0.5, TH / 2 - 0.5, 7, '#a32232');
  g.poly([[CX, TH / 2 - 6], [CX + 4, TH / 2], [CX, TH / 2 + 6], [CX - 4, TH / 2]], GOLD[1]);
  g.px(CX - 1, TH / 2 - 2, GOLD[0]);
  g.px(CX - 1, TH / 2 - 3, GOLD[0]);
  return g;
}

/** Brzeg karty: jednolity, jasny. */
function edge() {
  const g = new Img(4, 4);
  g.rect(0, 0, 4, 4, '#dfe3ee');
  return g;
}

/** Klucz modelu i tekstury: "h_k", "s_10", "back" (dla card_h_k, card_s_10, card_back). */
function key(id) {
  return id.replace(/^card_/, '');
}

function render3d(id) {
  if (id === 'card_back') return back();
  const [suit, rank] = key(id).split('_');
  return face(rank, SUITS.find((s) => s.key === suit));
}

// wymiary karty w jednostkach modelu: 16 na wysokość, szerokość z proporcji tekstury, grubość pół jednostki
const HEIGHT = 16;
const WIDTH = (HEIGHT * TW) / TH;
const THICK = 0.5;

/** Model JSON karty (przestrzeń nazw ns). Wyświetlanie jak vanilla item/generated, żeby karta leżała w ręku i w ramce tak samo jak zwykły przedmiot. */
function model(id, ns) {
  const faceTex = id === 'card_back' ? `${ns}:item/card/back` : `${ns}:item/card/${key(id)}`;
  const x0 = Math.round(((16 - WIDTH) / 2) * 100) / 100;
  const x1 = Math.round((x0 + WIDTH) * 100) / 100;
  const z0 = 8 - THICK / 2;
  const z1 = 8 + THICK / 2;
  return {
    textures: { face: faceTex, back: `${ns}:item/card/back`, edge: `${ns}:item/card/edge`, particle: `${ns}:item/card/edge` },
    gui_light: 'front',
    elements: [{
      from: [x0, 0, z0],
      to: [x1, HEIGHT, z1],
      faces: {
        south: { uv: [0, 0, 16, 16], texture: '#face' },
        north: { uv: [0, 0, 16, 16], texture: '#back' },
        east: { uv: [0, 0, 16, 16], texture: '#edge' },
        west: { uv: [0, 0, 16, 16], texture: '#edge' },
        up: { uv: [0, 0, 16, 16], texture: '#edge' },
        down: { uv: [0, 0, 16, 16], texture: '#edge' },
      },
    }],
    display: {
      ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.5, 0.5, 0.5] },
      head: { rotation: [0, 180, 0], translation: [0, 13, 7], scale: [1, 1, 1] },
      thirdperson_righthand: { rotation: [0, 0, 0], translation: [0, 3, 1], scale: [0.55, 0.55, 0.55] },
      firstperson_righthand: { rotation: [0, -90, 25], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] },
      fixed: { rotation: [0, 180, 0], scale: [1, 1, 1] },
    },
  };
}

module.exports = { names, key, render3d, edge, model, TW, TH };
