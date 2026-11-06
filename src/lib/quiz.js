const { ActionRowBuilder, ButtonBuilder, ButtonStyle, ComponentType } = require('discord.js');
const db = require('./db');
const { guardCooldown, ownerFilter, delay } = require('./game');
const { applyEarn } = require('./boosts');
const { embed, COLORS, fmt, randInt } = require('./util');

/**
 * Pytanie z czterema przyciskami. Dobra odpowiedź = nagroda, zła lub brak odpowiedzi = nic.
 * cfg: { key, cooldown, title, question, options, correctIndex, reward: [min, max], time }
 */
async function runQuiz(interaction, cfg) {
  if (!(await guardCooldown(interaction, cfg.key, cfg.cooldown))) return;
  const userId = interaction.user.id;
  const seconds = Math.round((cfg.time ?? 20_000) / 1000);

  const buttons = (state) =>
    new ActionRowBuilder().addComponents(
      cfg.options.map((text, i) => {
        let style = ButtonStyle.Primary;
        if (state) {
          if (i === cfg.correctIndex) style = ButtonStyle.Success;
          else if (i === state.picked) style = ButtonStyle.Danger;
          else style = ButtonStyle.Secondary;
        }
        return new ButtonBuilder()
          .setCustomId(`quiz_${i}`)
          .setLabel(String(text).slice(0, 80))
          .setStyle(style)
          .setDisabled(Boolean(state));
      }),
    );

  await interaction.reply({
    embeds: [embed({ title: cfg.title, description: `**${cfg.question}**\n\n⏱️ Masz ${seconds} sekund.`, color: COLORS.info })],
    components: [buttons(null)],
  });
  const message = await interaction.fetchReply();
  const collector = message.createMessageComponentCollector({
    componentType: ComponentType.Button,
    filter: ownerFilter(interaction),
    time: delay(cfg.time ?? 20_000),
    max: 1,
  });

  let answered = false;
  collector.on('collect', async (i) => {
    answered = true;
    const picked = Number(i.customId.split('_')[1]);
    const correct = picked === cfg.correctIndex;
    let description;
    if (correct) {
      const earned = applyEarn(userId, randInt(cfg.reward[0], cfg.reward[1]));
      db.addWallet(userId, earned.amount);
      const lv = db.addXp(userId, randInt(8, 16));
      const notes = earned.notes.length ? `\n${earned.notes.join('\n')}` : '';
      description = `**${cfg.question}**\n\n✅ Dobrze! Zarabiasz **${fmt(earned.amount)}**.${notes}${lv.note}`;
    } else {
      description = `**${cfg.question}**\n\n❌ Źle! Poprawna odpowiedź: **${cfg.options[cfg.correctIndex]}**.`;
    }
    await i.update({
      embeds: [embed({ title: cfg.title, description, color: correct ? COLORS.win : COLORS.lose })],
      components: [buttons({ picked })],
    });
  });

  collector.on('end', async () => {
    if (answered) return;
    await interaction
      .editReply({
        embeds: [
          embed({
            title: cfg.title,
            description: `**${cfg.question}**\n\n⌛ Czas minął! Poprawna odpowiedź: **${cfg.options[cfg.correctIndex]}**.`,
            color: COLORS.lose,
          }),
        ],
        components: [buttons({ picked: -1 })],
      })
      .catch(() => {});
  });
}

module.exports = { runQuiz };
