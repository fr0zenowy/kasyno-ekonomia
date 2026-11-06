const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const lottery = require('../lib/lottery');
const { fmt, num, embed, COLORS } = require('../lib/util');
const { fail } = require('../lib/game');

const { TICKET_PRICE, MAX_TICKETS_PER_USER } = lottery;

module.exports = {
  data: new SlashCommandBuilder()
    .setName('loteria')
    .setDescription('Codzienna loteria – im więcej biletów, tym większa szansa na całą pulę')
    .addSubcommand((s) =>
      s
        .setName('kup')
        .setDescription(`Kup bilety (${TICKET_PRICE} monet za sztukę)`)
        .addIntegerOption((o) =>
          o.setName('ilosc').setDescription('Ile biletów').setRequired(true).setMinValue(1).setMaxValue(MAX_TICKETS_PER_USER),
        ),
    )
    .addSubcommand((s) => s.setName('info').setDescription('Pula, Twoje bilety i czas do losowania')),

  async execute(interaction) {
    const userId = interaction.user.id;
    const sub = interaction.options.getSubcommand();

    if (sub === 'kup') {
      const qty = interaction.options.getInteger('ilosc');
      const mine = db.lotteryTickets(userId);
      if (mine + qty > MAX_TICKETS_PER_USER) {
        return fail(interaction, `❌ Możesz mieć maksymalnie ${MAX_TICKETS_PER_USER} biletów (masz już ${mine}).`);
      }
      const cost = qty * TICKET_PRICE;
      if (db.getUser(userId).wallet < cost) return fail(interaction, `❌ Potrzebujesz ${fmt(cost)} w portfelu.`);

      db.tx(() => {
        db.takeWallet(userId, cost);
        db.lotteryAdd(userId, qty);
      });
      const pool = lottery.poolInfo();
      const have = mine + qty;
      return interaction.reply({
        embeds: [
          embed({
            title: '🎟️ Bilety kupione!',
            color: COLORS.win,
            description:
              `Kupiłeś **${qty}** ${qty === 1 ? 'bilet' : 'biletów'} za ${fmt(cost)}.\n` +
              `Masz ich razem **${have}** (szansa: **${((have / pool.tickets) * 100).toFixed(1)}%**).\n` +
              `Aktualna pula dla zwycięzcy: **${fmt(pool.pot)}**.\nLosowanie <t:${Math.floor(lottery.nextDraw() / 1000)}:R>.`,
          }),
        ],
      });
    }

    // info
    const pool = lottery.poolInfo();
    const mine = db.lotteryTickets(userId);
    const last = lottery.lastWinner();
    const odds = pool.tickets ? `${((mine / pool.tickets) * 100).toFixed(1)}%` : '–';
    await interaction.reply({
      embeds: [
        embed({
          title: '🎟️ Loteria',
          color: COLORS.gold,
          description:
            `💰 Pula dla zwycięzcy: **${fmt(pool.pot)}**\n` +
            `🎫 Sprzedanych biletów: **${num(pool.tickets)}** (graczy: ${pool.players})\n` +
            `🧾 Twoje bilety: **${mine}** (szansa: ${odds})\n` +
            `⏰ Losowanie: <t:${Math.floor(lottery.nextDraw() / 1000)}:R>\n` +
            `🛒 Cena biletu: ${fmt(TICKET_PRICE)} • limit ${MAX_TICKETS_PER_USER} na osobę` +
            (last ? `\n\n🏆 Ostatni zwycięzca: <@${last.winner}> – **${fmt(last.amount)}**` : ''),
        }),
      ],
    });
  },
};
