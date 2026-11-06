const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { fmt, num, embed, COLORS } = require('../lib/util');

const MEDALS = ['🥇', '🥈', '🥉'];

module.exports = {
  data: new SlashCommandBuilder()
    .setName('top')
    .setDescription('Ranking najlepszych graczy')
    .addStringOption((o) =>
      o
        .setName('typ')
        .setDescription('Według czego sortować')
        .addChoices(
          { name: '💎 Majątek (domyślnie)', value: 'majatek' },
          { name: '⭐ Poziom', value: 'poziom' },
          { name: '🎰 Wygrane w hazardzie', value: 'hazard' },
        ),
    ),

  async execute(interaction) {
    await interaction.deferReply();
    const by = interaction.options.getString('typ') ?? 'majatek';
    const rows = db.top(by, 10);
    if (!rows.length) return interaction.editReply('Nikt jeszcze nie gra. Bądź pierwszy: `/daily`!');

    const names = await Promise.all(
      rows.map((r) =>
        interaction.client.users
          .fetch(r.id)
          .then((u) => u.displayName)
          .catch(() => 'Nieznany gracz'),
      ),
    );

    const lines = rows.map((r, i) => {
      const medal = MEDALS[i] ?? `**${i + 1}.**`;
      let value;
      if (by === 'poziom') value = `poziom **${r.level}**`;
      else if (by === 'hazard') value = `**${fmt(r.total_won)}** wygranych`;
      else value = `**${fmt(r.wallet + r.bank)}**`;
      return `${medal} ${names[i]} – ${value}`;
    });

    const titles = { majatek: '💎 Najbogatsi gracze', poziom: '⭐ Najwyższe poziomy', hazard: '🎰 Najwięksi hazardziści' };
    await interaction.editReply({
      embeds: [
        embed({
          title: titles[by],
          color: COLORS.gold,
          description: lines.join('\n'),
          footer: `Graczy w bazie: ${num(db.userCount())}`,
        }),
      ],
    });
  },
};
