const { SlashCommandBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, ComponentType } = require('discord.js');
const db = require('../lib/db');
const { fmt, embed, COLORS, parseAmount, sleep, pick } = require('../lib/util');
const { safeEdit } = require('../lib/anim');
const { fail, delay, EPHEMERAL, MIN_BET, MAX_BET } = require('../lib/game');

const FEE = 0.05; // 5% puli znika z gry
const MAX_PLAYERS = 20;
const JOIN_TIME = 45_000;

// Jedno losowanie naraz na kanał
const running = new Set();

module.exports = {
  data: new SlashCommandBuilder()
    .setName('jackpot')
    .setDescription('Jackpot: wszyscy dorzucają tę samą stawkę, a jedna osoba zgarnia całą pulę')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka każdego uczestnika (np. 500, 2k)').setRequired(true)),

  async execute(interaction) {
    const starter = interaction.user;
    const channelKey = interaction.channelId;
    if (running.has(channelKey)) return fail(interaction, '🎰 Na tym kanale trwa już jackpot – dołącz do niego!');

    const stake = parseAmount(interaction.options.getString('zaklad'), db.getUser(starter.id).wallet);
    if (!stake) return fail(interaction, '❌ Podaj poprawną stawkę, np. `500`, `2k`.');
    if (stake < MIN_BET) return fail(interaction, `❌ Minimalna stawka to ${fmt(MIN_BET)}.`);
    if (stake > MAX_BET) return fail(interaction, `❌ Maksymalna stawka to ${fmt(MAX_BET)}.`);
    if (!db.takeWallet(starter.id, stake)) return fail(interaction, `❌ Masz za mało w portfelu na stawkę ${fmt(stake)}.`);
    running.add(channelKey);

    const players = [starter.id];
    const endsAt = Date.now() + JOIN_TIME;
    const potNow = () => stake * players.length;

    const lobby = () =>
      embed({
        title: '🎰 Jackpot!',
        color: COLORS.gold,
        description:
          `${starter} otwiera jackpot! Stawka: **${fmt(stake)}** od każdego.\n\n` +
          `👥 Uczestnicy (${players.length}/${MAX_PLAYERS}): ${players.map((id) => `<@${id}>`).join(', ')}\n` +
          `💰 Pula: **${fmt(potNow())}** (zwycięzca dostaje ${fmt(potNow() - Math.ceil(potNow() * FEE))} po 5% prowizji)\n` +
          `🎯 Szansa każdego: **${(100 / players.length).toFixed(1)}%**\n\n` +
          `⏱️ Losowanie <t:${Math.floor(endsAt / 1000)}:R>. Potrzeba minimum 2 osób.`,
      });

    const row = new ActionRowBuilder().addComponents(
      new ButtonBuilder().setCustomId('jackpot_join').setLabel(`Dorzucam ${stake}`).setEmoji('🎰').setStyle(ButtonStyle.Success),
    );

    let message;
    try {
      await interaction.reply({ embeds: [lobby()], components: [row] });
      message = await interaction.fetchReply();
    } catch (err) {
      // Nie udało się otworzyć lobby: oddaj stawkę i odblokuj kanał.
      db.addWallet(starter.id, stake);
      running.delete(channelKey);
      throw err;
    }

    const collector = message.createMessageComponentCollector({ componentType: ComponentType.Button, time: delay(JOIN_TIME) });

    collector.on('collect', async (i) => {
      try {
        if (players.includes(i.user.id)) return await i.reply({ content: '✋ Już dorzuciłeś do puli!', flags: EPHEMERAL });
        if (players.length >= MAX_PLAYERS) return await i.reply({ content: '🚫 Jackpot jest pełny.', flags: EPHEMERAL });
        if (!db.takeWallet(i.user.id, stake)) return await i.reply({ content: `❌ Nie masz ${fmt(stake)} w portfelu.`, flags: EPHEMERAL });
        players.push(i.user.id);
        await i.update({ embeds: [lobby()], components: [row] });
      } catch (err) {
        console.error('Błąd dołączania do jackpota:', err);
      }
    });

    collector.on('end', async () => {
      running.delete(channelKey);
      try {
        if (players.length < 2) {
          for (const id of players) db.addWallet(id, stake);
          return await interaction.editReply({
            embeds: [embed({ title: '🎰 Jackpot odwołany', color: COLORS.push, description: 'Nikt nie dołączył. Stawka wróciła do portfela.' })],
            components: [],
          });
        }

        // Rozliczenie najpierw (niezależnie od Discorda), potem pokaz losowania.
        const pot = stake * players.length;
        const payout = pot - Math.ceil(pot * FEE);
        const winner = pick(players);
        db.addWallet(winner, payout);
        for (const id of players) {
          db.recordGame(id, id === winner ? payout - stake : -stake);
          db.addXp(id, id === winner ? 25 : 8);
        }

        const scene = (text, name) => embed({ title: '🎰 Jackpot!', color: COLORS.gold, description: `Pula: **${fmt(pot)}** • ${players.length} graczy\n\n${text}${name ? `\n# 🎰 <@${name}>` : ''}` });
        for (const text of ['🌀 Maszyna losuje...', '🌀 Kto zgarnie wszystko?...', '🥁 Zwalnia...']) {
          await safeEdit(interaction, { embeds: [scene(text, pick(players))], components: [] });
          await sleep(delay(900));
        }
        await interaction.editReply({
          embeds: [
            embed({
              title: '🏆 Mamy zwycięzcę jackpota!',
              color: COLORS.win,
              description: `<@${winner}> zgarnia **${fmt(payout)}**!\nSzansa była **${(100 / players.length).toFixed(1)}%**, pula ${fmt(pot)} (po 5% prowizji).\nUczestnicy: ${players.map((id) => `<@${id}>`).join(', ')}`,
            }),
          ],
          components: [],
        });
      } catch (err) {
        console.error('Błąd rozliczania jackpota:', err);
      }
    });
  },
};
