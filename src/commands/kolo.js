const { SlashCommandBuilder } = require('discord.js');
const { takeBet, settle, resultEmbed, guarded } = require('../lib/game');
const { buildStrip, spinStrip, stripView } = require('../lib/anim');
const { embed, COLORS, weighted } = require('../lib/util');

// mult = mnożnik stawki, w = waga. Zwrot ok. 96% (sprawdza test/rtp.js). Rzadkie segmenty x50 i x200 to „duże wygrane”.
const SEGMENTS = [
  { label: '💀 x0', mult: 0, w: 38.3 },
  { label: '😐 x0.5', mult: 0.5, w: 20 },
  { label: '🟡 x1', mult: 1, w: 17 },
  { label: '🟢 x1.5', mult: 1.5, w: 12 },
  { label: '🔵 x2', mult: 2, w: 8 },
  { label: '🟣 x3', mult: 3, w: 3.5 },
  { label: '🔥 x5', mult: 5, w: 1.2 },
  { label: '💎 x10', mult: 10, w: 0.4 },
  { label: '👑 x50', mult: 50, w: 0.1 },
  { label: '🌠 x200', mult: 200, w: 0.05 },
];

const TARGET = 20;
const HALF = 2;
const icon = (seg) => seg.label.split(' ')[0];

module.exports = {
  SEGMENTS,

  data: new SlashCommandBuilder()
    .setName('kolo')
    .setDescription('Koło fortuny! Od x0 do aż x200')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true)),

  async execute(interaction) {
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      // Segment jest wylosowany z góry; pasek tylko „dojeżdża” do niego. Rozliczenie dopiero po animacji.
      const seg = weighted(SEGMENTS);
      const cells = buildStrip(icon(seg), () => icon(weighted(SEGMENTS)), TARGET, HALF);
      const info = { game: 'kolo', index: SEGMENTS.indexOf(seg), label: seg.label, mult: seg.mult, bet };
      interaction.meta?.(info);
      const frame = (view, text) => embed({ title: '🎡 Koło fortuny', color: COLORS.push, description: `${view}\n\n${text}` });

      await interaction.reply({ embeds: [frame(stripView(cells, HALF, HALF), '🌀 Koło się kręci...')] });
      const finalView = await spinStrip(cells, TARGET, (view) => interaction.editReply({ embeds: [frame(view, '🌀 Koło się kręci...')] }), { half: HALF });

      const res = settle(userId, bet, Math.floor(bet * seg.mult));
      interaction.meta?.({ ...info, net: res.net });
      await interaction.editReply({
        embeds: [resultEmbed({ title: '🎡 Koło fortuny', description: `${finalView}\n\nWypadło: **${seg.label}**`, bet, res, userId })],
      });
    });
  },
};
