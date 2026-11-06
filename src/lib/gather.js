const db = require('./db');
const { ITEMS } = require('./data');
const { label, sellValue } = require('./items');
const { guardCooldown, fail, delay } = require('./game');
const { safeEdit } = require('./anim');
const { lootMultiplier, luckMultiplier, breakChance } = require('./boosts');
const { embed, COLORS, weighted, randInt, chance, fmt, sleep } = require('./util');

/**
 * Wspólna logika dla /ryby, /kopalnia, /polowanie, /las i /smietnik.
 * cfg: { key, cooldown, title, emoji, frames, tools: [{ id, luck, miss? }], noTool, table, missChance?, missText?, found }
 * `tools` od najlepszego do najgorszego – używane jest najlepsze, jakie gracz ma. Wpis z `id: null` to wariant darmowy
 * (bez narzędzia): ostatni na liście, więc jest używany, gdy gracz nie ma żadnego lepszego. Narzędzie z `id` może się zepsuć.
 * `frames` to krótkie opisy kolejnych „klatek” animacji przed wynikiem.
 */
async function gather(interaction, cfg) {
  const userId = interaction.user.id;
  const tool = cfg.tools.find((t) => t.id === null || db.itemQty(userId, t.id) > 0);
  if (!tool) return fail(interaction, cfg.noTool);
  if (!(await guardCooldown(interaction, cfg.key, cfg.cooldown))) return;

  const frame = (text) => embed({ title: `${cfg.emoji} ${cfg.title}`, color: COLORS.info, description: text });

  // Animacja idzie przed losowaniem łupu: jeśli Discord odmówi odpowiedzi, nic nie zostaje przyznane.
  await interaction.reply({ embeds: [frame(cfg.frames[0])] });
  for (const text of cfg.frames.slice(1)) {
    await sleep(delay(1200));
    await safeEdit(interaction, { embeds: [frame(text)] });
  }
  await sleep(delay(1200));

  const lines = [];
  let color = COLORS.win;

  const missChance = typeof cfg.missChance === 'function' ? cfg.missChance(tool) : cfg.missChance ?? 0;
  if (chance(missChance)) {
    color = COLORS.lose;
    lines.push(cfg.missText);
  } else {
    const drop = weighted(cfg.table, tool.luck * luckMultiplier(userId));
    const [lo, hi] = drop.qty ?? [1, 1];
    const magnet = lootMultiplier(userId);
    const qty = randInt(lo, hi) * magnet;
    db.addItem(userId, drop.id, qty);
    const it = ITEMS[drop.id];
    const worth = it.type === 'loot' ? ` (warte ok. ${fmt(sellValue(drop.id) * qty)})` : '';
    lines.push(`${cfg.found(drop.id)} Zdobywasz **${qty}× ${label(drop.id)}**${worth}.`);
    if (magnet > 1) lines.push(`🧲 Magnes na łup: ×${magnet}`);
    if (it.type === 'crate') lines.push('🎁 To skrzynia! Otwórz ją: `/skrzynie otworz`.');
    if (it.type === 'crate' || (it.type === 'loot' && it.sell >= 900)) color = COLORS.gold;
  }

  if (tool.id && chance(breakChance(userId))) {
    db.takeItem(userId, tool.id, 1);
    lines.push(`💥 Twoje ${label(tool.id)} się zepsuło! Kup nowe w \`/sklep\`.`);
  }

  const lv = db.addXp(userId, randInt(6, 14));
  await safeEdit(interaction, {
    embeds: [
      embed({
        title: `${cfg.emoji} ${cfg.title}`,
        color,
        description: lines.join('\n') + lv.note,
        footer: 'Sprzedaj łup: /sprzedaj wszystko',
      }),
    ],
  });
}

module.exports = { gather };
