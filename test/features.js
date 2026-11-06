// Testy nowej mechaniki: katalog przedmiotów, polisy i tarcze, gry krokowe, plinko/limbo/szansa/rewolwer/jackpot,
// biznesy, farma, darmowe zarobki, efekty przedmiotów. Wywoływane z smoke.js, który dostarcza atrapy Discorda.
// Szybki przebieg samych tych testów: ONLY_FEATURES=1 node --disable-warning=ExperimentalWarning test/smoke.js
const data = require('../src/lib/data');
const crates = require('../src/lib/crates');
const boosts = require('../src/lib/boosts');
const biz = require('../src/lib/business');
const farm = require('../src/lib/farm');
const { handleMessage } = require('../src/prefix');
const { shuffle } = require('../src/lib/util');
const { stepMultiplier, stepsLimit } = require('../src/lib/stepgame');

// --- sterowanie losowością --------------------------------------------------------------------------------------
// Liczba albo tablica podmienia tylko kilka pierwszych losowań (potem wraca prawdziwy Math.random), więc pętle typu
// „losuj, aż się uda” nie zawisną. Funkcja przejmuje wszystkie losowania. withConst zwraca zawsze tę samą wartość
// (tylko tam, gdzie wiadomo, że nic na tym nie zapętli się).
const realRandom = Math.random;
const stubFor = (seq) => {
  if (typeof seq === 'function') return seq;
  const list = Array.isArray(seq) ? [...seq] : [seq];
  return () => (list.length ? list.shift() : realRandom());
};
async function withRandom(seq, fn) {
  Math.random = stubFor(seq);
  try {
    return await fn();
  } finally {
    Math.random = realRandom;
  }
}
const withConst = (value, fn) => withRandom(() => value, fn);
const constSync = (value, fn) => {
  Math.random = () => value;
  try {
    return fn();
  } finally {
    Math.random = realRandom;
  }
};

const closeTo = (a, b, eps = 1e-9) => Math.abs(a - b) < eps;
const choose = (n, k) => {
  let r = 1;
  for (let i = 1; i <= k; i++) r = (r * (n - k + i)) / i;
  return r;
};
const desc = (p) => p?.embeds?.[0]?.toJSON().description ?? p?.content ?? '';
const lastDesc = (i) => desc(i.edits.length ? i.edits[i.edits.length - 1] : i.replies[0]);
const buttonsOf = (p) => p.components.flatMap((row) => row.toJSON().components);

module.exports = async function features(h) {
  const { commands, check, section, makeUser, makeInteraction, run, ephemeral, ledger, resetLedger, rich, db, game, ITEMS, FakeCollector, fakeClient } = h;
  const fresh = (name, wallet = 0) => {
    const u = makeUser(`f_${name}`);
    db.setWallet(u.id, wallet);
    return u;
  };
  const wallet = (u) => db.getUser(u.id).wallet;

  // ------------------------------------------------------------------ katalog
  section('Nowy katalog: spójność przedmiotów, efektów, ochron, biznesów i farmy');
  {
    const TYPES = new Set(['tool', 'passive', 'consumable', 'boost', 'protection', 'crate', 'loot', 'luxury', 'trophy']);
    for (const [id, it] of Object.entries(data.ITEMS)) {
      check(it.name && it.emoji, `${id}: brak nazwy lub emoji`);
      check(TYPES.has(it.type), `${id}: nieznany typ „${it.type}”`);
      if (it.type === 'loot') check(it.sell > 0, `${id}: łup bez ceny skupu`);
      else check(it.price > 0 && it.desc, `${id}: brak ceny lub opisu`);
      if (it.requires) check(data.ITEMS[it.requires], `${id}: wymaga nieistniejącego „${it.requires}”`);
      if (it.type === 'luxury') check(it.unique, `${id}: luksus powinien być jednoegzemplarzowy`);
    }
    check(Object.keys(data.ITEMS).length >= 110, `przedmiotów w katalogu: ${Object.keys(data.ITEMS).length}`);

    const KNOWN_EFFECTS = new Set(['praca', 'earn', 'xp', 'luck', 'robDefense', 'bizIncome', 'bizStorage', 'farmSpeed']);
    for (const [id, eff] of Object.entries(data.EFFECTS)) {
      check(data.ITEMS[id]?.type === 'passive', `efekt ${id}: przedmiot musi być typu passive`);
      for (const k of Object.keys(eff)) check(KNOWN_EFFECTS.has(k), `efekt ${id}: nieznany rodzaj „${k}”`);
    }

    const keys = new Set();
    for (const [id, b] of Object.entries(data.BOOSTS)) {
      check(data.ITEMS[id]?.type === 'boost', `eliksir ${id}: przedmiot musi być typu boost`);
      check(!keys.has(b.key), `eliksir ${id}: powtórzony klucz ${b.key}`);
      keys.add(b.key);
      check(['earn', 'xp', 'loot', 'luck', 'cooldown'].includes(b.effect), `eliksir ${id}: zły efekt`);
      check(b.effect === 'cooldown' ? b.value > 0 && b.value < 1 : b.value > 1, `eliksir ${id}: zła wartość ${b.value}`);
      check(b.duration >= 60_000 && b.label, `eliksir ${id}: czas lub opis`);
    }
    for (const [id, it] of Object.entries(data.ITEMS)) {
      if (it.type === 'boost') check(data.BOOSTS[id], `${id}: boost bez wpisu w BOOSTS`);
      if (it.type === 'protection') check(data.PROTECTIONS[id], `${id}: ochrona bez wpisu w PROTECTIONS`);
      if (it.type === 'crate') check(crates.CRATES[id], `${id}: skrzynia bez tabeli łupów`);
    }

    // Zasada, która sprawia, że ochrony nie da się wykorzystać do zarabiania: sufit stawki nie przekracza ceny.
    for (const [id, p] of Object.entries(data.PROTECTIONS)) {
      check(data.ITEMS[id]?.type === 'protection', `ochrona ${id}: zły typ`);
      if (p.kind === 'polisa') check(p.cap <= data.ITEMS[id].price, `polisa ${id}: sufit ${p.cap} przekracza cenę ${data.ITEMS[id].price} (dałoby się zarabiać)`);
      else check(p.cap * 2 <= data.ITEMS[id].price, `tarcza ${id}: sufit ${p.cap} przekracza połowę ceny ${data.ITEMS[id].price}`);
    }

    // Sklep: każdy przedmiot jest w najwyżej jednej kategorii, a poza sklepem są tylko łupy i rzeczy ze skrzyń.
    const inShop = data.SHOP_CATEGORIES.flatMap((c) => c.items);
    check(new Set(inShop).size === inShop.length, 'przedmiot występuje w sklepie w dwóch kategoriach');
    check(inShop.length === data.SHOP_ORDER.length, 'SHOP_ORDER nie zgadza się z kategoriami');
    for (const id of inShop) check(data.ITEMS[id], `sklep sprzedaje nieistniejący „${id}”`);
    const outside = Object.entries(data.ITEMS).filter(([id, it]) => !inShop.includes(id) && it.type !== 'loot').map(([id]) => id);
    check(outside.every((id) => id === 'sejf'), `poza sklepem są przedmioty inne niż łup i sejf: ${outside.join(', ')}`);

    // Biznesy i farma: sensowny zwrot, plon wart więcej niż nasiona.
    for (const b of data.BUSINESSES) {
      const payback = b.price / b.hourly;
      check(payback >= 20 && payback <= 80, `biznes ${b.id}: zwrot po ${payback.toFixed(0)} h (oczekiwano 20-80)`);
    }
    const prices = data.BUSINESSES.map((b) => b.price);
    check(prices.every((p, i) => i === 0 || p > prices[i - 1]), 'biznesy są uporządkowane od najtańszego');
    for (const c of data.FARM_CROPS) check(c.yield > c.cost && c.yield <= c.cost * 2.2, `uprawa ${c.id}: plon ${c.yield} vs koszt ${c.cost}`);

    // Skrzynie: szanse na rzadkości sumują się do 100%, a wartość oczekiwana jest poniżej ceny (ale nie śmiesznie nisko).
    for (const id of Object.keys(crates.CRATES)) {
      const sum = Object.values(crates.rarityOdds(id)).reduce((s, x) => s + x, 0);
      const ev = crates.expectedValue(id);
      check(closeTo(sum, 100, 1e-6), `skrzynia ${id}: szanse sumują się do ${sum}`);
      check(ev < data.ITEMS[id].price && ev > data.ITEMS[id].price * 0.8, `skrzynia ${id}: wartość oczekiwana ${Math.round(ev)} przy cenie ${data.ITEMS[id].price}`);
    }
    console.log('  ok');
  }

  section('Sklep: przegląd i kategorie mieszczą się w limitach Discorda');
  {
    const u = fresh('shop', 1000);
    const over = await run('sklep', u, {});
    const e = over.replies[0].embeds[0].toJSON();
    const total = e.title.length + e.description.length + e.footer.text.length + e.fields.reduce((s, f) => s + f.name.length + f.value.length, 0);
    check(e.fields.length === data.SHOP_CATEGORIES.length, '/sklep: pole na kategorię');
    for (const f of e.fields) check(f.value.length <= 1024, `/sklep: „${f.name}” ma ${f.value.length} znaków`);
    check(total <= 6000, `/sklep: przegląd ma ${total} znaków (limit 6000)`);
    for (const cat of data.SHOP_CATEGORIES) {
      const r = await run('sklep', u, { kategoria: cat.id });
      const d = desc(r.replies[0]);
      check(d.length <= 4096, `/sklep ${cat.id}: opis ma ${d.length} znaków`);
      for (const id of cat.items) check(d.includes(`\`${id}\``), `/sklep ${cat.id}: brakuje ${id}`);
    }
    check(ephemeral(await run('sklep', u, { kategoria: 'nie_ma_takiej' })), '/sklep: nieznana kategoria → komunikat');

    const help = await run('pomoc', u, {});
    const he = help.replies[0].embeds[0].toJSON();
    const helpTotal = he.title.length + he.description.length + he.footer.text.length + he.fields.reduce((s, f) => s + f.name.length + f.value.length, 0);
    for (const f of he.fields) check(f.value.length <= 1024, `/pomoc: „${f.name}” ma ${f.value.length} znaków`);
    check(he.fields.length <= 25 && helpTotal <= 6000, `/pomoc: ${helpTotal} znaków w ${he.fields.length} polach`);
    const text = he.fields.map((f) => f.value).join('\n');
    const missing = [...commands.keys()].filter((name) => !['pomoc', 'admin'].includes(name) && !text.includes(`/${name}`));
    check(missing.length === 0, `/pomoc nie wspomina o: ${missing.join(', ')}`);
    console.log('  ok');
  }

  // ------------------------------------------------------------------ polisy
  section('Polisy: zwrot stawki po całkowitej przegranej');
  {
    const u = fresh('pol', 1_000_000);
    const arm = async (item) => {
      db.addItem(u.id, item, 1);
      return run('uzyj', u, { przedmiot: item });
    };
    const armed = await arm('polisa_mala');
    check(armed.replied && !ephemeral(armed) && db.buffUses(u.id, 'polisa_mala') === 1 && db.itemQty(u.id, 'polisa_mala') === 0, '/uzyj polisa_mala uzbraja polisę i zużywa przedmiot');
    check(ephemeral(await run('uzyj', u, { przedmiot: 'polisa_srednia' })), '/uzyj bez polisy w ekwipunku: odmowa');

    // pełna przegrana oddaje stawkę (do sufitu), wygrana i częściowa strata jej nie ruszają
    const win = game.settle(u.id, 1000, 2000);
    check(!win.refunded && db.buffUses(u.id, 'polisa_mala') === 1, 'wygrana nie zużywa polisy');
    const part = game.settle(u.id, 1000, 500);
    check(!part.refunded && db.buffUses(u.id, 'polisa_mala') === 1, 'częściowa strata nie zużywa polisy');
    const lost = game.settle(u.id, 1000, 0);
    check(lost.refunded?.id === 'polisa_mala' && lost.payout === 1000 && lost.net === 0, 'przegrana: stawka wraca, wynik 0');
    check(db.buffUses(u.id, 'polisa_mala') === 0, 'polisa zużyła się po zadziałaniu');
    check(!game.settle(u.id, 1000, 0).refunded, 'druga przegrana już nie jest chroniona');

    // stawka ponad sufit nie zużywa polisy i nie jest zwracana
    await arm('polisa_mala');
    const big = game.settle(u.id, 3000, 0);
    check(!big.refunded && big.net === -3000 && db.buffUses(u.id, 'polisa_mala') === 1, 'stawka ponad sufit: brak zwrotu, polisa zostaje');

    // wybierana jest najtańsza wystarczająca polisa
    await arm('polisa_srednia');
    const mid = game.settle(u.id, 10_000, 0);
    check(mid.refunded?.id === 'polisa_srednia' && db.buffUses(u.id, 'polisa_mala') === 1 && db.buffUses(u.id, 'polisa_srednia') === 0, 'dla stawki 10 000 działa polisa średnia, mała zostaje');
    check(game.settle(u.id, 500, 0).refunded?.id === 'polisa_mala', 'dla stawki 500 działa najtańsza (mała)');

    // w prawdziwych grach: wymuszona przegrana
    await arm('polisa_srednia');
    resetLedger();
    let before = wallet(u);
    const coin = await withRandom(0.9, () => run('moneta', u, { zaklad: '5000', strona: 'orzel' })); // 0.9 = reszka
    check(ledger.net === 0 && wallet(u) === before + ledger.levelBonus, `moneta z polisą: przegrana zwrócona (net ${ledger.net})`);
    check(/zwróciła Ci stawkę/.test(lastDesc(coin)), 'wynik gry mówi o zwrocie z polisy');

    await arm('polisa_srednia');
    resetLedger();
    before = wallet(u);
    const scratch = await withRandom(0, () => run('zdrapka', u, { zaklad: '5000' })); // pierwszy los = pole bez wygranej
    check(ledger.net === 0 && wallet(u) === before + ledger.levelBonus && /zwróciła Ci stawkę/.test(lastDesc(scratch)), `zdrapka z polisą: przegrana zwrócona (net ${ledger.net})`);
    console.log('  ok');
  }

  // ------------------------------------------------------------------ tarcze
  section('Tarcze: pierwszy zły klik się nie liczy');
  {
    const U = fresh('tar', 5_000_000);
    const armShield = () => db.addBuff(U.id, 'tarcza_mala', 1);
    // Przy stałym losie 0 tasowanie jest przewidywalne, więc wiemy, gdzie są miny.
    const minesAt = (count) => constSync(0, () => shuffle([...Array(20).keys()]).slice(0, count));

    // --- miny ---
    {
      const mines = minesAt(3);
      const safe = [...Array(20).keys()].filter((i) => !mines.includes(i));
      armShield();
      resetLedger();
      const before = wallet(U);
      const g = await withConst(0, () => run('miny', U, { zaklad: '1000', miny: 3 }));
      check(/pierwszy błąd się nie liczy/.test(desc(g.replies[0])), 'miny: ekran pokazuje aktywną tarczę');
      const hit = await g.collector.click(`mine_${mines[0]}`, U);
      check(!g.collector.ended && hit.updates.length === 1, 'miny: kliknięta mina z tarczą nie kończy gry');
      check(db.buffUses(U.id, 'tarcza_mala') === 0, 'miny: tarcza się zużyła');
      check(/zablokowała minę/.test(desc(hit.updates[0])), 'miny: komunikat o zablokowanej minie');
      const tile = buttonsOf(hit.updates[0]).find((b) => b.custom_id === `mine_${mines[0]}`);
      check(tile.disabled && tile.emoji.name === '🛡️', 'miny: zablokowane pole jest wyłączone i oznaczone tarczą');
      const again = await g.collector.click(`mine_${mines[0]}`, U);
      check(!g.collector.ended && again.updates.length === 0, 'miny: ponowny klik w zablokowane pole nic nie robi');
      await g.collector.click(`mine_${safe[0]}`, U);
      check(!g.collector.ended, 'miny: bezpieczne pole po tarczy działa normalnie');
      const boom = await g.collector.click(`mine_${mines[1]}`, U);
      await g.collector.settled();
      check(g.collector.ended && /BOOM/.test(desc(boom.updates[0])), 'miny: druga mina (bez tarczy) kończy grę');
      check(wallet(U) === before + ledger.net + ledger.levelBonus && ledger.net === -1000, `miny: po przegranej tracisz stawkę (net ${ledger.net})`);
    }
    // stawka ponad sufit: tarcza nie zadziała i nie zużyje się
    {
      const mines = minesAt(3);
      armShield();
      const g = await withConst(0, () => run('miny', U, { zaklad: '6000', miny: 3 })); // tarcza_mala chroni do 5 000
      check(!/pierwszy błąd się nie liczy/.test(desc(g.replies[0])), 'miny: ekran nie obiecuje tarczy przy zbyt wysokiej stawce');
      await g.collector.click(`mine_${mines[0]}`, U);
      await g.collector.settled();
      check(g.collector.ended && db.buffUses(U.id, 'tarcza_mala') === 1, 'miny: przy stawce ponad sufit mina wybucha, a tarcza zostaje');
      db.useBuff(U.id, 'tarcza_mala');
    }
    // wypłata po zablokowanej minie jest normalna
    {
      const mines = minesAt(3);
      const safe = [...Array(20).keys()].filter((i) => !mines.includes(i));
      armShield();
      resetLedger();
      const before = wallet(U);
      const g = await withConst(0, () => run('miny', U, { zaklad: '1000', miny: 3 }));
      await g.collector.click(`mine_${mines[0]}`, U);
      await g.collector.click(`mine_${safe[0]}`, U);
      await g.collector.click('mine_cash', U);
      await g.collector.settled();
      const expected = Math.floor(1000 * commands.get('miny').multiplier(1, 3));
      check(wallet(U) === before - 1000 + expected + ledger.levelBonus, `miny: wypłata po zablokowanej minie = ${expected}`);
    }

    // --- hilo: przy losie 0 każda karta to dwójka, więc „wyżej” zawsze przegrywa ---
    {
      armShield();
      resetLedger();
      const before = wallet(U);
      const g = await withConst(0, () => run('hilo', U, { zaklad: '1000' }));
      const first = await withConst(0, () => g.collector.click('hilo_high', U));
      check(!g.collector.ended && /anulowała zły typ/.test(desc(first.updates[0])), 'hilo: zły typ z tarczą jest anulowany, gra trwa');
      check(db.buffUses(U.id, 'tarcza_mala') === 0, 'hilo: tarcza zużyta');
      const second = await withConst(0, () => g.collector.click('hilo_high', U));
      await g.collector.settled();
      check(g.collector.ended && /Wypadło/.test(desc(second.updates[0])), 'hilo: drugi zły typ (bez tarczy) kończy grę');
      check(wallet(U) === before + ledger.net + ledger.levelBonus && ledger.net === -1000, 'hilo: pieniądze się zgadzają po przegranej');
    }

    // --- blackjack: dobranie, które by spaliło, jest anulowane, dopóki jest tarcza ---
    {
      let absorbed = 0;
      let violations = 0;
      for (let k = 0; k < 250; k++) {
        db.useBuff(U.id, 'tarcza_mala');
        armShield();
        resetLedger();
        const before = wallet(U);
        const g = await run('blackjack', U, { zaklad: '1000' });
        const col = g.collector; // brak kolektora = naturalny blackjack rozstrzygnięty od razu
        let sawBust = false;
        let sawShield = false;
        let guard = 0;
        while (col && !col.ended && guard++ < 25) {
          const ci = await col.click('bj_hit', U);
          const text = ci?.updates[0] ? desc(ci.updates[0]) : '';
          if (/Przekroczyłeś 21/.test(text)) sawBust = true;
          if (/anulowała dobranie/.test(text)) sawShield = true;
        }
        if (col && !col.ended) await col.stop('time');
        if (col) await col.settled();
        if (sawShield) absorbed++;
        if (sawBust && !sawShield && db.buffUses(U.id, 'tarcza_mala') > 0) violations++;
        check(wallet(U) === before + ledger.net + ledger.levelBonus, 'blackjack z tarczą: pieniądze się zgadzają');
      }
      check(absorbed > 100, `blackjack: tarcza anulowała dobranie ${absorbed} razy w 250 grach`);
      check(violations === 0, `blackjack: ${violations} razy spalenie mimo aktywnej tarczy`);
      db.useBuff(U.id, 'tarcza_mala');
    }
    console.log('  ok');
  }

  // ------------------------------------------------------------------ kurczak
  section('Kurczak (Chicken Road)');
  {
    const U = fresh('kurczak', 50_000_000);
    const lvl = commands.get('kurczak').LEVELS;
    for (const [key, l] of Object.entries(lvl)) {
      const p = 1 - l.d;
      let best = 0;
      for (let n = 1; n <= stepsLimit(p, l.max); n++) best = Math.max(best, p ** n * stepMultiplier(p, n));
      check(best < 0.97 && best > 0.9, `kurczak ${key}: zwrot najlepszej strategii ${(best * 100).toFixed(2)}%`);
      check(stepMultiplier(p, stepsLimit(p, l.max)) <= 1000, `kurczak ${key}: mnożnik w limicie x1000`);
    }
    check(stepMultiplier(0.75, 16) > 90, 'kurczak trudny: x100 jest osiągalne');
    check(stepMultiplier(0.6, stepsLimit(0.6, 14)) > 500, 'kurczak hardcore: sięga setek x');

    // 5 bezpiecznych kroków i wypłata
    {
      resetLedger();
      const before = wallet(U);
      const g = await run('kurczak', U, { zaklad: '1000', poziom: 'latwy' });
      for (let k = 0; k < 5; k++) await withConst(0, () => g.collector.click('go_forward', U));
      const ci = await g.collector.click('cash', U);
      await g.collector.settled();
      const expected = Math.floor(1000 * stepMultiplier(1 - lvl.latwy.d, 5));
      check(g.collector.ended && wallet(U) === before - 1000 + expected + ledger.levelBonus, `kurczak: 5 kroków → wypłata ${expected} (portfel ${wallet(U) - before})`);
      check(ledger.net === expected - 1000, 'kurczak: księga zgadza się z wypłatą');
      check(/Wypłacono/.test(desc(ci.updates[0])), 'kurczak: ekran końcowy pokazuje wypłatę');
    }
    // przycisk wypłaty jest wyłączony na starcie, a brak ruchu oddaje stawkę
    {
      const before = wallet(U);
      const g = await run('kurczak', U, { zaklad: '2000', poziom: 'sredni' });
      check(buttonsOf(g.replies[0]).find((b) => b.custom_id === 'cash').disabled, 'kurczak: wypłata wyłączona przed pierwszym krokiem');
      await g.collector.stop('idle');
      await g.collector.settled();
      check(wallet(U) === before, 'kurczak: brak ruchu → pełny zwrot stawki');
    }
    // śmierć = utrata stawki
    {
      resetLedger();
      const before = wallet(U);
      const g = await run('kurczak', U, { zaklad: '1000', poziom: 'trudny' });
      const dead = await withConst(0.999999, () => g.collector.click('go_forward', U));
      await g.collector.settled();
      check(g.collector.ended && /Potrącony/.test(desc(dead.updates[0])), 'kurczak: potrącenie kończy grę');
      check(wallet(U) === before + ledger.net + ledger.levelBonus && ledger.net === -1000, 'kurczak: po potrąceniu tracisz stawkę');
    }
    // tarcza: potrącenie nie liczy się, kurczak zostaje na pasie
    {
      db.addBuff(U.id, 'tarcza_mala', 1);
      const g = await run('kurczak', U, { zaklad: '1000', poziom: 'trudny' });
      await withConst(0, () => g.collector.click('go_forward', U));
      await withConst(0, () => g.collector.click('go_forward', U));
      const absorbed = await withConst(0.999999, () => g.collector.click('go_forward', U));
      check(!g.collector.ended && /odepchnęła samochód/.test(desc(absorbed.updates[0])), 'kurczak: tarcza odpycha samochód');
      check(/Pas: \*\*2\//.test(desc(absorbed.updates[0])), 'kurczak: po tarczy nadal stoisz na pasie 2');
      check(db.buffUses(U.id, 'tarcza_mala') === 0, 'kurczak: tarcza zużyta');
      await withConst(0.999999, () => g.collector.click('go_forward', U));
      await g.collector.settled();
      check(g.collector.ended, 'kurczak: kolejne potrącenie kończy grę');
    }
    // limit mnożnika: gra kończy się sama wypłatą
    {
      resetLedger();
      const before = wallet(U);
      const g = await run('kurczak', U, { zaklad: '1000', poziom: 'hardcore' });
      const p = 1 - lvl.hardcore.d;
      const max = stepsLimit(p, lvl.hardcore.max);
      let last = null;
      for (let k = 0; k < max + 2 && !g.collector.ended; k++) last = await withConst(0, () => g.collector.click('go_forward', U));
      await g.collector.settled();
      const expected = Math.floor(1000 * stepMultiplier(p, max));
      check(g.collector.ended && /limit x1000/.test(desc(last.updates[0])), 'kurczak: po osiągnięciu limitu automatyczna wypłata');
      check(wallet(U) === before - 1000 + expected + ledger.levelBonus, `kurczak: wypłata z limitu ${expected}`);
    }
    // timeout po kilku krokach wypłaca
    {
      resetLedger();
      const before = wallet(U);
      const g = await run('kurczak', U, { zaklad: '2000', poziom: 'latwy' });
      await withConst(0, () => g.collector.click('go_forward', U));
      await g.collector.stop('idle');
      await g.collector.settled();
      check(wallet(U) === before + ledger.net + ledger.levelBonus && ledger.net > 0, 'kurczak: po kroku timeout wypłaca wygraną');
    }
    // obcy gracz nie kliknie
    {
      const g = await run('kurczak', U, { zaklad: '1000' });
      const ci = await g.collector.click('go_forward', makeUser('f_obcy'));
      check(ci.replies.length === 1 && !g.collector.ended, 'kurczak: klik obcego odrzucony');
      await g.collector.stop('idle');
      await g.collector.settled();
    }
    console.log('  ok');
  }

  // ------------------------------------------------------------------ wieża
  section('Wieża');
  {
    const U = fresh('wieza', 50_000_000);
    const lvl = commands.get('wieza').LEVELS;
    for (const [key, l] of Object.entries(lvl)) {
      const p = (l.cols - l.bad) / l.cols;
      let best = 0;
      for (let n = 1; n <= stepsLimit(p, 14); n++) best = Math.max(best, p ** n * stepMultiplier(p, n));
      check(best < 0.97 && best > 0.9, `wieża ${key}: zwrot ${(best * 100).toFixed(2)}%`);
    }
    // Przy stałym losie 0 pułapki na każdym piętrze są w tych samych miejscach.
    const badAt = (cols, bad) => constSync(0, () => shuffle([...Array(cols).keys()]).slice(0, bad));
    const [bad] = badAt(3, 1);
    const safe = [0, 1, 2].filter((i) => i !== bad);
    const start = (opts) => withConst(0, () => run('wieza', U, { zaklad: '1000', ...opts }));

    {
      resetLedger();
      const before = wallet(U);
      const g = await start({ poziom: 'sredni' });
      check(buttonsOf(g.replies[0]).filter((b) => b.custom_id.startsWith('go_')).length === 3, 'wieża: trzy pola na piętrze');
      await withConst(0, () => g.collector.click(`go_${safe[0]}`, U));
      await withConst(0, () => g.collector.click(`go_${safe[1]}`, U));
      const ci = await g.collector.click('cash', U);
      await g.collector.settled();
      const expected = Math.floor(1000 * stepMultiplier(2 / 3, 2));
      check(g.collector.ended && wallet(U) === before - 1000 + expected + ledger.levelBonus, `wieża: 2 piętra → wypłata ${expected}`);
      check(/Piętro 2:/.test(desc(ci.updates[0])) && /Piętro 1:/.test(desc(ci.updates[0])), 'wieża: ekran pokazuje przebyte piętra');
    }
    // pułapka
    {
      resetLedger();
      const before = wallet(U);
      const g = await start({ poziom: 'sredni' });
      const boom = await g.collector.click(`go_${bad}`, U);
      await g.collector.settled();
      check(g.collector.ended && /Pułapka/.test(desc(boom.updates[0])), 'wieża: pułapka kończy grę');
      check(wallet(U) === before + ledger.net + ledger.levelBonus && ledger.net === -1000, 'wieża: tracisz stawkę');
    }
    // tarcza zdejmuje pułapkę: pole zostaje wyłączone, można wybrać inne
    {
      db.addBuff(U.id, 'tarcza_mala', 1);
      const g = await start({ poziom: 'sredni' });
      const blocked = await g.collector.click(`go_${bad}`, U);
      check(!g.collector.ended && /zablokowała pułapkę/.test(desc(blocked.updates[0])), 'wieża: tarcza blokuje pułapkę');
      const tile = buttonsOf(blocked.updates[0]).find((b) => b.custom_id === `go_${bad}`);
      check(tile.disabled && tile.emoji.name === '🛡️', 'wieża: zablokowane pole jest wyłączone');
      const again = await g.collector.click(`go_${bad}`, U);
      check(!g.collector.ended && again.updates.length === 0, 'wieża: ponowny klik w zablokowane pole nic nie robi');
      const up = await withConst(0, () => g.collector.click(`go_${safe[0]}`, U));
      check(!g.collector.ended && /Piętro: \*\*1\//.test(desc(up.updates[0])), 'wieża: po tarczy wchodzisz piętro wyżej');
      await g.collector.stop('idle');
      await g.collector.settled();
    }
    // 2 pola, 1 pułapka (trudny): po zablokowaniu pułapki drugie pole jest pewne
    {
      const [b2] = badAt(2, 1);
      db.addBuff(U.id, 'tarcza_mala', 1);
      const g = await start({ poziom: 'trudny' });
      await g.collector.click(`go_${b2}`, U);
      const ok = await withConst(0, () => g.collector.click(`go_${1 - b2}`, U));
      check(!g.collector.ended && ok.updates.length === 1, 'wieża trudna: po zablokowaniu pułapki drugie pole jest bezpieczne');
      await g.collector.stop('idle');
      await g.collector.settled();
    }
    console.log('  ok');
  }

  // ------------------------------------------------------------------ plinko
  section('Plinko');
  {
    const plinko = commands.get('plinko');
    for (const [risk, mults] of Object.entries(plinko.MULTS)) {
      check(mults.length === plinko.ROWS + 1 && mults.every((m, i) => m === mults[mults.length - 1 - i]), `plinko ${risk}: ${plinko.ROWS + 1} koszyków, układ symetryczny`);
      const rtp = mults.reduce((s, m, i) => s + (m * choose(plinko.ROWS, i)) / 2 ** plinko.ROWS, 0);
      check(rtp > 0.88 && rtp < 1, `plinko ${risk}: zwrot ${(rtp * 100).toFixed(2)}%`);
    }
    check(plinko.MULTS.szalone[0] === 1000 && plinko.MULTS.wysokie[0] >= 100, 'plinko: są koszyki x100 i x1000');

    const U = fresh('plinko', 50_000_000);
    for (const risk of Object.keys(plinko.MULTS)) {
      const m = plinko.MULTS[risk];
      // Math.random() < 0.5 oznacza ruch w prawo, więc 0.99 to same ruchy w lewo (koszyk 0), a 0.01 same w prawo (koszyk 12).
      for (const [value, bucket] of [[0.99, 0], [0.01, 12]]) {
        resetLedger();
        const before = wallet(U);
        const g = await withConst(value, () => run('plinko', U, { zaklad: '1000', ryzyko: risk }));
        const expected = Math.floor(1000 * m[bucket]);
        check(wallet(U) === before - 1000 + expected + ledger.levelBonus, `plinko ${risk}: koszyk ${bucket} → wypłata ${expected}`);
        check(g.edits.length === 4 && /Kulka wpadła/.test(lastDesc(g)), 'plinko: animacja ma klatki i wynik');
      }
      let i = 0; // na przemian lewo/prawo: koszyk środkowy
      resetLedger();
      const before = wallet(U);
      await withRandom(() => (i++ % 2 ? 0.9 : 0.1), () => run('plinko', U, { zaklad: '1000', ryzyko: risk }));
      check(wallet(U) === before - 1000 + Math.floor(1000 * m[6]) + ledger.levelBonus, `plinko ${risk}: środkowy koszyk`);
    }
    resetLedger();
    const before = wallet(U);
    await withConst(0.01, () => run('plinko', U, { zaklad: '1000', ryzyko: 'szalone' }));
    check(wallet(U) === before - 1000 + 1_000_000 + ledger.levelBonus, 'plinko szalone: skrajny koszyk wypłaca 1000× stawki');
    console.log('  ok');
  }

  // ------------------------------------------------------------------ limbo
  section('Limbo');
  {
    const limbo = commands.get('limbo');
    check(limbo.roll(0) === 1 && limbo.roll(0.5) === 1.92 && limbo.roll(0.75) === 3.84 && limbo.roll(0.875) === 7.68, 'limbo: wartości wyniku dla znanych losów');
    check(Math.abs(limbo.roll(0.99) - 96) < 0.02, 'limbo: los 0.99 daje ok. x96');
    check(limbo.roll(0.9999999999) === 1_000_000, 'limbo: wynik ma sufit x1 000 000');
    // rozkład: P(wynik ≥ x) = 0.96 / x
    const N = 600_000;
    for (const [x, tol] of [[2, 0.004], [10, 0.0025], [100, 0.0008]]) {
      let hit = 0;
      for (let i = 0; i < N; i++) if (limbo.roll() >= x) hit++;
      const p = hit / N;
      check(Math.abs(p - 0.96 / x) < tol, `limbo: P(≥x${x}) = ${(p * 100).toFixed(3)}% (oczekiwano ${((0.96 / x) * 100).toFixed(3)}%)`);
    }
    const U = fresh('limbo', 50_000_000);
    resetLedger();
    let before = wallet(U);
    let g = await withConst(0.75, () => run('limbo', U, { zaklad: '1000', cel: 2 })); // wynik 3.84 ≥ 2.00
    check(wallet(U) === before - 1000 + 2000 + ledger.levelBonus && /✅/.test(lastDesc(g)), 'limbo: wynik 3.84 dobija do celu 2 → wygrana');
    resetLedger();
    before = wallet(U);
    g = await withConst(0.1, () => run('limbo', U, { zaklad: '1000', cel: 2 })); // wynik 1.06
    check(wallet(U) === before - 1000 + ledger.levelBonus && /❌/.test(lastDesc(g)), 'limbo: wynik 1.06 nie dobija do celu 2 → przegrana');
    resetLedger();
    before = wallet(U);
    g = await withConst(0.99999, () => run('limbo', U, { zaklad: '1000', cel: 1000 })); // wynik 96 000
    check(wallet(U) === before - 1000 + 1_000_000 + ledger.levelBonus, 'limbo: cel x1000 wypłaca 1000× stawki');
    check(g.edits.length === 4, 'limbo: animacja licznika');
    for (const bad of [1, 1.005, 1001, 5000]) {
      check(ephemeral(await withConst(0.5, () => run('limbo', U, { zaklad: '100', cel: bad }))), `limbo: cel ${bad} poza zakresem → odmowa`);
    }
    console.log('  ok');
  }

  // ------------------------------------------------------------------ szansa
  section('Szansa (rzut na procent)');
  {
    const szansa = commands.get('szansa');
    for (const c of [0.5, 1, 2.5, 10, 33.33, 50, 75, 95]) {
      const rtp = (c / 100) * szansa.multiplierFor(c);
      check(rtp <= 0.96 + 1e-9 && rtp > 0.94, `szansa ${c}%: zwrot ${(rtp * 100).toFixed(2)}%`);
    }
    check(szansa.multiplierFor(50) === 1.92 && szansa.multiplierFor(0.5) === 192, 'szansa: mnożniki 50% → x1.92, 0.5% → x192');
    const U = fresh('szansa', 50_000_000);
    const play = async (value, opts) => {
      resetLedger();
      const before = wallet(U);
      const g = await withConst(value, () => run('szansa', U, { zaklad: '1000', ...opts }));
      return { g, delta: wallet(U) - before - ledger.levelBonus };
    };
    check((await play(0.3, { szansa: 50 })).delta === 920, 'szansa: wynik 30.00 < 50 → wygrana x1.92');
    check((await play(0.5, { szansa: 50 })).delta === -1000, 'szansa: wynik 50.00 nie jest poniżej 50 → przegrana (granica)');
    check((await play(0.6, { szansa: 50, tryb: 'powyzej' })).delta === 920, 'szansa: tryb powyżej, wynik 60.00 → wygrana');
    check((await play(0.3, { szansa: 50, tryb: 'powyzej' })).delta === -1000, 'szansa: tryb powyżej, wynik 30.00 → przegrana');
    check((await play(0.004, { szansa: 0.5 })).delta === 191_000, 'szansa 0.5%: wygrana x192');
    const r = await play(0.2, { szansa: 20 });
    check(r.g.edits.length === 3 && /🔻/.test(lastDesc(r.g)), 'szansa: animacja i pasek z markerem');
    check(ephemeral(await run('szansa', U, { zaklad: '100', szansa: 99 })) && ephemeral(await run('szansa', U, { zaklad: '100', szansa: 0.1 })), 'szansa: poza 0,5-95% odmowa');
    console.log('  ok');
  }

  // ------------------------------------------------------------------ rewolwer
  section('Rewolwer (rosyjska ruletka)');
  {
    const rew = commands.get('rewolwer');
    for (let b = 1; b <= 5; b++) {
      const rtp = ((6 - b) / 6) * rew.multiplierFor(b);
      check(rtp <= 0.96 + 1e-9 && rtp > 0.95, `rewolwer ${b} naboi: zwrot ${(rtp * 100).toFixed(2)}%`);
    }
    check(rew.multiplierFor(5) === 5.76 && rew.multiplierFor(1) === 1.15, 'rewolwer: mnożniki x1.15 … x5.76');
    const U = fresh('rewolwer', 50_000_000);
    // Pierwsze 5 losowań to tasowanie bębna (0.999 = bez zmian, naboje w komorach 0..n-1), szóste wybiera komorę strzału.
    const shoot = async (survive, count) => {
      let calls = 0;
      const stub = () => (++calls <= 5 ? 0.999 : survive ? 0.999 : 0);
      resetLedger();
      const before = wallet(U);
      const g = await withRandom(stub, () => run('rewolwer', U, { zaklad: '1000', naboje: count }));
      return { g, delta: wallet(U) - before - ledger.levelBonus };
    };
    const live = await shoot(true, 5);
    check(live.delta === 4760 && /Klik/.test(lastDesc(live.g)), 'rewolwer: 5 naboi, przeżycie → x5.76');
    const dead = await shoot(false, 1);
    check(dead.delta === -1000 && /BANG/.test(lastDesc(dead.g)), 'rewolwer: trafiona komora → przegrana');
    check(dead.g.edits.length === 3, 'rewolwer: animacja napięcia');
    console.log('  ok');
  }

  // ------------------------------------------------------------------ jackpot
  section('Jackpot (wielu graczy)');
  {
    const A = fresh('jp_a', 10_000);
    const B = fresh('jp_b', 10_000);
    const C = fresh('jp_c', 50);
    const total = () => wallet(A) + wallet(B) + wallet(C);
    resetLedger();
    const before = total();
    const j = await run('jackpot', A, { zaklad: '1000' }, null, 'jackpot_chan');
    check(wallet(A) === 9000, 'jackpot: starter płaci stawkę');
    check(ephemeral(await run('jackpot', B, { zaklad: '1000' }, null, 'jackpot_chan')), 'jackpot: drugi jackpot na tym samym kanale zablokowany');
    const joinB = await j.collector.click('jackpot_join', B);
    const dup = await j.collector.click('jackpot_join', B);
    const poor = await j.collector.click('jackpot_join', C);
    check(joinB.updates.length === 1 && dup.replies.length === 1 && poor.replies.length === 1, 'jackpot: dołączenie, duplikat i brak środków');
    check(wallet(C) === 50, 'jackpot: kto nie ma środków, nic nie traci');
    await j.collector.stop('time');
    await j.collector.settled();
    const pot = 2000;
    const fee = Math.ceil(pot * 0.05);
    check(total() === before - fee + ledger.levelBonus, `jackpot: z puli znika tylko 5% prowizji (suma ${total()} vs ${before - fee})`);
    check(j.edits.length === 4 && /zgarnia/.test(lastDesc(j)), 'jackpot: animacja losowania i wynik');
    const winners = [A, B].filter((u) => wallet(u) > 9000 + ledger.levelBonus);
    check(winners.length === 1 && wallet(winners[0]) >= 10_900, 'jackpot: jeden zwycięzca bierze pulę pomniejszoną o prowizję');

    // brak chętnych: zwrot
    rich(A, 5000);
    const solo = await run('jackpot', A, { zaklad: '1000' }, null, 'jackpot_chan2');
    await solo.collector.stop('time');
    await solo.collector.settled();
    check(wallet(A) === 5000 && /Nikt nie dołączył/.test(lastDesc(solo)), 'jackpot: bez uczestników stawka wraca');
    check(ephemeral(await run('jackpot', C, { zaklad: '1000' }, null, 'jackpot_chan3')), 'jackpot: za mało pieniędzy na start → odmowa');
    console.log('  ok');
  }

  // ------------------------------------------------------------------ crash, sloty, koło, zdrapka
  section('Crash i inne gry po przestrojeniu');
  {
    const crash = commands.get('crash');
    check(crash.MAX_CRASH === 10000, 'crash: sufit x10 000');
    check(closeTo(crash.reachChance(2), 0.485) && closeTo(crash.reachChance(10), 0.097), 'crash: szanse dojścia do x2 (48.5%) i x10 (9.7%)');
    const N = 500_000;
    let ge2 = 0;
    let ge10 = 0;
    let ge100 = 0;
    for (let i = 0; i < N; i++) {
      const c = crash.crashPoint();
      if (c >= 2) ge2++;
      if (c >= 10) ge10++;
      if (c >= 100) ge100++;
    }
    check(
      Math.abs(ge2 / N - 0.485) < 0.004 && Math.abs(ge10 / N - 0.097) < 0.002 && Math.abs(ge100 / N - 0.0097) < 0.0007,
      `crash: rozkład P(≥2)=${((ge2 / N) * 100).toFixed(1)}% P(≥10)=${((ge10 / N) * 100).toFixed(2)}% P(≥100)=${((ge100 / N) * 100).toFixed(2)}%`,
    );

    // historia rund zapisuje się i pokazuje
    const U = fresh('crashhist', 10_000_000);
    db.kvSet('crash_history', '[]');
    for (let k = 0; k < 3; k++) {
      const g = await run('crash', U, { zaklad: '100', auto: 1.1 });
      for (let w = 0; w < 500 && !g.collector.ended; w++) await new Promise((r) => setTimeout(r, 2));
      if (!g.collector.ended) await g.collector.stop('time');
      await g.collector.settled();
      await new Promise((r) => setTimeout(r, 5));
    }
    const hist = JSON.parse(db.kvGet('crash_history', '[]'));
    check(hist.length === 3 && hist.every((v) => v >= 1), `crash: historia ma ${hist.length} rundy`);
    const again = await run('crash', U, { zaklad: '100', auto: 1.1 });
    check(/Ostatnie rundy/.test(desc(again.replies[0])) && /Szanse dojścia/.test(again.replies[0].embeds[0].toJSON().footer.text), 'crash: ekran pokazuje historię i szanse');
    await again.collector.stop('time');
    await again.collector.settled();

    // sloty: jackpot x200, zwrot z jackpotem poniżej 100%
    const sloty = commands.get('sloty');
    const W = sloty.SYMBOLS.reduce((s, x) => s + x.w, 0);
    let base = 0;
    for (const a of sloty.SYMBOLS) for (const b of sloty.SYMBOLS) for (const c of sloty.SYMBOLS) base += (a.w / W) * (b.w / W) * (c.w / W) * sloty.multiplier([a, b, c]).mult;
    const rtp = (1 - sloty.JACKPOT.chance) * base + sloty.JACKPOT.chance * sloty.JACKPOT.mult;
    check(rtp < 1 && rtp > 0.9, `sloty: zwrot z jackpotem ${(rtp * 100).toFixed(2)}%`);
    resetLedger();
    const before = wallet(U);
    const jp = await withRandom(0, () => run('sloty', U, { zaklad: '1000' })); // pierwszy los = jackpot
    check(wallet(U) === before - 1000 + 200_000 + ledger.levelBonus && /JACKPOT/.test(lastDesc(jp)), 'sloty: jackpot wypłaca 200× stawki');

    // koło i zdrapka: duże wygrane, zwrot poniżej 100%
    const kolo = commands.get('kolo');
    const kw = kolo.SEGMENTS.reduce((s, x) => s + x.w, 0);
    const krtp = kolo.SEGMENTS.reduce((s, x) => s + (x.w / kw) * x.mult, 0);
    check(krtp < 1 && krtp > 0.9 && Math.max(...kolo.SEGMENTS.map((x) => x.mult)) === 200, `koło: zwrot ${(krtp * 100).toFixed(2)}%, jest segment x200`);
    const zd = commands.get('zdrapka');
    const zw = zd.OUTCOMES.reduce((s, x) => s + x.w, 0);
    const zrtp = zd.OUTCOMES.reduce((s, x) => s + (x.w / zw) * x.mult, 0);
    check(zrtp < 1 && zrtp > 0.9 && Math.max(...zd.OUTCOMES.map((x) => x.mult)) === 500, `zdrapka: zwrot ${(zrtp * 100).toFixed(2)}%, jest wygrana x500`);
    // plansza zdrapki: przy wygranej dokładnie trzy takie same symbole, przy przegranej żaden nie występuje trzy razy
    for (const outcome of zd.OUTCOMES) {
      for (let k = 0; k < 40; k++) {
        const counts = {};
        for (const c of zd.buildGrid(outcome)) counts[c] = (counts[c] ?? 0) + 1;
        const triples = Object.entries(counts).filter(([, n]) => n >= 3);
        check(outcome.sym ? triples.length === 1 && triples[0][0] === outcome.sym && triples[0][1] === 3 : triples.length === 0, `zdrapka: plansza dla x${outcome.mult} jest poprawna`);
      }
    }
    console.log('  ok');
  }

  // ------------------------------------------------------------------ efekty przedmiotów
  section('Efekty przedmiotów: zwierzaki, karty, eliksiry, bonusy');
  {
    const U = fresh('eff', 100_000_000);
    const mult = () => boosts.applyEarn(U.id, 1000).mult;
    check(closeTo(mult(), 1), 'bez przedmiotów ×1');
    db.addItem(U.id, 'papuga');
    check(closeTo(mult(), 1.05), 'papuga: +5% zarobków');
    db.addItem(U.id, 'smok');
    check(closeTo(mult(), 1.2), 'papuga i smok: +20% zarobków (sumują się)');
    db.addItem(U.id, 'vip_zloty');
    check(closeTo(mult(), 1.7), 'złota karta + zwierzaki: 1 + 0,5 + 0,2 = ×1,7');
    db.addItem(U.id, 'vip_platynowa');
    check(closeTo(mult(), 2.2), 'karta platynowa (najlepsza z kart, nie suma): 1 + 1 + 0,2');
    db.addItem(U.id, 'vip_diamentowa');
    check(closeTo(mult(), 3.2), 'karta diamentowa: 1 + 2 + 0,2');
    db.addItem(U.id, 'mega_eliksir');
    const used = await run('uzyj', U, { przedmiot: 'mega_eliksir' });
    check(used.replied && !ephemeral(used) && db.itemQty(U.id, 'mega_eliksir') === 0, '/uzyj mega_eliksir aktywuje eliksir');
    boosts.activate(U.id, 'eliksir_zarobkow');
    check(closeTo(mult(), 9.6), 'eliksir ×3 i ×2 naraz: działa najlepszy (×3), nie iloczyn');
    check(closeTo(boosts.summary(U.id).totals.earn, 9.6), '/mnozniki: razem zarobki ×9,6');
    const reuse = boosts.activate(U.id, 'mega_eliksir');
    check(reuse.extended && reuse.left > 15 * 60_000, 'ponowne użycie eliksiru przedłuża czas');

    // XP: bonusy sumują się, eliksir mnoży, XP ze skrzyń ({ boost: false }) jest bez mnożników
    const xpMult = (setup) => {
      const u = fresh(`xp_${Math.random()}`);
      setup(u);
      return { u, mult: db.xpMultiplier(u.id) };
    };
    check(closeTo(xpMult(() => {}).mult, 1), 'XP: bez bonusów ×1');
    check(closeTo(xpMult((u) => db.addItem(u.id, 'sowa')).mult, 1.1), 'XP: sowa +10%');
    check(closeTo(xpMult((u) => ['dyplom', 'doktorat', 'sowa'].forEach((i) => db.addItem(u.id, i))).mult, 1.85), 'XP: dyplom + doktorat + sowa = ×1,85');
    const smokPodrecznik = xpMult((u) => (db.addItem(u.id, 'smok'), boosts.activate(u.id, 'mega_podrecznik')));
    check(closeTo(smokPodrecznik.mult, 3.45), 'XP: smok (+15%) razy mega podręcznik ×3 = ×3,45');
    const gain = (u, fn) => {
      const x0 = db.getUser(u.id).xp;
      fn();
      return db.getUser(u.id).xp - x0;
    };
    check(gain(smokPodrecznik.u, () => db.addXp(smokPodrecznik.u.id, 10)) === 35, 'XP: 10 → 35 z bonusami');
    check(gain(smokPodrecznik.u, () => db.addXp(smokPodrecznik.u.id, 10, { boost: false })) === 10, 'XP ze skrzyni ({ boost: false }) pomija mnożniki');

    // szczęście i łup
    const L = fresh('luck');
    check(boosts.luckMultiplier(L.id) === 1, 'szczęście: ×1');
    db.addItem(L.id, 'kot');
    db.addItem(L.id, 'talizman');
    check(closeTo(boosts.luckMultiplier(L.id), 1.35), 'szczęście: kot + talizman = ×1,35');
    boosts.activate(L.id, 'eliksir_szczescia');
    check(closeTo(boosts.luckMultiplier(L.id), 2.7), 'szczęście: razy eliksir ×2 = ×2,7');
    boosts.activate(L.id, 'magnes_lupu');
    check(boosts.lootMultiplier(L.id) === 2, 'łup: magnes ×2');
    boosts.activate(L.id, 'mega_magnes');
    check(boosts.lootMultiplier(L.id) === 3, 'łup: mega magnes ×3 (najlepszy)');

    // cooldowny: działa najkrótszy
    const Z = fresh('cd');
    boosts.activate(Z.id, 'zegarek');
    check(db.scaledCooldown(Z.id, 1000) === 500, 'cooldown: przyspieszacz −50%');
    boosts.activate(Z.id, 'super_zegarek');
    check(db.scaledCooldown(Z.id, 1000) === 250, 'cooldown: super przyspieszacz −75% (najkrótszy)');
    await run('godzinna', Z, {});
    check(db.cooldownLeft(Z.id, 'godzinna') <= data.COOLDOWNS.godzinna / 4 + 5, 'cooldown z eliksirem skraca się naprawdę (godzinna)');

    // mega magnes w lesie: każdy łup jest wielokrotnością 3
    const M = fresh('mega');
    boosts.activate(M.id, 'mega_magnes');
    const lootTotal = (id) => db.inventory(id).filter((r) => ['loot', 'crate'].includes(ITEMS[r.item]?.type)).reduce((s, r) => s + r.qty, 0);
    let allTriple = true;
    for (let k = 0; k < 30; k++) {
      db.clearCooldown(M.id, 'las');
      const b = lootTotal(M.id);
      await run('las', M, {});
      const gained = lootTotal(M.id) - b;
      if (gained < 3 || gained % 3 !== 0) allTriple = false;
    }
    check(allTriple, 'mega magnes: każdy łup z lasu ×3');

    // odsetki z konta premium
    const P = fresh('premium');
    db.addItem(P.id, 'konto_oszczednosciowe');
    db.addItem(P.id, 'konto_premium');
    db.setBank(P.id, 1_000_000);
    await run('daily', P, {});
    check(wallet(P) === 625 + 60_000, `konto premium: 6% od miliona = 60 000 (portfel ${wallet(P)})`);
    const P2 = fresh('premium2');
    db.addItem(P2.id, 'konto_premium');
    db.setBank(P2.id, 5_000_000);
    await run('daily', P2, {});
    check(wallet(P2) === 625 + 150_000, 'konto premium: sufit odsetek 150 000');

    // /praca: bonusy z przedmiotów się sumują i są pokazane osobno
    const W = fresh('praca');
    for (const i of ['laptop', 'garnitur', 'samochod']) db.addItem(W.id, i);
    check(closeTo(boosts.pracaPerks(W.id).reduce((s, p) => s + p.bonus, 0), 0.65), '/praca: laptop + garnitur + samochód = +65%');
    const job = await run('praca', W, {}, 'wykonaj');
    const jd = desc(job.replies[0]);
    check(/Laptop: \+25%/.test(jd) && /Garnitur: \+15%/.test(jd) && /Samochód: \+25%/.test(jd), '/praca pokazuje każdy bonus osobno');
    console.log('  ok');
  }

  // ------------------------------------------------------------------ rabunek: pies, alarm, łom
  section('Okradanie: pies, alarm i łom');
  {
    const thief = fresh('zlodziej', 10_000);
    const victim = fresh('ofiara', 10_000);
    const reset = () => {
      db.setWallet(thief.id, 10_000);
      db.setWallet(victim.id, 10_000);
      db.clearCooldown(thief.id, 'rob');
      db.clearCooldown(victim.id, 'robbed');
    };
    const rob = (value) => withConst(value, () => run('okradnij', thief, { ofiara: victim }));
    // los 0.4: szansa 45% wystarcza, ale z psem (−15 pkt, czyli 30%) już nie
    reset();
    await rob(0.4);
    check(wallet(thief) > 10_000, 'okradnij: los 0,4 przy szansie 45% → skok udany');
    reset();
    db.addItem(victim.id, 'pies');
    await rob(0.4);
    check(wallet(thief) < 10_000, 'okradnij: pies ofiary obniża szansę do 30% → ten sam los już nie wystarcza');
    // alarm: przy porażce grzywna podwójna
    reset();
    db.takeItem(victim.id, 'pies', 1);
    await rob(0.99);
    const plainFine = wallet(victim) - 10_000;
    reset();
    db.addItem(victim.id, 'alarm');
    await rob(0.99);
    const alarmFine = wallet(victim) - 10_000;
    check(plainFine > 0 && alarmFine === plainFine * 2, `okradnij: alarm podwaja grzywnę (${plainFine} → ${alarmFine})`);
    // kłódka bez łomu: porażka, a kłódka pęka. Z łomem skok trwa, łom i kłódka znikają.
    reset();
    db.takeItem(victim.id, 'alarm', 1);
    db.addItem(victim.id, 'klodka');
    await rob(0);
    check(wallet(thief) < 10_000 && db.itemQty(victim.id, 'klodka') === 0, 'okradnij: kłódka bez łomu zatrzymuje złodzieja');
    reset();
    db.addItem(victim.id, 'klodka');
    db.addItem(thief.id, 'lom');
    await rob(0);
    check(wallet(thief) > 10_000 && db.itemQty(thief.id, 'lom') === 0 && db.itemQty(victim.id, 'klodka') === 0, 'okradnij: łom przebija kłódkę i skok się udaje');
    console.log('  ok');
  }

  // ------------------------------------------------------------------ darmowe zarobki
  section('Darmowe zarobki: godzinna, las, śmietnik, uliczny');
  {
    const U = fresh('free', 0);
    const lvl = db.getUser(U.id).level;
    const hour = await withConst(0.5, () => run('godzinna', U, {}));
    check(hour.replied && wallet(U) === Math.floor((300 + lvl * 40) * (0.8 + 0.5 * 0.4)), `godzinna: premia ${wallet(U)} zależy od poziomu`);
    check(ephemeral(await run('godzinna', U, {})), 'godzinna: drugi raz w godzinie → cooldown');
    const left = db.cooldownLeft(U.id, 'godzinna');
    check(left > 55 * 60_000 && left <= 60 * 60_000, 'godzinna: cooldown ok. godziny');

    // las i śmietnik działają bez narzędzia
    const lootTotal = (id) => db.inventory(id).filter((r) => ['loot', 'crate'].includes(ITEMS[r.item]?.type)).reduce((s, r) => s + r.qty, 0);
    for (const [cmd, min] of [['las', 100], ['smietnik', 70]]) {
      const F = fresh(`free_${cmd}`);
      let hits = 0;
      for (let k = 0; k < 100; k++) {
        db.clearCooldown(F.id, cmd);
        const b = lootTotal(F.id);
        const r = await run(cmd, F, {});
        check(r.replied && !ephemeral(r), `/${cmd} działa bez narzędzia`);
        if (lootTotal(F.id) > b) hits++;
      }
      check(hits >= min, `/${cmd}: łup w ${hits}/100 próbach (oczekiwano ≥ ${min})`);
    }
    // wielki koszyk zwiększa udział rzadkich łupów (zwykle ok. 18% → ok. 40%). Narzędzia psują się z małą szansą,
    // więc przed każdą próbą oddajemy koszyk, żeby porównanie nie zależało od tego, kiedy pęknie.
    const RARE = ['borowik', 'trufla', 'zloty_grzyb', 'skrzynka'];
    const rareCount = async (basket) => {
      const F = fresh(`basket_${basket ?? 'brak'}`);
      for (let k = 0; k < 400; k++) {
        if (basket && db.itemQty(F.id, basket) < 1) db.addItem(F.id, basket);
        db.clearCooldown(F.id, 'las');
        await run('las', F, {});
      }
      return RARE.reduce((s, id) => s + db.itemQty(F.id, id), 0);
    };
    const noBasket = await rareCount(null);
    const bigBasket = await rareCount('wielki_koszyk');
    check(bigBasket > noBasket * 1.5, `las: z wielkim koszykiem ${bigBasket} rzadkich łupów w 400 próbach vs ${noBasket} bez`);

    // narzędzie psuje się z szansą z BONUSES.breakChance (pas narzędziowy ją obniża)
    const B = fresh('break');
    let broke = 0;
    for (let k = 0; k < 400; k++) {
      db.clearCooldown(B.id, 'las');
      if (db.itemQty(B.id, 'koszyk') < 1) db.addItem(B.id, 'koszyk');
      await run('las', B, {});
      if (db.itemQty(B.id, 'koszyk') < 1) broke++;
    }
    check(broke > 400 * data.BONUSES.breakChance * 0.5 && broke < 400 * data.BONUSES.breakChance * 1.8, `las: koszyk zepsuł się ${broke}/400 razy (oczekiwano ok. ${Math.round(400 * data.BONUSES.breakChance)})`);

    // uliczny: instrument mnoży napiwki (przy stałym losie wynik jest przewidywalny)
    const busk = async (instrument) => {
      const F = fresh(`busk_${instrument ?? 'brak'}`);
      if (instrument) db.addItem(F.id, instrument);
      const level = db.getUser(F.id).level;
      await withConst(0.5, () => run('uliczny', F, {}));
      return { got: wallet(F), base: 65 + level * 2 }; // randInt(20, 110) przy losie 0.5 to 65
    };
    for (const [instrument, mult] of [[null, 1], ['gitara', 1.5], ['skrzypce', 2.2], ['fortepian', 4]]) {
      const r = await busk(instrument);
      check(r.got === Math.floor(r.base * mult), `uliczny: ${instrument ?? 'śpiew'} daje ${r.got} (oczekiwano ${Math.floor(r.base * mult)})`);
    }
    console.log('  ok');
  }

  // ------------------------------------------------------------------ biznesy
  section('Biznesy: dochód pasywny, magazyn, ulepszenia');
  {
    const U = fresh('biz', 100_000_000);
    const T = 1_700_000_000_000;
    const H = biz.HOUR;
    const kiosk = data.BUSINESSES[0];

    const bought = biz.buyOrUpgrade(U.id, 'kiosk', T);
    check(bought.ok && bought.bought && wallet(U) === 100_000_000 - kiosk.price, 'biznes: zakup kiosku pobiera cenę');
    check(biz.pending(U.id, T + 3 * H).total === kiosk.hourly * 3, 'biznes: po 3 h kiosk zarobił 3× godzinowy dochód');
    check(biz.pending(U.id, T + 20 * H).total === kiosk.hourly * 8, 'biznes: magazyn zatrzymuje się na 8 h');
    check(biz.pending(U.id, T + 20 * H).parts[0].full, 'biznes: pełny magazyn jest oznaczony');

    db.addItem(U.id, 'ksiegowy');
    check(biz.pending(U.id, T + 20 * H).total === kiosk.hourly * 16, 'księgowy: magazyn 16 h');
    db.addItem(U.id, 'menedzer');
    check(biz.pending(U.id, T + 20 * H).total === Math.floor(kiosk.hourly * 1.2 * 16), 'menedżer: +20% dochodu');
    db.takeItem(U.id, 'ksiegowy', 1);
    db.takeItem(U.id, 'menedzer', 1);

    const before = wallet(U);
    const col = biz.collect(U.id, T + 3 * H);
    check(col.total === kiosk.hourly * 3 && wallet(U) === before + col.total, 'biznes: zbiór wypłaca zarobki');
    check(biz.pending(U.id, T + 3 * H).total === 0 && biz.collect(U.id, T + 3 * H).total === 0, 'biznes: po zbiorze licznik jest wyzerowany, drugi zbiór nic nie daje');

    // ulepszenia: koszt = cena × poziom × 1.5, zaległy dochód wypłacany po starej stawce
    let level = 1;
    let t = T + 3 * H;
    for (let k = 0; k < data.BUSINESS_MAX_LEVEL - 1; k++) {
      t += 2 * H;
      const w0 = wallet(U);
      const r = biz.buyOrUpgrade(U.id, 'kiosk', t);
      const cost = Math.floor(kiosk.price * level * 1.5);
      check(r.ok && r.cost === cost && r.level === level + 1, `biznes: ulepszenie ${level}→${level + 1} kosztuje ${cost}`);
      check(r.collected === kiosk.hourly * level * 2, `biznes: przy ulepszeniu wypłacono zaległe ${kiosk.hourly * level * 2} po starej stawce`);
      check(wallet(U) === w0 - cost + r.collected, 'biznes: portfel po ulepszeniu się zgadza');
      level++;
    }
    const maxed = biz.buyOrUpgrade(U.id, 'kiosk', t);
    check(!maxed.ok && maxed.reason === 'max', 'biznes: ostatni poziom to maksimum');
    check(biz.pending(U.id, t + H).total === kiosk.hourly * data.BUSINESS_MAX_LEVEL, 'biznes: maksymalny poziom daje wielokrotność dochodu');
    check(biz.hourlyTotal(U.id) === kiosk.hourly * data.BUSINESS_MAX_LEVEL, 'biznes: dochód godzinowy z poziomu');

    // brak pieniędzy: nic się nie zmienia
    const poor = fresh('biz_poor', 1000);
    const noBuy = biz.buyOrUpgrade(poor.id, 'pizzeria', T);
    check(!noBuy.ok && noBuy.reason === 'poor' && wallet(poor) === 1000 && db.getBusinesses(poor.id).length === 0, 'biznes: za mało pieniędzy → bez zmian');
    check(!biz.buyOrUpgrade(poor.id, 'nie_ma', T).ok, 'biznes: nieznany biznes');

    // komendy
    const C = fresh('biz_cmd', 100_000);
    check(ephemeral(await run('biznes', C, {}, 'zbierz')), '/biznes zbierz bez biznesu → komunikat');
    const buy = await run('biznes', C, { biznes: 'kiosk' }, 'kup');
    check(buy.replied && !ephemeral(buy) && wallet(C) === 100_000 - kiosk.price, '/biznes kup kiosk');
    const list = await run('biznes', C, {}, 'lista');
    check(/Kiosk z gazetami/.test(desc(list.replies[0])) && /poz\. \*\*1\/5\*\*/.test(desc(list.replies[0])), '/biznes lista pokazuje poziom');
    const none = await run('biznes', C, {}, 'zbierz');
    check(none.replied && !ephemeral(none), '/biznes zbierz zaraz po zakupie: bez błędu');
    const listE = list.replies[0].embeds[0].toJSON();
    check(listE.fields.every((f) => f.value.length <= 1024) && desc(list.replies[0]).length <= 4096, '/biznes lista mieści się w limitach');
    console.log('  ok');
  }

  // ------------------------------------------------------------------ farma
  section('Farma: sadzenie, wzrost w czasie, zbiory');
  {
    const U = fresh('farm', 1000);
    const T = 1_700_000_000_000;
    const carrot = data.FARM_CROPS[0];
    const slots = data.FARM_SLOTS;

    const r = farm.plant(U.id, 'marchew', null, T);
    check(r.ok && r.planted === slots && r.cost === carrot.cost * slots, `farma: sadzenie na wszystkich wolnych polach (${r.planted})`);
    check(wallet(U) === 1000 - r.cost, 'farma: pobrano koszt nasion');
    check(farm.status(U.id, T + 60_000).every((s) => s.state === 'growing' && s.left === carrot.minutes * 60_000 - 60_000), 'farma: po minucie uprawy jeszcze rosną');
    check(farm.harvest(U.id, T + 60_000).count === 0, 'farma: przed czasem nic nie da się zebrać');
    check(farm.plant(U.id, 'marchew', 1, T).reason === 'busy' && farm.plant(U.id, 'marchew', null, T).reason === 'full', 'farma: pola zajęte');
    const before = wallet(U);
    const h2 = farm.harvest(U.id, T + carrot.minutes * 60_000);
    check(h2.count === slots && h2.gain === carrot.yield * slots && wallet(U) === before + carrot.yield * slots, 'farma: po czasie zbiór wypłaca plon');
    check(farm.status(U.id, T + 999_999_999).every((s) => s.state === 'empty') && farm.harvest(U.id, T + 999_999_999).count === 0, 'farma: po zbiorze pola są puste');

    // częściowe fundusze, zły numer pola, nieznana uprawa
    const P = fresh('farm_poor', carrot.cost * 2 + 50);
    const part = farm.plant(P.id, 'marchew', null, T);
    check(part.ok && part.planted === 2 && part.skipped === slots - 2 && wallet(P) === 50, 'farma: starcza na 2 pola z 4');
    check(farm.plant(P.id, 'marchew', 2, T).reason === 'busy', 'farma: wybrane pole zajęte');
    check(farm.plant(P.id, 'marchew', 9, T).reason === 'slot' && farm.plant(P.id, 'nie_ma', null, T).reason === 'unknown', 'farma: zły numer pola i nieznana uprawa');
    check(farm.plant(fresh('farm_nomoney', 10).id, 'marchew', null, T).reason === 'poor', 'farma: brak pieniędzy na nasiona');
    const one = fresh('farm_one', 100_000);
    const single = farm.plant(one.id, 'ziemniak', 3, T);
    check(single.ok && single.planted === 1 && single.slots[0] === 3, 'farma: sadzenie na jednym wybranym polu');

    // podlewaczka skraca wzrost o 25%
    const W = fresh('farm_water', 1000);
    db.addItem(W.id, 'podlewaczka');
    check(farm.plant(W.id, 'marchew', 1, T).readyIn === Math.round(carrot.minutes * 60_000 * 0.75), 'farma: podlewaczka skraca wzrost o 25%');

    // komendy
    const C = fresh('farm_cmd', 1000);
    check(/puste/.test(desc((await run('farma', C, {}, 'pola')).replies[0])), '/farma pola');
    const plant = await run('farma', C, { uprawa: 'marchew' }, 'zasadz');
    check(plant.replied && !ephemeral(plant) && /Zasadzone/.test(plant.replies[0].embeds[0].toJSON().title) && wallet(C) === 1000 - carrot.cost * slots, '/farma zasadz');
    check(ephemeral(await run('farma', C, {}, 'zbierz')), '/farma zbierz przed czasem → komunikat');
    db.db.prepare('UPDATE farm SET ready_at = ? WHERE user_id = ?').run(Date.now() - 1, C.id);
    const harvest = await run('farma', C, {}, 'zbierz');
    check(harvest.replied && !ephemeral(harvest) && wallet(C) === 1000 - carrot.cost * slots + carrot.yield * slots, '/farma zbierz po czasie wypłaca plon');
    console.log('  ok');
  }

  // ------------------------------------------------------------------ zakupy nowych przedmiotów
  section('Zakupy nowych przedmiotów: wymagania, luksus, sprzedaż, podpowiedzi');
  {
    // Każdy przedmiot z wymaganiem: bez niego kupić się nie da, z nim tak.
    for (const [id, it] of Object.entries(data.ITEMS)) {
      if (!it.requires || !data.SHOP_ORDER.includes(id)) continue;
      const V = fresh(`req_${id}`, 1e13);
      const denied = await run('kup', V, { przedmiot: id });
      check(ephemeral(denied) && db.itemQty(V.id, id) === 0, `${id}: bez „${it.requires}” kupić się nie da`);
      db.addItem(V.id, it.requires);
      const w = wallet(V);
      const ok = await run('kup', V, { przedmiot: id });
      check(!ephemeral(ok) && db.itemQty(V.id, id) === 1 && wallet(V) === w - it.price, `${id}: z wymaganiem kupno działa i pobiera ${it.price}`);
    }

    const U = fresh('shop2', 1e13);
    const buy = (item) => run('kup', U, { przedmiot: item });
    for (const id of data.SHOP_CATEGORIES.find((c) => c.id === 'luksus').items) {
      const w = wallet(U);
      check(!ephemeral(await buy(id)) && db.itemQty(U.id, id) === 1 && wallet(U) === w - data.ITEMS[id].price, `luksus ${id}: kupiony`);
      check(ephemeral(await buy(id)) && db.itemQty(U.id, id) === 1, `luksus ${id}: drugiej sztuki nie da się kupić`);
    }
    const profile = JSON.stringify((await run('profil', U, {})).replies[0].embeds[0].toJSON());
    check(/Kolekcja/.test(profile) && /🪐/.test(profile), '/profil pokazuje kolekcję luksusu');
    db.addItem(U.id, 'kot');
    db.addItem(U.id, 'smok');
    check(/Zwierzaki/.test(JSON.stringify((await run('profil', U, {})).replies[0].embeds[0].toJSON())), '/profil pokazuje zwierzaki');
    const inv = await run('ekwipunek', U, {});
    const invText = JSON.stringify(inv.replies[0].embeds[0].toJSON());
    check(/Kolekcja/.test(invText) && /Ulepszenia i zwierzaki/.test(invText), '/ekwipunek grupuje kolekcję i zwierzaki');
    check(inv.replies[0].embeds[0].toJSON().fields.every((f) => f.value.length <= 1024), '/ekwipunek: pola mieszczą się w limicie');

    const sellW = wallet(U);
    await run('sprzedaj', U, { przedmiot: 'luks_okulary' });
    check(wallet(U) === sellW + Math.floor(data.ITEMS.luks_okulary.price * 0.5) && db.itemQty(U.id, 'luks_okulary') === 0, 'sprzedaż luksusu zwraca 50% ceny');

    // /uzyj podpowiada tylko to, co masz i da się użyć
    const A = fresh('ac');
    db.addItem(A.id, 'polisa_mala', 2);
    db.addItem(A.id, 'podrecznik');
    db.addItem(A.id, 'wedka');
    const ac = makeInteraction(A, { focused: '' });
    await commands.get('uzyj').autocomplete(ac);
    const values = ac.choices.map((c) => c.value).sort();
    check(values.join() === ['podrecznik', 'polisa_mala'].join(), `/uzyj autocomplete: tylko używalne i posiadane (${values.join()})`);
    check(ac.choices.find((c) => c.value === 'polisa_mala').name.includes('×2'), '/uzyj autocomplete pokazuje ilość');
    check(ephemeral(await run('uzyj', A, { przedmiot: 'wedka' })), '/uzyj: wędki nie da się „użyć”');
    // /kup podpowiada przedmioty ze sklepu, ale nigdy więcej niż 25
    const kc = makeInteraction(A, { focused: '' });
    await commands.get('kup').autocomplete(kc);
    check(kc.choices.length === 25 && kc.choices.every((c) => c.name.length <= 100), '/kup autocomplete: 25 podpowiedzi o poprawnej długości');
    const kc2 = makeInteraction(A, { focused: 'polisa' });
    await commands.get('kup').autocomplete(kc2);
    check(kc2.choices.length === 4 && kc2.choices.every((c) => c.value.startsWith('polisa')), '/kup autocomplete: filtruje po wpisanym tekście');

    // /mnozniki pokazuje uzbrojone ochrony
    await run('uzyj', A, { przedmiot: 'polisa_mala' });
    const mz = await run('mnozniki', A, {});
    check(/×1 \(zwrot stawki do/.test(JSON.stringify(mz.replies[0].embeds[0].toJSON())), '/mnozniki pokazuje uzbrojone ochrony');
    console.log('  ok');
  }

  // ------------------------------------------------------------------ prefiks dla nowych komend
  section('Prefiks „!” dla nowych komend i aliasów');
  {
    const makeSent = (m, payload) => {
      const s = {
        payload,
        edits: [],
        async edit(p) {
          s.edits.push(p);
          return s;
        },
        createMessageComponentCollector(o) {
          return (s.collector = new FakeCollector(o));
        },
      };
      m.sent.push(s);
      return s;
    };
    const makeMessage = (author, content, channelId = 'chanP') => {
      const m = {
        author,
        content,
        channelId,
        client: fakeClient,
        guild: { id: 'g1' },
        mentions: { users: new Map() },
        sent: [],
        channel: {
          id: channelId,
          async sendTyping() {},
          async send(p) {
            return makeSent(m, p);
          },
        },
        async reply(p) {
          return makeSent(m, p);
        },
      };
      return m;
    };
    const say = async (author, content, channelId) => {
      const m = makeMessage(author, content, channelId);
      const handled = await handleMessage(m, commands, { prefix: '!', throttleMs: 0 });
      return { m, handled, first: m.sent[0] };
    };
    const textOf = (s) => (typeof s.payload === 'string' ? s.payload : s.payload.content ?? '');
    const embedOf = (s) => (s.edits.length ? s.edits[s.edits.length - 1] : s.payload).embeds?.[0]?.toJSON();

    const P = makeUser('810000000000000001');
    db.setWallet(P.id, 1_000_000);

    let r = await say(P, '!zbierz');
    check(r.handled && /Nie masz jeszcze żadnego biznesu/.test(textOf(r.first)), '!zbierz → /biznes zbierz');
    r = await say(P, '!biznes kup kiosk');
    check(r.handled && wallet(P) === 1_000_000 - 20_000 && db.getBusinesses(P.id).length === 1, '!biznes kup kiosk');
    r = await say(P, '!biznes');
    check(/Twoje biznesy/.test(embedOf(r.first).title), '!biznes bez podkomendy pokazuje listę');
    r = await say(P, '!farma');
    check(/Twoja farma/.test(embedOf(r.first).title), '!farma bez podkomendy pokazuje pola');
    r = await say(P, '!farma zasadz marchew 2');
    const st = farm.status(P.id);
    check(r.handled && st[0].state === 'empty' && st[1].state === 'growing', '!farma zasadz marchew 2 sadzi tylko na polu 2');
    r = await say(P, '!sadz ziemniak');
    check(r.handled && farm.status(P.id).filter((s) => s.state === 'growing').length === data.FARM_SLOTS, '!sadz ziemniak zapełnia pozostałe pola');
    db.db.prepare('UPDATE farm SET ready_at = ? WHERE user_id = ?').run(Date.now() - 1, P.id);
    const w0 = wallet(P);
    r = await say(P, '!zbiory');
    check(r.handled && wallet(P) > w0, '!zbiory → /farma zbierz');

    resetLedger();
    r = await say(P, '!godzinna');
    check(r.handled && embedOf(r.first).title.includes('Premia godzinowa'), '!godzinna');
    r = await say(P, '!las');
    check(r.handled && embedOf(r.first).title.includes('Grzybobranie'), '!las');

    // gry: stawka i opcje w dowolnej kolejności
    let w = wallet(P);
    r = await say(P, '!kurczak 500 trudny');
    check(r.handled && /Trudny/.test(embedOf(r.first).title) && wallet(P) === w - 500, '!kurczak 500 trudny');
    await r.first.collector.stop('idle');
    await r.first.collector.settled();
    check(wallet(P) === w, '!kurczak: brak ruchu zwraca stawkę');
    r = await say(P, '!chicken 100');
    check(r.handled && /Średni/.test(embedOf(r.first).title), '!chicken 100 (alias, domyślny poziom)');
    await r.first.collector.stop('idle');
    await r.first.collector.settled();
    r = await say(P, '!wieza trudny 200');
    check(r.handled && /Trudny/.test(embedOf(r.first).title), '!wieza trudny 200 (poziom przed stawką)');
    await r.first.collector.stop('idle');
    await r.first.collector.settled();
    resetLedger();
    w = wallet(P);
    r = await say(P, '!limbo 1k 10');
    check(r.handled && /Limbo/.test(embedOf(r.first).title) && wallet(P) === w + ledger.net + ledger.levelBonus, '!limbo 1k 10');
    r = await say(P, '!szansa 100 50 powyzej');
    check(r.handled && /Szansa/.test(embedOf(r.first).title), '!szansa 100 50 powyzej');
    r = await say(P, '!rr 500 2');
    check(r.handled && /Rosyjska ruletka/.test(embedOf(r.first).title) && /Naboje: \*\*2\/6\*\*/.test(embedOf(r.first).description), '!rr 500 2 (alias, dwa naboje)');
    r = await say(P, '!plinko 100 wysokie');
    check(r.handled && /Plinko – .*[Ww]ysokie/.test(embedOf(r.first).title), '!plinko 100 wysokie');
    r = await say(P, '!limbo 1k');
    check(/Brakuje: cel/.test(textOf(r.first)) && /Użycie: `!limbo/.test(textOf(r.first)), '!limbo bez celu: podpowiedź użycia');
    r = await say(P, '!jackpot 100', 'chanJackpot');
    check(r.handled && /Jackpot/.test(embedOf(r.first).title), '!jackpot 100');
    await r.first.collector.stop('time');
    await r.first.collector.settled();
    console.log('  ok');
  }
};
