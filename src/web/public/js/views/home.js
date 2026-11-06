// Strona startowa: powitanie, odbiór nagród (daily, tygodniowa, godzinna), polecane gry, wygrane na żywo i ranking.
import { h, fmt, short, countdown, duration, clear } from '../ui.js';
import { api } from '../api.js';
import { store } from '../store.js';
import { act } from '../render.js';
import { GAMES } from '../games/registry.js';
import { gameTile } from './casino.js';

function claimTile({ icon, title, desc, cmd, left, color }) {
  const wrap = h('div', { class: 'tile', style: { '--tile': `${color}44`, cursor: 'default' } });
  const btn = h('button', { class: 'btn gold', onclick: async () => {
    btn.classList.add('loading');
    const run = await act(cmd, {}, null, { as: 'toast' });
    await run?.done;
    btn.classList.remove('loading');
  } }, 'Odbierz');
  const paint = () => {
    const ms = left(store.me);
    const timer = h('span', { class: 'muted small mono' });
    clear(wrap).append(
      h('div', { class: 'ico emoji' }, icon),
      h('h3', {}, title),
      h('p', {}, desc),
      h('div', { style: 'margin-top:auto' }, ms > 0 ? h('div', { class: 'row' }, h('span', { class: 'emoji' }, '⏳'), (() => { countdown(timer, Date.now() + ms, { prefix: 'za ' }); return timer; })()) : btn),
    );
  };
  paint();
  wrap.repaint = paint;
  return wrap;
}

export default function home(container) {
  const me = store.me;
  const tiles = [
    claimTile({ icon: '🎁', title: 'Nagroda dzienna', desc: 'Seria dni + odsetki z banku. Co 7 dni skrzynia!', cmd: 'daily', left: (m) => m.daily, color: '#fbbf24' }),
    claimTile({ icon: '📅', title: 'Nagroda tygodniowa', desc: 'Gotówka i skrzynia skarbów.', cmd: 'weekly', left: (m) => m.weekly, color: '#a78bfa' }),
    claimTile({ icon: '⏰', title: 'Premia godzinna', desc: 'Rośnie z poziomem, czasem skrzynka.', cmd: 'godzinna', left: (m) => m.cooldowns.godzinna ?? 0, color: '#22d3ee' }),
  ];

  const feed = h('div', { class: 'col gap-s' });
  const top = h('div', { class: 'skeleton', style: 'height:180px' });
  const effects = h('div', { class: 'row' });
  const stats = h('div', { class: 'row gap-l' });

  const paintFeed = () => {
    clear(feed);
    if (!store.feed.length) feed.append(h('p', { class: 'muted small' }, 'Tu pojawią się duże wygrane graczy. Może Twoja będzie pierwsza?'));
    for (const f of store.feed.slice(0, 6)) feed.append(h('div', { class: 'feed-item' }, h('span', { class: 'emoji' }, f.mult >= 100 ? '🔥' : '💰'), h('b', {}, f.name), h('span', { class: 'muted' }, 'wygrywa'), h('b', { class: 'win' }, `${fmt(f.amount)} 💰`), h('span', { class: 'badge gold' }, `x${f.mult}`)));
  };

  const paint = () => {
    const m = store.me;
    const net = m.stats.won - m.stats.lost;
    clear(stats).append(
      stat('🎮', 'Gier', fmt(m.stats.games)),
      stat('📈', 'Bilans', `${net >= 0 ? '+' : ''}${short(net)}`, net >= 0 ? 'win' : 'lose'),
      stat('🏅', 'Rekord', short(m.stats.biggest)),
      stat('🔥', 'Seria daily', `${m.streak} dni`),
      stat('🏆', 'Ranking', `#${m.rank}`),
    );
    clear(effects);
    for (const b of m.boosts) effects.append(h('span', { class: 'badge accent' }, `${store.item(b.item).emoji} ${b.label} • ${duration(b.left)}`));
    for (const p of m.protections) effects.append(h('span', { class: 'badge win' }, `${store.item(p.item).emoji} ${store.item(p.item).name} ×${p.uses}`));
    if (m.clover) effects.append(h('span', { class: 'badge gold' }, `🍀 koniczyna ×${m.clover}`));
    if (!effects.children.length) effects.append(h('span', { class: 'muted small' }, 'Brak aktywnych bonusów. Eliksiry, polisy i tarcze kupisz w sklepie.'));
    tiles.forEach((t) => t.repaint());
  };

  const stat = (icon, label, value, cls = '') => h('div', {}, h('div', { class: 'tiny muted' }, `${icon} ${label}`), h('div', { class: `bold ${cls}`, style: 'font-size:1.15rem' }, value));

  container.append(
    h('div', { class: 'card glow', style: 'margin-bottom:18px; background: linear-gradient(135deg, rgba(139,92,246,0.25), rgba(34,211,238,0.12)), var(--surface)' },
      h('div', { class: 'row between' },
        h('div', {}, h('h1', {}, `Cześć, ${store.user.name}! 👋`), h('p', { class: 'muted', style: 'margin-top:4px' }, me.wallet < 100 ? 'Kończą Ci się monety. Odbierz nagrody poniżej albo pozarabiaj w zakładce Zarobki.' : 'Gotowy na kolejną rundę?')),
        h('a', { class: 'btn primary big', href: '#/casino' }, h('span', { class: 'emoji' }, '🎰'), 'Do kasyna')),
      h('hr', { class: 'sep', style: 'margin:14px 0' }), stats),
    h('h2', { style: 'margin-bottom:10px' }, '🎁 Do odebrania'),
    h('div', { class: 'grid auto', style: 'margin-bottom:22px' }, tiles),
    h('h2', { style: 'margin-bottom:10px' }, '⚡ Aktywne bonusy'),
    h('div', { class: 'card flat', style: 'margin-bottom:22px' }, effects),
    h('div', { class: 'row between', style: 'margin-bottom:10px' }, h('h2', {}, '🔥 Polecane gry'), h('a', { href: '#/casino' }, 'Wszystkie →')),
    h('div', { class: 'grid auto', style: 'margin-bottom:22px' }, ['crash', 'kurczak', 'plinko', 'sloty'].map((id) => gameTile(GAMES.find((g) => g.id === id)))),
    h('div', { class: 'grid two' },
      h('div', { class: 'card' }, h('h2', { style: 'margin-bottom:10px' }, '💎 Wygrane na żywo'), feed),
      h('div', { class: 'card' }, h('div', { class: 'row between', style: 'margin-bottom:10px' }, h('h2', {}, '🏆 Najbogatsi'), h('a', { href: '#/ranking' }, 'Ranking →')), top)),
  );

  paint();
  paintFeed();
  api('GET', '/api/leaderboard?by=majatek').then(({ rows }) => {
    top.replaceWith(h('table', { class: 'table' }, h('tbody', {}, rows.slice(0, 5).map((r) =>
      h('tr', { class: `link${r.id === store.user.id ? ' me' : ''}`, onclick: () => (location.hash = `#/profile/${r.id}`) }, h('td', { class: 'place' }, ['🥇', '🥈', '🥉'][r.place - 1] ?? r.place), h('td', {}, r.name, h('span', { class: 'muted small' }, ` • poz. ${r.level}`)), h('td', { class: 'gold mono', style: 'text-align:right' }, `${short(r.wealth)} 💰`))))));
  }).catch(() => {});

  return store.subscribe((what) => {
    if (what === 'me') paint();
    if (what === 'feed') paintFeed();
  });
}
