const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { fmt, parseAmount, embed, COLORS } = require('../lib/util');
const { fail } = require('../lib/game');

module.exports = {
  data: new SlashCommandBuilder()
    .setName('wyplac')
    .setDescription('Wypłać pieniądze z banku do portfela')
    .addStringOption((o) => o.setName('kwota').setDescription('Ile wypłacić (np. 500, 2k, 50%, all)').setRequired(true)),

  async execute(interaction) {
    const id = interaction.user.id;
    const u = db.getUser(id);
    const wanted = parseAmount(interaction.options.getString('kwota'), u.bank);
    if (!wanted) return fail(interaction, '❌ Podaj poprawną kwotę, np. `500`, `2k`, `50%` albo `all`.');
    if (u.bank < 1) return fail(interaction, '❌ Twój bank jest pusty.');

    const done = db.withdraw(id, wanted);
    if (!done) return fail(interaction, '❌ Nie udało się wypłacić.');
    const after = db.getUser(id);
    const note = done < wanted ? `\n*(wypłacono wszystko, co było w banku)*` : '';

    await interaction.reply({
      embeds: [
        embed({
          title: '🏦 Wypłata',
          color: COLORS.info,
          description: `Wypłacono **${fmt(done)}** z banku.${note}`,
          fields: [
            { name: '👛 Portfel', value: fmt(after.wallet), inline: true },
            { name: '🏦 Bank', value: `${fmt(after.bank)} / ${fmt(db.bankCap(after))}`, inline: true },
          ],
        }),
      ],
    });
  },
};
