const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { fmt, embed, COLORS } = require('../lib/util');
const { EPHEMERAL } = require('../lib/game');

module.exports = {
  data: new SlashCommandBuilder()
    .setName('saldo')
    .setDescription('Pokazuje stan konta (Twojego lub kogoś innego)')
    .addUserOption((o) => o.setName('uzytkownik').setDescription('Czyje konto sprawdzić')),

  async execute(interaction) {
    const target = interaction.options.getUser('uzytkownik') ?? interaction.user;
    if (target.bot) return interaction.reply({ content: '🤖 Boty nie mają kont w banku.', flags: EPHEMERAL });

    const u = db.getUser(target.id);
    const cap = db.bankCap(u);
    await interaction.reply({
      embeds: [
        embed({
          title: `💳 Konto – ${target.displayName}`,
          color: COLORS.gold,
          thumbnail: target.displayAvatarURL(),
          fields: [
            { name: '👛 Portfel', value: fmt(u.wallet), inline: true },
            { name: '🏦 Bank', value: `${fmt(u.bank)}\n*limit: ${fmt(cap)}*`, inline: true },
            { name: '💎 Majątek', value: fmt(u.wallet + u.bank), inline: true },
          ],
          footer: `Ranking majątku: #${db.rankOf(target.id)} z ${db.userCount()}`,
        }),
      ],
    });
  },
};
