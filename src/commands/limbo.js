const { SlashCommandBuilder } = require('discord.js');
const { takeBet, settle, resultEmbed, guarded, delay, fail } = require('../lib/game');
const { embed, sleep, COLORS } = require('../lib/util');

const HOUSE = 0.96; // P(wynik ≥ x) = 0.96 / x, więc każdy cel daje średnio 96% zwrotu
const MAX_RESULT = 1_000_000;
const MIN_TARGET = 1.01;
const MAX_TARGET = 1000;

/** Losuje wynik rundy: ok. 4% rund kończy się na x1.00, a duże wyniki są rzadkie (x10 ≈ 9.6%, x100 ≈ 0.96%, x1000 ≈ 0.096%). */
function roll(u = Math.random()) {
  return Math.min(MAX_RESULT, Math.max(1, Math.floor((100 * HOUSE) / (1 - u)) / 100));
}

module.exports = {
  roll,
  MIN_TARGET,
  MAX_TARGET,

  data: new SlashCommandBuilder()
    .setName('limbo')
    .setDescription('Limbo: wybierz cel (np. x2, x10, x100). Jeśli wynik go dobije, wygrywasz cel × stawka')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true))
    .addNumberOption((o) =>
      o.setName('cel').setDescription(`Docelowy mnożnik od ${MIN_TARGET} do ${MAX_TARGET} (np. 2, 10, 100)`).setRequired(true).setMinValue(MIN_TARGET).setMaxValue(MAX_TARGET),
    ),

  async execute(interaction) {
    const target = Math.round(interaction.options.getNumber('cel') * 100) / 100;
    if (!(target >= MIN_TARGET && target <= MAX_TARGET)) return fail(interaction, `❌ Cel musi być między x${MIN_TARGET} a x${MAX_TARGET}.`);
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      const result = roll();
      const chance = (HOUSE / target) * 100;
      const header = `Cel: **x${target.toFixed(2)}** • szansa na wygraną: **${chance < 1 ? chance.toFixed(3) : chance.toFixed(2)}%**`;
      const frame = (text) => embed({ title: '🚀 Limbo', color: COLORS.push, description: `${header}\n\n${text}` });
      const info = { game: 'limbo', result, target, chance, bet };
      interaction.meta?.(info);

      await interaction.reply({ embeds: [frame('# 🚀 x1.00\nStartujemy...')] });
      // Licznik „wspina się” do wyniku: kilka klatek po drodze.
      for (const part of [0.25, 0.55, 0.85]) {
        await sleep(delay(700));
        const shown = Math.max(1, Math.floor((1 + (result - 1) * part) * 100) / 100);
        await interaction.editReply({ embeds: [frame(`# 🚀 x${shown.toFixed(2)}\nLecimy...`)] });
      }
      await sleep(delay(800));

      const won = result >= target;
      const res = settle(userId, bet, won ? Math.floor(bet * target) : 0);
      interaction.meta?.({ ...info, won, net: res.net });
      await interaction.editReply({
        embeds: [
          resultEmbed({
            title: '🚀 Limbo',
            description: `${header}\n\n# ${won ? '✅' : '❌'} x${result.toFixed(2)}\n${won ? 'Wynik dobił do celu!' : 'Rakieta nie doleciała do celu.'}`,
            bet,
            res,
            userId,
          }),
        ],
      });
    });
  },
};
