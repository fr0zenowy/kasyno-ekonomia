const { SlashCommandBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, ComponentType } = require('discord.js');
const { takeBet, settle, refund, resultEmbed, guarded, ownerFilter, delay } = require('../lib/game');
const db = require('../lib/db');
const { embed, COLORS, fmt } = require('../lib/util');

const TICK_MS = 1400;
const GROWTH = 1.11; // mnożnik rośnie o 11% co tick
const HOUSE = 0.97; // P(crash >= x) = 0.97 / x, więc wypłata przy dowolnym mnożniku daje średnio 97% zwrotu
const MAX_CRASH = 10000;
const HISTORY_SIZE = 8;

function crashPoint() {
  const r = Math.random();
  const p = Math.floor((100 * HOUSE) / (1 - r)) / 100;
  return Math.min(Math.max(p, 1), MAX_CRASH);
}

const multAt = (tick, growth = GROWTH) => Math.floor(Math.pow(growth, tick) * 100) / 100;

/** Szansa, że runda dojdzie do mnożnika x przed crashem. Tak wygląda rozkład: x2 ≈ 48.5%, x10 ≈ 9.7%, x100 ≈ 0.97%. */
const reachChance = (x) => Math.min(1, HOUSE / x);

const history = () => JSON.parse(db.kvGet('crash_history', '[]'));
const pushHistory = (value) => db.kvSet('crash_history', JSON.stringify([value, ...history()].slice(0, HISTORY_SIZE)));
const dot = (v) => (v < 2 ? '🔴' : v < 10 ? '🟡' : '🟢');
const historyLine = () => {
  const h = history();
  return h.length ? `\nOstatnie rundy: ${h.map((v) => `${dot(v)}${v.toFixed(2)}`).join(' ')}` : '';
};
const odds = () =>
  `Szanse dojścia: x2 ${(reachChance(2) * 100).toFixed(1)}% · x10 ${(reachChance(10) * 100).toFixed(1)}% · x100 ${(reachChance(100) * 100).toFixed(2)}%`;

module.exports = {
  crashPoint,
  multAt,
  reachChance,
  MAX_CRASH,

  data: new SlashCommandBuilder()
    .setName('crash')
    .setDescription('Mnożnik rośnie, dopóki nie „crashnie”. Wypłać w porę!')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true))
    .addNumberOption((o) =>
      o.setName('auto').setDescription('Automatyczna wypłata przy tym mnożniku (np. 2.5)').setMinValue(1.1).setMaxValue(MAX_CRASH),
    ),

  async execute(interaction) {
    const auto = interaction.options.getNumber('auto');
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      const crash = crashPoint();
      // Na stronie nie ma limitów edycji wiadomości, więc ta sama krzywa rośnie płynnie: 14× częstsze, 14× mniejsze skoki.
      const tickMs = interaction.fromWeb ? 100 : TICK_MS;
      const growth = interaction.fromWeb ? Math.pow(GROWTH, tickMs / TICK_MS) : GROWTH;
      let tick = 0;
      let mult = 1;
      let ended = false;
      let timer = null;
      let collector = null;

      // `mult` to ostatni mnożnik, który gracz faktycznie widzi na ekranie – po nim liczona jest wypłata.
      const row = (disabled = false, m = mult) =>
        new ActionRowBuilder().addComponents(
          new ButtonBuilder()
            .setCustomId('crash_cash')
            .setLabel(`Wypłać ${fmt(Math.floor(bet * m))}`)
            .setEmoji('💸')
            .setStyle(ButtonStyle.Success)
            .setDisabled(disabled),
        );

      const rocket = (m) => (m < 2 ? '🚀' : m < 5 ? '🚀🔥' : m < 20 ? '🚀🔥🔥' : '🌌🚀🔥🔥🔥');
      // Dane strukturalne dla strony. Punkt crashu jest tajny aż do końca rundy, inaczej dałoby się wypłacić tuż przed nim.
      const meta = (extra = {}) => interaction.meta?.({ game: 'crash', bet, mult, auto, history: history(), over: false, ...extra });

      const live = (m = mult) => {
        meta({ mult: m });
        return embed({
          title: '🚀 Crash',
          color: COLORS.info,
          description: `${rocket(m)}\n# x${m.toFixed(2)}\nPotencjalna wypłata: **${fmt(Math.floor(bet * m))}**${auto ? `\n🤖 Auto-wypłata przy **x${auto.toFixed(2)}**` : ''}${historyLine()}`,
          footer: `Stawka: ${fmt(bet)} • ${odds()}`,
        });
      };

      const finish = (cashedAt) => {
        ended = true;
        clearInterval(timer);
        collector?.stop('done');
        const won = cashedAt !== null;
        pushHistory(crash);
        const res = settle(userId, bet, won ? Math.floor(bet * cashedAt) : 0);
        meta({ over: true, won, crash, cashedAt, mult: won ? cashedAt : crash, net: res.net });
        const text = won
          ? `💸 Wypłacasz przy **x${cashedAt.toFixed(2)}**! (Crash nastąpił dopiero przy x${crash.toFixed(2)})`
          : `💥 **CRASH przy x${crash.toFixed(2)}!** Nie zdążyłeś wypłacić.`;
        return resultEmbed({ title: '🚀 Crash', description: `${text}${historyLine()}`, bet, res, userId });
      };

      await interaction.reply({ embeds: [live()], components: [row()] });
      const message = await interaction.fetchReply();
      collector = message.createMessageComponentCollector({
        componentType: ComponentType.Button,
        filter: ownerFilter(interaction),
        time: delay(240_000),
      });

      collector.on('collect', async (i) => {
        try {
          if (ended) return await i.deferUpdate().catch(() => {});
          const e = finish(mult);
          await i.update({ embeds: [e], components: [row(true)] });
        } catch (err) {
          console.error('Błąd w crashu:', err);
          if (!ended) {
            ended = true;
            clearInterval(timer);
            refund(userId);
          }
        }
      });

      collector.on('end', async (_collected, reason) => {
        if (ended || reason === 'done') return;
        clearInterval(timer);
        try {
          const e = finish(mult);
          await interaction.editReply({ embeds: [e], components: [row(true)] });
        } catch (err) {
          console.error('Błąd kończenia crasha:', err);
        }
      });

      let busy = false;
      timer = setInterval(async () => {
        if (ended || busy) return;
        busy = true;
        try {
          tick += 1;
          const m = multAt(tick, growth);
          if (m >= crash) {
            const e = finish(null);
            await interaction.editReply({ embeds: [e], components: [row(true)] });
          } else if (auto && m >= auto) {
            const e = finish(auto);
            await interaction.editReply({ embeds: [e], components: [row(true)] });
          } else {
            await interaction.editReply({ embeds: [live(m)], components: [row(false, m)] });
            if (!ended) mult = m;
          }
        } catch (err) {
          console.error('Błąd animacji crasha:', err);
          if (!ended) {
            ended = true;
            clearInterval(timer);
            collector.stop('error');
            refund(userId);
          }
        } finally {
          busy = false;
        }
      }, delay(tickMs));
    });
  },
};
