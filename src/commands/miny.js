const { SlashCommandBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, ComponentType } = require('discord.js');
const { takeBet, settle, refund, resultEmbed, guarded, ownerFilter, delay } = require('../lib/game');
const { embed, COLORS, shuffle, fmt } = require('../lib/util');
const protect = require('../lib/protect');

const ROWS = 4;
const COLS = 5;
const TILES = ROWS * COLS;
const HOUSE_EDGE = 0.97;
const MAX_MULT = 1000;

/** Mnożnik po odkryciu k bezpiecznych pól przy m minach (uczciwy mnożnik × 0.97). */
function multiplier(k, m) {
  let x = HOUSE_EDGE;
  for (let i = 0; i < k; i++) x *= (TILES - i) / (TILES - m - i);
  return Math.floor(Math.min(x, MAX_MULT) * 100) / 100;
}

module.exports = {
  multiplier,
  TILES,

  data: new SlashCommandBuilder()
    .setName('miny')
    .setDescription('Saper z kasą: odkrywaj diamenty, unikaj min i wypłać w odpowiednim momencie')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true))
    .addIntegerOption((o) =>
      o.setName('miny').setDescription('Liczba min (1-12, domyślnie 3) – więcej min = większe mnożniki').setMinValue(1).setMaxValue(12),
    ),

  async execute(interaction) {
    const mineCount = interaction.options.getInteger('miny') ?? 3;
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      const mines = new Set(shuffle([...Array(TILES).keys()]).slice(0, mineCount));
      const revealed = new Set();
      const blocked = new Set(); // miny, które zablokowała tarcza (pole jest wyłączone, ale mina nie wybuchła)
      let note = '';
      let finished = false;
      let collector = null;
      const safeTotal = TILES - mineCount;

      const payoutNow = () => Math.floor(bet * multiplier(revealed.size, mineCount));

      const board = (over) => {
        const rows = [];
        for (let r = 0; r < ROWS; r++) {
          const row = new ActionRowBuilder();
          for (let c = 0; c < COLS; c++) {
            const idx = r * COLS + c;
            const btn = new ButtonBuilder().setCustomId(`mine_${idx}`);
            if (revealed.has(idx)) btn.setEmoji('💎').setStyle(ButtonStyle.Success).setDisabled(true);
            else if (blocked.has(idx)) btn.setEmoji('🛡️').setStyle(ButtonStyle.Primary).setDisabled(true);
            else if (over && mines.has(idx)) btn.setEmoji('💣').setStyle(ButtonStyle.Danger).setDisabled(true);
            else if (over) btn.setEmoji('💎').setStyle(ButtonStyle.Secondary).setDisabled(true);
            else btn.setEmoji('❔').setStyle(ButtonStyle.Secondary);
            row.addComponents(btn);
          }
          rows.push(row);
        }
        const cash = new ButtonBuilder()
          .setCustomId('mine_cash')
          .setLabel(revealed.size ? `Wypłać ${fmt(payoutNow())}` : 'Wypłać')
          .setStyle(ButtonStyle.Primary)
          .setDisabled(over || revealed.size === 0);
        rows.push(new ActionRowBuilder().addComponents(cash));
        return rows;
      };

      const status = (lost = false) => {
        const k = revealed.size;
        const next = !lost && k < safeTotal ? `\nNastępne pole: **x${multiplier(k + 1, mineCount).toFixed(2)}**` : '';
        return (
          `💣 Min: **${mineCount}** • 💎 Odkryto: **${k}/${safeTotal}**\n` +
          `Mnożnik: **x${multiplier(k, mineCount).toFixed(2)}** • ${lost ? 'Wypłata' : 'Do wypłaty'}: **${fmt(lost ? 0 : payoutNow())}**${next}`
        );
      };

      // Dane strukturalne dla strony. Plansza idzie z przycisków, a pozycje min poznajesz dopiero po końcu gry.
      const meta = (extra = {}) =>
        interaction.meta?.({
          game: 'miny',
          bet,
          mines: mineCount,
          revealed: revealed.size,
          safeTotal,
          mult: multiplier(revealed.size, mineCount),
          next: revealed.size < safeTotal ? multiplier(revealed.size + 1, mineCount) : null,
          payout: payoutNow(),
          note,
          over: false,
          ...extra,
        });

      const live = () => {
        meta();
        return embed({
          title: '💣 Miny',
          color: COLORS.info,
          description: `${status()}${protect.line(userId, bet)}${note ? `\n${note}` : ''}`,
          footer: `Stawka: ${fmt(bet)} • Odkrywaj pola, aż zechcesz wypłacić`,
        });
      };

      const end = (won, note) => {
        finished = true;
        collector?.stop('done');
        const res = settle(userId, bet, won ? payoutNow() : 0);
        meta({ over: true, won, net: res.net, payout: won ? payoutNow() : 0 });
        return resultEmbed({ title: '💣 Miny', description: `${note}\n\n${status(!won)}`, bet, res, userId });
      };

      await interaction.reply({ embeds: [live()], components: board(false) });
      const message = await interaction.fetchReply();
      collector = message.createMessageComponentCollector({
        componentType: ComponentType.Button,
        filter: ownerFilter(interaction),
        idle: delay(60_000),
        time: delay(10 * 60_000), // twardy limit: token interakcji wygasa po 15 minutach
      });

      collector.on('collect', async (i) => {
        try {
          if (finished) return;
          if (i.customId === 'mine_cash') {
            const e = end(true, '💰 Wypłacasz wygraną w porę!');
            return await i.update({ embeds: [e], components: board(true) });
          }

          const idx = Number(i.customId.split('_')[1]);
          if (revealed.has(idx)) return await i.deferUpdate();

          if (blocked.has(idx)) return await i.deferUpdate();

          if (mines.has(idx)) {
            // Tarcza: klik „się nie liczy”. Pole zostaje wyłączone (znasz już tę minę), a gra trwa.
            const shield = protect.consume(userId, 'tarcza', bet);
            if (shield) {
              blocked.add(idx);
              note = `${shield.emoji} **${shield.name}** zablokowała minę! Pole jest wyłączone, grasz dalej.`;
              return await i.update({ embeds: [live()], components: board(false) });
            }
            const e = end(false, '💥 **BOOM!** Trafiłeś na minę.');
            return await i.update({ embeds: [e], components: board(true) });
          }
          note = '';

          revealed.add(idx);
          if (revealed.size === safeTotal) {
            const e = end(true, '🏆 Odkryłeś wszystkie diamenty! Czyste mistrzostwo.');
            return await i.update({ embeds: [e], components: board(true) });
          }
          await i.update({ embeds: [live()], components: board(false) });
        } catch (err) {
          console.error('Błąd w minach:', err);
          finished = true;
          refund(userId);
          collector.stop('error');
        }
      });

      collector.on('end', async (_collected, reason) => {
        if (finished || reason === 'error') return;
        try {
          // Brak aktywności: jeśli coś odkryto – wypłata, w przeciwnym razie zwrot stawki.
          if (revealed.size > 0) {
            const e = end(true, '⌛ Koniec czasu – wygrana wypłacona automatycznie.');
            await interaction.editReply({ embeds: [e], components: board(true) });
          } else {
            finished = true;
            refund(userId);
            await interaction.editReply({
              embeds: [embed({ title: '💣 Miny', color: COLORS.push, description: '⌛ Gra anulowana z braku aktywności. Stawka wróciła do portfela.' })],
              components: [],
            });
          }
        } catch (err) {
          console.error('Błąd kończenia min:', err);
        }
      });
    });
  },
};
