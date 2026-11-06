// Sprawdza zwrot (RTP) gier: wartość oczekiwana wypłaty na 1 postawioną monetę.
// Uruchom: node --disable-warning=ExperimentalWarning test/rtp.js
process.env.DB_PATH = ':memory:';

const sloty = require('../src/commands/sloty');
const kolo = require('../src/commands/kolo');
const zdrapka = require('../src/commands/zdrapka');
const wyscigi = require('../src/commands/wyscigi');
const kosci = require('../src/commands/kosci');
const ruletka = require('../src/commands/ruletka');
const miny = require('../src/commands/miny');
const crash = require('../src/commands/crash');
const hilo = require('../src/commands/hilo');
const blackjack = require('../src/commands/blackjack');
const crates = require('../src/lib/crates');
const { ITEMS } = require('../src/lib/data');
const { randInt } = require('../src/lib/util');

const pct = (x) => `${(x * 100).toFixed(2)}%`;
const rows = [];
const add = (name, rtp, note = '') => rows.push({ gra: name, RTP: pct(rtp), uwagi: note });

// sloty – dokładnie
{
  const W = sloty.SYMBOLS.reduce((s, x) => s + x.w, 0);
  let ev = 0;
  for (const a of sloty.SYMBOLS) for (const b of sloty.SYMBOLS) for (const c of sloty.SYMBOLS) {
    const p = (a.w / W) * (b.w / W) * (c.w / W);
    ev += p * sloty.multiplier([a, b, c]).mult;
  }
  // losowy jackpot nadpisuje wynik bębnów
  add('sloty', (1 - sloty.JACKPOT.chance) * ev + sloty.JACKPOT.chance * sloty.JACKPOT.mult, `baza ${pct(ev)} + jackpot x${sloty.JACKPOT.mult} co 1/${Math.round(1 / sloty.JACKPOT.chance)}`);
}

// skrzynie – dokładnie: wartość oczekiwana nagrody / cena (przedmioty po cenie skupu)
for (const id of Object.keys(crates.CRATES)) {
  add(`skrzynia: ${ITEMS[id].name}`, crates.expectedValue(id) / ITEMS[id].price, 'przedmioty po cenie skupu');
}

// koło – dokładnie
{
  const W = kolo.SEGMENTS.reduce((s, x) => s + x.w, 0);
  add('kolo', kolo.SEGMENTS.reduce((s, x) => s + (x.w / W) * x.mult, 0));
}

// zdrapka – dokładnie
{
  const W = zdrapka.OUTCOMES.reduce((s, x) => s + x.w, 0);
  add('zdrapka', zdrapka.OUTCOMES.reduce((s, x) => s + (x.w / W) * x.mult, 0));
}

// wyścigi – dokładnie, każdy koń
for (const h of wyscigi.HORSES) add(`wyscigi: ${h.name}`, h.p * h.mult);

// kości – dokładnie
for (const [key, b] of Object.entries(kosci.BETS)) {
  let wins = 0;
  for (let d1 = 1; d1 <= 6; d1++) for (let d2 = 1; d2 <= 6; d2++) if (b.wins(d1 + d2)) wins++;
  add(`kosci: ${key}`, (wins / 36) * b.mult);
}

// ruletka – dokładnie
for (const [key, t] of Object.entries(ruletka.TYPES)) {
  let wins = 0;
  for (let n = 0; n <= 36; n++) if (t.wins(n)) wins++;
  add(`ruletka: ${key}`, (wins / 37) * t.mult);
}
add('ruletka: numer', (1 / 37) * 36);

add('moneta', 0.5 * 1.95);
add('knp', (1 / 3) * 1.9 + 1 / 3);

// miny – dokładnie: P(przeżycia k pól) × mnożnik. Pomijamy k, przy których zadziałał limit x1000
// (to tylko wyczyszczenie prawie całej planszy, szansa rzędu 1 na tysiące).
for (const m of [1, 3, 5, 12]) {
  const rtps = [];
  for (let k = 1; k <= miny.TILES - m; k++) {
    let surv = 1;
    for (let i = 0; i < k; i++) surv *= (miny.TILES - m - i) / (miny.TILES - i);
    if (1 / surv > 1000) break;
    rtps.push(surv * miny.multiplier(k, m));
  }
  add(`miny (${m} min)`, Math.min(...rtps), `zakres ${pct(Math.min(...rtps))}–${pct(Math.max(...rtps))} zależnie od momentu wypłaty`);
}

// crash – symulacja: wypłata przy stałym mnożniku
{
  const N = 400000;
  for (const target of [1.5, 2, 5, 10]) {
    let sum = 0;
    for (let i = 0; i < N; i++) {
      // gracz wypłaca przy pierwszym ticku, którego mnożnik >= target
      let tick = 1;
      while (crash.multAt(tick) < target) tick++;
      const m = crash.multAt(tick);
      const c = crash.crashPoint();
      if (m < c) sum += m;
    }
    add(`crash (cel x${target})`, sum / N);
  }
}

// hilo – symulacja: zawsze bardziej prawdopodobna strona, wypłata po 1/3/6 trafieniach
{
  const N = 300000;
  for (const goal of [1, 3, 6]) {
    let sum = 0;
    for (let i = 0; i < N; i++) {
      let rank = randInt(2, 14);
      let raw = 1;
      let ok = true;
      for (let s = 0; s < goal; s++) {
        const hi = hilo.pHigher(rank);
        const lo = hilo.pLower(rank);
        const high = hi >= lo;
        const p = high ? hi : lo;
        const next = randInt(2, 14);
        if (high ? next > rank : next < rank) {
          raw /= p;
          rank = next;
        } else {
          ok = false;
          break;
        }
      }
      if (ok) sum += hilo.totalMult(raw);
    }
    add(`hilo (wypłata po ${goal})`, sum / N);
  }
}

// blackjack – symulacja strategii podstawowej (bez splitów, które gra nie oferuje) na świeżej talii jak w grze
{
  const suits = ['♠️', '♥️', '♦️', '♣️'];
  const ranks = ['A', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'J', 'Q', 'K'];
  const cardVal = (c) => (c.r === 'A' ? 11 : ['J', 'Q', 'K'].includes(c.r) ? 10 : Number(c.r));
  const hardTotal = (cards) => cards.reduce((s, c) => s + (c.r === 'A' ? 1 : cardVal(c)), 0);
  const isSoft = (cards) => cards.some((c) => c.r === 'A') && hardTotal(cards) + 10 <= 21;

  // 'H' = dobierz, 'S' = pas, 'D' = podwój (jeśli się nie da: dobierz)
  function decide(cards, up, first) {
    const hard = hardTotal(cards);
    const soft = isSoft(cards);
    const total = soft ? hard + 10 : hard;
    let a;
    if (soft) {
      if (total >= 19) a = 'S';
      else if (total === 18) a = up >= 3 && up <= 6 ? 'D' : [2, 7, 8].includes(up) ? 'S' : 'H';
      else if (total === 17) a = up >= 3 && up <= 6 ? 'D' : 'H';
      else if (total >= 15) a = up >= 4 && up <= 6 ? 'D' : 'H';
      else a = up >= 5 && up <= 6 ? 'D' : 'H';
    } else if (total >= 17) a = 'S';
    else if (total >= 13) a = up <= 6 ? 'S' : 'H';
    else if (total === 12) a = up >= 4 && up <= 6 ? 'S' : 'H';
    else if (total === 11) a = up <= 10 ? 'D' : 'H';
    else if (total === 10) a = up <= 9 ? 'D' : 'H';
    else if (total === 9) a = up >= 3 && up <= 6 ? 'D' : 'H';
    else a = 'H';
    if (a === 'D' && !first) a = soft && total === 18 ? 'S' : 'H';
    return a;
  }

  const N = 400000;
  let sum = 0;
  let staked = 0;
  for (let i = 0; i < N; i++) {
    const deck = suits.flatMap((s) => ranks.map((r) => ({ r, s })));
    for (let j = deck.length - 1; j > 0; j--) {
      const k = Math.floor(Math.random() * (j + 1));
      [deck[j], deck[k]] = [deck[k], deck[j]];
    }
    const p = [deck.pop(), deck.pop()];
    const d = [deck.pop(), deck.pop()];
    let bet = 1;
    staked += 1;
    const pb = blackjack.isBlackjack(p);
    const dbj = blackjack.isBlackjack(d);
    if (pb || dbj) {
      sum += pb && dbj ? 1 : pb ? 2.5 : 0;
      continue;
    }
    let first = true;
    for (;;) {
      const a = decide(p, cardVal(d[0]), first);
      if (a === 'S') break;
      if (a === 'D') {
        bet = 2;
        staked += 1;
        p.push(deck.pop());
        break;
      }
      p.push(deck.pop());
      first = false;
      if (blackjack.handValue(p) >= 21) break;
    }
    const pv = blackjack.handValue(p);
    if (pv > 21) continue;
    while (blackjack.handValue(d) < 17) d.push(deck.pop());
    const dv = blackjack.handValue(d);
    if (dv > 21 || pv > dv) sum += 2 * bet;
    else if (pv === dv) sum += bet;
  }
  add('blackjack (strategia podstawowa)', sum / staked, 'bez splitów; z doublem');
}

// --- nowe gry ---
const plinko = require('../src/commands/plinko');
const limbo = require('../src/commands/limbo');
const szansa = require('../src/commands/szansa');
const rewolwer = require('../src/commands/rewolwer');
const kurczak = require('../src/commands/kurczak');
const wieza = require('../src/commands/wieza');
const { stepMultiplier, stepsLimit } = require('../src/lib/stepgame');

// plinko – dokładnie (rozkład dwumianowy 12 rzutów monetą)
{
  const choose = (n, k) => {
    let r = 1;
    for (let i = 1; i <= k; i++) r = (r * (n - k + i)) / i;
    return r;
  };
  for (const [risk, mults] of Object.entries(plinko.MULTS)) {
    add(`plinko: ${risk}`, mults.reduce((s, m, i) => s + (m * choose(plinko.ROWS, i)) / 2 ** plinko.ROWS, 0), `maks. x${mults[0]}`);
  }
}

// limbo – symulacja: wygrana, gdy wynik dobije do celu
{
  const N = 3_000_000;
  for (const target of [1.5, 2, 10, 100, 1000]) {
    let wins = 0;
    for (let i = 0; i < N; i++) if (limbo.roll() >= target) wins++;
    add(`limbo (cel x${target})`, (wins / N) * target, 'symulacja, dla x1000 z szumem ok. ±3 pkt');
  }
}

// szansa – dokładnie: szansa wygranej to chance/100
for (const c of [0.5, 1, 5, 10, 25, 33.33, 50, 75, 90, 95]) add(`szansa ${c}%`, (c / 100) * szansa.multiplierFor(c), `wypłata x${szansa.multiplierFor(c)}`);

// rewolwer – dokładnie
for (let b = 1; b <= 5; b++) add(`rewolwer (${b} naboi)`, ((6 - b) / 6) * rewolwer.multiplierFor(b), `wypłata x${rewolwer.multiplierFor(b)}`);

// kurczak i wieża – dokładnie: strategia „stój po n krokach” daje pSafe^n × mnożnik(n); bierzemy najlepszą dla gracza
for (const [game, levels, pOf, limitOf] of [
  ['kurczak', kurczak.LEVELS, (l) => 1 - l.d, (l) => l.max],
  ['wieza', wieza.LEVELS, (l) => (l.cols - l.bad) / l.cols, () => 14],
]) {
  for (const [key, l] of Object.entries(levels)) {
    const p = pOf(l);
    const max = stepsLimit(p, limitOf(l));
    let best = 0;
    let bestN = 0;
    for (let n = 1; n <= max; n++) {
      const r = p ** n * stepMultiplier(p, n);
      if (r > best) [best, bestN] = [r, n];
    }
    add(`${game}: ${key}`, best, `najlepsza strategia: stój po ${bestN} krokach, maks. x${stepMultiplier(p, max)}`);
  }
}

console.table(rows);

// Bramka: żadna gra nie może oddawać graczowi więcej, niż wpłaca (zwrot 100% i więcej = maszynka do drukowania pieniędzy).
const bad = rows.filter((r) => parseFloat(r.RTP) >= 100 && !/^skrzynia|^blackjack/.test(r.gra));
if (bad.length) {
  console.error(`\n❌ ${bad.length} gier ma zwrot ≥ 100%:`);
  for (const r of bad) console.error(`  • ${r.gra}: ${r.RTP}`);
  process.exitCode = 1;
} else {
  console.log('\n✅ Zwrot każdej gry jest poniżej 100%.');
}
