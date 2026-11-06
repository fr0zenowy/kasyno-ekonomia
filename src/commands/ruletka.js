const { SlashCommandBuilder } = require('discord.js');
const { takeBet, settle, resultEmbed, guarded, delay, fail } = require('../lib/game');
const { embed, sleep, COLORS, randInt } = require('../lib/util');

const RED = new Set([1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36]);
const colorOf = (n) => (n === 0 ? 'zielone' : RED.has(n) ? 'czerwone' : 'czarne');
const colorEmoji = (n) => (n === 0 ? '🟢' : RED.has(n) ? '🔴' : '⚫');

// mult = całkowity zwrot (stawka × mult)
const TYPES = {
  czerwone: { label: '🔴 Czerwone', mult: 2, wins: (n) => RED.has(n) },
  czarne: { label: '⚫ Czarne', mult: 2, wins: (n) => n !== 0 && !RED.has(n) },
  parzyste: { label: 'Parzyste', mult: 2, wins: (n) => n !== 0 && n % 2 === 0 },
  nieparzyste: { label: 'Nieparzyste', mult: 2, wins: (n) => n % 2 === 1 },
  niskie: { label: 'Niskie (1-18)', mult: 2, wins: (n) => n >= 1 && n <= 18 },
  wysokie: { label: 'Wysokie (19-36)', mult: 2, wins: (n) => n >= 19 },
  tuzin1: { label: 'Tuzin 1-12', mult: 3, wins: (n) => n >= 1 && n <= 12 },
  tuzin2: { label: 'Tuzin 13-24', mult: 3, wins: (n) => n >= 13 && n <= 24 },
  tuzin3: { label: 'Tuzin 25-36', mult: 3, wins: (n) => n >= 25 },
};

module.exports = {
  RED,
  TYPES,
  colorOf,

  data: new SlashCommandBuilder()
    .setName('ruletka')
    .setDescription('Ruletka europejska (0-36): kolor, parzystość, tuzin albo konkretny numer (x36)')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true))
    .addStringOption((o) =>
      o
        .setName('typ')
        .setDescription('Zakład zewnętrzny (albo użyj opcji „numer”)')
        .addChoices(
          { name: '🔴 Czerwone (x2)', value: 'czerwone' },
          { name: '⚫ Czarne (x2)', value: 'czarne' },
          { name: 'Parzyste (x2)', value: 'parzyste' },
          { name: 'Nieparzyste (x2)', value: 'nieparzyste' },
          { name: 'Niskie 1-18 (x2)', value: 'niskie' },
          { name: 'Wysokie 19-36 (x2)', value: 'wysokie' },
          { name: 'Tuzin 1-12 (x3)', value: 'tuzin1' },
          { name: 'Tuzin 13-24 (x3)', value: 'tuzin2' },
          { name: 'Tuzin 25-36 (x3)', value: 'tuzin3' },
        ),
    )
    .addIntegerOption((o) => o.setName('numer').setDescription('Konkretny numer 0-36 (wygrana x36)').setMinValue(0).setMaxValue(36)),

  async execute(interaction) {
    const typeKey = interaction.options.getString('typ');
    const number = interaction.options.getInteger('numer');
    if (typeKey && number !== null) return fail(interaction, '❌ Wybierz albo „typ”, albo „numer” – nie oba naraz.');
    if (!typeKey && number === null) return fail(interaction, '❌ Wybierz „typ” zakładu (np. czerwone) albo wpisz „numer” od 0 do 36.');

    const choice = number !== null ? { label: `Numer ${number}`, mult: 36, wins: (n) => n === number } : TYPES[typeKey];

    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      // Numer jest wylosowany z góry; kulka na stronie i klatki animacji tylko do niego „dojeżdżają”.
      const n = randInt(0, 36);
      const won = choice.wins(n);
      const info = { game: 'ruletka', n, color: colorOf(n), label: choice.label, mult: choice.mult, won, bet };
      interaction.meta?.(info);
      const frame = (text, ball) =>
        embed({ title: '🎡 Ruletka', color: COLORS.push, description: `Stawiasz: **${choice.label}**\n\n${ball ? `# ${colorEmoji(ball.n)} ${ball.n}\n` : ''}${text}` });

      await interaction.reply({ embeds: [frame('🌀 Kulka krąży po kole...')] });
      const hints = ['🌀 Kulka krąży po kole...', '👀 Kulka zwalnia...', '😮 Skacze między polami...'];
      for (const [k, wait] of [700, 800, 1000].entries()) {
        await sleep(delay(wait));
        await interaction.editReply({ embeds: [frame(hints[k], { n: randInt(0, 36) })] });
      }
      await sleep(delay(1200));
      const res = settle(userId, bet, won ? bet * choice.mult : 0);
      interaction.meta?.({ ...info, net: res.net });
      await interaction.editReply({
        embeds: [
          resultEmbed({
            title: '🎡 Ruletka',
            description: `Stawiasz: **${choice.label}**\n\n# ${colorEmoji(n)} ${n}\nWypadło: **${n}** (${colorOf(n)})`,
            bet,
            res,
            userId,
          }),
        ],
      });
    });
  },
};
