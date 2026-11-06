const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { COOLDOWNS } = require('../lib/data');
const { fmt, embed, COLORS, randInt, chance, duration } = require('../lib/util');
const { guardCooldown, fail } = require('../lib/game');

const MIN_WALLET = 200;

module.exports = {
  data: new SlashCommandBuilder()
    .setName('okradnij')
    .setDescription('Spróbuj okraść innego gracza z portfela (bank jest bezpieczny!)')
    .addUserOption((o) => o.setName('ofiara').setDescription('Kogo okraść').setRequired(true)),

  async execute(interaction) {
    const thief = interaction.user;
    const victim = interaction.options.getUser('ofiara');
    if (victim.bot) return fail(interaction, '🤖 Boty mają świetne zabezpieczenia. Odpuść.');
    if (victim.id === thief.id) return fail(interaction, '🤡 Okradanie samego siebie to dziwny pomysł.');

    const t = db.getUser(thief.id);
    const v = db.getUser(victim.id);
    if (t.wallet < MIN_WALLET) return fail(interaction, `❌ Musisz mieć przy sobie min. ${fmt(MIN_WALLET)}, żeby mieć z czego zapłacić grzywnę.`);
    if (v.wallet < MIN_WALLET) return fail(interaction, `❌ ${victim.displayName} ma za mało w portfelu, nie warto ryzykować.`);

    const safeLeft = db.cooldownLeft(victim.id, 'robbed');
    if (safeLeft > 0) return fail(interaction, `🛡️ ${victim.displayName} został niedawno okradziony i jest pod ochroną jeszcze przez **${duration(safeLeft)}**.`);

    if (!(await guardCooldown(interaction, 'rob', COOLDOWNS.rob))) return;

    const notes = [];

    // Kłódka ofiary: bez łomu to automatyczna porażka (kłódka się zużywa), z łomem skok trwa dalej.
    if (db.itemQty(victim.id, 'klodka') > 0) {
      if (db.itemQty(thief.id, 'lom') > 0) {
        db.takeItem(thief.id, 'lom', 1);
        db.takeItem(victim.id, 'klodka', 1);
        notes.push('🪓 Łom przebił kłódkę ofiary (łom zużyty).');
      } else {
        db.takeItem(victim.id, 'klodka', 1);
        const fine = Math.max(100, Math.floor(t.wallet * 0.1));
        const paid = Math.min(fine, t.wallet);
        db.transfer(thief.id, victim.id, paid);
        return interaction.reply({
          embeds: [
            embed({
              title: '🔒 Kłódka!',
              color: COLORS.lose,
              description: `${victim} miał zabezpieczony portfel. Kłódka pękła, ale Ty zostałeś przyłapany i płacisz **${fmt(paid)}** odszkodowania.
*Z łomem ze sklepu przebiłbyś kłódkę.*`,
            }),
          ],
        });
      }
    }

    let p = 0.45;
    if (db.takeItem(thief.id, 'maska', 1)) {
      p += 0.2;
      notes.push('🎭 Kominiarka zużyta (+20% szansy).');
    }
    // Pies ofiary utrudnia skok (nigdy poniżej 10% szansy).
    const guard = db.effectSum(victim.id, 'robDefense');
    if (guard > 0) {
      p = Math.max(0.1, p - guard);
      notes.push(`🐕 Pies ofiary: −${Math.round(guard * 100)} pkt szansy.`);
    }

    if (chance(p)) {
      const stolen = Math.min(v.wallet, Math.floor((v.wallet * randInt(10, 35)) / 100));
      if (!db.tx(() => (db.takeWallet(victim.id, stolen) ? (db.addWallet(thief.id, stolen), true) : false))) {
        return interaction.reply({ content: '💨 Ofiara w ostatniej chwili wydała pieniądze. Wracasz z niczym.' });
      }
      db.setCooldown(victim.id, 'robbed', COOLDOWNS.robbed);
      const lv = db.addXp(thief.id, randInt(10, 20));
      return interaction.reply({
        embeds: [
          embed({
            title: '🦹 Skok udany!',
            color: COLORS.win,
            description: `Okradłeś ${victim} na **${fmt(stolen)}**!${notes.length ? `\n${notes.join('\n')}` : ''}${lv.note}`,
            footer: `Szansa: ${Math.round(p * 100)}%. Kłódka z /sklep chroni przed takimi jak Ty.`,
          }),
        ],
      });
    }

    let fine = Math.min(t.wallet, Math.max(100, Math.floor((t.wallet * randInt(10, 20)) / 100)));
    if (db.itemQty(victim.id, 'alarm') > 0) {
      fine = Math.min(t.wallet, fine * 2);
      notes.push('🚨 Alarm ofiary zawył: grzywna podwójna!');
    }
    db.transfer(thief.id, victim.id, fine);
    await interaction.reply({
      embeds: [
        embed({
          title: '🚔 Przyłapany!',
          color: COLORS.lose,
          description: `Próba okradzenia ${victim} nie wyszła. Płacisz mu **${fmt(fine)}** za straty moralne.${notes.length ? `\n${notes.join('\n')}` : ''}`,
        }),
      ],
    });
  },
};
