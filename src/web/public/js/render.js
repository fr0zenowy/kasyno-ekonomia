// Ogólny renderer wiadomości z komend (embedy, pola, przyciski) i szuflada „dock” z trwającymi wynikami.
// Używany tam, gdzie nie ma własnego ekranu gry: zarobki, sklep, pojedynek, napad i wszystko, co dojdzie w przyszłości.
import { h, md, toast, sfx, fx } from './ui.js';
import { startRun, clickButton } from './api.js';
import { store } from './store.js';

const hex = (n) => `#${(n ?? 0x8b5cf6).toString(16).padStart(6, '0')}`;

export function renderEmbed(e) {
  const fields = (e.fields ?? []).map((f) => h('div', { class: `efield${f.inline ? '' : ' wide'}` }, h('b', {}, md(f.name)), h('div', {}, md(f.value))));
  return h(
    'div',
    { class: 'embed', style: { '--ec': hex(e.color) } },
    e.title && h('div', { class: 'etitle' }, md(e.title)),
    e.description && h('div', { class: 'edesc' }, md(e.description)),
    fields.length && h('div', { class: 'efields' }, fields),
    e.footer?.text && h('div', { class: 'efoot' }, e.footer.text),
  );
}

const emojiOf = (b) => b.emoji?.name ?? '';

/** Wiadomość jako element DOM. `update(msg)` podmienia treść po edycji (klatki animacji, nowy stan gry). */
export function renderMessage(initial, { onClose, title } = {}) {
  const root = h('div', { class: 'msg' });
  let busy = false;

  const paint = (msg) => {
    root.replaceChildren();
    root.dataset.id = msg.id;
    if (title !== null) {
      root.append(h('div', { class: 'mhead' }, h('span', {}, title ?? `/${msg.cmd} • ${msg.owner.name}`), msg.live && h('span', { class: 'badge win' }, 'na żywo'), onClose && h('button', { onclick: onClose, 'aria-label': 'Zamknij' }, '✕')));
    }
    if (msg.content) root.append(h('div', { class: 'mtext' }, md(msg.content)));
    for (const e of msg.embeds) root.append(renderEmbed(e));
    const rows = msg.components.filter((r) => r.components?.length);
    if (rows.length) {
      root.append(
        h(
          'div',
          { class: 'comps' },
          rows.map((r) =>
            h(
              'div',
              { class: 'crow' },
              r.components.map((b) =>
                h(
                  'button',
                  {
                    class: `cbtn s${b.style ?? 2}`,
                    disabled: b.disabled || !msg.live || busy,
                    onclick: async () => {
                      sfx.play('click');
                      busy = true;
                      paint(msg);
                      await clickButton(msg.id, b.custom_id);
                      busy = false;
                      if (root.isConnected && root.dataset.id === msg.id) paint(root._msg);
                    },
                  },
                  emojiOf(b) && h('span', { class: 'emoji' }, emojiOf(b)),
                  b.label,
                ),
              ),
            ),
          ),
        ),
      );
    }
    root._msg = msg;
  };
  paint(initial);
  return { el: root, update: (msg) => paint(msg) };
}

// ---------- dock ----------

const cards = new Map();
function dockRoot() {
  let el = document.getElementById('dock');
  if (!el) {
    el = h('div', { id: 'dock', class: 'dock' });
    document.body.append(el);
  }
  return el;
}

/** Pokazuje (albo odświeża) wiadomość w szufladzie. Zakończone znikają po chwili, trwające zostają. */
export function dockShow(msg) {
  const root = dockRoot();
  let card = cards.get(msg.id);
  if (!card) {
    const close = () => {
      clearTimeout(card.timer);
      card.el.remove();
      cards.delete(msg.id);
    };
    const r = renderMessage(msg, { onClose: close });
    card = { ...r, close, timer: null };
    cards.set(msg.id, card);
    root.append(card.el);
    card.el.scrollIntoView?.({ block: 'nearest' });
  } else card.update(msg);
  clearTimeout(card.timer);
  if (!msg.live) card.timer = setTimeout(card.close, msg.components.length ? 30_000 : 16_000);
  while (cards.size > 4) cards.values().next().value.close();
}

export const dockClear = () => [...cards.values()].forEach((c) => c.close());

/** Tekst wiadomości bez formatowania (do toastów). */
export function plain(msg) {
  const e = msg.embeds[0];
  const clean = (s) => String(s ?? '').replace(/[*_`#]/g, '').replace(/\|\|/g, '').trim();
  return { title: clean(e?.title ?? msg.content?.split('\n')[0] ?? ''), text: clean(e?.description ?? (e ? '' : msg.content?.split('\n').slice(1).join('\n'))).slice(0, 360) };
}

/**
 * Uruchamia komendę i pokazuje jej wynik: prywatne komunikaty (błędy, odmowy) jako toast,
 * reszta jako karta w szufladzie (`as: 'card'`) albo krótki toast z wynikiem (`as: 'toast'`).
 * Zwraca Run albo null, gdy serwer odrzucił zapytanie.
 */
export async function act(name, options = {}, sub = null, { as = 'card' } = {}) {
  let run;
  try {
    run = await startRun(name, options, sub);
  } catch (err) {
    toast({ title: err.message, kind: 'warn' });
    return null;
  }
  run.on((msg) => {
    if (!msg) return;
    const t = plain(msg);
    if (msg.ephemeral) {
      toast({ title: t.title || 'Uwaga', text: t.text, kind: 'warn' });
      return;
    }
    if (as === 'toast') {
      if (!msg.live && (msg.embeds.length || msg.content)) {
        const e = msg.embeds[0];
        const win = e && e.color === 0x2ecc71;
        const kind = e?.color === 0xe74c3c ? 'lose' : win ? 'win' : 'info';
        if (kind === 'win') sfx.play('coin');
        toast({ title: t.title, text: t.text, kind });
      }
      return;
    }
    dockShow(msg);
  });
  return run;
}

/** Wynik gry w formie banera (kwota, mnożnik) z konfetti przy dużej wygranej. */
export function celebrate(net, bet, { anchor } = {}) {
  const m = bet ? (net + bet) / bet : 0;
  if (net > 0) {
    if (m >= 10) {
      sfx.play('big');
      fx.confetti({ count: 160, power: 12 });
      fx.rain('🪙', 36);
      fx.flash('rgba(251,191,36,0.22)');
    } else if (m >= 3) {
      sfx.play('win');
      fx.confetti({ count: 90 });
    } else sfx.play('win');
  } else if (net < 0) {
    sfx.play('lose');
    if (anchor) fx.shake(anchor);
  }
  return m;
}

export const coinsLabel = (n) => `${n > 0 ? '+' : n < 0 ? '−' : ''}${Math.abs(Math.round(n)).toLocaleString('pl-PL')} 💰`;
export { store };
