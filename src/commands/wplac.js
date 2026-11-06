const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { fmt, parseAmount, embed, COLORS } = require('../lib/util');
const { fail } = require('../lib/game');

module.exports = {
  data: new SlashCommandBuilder()
    .setName('wplac')
    .setDescription('Wpłać pieniądze do banku (bezpieczne przed okradaniem)')
    .addStringOption((o) => o.setName('kwota').setDescription('Ile wpłacić (np. 500, 2k, 50%, all)').setRequired(true)),

  async execute(interaction) {
    const id = interaction.user.id;
    const u = db.getUser(id);
    const wanted = parseAmount(interaction.options.getString('kwota'), u.wallet);
    if (!wanted) return fail(interaction, '❌ Podaj poprawną kwotę, np. `500`, `2k`, `50%` albo `all`.');
    if (u.wallet < 1) return fail(interaction, '❌ Masz pusty portfel.');

    const room = db.bankCap(u) - u.bank;
    if (room < 1) return fail(interaction, '🏦 Twój bank jest pełny! Zwiększ limit ulepszeniem: `/bank ulepsz` (podgląd: `/bank lista`).');

    const done = db.deposit(id, wanted);
    if (!done) return fail(interaction, '❌ Nie udało się wpłacić.');
    const after = db.getUser(id);
    const note = done < wanted ? `\n*(wpłacono tyle, ile mieści się w limicie lub masz w portfelu)*` : '';

    await interaction.reply({
      embeds: [
        embed({
          title: '🏦 Wpłata',
          color: COLORS.win,
          description: `Wpłacono **${fmt(done)}** do banku.${note}`,
          fields: [
            { name: '👛 Portfel', value: fmt(after.wallet), inline: true },
            { name: '🏦 Bank', value: `${fmt(after.bank)} / ${fmt(db.bankCap(after))}`, inline: true },
          ],
        }),
      ],
    });
  },
};
