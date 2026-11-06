const db = require('./db');
const { ITEMS } = require('./data');
const { label, sellValue } = require('./items');
const { fail, delay } = require('./game');
const { buildStrip, spinStrip, stripView, safeEdit } = require('./anim');
const { fmt, num, embed, COLORS, randInt, weighted, chance, sleep } = require('./util');

const MAX_OPEN = 10;

const RARITY = {
  common: { emoji: '⚪', name: 'Zwykły', color: 0x95a5a6, rank: 0 },
  uncommon: { emoji: '🟢', name: 'Niezwykły', color: 0x2ecc71, rank: 1 },
  rare: { emoji: '🔵', name: 'Rzadki', color: 0x3498db, rank: 2 },
  epic: { emoji: '🟣', name: 'Epicki', color: 0x9b59b6, rank: 3 },
  legendary: { emoji: '🟡', name: 'Legendarny', color: 0xf1c40f, rank: 4 },
};

// Konstruktory wpisów: w = waga, r = rzadkość
const money = (min, max, w, r) => ({ kind: 'money', min, max, w, r });
const xp = (min, max, w, r) => ({ kind: 'xp', min, max, w, r });
const item = (id, qty, w, r) => ({ kind: 'item', item: id, qty, w, r });

// Tabele łupów. Wagi każdej skrzyni sumują się do 100, więc w to po prostu procent szansy.
// Zwrot (wartość/cena) liczy `expectedValue` i pokazuje /skrzynie lista oraz test/rtp.js.
const CRATES = {
  skrzynka: {
    loot: [
      money(250, 550, 35, 'common'),
      xp(50, 100, 10, 'common'),
      money(700, 1200, 20, 'uncommon'),
      item('klodka', 1, 6, 'uncommon'),
      item('maska', 1, 6, 'uncommon'),
      money(1300, 2400, 12, 'rare'),
      item('koniczyna', 1, 5, 'rare'),
      money(3000, 5500, 4, 'epic'),
      item('skrzynia', 1, 1.7, 'epic'),
      money(10000, 15000, 0.3, 'legendary'),
    ],
  },
  skrzynia: {
    loot: [
      money(600, 1300, 34, 'common'),
      xp(100, 200, 8, 'common'),
      money(1700, 3000, 22, 'uncommon'),
      item('klodka', 2, 5, 'uncommon'),
      item('maska', 2, 5, 'uncommon'),
      money(3500, 6500, 14, 'rare'),
      item('koniczyna', 2, 4, 'rare'),
      money(9000, 16000, 6, 'epic'),
      item('skrzynia_diamentowa', 1, 1.5, 'epic'),
      money(35000, 60000, 0.5, 'legendary'),
    ],
  },
  skrzynia_diamentowa: {
    loot: [
      money(3500, 7500, 36, 'common'),
      xp(300, 500, 8, 'common'),
      money(9000, 15000, 22, 'uncommon'),
      item('sejf', 1, 4, 'uncommon'),
      item('koniczyna', 3, 4, 'uncommon'),
      money(16000, 30000, 16, 'rare'),
      item('zloty_kilof', 1, 3, 'rare'),
      money(45000, 80000, 5.5, 'epic'),
      item('skrzynia_legendarna', 1, 1, 'epic'),
      money(130000, 220000, 0.5, 'legendary'),
    ],
  },
  // Skrzynia zbieracza: worki ryb, rud i zwierzyny. Wszystko od razu do sprzedania (`/sprzedaj wszystko`).
  skrzynia_zbieracza: {
    loot: [
      item('sledz', 36, 14, 'common'),
      item('karp', 27, 14, 'common'),
      item('krolik', 27, 12, 'common'),
      item('szczupak', 36, 12, 'uncommon'),
      item('zelazo', 60, 10, 'uncommon'),
      item('kurki', 60, 10, 'uncommon'),
      item('sum', 27, 8, 'rare'),
      item('diament', 15, 6, 'rare'),
      item('jelen', 27, 6, 'rare'),
      item('tunczyk', 40, 3, 'epic'),
      item('rubin', 20, 2.5, 'epic'),
      item('niedzwiedz', 20, 1.5, 'epic'),
      item('rekin', 20, 0.5, 'legendary'),
      item('meteoryt', 12, 0.3, 'legendary'),
      item('skrzynia_diamentowa', 1, 0.2, 'legendary'),
    ],
  },
  // Skrzynia hazardzisty: gotówka, koniczyny, polisy i tarcze do gier.
  skrzynia_hazardzisty: {
    loot: [
      money(2000, 4500, 30.4, 'common'),
      item('polisa_mala', 1, 12, 'common'),
      item('koniczyna', 2, 8, 'common'),
      money(4000, 9000, 20, 'uncommon'),
      item('koniczyna', 4, 6, 'uncommon'),
      item('tarcza_mala', 1, 4, 'uncommon'),
      money(10000, 20000, 8, 'rare'),
      item('polisa_srednia', 1, 4, 'rare'),
      item('eliksir_szczescia', 1, 2, 'rare'),
      money(25000, 50000, 3, 'epic'),
      item('tarcza_srednia', 1, 1, 'epic'),
      item('polisa_duza', 1, 0.3, 'epic'),
      money(80000, 160000, 1.1, 'legendary'),
      item('tarcza_duza', 1, 0.15, 'legendary'),
      item('polisa_platynowa', 1, 0.05, 'legendary'),
    ],
  },
  skrzynia_legendarna: {
    loot: [
      money(28000, 55000, 34, 'common'),
      xp(1000, 2000, 8, 'common'),
      money(60000, 100000, 22, 'uncommon'),
      item('koniczyna', 5, 4, 'uncommon'),
      money(110000, 190000, 16, 'rare'),
      item('sejf', 3, 4, 'rare'),
      money(250000, 400000, 8, 'epic'),
      item('karabin', 1, 2.9, 'epic'),
      item('trofeum', 1, 0.5, 'legendary'),
      money(700000, 1100000, 0.6, 'legendary'),
    ],
  },
};

const isCrate = (id) => Object.prototype.hasOwnProperty.call(CRATES, id);

/** Średnia wartość jednego otwarcia w monetach (przedmioty po cenie skupu, skrzynie w skrzyniach po ich własnej wartości). */
function expectedValue(crateId) {
  const total = CRATES[crateId].loot.reduce((s, e) => s + e.w, 0);
  let ev = 0;
  for (const e of CRATES[crateId].loot) {
    let value;
    if (e.kind === 'money' || e.kind === 'xp') value = (e.min + e.max) / 2;
    else if (isCrate(e.item)) value = expectedValue(e.item) * e.qty;
    else value = sellValue(e.item) * e.qty;
    ev += (e.w / total) * value;
  }
  return ev;
}

/** Szansa (w %) na każdą rzadkość w danej skrzyni. */
function rarityOdds(crateId) {
  const total = CRATES[crateId].loot.reduce((s, e) => s + e.w, 0);
  const odds = {};
  for (const e of CRATES[crateId].loot) odds[e.r] = (odds[e.r] ?? 0) + (e.w / total) * 100;
  return odds;
}

/** Losuje nagrodę z danej skrzyni. */
function roll(crateId) {
  const e = weighted(CRATES[crateId].loot);
  if (e.kind === 'item') return { kind: 'item', rarity: e.r, item: e.item, qty: e.qty };
  const step = e.max >= 100000 ? 1000 : e.max >= 10000 ? 100 : e.max >= 1000 ? 10 : 1;
  const amount = Math.round(randInt(e.min, e.max) / step) * step;
  return { kind: e.kind, rarity: e.r, amount };
}

/** Wręcza nagrodę. Zwraca opis i ewentualną notkę o awansie. */
function applyReward(userId, r) {
  if (r.kind === 'money') {
    db.addWallet(userId, r.amount);
    return { text: `💰 **${fmt(r.amount)}**`, note: '' };
  }
  if (r.kind === 'xp') {
    const lv = db.addXp(userId, r.amount, { boost: false }); // XP ze skrzyni jest już wycenione w tabeli łupów
    return { text: `⭐ **+${num(r.amount)} XP**`, note: lv.note };
  }
  db.addItem(userId, r.item, r.qty);
  return { text: `${label(r.item)} ×**${r.qty}**`, note: '' };
}

/** Losowa komórka paska (kolor rzadkości) – wagi takie jak w skrzyni, więc pasek wygląda wiarygodnie. */
function randomCellFor(crateId) {
  const odds = rarityOdds(crateId);
  const pool = Object.entries(odds).map(([r, w]) => ({ r, w }));
  return () => RARITY[weighted(pool).r].emoji;
}

function teaseCells(cells, target, winnerRarity) {
  // „Prawie!”: obok wygranej często ląduje coś lepszego, żeby było emocjonująco.
  if (winnerRarity === 'legendary') return;
  if (chance(0.5)) cells[target + (chance(0.5) ? 1 : -1)] = RARITY.legendary.emoji;
  else if (winnerRarity !== 'epic' && chance(0.5)) cells[target + (chance(0.5) ? 2 : -2)] = RARITY.epic.emoji;
}

/**
 * Otwiera `qty` skrzyń i pokazuje animację. Skrzynie zdejmowane są z ekwipunku, a nagrody wręczane
 * od razu, jeszcze przed animacją – awaria Discorda nie może gracza kosztować nagrody.
 */
async function openCrates(interaction, crateId, qty = 1) {
  const userId = interaction.user.id;
  const it = ITEMS[crateId];
  qty = Math.max(1, Math.min(MAX_OPEN, qty));

  const have = db.itemQty(userId, crateId);
  if (have < 1) return fail(interaction, `❌ Nie masz ${label(crateId)}. Kup ją w \`/sklep\` albo zdobądź w grze.`);
  if (have < qty) return fail(interaction, `❌ Masz tylko ${have}× ${label(crateId)}.`);

  const title = `${it.emoji} ${it.name}`;
  const rewards = Array.from({ length: qty }, () => roll(crateId));
  // Dane strukturalne dla strony: co wypadło (nagrody są już wylosowane, animacja to pokaz).
  interaction.meta?.({ game: 'crate', crate: crateId, qty, rewards });

  if (qty === 1) {
    const r = rewards[0];
    const target = 24;
    const cells = buildStrip(RARITY[r.rarity].emoji, randomCellFor(crateId), target);
    teaseCells(cells, target, r.rarity);

    await interaction.reply({ embeds: [embed({ title, color: COLORS.info, description: `${stripView(cells, 3)}\n\n🔓 *Otwieranie skrzyni...*` })] });
    if (!db.takeItem(userId, crateId, 1)) {
      await safeEdit(interaction, { embeds: [embed({ title, color: COLORS.lose, description: '❌ Ta skrzynia właśnie zniknęła z ekwipunku.' })] });
      return;
    }
    const applied = applyReward(userId, r);

    const hints = ['🔓 *Otwieranie skrzyni...*', '🌀 *Losowanie...*', '🌀 *Losowanie...*', '👀 *Zwalnia...*', '👀 *Zwalnia...*', '😮 *Jeszcze chwila...*', '😮 *Jeszcze chwila...*', '🥁 *Prawie...*'];
    const finalView = await spinStrip(cells, target, (view, k) =>
      interaction.editReply({ embeds: [embed({ title, color: COLORS.info, description: `${view}\n\n${hints[k] ?? ''}` })] }),
    );

    const rar = RARITY[r.rarity];
    const hype = r.rarity === 'legendary' ? '✨🎉 **LEGENDARNY DROP!** 🎉✨\n\n' : r.rarity === 'epic' ? '🎊 **Epicki drop!**\n\n' : '';
    const left = db.itemQty(userId, crateId);
    const crateHint = ITEMS[r.item]?.type === 'crate' ? '\n*Wypadła kolejna skrzynia! Otwórz ją: `/skrzynie otworz`*' : '';
    await safeEdit(interaction, {
      embeds: [
        embed({
          title: `${it.emoji} ${rar.emoji} ${rar.name} drop!`,
          color: rar.color,
          description: `${finalView}\n\n${hype}Wypadło: ${applied.text}${crateHint}${applied.note}`,
          footer: `Portfel: ${fmt(db.getUser(userId).wallet)}${left ? ` • Zostało skrzyń: ${left}` : ''}`,
        }),
      ],
    });
    return;
  }

  // Wiele skrzyń naraz: krótka animacja i zbiorcze podsumowanie.
  await interaction.reply({ embeds: [embed({ title, color: COLORS.info, description: `${it.emoji.repeat(Math.min(qty, 10))}\n\n🔓 *Otwieram ${qty} skrzyń naraz...*` })] });
  if (!db.takeItem(userId, crateId, qty)) {
    await safeEdit(interaction, { embeds: [embed({ title, color: COLORS.lose, description: '❌ Nie masz już tylu skrzyń.' })] });
    return;
  }
  const applied = rewards.map((r) => applyReward(userId, r));
  await sleep(delay(1400));
  await safeEdit(interaction, { embeds: [embed({ title, color: COLORS.info, description: `${rewards.map((r) => RARITY[r.rarity].emoji).join(' ')}\n\n🥁 *Podliczam łupy...*` })] });
  await sleep(delay(1200));

  const best = rewards.reduce((b, r) => (RARITY[r.rarity].rank > RARITY[b.rarity].rank ? r : b), rewards[0]);
  const lines = rewards.map((r, k) => `${RARITY[r.rarity].emoji} ${applied[k].text}`);
  const totalMoney = rewards.filter((r) => r.kind === 'money').reduce((s, r) => s + r.amount, 0);
  const notes = applied.map((a) => a.note).filter(Boolean).pop() ?? '';
  const left = db.itemQty(userId, crateId);
  await safeEdit(interaction, {
    embeds: [
      embed({
        title: `${it.emoji} Otwarto ${qty}× ${it.name}`,
        color: RARITY[best.rarity].color,
        description: `${lines.join('\n')}\n\n💰 Razem gotówki: **${fmt(totalMoney)}**\n🏅 Najlepszy drop: ${RARITY[best.rarity].emoji} **${RARITY[best.rarity].name}**${notes}`,
        footer: `Portfel: ${fmt(db.getUser(userId).wallet)}${left ? ` • Zostało skrzyń: ${left}` : ''}`,
      }),
    ],
  });
}

module.exports = {
  MAX_OPEN,
  RARITY,
  CRATES,
  isCrate,
  expectedValue,
  rarityOdds,
  roll,
  applyReward,
  openCrates,
};
