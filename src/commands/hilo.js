const { SlashCommandBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, ComponentType } = require('discord.js');
const { takeBet, settle, refund, resultEmbed, guarded, ownerFilter, delay } = require('../lib/game');
const { embed, COLORS, randInt, pick, fmt } = require('../lib/util');
const protect = require('../lib/protect');

const SUITS = ['♠️', '♥️', '♦️', '♣️'];
const NAMES = { 11: 'J', 12: 'Q', 13: 'K', 14: 'A' };
const HOUSE = 0.95;
const MAX_PAYOUT_MULT = 1000;

const rankName = (r) => NAMES[r] ?? String(r);
const draw = () => ({ rank: randInt(2, 14), suit: pick(SUITS) });
const cardStr = (c) => `**${rankName(c.rank)}${c.suit}**`;

/** Szanse przy nieskończonej talii: remis (to samo oczko) liczy się jako przegrana. */
const pHigher = (r) => (14 - r) / 13;
const pLower = (r) => (r - 2) / 13;
/**
 * `raw` to iloczyn uczciwych mnożników (1/p) z całej serii. Prowizja domu (5%) jest doliczana raz od całości,
 * a nie przy każdej karcie – inaczej zwrot topniałby z każdym kolejnym trafieniem.
 */
const totalMult = (raw) => Math.floor(HOUSE * raw * 100) / 100;

module.exports = {
  pHigher,
  pLower,
  totalMult,

  data: new SlashCommandBuilder()
    .setName('hilo')
    .setDescription('Wyżej czy niżej? Zgaduj kolejne karty, buduj serię i wypłać w porę')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true)),

  async execute(interaction) {
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      let card = draw();
      let raw = 1;
      let streak = 0;
      let finished = false;
      let collector = null;
      let note = '';
      const history = [];

      const currentMult = () => (streak ? totalMult(raw) : 1);
      const payoutNow = () => Math.floor(bet * currentMult());

      const buttons = (over) => {
        const hi = pHigher(card.rank);
        const lo = pLower(card.rank);
        return new ActionRowBuilder().addComponents(
          new ButtonBuilder()
            .setCustomId('hilo_high')
            .setLabel(hi > 0 ? `Wyżej (→ x${totalMult(raw / hi).toFixed(2)})` : 'Wyżej')
            .setEmoji('⬆️')
            .setStyle(ButtonStyle.Primary)
            .setDisabled(over || hi === 0),
          new ButtonBuilder()
            .setCustomId('hilo_low')
            .setLabel(lo > 0 ? `Niżej (→ x${totalMult(raw / lo).toFixed(2)})` : 'Niżej')
            .setEmoji('⬇️')
            .setStyle(ButtonStyle.Primary)
            .setDisabled(over || lo === 0),
          new ButtonBuilder()
            .setCustomId('hilo_cash')
            .setLabel(streak ? `Wypłać ${fmt(payoutNow())}` : 'Wypłać')
            .setEmoji('💰')
            .setStyle(ButtonStyle.Success)
            .setDisabled(over || streak === 0),
        );
      };

      const status = (lost = false) =>
        `Aktualna karta: ${cardStr(card)}\n` +
        `Seria: **${streak}** • Mnożnik: **x${currentMult().toFixed(2)}** • ${lost ? 'Wypłata' : 'Do wypłaty'}: **${fmt(lost ? 0 : payoutNow())}**` +
        (history.length ? `\nWcześniej: ${history.slice(-6).join(' → ')}` : '') +
        (lost ? '' : '\n*Ta sama figura co poprzednia karta = przegrana.*');

      // Dane strukturalne dla strony: tylko jawna karta, seria i widoczne mnożniki (następnej karty nikt nie zna).
      const meta = (extra = {}) =>
        interaction.meta?.({
          game: 'hilo',
          bet,
          card,
          streak,
          mult: currentMult(),
          payout: payoutNow(),
          history: history.slice(-8),
          high: pHigher(card.rank) > 0 ? totalMult(raw / pHigher(card.rank)) : 0,
          low: pLower(card.rank) > 0 ? totalMult(raw / pLower(card.rank)) : 0,
          note,
          over: false,
          ...extra,
        });

      const live = () => {
        meta();
        return embed({
          title: '🎴 Wyżej czy niżej?',
          color: COLORS.info,
          description: `${status()}${protect.line(userId, bet)}${note ? `\n${note}` : ''}`,
          footer: `Stawka: ${fmt(bet)}`,
        });
      };

      const end = (won, note) => {
        finished = true;
        collector?.stop('done');
        const res = settle(userId, bet, won ? payoutNow() : 0);
        meta({ over: true, won, net: res.net, payout: won ? payoutNow() : 0 });
        return resultEmbed({ title: '🎴 Wyżej czy niżej?', description: `${note}\n\n${status(!won)}`, bet, res, userId });
      };

      await interaction.reply({ embeds: [live()], components: [buttons(false)] });
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
          if (i.customId === 'hilo_cash') {
            const e = end(true, '💰 Wypłacasz wygraną!');
            return await i.update({ embeds: [e], components: [buttons(true)] });
          }

          const guessHigh = i.customId === 'hilo_high';
          const p = guessHigh ? pHigher(card.rank) : pLower(card.rank);
          if (p === 0) return await i.deferUpdate();

          const next = draw();
          const won = guessHigh ? next.rank > card.rank : next.rank < card.rank;
          if (!won) {
            // Tarcza: zły typ „się nie liczy” – karta zostaje ta sama i zgadujesz jeszcze raz.
            const shield = protect.consume(userId, 'tarcza', bet);
            if (shield) {
              note = `${shield.emoji} **${shield.name}** anulowała zły typ (wypadłoby ${cardStr(next)}). Karta się nie zmienia, zgaduj dalej.`;
              return await i.update({ embeds: [live()], components: [buttons(false)] });
            }
            history.push(cardStr(card));
            card = next;
            const e = end(false, `❌ Wypadło ${cardStr(next)} – pudło!`);
            return await i.update({ embeds: [e], components: [buttons(true)] });
          }

          note = '';
          history.push(cardStr(card));
          raw /= p;
          streak += 1;
          card = next;
          if (currentMult() >= MAX_PAYOUT_MULT) {
            const e = end(true, `🏆 Osiągnąłeś limit x${MAX_PAYOUT_MULT} – automatyczna wypłata!`);
            return await i.update({ embeds: [e], components: [buttons(true)] });
          }
          await i.update({ embeds: [live()], components: [buttons(false)] });
        } catch (err) {
          console.error('Błąd w hilo:', err);
          finished = true;
          refund(userId);
          collector.stop('error');
        }
      });

      collector.on('end', async (_collected, reason) => {
        if (finished || reason === 'error') return;
        try {
          if (streak > 0) {
            const e = end(true, '⌛ Koniec czasu – wygrana wypłacona automatycznie.');
            await interaction.editReply({ embeds: [e], components: [buttons(true)] });
          } else {
            finished = true;
            refund(userId);
            await interaction.editReply({
              embeds: [embed({ title: '🎴 Wyżej czy niżej?', color: COLORS.push, description: '⌛ Gra anulowana z braku aktywności. Stawka wróciła do portfela.' })],
              components: [],
            });
          }
        } catch (err) {
          console.error('Błąd kończenia hilo:', err);
        }
      });
    });
  },
};
