// Moneta: rzut w 3D. Wynik jest znany serwerowi od razu, ekran tylko odtwarza lot monety.
import { h, clear, fmt, sfx, sleep } from '../ui.js';
import { store } from '../store.js';
import { betPanel, gameFrame, playInstant, showResult, clearResult, historyBar } from './common.js';

const FACES = { orzel: '🦅', reszka: '🪙' };

export function mount(container) {
  const { stage, result, side, info } = gameFrame(container);
  const bet = betPanel('moneta', { hint: (n) => `wygrana ${fmt(Math.floor(n * 1.95))} 💰` });
  let side_ = 'orzel';
  let busy = false;

  const coin = h('div', { class: 'coin' }, h('div', { class: 'face front emoji' }, FACES.orzel), h('div', { class: 'face back emoji' }, FACES.reszka));
  const title = h('div', { class: 'stage-title' }, 'Wybierz stronę i rzuć monetą');
  stage.append(h('div', { class: 'col center', style: 'align-items:center' }, title, h('div', { class: 'coin-wrap' }, coin), h('div', { class: 'coin-shadow' })));

  const hist = historyBar();
  const pick = h('div', { class: 'opts cols2' });
  const paintPick = () => {
    clear(pick).append(...Object.entries(FACES).map(([k, e]) => h('button', { class: `opt${side_ === k ? ' on' : ''}`, disabled: busy, onclick: () => ((side_ = k), sfx.play('tick'), paintPick()) }, h('span', { class: 'emoji' }, e), k === 'orzel' ? 'Orzeł' : 'Reszka')));
  };
  paintPick();

  const play = h('button', { class: 'btn gold big playbtn', onclick: async () => {
    if (busy) return;
    busy = true;
    play.disabled = true;
    paintPick();
    clearResult(result);
    const chosen = side_;
    const meta = await playInstant('moneta', { zaklad: bet.value(), strona: chosen }, async (m) => {
      title.textContent = 'Moneta w powietrzu…';
      sfx.play('flip');
      const turns = 5 * 360 + (m.result === 'orzel' ? 0 : 180);
      await coin.animate(
        [
          { transform: 'translateY(0) rotateY(0deg) scale(1)' },
          { transform: 'translateY(-140px) rotateY(' + turns * 0.55 + 'deg) scale(1.25)', offset: 0.5 },
          { transform: 'translateY(0) rotateY(' + turns + 'deg) scale(1)' },
        ],
        { duration: 1800, easing: 'cubic-bezier(0.3, 0.6, 0.4, 1)', fill: 'forwards' },
      ).finished;
      coin.style.transform = `rotateY(${turns}deg)`;
      coin.getAnimations().forEach((a) => a.cancel());
      sfx.play('coin');
    });
    busy = false;
    play.disabled = false;
    paintPick();
    if (!meta) {
      title.textContent = 'Wybierz stronę i rzuć monetą';
      return;
    }
    title.textContent = `Wypadło: ${meta.result === 'orzel' ? 'Orzeł 🦅' : 'Reszka 🪙'}`;
    showResult(result, { net: meta.net, bet: meta.bet, label: `postawiono ${FACES[meta.side]}`, anchor: stage });
    hist.push(FACES[meta.result], meta.won ? 'win' : 'lose');
  } }, h('span', { class: 'emoji' }, '🪙'), 'Rzuć monetą');

  side.append(bet.el, h('div', { class: 'field' }, h('label', {}, 'Na co stawiasz'), pick), play, h('div', {}, h('div', { class: 'label', style: 'margin-bottom:6px' }, 'Ostatnie rzuty'), hist.el));
  info.append('Szansa 50%, wypłata x1.95 (zwrot 97,5%). Polisa zwraca stawkę po przegranej.');
  return () => bet.dispose();
}
