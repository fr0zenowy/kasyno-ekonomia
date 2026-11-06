// Biznesy: dochód pasywny naliczany od ostatniego zbioru (aż do limitu magazynu). Czas liczymy z zapisanych znaczników,
// więc nic nie musi działać w tle, a zarobki rosną także wtedy, gdy bot jest wyłączony.
const db = require('./db');
const { BUSINESSES, BUSINESS_MAX_LEVEL, BUSINESS_STORAGE_HOURS } = require('./data');

const HOUR = 3600 * 1000;

const find = (id) => BUSINESSES.find((b) => b.id === id) ?? null;
const hourlyAt = (biz, level) => biz.hourly * level;
/** Ulepszenie z poziomu L na L+1 kosztuje cena × L × 1.5. */
const upgradeCost = (biz, level) => Math.floor(biz.price * level * 1.5);
const storageHours = (userId) => BUSINESS_STORAGE_HOURS + db.effectSum(userId, 'bizStorage');
const incomeMult = (userId) => 1 + db.effectSum(userId, 'bizIncome');

/** Ile każdy biznes zarobił od ostatniego zbioru (z limitem magazynu). */
function pending(userId, now = Date.now()) {
  const cap = storageHours(userId);
  const mult = incomeMult(userId);
  const parts = [];
  for (const row of db.getBusinesses(userId)) {
    const biz = find(row.kind);
    if (!biz) continue;
    const hours = Math.min(cap, Math.max(0, (now - row.collected_at) / HOUR));
    parts.push({ biz, level: row.level, hours, amount: Math.floor(hourlyAt(biz, row.level) * mult * hours), full: hours >= cap });
  }
  return { parts, total: parts.reduce((s, p) => s + p.amount, 0), cap, mult };
}

/** Wypłaca zaległy dochód wszystkich biznesów i zeruje liczniki. */
function collect(userId, now = Date.now()) {
  return db.tx(() => {
    const p = pending(userId, now);
    if (p.total > 0) db.addWallet(userId, p.total);
    db.touchBusinesses(userId, now);
    return p;
  });
}

/**
 * Kupuje biznes albo ulepsza go o poziom. Przy ulepszeniu najpierw wypłaca zaległy dochód tego biznesu po starej stawce,
 * żeby nowy poziom nie liczył się wstecz. Zwraca { ok, ... }.
 */
function buyOrUpgrade(userId, bizId, now = Date.now()) {
  const biz = find(bizId);
  if (!biz) return { ok: false, reason: 'unknown' };
  return db.tx(() => {
    const row = db.getBusinesses(userId).find((r) => r.kind === bizId);
    if (!row) {
      if (!db.takeWallet(userId, biz.price)) return { ok: false, reason: 'poor', cost: biz.price, biz };
      db.setBusiness(userId, bizId, 1, now);
      return { ok: true, bought: true, cost: biz.price, level: 1, collected: 0, biz };
    }
    if (row.level >= BUSINESS_MAX_LEVEL) return { ok: false, reason: 'max', biz };
    const cost = upgradeCost(biz, row.level);
    if (!db.takeWallet(userId, cost)) return { ok: false, reason: 'poor', cost, biz };
    const hours = Math.min(storageHours(userId), Math.max(0, (now - row.collected_at) / HOUR));
    const collected = Math.floor(hourlyAt(biz, row.level) * incomeMult(userId) * hours);
    if (collected > 0) db.addWallet(userId, collected);
    db.setBusiness(userId, bizId, row.level + 1, now);
    return { ok: true, bought: false, cost, level: row.level + 1, collected, biz };
  });
}

/** Dochód na godzinę wszystkich biznesów gracza (z mnożnikiem menedżera). */
function hourlyTotal(userId) {
  const mult = incomeMult(userId);
  return Math.floor(db.getBusinesses(userId).reduce((s, r) => s + hourlyAt(find(r.kind) ?? { hourly: 0 }, r.level), 0) * mult);
}

module.exports = { find, hourlyAt, upgradeCost, storageHours, incomeMult, pending, collect, buyOrUpgrade, hourlyTotal, HOUR };
