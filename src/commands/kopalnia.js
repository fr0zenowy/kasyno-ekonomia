const { SlashCommandBuilder } = require('discord.js');
const { gather } = require('../lib/gather');
const { COOLDOWNS } = require('../lib/data');
const { pick } = require('../lib/util');

const FOUND = ['⛏️ Stuk, stuk, stuk...', '🔦 Latarka oświetliła coś błyszczącego!', '💥 Skała pękła!', '🪨 Kilof wbija się głęboko.'];

module.exports = {
  data: new SlashCommandBuilder().setName('kopalnia').setDescription('Idź kopać rudy (potrzebujesz kilofa ze sklepu)'),

  execute: (interaction) =>
    gather(interaction, {
      key: 'kopalnia',
      cooldown: COOLDOWNS.kopalnia,
      title: 'Kopalnia',
      emoji: '⛏️',
      frames: ['⛏️ Schodzisz do kopalni...\n🔦 ⬛⬛⬛⬛⬛', '⛏️ Stuk, stuk, stuk!\n🪨 🪨 💥 🪨 🪨', '⛏️ Skała pęka, coś błyszczy w świetle latarki...\n🪨 ✨ ✨ ✨ 🪨'],
      tools: [
        { id: 'kilof_kosmiczny', luck: 6 },
        { id: 'diamentowy_kilof', luck: 3.5 },
        { id: 'zloty_kilof', luck: 2 },
        { id: 'kilof', luck: 1 },
      ],
      noTool: '⛏️ Nie masz kilofa! Kup go w `/sklep` (`/kup kilof`).',
      found: () => pick(FOUND),
      table: [
        { id: 'kamien', w: 25, qty: [2, 5] },
        { id: 'wegiel', w: 25, qty: [1, 4] },
        { id: 'zelazo', w: 20, qty: [1, 3] },
        { id: 'zloto', w: 14, qty: [1, 2], rare: true },
        { id: 'diament', w: 8, rare: true },
        { id: 'rubin', w: 3, rare: true },
        { id: 'meteoryt', w: 0.8, rare: true },
        { id: 'skrzynka', w: 2.2, rare: true },
        { id: 'skrzynia', w: 0.7, rare: true },
      ],
    }),
};
