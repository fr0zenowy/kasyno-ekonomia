// Farma: uprawy rosną w czasie, a plon jest wart więcej niż nasiona. Czas liczymy ze znaczników (jak w biznesach).
const db = require('./db');
const { FARM_CROPS, FARM_SLOTS } = require('./data');

const find = (id) => FARM_CROPS.find((c) => c.id === id) ?? null;

/** Czas wzrostu w ms po uwzględnieniu podlewaczki. */
const growMs = (userId, crop) => Math.max(1000, Math.round(crop.minutes * 60_000 * (1 - db.effectSum(userId, 'farmSpeed'))));

/** Stan wszystkich pól: [{ slot, state: 'empty' | 'growing' | 'ready', crop?, left? }]. */
function status(userId, now = Date.now()) {
  const rows = new Map(db.getFarm(userId).map((r) => [r.slot, r]));
  return Array.from({ length: FARM_SLOTS }, (_, i) => {
    const slot = i + 1;
    const row = rows.get(slot);
    if (!row) return { slot, state: 'empty' };
    const crop = find(row.crop);
    return row.ready_at <= now ? { slot, state: 'ready', crop } : { slot, state: 'growing', crop, left: row.ready_at - now };
  });
}

/**
 * Sadzi uprawę: na wskazanym polu albo (gdy `slot` jest puste) na wszystkich wolnych polach, na ile starczy pieniędzy.
 * Zwraca { ok, planted, cost, ... } albo { ok: false, reason }.
 */
function plant(userId, cropId, slot = null, now = Date.now()) {
  const crop = find(cropId);
  if (!crop) return { ok: false, reason: 'unknown' };
  return db.tx(() => {
    const free = status(userId, now).filter((s) => s.state === 'empty').map((s) => s.slot);
    let targets;
    if (slot !== null) {
      if (slot < 1 || slot > FARM_SLOTS) return { ok: false, reason: 'slot' };
      if (!free.includes(slot)) return { ok: false, reason: 'busy' };
      targets = [slot];
    } else {
      if (!free.length) return { ok: false, reason: 'full' };
      targets = free;
    }
    const affordable = Math.min(targets.length, Math.floor(db.getUser(userId).wallet / crop.cost));
    if (affordable < 1) return { ok: false, reason: 'poor', cost: crop.cost, crop };
    const chosen = targets.slice(0, affordable);
    const cost = crop.cost * chosen.length;
    db.takeWallet(userId, cost);
    const ready = now + growMs(userId, crop);
    for (const s of chosen) db.setFarm(userId, s, crop.id, ready);
    return { ok: true, crop, planted: chosen.length, slots: chosen, cost, readyIn: ready - now, skipped: targets.length - chosen.length };
  });
}

/** Zbiera wszystkie dojrzałe uprawy i wypłaca plon. */
function harvest(userId, now = Date.now()) {
  return db.tx(() => {
    const ready = status(userId, now).filter((s) => s.state === 'ready');
    let gain = 0;
    const crops = {};
    for (const s of ready) {
      gain += s.crop.yield;
      crops[s.crop.id] = (crops[s.crop.id] ?? 0) + 1;
      db.clearFarmSlot(userId, s.slot);
    }
    if (gain > 0) db.addWallet(userId, gain);
    return { gain, crops, count: ready.length };
  });
}

module.exports = { find, growMs, status, plant, harvest };
