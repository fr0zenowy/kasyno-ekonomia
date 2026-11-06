const { sleep } = require('./util');
const { delay } = require('./game');

// Ułamki drogi, jakie pasek pokonuje w kolejnych klatkach: na początku szybko, pod koniec zwalnia.
const SPIN_STEPS = [0.14, 0.33, 0.5, 0.63, 0.75, 0.84, 0.91, 0.96];
const SPIN_DELAYS = [450, 450, 500, 600, 700, 800, 950, 1100];

/** Okno paska w stylu CS:GO: `2*half+1` komórek, środkowa obrzucona znacznikami » «. */
function stripView(cells, center, half = 3) {
  const out = [];
  for (let k = center - half; k <= center + half; k++) {
    const cell = cells[k] ?? '⬛';
    out.push(k === center ? `» ${cell} «` : cell);
  }
  return out.join(' ');
}

/** Losowy pasek, w którym na pozycji `target` leży zwycięska komórka. */
function buildStrip(winner, randomCell, target, half = 3) {
  const cells = Array.from({ length: target + half + 2 }, () => randomCell());
  cells[target] = winner;
  return cells;
}

/**
 * Odtwarza animację przewijania paska: wywołuje `show(widok, nrKlatki)` dla kolejnych klatek,
 * po każdej czeka (coraz dłużej). Zwraca widok końcowy, który wywołujący pokazuje razem z wynikiem.
 * Błędy edycji wiadomości są połykane – animacja to tylko dekoracja, wynik jest już zapisany.
 */
async function spinStrip(cells, target, show, { half = 3 } = {}) {
  let last = -1;
  for (let k = 0; k < SPIN_STEPS.length; k++) {
    const pos = Math.round(half + (target - half) * SPIN_STEPS[k]);
    if (pos <= last || pos >= target) continue;
    last = pos;
    try {
      await show(stripView(cells, pos, half), k);
    } catch (err) {
      console.error('Błąd klatki animacji:', err.message);
    }
    await sleep(delay(SPIN_DELAYS[k]));
  }
  return stripView(cells, target, half);
}

/** Bezpieczna edycja do animacji: nigdy nie rzuca błędu. */
async function safeEdit(interaction, payload) {
  try {
    await interaction.editReply(payload);
    return true;
  } catch (err) {
    console.error('Błąd edycji animacji:', err.message);
    return false;
  }
}

module.exports = { stripView, buildStrip, spinStrip, safeEdit, SPIN_STEPS };
