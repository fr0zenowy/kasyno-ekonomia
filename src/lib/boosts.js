// Mnożniki z przedmiotów: karty VIP, zwierzaki, eliksiry itd. Jedno miejsce, z którego komendy czytają, ile komu się należy.
// Liczby pochodzą z tabel w katalogu (BONUSES, EFFECTS, BOOSTS), a sumowanie robi db.effectSum / db.boostValue.
const db = require('./db');
const { BONUSES, BOOSTS, VIP_CARDS, EFFECTS, ITEMS, PROTECTIONS } = require('./data');
const { shortDuration, fmt } = require('./util');

const percent = (x) => `${Math.round(x * 100)}%`;
const name = (id) => `${ITEMS[id].emoji} ${ITEMS[id].name}`;

/** Najlepsza karta VIP, jaką ma gracz (albo null). */
const vipCard = (userId) => VIP_CARDS.find((id) => db.itemQty(userId, id) > 0) ?? null;

/** Który eliksir danego rodzaju działa teraz najmocniej (albo null). */
function activeBoost(userId, effect) {
  let best = null;
  for (const [itemId, b] of Object.entries(BOOSTS)) {
    if (b.effect !== effect || db.boostLeft(userId, b.key) <= 0) continue;
    if (!best || (effect === 'cooldown' ? b.value < best.value : b.value > best.value)) best = { itemId, ...b };
  }
  return best;
}

/**
 * Przelicza zarobek z pracy, żebrania, quizów i przestępstw: (1 + karta VIP + zwierzaki) × eliksir.
 * Zwraca { amount, mult, notes } – notatki do pokazania graczowi.
 */
function applyEarn(userId, base) {
  let bonus = 0;
  const notes = [];
  const card = vipCard(userId);
  if (card) {
    bonus += BONUSES.vip[card];
    notes.push(`${name(card)}: +${percent(BONUSES.vip[card])}`);
  }
  const perks = db.effectSum(userId, 'earn');
  if (perks > 0) {
    bonus += perks;
    notes.push(`🐾 Zwierzaki: +${percent(perks)}`);
  }
  const timed = activeBoost(userId, 'earn');
  if (timed) notes.push(`${name(timed.itemId)}: ×${timed.value}`);
  const mult = (1 + bonus) * (timed ? timed.value : 1);
  return { amount: Math.floor(base * mult), mult, notes };
}

/** Przedmioty podnoszące wypłatę z /praca, które gracz ma (laptop, garnitur, samochód): [{ id, bonus }]. */
const pracaPerks = (userId) =>
  Object.entries(EFFECTS)
    .filter(([id, e]) => e.praca && db.itemQty(userId, id) > 0)
    .map(([id, e]) => ({ id, bonus: e.praca }));

/** Ile razy więcej łupu z ryb, kopalni, polowania i lasu (magnes). */
const lootMultiplier = (userId) => db.boostValue(userId, 'loot');

/** Mnożnik szansy na rzadkie łupy: stałe bonusy (talizman, kot...) razy eliksir szczęścia. */
const luckMultiplier = (userId) => (1 + db.effectSum(userId, 'luck')) * db.boostValue(userId, 'luck');

/** Szansa na zepsucie narzędzia przy użyciu (pas narzędziowy ją obniża). */
const breakChance = (userId) => (db.itemQty(userId, 'pas_narzedziowy') > 0 ? BONUSES.breakChanceBelt : BONUSES.breakChance);

/** Dzienne odsetki z banku: stawka i limit. Działa najlepsze konto, jakie gracz ma. */
function interestTerms(userId) {
  for (const id of ['konto_premium', 'konto_oszczednosciowe']) {
    if (db.itemQty(userId, id) > 0) return { ...BONUSES.interest[id], savings: true, account: id };
  }
  return { ...BONUSES.interest.base, savings: false, account: null };
}

/** Zużywa eliksir (sprawdzanie ekwipunku robi wywołujący) i włącza albo przedłuża mnożnik. */
function activate(userId, itemId) {
  const boost = BOOSTS[itemId];
  return { ...boost, ...db.activateBoost(userId, boost.key, boost.duration) };
}

const EFFECT_TEXT = {
  praca: (v) => `+${percent(v)} z /praca`,
  earn: (v) => `+${percent(v)} zarobków`,
  xp: (v) => `+${percent(v)} XP`,
  luck: (v) => `+${percent(v)} szans na rzadkie łupy`,
  robDefense: (v) => `złodziejowi spada szansa o ${percent(v)}`,
  bizIncome: (v) => `+${percent(v)} dochodu biznesów`,
  bizStorage: (v) => `+${v} h magazynu biznesów`,
  farmSpeed: (v) => `uprawy rosną o ${percent(v)} szybciej`,
};

/** Czytelne podsumowanie wszystkich aktywnych i stałych mnożników gracza (do /mnozniki). */
function summary(userId) {
  const totals = {
    earn: applyEarn(userId, 1).mult,
    xp: db.xpMultiplier(userId),
    loot: lootMultiplier(userId),
    luck: luckMultiplier(userId),
    cooldown: db.scaledCooldown(userId, 100) / 100,
  };

  const permanent = [];
  const card = vipCard(userId);
  if (card) permanent.push(`${name(card)}: +${percent(BONUSES.vip[card])} zarobków`);
  for (const [item, effects] of Object.entries(EFFECTS)) {
    if (db.itemQty(userId, item) < 1) continue;
    permanent.push(`${name(item)}: ${Object.entries(effects).map(([k, v]) => EFFECT_TEXT[k](v)).join(', ')}`);
  }
  if (db.itemQty(userId, 'pas_narzedziowy') > 0) permanent.push(`${name('pas_narzedziowy')}: narzędzia psują się ${percent(BONUSES.breakChanceBelt)} zamiast ${percent(BONUSES.breakChance)}`);
  const terms = interestTerms(userId);
  if (terms.savings) permanent.push(`${name(terms.account)}: odsetki ${percent(terms.rate)} (do ${fmt(terms.cap)})`);

  const active = [];
  for (const [itemId, b] of Object.entries(BOOSTS)) {
    const left = db.boostLeft(userId, b.key);
    if (left > 0) active.push(`${name(itemId)}: ${b.label}, jeszcze ${shortDuration(left)}`);
  }
  const clover = db.buffUses(userId, 'koniczyna');
  if (clover) active.push(`${name('koniczyna')}: jeszcze ${clover} wygranych z bonusem`);

  const protections = db
    .listBuffs(userId)
    .filter((b) => PROTECTIONS[b.key])
    .map((b) => `${name(b.key)} ×${b.uses} (${PROTECTIONS[b.key].kind === 'polisa' ? 'zwrot stawki do' : 'chroni stawki do'} ${fmt(PROTECTIONS[b.key].cap)})`);

  return { totals, permanent, active, protections };
}

module.exports = {
  vipCard,
  activeBoost,
  applyEarn,
  pracaPerks,
  lootMultiplier,
  luckMultiplier,
  breakChance,
  interestTerms,
  activate,
  summary,
};
