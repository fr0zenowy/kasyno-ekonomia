const { SlashCommandBuilder } = require('discord.js');
const { takeBet, settle, resultEmbed, guarded, delay } = require('../lib/game');
const { embed, sleep, COLORS, randInt } = require('../lib/util');

const FACES = ['1️⃣', '2️⃣', '3️⃣', '4️⃣', '5️⃣', '6️⃣'];

// Szansa: poniżej 7 = 15/36, powyżej 7 = 15/36, dokładnie 7 = 6/36. Mnożniki dają ok. 96% zwrotu.
const BETS = {
  nizej: { label: '⬇️ Poniżej 7', mult: 2.3, wins: (s) => s < 7 },
  wyzej: { label: '⬆️ Powyżej 7', mult: 2.3, wins: (s) => s > 7 },
  siedem: { label: '🎯 Dokładnie 7', mult: 5.7, wins: (s) => s === 7 },
};

module.exports = {
  BETS,

  data: new SlashCommandBuilder()
    .setName('kosci')
    .setDescription('Rzut dwiema kośćmi: poniżej, powyżej albo dokładnie 7')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true))
    .addStringOption((o) =>
      o
        .setName('typ')
        .setDescription('Na co stawiasz')
        .setRequired(true)
        .addChoices(
          { name: '⬇️ Poniżej 7 (x2.3)', value: 'nizej' },
          { name: '⬆️ Powyżej 7 (x2.3)', value: 'wyzej' },
          { name: '🎯 Dokładnie 7 (x5.7)', value: 'siedem' },
        ),
    ),

  async execute(interaction) {
    const choice = BETS[interaction.options.getString('typ')];
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      // Rzut jest rozstrzygnięty z góry; klatki animacji i ekran na stronie tylko go odsłaniają.
      const d1 = randInt(1, 6);
      const d2 = randInt(1, 6);
      const sum = d1 + d2;
      const won = choice.wins(sum);
      const info = { game: 'kosci', choice: interaction.options.getString('typ'), label: choice.label, mult: choice.mult, d1, d2, sum, won, bet };
      interaction.meta?.(info);
      const face = (n) => FACES[n - 1];
      const roll = () => `🎲 ${face(randInt(1, 6))}   🎲 ${face(randInt(1, 6))}`;
      const frame = (text) => embed({ title: '🎲 Kości', color: COLORS.push, description: `Stawiasz: **${choice.label}**\n\n# ${text}` });

      await interaction.reply({ embeds: [frame('🎲 ❔   🎲 ❔')] });
      for (const wait of [600, 600, 800]) {
        await sleep(delay(wait));
        await interaction.editReply({ embeds: [frame(roll())] });
      }
      await sleep(delay(900));
      const res = settle(userId, bet, won ? Math.floor(bet * choice.mult) : 0);
      interaction.meta?.({ ...info, net: res.net });
      await interaction.editReply({
        embeds: [resultEmbed({ title: '🎲 Kości', description: `Stawiasz: **${choice.label}**\n\n# 🎲 ${face(d1)}   🎲 ${face(d2)}\nSuma: **${d1} + ${d2} = ${sum}**`, bet, res, userId })],
      });
    });
  },
};
