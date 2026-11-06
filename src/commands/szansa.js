const { SlashCommandBuilder } = require('discord.js');
const { takeBet, settle, resultEmbed, guarded, delay, fail } = require('../lib/game');
const { embed, sleep, COLORS } = require('../lib/util');

const HOUSE = 96; // wypłata = 96 / szansa (w %), więc każda szansa daje średnio 96% zwrotu
const MIN_CHANCE = 0.5;
const MAX_CHANCE = 95;
const CELLS = 20;

/** Mnożnik wypłaty dla danej szansy w procentach (zaokrąglony w dół do 2 miejsc). */
const multiplierFor = (chance) => Math.floor((HOUSE / chance) * 100) / 100;

function bar(chance, mode, roll) {
  const win = (value) => (mode === 'ponizej' ? value < chance : value >= 100 - chance);
  const cells = Array.from({ length: CELLS }, (_, i) => (win(((i + 0.5) / CELLS) * 100) ? '🟩' : '🟥')).join('');
  const marker = Array.from({ length: CELLS }, (_, i) => (i === Math.min(CELLS - 1, Math.floor((roll / 100) * CELLS)) ? '🔻' : '⬜')).join('');
  return `${marker}\n${cells}`;
}

module.exports = {
  multiplierFor,
  MIN_CHANCE,
  MAX_CHANCE,

  data: new SlashCommandBuilder()
    .setName('szansa')
    .setDescription('Rzut na procent: sam wybierasz szansę (0,5–95%), a wypłata rośnie, gdy szansa maleje')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true))
    .addNumberOption((o) =>
      o.setName('szansa').setDescription(`Szansa na wygraną w % (${MIN_CHANCE}–${MAX_CHANCE}), np. 50 = wypłata x1.92`).setRequired(true).setMinValue(MIN_CHANCE).setMaxValue(MAX_CHANCE),
    )
    .addStringOption((o) =>
      o
        .setName('tryb')
        .setDescription('Wygrywa wynik poniżej progu (domyślnie) albo powyżej')
        .addChoices({ name: '⬇️ Poniżej', value: 'ponizej' }, { name: '⬆️ Powyżej', value: 'powyzej' }),
    ),

  async execute(interaction) {
    const chance = Math.round(interaction.options.getNumber('szansa') * 100) / 100;
    const mode = interaction.options.getString('tryb') ?? 'ponizej';
    if (!(chance >= MIN_CHANCE && chance <= MAX_CHANCE)) return fail(interaction, `❌ Szansa musi być między ${MIN_CHANCE}% a ${MAX_CHANCE}%.`);
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      const mult = multiplierFor(chance);
      const rule = mode === 'ponizej' ? `wygrywa wynik **poniżej ${chance.toFixed(2)}**` : `wygrywa wynik **${(100 - chance).toFixed(2)} lub więcej**`;
      const header = `Szansa: **${chance.toFixed(2)}%** • wypłata: **x${mult.toFixed(2)}**\n${rule}`;
      const frame = (text, roll = 0) => embed({ title: '🎲 Szansa', color: COLORS.push, description: `${header}\n\n${text}\n${bar(chance, mode, roll)}` });

      // Rzut jest rozstrzygnięty z góry; klatki animacji i strona tylko go odsłaniają.
      const rolled = Math.floor(Math.random() * 10000) / 100; // 0.00–99.99
      const won = mode === 'ponizej' ? rolled < chance : rolled >= 100 - chance;
      const info = { game: 'szansa', chance, mode, mult, rolled, won, bet };
      interaction.meta?.(info);

      await interaction.reply({ embeds: [frame('🎲 Rzucam...')] });
      for (const wait of [600, 700]) {
        await sleep(delay(wait));
        await interaction.editReply({ embeds: [frame('🎲 Kostka się toczy...', Math.random() * 100)] });
      }
      await sleep(delay(800));

      const res = settle(userId, bet, won ? Math.floor(bet * mult) : 0);
      interaction.meta?.({ ...info, net: res.net });
      await interaction.editReply({
        embeds: [
          resultEmbed({
            title: '🎲 Szansa',
            description: `${header}\n\n# ${won ? '✅' : '❌'} ${rolled.toFixed(2)}\n${bar(chance, mode, rolled)}`,
            bet,
            res,
            userId,
          }),
        ],
      });
    });
  },
};
