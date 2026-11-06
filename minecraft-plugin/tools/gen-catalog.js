// Generuje src/main/resources/catalog.json z katalogu bota (src/lib/*.js), żeby plugin miał dokładnie te same ceny,
// bonusy, tabele łupów i skrzyń. Uruchom po zmianie katalogu bota:
//   node --disable-warning=ExperimentalWarning minecraft-plugin/tools/gen-catalog.js
process.env.DB_PATH = ':memory:';
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const data = require(path.join(root, 'src/lib/data'));
const { CRATES } = require(path.join(root, 'src/lib/crates'));

// Emoji nie renderują się w czcionce Minecrafta, więc z tekstów je zdejmujemy.
const EMOJI = /[\u{1F000}-\u{1FFFF}\u{2190}-\u{21FF}\u{2300}-\u{23FF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{FE0F}\u{200D}\u{20E3}]/gu;
const clean = (s) =>
  String(s ?? '')
    .replace(EMOJI, '')
    .replace(/\*\*/g, '')
    .replace(/`/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim();

// Narzędzia i tabele łupów z komend zbierackich: przechwytujemy konfigurację przekazaną do gather().
const gatherPath = require.resolve(path.join(root, 'src/lib/gather'));
const captured = {};
require.cache[gatherPath] = { id: gatherPath, filename: gatherPath, loaded: true, exports: { gather: (_i, cfg) => (captured[cfg.key] = cfg) } };
for (const name of ['ryby', 'kopalnia', 'polowanie', 'las', 'smietnik']) require(path.join(root, 'src/commands', name)).execute({});

const gather = Object.values(captured).map((c) => ({
  key: c.key,
  cooldown: c.cooldown,
  missChance: typeof c.missChance === 'number' ? c.missChance : 0,
  tools: c.tools.map((t) => ({ id: t.id, luck: t.luck, miss: t.miss ?? (typeof c.missChance === 'function' ? c.missChance(t) : 0) })),
  table: c.table.map((e) => ({ id: e.id, w: e.w, qty: e.qty ?? [1, 1], rare: Boolean(e.rare) })),
}));

const items = Object.entries(data.ITEMS).map(([id, it]) => ({
  id,
  name: clean(it.name),
  type: it.type,
  price: it.price ?? 0,
  sell: it.sell ?? 0,
  desc: clean(it.desc ?? ''),
  unique: Boolean(it.unique),
  requires: it.requires ?? null,
}));

// Tabele gier (koło, zdrapka, wyścigi, plinko, sloty, poziomy kurczaka i wieży) – te same liczby co w bocie.
const cmd = (n) => require(path.join(root, 'src/commands', n));
const games = {
  kolo: cmd('kolo').SEGMENTS.map((x) => ({ label: clean(x.label), mult: x.mult, w: x.w })),
  zdrapka: cmd('zdrapka').OUTCOMES.map((x) => ({ mult: x.mult, w: x.w, sym: x.sym })),
  wyscigi: cmd('wyscigi').HORSES,
  plinko: { rows: cmd('plinko').ROWS, mults: cmd('plinko').MULTS },
  sloty: { symbols: cmd('sloty').SYMBOLS, jackpot: { chance: cmd('sloty').JACKPOT.chance, mult: cmd('sloty').JACKPOT.mult } },
  kurczak: Object.fromEntries(Object.entries(cmd('kurczak').LEVELS).map(([k, v]) => [k, { name: clean(v.name), d: v.d, max: v.max }])),
  wieza: Object.fromEntries(Object.entries(cmd('wieza').LEVELS).map(([k, v]) => [k, { name: clean(v.name), cols: v.cols, bad: v.bad }])),
};

const out = {
  games,
  items,
  categories: data.SHOP_CATEGORIES.map((c) => ({ id: c.id, title: clean(c.title), items: c.items })),
  effects: data.EFFECTS,
  boosts: Object.entries(data.BOOSTS).map(([id, b]) => ({ id, key: b.key, effect: b.effect, value: b.value, duration: b.duration, label: clean(b.label) })),
  protections: Object.entries(data.PROTECTIONS).map(([id, p]) => ({ id, kind: p.kind, cap: p.cap })),
  bonuses: data.BONUSES,
  vipCards: data.VIP_CARDS,
  businesses: data.BUSINESSES.map((b) => ({ id: b.id, name: clean(b.name), price: b.price, hourly: b.hourly })),
  businessMaxLevel: data.BUSINESS_MAX_LEVEL,
  storageHours: data.BUSINESS_STORAGE_HOURS,
  crops: data.FARM_CROPS.map((c) => ({ id: c.id, name: clean(c.name), cost: c.cost, yield: c.yield, minutes: c.minutes })),
  farmSlots: data.FARM_SLOTS,
  jobs: data.JOBS.map((j) => ({ id: j.id, name: clean(j.name), level: j.level, min: j.min, max: j.max, lines: j.lines.map(clean) })),
  crimes: data.CRIMES.map((c) => ({ win: clean(c.win), lose: clean(c.lose) })),
  trivia: data.TRIVIA.map((t) => ({ q: t.q, a: t.a, w: t.w })),
  bankUpgrades: data.BANK_UPGRADES.map((u) => ({ name: clean(u.name), cost: u.cost, bonus: u.bonus })),
  bank: { base: data.BANK_BASE, perLevel: data.BANK_PER_LEVEL, sejfBonus: data.SEJF_BONUS },
  startBalance: data.START_BALANCE,
  cooldowns: data.COOLDOWNS,
  gather,
  crates: Object.entries(CRATES).map(([id, c]) => ({
    id,
    loot: c.loot.map((e) => ({ kind: e.kind, item: e.item ?? null, qty: e.qty ?? 0, min: e.min ?? 0, max: e.max ?? 0, w: e.w, r: e.r })),
  })),
};

const file = path.join(root, 'minecraft-plugin', 'src', 'main', 'resources', 'catalog.json');
fs.mkdirSync(path.dirname(file), { recursive: true });
fs.writeFileSync(file, JSON.stringify(out, null, 1));
console.log(`catalog.json: ${items.length} przedmiotów, ${out.businesses.length} biznesów, ${out.crops.length} upraw, ${out.crates.length} skrzyń → ${path.relative(root, file)}`);
