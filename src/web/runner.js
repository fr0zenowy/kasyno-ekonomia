// Uruchamianie komend z przeglądarki: walidacja opcji tak jak robi to Discord (typy, zakresy, wybory), limit tempa
// i obsługa błędów. Sama komenda wykonuje się w WebInteraction (hub.js).
const { randomUUID } = require('node:crypto');
const accounts = require('./accounts');
const { WebInteraction, EPHEMERAL } = require('./hub');

// Komendy tylko dla właściciela bota nie mają sensu w przeglądarce.
const BLOCKED = new Set(['admin']);
// Wielu graczy naraz: ich wiadomości (lobby, wyzwania) widzą wszyscy, a przyciski może kliknąć każdy.
const SHARED = new Set(['jackpot', 'napad', 'pojedynek']);

const T = { SUB: 1, STRING: 3, INTEGER: 4, USER: 6, NUMBER: 10 };

/** Zamienia opcje z przeglądarki na wartości, jakie dostałaby komenda od Discorda. Zwraca { values } albo { error }. */
function coerceOptions(defs, raw) {
  const values = {};
  for (const o of defs) {
    let v = raw?.[o.name];
    if (v === undefined || v === null || v === '') {
      if (o.required) return { error: `Brakuje: ${o.name}.` };
      continue;
    }
    switch (o.type) {
      case T.STRING:
        v = String(v);
        if (o.max_length && v.length > o.max_length) return { error: `„${o.name}” jest za długie.` };
        if (o.choices?.length && !o.choices.some((c) => c.value === v)) return { error: `„${o.name}” ma niedozwoloną wartość.` };
        break;
      case T.INTEGER:
        if (typeof v === 'string' && /^-?\d+$/.test(v.trim())) v = Number(v);
        if (!Number.isInteger(v)) return { error: `„${o.name}” musi być liczbą całkowitą.` };
        if ((o.min_value !== undefined && v < o.min_value) || (o.max_value !== undefined && v > o.max_value)) {
          return { error: `„${o.name}” musi być liczbą od ${o.min_value ?? '−∞'} do ${o.max_value ?? '∞'}.` };
        }
        if (o.choices?.length && !o.choices.some((c) => c.value === v)) return { error: `„${o.name}” ma niedozwoloną wartość.` };
        break;
      case T.NUMBER:
        if (typeof v === 'string') v = Number(v.trim().replace(',', '.'));
        if (typeof v !== 'number' || !Number.isFinite(v)) return { error: `„${o.name}” musi być liczbą.` };
        if ((o.min_value !== undefined && v < o.min_value) || (o.max_value !== undefined && v > o.max_value)) {
          return { error: `„${o.name}” musi być liczbą od ${o.min_value ?? '−∞'} do ${o.max_value ?? '∞'}.` };
        }
        break;
      case T.USER: {
        const acc = accounts.byId(String(v));
        if (!acc) return { error: 'Nie ma takiego gracza.' };
        v = accounts.userObject(acc.id, acc.username);
        break;
      }
      default:
        return { error: `Nieobsługiwana opcja „${o.name}”.` };
    }
    values[o.name] = v;
  }
  return { values };
}

/** Limit tempa: najwyżej `max` uruchomień komend na gracza w oknie `windowMs`. */
class RateLimiter {
  constructor(max = 15, windowMs = 5000) {
    this.max = max;
    this.windowMs = windowMs;
    this.hits = new Map();
  }

  take(key, now = Date.now()) {
    const recent = (this.hits.get(key) ?? []).filter((t) => now - t < this.windowMs);
    if (recent.length >= this.max) {
      this.hits.set(key, recent);
      return false;
    }
    recent.push(now);
    this.hits.set(key, recent);
    if (this.hits.size > 5000) this.hits.clear();
    return true;
  }
}

class Runner {
  constructor(hub, commands) {
    this.hub = hub;
    this.commands = commands;
    this.limiter = new RateLimiter();
    this.defs = new Map([...commands].map(([name, cmd]) => [name, cmd.data.toJSON()]));
  }

  /** Lista komend dla przeglądarki (opisy i opcje, do generowania formularzy). */
  describe() {
    return [...this.defs.values()]
      .filter((d) => !BLOCKED.has(d.name))
      .map((d) => ({ name: d.name, description: d.description, shared: SHARED.has(d.name), options: d.options ?? [] }));
  }

  /**
   * Odpala komendę w tle. Zwraca { ok, run, done } (done to obietnica końca komendy, przydatna w testach)
   * albo { ok: false, error }. Wyniki spływają jako wiadomości strumieniem SSE z tym samym `run`.
   */
  start(accountId, name, sub, rawOptions) {
    const cmd = this.commands.get(name);
    if (!cmd || BLOCKED.has(name)) return { ok: false, error: 'Nie ma takiej komendy.' };

    const def = this.defs.get(name);
    let optionDefs = def.options ?? [];
    const subs = optionDefs.filter((o) => o.type === T.SUB);
    if (subs.length) {
      const chosen = subs.find((s) => s.name === sub);
      if (!chosen) return { ok: false, error: 'Nie ma takiej podkomendy.' };
      optionDefs = chosen.options ?? [];
    } else {
      sub = null;
    }
    const parsed = coerceOptions(optionDefs, rawOptions);
    if (parsed.error) return { ok: false, error: parsed.error };
    // Limit tempa liczy tylko poprawne komendy: literówka w formularzu nie powinna blokować gracza.
    if (!this.limiter.take(accountId)) return { ok: false, error: 'Zwolnij trochę, za dużo akcji naraz.' };

    const user = accounts.userObject(accountId);
    const run = randomUUID();
    const interaction = new WebInteraction(this.hub, { user, commandName: name, sub, values: parsed.values, run, shared: SHARED.has(name) });
    const done = (async () => {
      try {
        await cmd.execute(interaction);
      } catch (err) {
        console.error(`Błąd w komendzie ${name}:`, err);
        await interaction.followUp({ content: '😵 Coś poszło nie tak. Spróbuj jeszcze raz!', flags: EPHEMERAL }).catch(() => {});
      } finally {
        this.hub.send(accountId, 'done', { run });
        this.hub.touch(accountId);
      }
    })();
    return { ok: true, run, done };
  }
}

module.exports = { Runner, RateLimiter, coerceOptions, BLOCKED, SHARED };
