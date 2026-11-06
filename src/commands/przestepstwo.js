const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { CRIMES, COOLDOWNS } = require('../lib/data');
const { fmt, embed, COLORS, randInt, chance, pick, shortDuration } = require('../lib/util');
const { guardCooldown } = require('../lib/game');
const { applyEarn } = require('../lib/boosts');

module.exports = {
  data: new SlashCommandBuilder().setName('przestepstwo').setDescription(`Popełnij przestępstwo – wysokie ryzyko, wysoka nagroda (co ${shortDuration(COOLDOWNS.crime)})`),

  async execute(interaction) {
    const userId = interaction.user.id;
    if (!(await guardCooldown(interaction, 'crime', COOLDOWNS.crime))) return;

    const u = db.getUser(userId);
    let p = 0.5;
    const notes = [];
    if (db.takeItem(userId, 'maska', 1)) {
      p += 0.2;
      notes.push('🎭 Założyłeś kominiarkę (+20% szansy, zużyta).');
    }

    const crime = pick(CRIMES);
    if (chance(p)) {
      const earned = applyEarn(userId, randInt(300, 1500) + u.level * 25);
      const loot = earned.amount;
      notes.push(...earned.notes);
      db.addWallet(userId, loot);
      const lv = db.addXp(userId, randInt(12, 25));
      return interaction.reply({
        embeds: [
          embed({
            title: '🦹 Udało się!',
            color: COLORS.win,
            description: `${crime.win}\nZgarniasz **${fmt(loot)}**.${notes.length ? `\n${notes.join('\n')}` : ''}${lv.note}`,
            footer: 'Szansa powodzenia: 50%',
          }),
        ],
      });
    }

    const fine = Math.min(u.wallet, randInt(150, 700));
    db.takeWallet(userId, fine);
    db.addXp(userId, 3);
    await interaction.reply({
      embeds: [
        embed({
          title: '🚔 Wpadka!',
          color: COLORS.lose,
          description: `${crime.lose}\n${fine ? `Płacisz grzywnę **${fmt(fine)}**.` : 'Na szczęście nie miałeś przy sobie nic do stracenia.'}${notes.length ? `\n${notes.join('\n')}` : ''}`,
          footer: 'Wsparcie: kominiarka ze sklepu zwiększa szansę.',
        }),
      ],
    });
  },
};
