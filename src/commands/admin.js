const { SlashCommandBuilder, PermissionFlagsBits } = require('discord.js');
const db = require('../lib/db');
const { ITEMS } = require('../lib/data');
const { resolveItem, suggestItems } = require('../lib/items');
const { fmt } = require('../lib/util');
const { fail } = require('../lib/game');

// Przedmioty, które da się komuś nadać (te z ceną: sklep i mały sejf).
const PRICED = Object.keys(ITEMS).filter((id) => ITEMS[id].price);

const owners = () =>
  (process.env.OWNER_IDS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);

module.exports = {
  data: new SlashCommandBuilder()
    .setName('admin')
    .setDescription('Komendy właściciela bota')
    .setDefaultMemberPermissions(PermissionFlagsBits.Administrator)
    .addSubcommand((s) =>
      s
        .setName('dodaj')
        .setDescription('Dodaj monety do portfela gracza')
        .addUserOption((o) => o.setName('uzytkownik').setDescription('Gracz').setRequired(true))
        .addIntegerOption((o) => o.setName('kwota').setDescription('Ile dodać').setRequired(true).setMinValue(1)),
    )
    .addSubcommand((s) =>
      s
        .setName('usun')
        .setDescription('Zabierz monety z portfela gracza')
        .addUserOption((o) => o.setName('uzytkownik').setDescription('Gracz').setRequired(true))
        .addIntegerOption((o) => o.setName('kwota').setDescription('Ile zabrać').setRequired(true).setMinValue(1)),
    )
    .addSubcommand((s) =>
      s
        .setName('ustaw')
        .setDescription('Ustaw portfel gracza na konkretną kwotę')
        .addUserOption((o) => o.setName('uzytkownik').setDescription('Gracz').setRequired(true))
        .addIntegerOption((o) => o.setName('kwota').setDescription('Nowy stan portfela').setRequired(true).setMinValue(0)),
    )
    .addSubcommand((s) =>
      s
        .setName('przedmiot')
        .setDescription('Daj graczowi przedmiot')
        .addUserOption((o) => o.setName('uzytkownik').setDescription('Gracz').setRequired(true))
        .addStringOption((o) => o.setName('id').setDescription('Przedmiot (zacznij pisać, a podpowiem)').setRequired(true).setAutocomplete(true))
        .addIntegerOption((o) => o.setName('ilosc').setDescription('Ile sztuk').setMinValue(1).setMaxValue(1000)),
    )
    .addSubcommand((s) =>
      s
        .setName('cooldowny')
        .setDescription('Zeruj wszystkie cooldowny gracza')
        .addUserOption((o) => o.setName('uzytkownik').setDescription('Gracz').setRequired(true)),
    ),

  async autocomplete(interaction) {
    await interaction.respond(suggestItems(interaction.options.getFocused(), PRICED));
  },

  async execute(interaction) {
    if (!owners().includes(interaction.user.id)) {
      return fail(interaction, '🔒 Ta komenda jest tylko dla właściciela bota (ustaw OWNER_IDS w pliku .env).');
    }

    const sub = interaction.options.getSubcommand();
    const target = interaction.options.getUser('uzytkownik');
    if (target.bot) return fail(interaction, '🤖 Boty nie mają kont.');
    const u = db.getUser(target.id);

    if (sub === 'dodaj') {
      const amount = interaction.options.getInteger('kwota');
      db.addWallet(target.id, amount);
      return interaction.reply({ content: `✅ Dodano ${fmt(amount)} dla ${target}. Portfel: ${fmt(u.wallet + amount)}`, flags: 64 });
    }
    if (sub === 'usun') {
      const amount = Math.min(interaction.options.getInteger('kwota'), u.wallet);
      db.takeWallet(target.id, amount);
      return interaction.reply({ content: `✅ Zabrano ${fmt(amount)} od ${target}.`, flags: 64 });
    }
    if (sub === 'ustaw') {
      const amount = interaction.options.getInteger('kwota');
      db.setWallet(target.id, amount);
      return interaction.reply({ content: `✅ Portfel ${target} ustawiony na ${fmt(amount)}.`, flags: 64 });
    }
    if (sub === 'przedmiot') {
      const typed = interaction.options.getString('id');
      const id = resolveItem(typed, PRICED);
      if (!id) return fail(interaction, `❌ Nie znam przedmiotu „${typed}”.`);
      const qty = interaction.options.getInteger('ilosc') ?? 1;
      db.addItem(target.id, id, qty);
      return interaction.reply({ content: `✅ ${target} dostaje ${qty}× ${ITEMS[id].emoji} ${ITEMS[id].name}.`, flags: 64 });
    }
    if (sub === 'cooldowny') {
      db.db.prepare('DELETE FROM cooldowns WHERE user_id = ?').run(target.id);
      return interaction.reply({ content: `✅ Wyzerowano cooldowny ${target}.`, flags: 64 });
    }
  },
};
