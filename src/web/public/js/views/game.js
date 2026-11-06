// Ekran pojedynczej gry: nagłówek i moduł gry ładowany na żądanie.
import { h } from '../ui.js';
import { gameById } from '../games/registry.js';

export default async function game(container, params) {
  const g = gameById(params[0]);
  if (!g) {
    container.append(h('div', { class: 'card' }, h('h2', {}, 'Nie ma takiej gry'), h('a', { class: 'btn', href: '#/casino', style: 'margin-top:12px' }, '← Do kasyna')));
    return;
  }
  const body = h('div', {});
  container.append(
    h('div', { class: 'page-head' },
      h('div', {}, h('a', { href: '#/casino', class: 'muted small' }, '← Kasyno'), h('h1', {}, h('span', { class: 'emoji' }, g.emoji), ` ${g.name}`), h('p', {}, g.desc)),
      h('div', { class: 'row' }, g.tags.map((t) => h('span', { class: 'badge accent' }, t)))),
    body,
  );
  const mod = await g.load();
  return mod.mount(body, g);
}
