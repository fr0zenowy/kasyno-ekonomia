// Przejściówka między komendami Discorda a przeglądarką. Komenda dostaje WebInteraction, które udaje interakcję
// (reply, editReply, kolektor przycisków...), a każda zmiana wiadomości leci do przeglądarek strumieniem SSE.
// Dzięki temu cała logika gier i ekonomii to ten sam, przetestowany kod co w bocie.
const { randomUUID } = require('node:crypto');
const { MessageFlags } = require('discord.js');
const accounts = require('./accounts');

const EPHEMERAL = MessageFlags.Ephemeral;
const MENTION_RE = /<@(w_[a-f0-9]{16})>/g;

const isEphemeral = (payload) => typeof payload === 'object' && payload !== null && payload.flags != null && (Number(payload.flags) & EPHEMERAL) === EPHEMERAL;
const toJson = (x) => (x && typeof x.toJSON === 'function' ? x.toJSON() : x);

/** Tekst wiadomości bez formatowania, np. do powiadomień. */
function plainText(msg) {
  const e = msg.embeds?.[0];
  const text = [msg.content, e?.title, e?.description].filter(Boolean).join(' – ');
  return text.replace(MENTION_RE, (_, id) => accounts.displayName(id)).replace(/[*_`#]/g, '').slice(0, 300);
}

class WebCollector {
  constructor(msg, opts = {}) {
    this.msg = msg;
    this.opts = opts;
    this.handlers = { collect: [], end: [] };
    this.collected = 0;
    this.ended = false;
    this.idleTimer = null;
    this.timeTimer = null;
    if (opts.time) this.timeTimer = setTimeout(() => this.stop('time'), opts.time).unref();
    this.armIdle();
  }

  armIdle() {
    if (!this.opts.idle) return;
    clearTimeout(this.idleTimer);
    this.idleTimer = setTimeout(() => this.stop('idle'), this.opts.idle).unref();
  }

  on(event, fn) {
    this.handlers[event]?.push(fn);
    return this;
  }

  /** Kliknięcie przycisku przez gracza `user`. Zwraca { ok, error? } po obsłużeniu przez grę. */
  async click(user, customId) {
    if (this.ended) return { ok: false, error: 'Ta gra już się skończyła.' };
    const ci = this.msg.hub.makeClick(this.msg, user, customId);
    if (this.opts.filter && !(await this.opts.filter(ci))) return { ok: true, filtered: true };
    this.collected += 1;
    this.armIdle();
    for (const fn of this.handlers.collect) {
      try {
        await fn(ci);
      } catch (err) {
        console.error('Błąd w obsłudze przycisku:', err);
      }
    }
    if (this.opts.max && this.collected >= this.opts.max) await this.stop('limit');
    return { ok: true };
  }

  /** Odpowiednik collector.stop(): odpala handlery „end” (gry rozliczają się w nich po czasie). */
  async stop(reason = 'user') {
    if (this.ended) return;
    this.ended = true;
    clearTimeout(this.idleTimer);
    clearTimeout(this.timeTimer);
    this.msg.collectors.delete(this);
    this.msg.hub.publish(this.msg, 'edit');
    for (const fn of this.handlers.end) {
      try {
        await fn([], reason);
      } catch (err) {
        console.error('Błąd przy kończeniu kolektora:', err);
      }
    }
  }
}

class WebMessage {
  constructor(hub, { run, cmd, owner, shared, ephemeral, payload, meta = null }) {
    this.hub = hub;
    this.id = randomUUID();
    this.run = run;
    this.cmd = cmd;
    this.owner = owner;
    this.shared = Boolean(shared) && !ephemeral;
    this.ephemeral = Boolean(ephemeral);
    this.content = '';
    this.embeds = [];
    this.components = [];
    this.meta = meta;
    this.collectors = new Set();
    this.createdAt = Date.now();
    this.updatedAt = this.createdAt;
    this.apply(payload);
  }

  /** Jak edycja wiadomości w Discordzie: zmieniają się tylko podane pola. */
  apply(payload) {
    if (typeof payload === 'string') payload = { content: payload };
    if (payload.content !== undefined) this.content = String(payload.content ?? '');
    if (payload.embeds !== undefined) this.embeds = payload.embeds.map(toJson);
    if (payload.components !== undefined) this.components = payload.components.map(toJson);
    this.updatedAt = Date.now();
  }

  get live() {
    return this.collectors.size > 0;
  }

  /** Wiadomość w takiej postaci, w jakiej idzie do przeglądarki. Wzmianki <@id> zamieniamy na nazwy graczy. */
  view() {
    const named = (value) =>
      JSON.parse(JSON.stringify(value), (_, v) => (typeof v === 'string' ? v.replace(MENTION_RE, (__, id) => `@${accounts.displayName(id)}`) : v));
    return {
      id: this.id,
      run: this.run,
      cmd: this.cmd,
      owner: { id: this.owner.id, name: this.owner.displayName },
      shared: this.shared,
      ephemeral: this.ephemeral,
      live: this.live,
      content: named(this.content),
      embeds: named(this.embeds),
      components: this.components,
      meta: this.meta,
      ts: this.updatedAt,
    };
  }

  /** discord.js: message.createMessageComponentCollector({ filter, idle, time, max }). */
  createMessageComponentCollector(opts = {}) {
    const collector = new WebCollector(this, opts);
    this.collectors.add(collector);
    this.hub.publish(this, 'edit');
    return collector;
  }

  async edit(payload) {
    this.apply(payload);
    this.hub.publish(this, 'edit');
    return this;
  }
}

class Hub {
  constructor() {
    this.conns = new Map(); // userId -> Set<res>
    this.messages = new Map();
    this.onActivity = null; // (userId) => void, wołane po zmianach wiadomości gracza (odświeża jego stan)
    this.activityTimers = new Map();
    this.gc = setInterval(() => this.collectGarbage(), 60_000);
    this.gc.unref();
  }

  // --- połączenia SSE ---

  connect(userId, res) {
    if (!this.conns.has(userId)) this.conns.set(userId, new Set());
    this.conns.get(userId).add(res);
    return () => {
      const set = this.conns.get(userId);
      set?.delete(res);
      if (set && !set.size) this.conns.delete(userId);
    };
  }

  isOnline(userId) {
    return this.conns.has(userId);
  }

  onlineCount() {
    return this.conns.size;
  }

  static write(res, event, data) {
    try {
      res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    } catch {
      /* połączenie padło: zostanie posprzątane przy zamknięciu */
    }
  }

  send(userId, event, data) {
    for (const res of this.conns.get(userId) ?? []) Hub.write(res, event, data);
  }

  broadcast(event, data) {
    for (const set of this.conns.values()) for (const res of set) Hub.write(res, event, data);
  }

  notify(userId, notice) {
    this.send(userId, 'notice', notice);
  }

  // --- wiadomości ---

  visibleTo(msg, userId) {
    return msg.shared || msg.owner.id === userId;
  }

  createMessage(opts) {
    const msg = new WebMessage(this, opts);
    this.messages.set(msg.id, msg);
    this.publish(msg, 'msg');
    // Wzmianka o innym graczu (okradanie, przelew...) to dla niego powiadomienie, bo wiadomość widzi tylko autor.
    if (!msg.shared && !msg.ephemeral) {
      const text = JSON.stringify([msg.content, msg.embeds]);
      const seen = new Set([msg.owner.id]);
      for (const [, id] of text.matchAll(MENTION_RE)) {
        if (seen.has(id)) continue;
        seen.add(id);
        this.notify(id, { from: msg.owner.displayName, cmd: msg.cmd, text: plainText(msg) });
      }
    }
    return msg;
  }

  publish(msg, kind) {
    const data = msg.view();
    if (msg.shared) this.broadcast(kind, data);
    else this.send(msg.owner.id, kind, data);
    this.touch(msg.owner.id);
  }

  /** Stan gracza (saldo, poziom...) odświeżamy z małym opóźnieniem, żeby seria edycji dała jedno odświeżenie. */
  touch(userId) {
    if (!this.onActivity || this.activityTimers.has(userId)) return;
    const t = setTimeout(() => {
      this.activityTimers.delete(userId);
      try {
        this.onActivity(userId);
      } catch (err) {
        console.error('Błąd odświeżania stanu gracza:', err);
      }
    }, 200);
    t.unref();
    this.activityTimers.set(userId, t);
  }

  /** Wiadomości z żywym kolektorem (trwająca gra, lobby) widoczne dla gracza: po odświeżeniu strony wracają na ekran. */
  snapshotFor(userId) {
    return [...this.messages.values()].filter((m) => m.live && this.visibleTo(m, userId)).map((m) => m.view());
  }

  collectGarbage(now = Date.now()) {
    for (const [id, m] of this.messages) if (!m.live && now - m.updatedAt > 10 * 60_000) this.messages.delete(id);
  }

  // --- kliknięcia ---

  /** Obiekt udający kliknięcie przycisku (MessageComponentInteraction). */
  makeClick(msg, user, customId) {
    const hub = this;
    const ci = {
      user,
      customId,
      message: msg,
      channelId: msg.shared ? 'kasyno' : user.id,
      replied: false,
      deferred: false,
      isButton: () => true,
      async update(payload) {
        ci.replied = true;
        msg.apply(payload);
        hub.publish(msg, 'edit');
        return msg;
      },
      async deferUpdate() {
        ci.deferred = true;
      },
      async reply(payload) {
        ci.replied = true;
        return hub.createMessage({
          run: msg.run,
          cmd: msg.cmd,
          owner: user,
          shared: msg.shared,
          ephemeral: isEphemeral(payload) || !msg.shared,
          payload,
        });
      },
    };
    ci.followUp = ci.reply;
    ci.editReply = ci.update;
    return ci;
  }

  /** Obsługa kliknięcia z przeglądarki. Sprawdza, czy gracz widzi wiadomość i czy taki przycisk naprawdę w niej jest. */
  async click(user, messageId, customId) {
    const msg = this.messages.get(messageId);
    if (!msg || !this.visibleTo(msg, user.id)) return { ok: false, error: 'Ta wiadomość już wygasła.' };
    const button = msg.components.flatMap((row) => row.components ?? []).find((c) => c.custom_id === customId);
    if (!button) return { ok: false, error: 'Nie ma takiego przycisku.' };
    if (button.disabled) return { ok: false, error: 'Ten przycisk jest nieaktywny.' };
    const collectors = [...msg.collectors];
    if (!collectors.length) return { ok: false, error: 'Ta gra już się skończyła.' };
    let result = { ok: true };
    for (const c of collectors) result = await c.click(user, customId);
    return result;
  }
}

/** Interakcja widziana przez komendę. `values` to już zwalidowane opcje (nazwa -> wartość). */
class WebInteraction {
  constructor(hub, { user, commandName, sub = null, values = {}, run, shared = false, channelId }) {
    this.hub = hub;
    this.user = user;
    this.commandName = commandName;
    this.run = run;
    this.shared = shared;
    this.channelId = channelId ?? (shared ? 'kasyno' : user.id);
    this.replied = false;
    this.deferred = false;
    this.msg = null;
    this.pendingMeta = null;
    this.fromWeb = true;
    this.client = {
      users: { fetch: async (id) => accounts.userObject(id) },
      channels: { fetch: async () => null },
    };
    this.options = {
      getString: (n) => (typeof values[n] === 'string' ? values[n] : null),
      getInteger: (n) => (Number.isInteger(values[n]) ? values[n] : null),
      getNumber: (n) => (typeof values[n] === 'number' ? values[n] : null),
      getUser: (n) => values[n] ?? null,
      getSubcommand: () => sub,
      getFocused: () => '',
    };
  }

  isRepliable() {
    return true;
  }

  isChatInputCommand() {
    return true;
  }

  isAutocomplete() {
    return false;
  }

  /** Dane strukturalne dla ekranów w przeglądarce (np. wynik rzutu monetą). Zwykły Discord tego nie ma, więc komendy wołają `interaction.meta?.()`. */
  meta(data) {
    if (this.msg) this.msg.meta = data;
    else this.pendingMeta = data;
  }

  create(payload, extra = {}) {
    this.msg = this.hub.createMessage({
      run: this.run,
      cmd: this.commandName,
      owner: this.user,
      shared: this.shared,
      ephemeral: isEphemeral(payload),
      payload,
      meta: this.pendingMeta,
      ...extra,
    });
    this.pendingMeta = null;
    return this.msg;
  }

  async reply(payload) {
    if (this.replied || this.deferred) throw new Error('Już odpowiedziano na tę komendę');
    this.replied = true;
    return this.create(payload);
  }

  async deferReply(opts = {}) {
    if (this.replied || this.deferred) throw new Error('Już odpowiedziano na tę komendę');
    this.deferred = true;
    this.deferEphemeral = isEphemeral(opts);
  }

  async editReply(payload) {
    if (!this.replied && !this.deferred) throw new Error('editReply przed reply');
    if (!this.msg) {
      this.replied = true;
      return this.create(payload, { ephemeral: this.deferEphemeral || isEphemeral(payload) });
    }
    return this.msg.edit(payload);
  }

  async followUp(payload) {
    return this.hub.createMessage({
      run: this.run,
      cmd: this.commandName,
      owner: this.user,
      shared: this.shared,
      ephemeral: isEphemeral(payload),
      payload,
    });
  }

  async fetchReply() {
    return this.msg;
  }
}

module.exports = { Hub, WebInteraction, WebMessage, WebCollector, EPHEMERAL, isEphemeral, plainText };
