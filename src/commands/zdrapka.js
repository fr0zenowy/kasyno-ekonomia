const { SlashCommandBuilder } = require('discord.js');
const { takeBet, settle, guarded, delay } = require('../lib/game');
const { embed, COLORS, weighted, shuffle, fmt, sleep } = require('../lib/util');

// Trzy takie same symbole = wygrana. Symbol zależy od mnożnika. Zwrot 94.5% (sprawdza test/rtp.js).
const OUTCOMES = [
  { mult: 0, w: 69.1, sym: null },
  { mult: 1, w: 15, sym: '🍒' },
  { mult: 2, w: 10.5, sym: '🍋' },
  { mult: 3, w: 5.2, sym: '🍀' },
  { mult: 5, w: 2.4, sym: '⭐' },
  { mult: 10, w: 1, sym: '💎' },
  { mult: 25, w: 0.25, sym: '👑' },
  { mult: 100, w: 0.08, sym: '🏆' },
  { mult: 500, w: 0.02, sym: '🎰' },
];
const ALL = ['🍒', '🍋', '🍀', '⭐', '💎', '👑', '🏆', '🎰'];

/** Buduje 9 pól: przy wygranej dokładnie trzy symbole wygrywającego, żaden inny nie występuje 3 razy. */
function buildGrid(outcome) {
  const cells = [];
  if (outcome.sym) cells.push(outcome.sym, outcome.sym, outcome.sym);
  const counts = Object.fromEntries(ALL.map((s) => [s, 0]));
  const fillers = ALL.filter((s) => s !== outcome.sym);
  while (cells.length < 9) {
    const s = fillers[Math.floor(Math.random() * fillers.length)];
    if (counts[s] >= 2) continue;
    counts[s] += 1;
    cells.push(s);
  }
  return shuffle(cells);
}

const render = (cells) =>
  [0, 3, 6].map((i) => cells.slice(i, i + 3).map((c) => `||${c}||`).join(' ')).join('\n');

module.exports = {
  OUTCOMES,
  buildGrid,

  data: new SlashCommandBuilder()
    .setName('zdrapka')
    .setDescription('Kup zdrapkę i zdrap pola – trzy takie same to wygrana (do x500)')
    .addStringOption((o) => o.setName('zaklad').setDescription('Cena zdrapki (np. 100, 2k, 50%)').setRequired(true)),

  async execute(interaction) {
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      // Plansza jest gotowa z góry; strona pozwala ją zdrapywać, a rozliczenie i tak następuje po krótkiej pauzie.
      const outcome = weighted(OUTCOMES);
      const cells = buildGrid(outcome);
      const info = { game: 'zdrapka', cells, sym: outcome.sym, mult: outcome.mult, bet };
      interaction.meta?.(info);
      await interaction.reply({ embeds: [embed({ title: '🎟️ Zdrapka', color: COLORS.push, description: 'Kupujesz zdrapkę i bierzesz monetę do ręki... 🪙' })] });
      await sleep(delay(900));

      const res = settle(userId, bet, Math.floor(bet * outcome.mult));
      interaction.meta?.({ ...info, net: res.net });

      const verdict =
        res.net > 0
          ? `🎉 Wygrywasz **${fmt(res.net)}** (x${outcome.mult})!`
          : res.refunded
            ? `${res.refunded.emoji} **${res.refunded.name}** zwróciła Ci stawkę!`
            : res.net < 0
              ? '💸 Pudło, tym razem bez wygranej.'
              : '🤝 Trzy wiśnie – stawka wraca do Ciebie.';
      const color = res.net > 0 ? COLORS.win : res.net < 0 ? COLORS.lose : COLORS.push;

      await interaction.editReply({
        embeds: [
          embed({
            title: '🎟️ Zdrapka',
            color,
            description:
              `Kliknij pola, żeby je zdrapać. Trzy takie same = wygrana!\n\n${render(cells)}\n\n` +
              `||${verdict}${res.bonus ? ` 🍀 Bonus: +${fmt(res.bonus)}` : ''}${res.lv.note}||`,
            footer: `Cena zdrapki: ${fmt(bet)}`,
          }),
        ],
      });
    });
  },
};
