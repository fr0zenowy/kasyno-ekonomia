// Serwer strony: bez dodatkowych zależności (sam moduł http). Udostępnia pliki z public/, proste API JSON
// i strumień zdarzeń (SSE), którym komendy wysyłają wyniki do przeglądarki.
//   npm run web   →   http://127.0.0.1:3000   (WEB_PORT i WEB_HOST w .env zmieniają adres)
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const game = require('../lib/game');
const lottery = require('../lib/lottery');
const accounts = require('./accounts');
const { Hub, plainText } = require('./hub');
const { Runner, RateLimiter } = require('./runner');
const { buildMe, buildProfile, buildCatalog, leaderboard } = require('./state');

const PUBLIC_DIR = path.join(__dirname, 'public');
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json',
};

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function createWebServer({ commands, scheduler = true, trustProxy = process.env.WEB_TRUST_PROXY === '1' } = {}) {
  const hub = new Hub();
  const runner = new Runner(hub, commands);
  const authLimiter = new RateLimiter(10, 5 * 60_000);
  const feed = []; // ostatnie duże wygrane (tablica „na żywo”)
  hub.onActivity = (id) => hub.send(id, 'me', buildMe(id));

  const stopFeed = game.onSettle(({ userId, bet, payout, net }) => {
    if (!userId.startsWith('w_') || bet <= 0 || net <= 0) return;
    const mult = payout / bet;
    if (!(net >= 100_000 || (mult >= 25 && net >= 5_000))) return;
    const entry = { name: accounts.displayName(userId), amount: net, mult: Math.round(mult * 100) / 100, ts: Date.now() };
    feed.unshift(entry);
    feed.length = Math.min(feed.length, 20);
    hub.broadcast('feed', entry);
  });

  // --- pomocnicze ---

  const ipOf = (req) => (trustProxy && req.headers['x-forwarded-for']?.split(',')[0].trim()) || req.socket.remoteAddress || '?';
  const isHttps = (req) => trustProxy && req.headers['x-forwarded-proto'] === 'https';

  function parseCookies(req) {
    const out = {};
    for (const part of (req.headers.cookie ?? '').split(';')) {
      const i = part.indexOf('=');
      if (i > 0) out[part.slice(0, i).trim()] = part.slice(i + 1).trim();
    }
    return out;
  }

  const accountOf = (req) => {
    const id = accounts.readToken(parseCookies(req).sid);
    return id ? accounts.byId(id) : null;
  };

  function setSession(req, res, id) {
    const flags = `HttpOnly; SameSite=Lax; Path=/; Max-Age=${Math.floor(accounts.SESSION_MS / 1000)}${isHttps(req) ? '; Secure' : ''}`;
    res.setHeader('Set-Cookie', `sid=${accounts.makeToken(id)}; ${flags}`);
  }

  function send(res, status, body, headers = {}) {
    const text = typeof body === 'string' ? body : JSON.stringify(body);
    res.writeHead(status, {
      'Content-Type': typeof body === 'string' ? 'text/plain; charset=utf-8' : 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      ...headers,
    });
    res.end(text);
  }

  async function readJson(req) {
    if (!(req.headers['content-type'] ?? '').startsWith('application/json')) throw new HttpError(415, 'Oczekiwano JSON.');
    const origin = req.headers.origin;
    if (origin) {
      let host = null;
      try {
        host = new URL(origin).host;
      } catch {
        /* zły nagłówek = odrzucamy niżej */
      }
      if (host !== req.headers.host) throw new HttpError(403, 'Zły adres źródłowy.');
    }
    const chunks = [];
    let size = 0;
    for await (const chunk of req) {
      size += chunk.length;
      if (size > 16_384) throw new HttpError(413, 'Za duże zapytanie.');
      chunks.push(chunk);
    }
    try {
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
      if (body === null || typeof body !== 'object' || Array.isArray(body)) throw new Error('not an object');
      return body;
    } catch {
      throw new HttpError(400, 'Niepoprawny JSON.');
    }
  }

  const needAccount = (req) => {
    const acc = accountOf(req);
    if (!acc) throw new HttpError(401, 'Zaloguj się.');
    return acc;
  };

  // --- trasy ---

  const routes = {
    'GET /api/session': (req, res) => {
      const acc = accountOf(req);
      send(res, 200, { user: acc ? { id: acc.id, name: acc.username } : null, players: accounts.count(), online: hub.onlineCount() });
    },

    'POST /api/register': async (req, res) => {
      if (!authLimiter.take(ipOf(req))) throw new HttpError(429, 'Za dużo prób, spróbuj za kilka minut.');
      const { username, password } = await readJson(req);
      const r = await accounts.register(username, password);
      if (!r.ok) throw new HttpError(400, r.error);
      setSession(req, res, r.account.id);
      send(res, 200, { user: { id: r.account.id, name: r.account.username } });
    },

    'POST /api/login': async (req, res) => {
      if (!authLimiter.take(ipOf(req))) throw new HttpError(429, 'Za dużo prób, spróbuj za kilka minut.');
      const { username, password } = await readJson(req);
      const r = await accounts.login(username, password);
      if (!r.ok) throw new HttpError(401, r.error);
      setSession(req, res, r.account.id);
      send(res, 200, { user: { id: r.account.id, name: r.account.username } });
    },

    'POST /api/logout': async (req, res) => {
      await readJson(req);
      send(res, 200, { ok: true }, { 'Set-Cookie': 'sid=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0' });
    },

    'GET /api/me': (req, res) => send(res, 200, buildMe(needAccount(req).id)),

    'GET /api/catalog': (req, res) => {
      needAccount(req);
      send(res, 200, buildCatalog(runner, commands));
    },

    'POST /api/cmd': async (req, res) => {
      const acc = needAccount(req);
      const body = await readJson(req);
      const r = runner.start(acc.id, String(body.name ?? ''), body.sub ?? null, body.options ?? {});
      if (!r.ok) throw new HttpError(400, r.error);
      send(res, 200, { run: r.run });
    },

    'POST /api/click': async (req, res) => {
      const acc = needAccount(req);
      const body = await readJson(req);
      const r = await hub.click(accounts.userObject(acc.id, acc.username), String(body.msg ?? ''), String(body.id ?? ''));
      if (!r.ok) throw new HttpError(409, r.error);
      send(res, 200, { ok: true });
    },

    'GET /api/leaderboard': (req, res, url) => {
      needAccount(req);
      const by = ['majatek', 'poziom', 'hazard'].includes(url.searchParams.get('by')) ? url.searchParams.get('by') : 'majatek';
      send(res, 200, { by, rows: leaderboard(by, 25), players: accounts.count() });
    },

    'GET /api/players': (req, res, url) => {
      const acc = needAccount(req);
      const rows = accounts
        .search(url.searchParams.get('q') ?? '', 10)
        .filter((r) => r.id !== acc.id)
        .map((r) => ({ id: r.id, name: r.username, online: hub.isOnline(r.id) }));
      send(res, 200, { players: rows });
    },

    'GET /api/feed': (req, res) => {
      needAccount(req);
      send(res, 200, { feed });
    },

    'GET /api/stream': (req, res) => {
      const acc = needAccount(req);
      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-store',
        Connection: 'keep-alive',
        'X-Accel-Buffering': 'no',
      });
      res.write('retry: 2000\n\n');
      const disconnect = hub.connect(acc.id, res);
      Hub.write(res, 'hello', { me: buildMe(acc.id), messages: hub.snapshotFor(acc.id), feed, online: hub.onlineCount() });
      hub.broadcast('online', { online: hub.onlineCount() });
      const beat = setInterval(() => res.write(': ping\n\n'), 25_000);
      req.on('close', () => {
        clearInterval(beat);
        disconnect();
        hub.broadcast('online', { online: hub.onlineCount() });
      });
    },
  };

  // --- obsługa żądań ---

  function serveStatic(req, res, pathname) {
    const rel = pathname === '/' ? 'index.html' : decodeURIComponent(pathname).replace(/^\/+/, '');
    const file = path.normalize(path.join(PUBLIC_DIR, rel));
    if (!file.startsWith(PUBLIC_DIR + path.sep) && file !== PUBLIC_DIR) throw new HttpError(403, 'Nie wolno.');
    let stat;
    try {
      stat = fs.statSync(file);
    } catch {
      throw new HttpError(404, 'Nie znaleziono.');
    }
    if (!stat.isFile()) throw new HttpError(404, 'Nie znaleziono.');
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] ?? 'application/octet-stream',
      'Content-Length': stat.size,
      'Cache-Control': 'no-cache',
    });
    if (req.method === 'HEAD') return res.end();
    fs.createReadStream(file).pipe(res);
  }

  const server = http.createServer(async (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Referrer-Policy', 'same-origin');
    res.setHeader(
      'Content-Security-Policy',
      "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    );
    try {
      const url = new URL(req.url, 'http://localhost');
      const handler = routes[`${req.method} ${url.pathname}`];
      if (handler) return await handler(req, res, url);
      if (url.pathname.startsWith('/api/profile/') && req.method === 'GET') {
        needAccount(req);
        const profile = buildProfile(decodeURIComponent(url.pathname.slice('/api/profile/'.length)));
        if (!profile) throw new HttpError(404, 'Nie ma takiego gracza.');
        return send(res, 200, profile);
      }
      if (url.pathname.startsWith('/api/')) {
        const known = Object.keys(routes).some((k) => k.endsWith(` ${url.pathname}`));
        throw new HttpError(known ? 405 : 404, known ? 'Niedozwolona metoda.' : 'Nie ma takiej ścieżki.');
      }
      if (req.method !== 'GET' && req.method !== 'HEAD') throw new HttpError(405, 'Niedozwolona metoda.');
      serveStatic(req, res, url.pathname);
    } catch (err) {
      if (res.headersSent) return res.end();
      if (err instanceof HttpError) return send(res, err.status, { error: err.message });
      console.error('Błąd serwera WWW:', err);
      send(res, 500, { error: 'Błąd serwera.' });
    }
  });

  if (scheduler) {
    // Losowanie loterii: zwycięzca dostaje powiadomienie na stronie (tak samo, jak na Discordzie dostawał wiadomość).
    const client = {
      users: {
        fetch: async (id) => ({
          send: async (p) => hub.notify(id, { cmd: 'loteria', text: plainText({ content: p.content ?? '', embeds: (p.embeds ?? []).map((e) => e.toJSON()) }) }),
        }),
      },
      channels: { fetch: async () => null },
    };
    lottery.startScheduler(client);
  }

  const close = () =>
    new Promise((resolve) => {
      stopFeed();
      clearInterval(hub.gc);
      for (const set of hub.conns.values()) for (const res of set) res.end();
      server.close(() => resolve());
      server.closeAllConnections?.();
    });

  return { server, hub, runner, close };
}

/** Wczytuje komendy, uruchamia serwer i wypisuje adres. Port i adres z WEB_PORT / WEB_HOST. */
function startServer({ port = Number(process.env.WEB_PORT) || 3000, host = process.env.WEB_HOST || '127.0.0.1' } = {}) {
  const { loadCommands } = require('../loader');
  const commands = loadCommands();
  const app = createWebServer({ commands });
  app.server.on('error', (err) => {
    console.error(err.code === 'EADDRINUSE' ? `❌ Port ${port} jest zajęty. Ustaw inny: WEB_PORT=3001 w pliku .env.` : `❌ ${err.message}`);
    process.exitCode = 1;
  });
  app.server.listen(port, host, () => {
    console.log(`🌐 Strona działa: http://${host === '0.0.0.0' ? 'localhost' : host}:${port}  (komend: ${commands.size})`);
    if (host === '0.0.0.0') console.log('⚠️  Strona jest dostępna z sieci. Do internetu wystaw ją za HTTPS (np. reverse proxy) i ustaw WEB_TRUST_PROXY=1.');
  });
  process.on('unhandledRejection', (err) => console.error('Nieobsłużony błąd:', err));
  return app;
}

if (require.main === module) {
  require('dotenv').config();
  startServer();
}

module.exports = { createWebServer, startServer, HttpError };
