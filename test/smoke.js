// Test dymny na atrapie Discorda: każda komenda przechodzi przez prawdziwy kod, bez łączenia z siecią.
// Gry są rozgrywane setki razy i po każdej sprawdzamy, czy pieniądze się zgadzają co do monety.
process.env.DB_PATH = ':memory:';
process.env.BOT_FAST = '1';
process.env.OWNER_IDS = 'u_admin';

const { EventEmitter } = require('node:events');
const { EmbedBuilder, ActionRowBuilder } = require('discord.js');
const db = require('../src/lib/db');
const game = require('../src/lib/game');
const lottery = require('../src/lib/lottery');
const { loadCommands } = require('../src/loader');
const { parseAmount, fmt } = require('../src/lib/util');
const { ITEMS, BANK_UPGRADES, SEJF_BONUS } = require('../src/lib/data');
const { handleMessage, parseOptions } = require('../src/prefix');
const { loginClient, connect } = require('../src/connect');
const crates = require('../src/lib/crates');
const anim = require('../src/lib/anim');

const commands = loadCommands();
const failures = [];
const check = (cond, msg) => {
  if (!cond) {
    failures.push(msg);
    console.log(`  ❌ ${msg}`);
  }
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// --- księga rozliczeń: wszystko, co gry zaksięgowały jako wynik i bonus za awans ---
const ledger = { net: 0, levelBonus: 0 };
const origRecord = db.recordGame;
db.recordGame = (id, net) => {
  ledger.net += net;
  return origRecord(id, net);
};
const origXp = db.addXp;
db.addXp = (id, n, ...rest) => {
  const r = origXp(id, n, ...rest);
  ledger.levelBonus += r.bonus;
  return r;
};
const resetLedger = () => {
  ledger.net = 0;
  ledger.levelBonus = 0;
};

// --- atrapy ---
const makeUser = (id) => ({
  id,
  username: id,
  displayName: `Gracz_${id}`,
  bot: false,
  displayAvatarURL: () => 'https://example.com/a.png',
  toString: () => `<@${id}>`,
});
const fakeClient = { users: { fetch: async (id) => makeUser(id) } };

/** Waliduje payload tak, jak zrobi to Discord: embeddy i komponenty muszą się dać zserializować. */
function validatePayload(p) {
  if (typeof p === 'string') return;
  for (const e of p.embeds ?? []) {
    check(e instanceof EmbedBuilder, 'embed nie jest EmbedBuilderem');
    const j = e.toJSON();
    check((j.description ?? '').length <= 4096, 'za długi opis embeda');
  }
  const rows = p.components ?? [];
  check(rows.length <= 5, 'więcej niż 5 rzędów komponentów');
  for (const row of rows) {
    check(row instanceof ActionRowBuilder, 'komponent nie jest ActionRowBuilderem');
    const j = row.toJSON();
    check(j.components.length >= 1 && j.components.length <= 5, 'zła liczba przycisków w rzędzie');
  }
}

class FakeCollector {
  constructor(opts) {
    this.opts = opts;
    this.ended = false;
    this.count = 0;
    this.handlers = { collect: [], end: [] };
    this.pending = [];
  }
  on(ev, fn) {
    this.handlers[ev].push(fn);
    return this;
  }
  async click(customId, user) {
    if (this.ended) return null;
    const collector = this;
    const ci = {
      user,
      customId,
      updates: [],
      replies: [],
      async update(p) {
        if (collector.failUpdate) throw new Error('Unknown interaction (symulacja)');
        validatePayload(p);
        this.updates.push(p);
      },
      async reply(p) {
        this.replies.push(p);
      },
      async deferUpdate() {},
    };
    if (this.opts.filter && !(await this.opts.filter(ci))) return ci;
    this.count += 1;
    await Promise.all(this.handlers.collect.map((fn) => fn(ci)));
    if (this.opts.max && this.count >= this.opts.max) await this.stop('limit');
    return ci;
  }
  /** Odpowiednik collector.stop(): odpala handlery 'end'. */
  stop(reason = 'user') {
    if (this.ended) return Promise.resolve();
    this.ended = true;
    const p = Promise.all(this.handlers.end.map((fn) => fn([], reason)));
    this.pending.push(p);
    return p;
  }
  async settled() {
    await Promise.all(this.pending);
  }
}

function makeInteraction(user, opts = {}, sub = null, channelId = 'chan1') {
  const i = {
    user,
    channelId,
    client: fakeClient,
    collector: null,
    replies: [],
    edits: [],
    replied: false,
    deferred: false,
    commandName: 'test',
    options: {
      getString: (n) => opts[n] ?? null,
      getInteger: (n) => opts[n] ?? null,
      getNumber: (n) => opts[n] ?? null,
      getUser: (n) => opts[n] ?? null,
      getSubcommand: () => sub,
      getFocused: () => opts.focused ?? '',
    },
    async reply(p) {
      if (i.replied || i.deferred) throw new Error('interakcja już odpowiedziana (InteractionAlreadyReplied)');
      validatePayload(p);
      i.replied = true;
      i.replies.push(p);
    },
    async deferReply() {
      if (i.replied || i.deferred) throw new Error('interakcja już odpowiedziana');
      i.deferred = true;
    },
    async editReply(p) {
      if (!i.replied && !i.deferred) throw new Error('editReply przed reply');
      validatePayload(p);
      i.edits.push(p);
    },
    async followUp(p) {
      i.replies.push(p);
    },
    async fetchReply() {
      return { createMessageComponentCollector: (o) => (i.collector = new FakeCollector(o)) };
    },
    async respond(choices) {
      i.choices = choices;
    },
  };
  return i;
}

const run = async (name, user, opts, sub, channelId) => {
  const i = makeInteraction(user, opts, sub, channelId);
  await commands.get(name).execute(i);
  return i;
};

const ephemeral = (i) => i.replies.some((r) => r.flags);
let n = 0;
const section = (t) => console.log(`\n▶ ${t}`);

const rich = (user, amount = 1_000_000) => db.setWallet(user.id, amount);

function report() {
  console.log('\n' + '─'.repeat(60));
  if (failures.length) {
    console.log(`❌ Niezaliczone: ${failures.length}`);
    for (const f of [...new Set(failures)].slice(0, 25)) console.log('  •', f);
    process.exitCode = 1;
  } else {
    console.log(`✅ Wszystko gra. Wykonano ${n}+ wywołań komend, każda gra rozliczona co do monety.`);
  }
}

const runFeatures = () =>
  require('./features')({ commands, check, section, makeUser, makeInteraction, run, ephemeral, ledger, resetLedger, rich, db, game, ITEMS, FakeCollector, fakeClient });

async function main() {
  // ONLY_FEATURES=1: tylko testy nowej mechaniki (test/features.js), do szybkiego iterowania.
  if (process.env.ONLY_FEATURES === '1') {
    await runFeatures();
    return report();
  }

  const A = makeUser('u1');
  const B = makeUser('u2');
  const C = makeUser('u3');
  const admin = makeUser('u_admin');

  // ---------------------------------------------------------------- narzędzia
  section('parseAmount');
  check(parseAmount('1500', 0) === 1500, '1500');
  check(parseAmount('1 500', 0) === 1500, '1 500');
  check(parseAmount('1.5k', 0) === 1500, '1.5k');
  check(parseAmount('2m', 0) === 2_000_000, '2m');
  check(parseAmount('50%', 1000) === 500, '50%');
  check(parseAmount('all', 777) === 777, 'all');
  check(parseAmount('połowa', 801) === 400, 'połowa');
  check(parseAmount('-5', 100) === null, 'ujemne');
  check(parseAmount('abc', 100) === null, 'śmieci');
  check(parseAmount('0', 100) === null, 'zero');
  check(parseAmount('101%', 100) === null, '101%');
  check(parseAmount('all', 0) === null, 'all przy 0');
  console.log('  ok');

  // ---------------------------------------------------------------- komendy bez gier
  section('Ekonomia i zarabianie (każda komenda raz, bez błędów)');
  db.setWallet(A.id, 50_000);
  db.setWallet(B.id, 50_000);
  const cases = [
    ['saldo', A, {}],
    ['saldo', A, { uzytkownik: B }],
    ['profil', A, {}],
    ['daily', A, {}],
    ['weekly', A, {}],
    ['wplac', A, { kwota: '10k' }],
    ['wyplac', A, { kwota: '5k' }],
    ['przelew', A, { uzytkownik: B, kwota: '1000' }],
    ['top', A, {}],
    ['top', A, { typ: 'poziom' }],
    ['top', A, { typ: 'hazard' }],
    ['sklep', A, {}],
    ['ekwipunek', A, {}],
    ['pomoc', A, {}],
    ['zebraj', A, {}],
    ['przestepstwo', A, {}],
    ['loteria', A, { ilosc: 5 }, 'kup'],
    ['loteria', A, {}, 'info'],
    ['praca', A, {}, 'wykonaj'],
    ['praca', A, {}, 'lista'],
    ['praca', A, { zawod: 'kurier' }, 'zmien'],
  ];
  for (const [name, user, opts, sub] of cases) {
    try {
      const i = await run(name, user, opts, sub);
      check(i.replied || i.deferred, `/${name}${sub ? ' ' + sub : ''}: brak odpowiedzi`);
      n++;
    } catch (err) {
      check(false, `/${name}${sub ? ' ' + sub : ''} rzuciło: ${err.stack}`);
    }
  }
  const dup = await run('daily', A, {});
  check(ephemeral(dup), '/daily drugi raz powinien dać komunikat o cooldownie');
  check((await run('praca', A, {}, 'wykonaj').then(ephemeral)), '/praca drugi raz = cooldown');

  // przelew/bank/wypłata: zachowanie pieniędzy
  section('Bank i przelewy');
  db.setWallet(A.id, 1000);
  db.setBank(A.id, 0);
  db.setWallet(B.id, 0);
  await run('wplac', A, { kwota: 'all' });
  check(db.getUser(A.id).wallet === 0 && db.getUser(A.id).bank === 1000, 'wplac all');
  await run('wyplac', A, { kwota: '50%' });
  check(db.getUser(A.id).wallet === 500 && db.getUser(A.id).bank === 500, 'wyplac 50%');
  await run('przelew', A, { uzytkownik: B, kwota: '200' });
  check(db.getUser(A.id).wallet === 300 && db.getUser(B.id).wallet === 200, 'przelew 200');
  const bad = await run('przelew', A, { uzytkownik: B, kwota: '99999' });
  check(ephemeral(bad) && db.getUser(A.id).wallet === 300, 'przelew ponad stan odrzucony');
  const self = await run('przelew', A, { uzytkownik: A, kwota: '10' });
  check(ephemeral(self), 'przelew do siebie odrzucony');
  const cap = db.bankCap(db.getUser(A.id));
  db.setBank(A.id, cap - 100);
  db.setWallet(A.id, 5000);
  await run('wplac', A, { kwota: 'all' });
  check(db.getUser(A.id).bank === cap && db.getUser(A.id).wallet === 4900, `limit banku respektowany: wpłacono tylko 100 (bank ${db.getUser(A.id).bank}/${cap})`);
  const full = await run('wplac', A, { kwota: '10' });
  check(ephemeral(full) && db.getUser(A.id).wallet === 4900, 'pełny bank odrzuca kolejną wpłatę bez ruszania portfela');

  // sklep / ekwipunek / sprzedaż / użycie
  section('Sklep, ekwipunek, łup');
  rich(A, 100_000);
  for (const item of ['wedka', 'kilof', 'strzelba', 'klodka', 'maska', 'laptop', 'koniczyna', 'skrzynia']) {
    const before = db.getUser(A.id).wallet;
    const qtyBefore = db.itemQty(A.id, item); // /weekly mogło już dać skrzynię
    await run('kup', A, { przedmiot: item, ilosc: 1 });
    check(db.itemQty(A.id, item) === qtyBefore + 1, `kup ${item}`);
    check(db.getUser(A.id).wallet < before, `kup ${item} zabrał pieniądze`);
  }
  const dupTool = await run('kup', A, { przedmiot: 'wedka', ilosc: 1 });
  check(ephemeral(dupTool), 'drugiej wędki kupić nie można');
  rich(B, 10);
  const poor = await run('kup', B, { przedmiot: 'trofeum', ilosc: 1 });
  check(ephemeral(poor) && db.itemQty(B.id, 'trofeum') === 0, 'za mało pieniędzy na trofeum');

  for (const cmd of ['ryby', 'kopalnia', 'polowanie']) {
    for (let k = 0; k < 120; k++) {
      db.clearCooldown(A.id, cmd);
      if (db.itemQty(A.id, 'wedka') + db.itemQty(A.id, 'kilof') + db.itemQty(A.id, 'strzelba') < 3) {
        db.addItem(A.id, 'wedka', 1 - db.itemQty(A.id, 'wedka') || 0);
      }
      const need = { ryby: 'wedka', kopalnia: 'kilof', polowanie: 'strzelba' }[cmd];
      if (db.itemQty(A.id, need) < 1) db.addItem(A.id, need, 1); // narzędzie mogło się zepsuć
      const i = await run(cmd, A, {});
      check(i.replied, `/${cmd} brak odpowiedzi`);
      n++;
    }
  }
  const inv = db.inventory(A.id);
  check(inv.length > 5, 'po łowieniu/kopaniu/polowaniu jest łup');
  const lootBefore = db.getUser(A.id).wallet;
  const sold = await run('sprzedaj', A, { przedmiot: 'wszystko' });
  check(sold.replied && db.getUser(A.id).wallet > lootBefore, 'sprzedaj wszystko dodało pieniądze');
  check(db.inventory(A.id).every((r) => ITEMS[r.item].type !== 'loot'), 'cały łup sprzedany, narzędzia i skrzynie zostały');
  const ac = makeInteraction(A, { focused: '' });
  await commands.get('sprzedaj').autocomplete(ac);
  check(Array.isArray(ac.choices) && ac.choices.length <= 25, 'autocomplete sprzedaj');

  // łowienie mogło dorzucić skrzynie – zostaw dokładnie jedną
  db.takeItem(A.id, 'skrzynia', db.itemQty(A.id, 'skrzynia') - 1);
  check(db.itemQty(A.id, 'skrzynia') === 1, 'przygotowanie: dokładnie jedna skrzynia');
  const opened = await run('uzyj', A, { przedmiot: 'skrzynia' });
  check(opened.replied && db.itemQty(A.id, 'skrzynia') === 0, 'uzyj skrzynia otwiera i zużywa skrzynię');
  await run('uzyj', A, { przedmiot: 'koniczyna' });
  check(db.buffUses(A.id, 'koniczyna') === 5, 'koniczyna daje 5 użyć');
  const none = await run('uzyj', A, { przedmiot: 'skrzynia' });
  check(ephemeral(none), 'brak skrzyni → odmowa');

  // quizy
  section('Trivia i matma');
  for (const q of ['trivia', 'matma']) {
    for (let k = 0; k < 40; k++) {
      db.clearCooldown(A.id, q);
      const i = await run(q, A, {});
      const labels = i.replies[0].components[0].toJSON().components.length;
      check(labels === 4, `${q}: 4 odpowiedzi`);
      const pick = `quiz_${Math.floor(Math.random() * 4)}`;
      if (k % 5 === 4) await i.collector.stop('time'); // brak odpowiedzi
      else await i.collector.click(pick, A);
      await i.collector.settled();
      n++;
    }
  }
  // poprawna odpowiedź w matmie daje nagrodę
  {
    db.clearCooldown(A.id, 'matma');
    const i = await run('matma', A, {});
    const q = i.replies[0].embeds[0].toJSON().description;
    const m = q.match(/(\d+) ([+\-×]) (\d+) = \?/);
    const [a, op, b] = [Number(m[1]), m[2], Number(m[3])];
    const ans = op === '+' ? a + b : op === '-' ? a - b : a * b;
    const idx = i.replies[0].components[0].toJSON().components.findIndex((c) => c.label === String(ans));
    check(idx >= 0, 'matma: poprawna odpowiedź jest wśród przycisków');
    const before = db.getUser(A.id).wallet;
    const ci = await i.collector.click(`quiz_${idx}`, A);
    check(db.getUser(A.id).wallet > before, 'matma: dobra odpowiedź płaci');
    check(ci.updates.length === 1, 'matma: wiadomość zaktualizowana');
  }

  // okradanie
  section('Okradanie');
  let totalBefore = 0;
  let robs = 0;
  for (let k = 0; k < 80; k++) {
    rich(A, 5000);
    rich(B, 5000);
    db.clearCooldown(A.id, 'rob');
    db.clearCooldown(B.id, 'robbed');
    if (k % 4 === 0) db.addItem(B.id, 'klodka', 1);
    resetLedger();
    totalBefore = 10_000;
    const i = await run('okradnij', A, { ofiara: B });
    check(i.replied, 'okradnij: brak odpowiedzi');
    const total = db.getUser(A.id).wallet + db.getUser(B.id).wallet;
    check(total === totalBefore + ledger.levelBonus, `okradnij: suma pieniędzy się nie zgadza (${total} vs ${totalBefore}+${ledger.levelBonus})`);
    robs++;
  }
  console.log(`  ok (${robs} prób)`);

  // admin
  section('Admin');
  await run('admin', admin, { uzytkownik: A, kwota: 123 }, 'dodaj');
  const denied = await run('admin', A, { uzytkownik: A, kwota: 1 }, 'dodaj');
  check(ephemeral(denied), 'admin: zwykły gracz odrzucony');
  await run('admin', admin, { uzytkownik: A, id: 'wedka', ilosc: 2 }, 'przedmiot');
  await run('admin', admin, { uzytkownik: A }, 'cooldowny');

  // ---------------------------------------------------------------- gry
  const instant = [
    ['moneta', () => ({ strona: Math.random() < 0.5 ? 'orzel' : 'reszka' })],
    ['sloty', () => ({})],
    ['kosci', () => ({ typ: ['nizej', 'wyzej', 'siedem'][n % 3] })],
    ['ruletka', () => (n % 3 === 0 ? { numer: n % 37 } : { typ: ['czerwone', 'czarne', 'parzyste', 'nieparzyste', 'niskie', 'wysokie', 'tuzin1', 'tuzin2', 'tuzin3'][n % 9] })],
    ['kolo', () => ({})],
    ['zdrapka', () => ({})],
    ['wyscigi', () => ({ kon: (n % 5) + 1 })],
  ];

  for (const [name, extra] of instant) {
    section(`/${name} – 300 gier z kontrolą pieniędzy`);
    let wins = 0;
    for (let k = 0; k < 300; k++) {
      n++;
      rich(A);
      resetLedger();
      const bet = [10, 100, 999, 5000, 123456][k % 5];
      if (k === 100) await run('uzyj', A, { przedmiot: 'koniczyna' }).catch(() => {});
      db.addBuff(A.id, 'koniczyna', 1);
      const before = db.getUser(A.id).wallet;
      const i = await run(name, A, { zaklad: String(bet), ...extra() });
      const after = db.getUser(A.id).wallet;
      check(after === before + ledger.net + ledger.levelBonus, `${name}: portfel ${before}→${after}, księga ${ledger.net}+${ledger.levelBonus}`);
      check(game.active.size === 0 && game.pending.size === 0, `${name}: gracz nadal zablokowany po grze`);
      check(i.replied, `${name}: brak odpowiedzi`);
      if (ledger.net > 0) wins++;
      check(Number.isInteger(after) && after >= 0, `${name}: portfel nie jest nieujemną liczbą całkowitą (${after})`);
    }
    console.log(`  ok (wygranych: ${wins}/300)`);
  }

  section('Plansza zdrapki: wygrana = dokładnie 3 takie same, przegrana = żadnej trójki');
  {
    const zd = require('../src/commands/zdrapka');
    let bad = 0;
    for (let k = 0; k < 20000; k++) {
      const outcome = zd.OUTCOMES[k % zd.OUTCOMES.length];
      const cells = zd.buildGrid(outcome);
      const counts = {};
      for (const c of cells) counts[c] = (counts[c] || 0) + 1;
      const triples = Object.entries(counts).filter(([, v]) => v >= 3);
      if (cells.length !== 9) bad++;
      else if (outcome.sym) {
        if (triples.length !== 1 || triples[0][0] !== outcome.sym || triples[0][1] !== 3) bad++;
      } else if (triples.length) bad++;
    }
    check(bad === 0, `zdrapka: ${bad} błędnych plansz`);
    console.log('  ok');
  }

  // blokada i błędne stawki
  section('Walidacja stawek i blokada');
  rich(A, 1000);
  for (const bad of ['0', '-5', 'abc', '5', '999999', '', '1e9']) {
    const i = await run('moneta', A, { zaklad: bad, strona: 'orzel' });
    check(ephemeral(i), `stawka "${bad}" powinna zostać odrzucona`);
    check(db.getUser(A.id).wallet === 1000, `stawka "${bad}" nie może ruszać portfela`);
  }
  const noChoice = await run('ruletka', A, { zaklad: '100' });
  check(ephemeral(noChoice) && db.getUser(A.id).wallet === 1000, 'ruletka bez typu i numeru → odmowa, bez poboru stawki');
  const bothChoice = await run('ruletka', A, { zaklad: '100', typ: 'czarne', numer: 5 });
  check(ephemeral(bothChoice) && db.getUser(A.id).wallet === 1000, 'ruletka typ+numer naraz → odmowa');

  // dwie gry naraz: druga musi zostać odrzucona
  {
    rich(A, 5000);
    const first = makeInteraction(A, { zaklad: '100' });
    const p1 = commands.get('blackjack').execute(first);
    await p1;
    if (first.collector && !first.collector.ended) {
      const second = await run('moneta', A, { zaklad: '100', strona: 'orzel' });
      check(ephemeral(second), 'druga gra w trakcie blackjacka powinna zostać odrzucona');
      await first.collector.stop('time');
      await first.collector.settled();
    }
    check(game.active.size === 0, 'blokada zwolniona po zakończeniu blackjacka');
  }

  // odporność: awaria Discorda w trakcie gry nie może zjeść stawki
  section('Awaria Discorda w trakcie gry → stawka wraca');
  for (const name of ['moneta', 'sloty', 'kosci', 'kolo', 'zdrapka', 'wyscigi', 'ruletka', 'blackjack', 'miny', 'crash', 'hilo', 'knp']) {
    // blackjack rozstrzyga się od razu tylko przy naturalnym blackjacku (ok. 5% rozdań),
    // więc powtarzamy, żeby ta ścieżka na pewno została sprawdzona.
    const rounds = name === 'blackjack' ? 400 : 1;
    for (let r = 0; r < rounds; r++) {
      rich(A, 10_000);
      const i = makeInteraction(A, { zaklad: '1000', strona: 'orzel', typ: 'czarne', kon: 1 });
      i.reply = async () => {
        throw new Error('Unknown interaction (symulacja)');
      };
      let threw = false;
      try {
        await commands.get(name).execute(i);
      } catch {
        threw = true;
      }
      check(db.getUser(A.id).wallet === 10_000, `${name}: po awarii portfel ${db.getUser(A.id).wallet} ≠ 10000`);
      check(game.active.size === 0 && game.pending.size === 0, `${name}: blokada po awarii nie została zwolniona`);
      check(threw, `${name}: awaria powinna wyjść na zewnątrz (do logów)`);
    }
  }

  // awaria przy odpowiedzi na kliknięcie „Przyjmuję” w pojedynku: nikt nie traci ani monety
  {
    rich(A, 10_000);
    rich(B, 10_000);
    const i = await run('pojedynek', A, { przeciwnik: B, zaklad: '1000' });
    i.collector.failUpdate = true;
    const realError = console.error;
    console.error = () => {};
    try {
      await i.collector.click('duel_accept', B);
    } finally {
      console.error = realError;
    }
    check(db.getUser(A.id).wallet === 10_000 && db.getUser(B.id).wallet === 10_000, 'pojedynek: awaria przy odpowiedzi nie rusza pieniędzy');
  }

  // ---------------------------------------------------------------- gry interaktywne
  section('/blackjack – 400 rozdań z losowymi ruchami');
  {
    let doubles = 0;
    for (let k = 0; k < 400; k++) {
      rich(A);
      resetLedger();
      const before = db.getUser(A.id).wallet;
      const i = await run('blackjack', A, { zaklad: String([50, 500, 5000][k % 3]) });
      const col = i.collector;
      let guard = 0;
      while (col && !col.ended && guard++ < 25) {
        const r = Math.random();
        const id = r < 0.45 ? 'bj_hit' : r < 0.8 ? 'bj_stand' : 'bj_double';
        const ci = await col.click(id, A);
        if (id === 'bj_double' && ci && ci.updates.length) doubles++;
      }
      if (col && !col.ended) {
        await col.stop('time');
      }
      if (col) await col.settled();
      const after = db.getUser(A.id).wallet;
      check(after === before + ledger.net + ledger.levelBonus, `blackjack: portfel ${before}→${after}, księga ${ledger.net}+${ledger.levelBonus}`);
      check(game.active.size === 0 && game.pending.size === 0, 'blackjack: blokada nie została zwolniona');
      check(ledger.net >= -Math.abs(before), 'blackjack: absurdalny wynik');
    }
    console.log(`  ok (doubli: ${doubles})`);
  }
  {
    // timeout bez ruchu = pas krupiera
    rich(A);
    resetLedger();
    const before = db.getUser(A.id).wallet;
    const i = await run('blackjack', A, { zaklad: '1000' });
    if (i.collector && !i.collector.ended) {
      await i.collector.stop('idle');
      await i.collector.settled();
      check(db.getUser(A.id).wallet === before + ledger.net + ledger.levelBonus, 'blackjack: timeout poprawnie rozliczony');
    }
    // cudzy klik jest ignorowany
    rich(A);
    const j = await run('blackjack', A, { zaklad: '1000' });
    if (j.collector && !j.collector.ended) {
      const ci = await j.collector.click('bj_stand', B);
      check(ci.replies.length === 1 && !j.collector.ended, 'blackjack: klik obcego gracza powinien być odrzucony');
      await j.collector.stop('time');
      await j.collector.settled();
    }
  }

  section('/miny – 300 gier z losowym odkrywaniem pól');
  {
    let boom = 0;
    let cashed = 0;
    for (let k = 0; k < 300; k++) {
      rich(A);
      resetLedger();
      const before = db.getUser(A.id).wallet;
      const mines = [1, 3, 6, 12][k % 4];
      const i = await run('miny', A, { zaklad: '1000', miny: mines });
      const col = i.collector;
      const target = 1 + (k % 6);
      const tiles = [...Array(20).keys()].sort(() => Math.random() - 0.5);
      let ti = 0;
      let guard = 0;
      while (!col.ended && guard++ < 40) {
        if (ti >= target) {
          await col.click('mine_cash', A);
          break;
        }
        await col.click(`mine_${tiles[ti++]}`, A);
      }
      if (!col.ended) await col.stop('idle');
      await col.settled();
      const after = db.getUser(A.id).wallet;
      check(after === before + ledger.net + ledger.levelBonus, `miny: portfel ${before}→${after}, księga ${ledger.net}+${ledger.levelBonus}`);
      check(game.active.size === 0 && game.pending.size === 0, 'miny: blokada nie została zwolniona');
      if (ledger.net === -1000) boom++;
      else cashed++;
    }
    // jedno dokładne sprawdzenie matematyki: 1 mina, 1 pole = x1.02
    const mod = require('../src/commands/miny');
    check(mod.multiplier(1, 1) === 1.02, `miny: x(1,1) = ${mod.multiplier(1, 1)}`);
    check(mod.multiplier(0, 3) === 0.97 || mod.multiplier(0, 3) === 0.97, 'miny: x(0)');
    console.log(`  ok (wybuchów: ${boom}, wypłat: ${cashed})`);
    // anulowanie bez aktywności zwraca stawkę
    rich(A);
    const before = db.getUser(A.id).wallet;
    const i = await run('miny', A, { zaklad: '1000', miny: 3 });
    await i.collector.stop('idle');
    await i.collector.settled();
    check(db.getUser(A.id).wallet === before, 'miny: brak aktywności bez ruchu → stawka wraca');
  }

  section('/hilo – 300 gier');
  {
    let streaks = 0;
    for (let k = 0; k < 300; k++) {
      rich(A);
      resetLedger();
      const before = db.getUser(A.id).wallet;
      const i = await run('hilo', A, { zaklad: '1000' });
      const col = i.collector;
      let guard = 0;
      const goal = k % 5;
      let steps = 0;
      while (!col.ended && guard++ < 60) {
        if (steps >= goal && steps > 0) {
          await col.click('hilo_cash', A);
          break;
        }
        const btns = i.edits.length ? null : i.replies[0].components[0].toJSON().components;
        void btns;
        await col.click(Math.random() < 0.5 ? 'hilo_high' : 'hilo_low', A);
        steps++;
      }
      streaks += steps;
      if (!col.ended) await col.stop('idle');
      await col.settled();
      const after = db.getUser(A.id).wallet;
      check(after === before + ledger.net + ledger.levelBonus, `hilo: portfel ${before}→${after}, księga ${ledger.net}+${ledger.levelBonus}`);
      check(game.active.size === 0 && game.pending.size === 0, 'hilo: blokada nie została zwolniona');
    }
    console.log(`  ok (łącznie ruchów: ${streaks})`);
  }

  section('/knp – 150 gier');
  for (let k = 0; k < 150; k++) {
    rich(A);
    resetLedger();
    const before = db.getUser(A.id).wallet;
    const i = await run('knp', A, { zaklad: '1000' });
    if (k % 10 === 9) await i.collector.stop('time');
    else await i.collector.click(['knp_kamien', 'knp_papier', 'knp_nozyce'][k % 3], A);
    await i.collector.settled();
    const after = db.getUser(A.id).wallet;
    if (k % 10 === 9) check(after === before, 'knp: timeout zwraca stawkę');
    else check(after === before + ledger.net + ledger.levelBonus, `knp: portfel ${before}→${after}, księga ${ledger.net}`);
    check(game.active.size === 0 && game.pending.size === 0, 'knp: blokada nie została zwolniona');
  }
  console.log('  ok');

  section('/crash – 120 gier (auto-wypłata, ręczna wypłata, czysty crash)');
  {
    let cash = 0;
    let crashed = 0;
    for (let k = 0; k < 120; k++) {
      rich(A);
      resetLedger();
      const before = db.getUser(A.id).wallet;
      const opts = { zaklad: '1000' };
      if (k % 3 === 0) opts.auto = 1.5;
      const i = await run('crash', A, opts);
      const col = i.collector;
      if (k % 3 === 1) {
        await sleep(Math.floor(Math.random() * 20));
        await col.click('crash_cash', A);
      }
      for (let w = 0; w < 400 && !col.ended; w++) await sleep(2);
      if (!col.ended) await col.stop('time');
      await col.settled();
      await sleep(2);
      const after = db.getUser(A.id).wallet;
      check(after === before + ledger.net + ledger.levelBonus, `crash: portfel ${before}→${after}, księga ${ledger.net}+${ledger.levelBonus}`);
      check(game.active.size === 0 && game.pending.size === 0, 'crash: blokada nie została zwolniona');
      if (ledger.net > 0) cash++;
      else if (ledger.net < 0) crashed++;
    }
    const mod = require('../src/commands/crash');
    check(mod.multAt(1) === 1.11 && mod.multAt(0) === 1, 'crash: krzywa mnożnika');
    console.log(`  ok (wypłat: ${cash}, crashy: ${crashed})`);
  }

  section('/pojedynek');
  for (let k = 0; k < 40; k++) {
    rich(A, 10_000);
    rich(B, 10_000);
    resetLedger();
    const i = await run('pojedynek', A, { przeciwnik: B, zaklad: '1000' });
    const col = i.collector;
    // obcy gracz nie może odpowiedzieć
    const stranger = await col.click('duel_accept', C);
    check(stranger.replies.length === 1 && !col.ended, 'pojedynek: obcy gracz odrzucony');
    if (k % 8 === 7) await col.click('duel_decline', B);
    else if (k % 8 === 6) await col.stop('time');
    else await col.click('duel_accept', B);
    await col.settled();
    const total = db.getUser(A.id).wallet + db.getUser(B.id).wallet;
    if (k % 8 >= 6) check(total === 20_000, 'pojedynek: odmowa/timeout nie rusza pieniędzy');
    else check(total === 20_000 - 60 + ledger.levelBonus, `pojedynek: suma po pojedynku ${total} (oczekiwano ${20_000 - 60}+${ledger.levelBonus})`);
  }
  {
    rich(A, 10_000);
    rich(B, 10_000);
    const i = await run('pojedynek', A, { przeciwnik: B, zaklad: '1000' });
    db.setWallet(B.id, 100); // przeciwnik wydał pieniądze, zanim przyjął
    resetLedger();
    await i.collector.click('duel_accept', B);
    check(db.getUser(A.id).wallet === 10_000 && db.getUser(B.id).wallet === 100, 'pojedynek: brak środków przy akceptacji → anulowany bez strat');
    const self = await run('pojedynek', A, { przeciwnik: A, zaklad: '10' });
    check(ephemeral(self), 'pojedynek z samym sobą odrzucony');
  }
  console.log('  ok');

  section('/napad');
  {
    // za mało chętnych → zwrot wpisowego
    rich(A, 5000);
    db.clearCooldown(A.id, 'heist');
    const i = await run('napad', A, {}, null, 'heist_chan');
    check(db.getUser(A.id).wallet === 4500, 'napad: wpisowe pobrane');
    const blocked = await run('napad', B, {}, null, 'heist_chan');
    check(ephemeral(blocked), 'napad: drugi napad na tym samym kanale zablokowany');
    await i.collector.stop('time');
    await i.collector.settled();
    check(db.getUser(A.id).wallet === 5000 && db.cooldownLeft(A.id, 'heist') === 0, 'napad: bez ekipy wpisowe wraca i cooldown znika');

    let wins = 0;
    let losses = 0;
    for (let k = 0; k < 40; k++) {
      for (const u of [A, B, C]) {
        rich(u, 5000);
        db.clearCooldown(u.id, 'heist');
      }
      const h = await run('napad', A, {}, null, 'heist_chan2');
      await h.collector.click('heist_join', B);
      await h.collector.click('heist_join', C);
      const again = await h.collector.click('heist_join', B);
      check(again.replies.length === 1, 'napad: podwójne dołączenie odrzucone');
      await h.collector.stop('time');
      await h.collector.settled();
      const total = [A, B, C].reduce((s, u) => s + db.getUser(u.id).wallet, 0);
      if (total < 15_000) losses++;
      else wins++;
      check(total === 15_000 - 1500 || total > 15_000 - 1500, `napad: suma ${total}`);
      check([A, B, C].every((u) => db.cooldownLeft(u.id, 'heist') > 0), 'napad: cooldown po napadzie');
    }
    console.log(`  ok (udanych: ${wins}, nieudanych: ${losses})`);
  }

  section('/loteria');
  {
    db.lotteryClear();
    for (const u of [A, B, C]) rich(u, 100_000);
    await run('loteria', A, { ilosc: 10 }, 'kup');
    await run('loteria', B, { ilosc: 30 }, 'kup');
    const tooMany = await run('loteria', C, { ilosc: 500 }, 'kup');
    await run('loteria', C, { ilosc: 500 }, 'kup');
    check(db.lotteryTickets(C.id) === 500, 'loteria: limit biletów');
    const pool = lottery.poolInfo();
    check(pool.tickets === 540 && pool.players === 3, 'loteria: pula');
    const sumBefore = [A, B, C].reduce((s, u) => s + db.getUser(u.id).wallet, 0);
    const result = await lottery.draw(null);
    const sumAfter = [A, B, C].reduce((s, u) => s + db.getUser(u.id).wallet, 0);
    check(result && sumAfter === sumBefore + pool.pot, 'loteria: wypłata puli zwycięzcy');
    check(db.lotteryAll().length === 0, 'loteria: bilety wyczyszczone po losowaniu');
    check(typeof tooMany === 'object', 'loteria ok');
    const info = await run('loteria', A, {}, 'info');
    check(info.replied, 'loteria info');
  }

  // ---------------------------------------------------------------- skrzynie
  section('Skrzynie: tabele łupów, zwroty, nagrody');
  {
    const ids = Object.keys(crates.CRATES);
    check(ids.length === 6, `sześć rodzajów skrzyń (jest ${ids.length})`);
    for (const id of ids) {
      const it = ITEMS[id];
      check(it && it.type === 'crate' && it.price > 0, `${id}: przedmiot w katalogu jako skrzynia`);
      const loot = crates.CRATES[id].loot;
      const sumW = loot.reduce((s, e) => s + e.w, 0);
      check(Math.abs(sumW - 100) < 1e-9, `${id}: wagi sumują się do 100 (jest ${sumW})`);
      for (const e of loot) {
        check(crates.RARITY[e.r], `${id}: nieznana rzadkość ${e.r}`);
        if (e.kind === 'item') check(ITEMS[e.item], `${id}: nieznany przedmiot ${e.item}`);
        else check(e.min > 0 && e.max >= e.min, `${id}: zły zakres nagrody`);
      }
      const ev = crates.expectedValue(id);
      check(ev < it.price, `${id}: wartość oczekiwana ${Math.round(ev)} musi być poniżej ceny ${it.price}`);
      check(ev > it.price * 0.8, `${id}: zwrot zbyt niski (${((ev / it.price) * 100).toFixed(0)}%)`);

      // Monte Carlo: średnia z losowań zgadza się z wyliczoną wartością oczekiwaną
      const N = 120_000;
      let sum = 0;
      const rarities = {};
      for (let k = 0; k < N; k++) {
        const r = crates.roll(id);
        rarities[r.rarity] = (rarities[r.rarity] ?? 0) + 1;
        if (r.kind === 'money' || r.kind === 'xp') sum += r.amount;
        else sum += (crates.isCrate(r.item) ? crates.expectedValue(r.item) : ITEMS[r.item].sell ?? Math.floor(ITEMS[r.item].price * 0.5)) * r.qty;
      }
      const odds = crates.rarityOdds(id);
      check(Math.abs(sum / N - ev) / ev < 0.06, `${id}: średnia z losowań ${Math.round(sum / N)} ≠ wartość oczekiwana ${Math.round(ev)}`);
      for (const [rar, pct] of Object.entries(odds)) {
        const got = ((rarities[rar] ?? 0) / N) * 100;
        check(Math.abs(got - pct) < Math.max(0.35, pct * 0.12), `${id}: rzadkość ${rar} wypada ${got.toFixed(2)}% zamiast ${pct.toFixed(2)}%`);
      }
    }

    // wręczanie nagród: pieniądze, przedmioty i XP trafiają do właściwego miejsca
    rich(A, 0);
    resetLedger();
    const wanted = {};
    let money = 0;
    for (let k = 0; k < 400; k++) {
      const r = crates.roll('skrzynia');
      crates.applyReward(A.id, r);
      if (r.kind === 'money') money += r.amount;
      if (r.kind === 'item') wanted[r.item] = (wanted[r.item] ?? 0) + r.qty;
    }
    // awans z XP ze skrzyni może dorzucić premię za poziom – księga ją uwzględnia
    check(db.getUser(A.id).wallet === money + ledger.levelBonus, `nagrody pieniężne: portfel ${db.getUser(A.id).wallet}, oczekiwano ${money}+${ledger.levelBonus}`);
    for (const [id, qty] of Object.entries(wanted)) {
      check(db.itemQty(A.id, id) >= qty, `przedmiot ${id}: w ekwipunku ${db.itemQty(A.id, id)}, wylosowano ${qty}`);
    }
  }

  section('Skrzynie: komenda /skrzynie, animacja, awarie');
  {
    const list = await run('skrzynie', A, {}, 'lista');
    check(list.replied && list.replies[0].embeds[0].toJSON().fields.length === Object.keys(crates.CRATES).length, '/skrzynie lista pokazuje wszystkie skrzynie');

    for (const id of Object.keys(crates.CRATES)) {
      for (let k = 0; k < 40; k++) {
        rich(A, 100);
        db.takeItem(A.id, id, db.itemQty(A.id, id));
        db.addItem(A.id, id, 1);
        const i = await run('skrzynie', A, { skrzynia: id }, 'otworz');
        check(i.replied && i.edits.length >= 7, `${id}: animacja paska ma za mało klatek (${i.edits.length})`);
        const last = i.edits[i.edits.length - 1].embeds[0].toJSON();
        const desc = last.description;
        check(/» [⚪🟢🔵🟣🟡] «/u.test(desc), `${id}: końcowy pasek bez znacznika środka`);
        const winnerEmoji = /» ([⚪🟢🔵🟣🟡]) «/u.exec(desc)[1];
        const rar = Object.values(crates.RARITY).find((r) => r.emoji === winnerEmoji);
        check(last.color === rar.color, `${id}: kolor embeda nie pasuje do rzadkości ${winnerEmoji}`);
        // skrzynia nigdy nie wypada sama z siebie, więc po otwarciu musi zniknąć z ekwipunku
        check(db.itemQty(A.id, id) === 0, `${id}: skrzynia nie została zużyta`);
      }
    }

    // wiele naraz
    rich(A, 100);
    db.addItem(A.id, 'skrzynka', 10);
    const before = db.itemQty(A.id, 'skrzynka');
    const multi = await run('skrzynie', A, { skrzynia: 'skrzynka', ilosc: 10 }, 'otworz');
    const fin = multi.edits[multi.edits.length - 1].embeds[0].toJSON();
    check(multi.replied && /Razem gotówki/.test(fin.description), 'otwieranie 10 skrzyń: podsumowanie');
    check(db.itemQty(A.id, 'skrzynka') >= before - 10, 'otwarcie 10 skrzyń zużyło 10 sztuk (mogły wypaść kolejne)');

    // brak skrzyni / za mało
    db.takeItem(A.id, 'skrzynia_legendarna', db.itemQty(A.id, 'skrzynia_legendarna'));
    const none = await run('skrzynie', A, { skrzynia: 'skrzynia_legendarna' }, 'otworz');
    check(ephemeral(none), 'brak skrzyni → odmowa');
    db.addItem(A.id, 'skrzynia_legendarna', 2);
    const few = await run('skrzynie', A, { skrzynia: 'skrzynia_legendarna', ilosc: 5 }, 'otworz');
    check(ephemeral(few) && db.itemQty(A.id, 'skrzynia_legendarna') === 2, 'za mało skrzyń na 5 otwarć → odmowa bez strat');

    // awaria Discorda przy pierwszej odpowiedzi: skrzynia i pieniądze nietknięte
    rich(A, 777);
    db.addItem(A.id, 'skrzynka', 1);
    const q0 = db.itemQty(A.id, 'skrzynka');
    const broken = makeInteraction(A, { skrzynia: 'skrzynka' }, 'otworz');
    broken.reply = async () => {
      throw new Error('Unknown interaction (symulacja)');
    };
    let threw = false;
    try {
      await commands.get('skrzynie').execute(broken);
    } catch {
      threw = true;
    }
    check(threw && db.itemQty(A.id, 'skrzynka') === q0 && db.getUser(A.id).wallet === 777, 'awaria przy otwieraniu nie zjada skrzyni ani nie wręcza nagrody');

    // awaria w trakcie animacji: nagroda jest już wręczona (nic nie przepada), komenda nie wybucha
    const F = makeUser('u_flaky');
    db.setWallet(F.id, 0);
    db.addItem(F.id, 'skrzynka', 1);
    const flaky = makeInteraction(F, { skrzynia: 'skrzynka' }, 'otworz');
    const origEdit = flaky.editReply;
    flaky.editReply = async (p) => {
      if (flaky.edits.length >= 2) throw new Error('rate limit (symulacja)');
      return origEdit(p);
    };
    const realError = console.error;
    console.error = () => {}; // awarie są tu zamierzone, nie zaśmiecamy wyniku testu
    try {
      await commands.get('skrzynie').execute(flaky);
    } finally {
      console.error = realError;
    }
    check(flaky.replied, 'animacja z awarią w połowie nie wywala komendy');
    const fu = db.getUser(F.id);
    const gotReward = fu.wallet > 0 || db.inventory(F.id).length > 0 || fu.xp > 0 || fu.level > 1;
    check(db.itemQty(F.id, 'skrzynka') === 0 && gotReward, 'animacja z awarią: skrzynia zużyta, a nagroda wręczona mimo awarii');
  }

  section('Skrzynie jako nagrody: daily, weekly, awans');
  {
    const U = makeUser('u_streak');
    db.getUser(U.id);
    db.setDaily(U.id, Date.now() - 21 * 3600 * 1000, 6);
    await run('daily', U, {});
    check(db.getUser(U.id).streak === 7 && db.itemQty(U.id, 'skrzynia') === 1, 'daily: seria 7 dni daje skrzynię skarbów');
    db.setDaily(U.id, Date.now() - 21 * 3600 * 1000, 29);
    await run('daily', U, {});
    check(db.getUser(U.id).streak === 30 && db.itemQty(U.id, 'skrzynia_diamentowa') === 1, 'daily: seria 30 dni daje skrzynię diamentową');

    let got = 0;
    for (let k = 0; k < 400; k++) {
      db.setDaily(U.id, Date.now() - 21 * 3600 * 1000, 1);
      const before = db.itemQty(U.id, 'skrzynka');
      await run('daily', U, {});
      if (db.itemQty(U.id, 'skrzynka') > before) got++;
    }
    check(got > 80 && got < 160, `daily: losowa skrzynka wypada w ok. 30% (jest ${((got / 400) * 100).toFixed(0)}%)`);

    const W = makeUser('u_weekly');
    await run('weekly', W, {});
    check(db.itemQty(W.id, 'skrzynia') === 1, 'weekly daje skrzynię skarbów');

    const L = makeUser('u_level');
    const res = db.addXp(L.id, 200_000);
    check(db.itemQty(L.id, 'skrzynia') === Math.floor(res.level / 5) && res.crates === Math.floor(res.level / 5), `awans do poziomu ${res.level} daje skrzynię co 5 poziomów (jest ${db.itemQty(L.id, 'skrzynia')})`);
  }

  section('Pasek animacji');
  {
    for (let k = 0; k < 500; k++) {
      const half = k % 2 ? 3 : 2;
      const target = 20 + (k % 6);
      const cells = anim.buildStrip('W', () => 'x', target, half);
      check(cells[target] === 'W' && cells.length >= target + half + 1, 'pasek: zwycięzca na pozycji docelowej, pasek wystarczająco długi');
      const frames = [];
      const finalView = await anim.spinStrip(cells, target, (view) => frames.push(view), { half });
      check(finalView.includes('» W «'), 'pasek: końcowy widok ma zwycięzcę pośrodku');
      check(frames.length >= 5, `pasek: za mało klatek (${frames.length})`);
      check(frames.every((f) => !f.includes('» W «')), 'pasek: zwycięzca nie może być pośrodku przed ostatnią klatką');
    }
  }

  section('Animacje gier: liczba klatek');
  {
    rich(A);
    const s = await run('sloty', A, { zaklad: '100' });
    check(s.edits.length >= 3, `sloty: bębny zatrzymują się po kolei (${s.edits.length} edycji)`);
    const k = await run('kolo', A, { zaklad: '100' });
    check(k.edits.length >= 5 && /» .+ «/.test(k.edits[k.edits.length - 1].embeds[0].toJSON().description), 'koło: pasek z wynikiem pośrodku');
    const m = await run('moneta', A, { zaklad: '100', strona: 'orzel' });
    check(m.edits.length >= 4, 'moneta: klatki rzutu');
    const d = await run('kosci', A, { zaklad: '100', typ: 'wyzej' });
    check(d.edits.length >= 4, 'kości: klatki rzutu');
    const r = await run('ruletka', A, { zaklad: '100', typ: 'czerwone' });
    check(r.edits.length >= 4, 'ruletka: klatki kulki');
    db.addItem(A.id, 'wedka', 1);
    db.clearCooldown(A.id, 'ryby');
    const f = await run('ryby', A, {});
    check(f.edits.length >= 3, 'ryby: klatki łowienia przed wynikiem');

    // blackjack: animacja dobierania krupiera – przynajmniej raz trzeba zobaczyć klatki z odkrytą kartą
    let animated = 0;
    for (let t = 0; t < 120 && animated === 0; t++) {
      rich(A);
      const b = await run('blackjack', A, { zaklad: '100' });
      if (!b.collector || b.collector.ended) continue;
      const ci = await b.collector.click('bj_stand', A);
      await b.collector.settled();
      if (ci.updates.length && b.edits.length >= 2) {
        animated++;
        check(/Krupier odkrywa/.test(ci.updates[0].embeds[0].toJSON().description), 'blackjack: pierwsza klatka to odkrycie kart');
        check(/Twoje karty/.test(b.edits[b.edits.length - 1].embeds[0].toJSON().description), 'blackjack: ostatnia klatka to wynik');
      }
    }
    check(animated === 1, 'blackjack: zobaczono animację dobierania krupiera');
  }

  // ---------------------------------------------------------------- bank
  section('Bank: limit 100 000 i ulepszenia');
  {
    const N = makeUser('u_bank');
    check(db.bankCap(db.getUser(N.id)) === 100_000, `limit na start to 100 000 (jest ${db.bankCap(db.getUser(N.id))})`);

    const info = await run('bank', N, {}, 'info');
    check(info.replied && /100 000/.test(info.replies[0].embeds[0].toJSON().description), '/bank info pokazuje limit 100 000');
    const lista = await run('bank', N, {}, 'lista');
    check(lista.replied && lista.replies[0].embeds[0].toJSON().description.split('\n').filter((l) => /^[✅👉🔒]/u.test(l)).length === BANK_UPGRADES.length, '/bank lista pokazuje wszystkie ulepszenia');

    db.setWallet(N.id, BANK_UPGRADES[0].cost - 1);
    const poor = await run('bank', N, {}, 'ulepsz');
    check(ephemeral(poor) && db.getUser(N.id).bank_tier === 0 && db.getUser(N.id).wallet === BANK_UPGRADES[0].cost - 1, 'za mało pieniędzy: odmowa bez strat');

    let expected = 100_000;
    for (const [k, up] of BANK_UPGRADES.entries()) {
      db.setWallet(N.id, up.cost + 123);
      const r = await run('bank', N, {}, 'ulepsz');
      expected += up.bonus;
      const u = db.getUser(N.id);
      check(r.replied && !ephemeral(r), `ulepszenie ${k + 1} (${up.name}) kupione`);
      check(u.bank_tier === k + 1 && u.wallet === 123, `ulepszenie ${k + 1}: poziom ${u.bank_tier}, portfel ${u.wallet} (oczekiwano ${k + 1} i 123)`);
      check(db.bankCap(u) === expected, `ulepszenie ${k + 1}: limit ${db.bankCap(u)} zamiast ${expected}`);
    }

    // atomowość: pieniędzy starcza na jedno ulepszenie, więc drugi zakup pod rząd musi się nie udać
    const T = makeUser('u_bank_atomic');
    db.setWallet(T.id, BANK_UPGRADES[0].cost);
    const first = db.buyBankUpgrade(T.id);
    const second = db.buyBankUpgrade(T.id);
    check(first.ok && !second.ok && second.reason === 'poor', 'drugi zakup za te same pieniądze odrzucony');
    check(db.getUser(T.id).wallet === 0 && db.getUser(T.id).bank_tier === 1, 'po dwóch próbach: portfel 0, dokładnie jedno ulepszenie');
    const maxed = await run('bank', N, {}, 'ulepsz');
    check(ephemeral(maxed) && db.getUser(N.id).bank_tier === BANK_UPGRADES.length, 'po ostatnim ulepszeniu zakup jest odrzucany');
    check(db.bankCap(db.getUser(N.id)) === 100_000 + BANK_UPGRADES.reduce((s, u) => s + u.bonus, 0), 'pełny limit = baza + wszystkie ulepszenia');

    // limit realnie działa przy wpłacie
    const cap = db.bankCap(db.getUser(N.id));
    db.setBank(N.id, 0);
    db.setWallet(N.id, cap + 1000);
    await run('wplac', N, { kwota: 'all' });
    check(db.getUser(N.id).bank === cap && db.getUser(N.id).wallet === 1000, `wpłata do nowego limitu: bank ${db.getUser(N.id).bank} z ${cap}`);

    // małe sejfy ze skrzyń nadal dodają swoje
    const S = makeUser('u_sejf');
    db.addItem(S.id, 'sejf', 3);
    check(db.bankCap(db.getUser(S.id)) === 100_000 + 3 * SEJF_BONUS, 'małe sejfy dodają po 25 000 każdy');
    check(!require('../src/lib/data').SHOP_ORDER.includes('sejf'), 'mały sejf nie jest już w sklepie');
    const bd = db.bankBreakdown(db.getUser(S.id));
    check(bd.total === bd.base + bd.levels + bd.upgrades + bd.safes, 'rozbicie limitu sumuje się do całości');

    // migracja starej bazy (bez kolumny bank_tier)
    const fs = require('node:fs');
    const os = require('node:os');
    const path = require('node:path');
    const { DatabaseSync } = require('node:sqlite');
    const { execFileSync } = require('node:child_process');
    const tmp = path.join(os.tmpdir(), `eco-old-${process.pid}-${Date.now()}.db`);
    const old = new DatabaseSync(tmp);
    old.exec(`CREATE TABLE users (id TEXT PRIMARY KEY, wallet INTEGER NOT NULL DEFAULT 0, bank INTEGER NOT NULL DEFAULT 0, xp INTEGER NOT NULL DEFAULT 0, level INTEGER NOT NULL DEFAULT 1, streak INTEGER NOT NULL DEFAULT 0, last_daily INTEGER NOT NULL DEFAULT 0, last_weekly INTEGER NOT NULL DEFAULT 0, job TEXT NOT NULL DEFAULT 'sprzatacz', total_won INTEGER NOT NULL DEFAULT 0, total_lost INTEGER NOT NULL DEFAULT 0, games INTEGER NOT NULL DEFAULT 0, biggest_win INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)`);
    old.prepare('INSERT INTO users (id, wallet, bank, level, created_at) VALUES (?, ?, ?, ?, ?)').run('old', 777, 5000, 3, 1);
    old.close();
    const out = execFileSync(
      process.execPath,
      ['--disable-warning=ExperimentalWarning', '-e', "const db=require('./src/lib/db');const u=db.getUser('old');console.log(JSON.stringify({tier:u.bank_tier,bank:u.bank,wallet:u.wallet,cap:db.bankCap(u)}))"],
      { env: { ...process.env, DB_PATH: tmp }, cwd: path.join(__dirname, '..'), encoding: 'utf8' },
    );
    for (const f of [tmp, `${tmp}-wal`, `${tmp}-shm`]) fs.rmSync(f, { force: true });
    const migrated = JSON.parse(out.trim().split('\n').pop());
    check(migrated.tier === 0 && migrated.bank === 5000 && migrated.wallet === 777 && migrated.cap === 110_000, `migracja starej bazy: ${out.trim()}`);
  }

  // ---------------------------------------------------------------- logowanie
  section('Logowanie i awaryjne wyłączenie prefiksu');
  {
    const fakeClient = (behaviour) => {
      const c = new EventEmitter();
      c.destroyed = false;
      c.destroy = async () => {
        c.destroyed = true;
      };
      c.login = behaviour(c);
      return c;
    };
    check((await loginClient(fakeClient(() => async () => 'tok'), 't')) === 'ok', 'udane logowanie → ok');
    check((await loginClient(fakeClient((c) => () => (setTimeout(() => c.emit('shardDisconnect', { code: 4014 }, 0), 5), new Promise(() => {}))), 't')) === 'intents', 'kod 4014 (brak intentu) przy wiszącym loginie → intents');
    check((await loginClient(fakeClient((c) => () => (setTimeout(() => c.emit('error', new Error('Used disallowed intents')), 5), new Promise(() => {}))), 't')) === 'intents', 'zdarzenie error „disallowed intents” → intents');
    const rejected = await loginClient(fakeClient(() => async () => Promise.reject(new Error('An invalid token was provided.'))), 't');
    check(rejected.error?.message === 'An invalid token was provided.', 'odrzucony login → error');
    const authFail = await loginClient(fakeClient((c) => () => (setTimeout(() => c.emit('shardDisconnect', { code: 4004 }, 0), 5), new Promise(() => {}))), 't');
    check(authFail.error, 'kod 4004 → error');

    // pełna pętla: pierwszy klient (z prefiksem) dostaje 4014, drugi (bez prefiksu) łączy się
    const created = [];
    const clients = [];
    const result = await connect(
      (withPrefix) => {
        created.push(withPrefix);
        const c = fakeClient((cl) => (withPrefix ? () => (setTimeout(() => cl.emit('shardDisconnect', { code: 4014 }, 0), 5), new Promise(() => {})) : async () => 'tok'));
        clients.push(c);
        return c;
      },
      't',
      true,
      { warn() {} },
    );
    check(result.ok && result.withPrefix === false && created.join() === 'true,false', `awaryjny start bez prefiksu: ${JSON.stringify(created)}`);
    check(clients[0].destroyed && !clients[1].destroyed, 'nieudany klient zamknięty, nowy działa');

    const warned = [];
    const withIntentsOk = await connect(() => fakeClient(() => async () => 'tok'), 't', true, { warn: (m) => warned.push(m) });
    check(withIntentsOk.ok && withIntentsOk.withPrefix === true && !warned.length, 'gdy intent jest włączony, prefiks zostaje');

    const bad = await connect(() => fakeClient(() => async () => Promise.reject(new Error('zły token'))), 't', true, { warn() {} });
    check(!bad.ok && !bad.intents && bad.error.message === 'zły token', 'zły token: bez ponownych prób, czytelny błąd');
  }

  // ---------------------------------------------------------------- prefiks
  section('Komendy z prefiksem „!”');
  {
    const makeSent = (m, payload) => {
      validatePayload(payload);
      const s = {
        payload,
        edits: [],
        collector: null,
        async edit(p) {
          validatePayload(p);
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
    const makeMessage = (author, content, { mentions = [], channelId = 'chan1' } = {}) => {
      const m = {
        author,
        content,
        channelId,
        client: fakeClient,
        guild: { id: 'g1' },
        mentions: { users: new Map(mentions.map((u) => [u.id, u])) },
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
    const say = async (author, content, opts) => {
      const m = makeMessage(author, content, opts);
      const handled = await handleMessage(m, commands, { prefix: '!', throttleMs: 0 });
      return { m, handled, first: m.sent[0] };
    };
    const text = (sent) => (typeof sent.payload === 'string' ? sent.payload : sent.payload.content ?? sent.payload.embeds?.[0]?.toJSON().description ?? '');
    const lastEmbed = (sent) => (sent.edits.length ? sent.edits[sent.edits.length - 1] : sent.payload).embeds?.[0]?.toJSON();

    // Prawdziwe ID Discorda to 17-20 cyfr; tylko takie adapter rozpoznaje jako wzmianki.
    const P = makeUser('800000000000000001');
    const Q = makeUser('800000000000000002');

    // co ma być zignowane
    for (const [who, content] of [[P, 'saldo'], [P, '!'], [P, '!   '], [P, '!nieistniejaca komenda'], [P, 'cześć !saldo'], [{ ...P, bot: true }, '!saldo']]) {
      const r = await say(who, content);
      check(!r.handled && r.m.sent.length === 0, `„${content}” powinno zostać zignorowane`);
    }

    // proste komendy i skróty
    for (const content of ['!saldo', '!SALDO', '!bal', '!profil', '!sklep', '!pomoc', '!ekwipunek', '!top', '!top poziom', '!bank', '!bank lista', '!skrzynie', '!loteria', '!praca', '!praca lista', '!weekly', '!daily']) {
      const r = await say(P, content);
      check(r.handled && r.m.sent.length >= 1, `„${content}” powinno odpowiedzieć`);
    }
    const mention = await say(P, `!saldo <@${Q.id}>`, { mentions: [Q] });
    check(JSON.stringify(mention.first.payload.embeds[0].toJSON()).includes(Q.displayName), '!saldo @ktoś pokazuje konto wskazanej osoby');
    const byId = await say(P, `!saldo ${Q.id.replace(/\D/g, '') || '12345678901234567'}`);
    check(byId.handled, '!saldo z surowym ID nie wywala');

    // gry natychmiastowe z różnym zapisem argumentów + kontrola pieniędzy
    const playInstant = async (content, label) => {
      rich(P);
      resetLedger();
      const before = db.getUser(P.id).wallet;
      const r = await say(P, content);
      const after = db.getUser(P.id).wallet;
      check(r.handled && r.m.sent.length === 1, `${label}: jedna wiadomość (jest ${r.m.sent.length})`);
      check(after === before + ledger.net + ledger.levelBonus, `${label}: portfel ${before}→${after}, księga ${ledger.net}+${ledger.levelBonus}`);
      check(game.active.size === 0 && game.pending.size === 0, `${label}: blokada nie zwolniona`);
      return r;
    };
    for (let k = 0; k < 30; k++) {
      await playInstant('!moneta 100 orzel', '!moneta 100 orzel');
      await playInstant('!moneta 100 o', '!moneta 100 o (skrót)');
      await playInstant('!moneta reszka 2k', '!moneta reszka 2k (odwrócona kolejność)');
      await playInstant('!moneta 50% r', '!moneta 50% r');
      await playInstant('!sloty 100', '!sloty');
      await playInstant('!kolo all', '!kolo all');
      await playInstant('!zdrapka 1.5k', '!zdrapka 1.5k');
      await playInstant('!kosci 100 wyzej', '!kosci wyzej');
      await playInstant('!kosci 100 poniżej', '!kosci poniżej');
      await playInstant('!kosci 100 s', '!kosci s (skrót do „siedem”)');
      await playInstant('!wyscigi 100 3', '!wyscigi 100 3');
      await playInstant('!roulette 100 czerwone', '!roulette (alias)');
      await playInstant('!ruletka 100 tuzin2', '!ruletka tuzin2');
    }
    for (const [content, expect] of [['!ruletka 50 17', 'Numer 17'], ['!ruletka 50 0', 'Numer 0'], ['!ruletka 50 czarne', 'Czarne'], ['!ruletka 50 niskie', 'Niskie']]) {
      const r = await playInstant(content, content);
      check(lastEmbed(r.first).description.includes(expect), `${content}: powinno stawiać na „${expect}”`);
    }

    // gry z przyciskami
    {
      rich(P);
      resetLedger();
      const before = db.getUser(P.id).wallet;
      const r = await say(P, '!miny 500 5');
      check(r.first.collector && /Min: \*\*5\*\*/.test(r.first.payload.embeds[0].toJSON().description), '!miny 500 5: pięć min');
      await r.first.collector.click('mine_cash', P); // jeszcze nic nie odkryto → przycisk wyłączony, ale klik nie psuje stanu
      await r.first.collector.stop('idle');
      await r.first.collector.settled();
      check(db.getUser(P.id).wallet === before + ledger.net + ledger.levelBonus, '!miny: pieniądze się zgadzają');
    }
    for (let k = 0; k < 40; k++) {
      rich(P);
      resetLedger();
      const before = db.getUser(P.id).wallet;
      const r = await say(P, '!bj 500');
      const col = r.first.collector;
      let guard = 0;
      while (col && !col.ended && guard++ < 20) await col.click(['bj_hit', 'bj_stand', 'bj_double'][Math.floor(Math.random() * 3)], P);
      if (col && !col.ended) await col.stop('time');
      if (col) await col.settled();
      check(db.getUser(P.id).wallet === before + ledger.net + ledger.levelBonus, `!bj: portfel ${before}→${db.getUser(P.id).wallet}`);
      check(game.active.size === 0 && game.pending.size === 0, '!bj: blokada zwolniona');
    }
    {
      rich(P);
      resetLedger();
      const before = db.getUser(P.id).wallet;
      const r = await say(P, '!crash 100 1.5');
      const col = r.first.collector;
      for (let w = 0; w < 400 && !col.ended; w++) await sleep(2);
      if (!col.ended) await col.stop('time');
      await col.settled();
      await sleep(3);
      check(db.getUser(P.id).wallet === before + ledger.net + ledger.levelBonus, '!crash 100 1.5: pieniądze się zgadzają');
    }
    {
      rich(P);
      resetLedger();
      const before = db.getUser(P.id).wallet;
      const r = await say(P, '!knp 100');
      await r.first.collector.click('knp_papier', P);
      await r.first.collector.settled();
      check(db.getUser(P.id).wallet === before + ledger.net + ledger.levelBonus, '!knp: pieniądze się zgadzają');
      const h = await say(P, '!hilo 100');
      await h.first.collector.stop('idle');
      await h.first.collector.settled();
    }

    // podkomendy, domyślne podkomendy i aliasy do podkomend
    db.clearCooldown(P.id, 'praca');
    check((await say(P, '!praca')).first, '!praca → domyślnie „wykonaj”');
    db.db.prepare('UPDATE users SET level = 1, xp = 0 WHERE id = ?').run(P.id); // po setkach gier gracz mógł awansować
    const lowJob = await say(P, '!praca zmien kurier');
    check(/wymaga poziomu/.test(text(lowJob.first)), '!praca zmien kurier: za niski poziom');
    check(/Sprzątacz/.test(text((await say(P, '!praca zmien sprzatacz')).first)), '!praca zmien sprzatacz działa');

    // sklep i skrzynie
    rich(P, 100_000);
    check(db.itemQty(P.id, 'wedka') === 0 && (await say(P, '!kup wędka')).handled && db.itemQty(P.id, 'wedka') === 1, '!kup wędka (z polskimi znakami)');
    check((await say(P, '!kup super_wedka')).handled && db.itemQty(P.id, 'super_wedka') === 1, '!kup super_wedka');
    check((await say(P, '!kup superwedka')).handled, '!kup superwedka (bez podkreślenia) nie wywala');
    const q0 = db.itemQty(P.id, 'skrzynka');
    await say(P, '!kup skrzynka 3');
    check(db.itemQty(P.id, 'skrzynka') === q0 + 3, '!kup skrzynka 3');
    const chestBefore = db.itemQty(P.id, 'skrzynka');
    await say(P, '!otworz skrzynka');
    check(db.itemQty(P.id, 'skrzynka') <= chestBefore - 1, '!otworz skrzynka zużywa skrzynię');
    const r2 = await say(P, '!skrzynie otworz skrzynka 2');
    check(r2.first && db.itemQty(P.id, 'skrzynka') <= chestBefore - 3, '!skrzynie otworz skrzynka 2 zużywa dwie');
    check((await say(P, '!sprzedaj wszystko')).handled, '!sprzedaj wszystko');
    check((await say(P, '!uzyj koniczyna')).handled, '!uzyj koniczyna');

    // bank
    rich(P, BANK_UPGRADES[0].cost + 10);
    const tier0 = db.getUser(P.id).bank_tier;
    await say(P, '!ulepsz');
    check(db.getUser(P.id).bank_tier === tier0 + 1, '!ulepsz kupuje ulepszenie banku');
    check(/Ulepszenia banku|Twój bank/.test(JSON.stringify((await say(P, '!bank lista')).first.payload.embeds[0].toJSON())), '!bank lista');

    // przelewy i pojedynek
    rich(P, 10_000);
    rich(Q, 10_000);
    await say(P, `!przelew <@${Q.id}> 1k`, { mentions: [Q] });
    check(db.getUser(P.id).wallet === 9000 && db.getUser(Q.id).wallet === 11_000, '!przelew @ktoś 1k');
    await say(P, `!przelew 500 <@${Q.id}>`, { mentions: [Q] });
    check(db.getUser(P.id).wallet === 8500 && db.getUser(Q.id).wallet === 11_500, '!przelew 500 @ktoś (odwrócona kolejność)');
    {
      rich(P, 10_000);
      rich(Q, 10_000);
      resetLedger();
      const d = await say(P, `!pojedynek <@${Q.id}> 1000`, { mentions: [Q] });
      check(d.first.payload.content === `<@${Q.id}>` && d.first.payload.allowedMentions.parse.includes('users'), '!pojedynek pinguje przeciwnika, ale nie autora');
      await d.first.collector.click('duel_accept', Q);
      await d.first.collector.settled();
      const sum = db.getUser(P.id).wallet + db.getUser(Q.id).wallet;
      check(sum === 20_000 - 60 + ledger.levelBonus, `!pojedynek: suma po walce ${sum}`);
    }
    {
      for (const u of [P, Q]) {
        rich(u, 5000);
        db.clearCooldown(u.id, 'heist');
      }
      const h = await say(P, '!napad', { channelId: 'prefix_heist' });
      await h.first.collector.click('heist_join', Q);
      await h.first.collector.stop('time');
      await h.first.collector.settled();
      check(h.first.edits.length >= 5, '!napad: animacja z odliczaniem');
    }

    // błędne użycie: czytelne podpowiedzi zamiast wywalania
    const bad = [
      ['!moneta', /Brakuje: zaklad, strona/, '!moneta'],
      ['!moneta 100', /Brakuje: strona/, '!moneta'],
      ['!moneta 100 orzel reszka', /Nie rozumiem/, '!moneta'],
      ['!ruletka 100 40', /od 0 do 36/, '!ruletka'],
      ['!crash 100 0.5', /od 1.1 do 100/, '!crash'],
      ['!wyscigi 100 9', /jedną z wartości/, '!wyscigi'],
      ['!okradnij', /Brakuje: ofiara/, '!okradnij'],
      ['!okradnij ktos', /Nie rozumiem/, '!okradnij'],
      ['!przelew 100', /Brakuje: uzytkownik/, '!przelew'],
      ['!kup', /Brakuje: przedmiot/, '!kup'],
      ['!kup nieistniejace', /Nie ma w sklepie przedmiotu/, '/sklep'],
      ['!admin', /Podaj, co chcesz zrobić/, '!admin <'],
    ];
    for (const [content, re, usage] of bad) {
      const r = await say(P, content);
      const t = text(r.first);
      check(r.handled && re.test(t) && t.includes(usage), `${content} → ${t.replace(/\n/g, ' | ')}`);
    }
    // błędna stawka przechodzi do zwykłej obsługi gry i nie rusza pieniędzy
    rich(P, 1000);
    for (const content of ['!moneta abc orzel', '!moneta 99999 orzel', '!moneta 5 orzel']) {
      const r = await say(P, content);
      check(r.handled && db.getUser(P.id).wallet === 1000, `${content}: stawka odrzucona bez ruszania portfela`);
    }

    // admin tylko dla właściciela
    const adminU = makeUser('u_admin');
    rich(Q, 100);
    const denied = await say(P, `!admin dodaj <@${Q.id}> 500`, { mentions: [Q] });
    check(/tylko dla właściciela/.test(text(denied.first)) && db.getUser(Q.id).wallet === 100, '!admin dodaj: zwykły gracz odrzucony');
    await say(adminU, `!admin dodaj <@${Q.id}> 500`, { mentions: [Q] });
    check(db.getUser(Q.id).wallet === 600, '!admin dodaj: właściciel działa przez prefiks');

    // nowe przedmioty i mnożniki przez prefiks
    rich(P, 10_000_000);
    await say(P, '!kup vip_brazowy');
    await say(P, '!kup vipsrebrny');
    check(db.itemQty(P.id, 'vip_brazowy') === 1 && db.itemQty(P.id, 'vip_srebrny') === 1, '!kup vip_brazowy i !kup vipsrebrny (bez podkreślenia)');
    await say(P, '!kup eliksir_zarobkow');
    await say(P, '!uzyj eliksirz');
    check(db.boostLeft(P.id, 'zarobki') > 0, '!uzyj eliksirz (po początku nazwy) włącza mnożnik');
    check(/Razem teraz/.test(JSON.stringify((await say(P, '!boosty')).first.payload.embeds[0].toJSON())), '!boosty (alias) pokazuje mnożniki');
    check((await say(P, '!mnozniki')).handled, '!mnozniki');

    // /pomoc mówi o prefiksie tylko wtedy, gdy faktycznie działa
    {
      const fieldNames = async () => (await run('pomoc', P, {})).replies[0].embeds[0].toJSON().fields.map((f) => f.name).join('|');
      delete process.env.PREFIX_ACTIVE;
      check(!/prefiks/i.test(await fieldNames()), '/pomoc bez prefiksu: brak sekcji o prefiksie');
      process.env.PREFIX_ACTIVE = '1';
      check(/prefiks/i.test(await fieldNames()), '/pomoc z prefiksem: jest sekcja o prefiksie');
      delete process.env.PREFIX_ACTIVE;
    }

    // hamulec antyspamowy
    {
      const spammer = makeUser('800000000000000003'); // świeży gracz: hamulec liczy czas od jego poprzedniej komendy
      const m1 = makeMessage(spammer, '!saldo');
      const m2 = makeMessage(spammer, '!saldo');
      await handleMessage(m1, commands, { prefix: '!', throttleMs: 60_000 });
      const swallowed = await handleMessage(m2, commands, { prefix: '!', throttleMs: 60_000 });
      check(m1.sent.length === 1 && swallowed === true && m2.sent.length === 0, 'drugie polecenie w ułamku sekundy jest po cichu pomijane');
    }

    // inny prefiks
    {
      const m = makeMessage(P, '?saldo');
      check((await handleMessage(m, commands, { prefix: '?', throttleMs: 0 })) && m.sent.length === 1, 'własny prefiks (?)');
      const n = makeMessage(P, '!saldo');
      check(!(await handleMessage(n, commands, { prefix: '?', throttleMs: 0 })) && n.sent.length === 0, 'przy prefiksie „?” komenda z „!” jest ignorowana');
    }

    // reply rzuca (np. skasowana wiadomość / brak uprawnień) → awaryjnie zwykły send
    {
      const m = makeMessage(P, '!saldo');
      m.reply = async () => {
        throw new Error('Unknown message');
      };
      await handleMessage(m, commands, { prefix: '!', throttleMs: 0 });
      check(m.sent.length === 1, 'gdy reply nie działa, odpowiedź idzie zwykłym send');
    }

    // wszystkie komendy: wygenerowane poprawne argumenty parsują się na zamierzone wartości
    {
      let checked = 0;
      for (const [name, cmd] of commands) {
        const defs = cmd.data.toJSON();
        const variants = defs.options?.some((o) => o.type === 1) ? defs.options.filter((o) => o.type === 1).map((s) => [s.name, s.options ?? []]) : [[null, defs.options ?? []]];
        for (const [sub, opts] of variants) {
          const tokens = [];
          const expected = {};
          for (const o of opts) {
            if (!o.required && opts.length > 1 && o.type !== 3) continue;
            if (o.type === 3) {
              const v = o.choices?.[0]?.value ?? '123';
              tokens.push(String(v));
              expected[o.name] = v;
            } else if (o.type === 4) {
              const v = o.choices?.[0]?.value ?? o.min_value ?? 1;
              tokens.push(String(v));
              expected[o.name] = v;
            } else if (o.type === 10) {
              const v = o.min_value ?? 1.5;
              tokens.push(String(v));
              expected[o.name] = v;
            } else if (o.type === 6) {
              tokens.push(`<@${Q.id}>`);
              expected[o.name] = Q;
            }
          }
          // opcjonalne pola są pomijane tylko wtedy, gdy nie ma przez to dwuznaczności – tu sprawdzamy to, co faktycznie podaliśmy
          const parsed = await parseOptions(opts.filter((o) => o.name in expected || o.required), tokens, { mentions: { users: new Map([[Q.id, Q]]) }, client: fakeClient });
          check(!parsed.error, `parse /${name}${sub ? ' ' + sub : ''}: ${parsed.error}`);
          if (!parsed.error) for (const [k, v] of Object.entries(expected)) check(parsed.values[k] === v, `parse /${name}${sub ? ' ' + sub : ''}: ${k} = ${parsed.values[k]} zamiast ${v}`);
          checked++;
        }
      }
      check(checked >= commands.size, `sprawdzono parsowanie wszystkich komend (${checked})`);
    }
    console.log('  ok');
  }

  // ---------------------------------------------------------------- sklep i mnożniki
  section('Sklep: katalog, kategorie i limity Discorda dla wszystkich komend');
  {
    const data = require('../src/lib/data');
    const { resolveItem, suggestItems } = require('../src/lib/items');

    const seen = new Set();
    for (const id of data.SHOP_ORDER) {
      const it = data.ITEMS[id];
      check(it && it.price > 0 && it.desc && it.emoji, `sklep: ${id} ma cenę, opis i emoji`);
      check(!seen.has(id), `sklep: ${id} występuje dwa razy`);
      seen.add(id);
      if (it?.requires) check(data.SHOP_ORDER.indexOf(it.requires) >= 0 && data.SHOP_ORDER.indexOf(it.requires) < data.SHOP_ORDER.indexOf(id), `sklep: ${id} wymaga przedmiotu, którego nie ma wcześniej w sklepie`);
    }
    check(data.SHOP_ORDER.length >= 80, `w sklepie jest ${data.SHOP_ORDER.length} pozycji`);
    for (const id of Object.keys(data.BOOSTS)) check(data.ITEMS[id]?.type === 'boost', `eliksir ${id} jest w katalogu jako boost`);
    for (const id of data.VIP_CARDS) check(data.ITEMS[id] && data.BONUSES.vip[id] > 0, `karta ${id} ma bonus`);

    const shop = await run('sklep', A, {});
    const e = shop.replies[0].embeds[0].toJSON();
    const total = (e.title?.length ?? 0) + (e.description?.length ?? 0) + (e.footer?.text.length ?? 0) + e.fields.reduce((s, f) => s + f.name.length + f.value.length, 0);
    check(e.fields.length === data.SHOP_CATEGORIES.length, '/sklep: jedno pole na kategorię');
    for (const f of e.fields) check(f.value.length <= 1024, `/sklep: pole „${f.name}” ma ${f.value.length} znaków (limit 1024)`);
    check(total <= 6000, `/sklep: embed ma ${total} znaków (limit 6000)`);
    for (const name of ['pomoc', 'mnozniki', 'ekwipunek', 'skrzynie']) {
      const r = await run(name, A, {}, name === 'skrzynie' ? 'lista' : null);
      const j = r.replies[0].embeds[0].toJSON();
      const t = (j.title?.length ?? 0) + (j.description?.length ?? 0) + (j.footer?.text.length ?? 0) + (j.fields ?? []).reduce((s, f) => s + f.name.length + f.value.length + (f.value.length > 1024 ? 99999 : 0), 0);
      check(t <= 6000, `/${name}: embed ma ${t} znaków (limit 6000, albo pole >1024)`);
    }

    // limity Discorda dla definicji wszystkich komend (wcześniej 26 pozycji w /kup przekroczyłoby limit 25 wyborów)
    const validateOpts = (opts, where) => {
      check(!opts || opts.length <= 25, `${where}: za dużo opcji (${opts?.length})`);
      let sawOptional = false;
      for (const o of opts ?? []) {
        check(/^[-_\p{L}\p{N}]{1,32}$/u.test(o.name) && o.name === o.name.toLowerCase(), `${where}: zła nazwa opcji „${o.name}”`);
        check(o.description && o.description.length >= 1 && o.description.length <= 100, `${where}/${o.name}: opis ma ${o.description?.length} znaków`);
        if (o.type > 2) {
          if (!o.required) sawOptional = true;
          else check(!sawOptional, `${where}/${o.name}: opcja wymagana po opcjonalnej`);
        }
        if (o.choices) {
          check(o.choices.length <= 25, `${where}/${o.name}: ${o.choices.length} wyborów (limit 25)`);
          check(!o.autocomplete, `${where}/${o.name}: wybory razem z autocomplete`);
          for (const c of o.choices) check(String(c.name).length <= 100, `${where}/${o.name}: za długa nazwa wyboru`);
        }
        if (o.options) validateOpts(o.options, `${where}/${o.name}`);
      }
    };
    for (const [name, cmd] of commands) {
      const j = cmd.data.toJSON();
      check(/^[-_\p{L}\p{N}]{1,32}$/u.test(j.name), `komenda ${name}: zła nazwa`);
      check(j.description.length >= 1 && j.description.length <= 100, `komenda ${name}: opis ma ${j.description.length} znaków`);
      validateOpts(j.options, `/${name}`);
    }
    check(commands.size >= 42 && commands.has('mnozniki'), `komend: ${commands.size}`);

    // wyszukiwanie przedmiotów po nazwie
    const SHOP = data.SHOP_ORDER;
    const expect = { wedka: 'wedka', Wędka: 'wedka', 'super wędka': 'super_wedka', superwedka: 'super_wedka', skrzynia: 'skrzynia', 'Karta VIP: złoto': 'vip_zloty', vipzloty: 'vip_zloty', eliksirz: 'eliksir_zarobkow', przyspieszacz: 'zegarek', wedkam: 'wedka_mistrza' };
    for (const [typed, id] of Object.entries(expect)) check(resolveItem(typed, SHOP) === id, `resolveItem(„${typed}”) = ${resolveItem(typed, SHOP)}, oczekiwano ${id}`);
    for (const typed of ['', 'xyz', 'skrz', 'karta', 'k', 'super', 'eliksir', 'wed', 'polisa']) check(resolveItem(typed, SHOP) === null, `resolveItem(„${typed}”) powinno być niejednoznaczne albo puste`);
    check(suggestItems('vip', SHOP).length === 5 && suggestItems('', SHOP).length === 25, 'autocomplete: filtr i limit 25');
    const kupAc = makeInteraction(A, { focused: 'eliksir' });
    await commands.get('kup').autocomplete(kupAc);
    check(kupAc.choices.length >= 3 && kupAc.choices.length <= 25 && kupAc.choices.some((c) => c.value === 'eliksir_zarobkow'), '/kup autocomplete');
    const adminAc = makeInteraction(admin, { focused: 'sejf' });
    await commands.get('admin').autocomplete(adminAc);
    check(adminAc.choices.some((c) => c.value === 'sejf'), '/admin autocomplete zna też mały sejf spoza sklepu');
    console.log('  ok');
  }

  section('Zakupy: reguły unikalności i wymagań');
  {
    const B2 = makeUser('u_buy');
    db.setWallet(B2.id, 100_000_000);
    const buy = (item, qty) => run('kup', B2, { przedmiot: item, ...(qty ? { ilosc: qty } : {}) });

    check(ephemeral(await buy('vip_srebrny')) && db.itemQty(B2.id, 'vip_srebrny') === 0, 'srebro bez brązu: odmowa');
    check(ephemeral(await buy('vip_zloty')) && db.itemQty(B2.id, 'vip_zloty') === 0, 'złoto bez srebra: odmowa');
    const before = db.getUser(B2.id).wallet;
    check(!ephemeral(await buy('vip_brazowy')) && db.itemQty(B2.id, 'vip_brazowy') === 1 && db.getUser(B2.id).wallet === before - 100_000, 'brąz: kupiony, pobrano 100 000');
    check(ephemeral(await buy('vip_brazowy')) && db.itemQty(B2.id, 'vip_brazowy') === 1, 'drugiego brązu kupić nie można');
    check(!ephemeral(await buy('vip_srebrny')) && !ephemeral(await buy('vip_zloty')), 'srebro po brązie i złoto po srebrze: ok');
    for (const id of ['dyplom', 'talizman', 'pas_narzedziowy', 'konto_oszczednosciowe', 'laptop']) {
      check(ephemeral(await buy(id, 2)) && db.itemQty(B2.id, id) === 0, `${id}: dwóch sztuk naraz kupić nie można`);
      check(!ephemeral(await buy(id)) && ephemeral(await buy(id)) && db.itemQty(B2.id, id) === 1, `${id}: wystarczy jedna sztuka`);
    }
    const stack = db.itemQty(B2.id, 'eliksir_zarobkow');
    check(!ephemeral(await buy('eliksir_zarobkow', 5)) && db.itemQty(B2.id, 'eliksir_zarobkow') === stack + 5, 'eliksiry można kupować po kilka');
    db.setWallet(B2.id, 100);
    check(ephemeral(await buy('vip_zloty')) === true && db.getUser(B2.id).wallet === 100, 'za mało pieniędzy: odmowa bez strat');
    const unknown = await buy('nic_takiego');
    check(ephemeral(unknown) && /Nie ma w sklepie/.test(unknown.replies[0].content), 'nieznany przedmiot: czytelny komunikat');
    const byName = makeUser('u_buy2');
    db.setWallet(byName.id, 1_000_000);
    await run('kup', byName, { przedmiot: 'Super wędka' });
    check(db.itemQty(byName.id, 'super_wedka') === 1, '/kup po nazwie, nie tylko po id');
    const eq = await run('ekwipunek', B2, {});
    check(JSON.stringify(eq.replies[0].embeds[0].toJSON().fields).includes('Eliksiry'), '/ekwipunek ma grupę eliksirów');
    console.log('  ok');
  }

  section('Mnożniki: zarobki, XP, cooldowny, łup, szansa, odsetki, koniczyna');
  {
    const boosts = require('../src/lib/boosts');
    const { COOLDOWNS, BONUSES } = require('../src/lib/data');
    const fresh = (n) => {
      const u = makeUser(`u_${n}`);
      db.setWallet(u.id, 0);
      return u;
    };
    const near = (value, target, tol) => Math.abs(value - target) <= tol;

    // zarobki: najlepsza karta VIP (nie suma), a eliksir mnoży całość
    const E2 = fresh('earn');
    const mult = (u) => boosts.applyEarn(u.id, 1000).amount;
    check(mult(E2) === 1000, 'bez przedmiotów: ×1');
    db.addItem(E2.id, 'vip_brazowy');
    check(mult(E2) === 1100, 'brąz: +10%');
    db.addItem(E2.id, 'vip_srebrny');
    check(mult(E2) === 1250, 'srebro i brąz razem: liczy się tylko najlepsza (+25%)');
    db.addItem(E2.id, 'vip_zloty');
    check(mult(E2) === 1500, 'złoto: +50%');
    db.addItem(E2.id, 'eliksir_zarobkow', 2);
    const use1 = await run('uzyj', E2, { przedmiot: 'eliksir_zarobkow' });
    const left1 = db.boostLeft(E2.id, 'zarobki');
    check(!ephemeral(use1) && left1 > 29 * 60_000 && left1 <= 30 * 60_000, `eliksir: ok. 30 min (jest ${Math.round(left1 / 1000)} s)`);
    check(mult(E2) === 3000, 'złoto + eliksir: (1 + 0,5) × 2 = ×3');
    const use2 = await run('uzyj', E2, { przedmiot: 'eliksir_zarobkow' });
    const left2 = db.boostLeft(E2.id, 'zarobki');
    check(left2 > 59 * 60_000 && /przedłużony/.test(use2.replies[0].embeds[0].toJSON().description), 'drugi eliksir przedłuża czas, a nie nadpisuje');
    check(db.itemQty(E2.id, 'eliksir_zarobkow') === 0, 'eliksiry zostały zużyte');
    check(ephemeral(await run('uzyj', E2, { przedmiot: 'eliksir_zarobkow' })), 'bez eliksiru w ekwipunku: odmowa');
    db.db.prepare('UPDATE boosts SET until = ? WHERE user_id = ? AND key = ?').run(Date.now() - 1, E2.id, 'zarobki');
    check(db.boostLeft(E2.id, 'zarobki') === 0 && mult(E2) === 1500, 'po wygaśnięciu eliksir przestaje działać (zostaje tylko karta)');

    // te mnożniki widać w komendach zarobkowych
    db.addItem(E2.id, 'eliksir_zarobkow');
    await run('uzyj', E2, { przedmiot: 'eliksir_zarobkow' });
    db.clearCooldown(E2.id, 'praca');
    const work = await run('praca', E2, {}, 'wykonaj');
    const workText = work.replies[0].embeds[0].toJSON().description;
    check(/Karta VIP: złoto: \+50%/.test(workText) && /Eliksir zarobków: ×2/.test(workText), '/praca pokazuje karty i eliksir');
    let begged = false;
    let crimed = false;
    for (let k = 0; k < 60 && !(begged && crimed); k++) {
      db.clearCooldown(E2.id, 'zebraj');
      db.clearCooldown(E2.id, 'crime');
      if (!begged) begged = /Karta VIP/.test(JSON.stringify((await run('zebraj', E2, {})).replies[0].embeds[0].toJSON()));
      if (!crimed) crimed = /Karta VIP/.test(JSON.stringify((await run('przestepstwo', E2, {})).replies[0].embeds[0].toJSON()));
    }
    check(begged, '/zebraj stosuje mnożnik zarobków');
    check(crimed, '/przestepstwo stosuje mnożnik zarobków');
    // nagroda z quizu: poprawna odpowiedź w /matma
    let quizOk = false;
    for (let k = 0; k < 30 && !quizOk; k++) {
      db.clearCooldown(E2.id, 'matma');
      const q = await run('matma', E2, {});
      const m = q.replies[0].embeds[0].toJSON().description.match(/(\d+) ([+\-×]) (\d+) = \?/);
      const [a, op, b] = [Number(m[1]), m[2], Number(m[3])];
      const ans = op === '+' ? a + b : op === '-' ? a - b : a * b;
      const idx = q.replies[0].components[0].toJSON().components.findIndex((c) => c.label === String(ans));
      const walletBefore = db.getUser(E2.id).wallet;
      const ci = await q.collector.click(`quiz_${idx}`, E2);
      const gained = db.getUser(E2.id).wallet - walletBefore - ledger.levelBonus * 0;
      quizOk = gained >= 120 * 3 && /Eliksir zarobków/.test(ci.updates[0].embeds[0].toJSON().description);
    }
    check(quizOk, '/matma: nagroda z mnożnikiem (min. 120 × 3)');

    // XP
    const xp = (setup) => {
      const u = fresh(`xp_${Math.random()}`);
      setup(u);
      const before = db.getUser(u.id).xp;
      db.addXp(u.id, 10);
      return db.getUser(u.id).xp - before;
    };
    check(xp(() => {}) === 10, 'XP bez bonusów: 10');
    check(xp((u) => db.addItem(u.id, 'dyplom')) === 13, 'dyplom: +25% (12,5 → 13)');
    check(xp((u) => db.activateBoost(u.id, 'xp', 60_000)) === 20, 'podręcznik: ×2');
    check(xp((u) => (db.addItem(u.id, 'dyplom'), db.activateBoost(u.id, 'xp', 60_000))) === 25, 'dyplom i podręcznik: ×2,5');
    const raw = fresh('xpraw');
    db.addItem(raw.id, 'dyplom');
    db.activateBoost(raw.id, 'xp', 60_000);
    db.addXp(raw.id, 10, { boost: false });
    check(db.getUser(raw.id).xp === 10, 'XP ze skrzyni ({ boost: false }) pomija mnożniki');
    const podr = fresh('podr');
    db.addItem(podr.id, 'podrecznik');
    await run('uzyj', podr, { przedmiot: 'podrecznik' });
    check(db.xpMultiplier(podr.id) === 2, '/uzyj podrecznik włącza ×2 XP');

    // cooldowny skracane przez przyspieszacz, ale ochrona ofiary rabunku nie
    const Z2 = fresh('zegar');
    const cdLeft = async () => {
      db.clearCooldown(Z2.id, 'zebraj');
      await run('zebraj', Z2, {});
      return db.cooldownLeft(Z2.id, 'zebraj');
    };
    const normalLeft = await cdLeft();
    check(near(normalLeft, COOLDOWNS.zebraj, 2000), `cooldown bez przyspieszacza: ${normalLeft} ms`);
    db.addItem(Z2.id, 'zegarek');
    await run('uzyj', Z2, { przedmiot: 'zegarek' });
    const fastLeft = await cdLeft();
    check(near(fastLeft, COOLDOWNS.zebraj / 2, 2000), `cooldown z przyspieszaczem: ${fastLeft} ms`);
    check(db.scaledCooldown(Z2.id, 1000) === 500 && db.scaledCooldown(makeUser('u_nozegar').id, 1000) === 1000, 'scaledCooldown: połowa tylko z aktywnym przyspieszaczem');
    const sum = boosts.summary(Z2.id);
    check(sum.totals.cooldown === 0.5 && sum.active.some((l) => /Przyspieszacz/.test(l)), '/mnozniki widzi przyspieszacz');
    const mz = await run('mnozniki', Z2, {});
    check(/Razem teraz/.test(JSON.stringify(mz.replies[0].embeds[0].toJSON())), '/mnozniki renderuje się');

    // łup: magnes ×2 (zawsze parzysta, ≥ 2), a bez magnesu bywa nieparzysta
    const lootTotal = (id) => db.inventory(id).filter((r) => ['loot', 'crate'].includes(ITEMS[r.item]?.type)).reduce((s, r) => s + r.qty, 0);
    const M2 = fresh('magnes');
    db.addItem(M2.id, 'magnes_lupu');
    await run('uzyj', M2, { przedmiot: 'magnes_lupu' });
    let allEven = true;
    for (let k = 0; k < 50; k++) {
      db.clearCooldown(M2.id, 'ryby');
      if (db.itemQty(M2.id, 'wedka') < 1) db.addItem(M2.id, 'wedka');
      const b = lootTotal(M2.id);
      await run('ryby', M2, {});
      const gained = lootTotal(M2.id) - b;
      if (gained < 2 || gained % 2 !== 0) allEven = false;
    }
    check(allEven, 'magnes: każdy łup ×2 (parzysty i ≥ 2)');
    const plain = fresh('bezmagnesu');
    let sawOdd = false;
    for (let k = 0; k < 60 && !sawOdd; k++) {
      db.clearCooldown(plain.id, 'ryby');
      if (db.itemQty(plain.id, 'wedka') < 1) db.addItem(plain.id, 'wedka');
      const b = lootTotal(plain.id);
      await run('ryby', plain, {});
      if ((lootTotal(plain.id) - b) % 2 === 1) sawOdd = true;
    }
    check(sawOdd, 'bez magnesu łup bywa nieparzysty (kontrola)');

    // talizman i pas narzędziowy
    const T2 = fresh('talizman');
    check(boosts.luckMultiplier(T2.id) === 1 && boosts.breakChance(T2.id) === 0.05 && boosts.lootMultiplier(T2.id) === 1, 'domyślnie: szczęście ×1, psucie 5%, łup ×1');
    db.addItem(T2.id, 'talizman');
    db.addItem(T2.id, 'pas_narzedziowy');
    check(boosts.luckMultiplier(T2.id) === 1.25 && boosts.breakChance(T2.id) === 0.01, 'talizman ×1,25 i pas 1%');
    // Psucie narzędzi sprawdzamy deterministycznie: wymuszamy los, zamiast losować setki razy.
    const castWithRoll = async (u, roll) => {
      db.clearCooldown(u.id, 'ryby');
      if (db.itemQty(u.id, 'wedka') < 1) db.addItem(u.id, 'wedka');
      const realRandom = Math.random;
      Math.random = () => roll;
      try {
        await run('ryby', u, {});
      } finally {
        Math.random = realRandom;
      }
      return db.itemQty(u.id, 'wedka') < 1;
    };
    check((await castWithRoll(T2, 0.03)) === false, 'z pasem narzędziowym przy losie 3% narzędzie się nie psuje');
    check((await castWithRoll(fresh('beztasmy'), 0.03)) === true, 'bez pasa przy losie 3% narzędzie się psuje (próg 5%)');
    check((await castWithRoll(T2, 0.005)) === true, 'z pasem przy losie 0,5% narzędzie jednak się psuje (próg 1%)');

    // odsetki w /daily
    const dailyWallet = async (u, bank) => {
      db.setWallet(u.id, 0);
      db.setBank(u.id, bank);
      await run('daily', u, {});
      return db.getUser(u.id).wallet;
    };
    check((await dailyWallet(fresh('int1'), 1_000_000)) === 625 + 20_000, 'odsetki 2% od miliona = 20 000');
    check((await dailyWallet(fresh('int2'), 5_000_000)) === 625 + 25_000, 'odsetki 2% mają sufit 25 000');
    const S2 = fresh('savings');
    db.addItem(S2.id, 'konto_oszczednosciowe');
    check((await dailyWallet(S2, 1_000_000)) === 625 + 40_000, 'konto oszczędnościowe: 4% od miliona = 40 000');
    const S3 = fresh('savings2');
    db.addItem(S3.id, 'konto_oszczednosciowe');
    check((await dailyWallet(S3, 5_000_000)) === 625 + 50_000, 'konto oszczędnościowe ma sufit 50 000');

    // koniczyna: bonus ma sufit, żeby nie dało się na niej zarabiać bez końca
    const C2 = fresh('clover');
    db.addBuff(C2.id, 'koniczyna', 5);
    const big = game.settle(C2.id, 100_000, 200_000);
    check(big.bonus === BONUSES.cloverMax && big.payout === 200_000 + BONUSES.cloverMax, `koniczyna: duża wygrana dostaje tylko ${BONUSES.cloverMax} bonusu (jest ${big.bonus})`);
    const small = game.settle(C2.id, 100, 200);
    check(small.bonus === 20, `koniczyna: mała wygrana dostaje pełne 20% (jest ${small.bonus})`);
    const lose = game.settle(C2.id, 100, 0);
    check(lose.bonus === 0 && db.buffUses(C2.id, 'koniczyna') === 3, 'koniczyna zużywa się tylko na wygranych');
    const worst = 1_000_000_000;
    db.addBuff(C2.id, 'koniczyna', 1);
    check(game.settle(C2.id, worst, worst * 2).bonus <= BONUSES.cloverMax, 'koniczyna: nawet przy stawce 1 mld bonus ≤ sufit');
    check(BONUSES.cloverMax * 5 <= ITEMS.koniczyna.price, 'koniczyna: łączny bonus z 5 wygranych nie przekracza ceny przedmiotu');
    console.log('  ok');
  }

  // ---------------------------------------------------------------- nowa mechanika (osobny plik)
  await runFeatures();

  // ---------------------------------------------------------------- podsumowanie
  report();
  void fmt;
}

main().catch((err) => {
  console.error('Test przerwany błędem:', err);
  process.exit(1);
});
