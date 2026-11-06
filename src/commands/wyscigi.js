const { SlashCommandBuilder } = require('discord.js');
const { takeBet, settle, resultEmbed, guarded, delay } = require('../lib/game');
const { embed, sleep, COLORS, randInt } = require('../lib/util');

// p = szansa na wygraną, mult = wypłata (ok. 95% / p)
const HORSES = [
  { name: 'Błyskawica', p: 0.35, mult: 2.7 },
  { name: 'Pegaz', p: 0.25, mult: 3.8 },
  { name: 'Burza', p: 0.2, mult: 4.7 },
  { name: 'Mustang', p: 0.12, mult: 7.9 },
  { name: 'Czarny Koń', p: 0.08, mult: 11.8 },
];
const TRACK = 12;

function pickWinner() {
  let r = Math.random();
  for (let i = 0; i < HORSES.length; i++) {
    r -= HORSES[i].p;
    if (r < 0) return i;
  }
  return HORSES.length - 1;
}

function render(positions, winner = -1) {
  return (
    '```\n' +
    positions
      .map((pos, i) => `${i + 1} |${'·'.repeat(pos)}🐎${'·'.repeat(TRACK - pos)}|🏁${i === winner ? ' 🏆' : ''}`)
      .join('\n') +
    '\n```'
  );
}

module.exports = {
  HORSES,
  pickWinner,

  data: new SlashCommandBuilder()
    .setName('wyscigi')
    .setDescription('Obstaw konia w wyścigu – im mniejszy faworyt, tym większa wypłata')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true))
    .addIntegerOption((o) =>
      o
        .setName('kon')
        .setDescription('Na którego konia stawiasz')
        .setRequired(true)
        .addChoices(...HORSES.map((h, i) => ({ name: `${i + 1}. ${h.name} (x${h.mult})`, value: i + 1 }))),
    ),

  async execute(interaction) {
    const pickIdx = interaction.options.getInteger('kon') - 1;
    const horse = HORSES[pickIdx];
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      const winner = pickWinner();
      const finals = HORSES.map((_, i) => (i === winner ? TRACK : randInt(6, TRACK - 1)));
      const header = `Stawiasz na **${pickIdx + 1}. ${horse.name}** (x${horse.mult})\n`;
      const info = { game: 'wyscigi', pick: pickIdx, winner, finals, track: TRACK, mult: horse.mult, bet };
      interaction.meta?.(info);

      await interaction.reply({
        embeds: [embed({ title: '🏇 Wyścigi konne', color: COLORS.push, description: `${header}Konie ruszają z boksów!${render(HORSES.map(() => 0))}` })],
      });

      // Pośrednie klatki: nikt nie dobiega do mety przed ostatnią (max TRACK-1 albo jego końcowa pozycja - 1).
      const steps = 4;
      for (let t = 1; t < steps; t++) {
        await sleep(delay(1100));
        const positions = finals.map((f) => Math.max(0, Math.min(f - 1, Math.floor(f * (t / steps) * (0.75 + Math.random() * 0.3)))));
        await interaction.editReply({
          embeds: [embed({ title: '🏇 Wyścigi konne', color: COLORS.push, description: `${header}Trwa gonitwa...${render(positions)}` })],
        });
      }
      await sleep(delay(1100));

      const res = settle(userId, bet, winner === pickIdx ? Math.floor(bet * horse.mult) : 0);
      interaction.meta?.({ ...info, net: res.net });
      await interaction.editReply({
        embeds: [
          resultEmbed({
            title: '🏇 Wyścigi konne',
            description: `${header}Wygrywa **${winner + 1}. ${HORSES[winner].name}**!${render(finals, winner)}`,
            bet,
            res,
            userId,
          }),
        ],
      });
    });
  },
};
