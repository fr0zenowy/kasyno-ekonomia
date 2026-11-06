const { SlashCommandBuilder } = require('discord.js');
const { takeBet, settle, resultEmbed, guarded, delay } = require('../lib/game');
const { embed, sleep, COLORS, fmt } = require('../lib/util');

const ROWS = 12; // 12 rzędów kołków = 13 koszyków

// Mnożniki od krawędzi do środka (druga połowa jest lustrzana). Szansa na koszyk to rozkład dwumianowy (jak w prawdziwym plinko),
// więc krawędzie padają rzadko: np. skrajny koszyk 2 × 1/4096. Zwrot każdego poziomu ok. 95–96% (sprawdza test/rtp.js).
const HALF = {
  niskie: [8.3, 5, 3.1, 1.9, 1.1, 0.7, 0.4],
  srednie: [30, 13, 5.4, 2.3, 1, 0.4, 0.2],
  wysokie: [170, 30, 7.5, 2.1, 0.5, 0.2, 0.1],
  szalone: [1000, 12, 4, 1.4, 0.3, 0.1, 0],
};
const mirror = (h) => [...h, ...h.slice(0, -1).reverse()];
const MULTS = Object.fromEntries(Object.entries(HALF).map(([k, h]) => [k, mirror(h)]));
const NAMES = { niskie: 'Niskie ryzyko', srednie: 'Średnie ryzyko', wysokie: 'Wysokie ryzyko', szalone: 'Szalone ryzyko' };

const label = (m) => (m < 1 ? String(m).replace('0.', '.') : String(m));

/** Plansza: kulka nad koszykami. `rights` to liczba ruchów w prawo po `k` rzędach. */
function board(mults, k, rights, finalIdx = null) {
  const pos = finalIdx ?? Math.max(0, Math.min(12, Math.round(6 + rights - k / 2)));
  const ball = Array.from({ length: 13 }, (_, i) => (i === pos ? ' ●  ' : '    ')).join('');
  const buckets = mults.map((m) => label(m).padStart(3).padEnd(4)).join('');
  const marker = finalIdx === null ? '' : `\n${Array.from({ length: 13 }, (_, i) => (i === finalIdx ? ' ▲  ' : '    ')).join('')}`;
  return `\`\`\`\n${ball}\n${buckets}${marker}\n\`\`\``;
}

module.exports = {
  ROWS,
  MULTS,

  data: new SlashCommandBuilder()
    .setName('plinko')
    .setDescription('Plinko: kulka spada przez kołki do koszyka z mnożnikiem (nawet x1000)')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true))
    .addStringOption((o) =>
      o
        .setName('ryzyko')
        .setDescription('Im większe ryzyko, tym większe maksymalne mnożniki (domyślnie średnie)')
        .addChoices(...Object.entries(HALF).map(([value, h]) => ({ name: `${NAMES[value]} (do x${h[0]})`, value }))),
    ),

  async execute(interaction) {
    const risk = interaction.options.getString('ryzyko') ?? 'srednie';
    const mults = MULTS[risk];
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      const path = Array.from({ length: ROWS }, () => (Math.random() < 0.5 ? 1 : 0));
      const bucket = path.reduce((a, b) => a + b, 0);
      const info = { game: 'plinko', risk, path, bucket, mult: mults[bucket], bet };
      interaction.meta?.(info);
      const frame = (text, k) => embed({ title: `🎯 Plinko – ${NAMES[risk]}`, color: COLORS.push, description: `${text}\n${board(mults, k, path.slice(0, k).reduce((a, b) => a + b, 0))}` });

      await interaction.reply({ embeds: [frame('⬇️ Kulka wypada z góry...', 0)] });
      for (const k of [3, 6, 9]) {
        await sleep(delay(800));
        await interaction.editReply({ embeds: [frame(`Rząd **${k}/${ROWS}**...`, k)] });
      }
      await sleep(delay(900));

      const mult = mults[bucket];
      const res = settle(userId, bet, Math.floor(bet * mult));
      interaction.meta?.({ ...info, net: res.net });
      await interaction.editReply({
        embeds: [
          resultEmbed({
            title: `🎯 Plinko – ${NAMES[risk]}`,
            description: `Kulka wpadła do koszyka **x${label(mult)}**${mult >= 10 ? ' 🔥' : ''}\n${board(mults, ROWS, bucket, bucket)}`,
            bet,
            res,
            userId,
          }),
        ],
      });
    });
  },
};
