'use strict';
// Generuje paczkę zasobów kasyna: tła okien (czcionka z negatywną spacją), ikony przedmiotów, podstawę okna i indeks dla pluginu.
// Uruchom: node minecraft-plugin/tools/gen-pack.js   (nie wymaga żadnych pakietów)
//
// Wyniki:
//   minecraft-plugin/resourcepack/                      rozpakowana paczka do podglądu i ręcznych zmian
//   minecraft-plugin/src/main/resources/pack/           KasynoUI.zip i pack-index.json, wchodzą do pluginu
//   minecraft-plugin/dist/KasynoUI.zip                  kopia do wgrania na własny hosting
//   minecraft-plugin/dist/preview/                      podglądy (ikony, przykładowe okna)
const fs = require('fs');
const path = require('path');
const { encodePng, writeZip } = require('./pack/png');
const { Img } = require('./pack/img');
const { drawGui, drawBase, SCREENS, TINTS, W, H, EXTRA } = require('./pack/gui');
const icons = require('./pack/icons');
const { composeWindow } = require('./pack/preview');
const { drawText5 } = require('./pack/font');
const cards3d = require('./pack/cards3d');
const balls = require('./pack/balls');
const { render: renderModel } = require('./pack/model3d');

const root = path.join(__dirname, '..');
const NS = 'kasyno';
const FIRST_GLYPH = 0xe000;
const SHIFT_CHAR = '';

const files = []; // { name, data }
const add = (name, data) => files.push({ name, data: Buffer.isBuffer(data) ? data : Buffer.from(data, 'utf8') });
const png = (img) => encodePng(img.w, img.h, img.data);
const json = (obj) => JSON.stringify(obj, null, 2).replace(/[\u0080-￿]/g, (c) => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0')) + '\n';

// ---------- pack.mcmeta i ikona paczki ----------
add('pack.mcmeta', json({ pack: { description: 'Kasyno i Ekonomia: własne okna i ikony', min_format: 75, max_format: 75 } }));
const logo = new Img(64, 64);
logo.rect(0, 0, 64, 64, '#7a1420');
logo.frame(0, 0, 64, 64, '#e8b230', 3);
logo.blit(icons.render('kasyno').scaled(3), 8, 8);
add('pack.png', png(logo));

// ---------- tła okien ----------
const providers = [{ type: 'space', advances: { [SHIFT_CHAR]: -8 } }];
const backgrounds = {};
let code = FIRST_GLYPH;
const screenIds = Object.keys(SCREENS);
// każde okno ma mieć własny napis na wstędze: dwa tła o tym samym tytule to dwa okna podpisane tak samo
const byTitle = {};
for (const id of screenIds) (byTitle[SCREENS[id][0]] = byTitle[SCREENS[id][0]] || []).push(id);
for (const [title, ids] of Object.entries(byTitle)) if (ids.length > 1) throw new Error(`Ten sam tytuł "${title}" mają tła: ${ids.join(', ')}`);
for (const id of screenIds) {
  const [title, theme] = SCREENS[id];
  const glyph = String.fromCharCode(code++);
  backgrounds[id] = glyph;
  add(`assets/${NS}/textures/font/bg_${id}.png`, png(drawGui(id, title, theme, TINTS[id])));
  // ascent 13 + EXTRA: lewy górny róg grafiki trafia nad lewy górny róg okna o EXTRA pikseli (tytuł zaczyna się 6 px od góry)
  providers.push({ type: 'bitmap', file: `${NS}:font/bg_${id}.png`, ascent: 13 + EXTRA, height: H, chars: [glyph] });
}
add(`assets/${NS}/font/gui.json`, json({ providers }));

// ---------- podstawa okna (zastępuje vanilla generic_54) ----------
add('assets/minecraft/textures/gui/container/generic_54.png', png(drawBase()));

// ---------- ikony przedmiotów ----------
const iconIds = icons.names();
for (const id of iconIds) {
  add(`assets/${NS}/textures/item/${id}.png`, png(icons.render(id)));
  add(`assets/${NS}/models/item/${id}.json`, json({ parent: 'minecraft:item/generated', textures: { layer0: `${NS}:item/${id}` } }));
  const flat = { type: 'minecraft:model', model: `${NS}:item/${id}` };
  // karty: w oknie płaska ikona, w ręku, na ziemi, w ramce i na głowie model 3D
  const item = id.startsWith('card_')
    ? { type: 'minecraft:select', property: 'minecraft:display_context', cases: [{ when: 'gui', model: flat }], fallback: { type: 'minecraft:model', model: `${NS}:card/${cards3d.key(id)}` } }
    : flat;
  add(`assets/${NS}/items/${id}.json`, json({ model: item }));
}

// ---------- modele 3D kart ----------
add(`assets/${NS}/textures/item/card/edge.png`, png(cards3d.edge()));
for (const id of cards3d.names()) {
  add(`assets/${NS}/textures/item/card/${cards3d.key(id)}.png`, png(cards3d.render3d(id)));
  add(`assets/${NS}/models/card/${cards3d.key(id)}.json`, json(cards3d.model(id, NS)));
}

// ---------- modele 3D piłek (plugin Sport: pilka.model-koszykowka / pilka.model-siatkowka) ----------
for (const n of balls.names()) {
  add(`assets/${NS}/textures/item/ball/${balls.key(n)}.png`, png(balls.texture(n)));
  add(`assets/${NS}/models/ball/${balls.key(n)}.json`, json(balls.model(n, NS)));
  add(`assets/${NS}/items/ball_${balls.key(n)}.json`, json(balls.itemDefinition(n, NS)));
}

// ---------- indeks dla pluginu ----------
const index = { font: `${NS}:gui`, namespace: NS, shift: SHIFT_CHAR, backgrounds, icons: iconIds };

// ---------- zapis ----------
function write(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
}
const unpacked = path.join(root, 'resourcepack');
fs.rmSync(unpacked, { recursive: true, force: true });
for (const f of files) write(path.join(unpacked, f.name), f.data);
const zip = writeZip(files);
write(path.join(root, 'src/main/resources/pack/KasynoUI.zip'), zip);
write(path.join(root, 'src/main/resources/pack/pack-index.json'), json(index));
write(path.join(root, 'dist/KasynoUI.zip'), zip);

// ---------- podglądy ----------
const prev = path.join(root, 'dist/preview');
const ic = (id) => icons.render(id);

function sheetOf(ids, cols, scale, labels) {
  const cell = 16 * scale + 14;
  const rows = Math.ceil(ids.length / cols);
  const sheet = new Img(cols * cell + 10, rows * (cell + (labels ? 12 : 0)) + 10);
  sheet.rect(0, 0, sheet.w, sheet.h, '#2a3140');
  ids.forEach((id, i) => {
    const x = 10 + (i % cols) * cell;
    const y = 10 + Math.floor(i / cols) * (cell + (labels ? 12 : 0));
    sheet.rect(x - 2, y - 2, 16 * scale + 4, 16 * scale + 4, '#3e4658');
    sheet.blit(ic(id).scaled(scale), x, y);
    if (labels) drawText5(sheet, x, y + 16 * scale + 5, id.toUpperCase().slice(0, 12), { color: '#c8d0e0' });
  });
  return sheet;
}
const isCard = (id) => id.startsWith('card_');
write(path.join(prev, 'ikony.png'), png(sheetOf(iconIds.filter((id) => !isCard(id)), 10, 6, true)));

function mock(id, placement) {
  const [title, theme] = SCREENS[id];
  const items = {};
  for (const [slot, icon] of Object.entries(placement)) items[slot] = ic(icon);
  return composeWindow(drawGui(id, title, theme, TINTS[id]), items).scaled(3);
}
const mocks = [
  mock('menu', { 10: 'kasyno', 12: 'm_zarobki', 14: 'm_sklep', 16: 'm_bank', 28: 'm_ekwipunek', 30: 'm_biznes', 32: 'm_skrzynie', 34: 'hall_pokoj', 45: 'm_ranking', 49: 'close', 53: 'm_pomoc' }),
  mock('kasyno', { 19: 'hall_automaty', 21: 'hall_karty', 23: 'hall_ryzyko', 25: 'hall_live', 31: 'hall_pokoj', 49: 'back' }),
  mock('napad', { 4: 'h_siren', 0: 'players', 9: 'players', 18: 'h_cuffs', 27: 'players', 8: 'players', 17: 'players', 3: 'h_vault', 5: 'h_loot', 19: 'h_camera', 25: 'h_camera', 22: 'players', 31: 'h_door', 39: 'h_car', 13: 'h_guard', 47: 'h_loot', 48: 'h_loot', 49: 'h_loot', 46: 'info', 45: 'back' }),
  mock('crash', { 4: 'crash', 45: 'back', 46: 'bet_min', 47: 'bet_half', 48: 'bet_stake', 49: 'play', 50: 'bet_double', 51: 'bet_max', 52: 'players' }),
];
const gap = 8;
const mw = mocks.reduce((a, m) => a + m.w + gap, gap);
const mh = Math.max(...mocks.map((m) => m.h)) + gap * 2;
const sheet = new Img(mw, mh);
sheet.rect(0, 0, mw, mh, '#0c0e12');
let mx = gap;
for (const m of mocks) {
  sheet.blit(m, mx, gap);
  mx += m.w + gap;
}
write(path.join(prev, 'okna.png'), png(sheet));

// podgląd kart: cała talia (rząd na kolor, rewers na końcu) i okna blackjacka oraz hi-lo z kartami w slotach
const deck = iconIds.filter(isCard);
const deckSheet = sheetOf(deck.filter((id) => id !== 'card_back').concat('card_back'), 13, 5, false);
const tables = [
  mock('blackjack', { 1: 'players', 10: 'card_back', 11: 'card_h_k', 12: 'card_s_7', 28: 'card_s_a', 29: 'card_d_10', 30: 'card_c_j', 31: 'card_h_5', 45: 'back', 49: 'play' }),
  mock('hilo', { 22: 'card_d_q', 10: 'card_c_2', 11: 'card_h_9', 12: 'card_s_a', 13: 'card_d_10', 45: 'back', 49: 'cashout' }),
];
const cardsW = Math.max(deckSheet.w, tables.reduce((a, m) => a + m.w + gap, gap));
const cardsSheet = new Img(cardsW, deckSheet.h + Math.max(...tables.map((m) => m.h)) + gap * 2);
cardsSheet.rect(0, 0, cardsSheet.w, cardsSheet.h, '#0c0e12');
cardsSheet.blit(deckSheet, 0, 0);
let tx = gap;
for (const m of tables) {
  cardsSheet.blit(m, tx, deckSheet.h + gap);
  tx += m.w + gap;
}
write(path.join(prev, 'karty.png'), png(cardsSheet));

// podgląd tytułów: wstęga każdego okna (górne 34 px tła), żeby zobaczyć, że żadne dwa okna nie są podpisane tak samo
const ribbonH = 34;
const ribbonCols = 3;
const ribbonCell = [W * 2 + 12, ribbonH * 2 + 22];
const ribbons = new Img(ribbonCols * ribbonCell[0] + 12, Math.ceil(screenIds.length / ribbonCols) * ribbonCell[1] + 12);
ribbons.rect(0, 0, ribbons.w, ribbons.h, '#0c0e12');
screenIds.forEach((id, i) => {
  const [title, theme] = SCREENS[id];
  const bg = drawGui(id, title, theme, TINTS[id]);
  const top = new Img(W, ribbonH);
  for (let y = 0; y < ribbonH; y++) for (let x = 0; x < W; x++) top.px(x, y, bg.get(x, y));
  const x0 = 12 + (i % ribbonCols) * ribbonCell[0];
  const y0 = 12 + Math.floor(i / ribbonCols) * ribbonCell[1];
  ribbons.blit(top.scaled(2), x0, y0);
  drawText5(ribbons, x0, y0 + ribbonH * 2 + 4, id.toUpperCase(), { color: '#8a94a8' });
});
write(path.join(prev, 'tytuly.png'), png(ribbons));

// podgląd tekstur modeli 3D (awers i rewers każdej karty, skala 2)
const faces3d = cards3d.names().filter((id) => id !== 'card_back').concat('card_back');
const cell3d = [cards3d.TW * 2 + 8, cards3d.TH * 2 + 8];
const sheet3d = new Img(13 * cell3d[0] + 8, Math.ceil(faces3d.length / 13) * cell3d[1] + 8);
sheet3d.rect(0, 0, sheet3d.w, sheet3d.h, '#2a3140');
faces3d.forEach((id, i) => sheet3d.blit(cards3d.render3d(id).scaled(2), 8 + (i % 13) * cell3d[0], 8 + Math.floor(i / 13) * cell3d[1]));
write(path.join(prev, 'karty3d.png'), png(sheet3d));

// podgląd piłek: tekstura (mapa kuli) i model pod trzema kątami
const ballViews = [[0, 12], [45, 12], [20, 62], [200, -35]];
const ballRows = balls.names().map((n) => {
  const model = balls.model(n, NS);
  const tex = { [`${n}`]: balls.texture(n) };
  return { tex: tex[n], views: ballViews.map(([yaw, pitch]) => renderModel(model, tex, 160, yaw, pitch)) };
});
const ballSheet = new Img(ballViews.length * 168 + 8 + balls.TW * 3 + 16, ballRows.length * 168 + 8);
ballSheet.rect(0, 0, ballSheet.w, ballSheet.h, '#3a4256');
ballRows.forEach((row, r) => {
  row.views.forEach((v, i) => ballSheet.blit(v, 8 + i * 168, 8 + r * 168));
  ballSheet.blit(row.tex.scaled(3), 8 + ballViews.length * 168 + 8, 8 + r * 168);
});
write(path.join(prev, 'pilki.png'), png(ballSheet));

console.log(`Paczka: ${files.length} plików, ${screenIds.length} teł, ${iconIds.length} ikon, ZIP ${(zip.length / 1024).toFixed(1)} KB`);
