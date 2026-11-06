const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { COOLDOWNS } = require('../lib/data');
const { label } = require('../lib/items');
const { applyEarn } = require('../lib/boosts');
const { fmt, embed, COLORS, randInt, chance, pick, shortDuration } = require('../lib/util');
const { guardCooldown } = require('../lib/game');

// Od najlepszego instrumentu do najgorszego; ostatni wpis to śpiew bez instrumentu (za darmo).
const INSTRUMENTS = [
  { id: 'fortepian', mult: 4 },
  { id: 'skrzypce', mult: 2.2 },
  { id: 'gitara', mult: 1.5 },
  { id: null, mult: 1 },
];

const SCENES = [
  'Grasz „Sto lat” dla przypadkowego jubilata i dostajesz brawa.',
  'Przechodzień rzucił monetę i poprosił o „coś z Metalliki”.',
  'Zebrał się tłumek, który głównie nagrywał telefonami.',
  'Dzieciaki tańczą, rodzice dorzucają drobne.',
];

module.exports = {
  INSTRUMENTS,

  data: new SlashCommandBuilder().setName('uliczny').setDescription(`Zagraj na ulicy i zbieraj do kapelusza (za darmo, co ${shortDuration(COOLDOWNS.uliczny)}); instrument zwiększa napiwki`),

  async execute(interaction) {
    const userId = interaction.user.id;
    if (!(await guardCooldown(interaction, 'uliczny', COOLDOWNS.uliczny))) return;

    const u = db.getUser(userId);
    const instrument = INSTRUMENTS.find((i) => i.id === null || db.itemQty(userId, i.id) > 0);
    let base = randInt(20, 110) + u.level * 2;
    const notes = [];
    let color = COLORS.win;
    if (chance(0.1)) {
      base = Math.floor(base * 0.3);
      color = COLORS.push;
      notes.push('🌧️ Zaczęło padać i ludzie się rozeszli. Słabszy dzień.');
    } else if (chance(0.05)) {
      base *= 3;
      color = COLORS.gold;
      notes.push('🤑 **Hojny turysta!** Potrójny napiwek.');
    }
    base = Math.floor(base * instrument.mult);
    notes.unshift(instrument.id ? `${label(instrument.id)}: ×${instrument.mult}` : '🎤 Śpiewasz a cappella (kup instrument w `/sklep`, to zarobisz więcej).');

    const earned = applyEarn(userId, base);
    db.addWallet(userId, earned.amount);
    const lv = db.addXp(userId, randInt(3, 7));

    await interaction.reply({
      embeds: [
        embed({
          title: '🎸 Grajek uliczny',
          color,
          description: `${pick(SCENES)}\n\nW kapeluszu **${fmt(earned.amount)}**.\n${[...notes, ...earned.notes].join('\n')}${lv.note}`,
          footer: `Kolejny występ za ${shortDuration(COOLDOWNS.uliczny)}`,
        }),
      ],
    });
  },
};
