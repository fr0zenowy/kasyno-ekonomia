// Stan gracza i dane statyczne (katalog) dla przeglądarki. Liczy je z tych samych modułów co komendy bota.
const db = require('../lib/db');
const data = require('../lib/data');
const boosts = require('../lib/boosts');
const biz = require('../lib/business');
const farm = require('../lib/farm');
const crates = require('../lib/crates');
const lottery = require('../lib/lottery');
const accounts = require('./accounts');

const DAILY_MS = 20 * 3600 * 1000;
const WEEK_MS = 7 * 24 * 3600 * 1000;

/** Cały stan gracza potrzebny interfejsowi: saldo, poziom, ekwipunek, cooldowny, biznesy, farma... */
function buildMe(userId) {
  const u = db.getUser(userId);
  const now = Date.now();
  const b = db.bankBreakdown(u);

  const cooldowns = {};
  for (const key of Object.keys(data.COOLDOWNS)) {
    const left = db.cooldownLeft(userId, key);
    if (left > 0) cooldowns[key] = left;
  }
  const heist = db.cooldownLeft(userId, 'heist');
  if (heist > 0) cooldowns.heist = heist;
  const summary = boosts.summary(userId);

  const pending = biz.pending(userId, now);
  return {
    id: userId,
    name: accounts.displayName(userId),
    wallet: u.wallet,
    bank: u.bank,
    level: u.level,
    xp: u.xp,
    xpNeeded: db.xpNeeded(u.level),
    streak: u.streak,
    job: u.job,
    rank: db.rankOf(userId),
    bankInfo: { cap: b.total, tier: b.tier, base: b.base, levels: b.levels, upgrades: b.upgrades, sejfs: b.sejfs, safes: b.safes },
    stats: { games: u.games, won: u.total_won, lost: u.total_lost, biggest: u.biggest_win },
    inventory: db.inventory(userId).filter((r) => data.ITEMS[r.item]),
    cooldowns,
    daily: Math.max(0, u.last_daily + DAILY_MS - now),
    weekly: Math.max(0, u.last_weekly + WEEK_MS - now),
    boosts: Object.entries(data.BOOSTS)
      .map(([item, bo]) => ({ item, label: bo.label, left: db.boostLeft(userId, bo.key) }))
      .filter((x) => x.left > 0),
    protections: db.listBuffs(userId).filter((x) => data.PROTECTIONS[x.key]).map((x) => ({ item: x.key, uses: x.uses })),
    clover: db.buffUses(userId, 'koniczyna'),
    totals: summary.totals,
    permanent: summary.permanent,
    businesses: {
      owned: pending.parts.map((p) => ({ id: p.biz.id, level: p.level, hours: p.hours, amount: p.amount, full: p.full })),
      pending: pending.total,
      cap: pending.cap,
      hourly: biz.hourlyTotal(userId),
    },
    farm: farm.status(userId, now).map((s) => ({ slot: s.slot, state: s.state, crop: s.crop?.id ?? null, left: s.left ?? 0 })),
    lottery: { tickets: db.lotteryTickets(userId), ...lottery.poolInfo(), next: lottery.nextDraw(), last: lottery.lastWinner() },
    now,
  };
}

/** Publiczny profil dowolnego gracza (bez ekwipunku i cooldownów). */
function buildProfile(userId) {
  const acc = accounts.byId(userId);
  if (!acc) return null;
  const u = db.getUser(userId);
  const owned = db.inventory(userId).filter((r) => data.ITEMS[r.item]);
  const petIds = data.SHOP_CATEGORIES.find((c) => c.id === 'zwierzaki').items;
  return {
    id: userId,
    name: acc.username,
    since: acc.created,
    level: u.level,
    xp: u.xp,
    xpNeeded: db.xpNeeded(u.level),
    wealth: u.wallet + u.bank,
    rank: db.rankOf(userId),
    job: u.job,
    streak: u.streak,
    stats: { games: u.games, won: u.total_won, lost: u.total_lost, biggest: u.biggest_win },
    trophy: db.itemQty(userId, 'trofeum') > 0,
    luxury: owned.filter((r) => data.ITEMS[r.item].type === 'luxury').map((r) => r.item),
    pets: owned.filter((r) => petIds.includes(r.item)).map((r) => r.item),
    businesses: db.getBusinesses(userId).length,
    hourly: biz.hourlyTotal(userId),
  };
}

function leaderboard(by = 'majatek', limit = 20) {
  const rows = db.top(by, limit);
  return rows.map((r, i) => ({
    place: i + 1,
    id: r.id,
    name: accounts.displayName(r.id),
    level: r.level,
    wealth: r.wallet + r.bank,
    won: r.total_won,
  }));
}

let catalogCache = null;

/** Dane, które się nie zmieniają w trakcie działania: przedmioty, sklep, biznesy, stałe gier. */
function buildCatalog(runner, commands) {
  if (catalogCache) return catalogCache;
  const games = {};
  const g = (name) => commands.get(name) ?? {};
  games.moneta = { payout: g('moneta').PAYOUT };
  games.kosci = Object.fromEntries(Object.entries(g('kosci').BETS ?? {}).map(([k, v]) => [k, { label: v.label, mult: v.mult }]));
  games.ruletka = {
    red: [...(g('ruletka').RED ?? [])],
    types: Object.fromEntries(Object.entries(g('ruletka').TYPES ?? {}).map(([k, v]) => [k, { label: v.label, mult: v.mult }])),
  };
  games.kolo = { segments: g('kolo').SEGMENTS };
  games.zdrapka = { outcomes: g('zdrapka').OUTCOMES };
  games.sloty = { symbols: g('sloty').SYMBOLS, jackpot: g('sloty').JACKPOT };
  games.wyscigi = { horses: g('wyscigi').HORSES };
  games.plinko = { rows: g('plinko').ROWS, mults: g('plinko').MULTS };
  games.kurczak = { levels: g('kurczak').LEVELS };
  games.wieza = { levels: g('wieza').LEVELS };
  games.limbo = { min: g('limbo').MIN_TARGET, max: g('limbo').MAX_TARGET };
  games.szansa = { min: g('szansa').MIN_CHANCE, max: g('szansa').MAX_CHANCE };
  games.rewolwer = { mults: [1, 2, 3, 4, 5].map((n) => g('rewolwer').multiplierFor(n)) };
  games.crash = { max: g('crash').MAX_CRASH };

  catalogCache = {
    items: data.ITEMS,
    categories: data.SHOP_CATEGORIES,
    shopOrder: data.SHOP_ORDER,
    bankUpgrades: data.BANK_UPGRADES,
    businesses: data.BUSINESSES,
    businessMaxLevel: data.BUSINESS_MAX_LEVEL,
    farmSlots: data.FARM_SLOTS,
    crops: data.FARM_CROPS,
    jobs: data.JOBS.map(({ id, name, emoji, level, min, max }) => ({ id, name, emoji, level, min, max })),
    effects: data.EFFECTS,
    boosts: data.BOOSTS,
    protections: data.PROTECTIONS,
    crates: Object.fromEntries(
      Object.keys(crates.CRATES).map((id) => [id, { odds: crates.rarityOdds(id), ev: Math.round(crates.expectedValue(id)) }]),
    ),
    cooldowns: data.COOLDOWNS,
    startBalance: data.START_BALANCE,
    lottery: { ticketPrice: lottery.TICKET_PRICE, maxTickets: lottery.MAX_TICKETS_PER_USER },
    games,
    commands: runner.describe(),
  };
  return catalogCache;
}

module.exports = { buildMe, buildProfile, buildCatalog, leaderboard };
