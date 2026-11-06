const { ITEMS } = require('./data');
const { norm } = require('./util');

const label = (id) => {
  const it = ITEMS[id];
  return it ? `${it.emoji} ${it.name}` : id;
};

/** Za ile można sprzedać przedmiot (łup ma stałą cenę, reszta 50% ceny ze sklepu). */
const sellValue = (id) => {
  const it = ITEMS[id];
  if (!it) return 0;
  return it.sell ?? Math.floor((it.price ?? 0) * 0.5);
};

/** Najlepsze narzędzie z listy (od najlepszego do najgorszego), jakie gracz posiada. */
function bestTool(qtyOf, toolIds) {
  return toolIds.find((id) => qtyOf(id) > 0) ?? null;
}

/**
 * Znajduje przedmiot po tym, co wpisał gracz: id („super_wedka”), nazwa („Super wędka”, „superwedka”)
 * albo jednoznaczny początek („super”). Przeszukuje tylko `pool`. Zwraca id albo null.
 */
function resolveItem(input, pool = Object.keys(ITEMS)) {
  const k = norm(input ?? '');
  if (!k) return null;
  const exact = pool.find((id) => norm(id) === k || norm(ITEMS[id].name) === k);
  if (exact) return exact;
  const starts = pool.filter((id) => norm(id).startsWith(k) || norm(ITEMS[id].name).startsWith(k));
  return starts.length === 1 ? starts[0] : null;
}

/** Pozycje do autocomplete: pasujące do wpisanego tekstu, najwyżej 25 (limit Discorda). */
function suggestItems(input, pool) {
  const k = norm(input ?? '');
  return pool
    .filter((id) => !k || norm(id).includes(k) || norm(ITEMS[id].name).includes(k))
    .slice(0, 25)
    .map((id) => ({ name: `${ITEMS[id].emoji} ${ITEMS[id].name} – ${ITEMS[id].price} monet`.slice(0, 100), value: id }));
}

module.exports = { label, sellValue, bestTool, resolveItem, suggestItems };
