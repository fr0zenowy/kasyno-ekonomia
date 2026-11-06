const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { fmt, parseAmount, embed, COLORS } = require('../lib/util');
const { fail } = require('../lib/game');

module.exports = {
  data: new SlashCommandBuilder()
    .setName('przelew')
    .setDescription('Przelej pieniądze z portfela innemu graczowi')
    .addUserOption((o) => o.setName('uzytkownik').setDescription('Komu przelać').setRequired(true))
    .addStringOption((o) => o.setName('kwota').setDescription('Ile przelać (np. 500, 2k, 50%)').setRequired(true)),

  async execute(interaction) {
    const target = interaction.options.getUser('uzytkownik');
    if (target.bot) return fail(interaction, '🤖 Bot nie potrzebuje pieniędzy (ale dziękuje za gest).');
    if (target.id === interaction.user.id) return fail(interaction, '🙃 Przelew do samego siebie? Nic by to nie zmieniło.');

    const u = db.getUser(interaction.user.id);
    const amount = parseAmount(interaction.options.getString('kwota'), u.wallet);
    if (!amount) return fail(interaction, '❌ Podaj poprawną kwotę, np. `500`, `2k`, `50%`.');
    if (amount > u.wallet) return fail(interaction, `❌ Masz tylko ${fmt(u.wallet)} w portfelu.`);

    if (!db.transfer(interaction.user.id, target.id, amount)) {
      return fail(interaction, '❌ Przelew się nie udał.');
    }

    await interaction.reply({
      embeds: [
        embed({
          title: '💸 Przelew wykonany',
          color: COLORS.win,
          description: `${interaction.user} przelał **${fmt(amount)}** dla ${target}.`,
          footer: `Twój portfel: ${fmt(db.getUser(interaction.user.id).wallet)}`,
        }),
      ],
    });
  },
};
