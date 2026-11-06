const { SlashCommandBuilder } = require('discord.js');
const { runQuiz } = require('../lib/quiz');
const { COOLDOWNS } = require('../lib/data');
const { randInt, pick, shuffle, shortDuration } = require('../lib/util');

function makeProblem() {
  const op = pick(['+', '-', '×']);
  let a;
  let b;
  let answer;
  if (op === '+') {
    a = randInt(12, 99);
    b = randInt(12, 99);
    answer = a + b;
  } else if (op === '-') {
    a = randInt(30, 150);
    b = randInt(5, a - 1);
    answer = a - b;
  } else {
    a = randInt(4, 15);
    b = randInt(4, 15);
    answer = a * b;
  }

  const wrong = new Set();
  while (wrong.size < 3) {
    const candidate = answer + pick([-1, 1]) * pick([1, 2, 3, 5, 10, randInt(1, 12)]);
    if (candidate !== answer && candidate >= 0) wrong.add(candidate);
  }
  return { text: `${a} ${op} ${b} = ?`, answer, options: shuffle([answer, ...wrong]) };
}

module.exports = {
  data: new SlashCommandBuilder().setName('matma').setDescription(`Szybkie liczenie w głowie – poprawna odpowiedź to kasa (co ${shortDuration(COOLDOWNS.matma)})`),

  async execute(interaction) {
    const p = makeProblem();
    await runQuiz(interaction, {
      key: 'matma',
      cooldown: COOLDOWNS.matma,
      title: '🧮 Matma',
      question: p.text,
      options: p.options.map(String),
      correctIndex: p.options.indexOf(p.answer),
      reward: [120, 300],
      time: 12_000,
    });
  },
};
