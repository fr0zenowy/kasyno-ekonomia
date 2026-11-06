const { SlashCommandBuilder } = require('discord.js');
const { TRIVIA, COOLDOWNS } = require('../lib/data');
const { runQuiz } = require('../lib/quiz');
const { pick, shuffle, shortDuration } = require('../lib/util');

module.exports = {
  data: new SlashCommandBuilder().setName('trivia').setDescription(`Quiz wiedzy – poprawna odpowiedź to kasa (co ${shortDuration(COOLDOWNS.trivia)})`),

  async execute(interaction) {
    const item = pick(TRIVIA);
    const options = shuffle([item.a, ...item.w]);
    await runQuiz(interaction, {
      key: 'trivia',
      cooldown: COOLDOWNS.trivia,
      title: '🧠 Trivia',
      question: item.q,
      options,
      correctIndex: options.indexOf(item.a),
      reward: [200, 450],
      time: 20_000,
    });
  },
};
