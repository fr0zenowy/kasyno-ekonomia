const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { ITEMS, SHOP_CATEGORIES, SHOP_ORDER } = require('../lib/data');
const { fmt, embed, COLORS, norm } = require('../lib/util');
const { fail } = require('../lib/game');

// Takie przedmioty wystarczy mieć jeden – przy nich pokazujemy ✅, gdy już je masz.
const owned = (userId, id) => (ITEMS[id].unique || ['tool', 'trophy'].includes(ITEMS[id].type)) && db.itemQty(userId, id) > 0;

module.exports = {
  data: new SlashCommandBuilder()
    .setName('sklep')
    .setDescription('Zobacz, co możesz kupić: przegląd albo jedną kategorię z opisami')
    .addStringOption((o) =>
      o
        .setName('kategoria')
        .setDescription('Pokaż jedną kategorię ze szczegółami')
        .addChoices(...SHOP_CATEGORIES.map((c) => ({ name: c.title, value: c.id }))),
    ),

  async execute(interaction) {
    const userId = interaction.user.id;
    const wallet = db.getUser(userId).wallet;
    const typed = interaction.options.getString('kategoria');

    if (typed) {
      const k = norm(typed);
      const cat = SHOP_CATEGORIES.find((c) => c.id === typed) ?? SHOP_CATEGORIES.find((c) => norm(c.id) === k || norm(c.title).includes(k));
      if (!cat) return fail(interaction, `❌ Nie ma kategorii „${typed}”. Dostępne: ${SHOP_CATEGORIES.map((c) => `\`${c.id}\``).join(', ')}.`);
      const lines = cat.items.map((id) => {
        const it = ITEMS[id];
        return `${it.emoji} **${it.name}** – ${fmt(it.price)}${owned(userId, id) ? ' ✅' : ''}\n┗ ${it.desc} *(\`${id}\`)*`;
      });
      return interaction.reply({
        embeds: [
          embed({
            title: `🛒 ${cat.title}`,
            color: COLORS.info,
            description: `Kupisz: \`/kup przedmiot\` (nazwa albo id w nawiasie).\n\n${lines.join('\n')}`,
            footer: `Twój portfel: ${fmt(wallet)} • ✅ = już masz`,
          }),
        ],
      });
    }

    const fields = SHOP_CATEGORIES.map((cat) => ({
      name: cat.title,
      value: cat.items.map((id) => `${ITEMS[id].emoji} **${ITEMS[id].name}** – ${fmt(ITEMS[id].price)}${owned(userId, id) ? ' ✅' : ''}`).join('\n'),
    }));
    await interaction.reply({
      embeds: [
        embed({
          title: `🛒 Sklep (${SHOP_ORDER.length} przedmiotów)`,
          color: COLORS.info,
          description:
            'Szczegóły i opisy: `/sklep kategoria`. Kupujesz: `/kup przedmiot` (nazwa albo id).\n' +
            'Większy limit banku to osobne ulepszenia: `/bank lista`. Aktywne bonusy: `/mnozniki`.',
          fields,
          footer: `Twój portfel: ${fmt(wallet)} • ✅ = już masz`,
        }),
      ],
    });
  },
};
