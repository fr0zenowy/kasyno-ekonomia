const { SlashCommandBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle } = require('discord.js');
const { runStepGame } = require('../lib/stepgame');
const { shuffle, num } = require('../lib/util');

// cols = pól na piętrze, bad = ile z nich to pułapki. Bezpieczny krok ma szansę (cols - bad) / cols.
const LEVELS = {
  latwy: { name: 'Łatwy', cols: 4, bad: 1 },
  sredni: { name: 'Średni', cols: 3, bad: 1 },
  trudny: { name: 'Trudny', cols: 2, bad: 1 },
  ekstremalny: { name: 'Ekstremalny', cols: 3, bad: 2 },
};
const MAX_FLOORS = 14;
const SHOWN_FLOORS = 6;

module.exports = {
  LEVELS,

  data: new SlashCommandBuilder()
    .setName('wieza')
    .setDescription('Wspinaj się po wieży: na każdym piętrze wybierz pole bez pułapki (mnożnik do x1000)')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true))
    .addStringOption((o) =>
      o
        .setName('poziom')
        .setDescription('Trudność (domyślnie średni)')
        .addChoices(...Object.entries(LEVELS).map(([value, l]) => ({ name: `${l.name} (${l.cols} pola, ${l.bad} pułapk${l.bad === 1 ? 'a' : 'i'})`, value }))),
    ),

  async execute(interaction) {
    const levelKey = interaction.options.getString('poziom') ?? 'sredni';
    const level = LEVELS[levelKey];
    const pSafe = (level.cols - level.bad) / level.cols;
    const floors = []; // historia: [{ pick, ok }] dla ukończonych pięter

    await runStepGame(interaction, {
      game: 'wieza',
      // Pułapki bieżącego piętra wychodzą na jaw dopiero po przegranej. Wcześniej strona widzi tylko pola zablokowane tarczą i przebyte piętra.
      meta: (state, extra) => ({
        level: levelKey,
        cols: level.cols,
        pSafe,
        blocked: [...state.blocked],
        floors: floors.slice(-MAX_FLOORS),
        trap: extra.over && extra.dead ? [...state.bad] : [],
        lastChoice: state.lastChoice === null ? null : Number(state.lastChoice),
      }),
      title: `Wieża – ${level.name}`,
      emoji: '🗼',
      betInput: interaction.options.getString('zaklad'),
      pSafe,
      maxSteps: MAX_FLOORS,
      blockOnShield: true,
      // Pułapki na bieżącym piętrze losujemy z góry, więc zablokowana tarczą pułapka naprawdę znika z gry.
      newStep: (state) => {
        state.bad = new Set(shuffle([...Array(level.cols).keys()]).slice(0, level.bad));
      },
      resolve: (state, choice) => {
        const ok = !state.bad.has(Number(choice));
        if (ok) floors.push({ pick: Number(choice), bad: [...state.bad] });
        return ok;
      },
      rows: (state, over, cash) => [
        new ActionRowBuilder().addComponents(
          ...Array.from({ length: level.cols }, (_, idx) =>
            new ButtonBuilder()
              .setCustomId(`go_${idx}`)
              .setEmoji(state.blocked.has(String(idx)) ? '🛡️' : '❔')
              .setStyle(state.blocked.has(String(idx)) ? ButtonStyle.Primary : ButtonStyle.Secondary)
              .setDisabled(over || state.blocked.has(String(idx))),
          ),
        ),
        new ActionRowBuilder().addComponents(cash),
      ],
      render: (state, c) => {
        const lines = floors
          .slice(-SHOWN_FLOORS)
          .map((f, k) => {
            const floor = floors.length - Math.min(floors.length, SHOWN_FLOORS) + k + 1;
            const cells = Array.from({ length: level.cols }, (_, idx) => (idx === f.pick ? '✅' : f.bad.includes(idx) ? '💥' : '⬛')).join('');
            return `Piętro ${floor}: ${cells}`;
          })
          .reverse();
        const current = c.over
          ? c.dead
            ? `Piętro ${state.steps + 1}: ${Array.from({ length: level.cols }, (_, idx) => (idx === Number(state.lastChoice) ? '💥' : state.bad?.has(idx) ? '💣' : '⬛')).join('')}`
            : ''
          : `Piętro ${state.steps + 1}: ${Array.from({ length: level.cols }, (_, idx) => (state.blocked.has(String(idx)) ? '🛡️' : '❔')).join('')}`;
        return (
          `${[current, ...lines].filter(Boolean).join('\n')}\n\n` +
          `Piętro: **${state.steps}/${c.maxSteps}** • Mnożnik: **x${c.mult.toFixed(2)}** • ${c.over ? (c.dead ? 'Wypłata' : 'Wypłacono') : 'Do wypłaty'}: **${num(c.payout)}**` +
          (c.over ? '' : `\nNastępne piętro: **x${c.nextMult.toFixed(2)}** • szansa ${Math.round(pSafe * 100)}%`)
        );
      },
      deathText: () => '💥 **Pułapka!** Wieża się zawaliła.',
      shieldText: (state, shield) => `${shield.emoji} **${shield.name}** zablokowała pułapkę! To pole jest wyłączone, wybierz inne.`,
    });
  },
};
