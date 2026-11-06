const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { BUSINESSES, BUSINESS_MAX_LEVEL } = require('../lib/data');
const biz = require('../lib/business');
const { fmt, num, embed, COLORS } = require('../lib/util');
const { fail } = require('../lib/game');

module.exports = {
  data: new SlashCommandBuilder()
    .setName('biznes')
    .setDescription('Twoje biznesy: dochód pasywny, który zbierasz do magazynu')
    .addSubcommand((s) => s.setName('lista').setDescription('Twoje biznesy, dochód i ceny'))
    .addSubcommand((s) =>
      s
        .setName('kup')
        .setDescription('Kup biznes albo ulepsz go o poziom (większy dochód)')
        .addStringOption((o) =>
          o
            .setName('biznes')
            .setDescription('Który biznes')
            .setRequired(true)
            .addChoices(...BUSINESSES.map((b) => ({ name: `${b.emoji} ${b.name} – ${num(b.price)} 💰`, value: b.id }))),
        ),
    )
    .addSubcommand((s) => s.setName('zbierz').setDescription('Odbierz zarobki ze wszystkich biznesów')),

  async execute(interaction) {
    const userId = interaction.user.id;
    const sub = interaction.options.getSubcommand();

    if (sub === 'kup') {
      const r = biz.buyOrUpgrade(userId, interaction.options.getString('biznes'));
      if (!r.ok && r.reason === 'max') return fail(interaction, `🏆 ${r.biz.emoji} ${r.biz.name} ma już maksymalny poziom (${BUSINESS_MAX_LEVEL}).`);
      if (!r.ok && r.reason === 'poor') {
        return fail(interaction, `❌ ${r.biz.emoji} **${r.biz.name}** kosztuje ${fmt(r.cost)}, a w portfelu masz ${fmt(db.getUser(userId).wallet)}.`);
      }
      if (!r.ok) return fail(interaction, '❌ Nie ma takiego biznesu.');
      const income = biz.hourlyAt(r.biz, r.level) * biz.incomeMult(userId);
      return interaction.reply({
        embeds: [
          embed({
            title: r.bought ? `${r.biz.emoji} Nowy biznes!` : `${r.biz.emoji} Ulepszono!`,
            color: COLORS.win,
            description:
              `**${r.biz.name}** – poziom **${r.level}/${BUSINESS_MAX_LEVEL}** za ${fmt(r.cost)}.\n` +
              `Dochód: **${fmt(Math.floor(income))}/h**.${r.collected ? `\n💰 Zaległy dochód z poprzedniego poziomu: ${fmt(r.collected)}.` : ''}\n` +
              `Odbierasz go komendą \`/biznes zbierz\`.`,
            footer: `Portfel: ${fmt(db.getUser(userId).wallet)}`,
          }),
        ],
      });
    }

    if (sub === 'zbierz') {
      const p = biz.collect(userId);
      if (!p.parts.length) return fail(interaction, '🏪 Nie masz jeszcze żadnego biznesu. Zobacz `/biznes lista`.');
      const lines = p.parts.filter((x) => x.amount > 0).map((x) => `${x.biz.emoji} ${x.biz.name} (poz. ${x.level}): **${fmt(x.amount)}**${x.full ? ' *(magazyn był pełny)*' : ''}`);
      return interaction.reply({
        embeds: [
          embed({
            title: p.total ? '💼 Zbiór zarobków' : '💼 Jeszcze nic nie zarobiły',
            color: p.total ? COLORS.win : COLORS.push,
            description: p.total ? `${lines.join('\n')}\n\nRazem: **${fmt(p.total)}**` : 'Wróć za chwilę, biznesy dopiero zaczęły zarabiać.',
            footer: `Magazyn mieści ${p.cap} h zarobków • Portfel: ${fmt(db.getUser(userId).wallet)}`,
          }),
        ],
      });
    }

    // lista
    const owned = new Map(db.getBusinesses(userId).map((r) => [r.kind, r]));
    const now = biz.pending(userId);
    const lines = BUSINESSES.map((b) => {
      const row = owned.get(b.id);
      if (!row) return `${b.emoji} **${b.name}** – ${fmt(b.price)} • ${fmt(b.hourly)}/h (zwrot po ok. ${Math.round(b.price / b.hourly)} h)`;
      const maxed = row.level >= BUSINESS_MAX_LEVEL;
      return `${b.emoji} **${b.name}** ✅ poz. **${row.level}/${BUSINESS_MAX_LEVEL}** • ${fmt(Math.floor(biz.hourlyAt(b, row.level) * biz.incomeMult(userId)))}/h${maxed ? ' • max' : ` • ulepszenie ${fmt(biz.upgradeCost(b, row.level))}`}`;
    });
    await interaction.reply({
      embeds: [
        embed({
          title: '🏢 Twoje biznesy',
          color: COLORS.gold,
          description: `${lines.join('\n')}`,
          fields: [
            {
              name: '📊 Podsumowanie',
              value:
                `Dochód: **${fmt(biz.hourlyTotal(userId))}/h** • do odebrania teraz: **${fmt(now.total)}**\n` +
                `Magazyn: **${now.cap} h** (po tym czasie zarobki się nie kumulują)${now.parts.some((x) => x.full) ? '\n⚠️ Któryś biznes ma już pełny magazyn, odbierz: `/biznes zbierz`' : ''}`,
            },
          ],
          footer: 'Kup lub ulepsz: /biznes kup • Menedżer i księgowy ze /sklep zwiększają dochód i magazyn',
        }),
      ],
    });
  },
};
