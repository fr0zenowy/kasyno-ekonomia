const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { FARM_CROPS, FARM_SLOTS } = require('../lib/data');
const farm = require('../lib/farm');
const { fmt, num, embed, COLORS, shortDuration } = require('../lib/util');
const { fail } = require('../lib/game');

const cropLabel = (c) => `${c.emoji} ${c.name}`;
const minutes = (m) => (m >= 60 ? `${m / 60} h` : `${m} min`);

module.exports = {
  data: new SlashCommandBuilder()
    .setName('farma')
    .setDescription('Twoja farma: sadź uprawy, a po czasie zbieraj plon wart więcej niż nasiona')
    .addSubcommand((s) => s.setName('pola').setDescription('Co rośnie na Twoich polach'))
    .addSubcommand((s) =>
      s
        .setName('zasadz')
        .setDescription('Zasadź uprawę na wolnych polach (albo na jednym wybranym)')
        .addStringOption((o) =>
          o
            .setName('uprawa')
            .setDescription('Co sadzić')
            .setRequired(true)
            .addChoices(...FARM_CROPS.map((c) => ({ name: `${cropLabel(c)} – koszt ${num(c.cost)}, plon ${num(c.yield)}, ${minutes(c.minutes)}`, value: c.id }))),
        )
        .addIntegerOption((o) => o.setName('pole').setDescription(`Numer pola 1-${FARM_SLOTS} (domyślnie wszystkie wolne)`).setMinValue(1).setMaxValue(FARM_SLOTS)),
    )
    .addSubcommand((s) => s.setName('zbierz').setDescription('Zbierz wszystkie dojrzałe uprawy')),

  async execute(interaction) {
    const userId = interaction.user.id;
    const sub = interaction.options.getSubcommand();

    if (sub === 'zasadz') {
      const r = farm.plant(userId, interaction.options.getString('uprawa'), interaction.options.getInteger('pole'));
      if (!r.ok) {
        const reasons = {
          unknown: '❌ Nie znam takiej uprawy.',
          slot: `❌ Pole musi być od 1 do ${FARM_SLOTS}.`,
          busy: '❌ Na tym polu już coś rośnie.',
          full: '🌾 Wszystkie pola są zajęte. Poczekaj na plon albo zbierz: `/farma zbierz`.',
          poor: `❌ Nasiona ${r.crop ? `(${cropLabel(r.crop)})` : ''} kosztują ${fmt(r.cost ?? 0)} za pole, a masz ${fmt(db.getUser(userId).wallet)}.`,
        };
        return fail(interaction, reasons[r.reason] ?? '❌ Nie udało się zasadzić.');
      }
      return interaction.reply({
        embeds: [
          embed({
            title: `${r.crop.emoji} Zasadzone!`,
            color: COLORS.win,
            description:
              `**${r.planted}× ${r.crop.name}** (pola: ${r.slots.join(', ')}) za ${fmt(r.cost)}.\n` +
              `Dojrzeją za **${shortDuration(r.readyIn)}**, plon: **${fmt(r.crop.yield * r.planted)}**.` +
              (r.skipped ? `\n*(${r.skipped} wolnych pól zostało puste: zabrakło pieniędzy)*` : ''),
            footer: `Portfel: ${fmt(db.getUser(userId).wallet)} • zbierzesz komendą /farma zbierz`,
          }),
        ],
      });
    }

    if (sub === 'zbierz') {
      const h = farm.harvest(userId);
      if (!h.count) return fail(interaction, '🌱 Nic jeszcze nie dojrzało. Zobacz, kiedy będzie plon: `/farma pola`.');
      const lines = Object.entries(h.crops).map(([id, n]) => `${cropLabel(farm.find(id))} ×${n}`);
      return interaction.reply({
        embeds: [embed({ title: '🚜 Zbiory!', color: COLORS.win, description: `${lines.join(', ')}\n\nPlon wart **${fmt(h.gain)}**.`, footer: `Portfel: ${fmt(db.getUser(userId).wallet)}` })],
      });
    }

    // pola
    const slots = farm.status(userId);
    const lines = slots.map((s) => {
      if (s.state === 'empty') return `🟫 **Pole ${s.slot}**: puste`;
      if (s.state === 'ready') return `✅ **Pole ${s.slot}**: ${cropLabel(s.crop)} gotowe do zbioru!`;
      return `🌱 **Pole ${s.slot}**: ${cropLabel(s.crop)} – jeszcze ${shortDuration(s.left)}`;
    });
    const ready = slots.filter((s) => s.state === 'ready').length;
    await interaction.reply({
      embeds: [
        embed({
          title: '🌾 Twoja farma',
          color: COLORS.info,
          description: lines.join('\n') + (ready ? `\n\n🚜 Do zebrania: **${ready}** – \`/farma zbierz\`` : '') + '\n\nSadzisz: `/farma zasadz`. Podlewaczka ze `/sklep` skraca czas wzrostu o 25%.',
          footer: FARM_CROPS.map((c) => `${c.emoji}${minutes(c.minutes)}`).join(' • '),
        }),
      ],
    });
  },
};
