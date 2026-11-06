// Silnik gier krokowych (kurczak, wieża): za każdy bezpieczny krok rośnie mnożnik, w dowolnym momencie można wypłacić.
// Konfiguracja gry dostarcza tylko wygląd i sposób losowania kroku; stawka, tarcza, wypłata, limity czasu i zwrot
// stawki przy awarii są tu, wspólne dla wszystkich gier tego typu.
const { ActionRowBuilder, ButtonBuilder, ButtonStyle, ComponentType } = require('discord.js');
const { takeBet, settle, refund, resultEmbed, guarded, ownerFilter, delay } = require('./game');
const { embed, COLORS, fmt } = require('./util');
const protect = require('./protect');

const HOUSE = 0.96; // zwrot gry: każda strategia wypłaty daje średnio 96%
const MAX_MULT = 1000;

/** Mnożnik po n bezpiecznych krokach, gdy każdy przeżywa się z prawdopodobieństwem pSafe. */
const stepMultiplier = (pSafe, n) => (n <= 0 ? 1 : Math.floor(Math.min(HOUSE / Math.pow(pSafe, n), MAX_MULT) * 100) / 100);

/** Ile kroków da się maksymalnie zrobić: do limitu konfiguracji albo do osiągnięcia mnożnika MAX_MULT. */
function stepsLimit(pSafe, limit) {
  let n = 0;
  while (n < limit && HOUSE / Math.pow(pSafe, n + 1) <= MAX_MULT) n++;
  return Math.max(1, n);
}

/**
 * cfg: {
 *   title, emoji, betInput, pSafe, maxSteps,
 *   blockOnShield,               // czy wybór zablokowany tarczą zostaje wyłączony (wieża: tak, kurczak: nie)
 *   newStep(state),              // opcjonalnie: przygotowuje kolejny krok (np. rozmieszcza pułapki)
 *   resolve(state, choice),      // true = bezpieczny krok, false = pułapka
 *   rows(state, over, cashBtn),  // przyciski gry (customId `go_<wybór>`), cashBtn to gotowy przycisk wypłaty
 *   render(state, ctx),          // opis planszy; ctx: { bet, mult, nextMult, payout, maxSteps, over, dead }
 *   deathText(state), shieldText(state, shield, choice)
 * }
 */
async function runStepGame(interaction, cfg) {
  const bet = await takeBet(interaction, cfg.betInput);
  if (!bet) return;
  const userId = interaction.user.id;

  await guarded(userId, async () => {
    const maxSteps = stepsLimit(cfg.pSafe, cfg.maxSteps);
    const state = { steps: 0, note: '', blocked: new Set(), lastChoice: null };
    cfg.newStep?.(state);
    let finished = false;
    let collector = null;

    const mult = (n = state.steps) => stepMultiplier(cfg.pSafe, n);
    const payoutNow = () => Math.floor(bet * mult());
    const title = `${cfg.emoji} ${cfg.title}`;

    const ctx = (extra = {}) => ({ bet, mult: mult(), nextMult: mult(state.steps + 1), payout: payoutNow(), maxSteps, over: false, dead: false, ...extra });
    const cashButton = (over) =>
      new ButtonBuilder()
        .setCustomId('cash')
        .setLabel(state.steps ? `Wypłać ${fmt(payoutNow())}` : 'Wypłać')
        .setEmoji('💰')
        .setStyle(ButtonStyle.Success)
        .setDisabled(over || state.steps === 0);
    const rows = (over) => cfg.rows(state, over, cashButton(over));

    // Dane strukturalne dla strony (zwykły Discord ich nie ma). Tylko to, co gracz i tak widzi: ukrytych pułapek tu nie ma.
    const meta = (extra = {}) =>
      interaction.meta?.({
        game: cfg.game,
        bet,
        steps: state.steps,
        maxSteps,
        mult: mult(),
        nextMult: mult(state.steps + 1),
        payout: payoutNow(),
        over: false,
        dead: false,
        note: state.note,
        ...cfg.meta?.(state, extra),
        ...extra,
      });

    const live = () => {
      meta();
      return embed({
        title,
        color: COLORS.info,
        description: `${cfg.render(state, ctx())}${protect.line(userId, bet)}${state.note ? `\n${state.note}` : ''}`,
        footer: `Stawka: ${fmt(bet)} • zwrot gry ${Math.round(HOUSE * 100)}%`,
      });
    };

    const end = (won, note, extra = {}) => {
      finished = true;
      collector?.stop('done');
      const res = settle(userId, bet, won ? payoutNow() : 0);
      meta({ over: true, dead: !won, won, payout: won ? payoutNow() : 0, net: res.net });
      const text = cfg.render(state, ctx({ over: true, dead: !won, payout: won ? payoutNow() : 0, ...extra }));
      return resultEmbed({ title, description: `${note}\n\n${text}`, bet, res, userId });
    };

    await interaction.reply({ embeds: [live()], components: rows(false) });
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
        if (i.customId === 'cash') {
          const e = end(true, '💰 Wypłacasz wygraną w porę!');
          return await i.update({ embeds: [e], components: rows(true) });
        }
        if (!i.customId.startsWith('go_')) return await i.deferUpdate();

        const choice = i.customId.slice(3);
        if (state.blocked.has(choice)) return await i.deferUpdate();
        state.lastChoice = choice;

        if (!cfg.resolve(state, choice)) {
          // Tarcza: zły klik „się nie liczy”. Gra trwa, a w wieży zablokowane pole zostaje wyłączone.
          const shield = protect.consume(userId, 'tarcza', bet);
          if (shield) {
            if (cfg.blockOnShield) state.blocked.add(choice);
            state.note = cfg.shieldText(state, shield, choice);
            return await i.update({ embeds: [live()], components: rows(false) });
          }
          const e = end(false, cfg.deathText(state));
          return await i.update({ embeds: [e], components: rows(true) });
        }

        state.steps += 1;
        state.note = '';
        state.blocked.clear();
        if (state.steps >= maxSteps) {
          const e = end(true, `🏆 Dotarłeś do końca (limit x${MAX_MULT}) – automatyczna wypłata!`);
          return await i.update({ embeds: [e], components: rows(true) });
        }
        cfg.newStep?.(state);
        await i.update({ embeds: [live()], components: rows(false) });
      } catch (err) {
        console.error(`Błąd w grze „${cfg.title}”:`, err);
        finished = true;
        refund(userId);
        collector.stop('error');
      }
    });

    collector.on('end', async (_collected, reason) => {
      if (finished || reason === 'error') return;
      try {
        // Brak aktywności: jeśli zrobiono choć jeden krok – wypłata, w przeciwnym razie zwrot stawki.
        if (state.steps > 0) {
          const e = end(true, '⌛ Koniec czasu – wygrana wypłacona automatycznie.');
          await interaction.editReply({ embeds: [e], components: rows(true) });
        } else {
          finished = true;
          refund(userId);
          await interaction.editReply({
            embeds: [embed({ title, color: COLORS.push, description: '⌛ Gra anulowana z braku aktywności. Stawka wróciła do portfela.' })],
            components: [],
          });
        }
      } catch (err) {
        console.error(`Błąd kończenia gry „${cfg.title}”:`, err);
      }
    });
  });
}

module.exports = { runStepGame, stepMultiplier, stepsLimit, HOUSE, MAX_MULT };
