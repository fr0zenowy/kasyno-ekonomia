const { SlashCommandBuilder } = require('discord.js');
const { gather } = require('../lib/gather');
const { COOLDOWNS } = require('../lib/data');
const { pick } = require('../lib/util');

const FOUND = ['🌲 Cisza w lesie...', '🎯 Celujesz i strzelasz!', '🍂 Coś szeleści w krzakach.', '🦅 Wstrzymujesz oddech.'];

module.exports = {
  data: new SlashCommandBuilder().setName('polowanie').setDescription('Idź na polowanie (potrzebujesz strzelby ze sklepu)'),

  execute: (interaction) =>
    gather(interaction, {
      key: 'polowanie',
      cooldown: COOLDOWNS.polowanie,
      title: 'Polowanie',
      emoji: '🔫',
      frames: ['🌲 Zakradasz się do lasu...\n🌲🌲🍂🌲🌲', '🎯 Coś się rusza w krzakach... celujesz.\n🌲🌲👀🌲🌲', '💥 Strzał!\n🌲🌲💨🌲🌲'],
      tools: [
        { id: 'snajperka', luck: 6, miss: 0.03 },
        { id: 'luneta', luck: 3.5, miss: 0.06 },
        { id: 'karabin', luck: 2, miss: 0.12 },
        { id: 'strzelba', luck: 1, miss: 0.25 },
      ],
      noTool: '🔫 Nie masz broni! Kup strzelbę w `/sklep` (`/kup strzelba`).',
      missChance: (tool) => tool.miss,
      missText: '🙈 Pudło! Zwierzyna uciekła, a Ty wracasz z pustymi rękami.',
      found: () => pick(FOUND),
      table: [
        { id: 'krolik', w: 30, qty: [1, 2] },
        { id: 'kaczka', w: 26, qty: [1, 2] },
        { id: 'jelen', w: 16 },
        { id: 'dzik', w: 10, rare: true },
        { id: 'niedzwiedz', w: 4, rare: true },
        { id: 'zubr', w: 1.2, rare: true },
        { id: 'skrzynka', w: 2.2, rare: true },
        { id: 'skrzynia', w: 0.7, rare: true },
      ],
    }),
};
