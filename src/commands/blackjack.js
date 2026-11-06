const { SlashCommandBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, ComponentType } = require('discord.js');
const db = require('../lib/db');
const { takeBet, increaseBet, settle, refund, resultEmbed, guarded, ownerFilter, delay, EPHEMERAL } = require('../lib/game');
const { embed, COLORS, shuffle, fmt, sleep, randInt } = require('../lib/util');
const protect = require('../lib/protect');
const { safeEdit } = require('../lib/anim');

const SUITS = ['♠️', '♥️', '♦️', '♣️'];
const RANKS = ['A', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'J', 'Q', 'K'];

const newDeck = () => shuffle(SUITS.flatMap((s) => RANKS.map((r) => ({ r, s }))));

function handValue(cards) {
  let total = 0;
  let aces = 0;
  for (const c of cards) {
    if (c.r === 'A') {
      aces += 1;
      total += 11;
    } else if (['J', 'Q', 'K'].includes(c.r)) total += 10;
    else total += Number(c.r);
  }
  while (total > 21 && aces > 0) {
    total -= 10;
    aces -= 1;
  }
  return total;
}

const isBlackjack = (cards) => cards.length === 2 && handValue(cards) === 21;
const show = (cards, hideSecond = false) => cards.map((c, i) => (hideSecond && i === 1 ? '🂠' : `**${c.r}${c.s}**`)).join('  ');

module.exports = {
  handValue,
  isBlackjack,

  data: new SlashCommandBuilder()
    .setName('blackjack')
    .setDescription('Oczko: ugraj 21 i pokonaj krupiera (blackjack płaci 3:2)')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true)),

  async execute(interaction) {
    let bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      const deck = newDeck();
      const player = [deck.pop(), deck.pop()];
      const dealer = [deck.pop(), deck.pop()];
      let finished = false;
      let firstMove = true;
      let collector = null;
      let note = '';

      let dealerDrew = 0; // ile kart krupier dobrał po odkryciu (do animacji)

      // `shown` pozwala pokazać tylko pierwsze karty krupiera – tak powstaje animacja dobierania.
      const table = (reveal, shown = dealer.length) => {
        const cards = dealer.slice(0, shown);
        return (
          `**Twoje karty** (${handValue(player)}):\n${show(player)}\n\n` +
          `**Krupier** (${reveal ? handValue(cards) : handValue([dealer[0]])}${reveal ? '' : '+?'}):\n${show(cards, !reveal)}`
        );
      };

      const buttons = () => {
        const canDouble = firstMove && db.getUser(userId).wallet >= bet;
        return new ActionRowBuilder().addComponents(
          new ButtonBuilder().setCustomId('bj_hit').setLabel('Dobierz').setEmoji('🃏').setStyle(ButtonStyle.Primary),
          new ButtonBuilder().setCustomId('bj_stand').setLabel('Pas').setEmoji('✋').setStyle(ButtonStyle.Secondary),
          new ButtonBuilder().setCustomId('bj_double').setLabel('Podwój').setEmoji('💰').setStyle(ButtonStyle.Success).setDisabled(!canDouble),
        );
      };

      // Dane strukturalne dla strony. Zakrytej karty krupiera tu nie ma, dopóki gra się nie skończy.
      const meta = (extra = {}) =>
        interaction.meta?.({
          game: 'blackjack',
          bet,
          player: player.map((c) => ({ ...c })),
          dealer: [{ ...dealer[0] }],
          dealerHidden: true,
          playerValue: handValue(player),
          dealerValue: handValue([dealer[0]]),
          canDouble: firstMove && db.getUser(userId).wallet >= bet,
          note,
          over: false,
          ...extra,
        });

      const live = () => {
        meta();
        return embed({
          title: '🃏 Blackjack',
          color: COLORS.info,
          description: `${table(false)}${protect.line(userId, bet)}${note ? `\n${note}` : ''}`,
          footer: `Stawka: ${fmt(bet)}`,
        });
      };

      const finish = (kind, note) => {
        finished = true;
        collector?.stop('done');
        const payout = { blackjack: Math.floor(bet * 2.5), win: bet * 2, push: bet, lose: 0 }[kind];
        const res = settle(userId, bet, payout);
        meta({
          dealer: dealer.map((c) => ({ ...c })),
          dealerHidden: false,
          dealerValue: handValue(dealer),
          dealerDrew,
          over: true,
          kind,
          net: res.net,
          payout: res.payout,
          note: '',
        });
        return resultEmbed({ title: '🃏 Blackjack', description: `${table(true)}\n\n${note}`, bet, res, userId });
      };

      // Krupier dobiera do 17 i stoi.
      const stand = () => {
        const before = dealer.length;
        while (handValue(dealer) < 17) dealer.push(deck.pop());
        dealerDrew = dealer.length - before;
        const p = handValue(player);
        const d = handValue(dealer);
        if (d > 21) return finish('win', `Krupier przekroczył 21 (${d})!`);
        if (p > d) return finish('win', `Masz ${p} przeciw ${d}.`);
        if (p === d) return finish('push', `Remis: ${p} do ${d}.`);
        return finish('lose', `Krupier ma ${d}, a Ty ${p}.`);
      };

      // Gra jest już rozliczona (finish), więc animacja to tylko pokaz: krupier odkrywa kartę i dobiera po jednej.
      const deliver = async (result, send) => {
        if (dealerDrew === 0) return send({ embeds: [result], components: [] });
        const frame = (shown, text) =>
          embed({ title: '🃏 Blackjack', color: COLORS.info, description: `${table(true, shown)}\n\n${text}`, footer: `Stawka: ${fmt(bet)}` });
        await send({ embeds: [frame(2, '🎬 Krupier odkrywa karty...')], components: [] });
        for (let n = 3; n <= dealer.length; n++) {
          await sleep(delay(900));
          await safeEdit(interaction, { embeds: [frame(n, '🃏 Krupier dobiera kartę...')] });
        }
        await sleep(delay(900));
        await interaction.editReply({ embeds: [result] });
      };

      // Naturalny blackjack rozstrzyga się od razu. Najpierw odpowiedź, dopiero potem rozliczenie:
      // gdyby Discord odmówił, stawka wraca (guarded), a nie przepada razem z niewidocznym wynikiem.
      if (isBlackjack(player) || isBlackjack(dealer)) {
        await interaction.reply({ embeds: [embed({ title: '🃏 Blackjack', color: COLORS.info, description: '🃏 Rozdaję karty...', footer: `Stawka: ${fmt(bet)}` })] });
        await sleep(delay(900));
        let e;
        if (isBlackjack(player) && isBlackjack(dealer)) e = finish('push', 'Obaj macie blackjacka – remis!');
        else if (isBlackjack(player)) e = finish('blackjack', '🔥 **BLACKJACK!** Wypłata 3:2.');
        else e = finish('lose', 'Krupier ma blackjacka.');
        await interaction.editReply({ embeds: [e] });
        return;
      }

      await interaction.reply({ embeds: [live()], components: [buttons()] });
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
          let result = null;

          note = '';
          if (i.customId === 'bj_hit') {
            player.push(deck.pop());
            const v = handValue(player);
            if (v > 21) {
              // Tarcza: dobranie „się nie liczy”. Karta wraca do talii w losowe miejsce, a Ty możesz spasować albo spróbować ponownie.
              const shield = protect.consume(userId, 'tarcza', bet);
              if (shield) {
                deck.splice(randInt(0, deck.length), 0, player.pop());
                note = `${shield.emoji} **${shield.name}** anulowała dobranie, które by Cię spaliło. Karta wróciła do talii.`;
                return await i.update({ embeds: [live()], components: [buttons()] });
              }
              firstMove = false;
              result = finish('lose', `Przekroczyłeś 21 (${v})!`);
            } else {
              firstMove = false;
              if (v === 21) result = stand();
            }
          } else if (i.customId === 'bj_stand') {
            result = stand();
          } else if (i.customId === 'bj_double') {
            if (!firstMove || !increaseBet(userId, bet)) {
              return await i.reply({ content: '❌ Nie możesz teraz podwoić (za mało monet albo to nie pierwszy ruch).', flags: EPHEMERAL });
            }
            bet *= 2;
            firstMove = false;
            player.push(deck.pop());
            const v = handValue(player);
            if (v > 21) {
              // Po podwojeniu dostajesz jedną kartę. Tarcza oddaje ją do talii i krupier gra od razu.
              const shield = protect.consume(userId, 'tarcza', bet);
              if (shield) {
                deck.splice(randInt(0, deck.length), 0, player.pop());
                result = stand();
              } else {
                result = finish('lose', `Podwoiłeś i przekroczyłeś 21 (${v})!`);
              }
            } else {
              result = stand();
            }
          }

          if (result) await deliver(result, (payload) => i.update(payload));
          else await i.update({ embeds: [live()], components: [buttons()] });
        } catch (err) {
          console.error('Błąd w blackjacku:', err);
          finished = true;
          refund(userId);
          collector.stop('error');
        }
      });

      collector.on('end', async (_collected, reason) => {
        if (finished || reason === 'error') return;
        try {
          const result = stand();
          await deliver(result, (payload) => interaction.editReply(payload));
        } catch (err) {
          console.error('Błąd kończenia blackjacka:', err);
        }
      });
    });
  },
};
