// Ochrona w grach: polisy zwracają stawkę po całkowitej przegranej, tarcze anulują pierwszy zły klik w grach krokowych.
// Ochrony uzbraja /uzyj (buff o kluczu = id przedmiotu) i zużywają się dopiero, gdy faktycznie zadziałają.
// Sufity (`cap` w katalogu) są dobrane do cen, więc nie da się na nich zarabiać na hazardzie.
const db = require('./db');
const { PROTECTIONS, ITEMS } = require('./data');

/** Uzbrojone ochrony danego rodzaju, które pokrywają stawkę; od najtańszej (najmniejszy sufit). */
function armed(userId, kind, stake) {
  return Object.entries(PROTECTIONS)
    .filter(([id, p]) => p.kind === kind && p.cap >= stake && db.buffUses(userId, id) > 0)
    .sort((a, b) => a[1].cap - b[1].cap)
    .map(([id, p]) => ({ id, ...p, name: ITEMS[id].name, emoji: ITEMS[id].emoji }));
}

/** Zużywa jedną, najtańszą wystarczającą ochronę. Zwraca ją albo null, gdy nic nie pasuje do stawki. */
function consume(userId, kind, stake) {
  const [p] = armed(userId, kind, stake);
  return p && db.useBuff(userId, p.id) ? p : null;
}

/** Uzbraja ochronę z przedmiotu i zwraca, ile takich sztuk jest teraz uzbrojonych. */
function arm(userId, itemId) {
  db.addBuff(userId, itemId, 1);
  return db.buffUses(userId, itemId);
}

/** Linijka do pokazania w grze: co aktywnie chroni tę stawkę (albo pusty tekst). */
function line(userId, stake) {
  const parts = [];
  const polisa = armed(userId, 'polisa', stake)[0];
  const tarcza = armed(userId, 'tarcza', stake)[0];
  if (polisa) parts.push(`${polisa.emoji} ${polisa.name} (zwrot stawki po przegranej)`);
  if (tarcza) parts.push(`${tarcza.emoji} ${tarcza.name} (pierwszy błąd się nie liczy)`);
  return parts.length ? `\n${parts.join('\n')}` : '';
}

module.exports = { armed, consume, arm, line };
