const { DatabaseSync } = require('node:sqlite');
const path = require('node:path');
const fs = require('node:fs');
const { START_BALANCE, BOOSTS, EFFECTS, BANK_BASE, BANK_PER_LEVEL, SEJF_BONUS, BANK_UPGRADES } = require('./data');
const { fmt } = require('./util');

const dbPath = process.env.DB_PATH || path.join(__dirname, '..', '..', 'data', 'economy.db');
if (dbPath !== ':memory:') fs.mkdirSync(path.dirname(dbPath), { recursive: true });

const db = new DatabaseSync(dbPath);
db.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    wallet INTEGER NOT NULL DEFAULT 0,
    bank INTEGER NOT NULL DEFAULT 0,
    xp INTEGER NOT NULL DEFAULT 0,
    level INTEGER NOT NULL DEFAULT 1,
    streak INTEGER NOT NULL DEFAULT 0,
    last_daily INTEGER NOT NULL DEFAULT 0,
    last_weekly INTEGER NOT NULL DEFAULT 0,
    job TEXT NOT NULL DEFAULT 'sprzatacz',
    total_won INTEGER NOT NULL DEFAULT 0,
    total_lost INTEGER NOT NULL DEFAULT 0,
    games INTEGER NOT NULL DEFAULT 0,
    biggest_win INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS inventory (
    user_id TEXT NOT NULL,
    item TEXT NOT NULL,
    qty INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, item)
  );
  CREATE TABLE IF NOT EXISTS cooldowns (
    user_id TEXT NOT NULL,
    key TEXT NOT NULL,
    until INTEGER NOT NULL,
    PRIMARY KEY (user_id, key)
  );
  CREATE TABLE IF NOT EXISTS buffs (
    user_id TEXT NOT NULL,
    key TEXT NOT NULL,
    uses INTEGER NOT NULL,
    PRIMARY KEY (user_id, key)
  );
  CREATE TABLE IF NOT EXISTS boosts (
    user_id TEXT NOT NULL,
    key TEXT NOT NULL,
    until INTEGER NOT NULL,
    PRIMARY KEY (user_id, key)
  );
  CREATE TABLE IF NOT EXISTS businesses (
    user_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    level INTEGER NOT NULL DEFAULT 1,
    collected_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, kind)
  );
  CREATE TABLE IF NOT EXISTS farm (
    user_id TEXT NOT NULL,
    slot INTEGER NOT NULL,
    crop TEXT NOT NULL,
    ready_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, slot)
  );
  CREATE TABLE IF NOT EXISTS lottery (
    user_id TEXT PRIMARY KEY,
    tickets INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS kv (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
  );
`);

// Migracja: starsze bazy nie mają kolumny z poziomem ulepszenia banku.
if (!db.prepare('PRAGMA table_info(users)').all().some((c) => c.name === 'bank_tier')) {
  db.exec('ALTER TABLE users ADD COLUMN bank_tier INTEGER NOT NULL DEFAULT 0');
}

const q = {
  setBankTier: db.prepare('UPDATE users SET bank_tier = ? WHERE id = ? AND bank_tier = ?'),
  getUser: db.prepare('SELECT * FROM users WHERE id = ?'),
  insertUser: db.prepare('INSERT OR IGNORE INTO users (id, wallet, created_at) VALUES (?, ?, ?)'),
  addWallet: db.prepare('UPDATE users SET wallet = wallet + ? WHERE id = ?'),
  takeWallet: db.prepare('UPDATE users SET wallet = wallet - ? WHERE id = ? AND wallet >= ?'),
  setWallet: db.prepare('UPDATE users SET wallet = ? WHERE id = ?'),
  setBank: db.prepare('UPDATE users SET bank = ? WHERE id = ?'),
  moveToBank: db.prepare('UPDATE users SET wallet = wallet - ?, bank = bank + ? WHERE id = ? AND wallet >= ?'),
  moveToWallet: db.prepare('UPDATE users SET wallet = wallet + ?, bank = bank - ? WHERE id = ? AND bank >= ?'),
  setXp: db.prepare('UPDATE users SET xp = ?, level = ? WHERE id = ?'),
  setJob: db.prepare('UPDATE users SET job = ? WHERE id = ?'),
  setDaily: db.prepare('UPDATE users SET last_daily = ?, streak = ? WHERE id = ?'),
  setWeekly: db.prepare('UPDATE users SET last_weekly = ? WHERE id = ?'),
  recordGame: db.prepare(
    'UPDATE users SET games = games + 1, total_won = total_won + ?, total_lost = total_lost + ?, biggest_win = MAX(biggest_win, ?) WHERE id = ?',
  ),
  getItem: db.prepare('SELECT qty FROM inventory WHERE user_id = ? AND item = ?'),
  addItem: db.prepare(
    'INSERT INTO inventory (user_id, item, qty) VALUES (?, ?, ?) ON CONFLICT(user_id, item) DO UPDATE SET qty = qty + excluded.qty',
  ),
  takeItem: db.prepare('UPDATE inventory SET qty = qty - ? WHERE user_id = ? AND item = ? AND qty >= ?'),
  cleanItems: db.prepare('DELETE FROM inventory WHERE qty <= 0'),
  inventory: db.prepare('SELECT item, qty FROM inventory WHERE user_id = ? AND qty > 0 ORDER BY item'),
  getCd: db.prepare('SELECT until FROM cooldowns WHERE user_id = ? AND key = ?'),
  setCd: db.prepare(
    'INSERT INTO cooldowns (user_id, key, until) VALUES (?, ?, ?) ON CONFLICT(user_id, key) DO UPDATE SET until = excluded.until',
  ),
  clearCd: db.prepare('DELETE FROM cooldowns WHERE user_id = ? AND key = ?'),
  getBuff: db.prepare('SELECT uses FROM buffs WHERE user_id = ? AND key = ?'),
  addBuff: db.prepare(
    'INSERT INTO buffs (user_id, key, uses) VALUES (?, ?, ?) ON CONFLICT(user_id, key) DO UPDATE SET uses = uses + excluded.uses',
  ),
  useBuff: db.prepare('UPDATE buffs SET uses = uses - 1 WHERE user_id = ? AND key = ? AND uses > 0'),
  cleanBuffs: db.prepare('DELETE FROM buffs WHERE uses <= 0'),
  listBuffs: db.prepare('SELECT key, uses FROM buffs WHERE user_id = ? AND uses > 0 ORDER BY key'),
  getBusinesses: db.prepare('SELECT kind, level, collected_at FROM businesses WHERE user_id = ?'),
  setBusiness: db.prepare(
    'INSERT INTO businesses (user_id, kind, level, collected_at) VALUES (?, ?, ?, ?) ON CONFLICT(user_id, kind) DO UPDATE SET level = excluded.level, collected_at = excluded.collected_at',
  ),
  touchBusinesses: db.prepare('UPDATE businesses SET collected_at = ? WHERE user_id = ?'),
  getFarm: db.prepare('SELECT slot, crop, ready_at FROM farm WHERE user_id = ? ORDER BY slot'),
  setFarm: db.prepare(
    'INSERT INTO farm (user_id, slot, crop, ready_at) VALUES (?, ?, ?, ?) ON CONFLICT(user_id, slot) DO UPDATE SET crop = excluded.crop, ready_at = excluded.ready_at',
  ),
  clearFarmSlot: db.prepare('DELETE FROM farm WHERE user_id = ? AND slot = ?'),
  getBoost: db.prepare('SELECT until FROM boosts WHERE user_id = ? AND key = ?'),
  setBoost: db.prepare(
    'INSERT INTO boosts (user_id, key, until) VALUES (?, ?, ?) ON CONFLICT(user_id, key) DO UPDATE SET until = excluded.until',
  ),
  cleanBoosts: db.prepare('DELETE FROM boosts WHERE until <= ?'),
  topWealth: db.prepare('SELECT id, wallet, bank, level, total_won FROM users ORDER BY (wallet + bank) DESC LIMIT ?'),
  topLevel: db.prepare('SELECT id, wallet, bank, level, xp, total_won FROM users ORDER BY level DESC, xp DESC LIMIT ?'),
  topWon: db.prepare('SELECT id, wallet, bank, level, total_won FROM users ORDER BY total_won DESC LIMIT ?'),
  rank: db.prepare(
    'SELECT COUNT(*) + 1 AS rank FROM users WHERE (wallet + bank) > (SELECT wallet + bank FROM users WHERE id = ?)',
  ),
  userCount: db.prepare('SELECT COUNT(*) AS n FROM users'),
  lotteryGet: db.prepare('SELECT tickets FROM lottery WHERE user_id = ?'),
  lotteryAdd: db.prepare(
    'INSERT INTO lottery (user_id, tickets) VALUES (?, ?) ON CONFLICT(user_id) DO UPDATE SET tickets = tickets + excluded.tickets',
  ),
  lotteryAll: db.prepare('SELECT user_id, tickets FROM lottery'),
  lotteryClear: db.prepare('DELETE FROM lottery'),
  kvGet: db.prepare('SELECT value FROM kv WHERE key = ?'),
  kvSet: db.prepare('INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value'),
};

const int = (n) => Math.max(0, Math.floor(n));

/** Wykonuje blok w transakcji (kod jest synchroniczny, więc nic się nie przeplata). */
function tx(fn) {
  db.exec('BEGIN');
  try {
    const result = fn();
    db.exec('COMMIT');
    return result;
  } catch (err) {
    db.exec('ROLLBACK');
    throw err;
  }
}

// --- użytkownicy i pieniądze ---

function getUser(id) {
  let u = q.getUser.get(id);
  if (!u) {
    q.insertUser.run(id, START_BALANCE, Date.now());
    u = q.getUser.get(id);
  }
  return u;
}

function addWallet(id, amount) {
  getUser(id);
  q.addWallet.run(Math.floor(amount), id);
}

/** Zabiera pieniądze z portfela, jeśli są. Zwraca true/false. */
function takeWallet(id, amount) {
  amount = int(amount);
  getUser(id);
  return q.takeWallet.run(amount, id, amount).changes === 1;
}

function setWallet(id, amount) {
  getUser(id);
  q.setWallet.run(int(amount), id);
}

function setBank(id, amount) {
  getUser(id);
  q.setBank.run(int(amount), id);
}

function transfer(fromId, toId, amount) {
  amount = int(amount);
  getUser(toId);
  return tx(() => {
    if (!takeWallet(fromId, amount)) return false;
    addWallet(toId, amount);
    return true;
  });
}

/** Z czego składa się limit banku. */
function bankBreakdown(user) {
  const base = BANK_BASE;
  const levels = (user.level - 1) * BANK_PER_LEVEL;
  const upgrades = BANK_UPGRADES.slice(0, user.bank_tier).reduce((s, u) => s + u.bonus, 0);
  const sejfs = itemQty(user.id, 'sejf');
  const safes = sejfs * SEJF_BONUS;
  return { base, levels, upgrades, tier: user.bank_tier, sejfs, safes, total: base + levels + upgrades + safes };
}

const bankCap = (user) => bankBreakdown(user).total;

/**
 * Kupuje kolejne ulepszenie banku z portfela. Zwraca { ok: true, upgrade } albo { ok: false, reason: 'max' | 'poor', upgrade? }.
 * Zakup (pobranie pieniędzy i podniesienie poziomu) jest atomowy.
 */
function buyBankUpgrade(id) {
  return tx(() => {
    const u = getUser(id);
    const upgrade = BANK_UPGRADES[u.bank_tier];
    if (!upgrade) return { ok: false, reason: 'max' };
    if (!takeWallet(id, upgrade.cost)) return { ok: false, reason: 'poor', upgrade };
    if (q.setBankTier.run(u.bank_tier + 1, id, u.bank_tier).changes !== 1) {
      addWallet(id, upgrade.cost);
      return { ok: false, reason: 'poor', upgrade };
    }
    return { ok: true, upgrade };
  });
}

function deposit(id, amount) {
  const u = getUser(id);
  const room = Math.max(0, bankCap(u) - u.bank);
  const amt = Math.min(int(amount), u.wallet, room);
  if (amt < 1) return 0;
  return q.moveToBank.run(amt, amt, id, amt).changes === 1 ? amt : 0;
}

function withdraw(id, amount) {
  const u = getUser(id);
  const amt = Math.min(int(amount), u.bank);
  if (amt < 1) return 0;
  return q.moveToWallet.run(amt, amt, id, amt).changes === 1 ? amt : 0;
}

// --- poziomy ---

const xpNeeded = (level) => 100 + (level - 1) * 75;

/** Mnożnik XP: stałe bonusy (dyplom, doktorat, sowa...) razy najlepszy aktywny eliksir. */
function xpMultiplier(id) {
  return (1 + effectSum(id, 'xp')) * boostValue(id, 'xp');
}

/**
 * Dodaje XP. Domyślnie z mnożnikami; `{ boost: false }` pomija je (np. XP wylosowane ze skrzyni jest już wycenione w tabeli).
 */
function addXp(id, amount, { boost = true } = {}) {
  const u = getUser(id);
  let xp = u.xp + Math.round(int(amount) * (boost ? xpMultiplier(id) : 1));
  let level = u.level;
  let ups = 0;
  let bonus = 0;
  let crates = 0; // co 5. poziom dorzuca skrzynię skarbów
  while (xp >= xpNeeded(level)) {
    xp -= xpNeeded(level);
    level += 1;
    ups += 1;
    bonus += level * 200;
    if (level % 5 === 0) crates += 1;
  }
  q.setXp.run(xp, level, id);
  if (bonus) addWallet(id, bonus);
  if (crates) addItem(id, 'skrzynia', crates);
  return {
    level,
    ups,
    bonus,
    crates,
    note: ups
      ? `\n🎉 **Awans na poziom ${level}!** Bonus: ${fmt(bonus)}${crates ? `\n🎁 Za okrągły poziom dostajesz też ${crates > 1 ? `${crates}× ` : ''}skrzynię skarbów!` : ''}`
      : '',
  };
}

// --- ekwipunek ---

function itemQty(id, item) {
  const r = q.getItem.get(id, item);
  return r ? r.qty : 0;
}

function addItem(id, item, qty = 1) {
  getUser(id);
  q.addItem.run(id, item, int(qty));
}

function takeItem(id, item, qty = 1) {
  qty = int(qty);
  if (qty < 1) return false;
  const ok = q.takeItem.run(qty, id, item, qty).changes === 1;
  if (ok) q.cleanItems.run();
  return ok;
}

function inventory(id) {
  return q.inventory.all(id);
}

// --- cooldowny ---

function cooldownLeft(id, key) {
  const r = q.getCd.get(id, key);
  if (!r) return 0;
  return Math.max(0, r.until - Date.now());
}

function setCooldown(id, key, ms) {
  q.setCd.run(id, key, Date.now() + ms);
}

function clearCooldown(id, key) {
  q.clearCd.run(id, key);
}

/** Cooldown skrócony o aktywny przyspieszacz. Do nowych cooldownów gracza, nie do ochrony ofiary rabunku. */
function scaledCooldown(id, ms) {
  return Math.round(ms * boostValue(id, 'cooldown'));
}

/** Suma stałych bonusów danego rodzaju z przedmiotów, które gracz ma (tabela EFFECTS w katalogu). */
function effectSum(id, effect) {
  let sum = 0;
  for (const [item, effects] of Object.entries(EFFECTS)) {
    if (effects[effect] && itemQty(id, item) > 0) sum += effects[effect];
  }
  return sum;
}

/**
 * Wartość czasowego mnożnika danego rodzaju: najlepszy z aktywnych eliksirów (dla cooldownu: najkrótszy czas).
 * Bez aktywnego eliksiru zwraca 1.
 */
function boostValue(id, effect) {
  let best = 1;
  for (const b of Object.values(BOOSTS)) {
    if (b.effect !== effect || boostLeft(id, b.key) <= 0) continue;
    best = effect === 'cooldown' ? Math.min(best, b.value) : Math.max(best, b.value);
  }
  return best;
}

// --- czasowe mnożniki (eliksiry) ---

/** Ile milisekund zostało do końca mnożnika (0 = nieaktywny). */
function boostLeft(id, key) {
  const r = q.getBoost.get(id, key);
  return r ? Math.max(0, r.until - Date.now()) : 0;
}

/** Włącza mnożnik albo przedłuża aktywny. Zwraca, ile czasu zostało po aktywacji, i czy przedłużono. */
function activateBoost(id, key, ms) {
  getUser(id);
  const now = Date.now();
  const left = boostLeft(id, key);
  q.setBoost.run(id, key, now + left + ms);
  q.cleanBoosts.run(now);
  return { left: left + ms, extended: left > 0 };
}

// --- buffy (np. koniczyna) ---

function buffUses(id, key) {
  const r = q.getBuff.get(id, key);
  return r ? r.uses : 0;
}

function addBuff(id, key, uses) {
  q.addBuff.run(id, key, uses);
}

function useBuff(id, key) {
  const ok = q.useBuff.run(id, key).changes === 1;
  if (ok) q.cleanBuffs.run();
  return ok;
}

/** Wszystkie aktywne buffy gracza: [{ key, uses }]. */
function listBuffs(id) {
  return q.listBuffs.all(id);
}

// --- biznesy i farma (logika w /biznes i /farma, tu tylko zapis) ---

function getBusinesses(id) {
  return q.getBusinesses.all(id);
}

function setBusiness(id, kind, level, collectedAt) {
  getUser(id);
  q.setBusiness.run(id, kind, level, collectedAt);
}

function touchBusinesses(id, ts) {
  q.touchBusinesses.run(ts, id);
}

function getFarm(id) {
  return q.getFarm.all(id);
}

function setFarm(id, slot, crop, readyAt) {
  getUser(id);
  q.setFarm.run(id, slot, crop, readyAt);
}

function clearFarmSlot(id, slot) {
  q.clearFarmSlot.run(id, slot);
}

// --- statystyki i rankingi ---

function recordGame(id, net) {
  q.recordGame.run(net > 0 ? net : 0, net < 0 ? -net : 0, net > 0 ? net : 0, id);
}

function top(by = 'majatek', limit = 10) {
  const stmt = by === 'poziom' ? q.topLevel : by === 'hazard' ? q.topWon : q.topWealth;
  return stmt.all(limit);
}

function rankOf(id) {
  getUser(id);
  return q.rank.get(id).rank;
}

function userCount() {
  return q.userCount.get().n;
}

// --- loteria i kv ---

function kvGet(key, fallback = null) {
  const r = q.kvGet.get(key);
  return r ? r.value : fallback;
}

function kvSet(key, value) {
  q.kvSet.run(key, String(value));
}

function lotteryTickets(id) {
  const r = q.lotteryGet.get(id);
  return r ? r.tickets : 0;
}

function lotteryAdd(id, n) {
  q.lotteryAdd.run(id, int(n));
}

function lotteryAll() {
  return q.lotteryAll.all();
}

function lotteryClear() {
  q.lotteryClear.run();
}

module.exports = {
  db,
  tx,
  getUser,
  addWallet,
  takeWallet,
  setWallet,
  setBank,
  setJob: (id, job) => {
    getUser(id);
    q.setJob.run(job, id);
  },
  setDaily: (id, ts, streak) => q.setDaily.run(ts, streak, id),
  setWeekly: (id, ts) => q.setWeekly.run(ts, id),
  transfer,
  bankCap,
  bankBreakdown,
  buyBankUpgrade,
  deposit,
  withdraw,
  xpNeeded,
  xpMultiplier,
  addXp,
  itemQty,
  addItem,
  takeItem,
  inventory,
  cooldownLeft,
  setCooldown,
  clearCooldown,
  scaledCooldown,
  effectSum,
  boostValue,
  boostLeft,
  activateBoost,
  listBuffs,
  getBusinesses,
  setBusiness,
  touchBusinesses,
  getFarm,
  setFarm,
  clearFarmSlot,
  buffUses,
  addBuff,
  useBuff,
  recordGame,
  top,
  rankOf,
  userCount,
  kvGet,
  kvSet,
  lotteryTickets,
  lotteryAdd,
  lotteryAll,
  lotteryClear,
};
