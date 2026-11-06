const { SlashCommandBuilder } = require('discord.js');
const { gather } = require('../lib/gather');
const { COOLDOWNS } = require('../lib/data');
const { pick } = require('../lib/util');

const FOUND = ['🌊 Spławik drgnął!', '🎣 Branie!', '🐟 Coś mocno ciągnie!', '☀️ Spokojny dzień nad wodą.'];

module.exports = {
  data: new SlashCommandBuilder().setName('ryby').setDescription('Idź na ryby (potrzebujesz wędki ze sklepu)'),

  execute: (interaction) =>
    gather(interaction, {
      key: 'ryby',
      cooldown: COOLDOWNS.ryby,
      title: 'Wędkowanie',
      emoji: '🎣',
      frames: ['🎣 Zarzucasz wędkę i czekasz...\n🌊〰️〰️〰️〰️〰️', '🎣 Spławik drgnął!\n🌊〰️〰️🔴〰️〰️', '🎣 Coś ciągnie! Podbijasz i zwijasz żyłkę...\n🌊〰️💦💦〰️〰️'],
      tools: [
        { id: 'wedka_legendarna', luck: 6 },
        { id: 'wedka_mistrza', luck: 3.5 },
        { id: 'super_wedka', luck: 2 },
        { id: 'wedka', luck: 1 },
      ],
      noTool: '🎣 Nie masz wędki! Kup ją w `/sklep` (`/kup wedka`).',
      found: () => pick(FOUND),
      table: [
        { id: 'but', w: 15 },
        { id: 'sledz', w: 30, qty: [1, 2] },
        { id: 'karp', w: 25, qty: [1, 2] },
        { id: 'szczupak', w: 14, rare: true },
        { id: 'sum', w: 8, rare: true },
        { id: 'tunczyk', w: 5, rare: true },
        { id: 'rekin', w: 1.5, rare: true },
        { id: 'skrzynka', w: 2.2, rare: true },
        { id: 'skrzynia', w: 0.7, rare: true },
      ],
    }),
};
