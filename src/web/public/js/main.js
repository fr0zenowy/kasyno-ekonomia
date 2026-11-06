// Start aplikacji: sesja, powłoka (pasek, menu, szuflada), routing po hashu i podpięcie strumienia zdarzeń.
import { h, clear, animateNumber, fmt, toast, sfx, short } from './ui.js';
import { api, connectStream, disconnectStream, onEvent } from './api.js';
import { store } from './store.js';
import { dockShow, dockClear } from './render.js';
import { authView } from './views/auth.js';
import { routes } from './views/index.js';

const app = document.getElementById('app');
let dispose = null;

const NAV = [
  ['Gra', [['', '🏠', 'Start'], ['casino', '🎰', 'Kasyno'], ['lobby', '🎲', 'Pokój graczy']]],
  ['Zarabiaj', [['earn', '💼', 'Zarobki'], ['empire', '🏢', 'Biznes i farma'], ['crates', '🎁', 'Skrzynie']]],
  ['Majątek', [['shop', '🛒', 'Sklep'], ['inventory', '🎒', 'Ekwipunek'], ['bank', '🏦', 'Bank']]],
  ['Społeczność', [['ranking', '🏆', 'Ranking'], ['profile', '👤', 'Mój profil'], ['help', '❓', 'Pomoc']]],
];

function parseHash() {
  const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent);
  return { name: parts[0] ?? '', params: parts.slice(1) };
}

function renderShell() {
  clear(app);
  const walletEl = h('span', { class: 'mono' }, fmt(store.me.wallet));
  const bankEl = h('span', { class: 'mono' }, fmt(store.me.bank));
  const walletChip = h('div', { class: 'chip gold', title: 'Portfel' }, h('span', { class: 'emoji' }, '💰'), walletEl);
  const levelFill = h('div', { class: 'fill' });
  const levelText = h('span', {}, '');
  const levelChip = h('div', { class: 'chip level-chip', title: 'Poziom i doświadczenie' }, levelFill, levelText);
  const online = h('span', {}, '');
  const sfxBtn = h('button', { class: 'btn ghost small', title: 'Dźwięk', 'aria-label': 'Dźwięk', onclick: () => { sfx.on = !sfx.on; sfxBtn.textContent = sfx.on ? '🔊' : '🔇'; sfx.play('click'); } }, sfx.on ? '🔊' : '🔇');

  const topbar = h(
    'header',
    { class: 'topbar' },
    h('a', { class: 'logo', href: '#/' }, h('span', { class: 'emoji' }, '🎰'), h('span', { class: 't' }, 'Kasyno ', h('b', {}, 'Ekonomia'))),
    h('div', { class: 'spacer' }),
    h('div', { class: 'hud' },
      h('div', { class: 'chip hud-opt', title: 'Graczy online' }, h('span', { class: 'emoji' }, '🟢'), online),
      walletChip,
      h('div', { class: 'chip hud-opt', title: 'Bank' }, h('span', { class: 'emoji' }, '🏦'), bankEl),
      levelChip,
      sfxBtn,
      h('button', { class: 'btn ghost small', onclick: logout, title: 'Wyloguj' }, `${store.user.name} ⎋`)),
  );

  const navLinks = [];
  const side = h('nav', { class: 'nav' });
  for (const [group, items] of NAV) {
    side.append(h('h4', {}, group));
    for (const [path, icon, label] of items) {
      const a = h('a', { href: `#/${path}`, dataset: { path } }, h('span', { class: 'emoji' }, icon), label, path === 'lobby' && h('span', { class: 'badge-dot hide', id: 'lobby-dot' }));
      navLinks.push(a);
      side.append(a);
    }
  }
  const bottom = h('nav', { class: 'bottomnav' }, NAV.flatMap(([, items]) => items).map(([path, icon, label]) => {
    const a = h('a', { href: `#/${path}`, dataset: { path } }, h('span', { class: 'emoji' }, icon), label.split(' ')[0]);
    navLinks.push(a);
    return a;
  }));

  const main = h('main', { class: 'main', id: 'main' });
  app.append(h('div', { class: 'shell' }, topbar, h('aside', { class: 'sidebar' }, side), main), bottom);

  const refresh = (what) => {
    const me = store.me;
    if (what === 'me' || what === undefined) {
      const prev = store.last.wallet;
      animateNumber(walletEl, me.wallet, { ms: 650 });
      animateNumber(bankEl, me.bank, { ms: 650 });
      if (prev !== null && me.wallet !== prev) {
        const cls = me.wallet > prev ? 'pulse-win' : 'pulse-lose';
        walletChip.classList.remove('pulse-win', 'pulse-lose');
        void walletChip.offsetWidth;
        walletChip.classList.add(cls);
      }
      levelText.textContent = `Poz. ${me.level} • ${short(me.xp)}/${short(me.xpNeeded)} XP`;
      levelFill.style.width = `${Math.min(100, (me.xp / me.xpNeeded) * 100)}%`;
    }
    online.textContent = String(store.online);
    const dot = document.getElementById('lobby-dot');
    if (dot) dot.classList.toggle('hide', ![...store.live.values()].some((m) => m.shared && m.live));
  };
  store.subscribe(refresh);
  refresh('me');
  return { main, navLinks };
}

let shell = null;
function route() {
  const { name, params } = parseHash();
  const view = routes[name] ?? routes[''];
  dispose?.();
  dispose = null;
  const { main, navLinks } = shell;
  for (const a of navLinks) a.classList.toggle('active', a.dataset.path === (routes[name] ? name : ''));
  clear(main);
  main.scrollTo?.(0, 0);
  window.scrollTo(0, 0);
  const container = h('div', { class: 'view' });
  main.append(container);
  Promise.resolve(view(container, params)).then((d) => {
    dispose = typeof d === 'function' ? d : null;
  }).catch((err) => {
    console.error(err);
    container.replaceChildren(h('div', { class: 'card' }, h('h2', {}, 'Ups, coś poszło nie tak'), h('p', { class: 'muted' }, err.message)));
  });
}

async function enter(user) {
  store.user = user;
  const [catalog, me] = await Promise.all([api('GET', '/api/catalog'), api('GET', '/api/me')]);
  store.catalog = catalog;
  store.me = me;
  shell = renderShell();
  // Wspólne wiadomości (jackpot, napad, pojedynek) widać w szufladzie z każdej podstrony.
  onEvent('msg', (m) => m.shared && dockShow(m));
  onEvent('edit', (m) => m.shared && dockShow(m));
  connectStream();
  window.addEventListener('hashchange', route);
  route();
}

async function logout() {
  try {
    await api('POST', '/api/logout', {});
  } catch { /* wylogowanie lokalne i tak się uda */ }
  location.hash = '';
  location.reload();
}

async function boot() {
  try {
    const s = await api('GET', '/api/session');
    if (s.user) await enter(s.user);
    else authView(app, s, enter);
  } catch (err) {
    app.replaceChildren(h('div', { class: 'boot' }, h('div', { class: 'boot-logo' }, '🔌'), h('div', { class: 'boot-text' }, `Nie można połączyć z serwerem: ${err.message}`), h('button', { class: 'btn', onclick: () => location.reload() }, 'Spróbuj ponownie')));
  }
}

window.addEventListener('unhandledrejection', (e) => {
  console.error(e.reason);
  if (e.reason?.status === 401) location.reload();
});
export { dockClear, disconnectStream, toast };
boot();
