const { SlashCommandBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, ComponentType } = require('discord.js');
const db = require('../lib/db');
const { fail, delay, EPHEMERAL, MIN_BET, MAX_BET } = require('../lib/game');
const { embed, COLORS, fmt, parseAmount, chance, sleep } = require('../lib/util');
const { safeEdit } = require('../lib/anim');

const FEE = 0.03; // 3% puli znika z gry
const WAIT = 60_000;

module.exports = {
  data: new SlashCommandBuilder()
    .setName('pojedynek')
    .setDescription('Wyzwij innego gracza na pojedynek o pieniądze (50/50)')
    .addUserOption((o) => o.setName('przeciwnik').setDescription('Z kim walczysz').setRequired(true))
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka każdego z graczy (np. 500, 2k)').setRequired(true)),

  async execute(interaction) {
    const challenger = interaction.user;
    const opponent = interaction.options.getUser('przeciwnik');
    if (opponent.bot) return fail(interaction, '🤖 Boty nie biorą udziału w pojedynkach.');
    if (opponent.id === challenger.id) return fail(interaction, '🙃 Pojedynek z samym sobą? Zawsze wygrasz i przegrasz jednocześnie.');

    const bet = parseAmount(interaction.options.getString('zaklad'), db.getUser(challenger.id).wallet);
    if (!bet) return fail(interaction, '❌ Podaj poprawną stawkę, np. `500`, `2k`.');
    if (bet < MIN_BET) return fail(interaction, `❌ Minimalna stawka to ${fmt(MIN_BET)}.`);
    if (bet > MAX_BET) return fail(interaction, `❌ Maksymalna stawka to ${fmt(MAX_BET)}.`);
    if (db.getUser(challenger.id).wallet < bet) return fail(interaction, '❌ Nie masz tyle w portfelu.');
    if (db.getUser(opponent.id).wallet < bet) return fail(interaction, `❌ ${opponent.displayName} nie ma tyle w portfelu.`);

    const row = new ActionRowBuilder().addComponents(
      new ButtonBuilder().setCustomId('duel_accept').setLabel('Przyjmuję!').setEmoji('⚔️').setStyle(ButtonStyle.Success),
      new ButtonBuilder().setCustomId('duel_decline').setLabel('Odmawiam').setEmoji('🏳️').setStyle(ButtonStyle.Secondary),
    );
    const pot = bet * 2;
    await interaction.reply({
      content: `${opponent}`,
      embeds: [
        embed({
          title: '⚔️ Wyzwanie na pojedynek!',
          color: COLORS.gold,
          description: `${challenger} wyzywa ${opponent} na pojedynek!\nStawka: **${fmt(bet)}** od każdego • Pula: **${fmt(pot)}** (zwycięzca dostaje ${fmt(pot - Math.ceil(pot * FEE))} po 3% prowizji)\n\n${opponent}, masz minutę na odpowiedź.`,
        }),
      ],
      components: [row],
    });
    const message = await interaction.fetchReply();

    let done = false;
    const collector = message.createMessageComponentCollector({ componentType: ComponentType.Button, time: delay(WAIT) });

    collector.on('collect', async (i) => {
      try {
        if (i.user.id !== opponent.id) {
          return await i.reply({ content: '✋ To wyzwanie jest dla kogoś innego.', flags: EPHEMERAL });
        }
        done = true;
        collector.stop('answered');

        if (i.customId === 'duel_decline') {
          return await i.update({
            content: '',
            embeds: [embed({ title: '🏳️ Pojedynek odrzucony', color: COLORS.push, description: `${opponent} nie przyjął wyzwania.` })],
            components: [],
          });
        }

        // Najpierw odpowiedź na kliknięcie, dopiero potem ruszamy pieniądze. Gdyby Discord odmówił, nikt nic nie traci.
        const scene = (text) => embed({ title: '⚔️ Pojedynek!', color: COLORS.gold, description: `${challenger} ⚔️ ${opponent}\n\n${text}` });
        await i.update({ content: '', embeds: [scene('⚔️ Przeciwnicy stają naprzeciw siebie...')], components: [] });

        // Obie stawki pobieramy dopiero teraz; jeśli komuś zabrakło, pojedynek jest anulowany.
        const paid = db.tx(() => {
          if (!db.takeWallet(challenger.id, bet)) return false;
          if (!db.takeWallet(opponent.id, bet)) {
            db.addWallet(challenger.id, bet);
            return false;
          }
          return true;
        });
        if (!paid) {
          return await interaction.editReply({
            embeds: [embed({ title: '⚔️ Pojedynek anulowany', color: COLORS.lose, description: 'Któryś z graczy nie ma już wystarczająco monet w portfelu.' })],
          });
        }

        const challengerWins = chance(0.5);
        const winner = challengerWins ? challenger : opponent;
        const loser = challengerWins ? opponent : challenger;
        const payout = pot - Math.ceil(pot * FEE);
        db.addWallet(winner.id, payout);
        db.recordGame(winner.id, payout - bet);
        db.recordGame(loser.id, -bet);
        const lvW = db.addXp(winner.id, 15);
        db.addXp(loser.id, 5);

        // Wynik jest już zapisany; reszta to pokaz.
        for (const text of ['🥊 Wymiana ciosów!', '💥 Jednemu z was ugięły się kolana...']) {
          await sleep(delay(1100));
          await safeEdit(interaction, { embeds: [scene(text)] });
        }
        await sleep(delay(1100));
        await interaction.editReply({
          embeds: [
            embed({
              title: '⚔️ Pojedynek rozstrzygnięty!',
              color: COLORS.win,
              description: `🏆 **${winner.displayName}** pokonuje **${loser.displayName}** i zgarnia **${fmt(payout)}**!${lvW.note}`,
              footer: `Stawka: ${fmt(bet)} od każdego`,
            }),
          ],
        });
      } catch (err) {
        console.error('Błąd w pojedynku:', err);
      }
    });

    collector.on('end', async () => {
      if (done) return;
      await interaction
        .editReply({
          content: '',
          embeds: [embed({ title: '⌛ Wyzwanie wygasło', color: COLORS.push, description: `${opponent} nie odpowiedział na czas.` })],
          components: [],
        })
        .catch(() => {});
    });
  },
};
