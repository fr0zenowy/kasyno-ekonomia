// Kasyno: siatka gier z podziałem na rodzaje.
import { h } from '../ui.js';
import { GAMES, GROUPS } from '../games/registry.js';

export function gameTile(g) {
  return h(
    'a',
    { class: 'tile', href: `#/game/${g.id}`, style: { '--tile': `${g.color}55` } },
    h('div', { class: 'ico emoji' }, g.emoji),
    h('h3', {}, g.name),
    h('p', {}, g.desc),
    h('div', { class: 'tags' }, g.tags.map((t) => h('span', { class: 'badge accent' }, t))),
  );
}

export default function casino(container) {
  container.append(
    h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, '🎰 Kasyno'), h('p', {}, 'Wszystkie gry używają tych samych zasad co bot: zwrot każdej jest poniżej 100%, a ochrona (polisa, tarcza) działa wszędzie.'))),
    GROUPS.map(([key, title]) =>
      h('section', { style: 'margin-bottom:26px' }, h('h2', { style: 'margin-bottom:12px' }, title), h('div', { class: 'grid auto' }, GAMES.filter((g) => g.group === key).map(gameTile))),
    ),
    h('section', {}, h('h2', { style: 'margin-bottom:12px' }, '👥 Z innymi graczami'), h('div', { class: 'grid auto' },
      h('a', { class: 'tile', href: '#/lobby', style: { '--tile': '#fbbf2455' } }, h('div', { class: 'ico emoji' }, '🎰'), h('h3', {}, 'Jackpot'), h('p', {}, 'Wszyscy dorzucają tę samą stawkę, jedna osoba bierze całą pulę.'), h('div', { class: 'tags' }, h('span', { class: 'badge gold' }, 'na żywo'))),
      h('a', { class: 'tile', href: '#/lobby', style: { '--tile': '#fb718555' } }, h('div', { class: 'ico emoji' }, '⚔️'), h('h3', {}, 'Pojedynek'), h('p', {}, 'Wyzwij gracza na 50/50 o pieniądze.'), h('div', { class: 'tags' }, h('span', { class: 'badge gold' }, 'PvP'))),
      h('a', { class: 'tile', href: '#/lobby', style: { '--tile': '#22d3ee55' } }, h('div', { class: 'ico emoji' }, '🏦'), h('h3', {}, 'Napad na bank'), h('p', {}, 'Zbierz ekipę, a łup podzielicie po równo.'), h('div', { class: 'tags' }, h('span', { class: 'badge gold' }, 'ekipa'))),
    )),
  );
}
