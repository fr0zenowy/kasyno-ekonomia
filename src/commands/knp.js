const { SlashCommandBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, ComponentType } = require('discord.js');
const { takeBet, settle, refund, resultEmbed, guarded, ownerFilter, delay } = require('../lib/game');
const { embed, COLORS, pick, fmt } = require('../lib/util');

const MOVES = {
  kamien: { e: '🪨', name: 'Kamień', beats: 'nozyce' },
  papier: { e: '📄', name: 'Papier', beats: 'kamien' },
  nozyce: { e: '✂️', name: 'Nożyce', beats: 'papier' },
};
const WIN_MULT = 1.9;

module.exports = {
  MOVES,

  data: new SlashCommandBuilder()
    .setName('knp')
    .setDescription('Kamień, papier, nożyce z botem o stawkę (wygrana x1.9)')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true)),

  async execute(interaction) {
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      let finished = false;
      const row = new ActionRowBuilder().addComponents(
        ...Object.entries(MOVES).map(([id, m]) =>
          new ButtonBuilder().setCustomId(`knp_${id}`).setLabel(m.name).setEmoji(m.e).setStyle(ButtonStyle.Primary),
        ),
      );

      interaction.meta?.({ game: 'knp', mult: WIN_MULT, bet });
      await interaction.reply({
        embeds: [embed({ title: '✊ Kamień, papier, nożyce', color: COLORS.info, description: 'Wybierz swój ruch! Masz 30 sekund.', footer: `Stawka: ${fmt(bet)}` })],
        components: [row],
      });
      const message = await interaction.fetchReply();
      const collector = message.createMessageComponentCollector({
        componentType: ComponentType.Button,
        filter: ownerFilter(interaction),
        time: delay(30_000),
        max: 1,
      });

      collector.on('collect', async (i) => {
        try {
          finished = true;
          const mine = i.customId.split('_')[1];
          const bot = pick(Object.keys(MOVES));
          let payout = 0;
          let text;
          if (mine === bot) {
            payout = bet;
            text = 'Remis!';
          } else if (MOVES[mine].beats === bot) {
            payout = Math.floor(bet * WIN_MULT);
            text = 'Twój ruch wygrywa!';
          } else {
            text = 'Bot wygrywa tę rundę.';
          }
          const res = settle(userId, bet, payout);
          interaction.meta?.({ game: 'knp', mine, bot, won: payout > bet, draw: payout === bet, mult: WIN_MULT, bet, net: res.net });
          await i.update({
            embeds: [
              resultEmbed({
                title: '✊ Kamień, papier, nożyce',
                description: `Ty: ${MOVES[mine].e} **${MOVES[mine].name}**\nBot: ${MOVES[bot].e} **${MOVES[bot].name}**\n\n${text}`,
                bet,
                res,
                userId,
              }),
            ],
            components: [],
          });
        } catch (err) {
          console.error('Błąd w knp:', err);
          refund(userId);
        }
      });

      collector.on('end', async () => {
        if (finished) return;
        finished = true;
        refund(userId);
        await interaction
          .editReply({
            embeds: [embed({ title: '✊ Kamień, papier, nożyce', color: COLORS.push, description: '⌛ Nie wybrałeś ruchu. Stawka wróciła do portfela.' })],
            components: [],
          })
          .catch(() => {});
      });
    });
  },
};
