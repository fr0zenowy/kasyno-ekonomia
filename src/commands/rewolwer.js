const { SlashCommandBuilder } = require('discord.js');
const { takeBet, settle, resultEmbed, guarded, delay } = require('../lib/game');
const { embed, sleep, COLORS, shuffle } = require('../lib/util');

const CHAMBERS = 6;
const HOUSE = 0.96; // zwrot: każda liczba naboi daje średnio 96%

/** Mnożnik za przeżycie jednego strzału przy `bullets` nabojach w 6 komorach. */
const multiplierFor = (bullets) => Math.floor(((HOUSE * CHAMBERS) / (CHAMBERS - bullets)) * 100) / 100;

const drum = (bullets, fired = null) =>
  Array.from({ length: CHAMBERS }, (_, i) => `${i === fired ? '▶' : ''}${bullets.has(i) ? '🔴' : '⚪'}`).join(' ');

module.exports = {
  multiplierFor,

  data: new SlashCommandBuilder()
    .setName('rewolwer')
    .setDescription('Rosyjska ruletka: im więcej naboi w bębnie, tym większa wypłata za przeżycie (do x5.76)')
    .addStringOption((o) => o.setName('zaklad').setDescription('Stawka (np. 500, 2k, 50%, all)').setRequired(true))
    .addIntegerOption((o) =>
      o.setName('naboje').setDescription('Ile naboi w bębnie 6-komorowym (1-5, domyślnie 1)').setMinValue(1).setMaxValue(CHAMBERS - 1),
    ),

  async execute(interaction) {
    const count = interaction.options.getInteger('naboje') ?? 1;
    const bet = await takeBet(interaction, interaction.options.getString('zaklad'));
    if (!bet) return;
    const userId = interaction.user.id;

    await guarded(userId, async () => {
      const mult = multiplierFor(count);
      const bullets = new Set(shuffle([...Array(CHAMBERS).keys()]).slice(0, count));
      const fired = Math.floor(Math.random() * CHAMBERS);
      const header = `Naboje: **${count}/${CHAMBERS}** • wypłata za przeżycie: **x${mult.toFixed(2)}** • szansa: **${Math.round(((CHAMBERS - count) / CHAMBERS) * 100)}%**`;
      const frame = (text, drumText) => embed({ title: '🔫 Rosyjska ruletka', color: COLORS.push, description: `${header}\n\n${text}${drumText ? `\n${drumText}` : ''}` });
      const info = { game: 'rewolwer', count, bullets: [...bullets], fired, dead: bullets.has(fired), mult, bet };
      interaction.meta?.(info);

      await interaction.reply({ embeds: [frame('🔄 Wkładasz naboje i kręcisz bębnem...')] });
      await sleep(delay(1000));
      await interaction.editReply({ embeds: [frame('😰 Przykładasz lufę do skroni...')] });
      await sleep(delay(1100));
      await interaction.editReply({ embeds: [frame('🤞 Palec na spuście...')] });
      await sleep(delay(1200));

      const dead = bullets.has(fired);
      const res = settle(userId, bet, dead ? 0 : Math.floor(bet * mult));
      interaction.meta?.({ ...info, net: res.net });
      await interaction.editReply({
        embeds: [
          resultEmbed({
            title: '🔫 Rosyjska ruletka',
            description: `${header}\n\n${dead ? '💥 **BANG!** Niestety...' : '😮‍💨 **Klik.** Przeżyłeś!'}\n${drum(bullets, fired)}`,
            bet,
            res,
            userId,
          }),
        ],
      });
    });
  },
};
