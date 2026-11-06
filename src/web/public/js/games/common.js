// Wspólne klocki ekranów gier: panel stawki, rama z areną, odtwarzanie wyniku z animacją i obsługa gier z przyciskami.
import { h, clear, fmt, short, toast, sfx, fx, sleep } from '../ui.js';
import { startRun, clickButton, onEvent } from '../api.js';
import { store } from '../store.js';
import { celebrate, coinsLabel, plain } from '../render.js';

/** Tłumaczy zapis stawki (500, 2k, 1.5m, 50%, all, half) na liczbę, tak jak robi to serwer. Do podglądu wygranej. */
export function parseBet(text, wallet = store.me?.wallet ?? 0) {
  const s = String(text ?? '').trim().toLowerCase().replace(/\s+/g, '').replace(',', '.');
  if (s === 'all' || s === 'max') return wallet;
  if (s === 'half' || s === 'polowa' || s === 'połowa') return Math.floor(wallet / 2);
  const pct = /^(\d+(?:\.\d+)?)%$/.exec(s);
  if (pct) return Math.floor((wallet * Math.min(100, Number(pct[1]))) / 100);
  const m = /^(\d+(?:\.\d+)?)([kmb]?)$/.exec(s);
  if (!m) return 0;
  return Math.floor(Number(m[1]) * { '': 1, k: 1e3, m: 1e6, b: 1e9 }[m[2]]);
}

/** Panel stawki: pole, szybkie przyciski i podpowiedź wygranej. `value()` zwraca tekst dla serwera. */
export function betPanel(gameId, { fixed = false, hint } = {}) {
  const key = `bet:${gameId}`;
  let saved = '100';
  try { saved = localStorage.getItem(key) || '100'; } catch { /* brak localStorage */ }
  const input = h('input', { class: 'input num', inputmode: 'text', autocomplete: 'off', value: saved, 'aria-label': 'Stawka', maxlength: 14 });
  const est = h('div', { class: 'muted small' });
  const set = (v) => {
    input.value = String(v);
    update();
    sfx.play('tick');
  };
  const update = () => {
    try { localStorage.setItem(key, input.value); } catch { /* ignoruj */ }
    const n = parseBet(input.value);
    est.textContent = n > 0 ? `${fmt(n)} 💰${n > store.me.wallet ? ' • za mało w portfelu' : ''}${hint ? ` • ${hint(n)}` : ''}` : 'Podaj stawkę, np. 500, 2k, 50% albo all';
    est.classList.toggle('lose', n > store.me.wallet);
  };
  input.addEventListener('input', update);
  const quick = (label, fn) => h('button', { class: 'btn small ghost', type: 'button', onclick: () => set(fn(parseBet(input.value) || 0)) }, label);
  const chips = [100, 500, 1000, 5000, 25_000, 100_000].map((n) => h('button', { class: 'btn small', type: 'button', onclick: () => set(n) }, short(n)));
  const el = h('div', { class: 'col' },
    h('div', { class: 'field' }, h('label', {}, 'Stawka'), input, est),
    !fixed && h('div', { class: 'row gap-s' }, quick('½', (n) => Math.max(10, Math.floor(n / 2))), quick('2×', (n) => n * 2), quick('Max', () => 'all'), quick('Min', () => 10)),
    h('div', { class: 'row gap-s' }, chips));
  const unsub = store.subscribe((w) => w === 'me' && update());
  update();
  return {
    el,
    input,
    value: () => input.value.trim(),
    amount: () => parseBet(input.value),
    dispose: unsub,
  };
}

/** Rama ekranu gry: arena po lewej, panel sterowania po prawej. */
export function gameFrame(container) {
  const stage = h('div', { class: 'stage' });
  const result = h('div', { class: 'result-strip' });
  const side = h('div', { class: 'card side' });
  const info = h('div', { class: 'card flat info' });
  container.append(h('div', { class: 'gamegrid' }, h('div', { class: 'col', style: 'min-width:0' }, stage, result, info), side));
  return { stage, result, side, info };
}

/** Pasek z wynikiem pod areną + efekty (konfetti przy dużej wygranej). */
export function showResult(strip, { net, bet, label, anchor }) {
  const m = celebrate(net, bet, { anchor });
  strip.className = `result-strip ${net > 0 ? 'win' : net < 0 ? 'lose' : 'push'} show`;
  clear(strip).append(
    h('span', { class: 'emoji' }, net > 0 ? '🎉' : net < 0 ? '💸' : '🤝'),
    h('b', {}, net > 0 ? 'Wygrana' : net < 0 ? 'Przegrana' : 'Remis'),
    label && h('span', { class: 'muted' }, label),
    h('span', { class: 'amount' }, coinsLabel(net)),
    m > 0 && net > 0 && h('span', { class: 'badge gold' }, `x${+m.toFixed(2)}`),
  );
  return m;
}

export const clearResult = (strip) => {
  strip.className = 'result-strip';
  clear(strip);
};

/** Kafelek historii: ostatnie wyniki gry. */
export function historyBar(max = 14) {
  const el = h('div', { class: 'history' });
  return {
    el,
    push(text, kind) {
      el.prepend(h('span', { class: `hchip ${kind}` }, text));
      while (el.children.length > max) el.lastChild.remove();
    },
  };
}

/**
 * Gra z wynikiem znanym od razu (moneta, sloty, kości...): uruchamia komendę, czeka na dane strukturalne,
 * puszcza animację ekranu i oddaje końcowe dane (z `net`). Saldo w pasku jest wstrzymane do końca animacji.
 * Zwraca null, gdy serwer odmówił (np. za mało monet) i pokazał komunikat.
 */
export async function playInstant(cmd, options, animate, sub = null) {
  store.hold();
  try {
    let run;
    try {
      run = await startRun(cmd, options, sub);
    } catch (err) {
      toast({ title: err.message, kind: 'warn' });
      return null;
    }
    const first = await run.waitFor((m) => m.meta || m.ephemeral);
    if (!first?.meta) {
      if (first) {
        const t = plain(first);
        toast({ title: t.title || 'Uwaga', text: t.text, kind: 'warn' });
      }
      return null;
    }
    const settled = run.waitFor((m) => m.meta?.net !== undefined, 25_000);
    await animate(first.meta);
    const final = await settled;
    return final?.meta ?? first.meta;
  } finally {
    store.release();
  }
}

/**
 * Gra z przyciskami (miny, hilo, kurczak...). Śledzi kolejne wersje wiadomości gry i woła onUpdate(msg).
 * `start(options)` rozpoczyna nową grę, `resume()` podejmuje trwającą po odświeżeniu strony.
 */
export class LiveGame {
  constructor(cmd, onUpdate) {
    this.cmd = cmd;
    this.onUpdate = onUpdate;
    this.msg = null;
    this.off = null;
    this.busy = false;
  }

  get active() {
    return Boolean(this.msg?.live);
  }

  attach(msg) {
    this.msg = msg;
    this.onUpdate(msg);
  }

  async start(options, sub = null) {
    this.stop();
    let run;
    try {
      run = await startRun(this.cmd, options, sub);
    } catch (err) {
      toast({ title: err.message, kind: 'warn' });
      return false;
    }
    let started = false;
    return new Promise((resolve) => {
      this.off = run.on((m) => {
        if (!m) {
          if (!started) resolve(false);
          return;
        }
        if (m.ephemeral && !m.meta) {
          const t = plain(m);
          toast({ title: t.title || 'Uwaga', text: t.text, kind: 'warn' });
          return;
        }
        if (m.meta?.game !== this.cmd && !m.live && !started) return;
        this.attach(m);
        if (!started) {
          started = true;
          resolve(true);
        }
      });
      for (const m of run.messages.values()) if (!m.ephemeral && !started) { this.attach(m); started = true; resolve(true); }
    });
  }

  /** Czy po wejściu na stronę trwa już gra tego rodzaju? Jeśli tak, wraca na ekran. */
  resume() {
    const live = [...store.live.values()].find((m) => m.cmd === this.cmd && m.owner.id === store.user.id && m.live);
    if (!live) return false;
    this.stop();
    this.attach(live);
    const off1 = onEvent('edit', (m) => m.id === live.id && this.attach(m));
    const off2 = onEvent('msg', (m) => m.id === live.id && this.attach(m));
    this.off = () => (off1(), off2());
    return true;
  }

  async click(customId) {
    if (!this.msg || this.busy) return false;
    this.busy = true;
    try {
      return await clickButton(this.msg.id, customId);
    } finally {
      this.busy = false;
    }
  }

  stop() {
    this.off?.();
    this.off = null;
  }
}

/** Opis pola z przycisku (np. emoji planszy w minach). */
export const btnEmoji = (b) => b?.emoji?.name ?? '';
export { sleep };
