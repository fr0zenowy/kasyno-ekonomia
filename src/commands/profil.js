const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { JOBS, ITEMS, SHOP_CATEGORIES, FARM_SLOTS } = require('../lib/data');
const biz = require('../lib/business');
const farm = require('../lib/farm');
const { fmt, num, embed, COLORS, bar } = require('../lib/util');
const { EPHEMERAL } = require('../lib/game');

module.exports = {
  data: new SlashCommandBuilder()
    .setName('profil')
    .setDescription('Twój profil: poziom, majątek, statystyki hazardu')
    .addUserOption((o) => o.setName('uzytkownik').setDescription('Czyj profil pokazać')),

  async execute(interaction) {
    const target = interaction.options.getUser('uzytkownik') ?? interaction.user;
    if (target.bot) return interaction.reply({ content: '🤖 Boty nie mają profili.', flags: EPHEMERAL });

    const u = db.getUser(target.id);
    const need = db.xpNeeded(u.level);
    const job = JOBS.find((j) => j.id === u.job) ?? JOBS[0];
    const trophy = db.itemQty(target.id, 'trofeum') > 0 ? ' 🏆' : '';
    const net = u.total_won - u.total_lost;

    // Dodatkowe pola pokazujemy tylko wtedy, gdy gracz coś ma.
    const extra = [];
    const owned = db.inventory(target.id).filter((r) => ITEMS[r.item]);
    const luxury = owned.filter((r) => ITEMS[r.item].type === 'luxury');
    if (luxury.length) {
      const value = luxury.reduce((s, r) => s + ITEMS[r.item].price * r.qty, 0);
      extra.push({ name: '💎 Kolekcja', value: `${luxury.map((r) => ITEMS[r.item].emoji).join(' ')}\nWartość: ${fmt(value)}` });
    }
    const petIds = SHOP_CATEGORIES.find((c) => c.id === 'zwierzaki').items;
    const pets = owned.filter((r) => petIds.includes(r.item));
    if (pets.length) extra.push({ name: '🐾 Zwierzaki', value: pets.map((r) => `${ITEMS[r.item].emoji} ${ITEMS[r.item].name}`).join(', '), inline: true });
    const businesses = db.getBusinesses(target.id);
    if (businesses.length) {
      extra.push({ name: '🏢 Biznesy', value: `${businesses.length} • ${fmt(biz.hourlyTotal(target.id))}/h`, inline: true });
    }
    const farmed = farm.status(target.id).filter((s) => s.state !== 'empty').length;
    if (farmed) extra.push({ name: '🌾 Farma', value: `${farmed}/${FARM_SLOTS} pól zajętych`, inline: true });

    await interaction.reply({
      embeds: [
        embed({
          title: `${target.displayName}${trophy}`,
          color: COLORS.info,
          thumbnail: target.displayAvatarURL(),
          fields: [
            { name: '⭐ Poziom', value: `**${u.level}**\n${bar(u.xp, need)} ${num(u.xp)}/${num(need)} XP` },
            { name: '💎 Majątek', value: `${fmt(u.wallet + u.bank)}\n*portfel ${fmt(u.wallet)} • bank ${fmt(u.bank)}*`, inline: true },
            { name: '🏆 Ranking', value: `#${db.rankOf(target.id)}`, inline: true },
            { name: '💼 Praca', value: `${job.emoji} ${job.name}`, inline: true },
            { name: '🔥 Seria daily', value: `${u.streak}`, inline: true },
            {
              name: '🎰 Hazard',
              value:
                `Gier: **${num(u.games)}**\n` +
                `Wygrane: ${fmt(u.total_won)}\n` +
                `Przegrane: ${fmt(u.total_lost)}\n` +
                `Bilans: **${net >= 0 ? '+' : ''}${fmt(net)}**\n` +
                `Rekord wygranej: ${fmt(u.biggest_win)}`,
            },
            ...extra,
          ],
        }),
      ],
    });
  },
};
