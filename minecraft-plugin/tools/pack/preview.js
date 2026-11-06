'use strict';
// Podglądy do oceny „na oko”: tło okna położone na ciemnej scenie z podstawą (generic_54) i ikonami w slotach.
const { Img } = require('./img');
const { drawBase, slots, W, H, EXTRA } = require('./gui');

/** Składa wygląd okna tak, jak zobaczy je gracz: scena, podstawa vanilla (nadpisana), potem tło z oknami na przedmioty. */
function composeWindow(bg, items = {}) {
  const scene = new Img(W + 16, H + 16);
  scene.rect(0, 0, scene.w, scene.h, '#161a20');
  const ox = 8;
  const oy = 8;
  const base = drawBase();
  // podstawa: górna część 125 px i dolna 96 px od v=126
  for (let y = 0; y < 125; y++) for (let x = 0; x < 176; x++) scene.px(ox + x, oy + EXTRA + y, base.get(x, y));
  for (let y = 0; y < 96; y++) for (let x = 0; x < 176; x++) scene.px(ox + x, oy + EXTRA + 125 + y, base.get(x, 126 + y));
  // przedmioty w slotach: items[numer slotu okna] = Img 16x16 (rysowane PRZED tłem, bo tło ma okienka)
  for (const s of slots()) {
    if (s.kind !== 'top') continue;
    const it = items[s.index];
    if (it) scene.blit(it, ox + s.x, oy + s.y);
  }
  scene.blit(bg, ox, oy);
  return scene;
}

module.exports = { composeWindow };
