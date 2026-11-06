const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { COOLDOWNS } = require('../lib/data');
const { fmt, embed, COLORS, randInt, chance, pick, shortDuration } = require('../lib/util');
const { guardCooldown } = require('../lib/game');
const { applyEarn } = require('../lib/boosts');

const GIVERS = [
  'Starsza pani', 'Zabiegany biznesmen', 'Student po sesji', 'Zagubiony turysta', 'Pan z psem',
  'Mała dziewczynka', 'Kierowca autobusu', 'Dziadek z gazetą', 'Influencer', 'Tajemniczy nieznajomy',
];

module.exports = {
  data: new SlashCommandBuilder().setName('zebraj').setDescription(`Poproś przechodniów o drobne (co ${shortDuration(COOLDOWNS.zebraj)})`),

  async execute(interaction) {
    if (!(await guardCooldown(interaction, 'zebraj', COOLDOWNS.zebraj))) return;
    const userId = interaction.user.id;
    const giver = pick(GIVERS);

    if (chance(0.2)) {
      return interaction.reply({
        embeds: [embed({ title: '🥺 Zbieranie', color: COLORS.lose, description: `**${giver}** udał, że Cię nie widzi. Dziś bez zysku.` })],
      });
    }

    const earned = applyEarn(userId, randInt(15, 120));
    db.addWallet(userId, earned.amount);
    const lv = db.addXp(userId, randInt(2, 5));
    const notes = earned.notes.length ? `\n${earned.notes.join('\n')}` : '';
    await interaction.reply({
      embeds: [embed({ title: '🥺 Zbieranie', color: COLORS.win, description: `**${giver}** rzucił Ci **${fmt(earned.amount)}**.${notes}${lv.note}` })],
    });
  },
};
