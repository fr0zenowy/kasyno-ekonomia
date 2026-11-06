const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { ITEMS } = require('../lib/data');
const { sellValue } = require('../lib/items');
const { fmt, embed, COLORS } = require('../lib/util');
const { EPHEMERAL } = require('../lib/game');

const GROUPS = [
  ['tool', '🧰 Narzędzia'],
  ['passive', '🛡️ Ulepszenia i zwierzaki'],
  ['protection', '🧾 Ochrona w grach (`/uzyj`)'],
  ['luxury', '💎 Kolekcja'],
  ['crate', '🎁 Skrzynie (`/skrzynie otworz`)'],
  ['boost', '⚡ Eliksiry (`/uzyj`)'],
  ['consumable', '🧪 Do użycia (`/uzyj`)'],
  ['loot', '🎒 Łup do sprzedaży (`/sprzedaj`)'],
  ['trophy', '🏆 Trofea'],
];

module.exports = {
  data: new SlashCommandBuilder()
    .setName('ekwipunek')
    .setDescription('Zobacz swój ekwipunek')
    .addUserOption((o) => o.setName('uzytkownik').setDescription('Czyj ekwipunek pokazać')),

  async execute(interaction) {
    const target = interaction.options.getUser('uzytkownik') ?? interaction.user;
    if (target.bot) return interaction.reply({ content: '🤖 Boty nie mają ekwipunku.', flags: EPHEMERAL });

    const inv = db.inventory(target.id).filter((r) => ITEMS[r.item]);
    const fields = [];
    let lootValue = 0;
    for (const [type, title] of GROUPS) {
      const rows = inv.filter((r) => ITEMS[r.item].type === type);
      if (!rows.length) continue;
      const value = rows
        .map((r) => {
          const it = ITEMS[r.item];
          if (type === 'loot') lootValue += sellValue(r.item) * r.qty;
          return `${it.emoji} ${it.name} ×**${r.qty}**`;
        })
        .join('\n');
      fields.push({ name: title, value, inline: true });
    }

    const clover = db.buffUses(target.id, 'koniczyna');
    const buffs = clover ? `🍀 Koniczyna: jeszcze **${clover}** wygranych z bonusem\nAktywne mnożniki: \`/mnozniki\`` : 'Aktywne mnożniki: `/mnozniki`';

    await interaction.reply({
      embeds: [
        embed({
          title: `🎒 Ekwipunek – ${target.displayName}`,
          color: COLORS.info,
          description: [
            inv.length ? `Wartość łupu: **${fmt(lootValue)}**` : 'Pusto! Zajrzyj do `/sklep`.',
            buffs,
          ]
            .filter(Boolean)
            .join('\n'),
          fields,
        }),
      ],
    });
  },
};
