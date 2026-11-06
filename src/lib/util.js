const { EmbedBuilder } = require('discord.js');

const CURRENCY = '💰';
const COLORS = {
  win: 0x2ecc71,
  lose: 0xe74c3c,
  push: 0xf1c40f,
  info: 0x5865f2,
  gold: 0xf1c40f,
  neutral: 0x2b2d31,
};

const group = (n) => String(Math.trunc(Math.abs(n))).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
const num = (n) => `${n < 0 ? '-' : ''}${group(n)}`;
const fmt = (n) => `${num(n)} ${CURRENCY}`;

/** Klucz do porównań tekstu: małe litery, bez polskich znaków i interpunkcji („Wędka” → „wedka”). */
const norm = (s) =>
  String(s)
    .toLowerCase()
    .replace(/ł/g, 'l')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]/g, '');

// W testach (BOT_FAST=1) pauzy animacji są pomijane, bo prawdziwe timery spowalniały cały zestaw o minuty.
const sleep = (ms) => new Promise((r) => (process.env.BOT_FAST === '1' ? setImmediate(r) : setTimeout(r, ms)));

const randInt = (min, max) => Math.floor(Math.random() * (max - min + 1)) + min;
const chance = (p) => Math.random() < p;
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];

function shuffle(arr) {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

/** Losuje element z tablicy obiektów z polem `w` (waga). `luck` mnoży wagi elementów oznaczonych `rare`. */
function weighted(items, luck = 1) {
  const weights = items.map((it) => (it.rare ? it.w * luck : it.w));
  const total = weights.reduce((a, b) => a + b, 0);
  let r = Math.random() * total;
  for (let i = 0; i < items.length; i++) {
    r -= weights[i];
    if (r < 0) return items[i];
  }
  return items[items.length - 1];
}

function duration(ms) {
  const s = Math.max(1, Math.ceil(ms / 1000));
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const parts = [];
  if (d) parts.push(`${d}d`);
  if (h) parts.push(`${h}h`);
  if (m) parts.push(`${m}m`);
  if (sec && !d) parts.push(`${sec}s`);
  return parts.join(' ');
}

/** Krótko do opisów komend: „10 min”, „30 s”, „1 min 30 s”. */
function shortDuration(ms) {
  const total = Math.round(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return [m && `${m} min`, s && `${s} s`].filter(Boolean).join(' ');
}

function bar(cur, max, len = 10) {
  const filled = Math.max(0, Math.min(len, Math.round((cur / max) * len)));
  return '🟩'.repeat(filled) + '⬜'.repeat(len - filled);
}

/**
 * Parsuje kwotę: 1500, 1 500, 1.5k, 2m, 50%, połowa, all/wszystko.
 * Zwraca liczbę całkowitą >= 1 albo null.
 */
function parseAmount(input, base = 0) {
  if (input === null || input === undefined) return null;
  const s = String(input).trim().toLowerCase().replace(/[\s_]/g, '').replace(',', '.');
  if (!s) return null;
  if (['all', 'allin', 'all-in', 'wszystko', 'max'].includes(s)) return base >= 1 ? Math.floor(base) : null;
  if (['half', 'polowa', 'połowa', 'pol', 'pół'].includes(s)) return base >= 2 ? Math.floor(base / 2) : null;
  let m = s.match(/^(\d+(?:\.\d+)?)%$/);
  if (m) {
    const pct = Number(m[1]);
    if (pct <= 0 || pct > 100) return null;
    const v = Math.floor((base * pct) / 100);
    return v >= 1 ? v : null;
  }
  m = s.match(/^(\d+(?:\.\d+)?)(k|tys|m|mln|b|mld)?$/);
  if (!m) return null;
  const mult = { k: 1e3, tys: 1e3, m: 1e6, mln: 1e6, b: 1e9, mld: 1e9 }[m[2]] || 1;
  const v = Math.floor(Number(m[1]) * mult);
  return Number.isFinite(v) && v >= 1 ? v : null;
}

function embed({ title, description, color = COLORS.info, footer, fields, thumbnail, author } = {}) {
  const e = new EmbedBuilder().setColor(color);
  if (title) e.setTitle(title);
  if (description) e.setDescription(description);
  if (footer) e.setFooter({ text: footer });
  if (fields) e.addFields(fields);
  if (thumbnail) e.setThumbnail(thumbnail);
  if (author) e.setAuthor(author);
  return e;
}

module.exports = {
  CURRENCY,
  COLORS,
  fmt,
  num,
  norm,
  sleep,
  randInt,
  chance,
  pick,
  shuffle,
  weighted,
  duration,
  shortDuration,
  bar,
  parseAmount,
  embed,
};
