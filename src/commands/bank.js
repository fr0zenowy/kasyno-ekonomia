const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { BANK_UPGRADES, SEJF_BONUS } = require('../lib/data');
const { fmt, num, embed, COLORS, bar } = require('../lib/util');
const { fail } = require('../lib/game');

module.exports = {
  data: new SlashCommandBuilder()
    .setName('bank')
    .setDescription('Twój bank: limit, ulepszenia i ich zakup')
    .addSubcommand((s) => s.setName('info').setDescription('Ile masz w banku i z czego składa się limit'))
    .addSubcommand((s) => s.setName('ulepsz').setDescription('Kup kolejne ulepszenie, które na stałe zwiększa limit banku'))
    .addSubcommand((s) => s.setName('lista').setDescription('Wszystkie ulepszenia banku i ich ceny')),

  async execute(interaction) {
    const userId = interaction.user.id;
    const sub = interaction.options.getSubcommand();

    if (sub === 'ulepsz') {
      const before = db.bankBreakdown(db.getUser(userId)).total;
      const result = db.buyBankUpgrade(userId);
      if (!result.ok && result.reason === 'max') return fail(interaction, '🏆 Masz już wszystkie ulepszenia banku. Nie da się większego!');
      if (!result.ok) {
        const wallet = db.getUser(userId).wallet;
        return fail(
          interaction,
          `❌ ${result.upgrade.emoji} **${result.upgrade.name}** kosztuje ${fmt(result.upgrade.cost)}, a w portfelu masz ${fmt(wallet)}. (Pieniądze z banku wypłacisz przez \`/wyplac\`.)`,
        );
      }
      const after = db.bankBreakdown(db.getUser(userId));
      const next = BANK_UPGRADES[after.tier];
      return interaction.reply({
        embeds: [
          embed({
            title: `${result.upgrade.emoji} Ulepszenie kupione!`,
            color: COLORS.win,
            description:
              `**${result.upgrade.name}** za ${fmt(result.upgrade.cost)}.\n` +
              `Limit banku: ${fmt(before)} → **${fmt(after.total)}** (+${num(result.upgrade.bonus)})\n\n` +
              (next ? `Następne: ${next.emoji} **${next.name}** – ${fmt(next.cost)} (+${num(next.bonus)} limitu)` : '🏆 To było ostatnie ulepszenie!'),
            footer: `Portfel: ${fmt(db.getUser(userId).wallet)}`,
          }),
        ],
      });
    }

    const u = db.getUser(userId);
    const b = db.bankBreakdown(u);

    if (sub === 'lista') {
      const lines = BANK_UPGRADES.map((up, i) => {
        const mark = i < b.tier ? '✅' : i === b.tier ? '👉' : '🔒';
        return `${mark} ${up.emoji} **${up.name}** – ${fmt(up.cost)} → +${num(up.bonus)} limitu`;
      });
      return interaction.reply({
        embeds: [
          embed({
            title: '🏦 Ulepszenia banku',
            color: COLORS.gold,
            description: `${lines.join('\n')}\n\nKupujesz po kolei: \`/bank ulepsz\`. Płacisz z portfela, a limit rośnie na stałe.`,
            footer: `Aktualny limit: ${fmt(b.total)}`,
          }),
        ],
      });
    }

    // info
    const next = BANK_UPGRADES[b.tier];
    const percent = Math.min(100, Math.floor((u.bank / b.total) * 100));
    await interaction.reply({
      embeds: [
        embed({
          title: '🏦 Twój bank',
          color: COLORS.gold,
          description:
            `**${fmt(u.bank)}** z limitu **${fmt(b.total)}**\n${bar(u.bank, b.total)} ${percent}%\n\n` +
            `**Skąd limit?**\n` +
            `• baza: ${fmt(b.base)}\n` +
            `• poziom ${u.level}: +${num(b.levels)}\n` +
            `• ulepszenia (${b.tier}/${BANK_UPGRADES.length}): +${num(b.upgrades)}\n` +
            (b.sejfs ? `• małe sejfy ×${b.sejfs} (+${num(SEJF_BONUS)} każdy): +${num(b.safes)}\n` : '') +
            `\n${next ? `**Następne ulepszenie:** ${next.emoji} ${next.name} – ${fmt(next.cost)} (+${num(next.bonus)} limitu)\n\`/bank ulepsz\`` : '🏆 Masz wszystkie ulepszenia!'}`,
          footer: 'Pieniądze w banku są bezpieczne przed /okradnij, ale w hazardzie liczy się tylko portfel.',
        }),
      ],
    });
  },
};
