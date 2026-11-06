const { SlashCommandBuilder } = require('discord.js');
const { gather } = require('../lib/gather');
const { COOLDOWNS } = require('../lib/data');
const { pick, shortDuration } = require('../lib/util');

const FOUND = ['🗑️ Pod kartonem coś błyszczy!', '🥫 Grzebiesz głębiej...', '🐀 Szczur patrzy krzywo, ale zostawia Ci to:', '♻️ Czyjeś śmieci to Twój skarb.'];

module.exports = {
  data: new SlashCommandBuilder().setName('smietnik').setDescription(`Przeszukaj śmietniki w poszukiwaniu skarbów (za darmo, co ${shortDuration(COOLDOWNS.smietnik)})`),

  execute: (interaction) =>
    gather(interaction, {
      key: 'smietnik',
      cooldown: COOLDOWNS.smietnik,
      title: 'Szperanie w śmietnikach',
      emoji: '🗑️',
      frames: ['🌙 Skradasz się do śmietnika...\n🏚️🗑️🗑️', '🔦 Odsuwasz pokrywę i świecisz latarką...\n🗑️🔍🗑️', '🤏 Wyciągasz coś z samego dna...\n🗑️✋🗑️'],
      tools: [{ id: null, luck: 1 }],
      noTool: '🗑️ Dziś wszystkie śmietniki są puste.',
      // Czasem trafi się przeganiający Cię pies albo dozorca.
      missChance: 0.12,
      missText: '🐕 Pies ze śmietnika przegonił Cię z podkulonym ogonem. Nic nie znalazłeś.',
      found: () => pick(FOUND),
      table: [
        { id: 'puszka', w: 35, qty: [2, 6] },
        { id: 'butelka', w: 30, qty: [1, 4] },
        { id: 'stary_telefon', w: 5, rare: true },
        { id: 'zepsuty_zegarek', w: 2, rare: true },
        { id: 'zlota_obraczka', w: 0.4, rare: true },
        { id: 'skrzynka', w: 1, rare: true },
      ],
    }),
};
