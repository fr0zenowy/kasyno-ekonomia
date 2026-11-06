const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { ITEMS } = require('../lib/data');
const { label, sellValue } = require('../lib/items');
const { fmt, embed, COLORS } = require('../lib/util');
const { fail } = require('../lib/game');

module.exports = {
  data: new SlashCommandBuilder()
    .setName('sprzedaj')
    .setDescription('Sprzedaj przedmioty z ekwipunku (ryby, rudy, zwierzyna...)')
    .addStringOption((o) =>
      o.setName('przedmiot').setDescription('Co sprzedać („wszystko” = cały łup)').setRequired(true).setAutocomplete(true),
    )
    .addIntegerOption((o) => o.setName('ilosc').setDescription('Ile sztuk (domyślnie wszystkie)').setMinValue(1)),

  async autocomplete(interaction) {
    const focused = interaction.options.getFocused().toLowerCase();
    const inv = db.inventory(interaction.user.id);
    const options = [{ name: '💰 Wszystko (cały łup)', value: 'wszystko' }];
    for (const { item, qty } of inv) {
      const it = ITEMS[item];
      if (!it) continue;
      options.push({ name: `${it.emoji} ${it.name} (x${qty}) – ${sellValue(item)} / szt.`, value: item });
    }
    const filtered = options.filter((o) => o.name.toLowerCase().includes(focused) || o.value.includes(focused));
    await interaction.respond(filtered.slice(0, 25));
  },

  async execute(interaction) {
    const userId = interaction.user.id;
    const what = interaction.options.getString('przedmiot');
    const wanted = interaction.options.getInteger('ilosc');

    if (what === 'wszystko') {
      let total = 0;
      const lines = [];
      for (const { item, qty } of db.inventory(userId)) {
        if (ITEMS[item]?.type !== 'loot') continue;
        if (!db.takeItem(userId, item, qty)) continue;
        const value = sellValue(item) * qty;
        total += value;
        lines.push(`${label(item)} ×${qty} → ${fmt(value)}`);
      }
      if (!total) return fail(interaction, '🎒 Nie masz nic do sprzedania. Złap ryby (`/ryby`), kop (`/kopalnia`) albo poluj (`/polowanie`).');
      db.addWallet(userId, total);
      return interaction.reply({
        embeds: [
          embed({
            title: '💰 Sprzedano cały łup',
            color: COLORS.win,
            description: `${lines.join('\n')}\n\n**Razem: ${fmt(total)}**`,
            footer: `Portfel: ${fmt(db.getUser(userId).wallet)}`,
          }),
        ],
      });
    }

    const it = ITEMS[what];
    if (!it) return fail(interaction, '❌ Nie znam takiego przedmiotu.');
    const have = db.itemQty(userId, what);
    if (have < 1) return fail(interaction, `❌ Nie masz ${it.emoji} ${it.name}.`);
    const qty = Math.min(wanted ?? have, have);

    if (!db.takeItem(userId, what, qty)) return fail(interaction, '❌ Nie udało się sprzedać.');
    const value = sellValue(what) * qty;
    db.addWallet(userId, value);

    await interaction.reply({
      embeds: [
        embed({
          title: '💰 Sprzedano',
          color: COLORS.win,
          description: `Sprzedałeś **${qty}× ${it.emoji} ${it.name}** za ${fmt(value)}.`,
          footer: `Portfel: ${fmt(db.getUser(userId).wallet)}`,
        }),
      ],
    });
  },
};
