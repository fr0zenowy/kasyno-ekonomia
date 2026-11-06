'use strict';
// Talia 52 kart i rewers jako ikony 16x16 (id: card_<kolor>_<figura>, card_back). Kolory jak w grze: s = pik, h = kier, d = karo, c = trefl,
// czyli numer koloru 0-3 z Games.Card. Figury: a, 2-10, j, q, k. Karta ma 11x16 px (proporcje prawdziwej karty), figurę w rogu i kolor pod nią.
const { Img } = require('./img');
const { drawText3 } = require('./font');

const OUT = '#1b1014';
const FACE = '#f7f8fc';
const FACE_SHADE = '#c9cfdc';
const RED = '#d9323c';
const BLACK = '#262a38';
const GOLD = ['#fff09a', '#f4bb2a', '#b9780f'];

const X0 = 2; // lewa krawędź karty (z ramką); karta zajmuje x 2..12, y 0..15
const CARD_W = 11;
const CARD_H = 16;

const SUITS = [
  { key: 's', color: BLACK, pip: ['..#..', '.###.', '#####', '#####', '..#..'] },
  { key: 'h', color: RED, pip: ['.#.#.', '#####', '#####', '.###.', '..#..'] },
  { key: 'd', color: RED, pip: ['..#..', '.###.', '#####', '.###.', '..#..'] },
  { key: 'c', color: BLACK, pip: ['.###.', '.###.', '#####', '..#..', '.###.'] },
];
const RANKS = ['a', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'j', 'q', 'k'];

function bits(g, x, y, rows, color) {
  rows.forEach((row, j) => {
    for (let i = 0; i < row.length; i++) if (row[i] === '#') g.px(x + i, y + j, color);
  });
}

/** Ramka karty z zaokrąglonymi rogami; wnętrze x 3..11, y 1..14. */
function blank(fill) {
  const g = new Img(16, 16);
  g.rect(X0, 0, CARD_W, CARD_H, OUT);
  g.rect(X0 + 1, 1, CARD_W - 2, CARD_H - 2, fill);
  for (const [x, y] of [[X0, 0], [X0 + CARD_W - 1, 0], [X0, CARD_H - 1], [X0 + CARD_W - 1, CARD_H - 1]]) g.px(x, y, [0, 0, 0, 0]);
  return g;
}

function face(rank, suit) {
  const g = blank(FACE);
  // lekki cień przy prawej i dolnej krawędzi daje wrażenie grubości
  g.vline(X0 + CARD_W - 2, 2, CARD_H - 4, FACE_SHADE);
  g.hline(X0 + 2, CARD_H - 2, CARD_W - 3, FACE_SHADE);
  const label = rank.toUpperCase();
  drawText3(g, 4, 2, label, suit.color);
  const royal = rank === 'j' || rank === 'q' || rank === 'k';
  if (royal) {
    // figury dostają złotą ramkę z kolorem w środku, żeby odróżniały się od kart liczbowych
    g.frame(4, 7, 7, 7, GOLD[1], 1);
    g.px(4, 7, GOLD[0]);
    g.px(10, 13, GOLD[2]);
    bits(g, 5, 8, suit.pip, suit.color);
  } else {
    bits(g, 5, 8, suit.pip, suit.color);
  }
  if (rank === 'a') {
    // as ma iskrę nad kolorem po prawej stronie figury
    g.px(9, 3, GOLD[1]);
    g.px(9, 4, GOLD[0]);
    g.px(8, 4, GOLD[1]);
    g.px(10, 4, GOLD[1]);
    g.px(9, 5, GOLD[1]);
  }
  return g;
}

function back() {
  const g = blank('#ffffff');
  const lattice = ['#c42b3a', '#8f1c2a'];
  for (let y = 2; y <= CARD_H - 3; y++) {
    for (let x = X0 + 2; x <= X0 + CARD_W - 3; x++) g.px(x, y, lattice[(x + y) % 2]);
  }
  // złoty romb pośrodku
  const cx = X0 + 5;
  const cy = 7;
  for (const [dx, dy] of [[0, -2], [-1, -1], [0, -1], [1, -1], [-2, 0], [-1, 0], [0, 0], [1, 0], [2, 0], [-1, 1], [0, 1], [1, 1], [0, 2]]) g.px(cx + dx, cy + 1 + dy, GOLD[1]);
  g.px(cx, cy + 1, GOLD[0]);
  return g;
}

const IDS = ['card_back'];
for (const s of SUITS) for (const r of RANKS) IDS.push(`card_${s.key}_${r}`);

/** Identyfikatory ikon kart: najpierw rewers, potem pik, kier, karo, trefl po 13 figur. */
function names() {
  return IDS.slice();
}

function render(id) {
  if (id === 'card_back') return back();
  const m = /^card_([shdc])_(a|10|[2-9]|j|q|k)$/.exec(id);
  if (!m) throw new Error('Nieznana karta: ' + id);
  return face(m[2], SUITS.find((s) => s.key === m[1]));
}

module.exports = { names, render, SUITS, RANKS, OUT, FACE, FACE_SHADE, GOLD };
