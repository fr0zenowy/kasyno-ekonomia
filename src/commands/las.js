const { SlashCommandBuilder } = require('discord.js');
const { gather } = require('../lib/gather');
const { COOLDOWNS } = require('../lib/data');
const { pick, shortDuration } = require('../lib/util');

const FOUND = ['🍂 Pod mchem coś żółknie!', '🌲 Ścieżka pachnie żywicą.', '🐦 Ptaki ćwierkają, a Ty znajdujesz...', '🌿 Trafiłeś na dobre miejsce.'];

module.exports = {
  data: new SlashCommandBuilder().setName('las').setDescription(`Idź do lasu na grzyby i jagody (za darmo, co ${shortDuration(COOLDOWNS.las)}); koszyk daje lepszy zbiór`),

  execute: (interaction) =>
    gather(interaction, {
      key: 'las',
      cooldown: COOLDOWNS.las,
      title: 'Grzybobranie',
      emoji: '🍄',
      frames: ['🌲 Wchodzisz do lasu z pustymi rękami...\n🌲🌳🌲🌳🌲', '🔍 Rozglądasz się pod drzewami...\n🌳🍂🍄🍂🌳', '🧺 Coś jest w trawie! Schylasz się...\n🌿🍄🫐🌿🌿'],
      // Ostatni wpis bez id to wariant darmowy: koszyk nie jest potrzebny, tylko pomaga.
      tools: [
        { id: 'wielki_koszyk', luck: 3 },
        { id: 'koszyk', luck: 1.5 },
        { id: null, luck: 1 },
      ],
      noTool: '🌲 Dziś w lesie nic się nie da zebrać.',
      found: () => pick(FOUND),
      table: [
        { id: 'szyszka', w: 25, qty: [2, 5] },
        { id: 'jagody', w: 28, qty: [1, 3] },
        { id: 'kurki', w: 22, qty: [1, 3] },
        { id: 'borowik', w: 12, rare: true },
        { id: 'trufla', w: 3, rare: true },
        { id: 'zloty_grzyb', w: 0.4, rare: true },
        { id: 'skrzynka', w: 1.5, rare: true },
      ],
    }),
};
