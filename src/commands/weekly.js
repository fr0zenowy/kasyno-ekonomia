const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { fmt, embed, COLORS, duration } = require('../lib/util');
const { EPHEMERAL } = require('../lib/game');

const WEEK = 7 * 24 * 3600 * 1000;

module.exports = {
  data: new SlashCommandBuilder().setName('weekly').setDescription('Odbierz cotygodniową nagrodę'),

  async execute(interaction) {
    const id = interaction.user.id;
    const u = db.getUser(id);
    const now = Date.now();

    const left = u.last_weekly + WEEK - now;
    if (left > 0) {
      return interaction.reply({
        content: `⏰ Tygodniową nagrodę odbierzesz za **${duration(left)}**.`,
        flags: EPHEMERAL,
      });
    }

    const reward = 5000 + u.level * 250;
    db.addWallet(id, reward);
    db.addItem(id, 'skrzynia', 1);
    db.setWeekly(id, now);
    const lv = db.addXp(id, 60);

    await interaction.reply({
      embeds: [
        embed({
          title: '📅 Tygodniowa nagroda',
          color: COLORS.win,
          description: `Odebrałeś **${fmt(reward)}** i 🎁 skrzynię skarbów (\`/skrzynie otworz\`)!${lv.note}`,
          footer: 'Następna za 7 dni.',
        }),
      ],
    });
  },
};
