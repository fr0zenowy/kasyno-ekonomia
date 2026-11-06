// Konta graczy na stronie: nazwa + hasło (scrypt), sesja w podpisanym ciasteczku. Ekonomia (portfel, bank, ekwipunek...)
// siedzi w tych samych tabelach co w bocie, a id konta (w_...) jest po prostu kluczem gracza.
const crypto = require('node:crypto');
const { promisify } = require('node:util');
const db = require('../lib/db');

const scrypt = promisify(crypto.scrypt);
const SESSION_MS = 30 * 24 * 3600 * 1000;

db.db.exec(`
  CREATE TABLE IF NOT EXISTS web_accounts (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
    salt TEXT NOT NULL,
    hash TEXT NOT NULL,
    created INTEGER NOT NULL
  );
`);

const q = {
  insert: db.db.prepare('INSERT INTO web_accounts (id, username, salt, hash, created) VALUES (?, ?, ?, ?, ?)'),
  byName: db.db.prepare('SELECT * FROM web_accounts WHERE username = ?'),
  byId: db.db.prepare('SELECT id, username, created FROM web_accounts WHERE id = ?'),
  search: db.db.prepare('SELECT id, username FROM web_accounts WHERE username LIKE ? ESCAPE \'\\\' ORDER BY username LIMIT ?'),
  count: db.db.prepare('SELECT COUNT(*) AS n FROM web_accounts'),
};

const NAME_RE = /^[\p{L}\p{N}_-]{3,20}$/u;

/** Sekret do podpisywania sesji: z .env (WEB_SECRET) albo wygenerowany raz i zapisany w bazie. */
function secret() {
  if (process.env.WEB_SECRET) return process.env.WEB_SECRET;
  let s = db.kvGet('web_secret');
  if (!s) {
    s = crypto.randomBytes(32).toString('hex');
    db.kvSet('web_secret', s);
  }
  return s;
}

const sign = (payload) => crypto.createHmac('sha256', secret()).update(payload).digest('base64url');

function makeToken(id, now = Date.now()) {
  const exp = now + SESSION_MS;
  return `${id}.${exp}.${sign(`${id}.${exp}`)}`;
}

/** Zwraca id konta z tokenu sesji albo null (zły podpis, wygasły, konto usunięte). */
function readToken(token, now = Date.now()) {
  const m = /^(w_[a-f0-9]{16})\.(\d{10,16})\.([\w-]{20,})$/.exec(token ?? '');
  if (!m) return null;
  const [, id, exp, sig] = m;
  const want = Buffer.from(sign(`${id}.${exp}`));
  const got = Buffer.from(sig);
  if (want.length !== got.length || !crypto.timingSafeEqual(want, got)) return null;
  if (Number(exp) < now) return null;
  return q.byId.get(id) ? id : null;
}

async function hashPassword(password, salt) {
  return (await scrypt(password, salt, 64)).toString('hex');
}

function validate(username, password) {
  if (!NAME_RE.test(username ?? '')) return 'Nazwa musi mieć 3-20 znaków: litery, cyfry, _ lub -.';
  if (typeof password !== 'string' || password.length < 6 || password.length > 72) return 'Hasło musi mieć od 6 do 72 znaków.';
  return null;
}

async function register(username, password) {
  username = String(username ?? '').trim();
  const bad = validate(username, password);
  if (bad) return { ok: false, error: bad };
  if (q.byName.get(username)) return { ok: false, error: 'Ta nazwa jest już zajęta.' };
  const id = `w_${crypto.randomBytes(8).toString('hex')}`;
  const salt = crypto.randomBytes(16).toString('hex');
  try {
    q.insert.run(id, username, salt, await hashPassword(password, salt), Date.now());
  } catch {
    return { ok: false, error: 'Ta nazwa jest już zajęta.' }; // wyścig dwóch rejestracji
  }
  db.getUser(id); // zakłada wiersz gracza z kwotą startową
  return { ok: true, account: { id, username } };
}

async function login(username, password) {
  const row = q.byName.get(String(username ?? '').trim());
  // Ten sam koszt hashowania dla nieistniejącego konta, żeby czas odpowiedzi nie zdradzał, czy nazwa istnieje.
  const salt = row?.salt ?? 'x'.repeat(32);
  const hash = await hashPassword(typeof password === 'string' ? password.slice(0, 72) : '', salt);
  const ok = row && crypto.timingSafeEqual(Buffer.from(hash), Buffer.from(row.hash));
  return ok ? { ok: true, account: { id: row.id, username: row.username } } : { ok: false, error: 'Zła nazwa albo hasło.' };
}

const byId = (id) => q.byId.get(id) ?? null;
const displayName = (id) => byId(id)?.username ?? 'Gracz Discorda';
const count = () => q.count.get().n;

/** Podpowiedzi graczy po początku nazwy (do wyboru przeciwnika, odbiorcy przelewu...). */
function search(prefix, limit = 8) {
  const escaped = String(prefix ?? '').replace(/[\\%_]/g, (c) => `\\${c}`);
  return q.search.all(`${escaped}%`, limit);
}

/** Obiekt udający użytkownika Discorda, na którym działają komendy z src/commands. */
function userObject(id, name = displayName(id)) {
  return {
    id,
    username: name,
    displayName: name,
    globalName: name,
    bot: false,
    displayAvatarURL: () => '',
    toString: () => `<@${id}>`,
  };
}

module.exports = { SESSION_MS, NAME_RE, makeToken, readToken, register, login, byId, displayName, count, search, userObject };
