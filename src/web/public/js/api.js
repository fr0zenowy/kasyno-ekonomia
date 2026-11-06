// Komunikacja z serwerem: zapytania JSON, strumień zdarzeń (SSE) i uruchamianie komend z odbiorem wyników.
import { store } from './store.js';
import { toast } from './ui.js';

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export async function api(method, path, body) {
  let res;
  try {
    res = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, 'Brak połączenia z serwerem.');
  }
  let json = null;
  try {
    json = await res.json();
  } catch {
    /* odpowiedź bez JSON */
  }
  if (!res.ok) throw new ApiError(res.status, json?.error ?? `Błąd ${res.status}`);
  return json;
}

// ---------- komendy ----------

const runs = new Map();
const orphans = new Map(); // zdarzenia, które wyprzedziły odpowiedź HTTP z id komendy
const orphanDone = new Set();

export class Run {
  constructor(id) {
    this.id = id;
    this.messages = new Map();
    this.listeners = new Set();
    this.finished = false;
    this.done = new Promise((resolve) => (this.resolveDone = resolve));
  }

  /** Nasłuch wiadomości tej komendy: fn(msg, 'msg' | 'edit') i na końcu fn(null, 'done'). */
  on(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  push(kind, msg) {
    this.messages.set(msg.id, msg);
    for (const fn of [...this.listeners]) fn(msg, kind);
  }

  finish() {
    if (this.finished) return;
    this.finished = true;
    this.resolveDone();
    for (const fn of [...this.listeners]) fn(null, 'done');
  }

  /** Pierwsza wiadomość spełniająca warunek (sprawdza też już odebrane); null, gdy komenda skończy się wcześniej. */
  waitFor(pred, timeout = 30_000) {
    return new Promise((resolve) => {
      for (const m of this.messages.values()) {
        if (pred(m)) return resolve(m);
      }
      if (this.finished) return resolve(null);
      const timer = setTimeout(() => {
        off();
        resolve(null);
      }, timeout);
      const off = this.on((m) => {
        if (m === null || pred(m)) {
          clearTimeout(timer);
          off();
          resolve(m);
        }
      });
    });
  }

  /** Najnowsza wersja ostatniej wiadomości (zwykle wynik komendy). */
  last() {
    return [...this.messages.values()].at(-1) ?? null;
  }
}

/** Uruchamia komendę. Rzuca ApiError, gdy serwer odrzuci opcje albo tempo. */
export async function startRun(name, options = {}, sub = null) {
  const { run: id } = await api('POST', '/api/cmd', { name, sub, options });
  const run = new Run(id);
  runs.set(id, run);
  for (const [kind, msg] of orphans.get(id) ?? []) run.push(kind, msg);
  orphans.delete(id);
  if (orphanDone.delete(id)) run.finish();
  return run;
}

/** Klika przycisk w wiadomości; błędy (np. gra już się skończyła) pokazuje jako toast. */
export async function clickButton(msgId, customId) {
  try {
    await api('POST', '/api/click', { msg: msgId, id: customId });
    return true;
  } catch (err) {
    toast({ title: err.message, kind: 'warn' });
    return false;
  }
}

// ---------- strumień zdarzeń ----------

const handlers = new Map();
export const onEvent = (name, fn) => {
  if (!handlers.has(name)) handlers.set(name, new Set());
  handlers.get(name).add(fn);
  return () => handlers.get(name).delete(fn);
};
const fire = (name, data) => {
  for (const fn of handlers.get(name) ?? []) fn(data);
};

function route(kind, msg) {
  if (msg.live) store.live.set(msg.id, msg);
  else store.live.delete(msg.id);
  fire(kind, msg);
  const run = runs.get(msg.run);
  if (run) run.push(kind, msg);
  else {
    if (!orphans.has(msg.run)) {
      orphans.set(msg.run, []);
      setTimeout(() => orphans.delete(msg.run), 15_000);
    }
    orphans.get(msg.run).push([kind, msg]);
  }
}

let source = null;
export function connectStream() {
  source?.close();
  source = new EventSource('/api/stream');
  const on = (name, fn) => source.addEventListener(name, (e) => fn(JSON.parse(e.data)));
  on('hello', (d) => {
    store.live.clear();
    for (const m of d.messages) store.live.set(m.id, m);
    store.feed = d.feed ?? [];
    store.online = d.online;
    store.setMe(d.me);
    store.emit('live');
    fire('hello', d);
  });
  on('msg', (m) => route('msg', m));
  on('edit', (m) => route('edit', m));
  on('done', ({ run }) => {
    const r = runs.get(run);
    if (r) r.finish();
    else {
      orphanDone.add(run);
      setTimeout(() => orphanDone.delete(run), 15_000);
    }
    fire('done', { run });
  });
  on('me', (me) => store.setMe(me));
  on('online', (d) => {
    store.online = d.online;
    store.emit('online');
  });
  on('feed', (entry) => {
    store.feed.unshift(entry);
    store.feed.length = Math.min(store.feed.length, 20);
    store.emit('feed');
    fire('feed', entry);
  });
  on('notice', (n) => {
    toast({ title: n.from ? `${n.from}: /${n.cmd}` : `/${n.cmd}`, text: n.text, kind: 'warn', icon: '🔔', ms: 9000 });
    fire('notice', n);
  });
}

export function disconnectStream() {
  source?.close();
  source = null;
}
