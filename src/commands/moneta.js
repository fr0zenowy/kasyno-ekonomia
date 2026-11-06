const { SlashCommandBuilder } = require('discord.js');
const { takeBet, settle, resultEmbed, guarded, delay } = require('../lib/game');
const { embed, sleep, COLORS } = require('../lib/util');

const NAMES = { orzel: '🦅 Orzeł', reszka: '🪙 Reszka' };
const PAYOUT = 1.95;

module.exports = {
  PAYOUT,

  data: new SlashCommandBuilder()
    .setName('moneta')
    .setDescription('Rzut monetą: orzeł czy reszka? Wygrana x1.95')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true))
    .addStringOption((o) =>
      o
        .setName('strona')
        .setDescription('Na co stawiasz')
        .setRequired(true)
        .addChoices({ name: '🦅 Orzeł', value: 'orzel' }, { name: '🪙 Reszka', value: 'reszka' }),
    ),

  async execute(interaction) {
    const side = interaction.options.getString('strona');
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      // Wynik jest znany z góry (animacja to tylko pokaz), więc strona może od razu zacząć własną animację rzutu.
      const result = Math.random() < 0.5 ? 'orzel' : 'reszka';
      const info = { game: 'moneta', side, result, bet, mult: PAYOUT };
      interaction.meta?.(info);
      const frame = (face) =>
        embed({ title: '🪙 Rzut monetą', color: COLORS.push, description: `Stawiasz na **${NAMES[side]}**\n\n# ${face}\nMoneta wiruje w powietrzu...` });

      await interaction.reply({ embeds: [frame('🌀')] });
      await sleep(delay(700));
      await interaction.editReply({ embeds: [frame('🦅')] });
      await sleep(delay(600));
      await interaction.editReply({ embeds: [frame('🪙')] });
      await sleep(delay(600));
      await interaction.editReply({ embeds: [frame('🦅')] });
      await sleep(delay(700));
      const res = settle(userId, bet, result === side ? Math.floor(bet * PAYOUT) : 0);
      interaction.meta?.({ ...info, net: res.net, won: result === side });
      await interaction.editReply({
        embeds: [resultEmbed({ title: '🪙 Rzut monetą', description: `Stawiasz na **${NAMES[side]}**\n\n# ${result === 'orzel' ? '🦅' : '🪙'}\nWypadło: **${NAMES[result]}**`, bet, res, userId })],
      });
    });
  },
};
