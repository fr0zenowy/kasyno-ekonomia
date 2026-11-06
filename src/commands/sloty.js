const { SlashCommandBuilder } = require('discord.js');
const { takeBet, settle, resultEmbed, guarded, delay } = require('../lib/game');
const { embed, sleep, COLORS, weighted } = require('../lib/util');

// w = waga na bębnie, triple = mnożnik za trzy takie same, pair = mnożnik za dwa takie same
const SYMBOLS = [
  { e: '🍒', w: 30, triple: 3, pair: 1 },
  { e: '🍋', w: 25, triple: 6, pair: 1 },
  { e: '🍇', w: 20, triple: 10, pair: 1 },
  { e: '🔔', w: 12, triple: 20, pair: 1 },
  { e: '💎', w: 8, triple: 50, pair: 1 },
  { e: '7️⃣', w: 5, triple: 100, pair: 1 },
];

// Losowy jackpot niezależny od bębnów: wpada rzadko, ale płaci dużo. Razem ze stołem wypłat zwrot to ok. 94.9% (test/rtp.js).
const JACKPOT = { chance: 1 / 1500, mult: 200, symbol: { e: '🌟' } };

const spin = () => [weighted(SYMBOLS), weighted(SYMBOLS), weighted(SYMBOLS)];

function multiplier([a, b, c]) {
  if (a.e === b.e && b.e === c.e) return { mult: a.triple, kind: 'triple' };
  if (a.e === b.e) return { mult: a.pair, kind: 'pair' };
  if (a.e === c.e) return { mult: a.pair, kind: 'pair' };
  if (b.e === c.e) return { mult: b.pair, kind: 'pair' };
  return { mult: 0, kind: 'none' };
}

// Bęben, który jeszcze się kręci, pokazuje 🌀.
const show = (reels) => `**[ ${[0, 1, 2].map((k) => reels[k]?.e ?? '🌀').join(' | ')} ]**`;

module.exports = {
  SYMBOLS,
  JACKPOT,
  multiplier,

  data: new SlashCommandBuilder()
    .setName('sloty')
    .setDescription('Jednoręki bandyta! Trzy 7️⃣ to x100, a czasem pada jackpot x200')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true)),

  async execute(interaction) {
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      // Wynik jest już wylosowany, a bębny zatrzymują się po kolei. Rozliczenie dopiero po animacji,
      // więc awaria w trakcie oddaje stawkę (guarded).
      const isJackpot = Math.random() < JACKPOT.chance;
      const reels = isJackpot ? [JACKPOT.symbol, JACKPOT.symbol, JACKPOT.symbol] : spin();
      const { mult, kind } = isJackpot ? { mult: JACKPOT.mult, kind: 'jackpot' } : multiplier(reels);
      const info = { game: 'sloty', reels: reels.map((r) => r.e), kind, mult, bet };
      interaction.meta?.(info);
      const frame = (stopped, text) => embed({ title: '🎰 Sloty', color: COLORS.push, description: `${show(reels.slice(0, stopped))}\n\n${text}` });

      await interaction.reply({ embeds: [frame(0, '🌀 Bębny ruszają...')] });
      await sleep(delay(800));
      await interaction.editReply({ embeds: [frame(1, 'Pierwszy bęben stanął!')] });
      await sleep(delay(800));
      const twoAlike = reels[0].e === reels[1].e;
      await interaction.editReply({ embeds: [frame(2, twoAlike ? '😮 **Dwa takie same!** Jeszcze jeden bęben...' : 'Drugi bęben stanął...')] });
      await sleep(delay(twoAlike ? 1600 : 900));

      const res = settle(userId, bet, Math.floor(bet * mult));
      interaction.meta?.({ ...info, net: res.net });
      const text =
        kind === 'jackpot'
          ? `🌟🌟🌟 **JACKPOT!!!** Mnożnik **x${mult}**`
          : kind === 'triple'
            ? `🔥 **TRZY W RZĘDZIE!** Mnożnik **x${mult}**`
            : kind === 'pair'
              ? `Dwa takie same – stawka wraca (x${mult})`
              : 'Nic z tego...';
      await interaction.editReply({ embeds: [resultEmbed({ title: '🎰 Sloty', description: `${show(reels)}\n\n${text}`, bet, res, userId })] });
    });
  },
};
