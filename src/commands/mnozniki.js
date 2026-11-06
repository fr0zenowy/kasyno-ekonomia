const { SlashCommandBuilder } = require('discord.js');
const { summary } = require('../lib/boosts');
const { embed, COLORS } = require('../lib/util');

const times = (n) => `×${Math.round(n * 100) / 100}`;

module.exports = {
  data: new SlashCommandBuilder().setName('mnozniki').setDescription('Twoje mnożniki: aktywne eliksiry, karty VIP i stałe bonusy'),

  async execute(interaction) {
    const { totals, permanent, active, protections } = summary(interaction.user.id);
    await interaction.reply({
      embeds: [
        embed({
          title: '📈 Twoje mnożniki',
          color: COLORS.gold,
          fields: [
            {
              name: '📊 Razem teraz',
              value:
                `💰 Zarobki (praca, żebranie, quizy, przestępstwa): **${times(totals.earn)}**\n` +
                `⭐ XP: **${times(totals.xp)}**\n` +
                `🎒 Łup (ryby, kopalnia, polowanie): **${times(totals.loot)}**\n` +
                `⏰ Czas cooldownów: **${times(totals.cooldown)}**`,
            },
            { name: '⚡ Aktywne eliksiry', value: active.length ? active.join('\n') : 'Brak. Kup eliksir w `/sklep` i użyj go: `/uzyj`.' },
            { name: '📈 Stałe bonusy', value: permanent.length ? permanent.join('\n') : 'Brak. Karty VIP, dyplom i inne znajdziesz w `/sklep`.' },
            {
              name: '🧾 Uzbrojone ochrony',
              value: protections.length ? protections.join('\n') : 'Brak. Polisy i tarcze kupisz w `/sklep kategoria`, uzbroisz je: `/uzyj`.',
            },
          ],
          footer: 'Mnożniki nie dotyczą hazardu ani okradania innych graczy.',
        }),
      ],
    });
  },
};
