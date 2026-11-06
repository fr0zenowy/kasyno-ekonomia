const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { COOLDOWNS } = require('../lib/data');
const { applyEarn } = require('../lib/boosts');
const { fmt, embed, COLORS, randInt, chance, shortDuration } = require('../lib/util');
const { guardCooldown } = require('../lib/game');

module.exports = {
  data: new SlashCommandBuilder().setName('godzinna').setDescription(`Darmowa premia godzinowa (co ${shortDuration(COOLDOWNS.godzinna)}), rośnie z poziomem`),

  async execute(interaction) {
    const userId = interaction.user.id;
    if (!(await guardCooldown(interaction, 'godzinna', COOLDOWNS.godzinna))) return;

    const u = db.getUser(userId);
    const base = Math.floor((300 + u.level * 40) * (0.8 + Math.random() * 0.4));
    const earned = applyEarn(userId, base);
    db.addWallet(userId, earned.amount);

    let extra = '';
    if (chance(0.05)) {
      db.addItem(userId, 'skrzynka', 1);
      extra = '\n📦 W kopercie była też drewniana skrzynka! (`/skrzynie otworz`)';
    }
    const lv = db.addXp(userId, randInt(8, 14));

    await interaction.reply({
      embeds: [
        embed({
          title: '⏰ Premia godzinowa',
          color: COLORS.win,
          description: `Odbierasz **${fmt(earned.amount)}**!${earned.notes.length ? `\n${earned.notes.join('\n')}` : ''}${extra}${lv.note}`,
          footer: `Następna za ${shortDuration(COOLDOWNS.godzinna)}`,
        }),
      ],
    });
  },
};
