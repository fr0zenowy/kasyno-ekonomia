const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { ITEMS } = require('../lib/data');
const { CRATES, RARITY, MAX_OPEN, expectedValue, rarityOdds, openCrates } = require('../lib/crates');
const { fmt, embed, COLORS } = require('../lib/util');

const crateIds = Object.keys(CRATES);

module.exports = {
  data: new SlashCommandBuilder()
    .setName('skrzynie')
    .setDescription('Skrzynie z nagrodami: sprawdź szanse i otwieraj z animacją')
    .addSubcommand((s) => s.setName('lista').setDescription('Rodzaje skrzyń, szanse na rzadkości i ile ich masz'))
    .addSubcommand((s) =>
      s
        .setName('otworz')
        .setDescription('Otwórz skrzynię z ekwipunku')
        .addStringOption((o) =>
          o
            .setName('skrzynia')
            .setDescription('Którą otworzyć')
            .setRequired(true)
            .addChoices(...crateIds.map((id) => ({ name: ITEMS[id].name, value: id }))),
        )
        .addIntegerOption((o) =>
          o.setName('ilosc').setDescription(`Ile naraz (1-${MAX_OPEN}); jedna skrzynia = pełna animacja`).setMinValue(1).setMaxValue(MAX_OPEN),
        ),
    ),

  async execute(interaction) {
    const sub = interaction.options.getSubcommand();

    if (sub === 'otworz') {
      return openCrates(interaction, interaction.options.getString('skrzynia'), interaction.options.getInteger('ilosc') ?? 1);
    }

    const userId = interaction.user.id;
    const fields = crateIds.map((id) => {
      const it = ITEMS[id];
      const odds = rarityOdds(id);
      const ev = expectedValue(id);
      const owned = db.itemQty(userId, id);
      const chances = Object.entries(RARITY)
        .map(([key, r]) => `${r.emoji} ${(odds[key] ?? 0).toFixed(1)}%`)
        .join(' · ');
      return {
        name: `${it.emoji} ${it.name} — ${fmt(it.price)}${owned ? ` (masz: ${owned})` : ''}`,
        value: `${it.desc}\n${chances}\n*Średnia wartość nagrody: ${fmt(Math.round(ev))} (${Math.round((ev / it.price) * 100)}% ceny)*`,
      };
    });

    await interaction.reply({
      embeds: [
        embed({
          title: '🎁 Skrzynie',
          color: COLORS.gold,
          description:
            'Kupisz je w `/sklep`, a zdobędziesz też z `/daily`, `/weekly`, awansów i łupów (`/ryby`, `/kopalnia`, `/polowanie`).\n' +
            'Otwierasz komendą `/skrzynie otworz`. Wypadają pieniądze, XP i przedmioty, a czasem… kolejna skrzynia!',
          fields,
          footer: 'Przedmioty wyceniane po cenie skupu • rzadkości: ⚪ zwykły 🟢 niezwykły 🔵 rzadki 🟣 epicki 🟡 legendarny',
        }),
      ],
    });
  },
};
