const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { ITEMS, SHOP_ORDER } = require('../lib/data');
const { label, resolveItem, suggestItems } = require('../lib/items');
const { fmt, embed, COLORS } = require('../lib/util');
const { fail } = require('../lib/game');

// Narzędzia i trofeum zawsze wystarczy mieć jedno; inne przedmioty oznacza flaga `unique` w katalogu.
const SINGLE_TYPES = new Set(['tool', 'trophy']);

module.exports = {
  data: new SlashCommandBuilder()
    .setName('kup')
    .setDescription('Kup przedmiot ze sklepu (nazwa albo id, np. wedka)')
    .addStringOption((o) => o.setName('przedmiot').setDescription('Co kupić – zacznij pisać, a podpowiem').setRequired(true).setAutocomplete(true))
    .addIntegerOption((o) => o.setName('ilosc').setDescription('Ile sztuk (domyślnie 1)').setMinValue(1).setMaxValue(100)),

  async autocomplete(interaction) {
    await interaction.respond(suggestItems(interaction.options.getFocused(), SHOP_ORDER));
  },

  async execute(interaction) {
    const qty = interaction.options.getInteger('ilosc') ?? 1;
    const typed = interaction.options.getString('przedmiot');
    const id = resolveItem(typed, SHOP_ORDER);
    if (!id) return fail(interaction, `❌ Nie ma w sklepie przedmiotu „${typed}”. Zobacz listę: \`/sklep\`.`);
    const item = ITEMS[id];

    const userId = interaction.user.id;
    if (item.unique || SINGLE_TYPES.has(item.type)) {
      if (qty > 1) return fail(interaction, `❌ ${label(id)} wystarczy mieć jedno.`);
      if (db.itemQty(userId, id) > 0) return fail(interaction, `❌ Już masz ${label(id)}.`);
    }
    if (item.requires && db.itemQty(userId, item.requires) < 1) {
      return fail(interaction, `🔒 Najpierw kup ${label(item.requires)}, a potem to ulepszenie.`);
    }

    const cost = item.price * qty;
    const u = db.getUser(userId);
    if (u.wallet < cost) {
      return fail(
        interaction,
        `❌ Potrzebujesz ${fmt(cost)}, a masz ${fmt(u.wallet)} w portfelu. (Pieniądze z banku: \`/wyplac\`.)`,
      );
    }
    if (!db.takeWallet(userId, cost)) return fail(interaction, '❌ Nie udało się zapłacić.');
    db.addItem(userId, id, qty);

    await interaction.reply({
      embeds: [
        embed({
          title: '🛍️ Zakup udany',
          color: COLORS.win,
          description: `Kupiłeś **${qty}× ${label(id)}** za ${fmt(cost)}.\n*${item.desc}*`,
          footer: `Portfel: ${fmt(db.getUser(userId).wallet)}`,
        }),
      ],
    });
  },
};
