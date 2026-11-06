// Narzędzia interfejsu: budowanie DOM, formatowanie, markdown z Discorda, toasty, okna, liczniki, dźwięk i efekty.

// ---------- DOM ----------
export function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props ?? {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'style') {
      if (typeof v === 'string') el.style.cssText = v;
      else for (const [sk, sv] of Object.entries(v)) (sk.startsWith('--') ? el.style.setProperty(sk, sv) : (el.style[sk] = sv));
    } else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  append(el, kids);
  return el;
}

export function append(el, kids) {
  for (const k of kids.flat(Infinity)) {
    if (k === null || k === undefined || k === false) continue;
    el.append(k instanceof Node ? k : document.createTextNode(String(k)));
  }
  return el;
}

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
export const clear = (el) => {
  el.replaceChildren();
  return el;
};
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const rand = (a, b) => a + Math.random() * (b - a);
export const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
export const ease = { out: (t) => 1 - (1 - t) ** 3, inOut: (t) => (t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2), outBack: (t) => 1 + 2.70158 * (t - 1) ** 3 + 1.70158 * (t - 1) ** 2 };

/** Klatka po klatce przez `ms` milisekund: fn(t) dostaje postęp 0..1. Zwraca obietnicę na koniec. */
export function tween(ms, fn) {
  return new Promise((resolve) => {
    const start = performance.now();
    const step = (now) => {
      const t = clamp((now - start) / ms, 0, 1);
      fn(t);
      if (t < 1) requestAnimationFrame(step);
      else resolve();
    };
    requestAnimationFrame(step);
  });
}

// ---------- formatowanie ----------
export const fmt = (n) => Math.round(n).toLocaleString('pl-PL');
export function short(n) {
  const a = Math.abs(n);
  if (a >= 1e12) return `${+(n / 1e12).toFixed(2)} bln`;
  if (a >= 1e9) return `${+(n / 1e9).toFixed(2)} mld`;
  if (a >= 1e6) return `${+(n / 1e6).toFixed(2)} mln`;
  if (a >= 1e4) return `${+(n / 1e3).toFixed(1)} tys.`;
  return fmt(n);
}
export function duration(ms) {
  const s = Math.max(0, Math.ceil(ms / 1000));
  const d = Math.floor(s / 86400);
  const hh = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (d) return `${d} d ${hh} h`;
  if (hh) return `${hh} h ${String(m).padStart(2, '0')} min`;
  if (m) return `${m} min ${String(sec).padStart(2, '0')} s`;
  return `${sec} s`;
}
export const mult = (m) => `x${Number.isInteger(m) ? m : +m.toFixed(2)}`;

// ---------- zegary ----------
const tickers = new Set();
setInterval(() => {
  for (const t of tickers) {
    if (t.el && !t.el.isConnected) tickers.delete(t);
    else t.fn();
  }
}, 1000);

/** Tekst odliczający do chwili `until` (ms), odświeżany co sekundę; po końcu wywołuje onDone. */
export function countdown(el, until, { prefix = '', done = 'gotowe!', onDone } = {}) {
  let finished = false;
  const fn = () => {
    const left = until - Date.now();
    if (left <= 0) {
      el.textContent = done;
      if (!finished) {
        finished = true;
        onDone?.();
      }
      tickers.delete(t);
    } else el.textContent = prefix + duration(left);
  };
  const t = { el, fn };
  tickers.add(t);
  fn();
  return () => tickers.delete(t);
}

/** Płynna zmiana liczby w elemencie (np. saldo). */
export function animateNumber(el, to, { ms = 700, format = fmt } = {}) {
  const from = el._val ?? to;
  el._val = to;
  cancelAnimationFrame(el._raf);
  if (from === to) {
    el.textContent = format(to);
    return;
  }
  const start = performance.now();
  const step = (now) => {
    const t = clamp((now - start) / ms, 0, 1);
    el.textContent = format(from + (to - from) * ease.out(t));
    if (t < 1) el._raf = requestAnimationFrame(step);
  };
  el._raf = requestAnimationFrame(step);
}

// ---------- markdown z Discorda ----------
const INLINE = /\*\*(.+?)\*\*|__(.+?)__|\*(.+?)\*|`([^`]+)`|\|\|(.+?)\|\||<t:(\d+)(?::[a-zA-Z])?>|(@[\p{L}\p{N}_-]{3,20})/gu;

function inline(text) {
  const out = [];
  let last = 0;
  for (const m of text.matchAll(INLINE)) {
    if (m.index > last) out.push(text.slice(last, m.index));
    const [, bold, under, ital, code, spoil, ts, mention] = m;
    if (bold !== undefined) out.push(h('b', {}, inline(bold)));
    else if (under !== undefined) out.push(h('u', {}, inline(under)));
    else if (ital !== undefined) out.push(h('i', {}, inline(ital)));
    else if (code !== undefined) out.push(h('code', {}, code));
    else if (spoil !== undefined) out.push(h('span', { class: 'spoiler', onclick: (e) => e.currentTarget.classList.add('open') }, inline(spoil)));
    else if (ts !== undefined) {
      const el = h('span', { class: 'ts' });
      countdown(el, Number(ts) * 1000, { prefix: 'za ', done: 'teraz' });
      out.push(el);
    } else if (mention !== undefined) out.push(h('span', { class: 'mention' }, mention));
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

/** Renderuje podzbiór markdownu Discorda (pogrubienie, kod, nagłówki, spoilery, znaczniki czasu) do DocumentFragment. */
export function md(text) {
  const frag = document.createDocumentFragment();
  const lines = String(text ?? '').split('\n');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith('```')) {
      const buf = [];
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) buf.push(lines[i++]);
      frag.append(h('pre', {}, buf.join('\n')));
      continue;
    }
    const m = /^(#{1,3}) (.*)$/.exec(line);
    if (m) frag.append(h('span', { class: `h${m[1].length}` }, inline(m[2])));
    else {
      append(frag, inline(line));
      if (i < lines.length - 1) frag.append('\n');
    }
  }
  return frag;
}

// ---------- toasty i okna ----------
export function toast({ title, text = '', kind = 'info', icon, ms = 4500 }) {
  const root = document.getElementById('toasts');
  const icons = { win: '🎉', lose: '💸', warn: '⚠️', gold: '🏆', info: 'ℹ️' };
  const el = h('div', { class: `toast ${kind}` }, h('div', { class: 'ti emoji' }, icon ?? icons[kind] ?? 'ℹ️'), h('div', {}, h('b', {}, title), text && h('span', {}, text)));
  root.append(el);
  while (root.children.length > 5) root.firstChild.remove();
  const close = () => {
    el.classList.add('out');
    setTimeout(() => el.remove(), 260);
  };
  el.addEventListener('click', close);
  if (ms) setTimeout(close, ms);
  return close;
}

export function modal(content, { title, wide = false, onClose } = {}) {
  const root = document.getElementById('modal-root');
  const close = () => {
    back.remove();
    document.removeEventListener('keydown', onKey);
    onClose?.();
  };
  const onKey = (e) => e.key === 'Escape' && close();
  const box = h('div', { class: `modal${wide ? ' wide' : ''}`, role: 'dialog', 'aria-modal': 'true' }, h('button', { class: 'mclose', 'aria-label': 'Zamknij', onclick: close }, '✕'), title && h('h2', { style: 'margin-bottom:12px' }, title), content);
  const back = h('div', { class: 'modal-back', onmousedown: (e) => e.target === back && close() }, box);
  root.append(back);
  document.addEventListener('keydown', onKey);
  return { close, el: box };
}

// ---------- dźwięk (syntezowany, bez plików) ----------
let audio = null;
const sfxState = { on: (() => { try { return localStorage.getItem('sfx') !== '0'; } catch { return true; } })() };
function ctx() {
  if (!audio) {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    audio = new AC();
  }
  if (audio.state === 'suspended') audio.resume().catch(() => {});
  return audio;
}
function tone(freq, dur, { type = 'sine', vol = 0.08, to = null, at = 0 } = {}) {
  const c = ctx();
  if (!c) return;
  const t0 = c.currentTime + at;
  const o = c.createOscillator();
  const g = c.createGain();
  o.type = type;
  o.frequency.setValueAtTime(freq, t0);
  if (to) o.frequency.exponentialRampToValueAtTime(to, t0 + dur);
  g.gain.setValueAtTime(0.0001, t0);
  g.gain.exponentialRampToValueAtTime(vol, t0 + 0.012);
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
  o.connect(g).connect(c.destination);
  o.start(t0);
  o.stop(t0 + dur + 0.02);
}
function noise(dur, vol = 0.1, at = 0) {
  const c = ctx();
  if (!c) return;
  const buf = c.createBuffer(1, Math.ceil(c.sampleRate * dur), c.sampleRate);
  const d = buf.getChannelData(0);
  for (let i = 0; i < d.length; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / d.length);
  const src = c.createBufferSource();
  const g = c.createGain();
  g.gain.value = vol;
  src.buffer = buf;
  src.connect(g).connect(c.destination);
  src.start(c.currentTime + at);
}
const SOUNDS = {
  click: () => tone(520, 0.06, { type: 'triangle', vol: 0.05 }),
  tick: () => tone(900, 0.03, { type: 'square', vol: 0.025 }),
  flip: () => tone(300, 0.25, { type: 'triangle', to: 900, vol: 0.05 }),
  coin: () => { tone(988, 0.08, { type: 'square', vol: 0.05 }); tone(1319, 0.2, { type: 'square', vol: 0.05, at: 0.07 }); },
  win: () => [523, 659, 784, 1047].forEach((f, i) => tone(f, 0.16, { type: 'triangle', vol: 0.08, at: i * 0.08 })),
  big: () => { [523, 659, 784, 1047, 1319, 1568].forEach((f, i) => tone(f, 0.28, { type: 'triangle', vol: 0.09, at: i * 0.09 })); noise(0.5, 0.05, 0.2); },
  lose: () => [330, 262, 196].forEach((f, i) => tone(f, 0.22, { type: 'sawtooth', vol: 0.05, at: i * 0.12 })),
  boom: () => { noise(0.6, 0.18); tone(120, 0.5, { type: 'sawtooth', to: 40, vol: 0.12 }); },
  whoosh: () => tone(200, 0.4, { type: 'sine', to: 1200, vol: 0.04 }),
};
export const sfx = {
  get on() { return sfxState.on; },
  set on(v) {
    sfxState.on = v;
    try { localStorage.setItem('sfx', v ? '1' : '0'); } catch { /* tryb prywatny */ }
  },
  play(name) {
    if (sfxState.on) SOUNDS[name]?.();
  },
};

// ---------- efekty na canvasie: konfetti, monety, błysk ----------
const particles = [];
let fxRaf = 0;
function fxLoop() {
  const canvas = document.getElementById('fx');
  const c = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  if (canvas.width !== innerWidth * dpr || canvas.height !== innerHeight * dpr) {
    canvas.width = innerWidth * dpr;
    canvas.height = innerHeight * dpr;
  }
  c.setTransform(dpr, 0, 0, dpr, 0, 0);
  c.clearRect(0, 0, innerWidth, innerHeight);
  for (let i = particles.length - 1; i >= 0; i--) {
    const p = particles[i];
    p.vy += p.g;
    p.vx *= 0.995;
    p.x += p.vx;
    p.y += p.vy;
    p.rot += p.vr;
    p.life -= 1;
    if (p.life <= 0 || p.y > innerHeight + 40) {
      particles.splice(i, 1);
      continue;
    }
    c.save();
    c.translate(p.x, p.y);
    c.rotate(p.rot);
    c.globalAlpha = Math.min(1, p.life / 25);
    if (p.emoji) {
      c.font = `${p.size}px "Segoe UI Emoji","Apple Color Emoji","Noto Color Emoji",sans-serif`;
      c.textAlign = 'center';
      c.textBaseline = 'middle';
      c.fillText(p.emoji, 0, 0);
    } else {
      c.fillStyle = p.color;
      c.fillRect(-p.size / 2, -p.size / 3, p.size, p.size * 0.6);
    }
    c.restore();
  }
  fxRaf = particles.length ? requestAnimationFrame(fxLoop) : 0;
  if (!particles.length) c.clearRect(0, 0, innerWidth, innerHeight);
}
const reduced = () => matchMedia('(prefers-reduced-motion: reduce)').matches;
function emit(list) {
  if (reduced()) return;
  particles.push(...list);
  if (particles.length > 600) particles.splice(0, particles.length - 600);
  if (!fxRaf) fxRaf = requestAnimationFrame(fxLoop);
}
export const fx = {
  /** Konfetti z punktu (x, y) w pikselach okna; domyślnie ze środka ekranu. */
  confetti({ x = innerWidth / 2, y = innerHeight * 0.45, count = 90, power = 9 } = {}) {
    const colors = ['#8b5cf6', '#22d3ee', '#fbbf24', '#34d399', '#fb7185', '#f472b6'];
    emit(Array.from({ length: count }, () => {
      const a = rand(0, Math.PI * 2);
      const v = rand(power * 0.3, power);
      return { x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v - power * 0.6, g: 0.22, rot: rand(0, 6), vr: rand(-0.3, 0.3), size: rand(7, 13), color: colors[(Math.random() * colors.length) | 0], life: rand(70, 130) };
    }));
  },
  /** Deszcz emoji (monety, gwiazdki...) spadający z góry. */
  rain(emoji = '🪙', count = 40) {
    emit(Array.from({ length: count }, () => ({ x: rand(0, innerWidth), y: rand(-200, -20), vx: rand(-1, 1), vy: rand(1, 4), g: 0.12, rot: rand(0, 6), vr: rand(-0.1, 0.1), size: rand(22, 38), emoji, life: 260 })));
  },
  burst(emoji, x, y, count = 14) {
    emit(Array.from({ length: count }, () => {
      const a = rand(0, Math.PI * 2);
      const v = rand(2, 8);
      return { x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v - 3, g: 0.25, rot: 0, vr: rand(-0.2, 0.2), size: rand(18, 30), emoji, life: rand(40, 80) };
    }));
  },
  flash(color = 'rgba(52,211,153,0.25)') {
    if (reduced()) return;
    const el = h('div', { style: { position: 'fixed', inset: 0, background: color, zIndex: 850, pointerEvents: 'none', transition: 'opacity 0.6s' } });
    document.body.append(el);
    requestAnimationFrame(() => (el.style.opacity = '0'));
    setTimeout(() => el.remove(), 700);
  },
  shake(el) {
    el.classList.remove('shake');
    void el.offsetWidth;
    el.classList.add('shake');
  },
};

/** Środek elementu w pikselach okna (do efektów). */
export function centerOf(el) {
  const r = el.getBoundingClientRect();
  return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
}
