// Test strony: prawdziwy serwer HTTP na losowym porcie, prawdziwe żądania, strumień SSE i komendy z src/commands.
// Czasów gier nie skracamy (BOT_FAST wyłączony), bo lobby jackpota i pojedynku muszą chwilę żyć.
process.env.DB_PATH = ':memory:';
delete process.env.BOT_FAST;
process.env.WEB_SECRET = 'test-secret';

const db = require('../src/lib/db');
const { loadCommands } = require('../src/loader');
const { createWebServer } = require('../src/web/server');
const accounts = require('../src/web/accounts');

const failures = [];
const check = (cond, msg) => {
  if (!cond) {
    failures.push(msg);
    console.log(`  ❌ ${msg}`);
  }
};
const section = (t) => console.log(`\n▶ ${t}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Klient HTTP z ciasteczkiem i podglądem strumienia zdarzeń. */
class Client {
  static counter = 1;

  constructor(base) {
    this.base = base;
    // Osobny „adres IP” dla każdego klienta, żeby limity prób logowania nie mieszały testów między sobą.
    this.ip = `10.0.${Client.counter >> 8}.${Client.counter++ & 255}`;
    this.cookie = '';
    this.events = [];
    this.user = null;
    this.abort = null;
  }

  async req(method, path, body, headers = {}) {
    const res = await fetch(this.base + path, {
      method,
      headers: { 'X-Forwarded-For': this.ip, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(this.cookie ? { Cookie: this.cookie } : {}), ...headers },
      body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
    });
    const set = res.headers.get('set-cookie');
    if (set) this.cookie = set.split(';')[0].endsWith('=') ? '' : set.split(';')[0];
    const text = await res.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch {
      /* nie-JSON (pliki statyczne) */
    }
    return { status: res.status, json, text, headers: res.headers };
  }

  async register(name, password = 'haslo123') {
    const r = await this.req('POST', '/api/register', { username: name, password });
    if (r.status === 200) this.user = r.json.user;
    return r;
  }

  async open() {
    this.abort = new AbortController();
    const res = await fetch(this.base + '/api/stream', { headers: { Cookie: this.cookie }, signal: this.abort.signal });
    this.streamStatus = res.status;
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    (async () => {
      try {
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buf += dec.decode(value, { stream: true });
          let i;
          while ((i = buf.indexOf('\n\n')) >= 0) {
            const chunk = buf.slice(0, i);
            buf = buf.slice(i + 2);
            const ev = /^event: (.+)$/m.exec(chunk)?.[1];
            const data = /^data: (.+)$/m.exec(chunk)?.[1];
            if (ev && data) this.events.push({ event: ev, data: JSON.parse(data) });
          }
        }
      } catch {
        /* przerwane przez close() */
      }
    })();
    await this.waitFor((e) => e.event === 'hello');
  }

  close() {
    this.abort?.abort();
  }

  async waitFor(pred, ms = 8000) {
    const end = Date.now() + ms;
    while (Date.now() < end) {
      const hit = this.events.find(pred);
      if (hit) return hit;
      await sleep(15);
    }
    throw new Error(`Timeout czekania na zdarzenie (${this.events.map((e) => e.event).join(',')})`);
  }

  /** Uruchamia komendę i czeka na jej koniec. Zwraca wiadomości tej komendy (ostatnia wersja każdej). */
  async run(name, options = {}, sub = null) {
    const start = this.events.length;
    const r = await this.req('POST', '/api/cmd', { name, sub, options });
    if (r.status !== 200) return { status: r.status, error: r.json?.error, msgs: [] };
    const run = r.json.run;
    await this.waitFor((e) => e.event === 'done' && e.data.run === run, 15000);
    const msgs = new Map();
    for (const e of this.events.slice(start)) if (['msg', 'edit'].includes(e.event) && e.data.run === run) msgs.set(e.data.id, e.data);
    return { status: 200, run, msgs: [...msgs.values()] };
  }

  /** Ostatnia znana wersja wiadomości o danym id. */
  latest(id) {
    return this.events.filter((e) => ['msg', 'edit'].includes(e.event) && e.data.id === id).at(-1)?.data;
  }
}

const text = (m) =>
  [m.content, ...(m.embeds ?? []).flatMap((e) => [e.title, e.description, ...(e.fields ?? []).flatMap((f) => [f.name, f.value])])].filter(Boolean).join('\n');

async function main() {
  const commands = loadCommands();
  const app = createWebServer({ commands, scheduler: false, trustProxy: true });
  await new Promise((r) => app.server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${app.server.address().port}`;
  const anon = new Client(base);

  // ------------------------------------------------------------------ dostęp i bezpieczeństwo
  section('Dostęp: pliki, brak sesji, nagłówki bezpieczeństwa');
  {
    const home = await anon.req('GET', '/');
    check(home.status === 200 && /text\/html/.test(home.headers.get('content-type')), 'strona główna się serwuje');
    check(/default-src 'self'/.test(home.headers.get('content-security-policy')) && home.headers.get('x-frame-options') === 'DENY', 'nagłówki bezpieczeństwa (CSP, X-Frame-Options)');
    check((await anon.req('GET', '/../package.json')).status !== 200 && (await anon.req('GET', '/%2e%2e/package.json')).status !== 200, 'nie da się wyjść poza folder public');
    check((await anon.req('GET', '/nie-ma.js')).status === 404, 'nieistniejący plik → 404');
    for (const p of ['/api/me', '/api/catalog', '/api/stream', '/api/leaderboard', '/api/players', '/api/profile/x', '/api/feed']) {
      check((await anon.req('GET', p)).status === 401, `GET ${p} bez sesji → 401`);
    }
    for (const p of ['/api/cmd', '/api/click']) check((await anon.req('POST', p, {})).status === 401, `POST ${p} bez sesji → 401`);
    check((await anon.req('GET', '/api/session')).json.user === null, '/api/session bez logowania: user null');
    check((await anon.req('GET', '/api/nie-ma')).status === 404, 'nieznana ścieżka API → 404');
    console.log('  ok');
  }

  section('Konta: rejestracja, logowanie, wylogowanie');
  const ann = new Client(base);
  const bob = new Client(base);
  const cat = new Client(base);
  {
    const c = new Client(base);
    check((await c.register('ab')).status === 400, 'za krótka nazwa → 400');
    check((await c.register('zła nazwa!')).status === 400, 'niedozwolone znaki → 400');
    check((await c.register('Janek', '123')).status === 400, 'za krótkie hasło → 400');
    check((await ann.register('Ania')).status === 200 && ann.user.id.startsWith('w_'), 'rejestracja działa i nadaje id');
    check((await c.register('ania')).status === 400, 'nazwa zajęta (bez względu na wielkość liter) → 400');
    check((await bob.register('Bartek')).status === 200 && (await cat.register('Celina_9')).status === 200, 'kolejne konta');
    check(db.getUser(ann.user.id).wallet === 500, 'nowe konto dostaje 500 monet');
    check((await ann.req('GET', '/api/session')).json.user.name === 'Ania', '/api/session po rejestracji');

    const fresh = new Client(base);
    check((await fresh.req('POST', '/api/login', { username: 'Ania', password: 'zlehaslo' })).status === 401, 'złe hasło → 401');
    check((await fresh.req('POST', '/api/login', { username: 'nikt', password: 'haslo123' })).status === 401, 'nieistniejące konto → 401');
    const ok = await fresh.req('POST', '/api/login', { username: 'ania', password: 'haslo123' });
    check(ok.status === 200 && fresh.cookie.startsWith('sid='), 'logowanie (nazwa bez względu na wielkość liter)');
    check((await fresh.req('GET', '/api/me')).json.id === ann.user.id, '/api/me po zalogowaniu');
    await fresh.req('POST', '/api/logout', {});
    check(fresh.cookie === '' && (await fresh.req('GET', '/api/me')).status === 401, 'wylogowanie czyści sesję');

    const forged = new Client(base);
    forged.cookie = `sid=${ann.cookie.split('=')[1].replace(/.$/, (ch) => (ch === 'A' ? 'B' : 'A'))}`;
    check((await forged.req('GET', '/api/me')).status === 401, 'podrobiony token sesji → 401');
    const wrongUser = new Client(base);
    wrongUser.cookie = `sid=${accounts.makeToken('w_0123456789abcdef')}`;
    check((await wrongUser.req('GET', '/api/me')).status === 401, 'token nieistniejącego konta → 401');
    const expired = new Client(base);
    expired.cookie = `sid=${accounts.makeToken(ann.user.id, Date.now() - accounts.SESSION_MS - 1000)}`;
    check((await expired.req('GET', '/api/me')).status === 401, 'wygasły token → 401');
    console.log('  ok');
  }

  section('Zapytania: JSON, origin, rozmiar, limit prób logowania');
  {
    check((await ann.req('POST', '/api/cmd', 'name=saldo', { 'Content-Type': 'text/plain' })).status === 415, 'zły Content-Type → 415');
    check((await ann.req('POST', '/api/cmd', '{zepsuty')).status === 400, 'zepsuty JSON → 400');
    check((await ann.req('POST', '/api/cmd', '[1,2]')).status === 400, 'tablica zamiast obiektu → 400');
    check((await ann.req('POST', '/api/cmd', { name: 'saldo', pad: 'x'.repeat(20_000) })).status === 413, 'za duże zapytanie → 413');
    check((await ann.req('POST', '/api/cmd', { name: 'saldo' }, { Origin: 'http://evil.example' })).status === 403, 'obcy Origin → 403 (ochrona CSRF)');
    check((await ann.req('POST', '/api/cmd', { name: 'saldo' }, { Origin: base })).status === 200, 'własny Origin przechodzi');
    check((await ann.req('PUT', '/api/me', {})).status === 405, 'zła metoda → 405');
    const spam = new Client(base);
    let last = 0;
    for (let i = 0; i < 12; i++) last = (await spam.req('POST', '/api/login', { username: 'Ania', password: 'nie' })).status;
    check(last === 429, 'po serii złych logowań → 429');
    console.log('  ok');
  }

  // ------------------------------------------------------------------ dane
  section('Dane: stan gracza, katalog, ranking, wyszukiwanie');
  {
    const me = (await ann.req('GET', '/api/me')).json;
    check(me.wallet === 500 && me.level === 1 && me.bankInfo.cap === 100_000 && me.cooldowns && Array.isArray(me.inventory), '/api/me: saldo, poziom, limit banku');
    check(me.daily === 0 && me.weekly === 0 && me.businesses.owned.length === 0 && me.farm.length === 4, '/api/me: daily, biznesy, farma');
    const cat1 = (await ann.req('GET', '/api/catalog')).json;
    check(Object.keys(cat1.items).length >= 110 && cat1.categories.length === 10 && cat1.businesses.length === 8, '/api/catalog: przedmioty, kategorie, biznesy');
    const names = cat1.commands.map((c) => c.name);
    check(names.length >= 50 && !names.includes('admin') && names.includes('kurczak'), '/api/catalog: komendy bez /admin');
    check(cat1.games.plinko.mults.szalone.length === 13 && cat1.games.kolo.segments.length === 10 && cat1.crates.skrzynka.ev > 0, '/api/catalog: stałe gier i skrzyń');
    const players = (await ann.req('GET', '/api/players?q=ba')).json.players;
    check(players.length === 1 && players[0].name === 'Bartek' && players[0].online === false, '/api/players: podpowiedź po początku nazwy (bez siebie)');
    check((await ann.req('GET', '/api/players?q=%25')).json.players.length === 0, '/api/players: znak % nie jest wieloznacznikiem');
    const lb = (await ann.req('GET', '/api/leaderboard?by=majatek')).json;
    check(lb.rows.length === 3 && lb.rows[0].place === 1 && lb.rows[0].name, '/api/leaderboard');
    check((await ann.req('GET', '/api/leaderboard?by=; DROP TABLE')).json.by === 'majatek', 'ranking: zły parametr wraca do majątku');
    const prof = (await ann.req('GET', `/api/profile/${bob.user.id}`)).json;
    check(prof.name === 'Bartek' && prof.wealth === 500 && !('inventory' in prof) && !('cooldowns' in prof), '/api/profile: publiczne dane bez ekwipunku');
    check((await ann.req('GET', '/api/profile/w_0000000000000000')).status === 404, 'profil nieistniejącego gracza → 404');
    console.log('  ok');
  }

  // ------------------------------------------------------------------ komendy
  section('Komendy: walidacja opcji i uruchamianie');
  await ann.open();
  await bob.open();
  {
    check((await ann.run('nie_ma')).status === 400, 'nieznana komenda → 400');
    check((await ann.run('admin', { akcja: 'x' })).status === 400, '/admin zablokowane na stronie');
    check((await ann.run('moneta', { zaklad: '100' })).error?.includes('strona'), 'brak wymaganej opcji → błąd z nazwą');
    check((await ann.run('moneta', { zaklad: '100', strona: 'glowa' })).status === 400, 'wartość spoza wyborów → 400');
    check((await ann.run('szansa', { zaklad: '100', szansa: 99 })).status === 400, 'liczba poza zakresem (max 95) → 400');
    check((await ann.run('kup', { przedmiot: 'wedka', ilosc: 'dużo' })).status === 400, 'tekst zamiast liczby → 400');
    check((await ann.run('bank', {}, 'nie_ma')).status === 400 && (await ann.run('bank', {})).status === 400, 'zła albo brakująca podkomenda → 400');
    check((await ann.run('profil', { uzytkownik: 'w_ffffffffffffffff' })).status === 400, 'nieistniejący gracz w opcji → 400');

    const saldo = await ann.run('saldo');
    check(saldo.msgs.length === 1 && /500/.test(text(saldo.msgs[0])), `/saldo odpowiada na stronie (${saldo.msgs.length}: ${saldo.msgs[0] && text(saldo.msgs[0])})`);
    const profil = await ann.run('profil', { uzytkownik: bob.user.id });
    check(profil.msgs.length === 1 && text(profil.msgs[0]).includes('Bartek'), '/profil innego gracza (opcja typu użytkownik)');

    // Zakupy to ten sam kod co w bocie: za mało monet = odmowa, po dopłacie zakup przechodzi.
    const poor = await ann.run('kup', { przedmiot: 'koszyk', ilosc: 1 });
    check(poor.msgs.length === 1 && poor.msgs[0].ephemeral && /Potrzebujesz/.test(text(poor.msgs[0])) && db.itemQty(ann.user.id, 'koszyk') === 0, '/kup za mało monet → prywatna odmowa');
    db.setWallet(ann.user.id, 10_000);
    const buy = await ann.run('kup', { przedmiot: 'koszyk', ilosc: 1 });
    check(buy.msgs.length === 1 && /Zakup udany/.test(text(buy.msgs[0])) && db.itemQty(ann.user.id, 'koszyk') === 1 && db.getUser(ann.user.id).wallet === 7000, '/kup: zakup pobiera cenę i daje przedmiot');
    const me = await ann.waitFor((e) => e.event === 'me' && e.data.wallet === 7000);
    check(me.data.inventory.some((r) => r.item === 'koszyk'), 'po zakupie przychodzi odświeżony stan gracza (SSE „me”)');
    const bank = await ann.run('bank', {}, 'info');
    check(bank.msgs.length === 1 && /Twój bank/.test(text(bank.msgs[0])), '/bank info (podkomenda)');
    console.log('  ok');
  }

  section('Gry: wynik rozliczony co do monety, wiadomości z klatkami animacji');
  {
    db.setWallet(ann.user.id, 10_000);
    const before = ann.events.length;
    const r = await ann.run('moneta', { zaklad: '1000', strona: 'orzel' });
    const msg = r.msgs[0];
    check(r.msgs.length === 1 && /Wypadło/.test(text(msg)), '/moneta: wiadomość z wynikiem');
    const frames = ann.events.slice(before).filter((e) => ['msg', 'edit'].includes(e.event) && e.data.run === r.run).length;
    check(frames >= 5, `/moneta: klatki animacji dotarły osobno (${frames})`);
    const wallet = db.getUser(ann.user.id).wallet;
    check(wallet === 9000 || wallet === 10_950, `/moneta: saldo ${wallet} to przegrana (9000) albo wygrana x1.95 (10950)`);
    const stat = db.getUser(ann.user.id);
    check(stat.games === 1 && stat.total_won + stat.total_lost === 950 + 0 || stat.total_lost === 1000, '/moneta: statystyki zapisane');
    check((await ann.run('moneta', { zaklad: '100000', strona: 'orzel' })).msgs[0].ephemeral, 'za duża stawka → prywatna odmowa');
  }
  {
    // Gra z przyciskami przez HTTP: start, kliknięcia, wypłata.
    db.setWallet(ann.user.id, 10_000);
    const r = await ann.run('miny', { zaklad: '1000', miny: 1 });
    const first = r.msgs[0];
    check(first.live && first.components.length === 5 && first.components[0].components.length === 5, '/miny: plansza 4×5 + wypłata, wiadomość „live”');
    const id = first.id;
    const click = (customId) => ann.req('POST', '/api/click', { msg: id, id: customId });
    check((await click('cos_obcego')).status === 409, 'kliknięcie nieistniejącego przycisku → 409');
    check((await bob.req('POST', '/api/click', { msg: id, id: 'mine_0' })).status === 409, 'cudza prywatna gra: klik → 409');
    let tiles = 0;
    for (let k = 0; k < 20 && ann.latest(id).live; k++) {
      const r2 = await click(`mine_${k}`);
      if (r2.status !== 200) break;
      await sleep(30);
      tiles++;
      if (tiles === 2 && ann.latest(id).live) await click('mine_cash');
    }
    await sleep(100);
    const last = ann.latest(id);
    check(!last.live && /Wypłacasz|BOOM/.test(text(last)), `/miny: gra skończyła się wypłatą albo miną (po ${tiles} kliknięciach)`);
    const w = db.getUser(ann.user.id).wallet;
    check(w >= 9000 && w < 12_000 && (await click('mine_5')).status === 409, `/miny: saldo ${w} się zgadza, po końcu klik jest odrzucony`);
    check(db.getUser(ann.user.id).games === 2, '/miny: gra zapisana w statystykach');
  }
  {
    // Powrót na stronę w trakcie gry: nowe połączenie dostaje trwającą wiadomość.
    db.setWallet(ann.user.id, 10_000);
    const r = await ann.run('hilo', { zaklad: '500' });
    const id = r.msgs[0].id;
    const again = new Client(base);
    again.cookie = ann.cookie;
    await again.open();
    const hello = again.events.find((e) => e.event === 'hello').data;
    check(hello.messages.some((m) => m.id === id && m.live) && hello.me.wallet === 9500, 'po odświeżeniu strony trwająca gra wraca na ekran');
    again.close();
    const other = new Client(base);
    other.cookie = bob.cookie;
    await other.open();
    check(!other.events.find((e) => e.event === 'hello').data.messages.some((m) => m.id === id), 'cudza prywatna gra nie trafia do innych graczy');
    other.close();
    await ann.req('POST', '/api/click', { msg: id, id: 'hilo_high' }); // jedno kliknięcie, reszta wyjdzie z limitu czasu
    const hub = app.hub;
    for (const c of [...hub.messages.get(id).collectors]) await c.stop('idle'); // brak aktywności: gra rozlicza się sama
    await sleep(100);
    const w = db.getUser(ann.user.id).wallet;
    check(!ann.latest(id).live && w >= 9500 - 0 && w <= 9500 + 1_000_000, `hilo: po limicie czasu gra się rozlicza (saldo ${w})`);
    console.log('  ok');
  }

  section('Wielu graczy: powiadomienia, jackpot i pojedynek na żywo');
  {
    // Okradanie: ofiara dostaje powiadomienie na stronie, bo jej nazwa jest w wiadomości.
    db.setWallet(ann.user.id, 5000);
    db.setWallet(bob.user.id, 5000);
    const nb = bob.events.length;
    await ann.run('okradnij', { ofiara: bob.user.id });
    const notice = bob.events.slice(nb).find((e) => e.event === 'notice');
    check(notice && notice.data.from === 'Ania' && /Bartek/.test(notice.data.text), '/okradnij: ofiara dostaje powiadomienie z nazwą');

    // Jackpot: lobby widzą wszyscy, dołączyć może każdy.
    await cat.open();
    db.setWallet(ann.user.id, 5000);
    db.setWallet(bob.user.id, 5000);
    db.setWallet(cat.user.id, 5000);
    const start = await ann.req('POST', '/api/cmd', { name: 'jackpot', options: { zaklad: '1000' } });
    check(start.status === 200, 'jackpot startuje');
    const lobbyEvent = await bob.waitFor((e) => ['msg', 'edit'].includes(e.event) && e.data.cmd === 'jackpot' && e.data.live);
    const lobby = lobbyEvent.data;
    check(lobby.shared && lobby.live && lobby.components[0].components[0].custom_id === 'jackpot_join', 'lobby jackpota jest wspólne i ma przycisk dołączenia');
    check(cat.events.some((e) => e.event === 'msg' && e.data.id === lobby.id), 'lobby dostaje też trzeci gracz');
    check((await bob.req('POST', '/api/click', { msg: lobby.id, id: 'jackpot_join' })).status === 200, 'Bartek dołącza');
    check((await cat.req('POST', '/api/click', { msg: lobby.id, id: 'jackpot_join' })).status === 200, 'Celina dołącza');
    await bob.waitFor((e) => e.event === 'edit' && e.data.id === lobby.id && /Uczestnicy \(3\/20\)/.test(text(e.data)));
    check(db.getUser(bob.user.id).wallet === 4000 && db.getUser(cat.user.id).wallet === 4000, 'dołączenie pobiera stawkę');
    const msg = app.hub.messages.get(lobby.id);
    for (const c of [...msg.collectors]) await c.stop('time'); // nie czekamy 45 s
    await ann.waitFor((e) => e.event === 'edit' && e.data.id === lobby.id && /zgarnia/.test(text(e.data)), 15000);
    const total = [ann, bob, cat].reduce((s, c) => s + db.getUser(c.user.id).wallet, 0);
    check(total === 15_000 - 150, `jackpot: z puli 3000 znika tylko 5% prowizji (suma ${total})`);

    // Pojedynek: wyzwanie widzi przeciwnik, przyjmuje je przyciskiem. Ktoś trzeci przycisku nie ruszy.
    db.setWallet(ann.user.id, 5000);
    db.setWallet(bob.user.id, 5000);
    const duel = await ann.req('POST', '/api/cmd', { name: 'pojedynek', options: { przeciwnik: bob.user.id, zaklad: '1000' } });
    check(duel.status === 200, 'pojedynek startuje');
    const challenge = (await bob.waitFor((e) => e.event === 'msg' && e.data.cmd === 'pojedynek')).data;
    check(/wyzywa/.test(text(challenge)), 'przeciwnik widzi wyzwanie');
    const cs = await cat.req('POST', '/api/click', { msg: challenge.id, id: 'duel_accept' });
    check(cs.status === 200, 'trzeci gracz może kliknąć, ale gra odrzuci go komunikatem');
    check(app.hub.messages.get(challenge.id).live, 'cudze kliknięcie nie kończy wyzwania');
    check((await bob.req('POST', '/api/click', { msg: challenge.id, id: 'duel_accept' })).status === 200, 'przeciwnik przyjmuje');
    await ann.waitFor((e) => e.event === 'edit' && e.data.id === challenge.id && /rozstrzygnięty/.test(text(e.data)), 15000);
    const sum = db.getUser(ann.user.id).wallet + db.getUser(bob.user.id).wallet;
    check(sum === 10_000 - 60, `pojedynek: znika tylko 3% puli (suma ${sum})`);
    console.log('  ok');
  }

  section('Limity: tempo komend i porządki');
  {
    const spam = new Client(base);
    await spam.register('Spamer');
    let blocked = 0;
    for (let i = 0; i < 25; i++) if ((await spam.req('POST', '/api/cmd', { name: 'saldo' })).status === 400) blocked++;
    check(blocked >= 8, `zbyt szybkie komendy są wstrzymywane (${blocked}/25)`);
    console.log('  ok');
  }

  for (const c of [ann, bob, cat]) c.close();
  await app.close();
}

main()
  .then(() => {
    if (!failures.length) console.log('\n✅ Strona działa: konta, bezpieczeństwo, komendy, gry i tryb wielu graczy.');
  })
  .catch((err) => {
    console.error('Test przerwany błędem:', err);
    process.exitCode = 1;
  })
  .finally(() => {
    if (failures.length) {
      console.log(`\n❌ Niezaliczone: ${failures.length}`);
      for (const f of [...new Set(failures)]) console.log('  •', f);
      process.exitCode = 1;
    }
  });
