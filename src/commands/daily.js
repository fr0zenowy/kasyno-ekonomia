const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { fmt, embed, COLORS, duration, chance } = require('../lib/util');
const { EPHEMERAL } = require('../lib/game');
const { interestTerms } = require('../lib/boosts');

const HOUR = 3600 * 1000;

module.exports = {
  data: new SlashCommandBuilder().setName('daily').setDescription('Odbierz dzienną nagrodę (im dłuższa seria, tym więcej!)'),

  async execute(interaction) {
    const id = interaction.user.id;
    const u = db.getUser(id);
    const now = Date.now();

    const left = u.last_daily + 20 * HOUR - now;
    if (left > 0) {
      return interaction.reply({
        content: `⏰ Dzienną nagrodę odbierzesz za **${duration(left)}**.`,
        flags: EPHEMERAL,
      });
    }

    const streak = u.last_daily && now - u.last_daily <= 48 * HOUR ? u.streak + 1 : 1;
    const base = 500 + Math.min(streak, 30) * 75 + u.level * 50;
    const terms = interestTerms(id);
    const interest = Math.min(Math.floor(u.bank * terms.rate), terms.cap);
    const total = base + interest;

    db.addWallet(id, total);
    db.setDaily(id, now, streak);

    let extra = '';
    if (streak % 30 === 0) {
      db.addItem(id, 'skrzynia_diamentowa', 1);
      extra += '\n💠 **Seria 30 dni!** Dostajesz skrzynię diamentową.';
    } else if (streak % 7 === 0) {
      db.addItem(id, 'skrzynia', 1);
      extra += '\n🎁 **Seria 7 dni!** Dostajesz skrzynię skarbów.';
    }
    if (chance(0.3)) {
      db.addItem(id, 'skrzynka', 1);
      extra += '\n📦 Szczęściarz! Znalazłeś drewnianą skrzynkę.';
    }
    if (extra) extra += '\nOtwórz: `/skrzynie otworz`';
    const lv = db.addXp(id, 20);

    await interaction.reply({
      embeds: [
        embed({
          title: '🎁 Dzienna nagroda',
          color: COLORS.win,
          description:
            `Odebrałeś **${fmt(total)}**!\n` +
            `• nagroda: ${fmt(base)}\n` +
            `• odsetki z banku (${Math.round(terms.rate * 100)}%${terms.savings ? ', konto oszczędnościowe' : ''}): ${fmt(interest)}\n` +
            `🔥 Seria: **${streak}** ${streak === 1 ? 'dzień' : 'dni'} (max bonus po 30)` +
            extra +
            lv.note,
          footer: 'Wróć za ok. 20 godzin, żeby nie stracić serii!',
        }),
      ],
    });
  },
};
