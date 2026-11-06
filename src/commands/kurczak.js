const { SlashCommandBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle } = require('discord.js');
const { runStepGame, stepMultiplier } = require('../lib/stepgame');
const { num } = require('../lib/util');

// d = szansa, że kurczak zostanie potrącony na danym pasie. Im trudniej, tym szybciej rosną mnożniki.
const LEVELS = {
  latwy: { name: 'Łatwy', d: 0.06, max: 30 },
  sredni: { name: 'Średni', d: 0.14, max: 26 },
  trudny: { name: 'Trudny', d: 0.25, max: 22 },
  hardcore: { name: 'Hardcore', d: 0.4, max: 14 },
};
const WINDOW = 10; // tyle pasów widać naraz

function road(steps, max, dead) {
  const start = Math.max(0, Math.min(steps - 3, max - WINDOW));
  let out = '';
  for (let p = start; p < start + WINDOW && p <= max; p++) {
    if (p < steps) out += '🟩';
    else if (p === steps) out += dead ? '🪦' : '🐔';
    else if (dead && p === steps + 1) out += '🚗';
    else out += p === max ? '🏁' : '⬜';
  }
  return out;
}

module.exports = {
  LEVELS,

  data: new SlashCommandBuilder()
    .setName('kurczak')
    .setDescription('Chicken Road: przeprowadź kurczaka przez ulicę, im dalej, tym większy mnożnik (do x1000)')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true))
    .addStringOption((o) =>
      o
        .setName('poziom')
        .setDescription('Trudność (domyślnie średni)')
        .addChoices(...Object.entries(LEVELS).map(([value, l]) => ({ name: `${l.name} (${Math.round(l.d * 100)}% ryzyka na pas)`, value }))),
    ),

  async execute(interaction) {
    const levelKey = interaction.options.getString('poziom') ?? 'sredni';
    const level = LEVELS[levelKey];
    const pSafe = 1 - level.d;

    await runStepGame(interaction, {
      game: 'kurczak',
      meta: () => ({ level: levelKey, pSafe }),
      title: `Chicken Road – ${level.name}`,
      emoji: '🐔',
      betInput: interaction.options.getString('zaklad'),
      pSafe,
      maxSteps: level.max,
      resolve: () => Math.random() < pSafe,
      rows: (state, over, cash) => [
        new ActionRowBuilder().addComponents(
          new ButtonBuilder()
            .setCustomId('go_forward')
            .setLabel(`Idź dalej (→ x${stepMultiplier(pSafe, state.steps + 1).toFixed(2)})`)
            .setEmoji('🐔')
            .setStyle(ButtonStyle.Primary)
            .setDisabled(over),
          cash,
        ),
      ],
      render: (state, c) =>
        `${road(state.steps, c.maxSteps, c.dead)}\n` +
        `Pas: **${state.steps}/${c.maxSteps}** • Mnożnik: **x${c.mult.toFixed(2)}** • ${c.over ? (c.dead ? 'Wypłata' : 'Wypłacono') : 'Do wypłaty'}: **${num(c.payout)}**` +
        (c.over ? '' : `\nNastępny pas: **x${c.nextMult.toFixed(2)}** • szansa przeżycia ${Math.round(pSafe * 100)}%`),
      deathText: () => '🚗💥 **Potrącony!** Kurczak nie przeżył tego pasa.',
      shieldText: (state, shield) => `${shield.emoji} **${shield.name}** odepchnęła samochód! Kurczak stoi tam, gdzie stał, idź dalej.`,
    });
  },
};
