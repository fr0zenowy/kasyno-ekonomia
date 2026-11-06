const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { JOBS, COOLDOWNS } = require('../lib/data');
const { fmt, embed, COLORS, randInt, chance, pick, shortDuration } = require('../lib/util');
const { guardCooldown, fail } = require('../lib/game');
const { applyEarn, pracaPerks } = require('../lib/boosts');
const { label } = require('../lib/items');

module.exports = {
  data: new SlashCommandBuilder()
    .setName('praca')
    .setDescription('Pracuj i zarabiaj – im wyższy poziom, tym lepsza robota')
    .addSubcommand((s) => s.setName('wykonaj').setDescription(`Idź do pracy (co ${shortDuration(COOLDOWNS.praca)})`))
    .addSubcommand((s) => s.setName('lista').setDescription('Zobacz dostępne zawody'))
    .addSubcommand((s) =>
      s
        .setName('zmien')
        .setDescription('Zmień zawód')
        .addStringOption((o) =>
          o
            .setName('zawod')
            .setDescription('Nowy zawód')
            .setRequired(true)
            .addChoices(...JOBS.map((j) => ({ name: `${j.name} (poziom ${j.level}+)`, value: j.id }))),
        ),
    ),

  async execute(interaction) {
    const userId = interaction.user.id;
    const u = db.getUser(userId);
    const sub = interaction.options.getSubcommand();

    if (sub === 'lista') {
      const lines = JOBS.map((j) => {
        const unlocked = u.level >= j.level;
        const current = j.id === u.job ? ' ← **Twój zawód**' : '';
        return `${unlocked ? '✅' : '🔒'} ${j.emoji} **${j.name}** – poziom ${j.level}+ • ${fmt(j.min)}–${fmt(j.max)}${current}`;
      });
      return interaction.reply({
        embeds: [embed({ title: '💼 Zawody', description: `${lines.join('\n')}\n\nZmień zawód: \`/praca zmien\``, footer: `Twój poziom: ${u.level}` })],
      });
    }

    if (sub === 'zmien') {
      const job = JOBS.find((j) => j.id === interaction.options.getString('zawod'));
      if (!job) return fail(interaction, '❌ Nie ma takiego zawodu.');
      if (u.level < job.level) return fail(interaction, `🔒 ${job.emoji} ${job.name} wymaga poziomu **${job.level}**, a Ty masz ${u.level}.`);
      db.setJob(userId, job.id);
      return interaction.reply({ content: `✅ Teraz pracujesz jako ${job.emoji} **${job.name}**. Powodzenia! (\`/praca wykonaj\`)` });
    }

    // wykonaj
    const job = JOBS.find((j) => j.id === u.job) ?? JOBS[0];
    if (u.level < job.level) {
      db.setJob(userId, JOBS[0].id);
      return fail(interaction, '❌ Twój zawód jest niedostępny na tym poziomie. Przywrócono Sprzątacza.');
    }
    if (!(await guardCooldown(interaction, 'praca', COOLDOWNS.praca))) return;

    let pay = randInt(job.min, job.max);
    const notes = [];
    const perks = pracaPerks(userId); // laptop, garnitur, samochód – sumują się
    if (perks.length) {
      pay = Math.floor(pay * (1 + perks.reduce((sum, p) => sum + p.bonus, 0)));
      for (const p of perks) notes.push(`${label(p.id)}: +${Math.round(p.bonus * 100)}%`);
    }
    let color = COLORS.win;
    if (chance(0.08)) {
      pay *= 2;
      notes.push('🌟 **Premia od szefa!** Podwójna wypłata.');
    } else if (chance(0.08)) {
      pay = Math.floor(pay / 2);
      color = COLORS.push;
      notes.push('😠 Szef miał zły dzień. Wypłata o połowę mniejsza.');
    }

    const earned = applyEarn(userId, pay);
    pay = earned.amount;
    notes.push(...earned.notes);

    db.addWallet(userId, pay);
    const lv = db.addXp(userId, randInt(15, 30));

    await interaction.reply({
      embeds: [
        embed({
          title: `${job.emoji} Praca: ${job.name}`,
          color,
          description: `${pick(job.lines)}\n\nZarobiłeś **${fmt(pay)}**.${notes.length ? `\n${notes.join('\n')}` : ''}${lv.note}`,
          footer: `Kolejna zmiana za ${shortDuration(COOLDOWNS.praca)}`,
        }),
      ],
    });
  },
};
