const { SlashCommandBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, ComponentType } = require('discord.js');
const db = require('../lib/db');
const { COOLDOWNS } = require('../lib/data');
const { fmt, embed, COLORS, chance, duration, sleep } = require('../lib/util');
const { safeEdit } = require('../lib/anim');
const { fail, delay, EPHEMERAL } = require('../lib/game');

const STAKE = 500;
const MAX_PLAYERS = 10;
const JOIN_TIME = 45_000;

// Jeden napad naraz na kanał
const running = new Set();

const successChance = (n) => Math.min(0.7, 0.25 + 0.07 * (n - 1));

module.exports = {
  data: new SlashCommandBuilder().setName('napad').setDescription(`Zorganizuj napad na bank z innymi graczami (wpisowe ${STAKE} monet)`),

  async execute(interaction) {
    const starter = interaction.user;
    const channelKey = interaction.channelId;
    if (running.has(channelKey)) return fail(interaction, '🚨 Na tym kanale trwa już przygotowanie napadu – dołącz do niego!');

    const left = db.cooldownLeft(starter.id, 'heist');
    if (left > 0) return fail(interaction, `⏰ Policja jeszcze Cię szuka. Następny napad za **${duration(left)}**.`);
    if (!db.takeWallet(starter.id, STAKE)) return fail(interaction, `❌ Wpisowe to ${fmt(STAKE)}, a masz za mało w portfelu.`);
    db.setCooldown(starter.id, 'heist', db.scaledCooldown(starter.id, COOLDOWNS.heist));
    running.add(channelKey);

    const players = [starter.id];
    const endsAt = Date.now() + JOIN_TIME;

    const lobby = () =>
      embed({
        title: '🏦 Napad na bank!',
        color: COLORS.gold,
        description:
          `${starter} planuje skok i szuka ekipy!\n\n` +
          `👥 Ekipa (${players.length}/${MAX_PLAYERS}): ${players.map((id) => `<@${id}>`).join(', ')}\n` +
          `🎯 Szansa powodzenia: **${Math.round(successChance(players.length) * 100)}%** (rośnie z każdym uczestnikiem)\n` +
          `💵 Wpisowe: ${fmt(STAKE)} • Łup: od 1.6× do 3× sumy wpisowego, dzielony po równo\n\n` +
          `⏱️ Start <t:${Math.floor(endsAt / 1000)}:R>. Potrzeba minimum 2 osób.`,
      });

    const row = new ActionRowBuilder().addComponents(
      new ButtonBuilder().setCustomId('heist_join').setLabel(`Dołącz (${STAKE})`).setEmoji('🔫').setStyle(ButtonStyle.Danger),
    );

    let message;
    try {
      await interaction.reply({ embeds: [lobby()], components: [row] });
      message = await interaction.fetchReply();
    } catch (err) {
      // Nie udało się otworzyć lobby: oddaj wpisowe i odblokuj kanał.
      db.addWallet(starter.id, STAKE);
      db.clearCooldown(starter.id, 'heist');
      running.delete(channelKey);
      throw err;
    }
    const collector = message.createMessageComponentCollector({ componentType: ComponentType.Button, time: delay(JOIN_TIME) });

    collector.on('collect', async (i) => {
      if (players.includes(i.user.id)) return i.reply({ content: '✋ Już jesteś w ekipie!', flags: EPHEMERAL });
      if (players.length >= MAX_PLAYERS) return i.reply({ content: '🚫 Ekipa jest pełna.', flags: EPHEMERAL });
      const cd = db.cooldownLeft(i.user.id, 'heist');
      if (cd > 0) return i.reply({ content: `⏰ Odczekaj jeszcze **${duration(cd)}** po poprzednim napadzie.`, flags: EPHEMERAL });
      if (!db.takeWallet(i.user.id, STAKE)) return i.reply({ content: `❌ Nie masz ${fmt(STAKE)} na wpisowe.`, flags: EPHEMERAL });
      db.setCooldown(i.user.id, 'heist', db.scaledCooldown(i.user.id, COOLDOWNS.heist));
      players.push(i.user.id);
      await i.update({ embeds: [lobby()], components: [row] });
    });

    collector.on('end', async () => {
      running.delete(channelKey);
      try {
        if (players.length < 2) {
          for (const id of players) {
            db.addWallet(id, STAKE);
            db.clearCooldown(id, 'heist');
          }
          return await interaction.editReply({
            embeds: [embed({ title: '🏦 Napad odwołany', color: COLORS.push, description: 'Nikt nie dołączył do ekipy. Wpisowe wróciło do właściciela.' })],
            components: [],
          });
        }

        // Najpierw rozliczenie (wypłaty trafiają do graczy niezależnie od Discorda), potem pokaz z odliczaniem.
        const n = players.length;
        const crew = players.map((id) => `<@${id}>`).join(', ');
        let result;
        if (chance(successChance(n))) {
          const loot = Math.floor(STAKE * n * (1.6 + Math.random() * 1.4));
          const share = Math.floor(loot / n);
          for (const id of players) {
            db.addWallet(id, share);
            db.addXp(id, 30);
          }
          result = embed({
            title: '💰 Napad udany!',
            color: COLORS.win,
            description: `Ekipa (${crew}) wynosi z banku **${fmt(loot)}**!\nKażdy dostaje po **${fmt(share)}** (wpisowe: ${fmt(STAKE)}).`,
          });
        } else {
          for (const id of players) db.addXp(id, 5);
          result = embed({
            title: '🚔 Napad nieudany!',
            color: COLORS.lose,
            description: `Policja zjawiła się wcześniej, niż planowaliście. Ekipa (${crew}) traci wpisowe (${fmt(STAKE)} na głowę).`,
          });
        }

        const scene = (text) => embed({ title: '🏦 Napad na bank!', color: COLORS.gold, description: `Ekipa: ${crew}\n\n${text}` });
        for (const text of [
          '🔐 Cicho, cicho... wchodzicie tylnymi drzwiami.',
          '💣 Ładunki założone. Odliczanie...\n# 3️⃣',
          '💣 Odliczanie...\n# 2️⃣',
          '💣 Odliczanie...\n# 1️⃣',
          '💥 **BUUUM!** Dym opada...',
        ]) {
          await safeEdit(interaction, { embeds: [scene(text)], components: [] });
          await sleep(delay(1000));
        }
        await interaction.editReply({ embeds: [result], components: [] });
      } catch (err) {
        console.error('Błąd rozliczania napadu:', err);
      }
    });
  },
};
