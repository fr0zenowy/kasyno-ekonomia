const { SlashCommandBuilder } = require('discord.js');
const db = require('../lib/db');
const { ITEMS, BOOSTS, BONUSES, PROTECTIONS } = require('../lib/data');
const { openCrates, isCrate } = require('../lib/crates');
const { activate } = require('../lib/boosts');
const protect = require('../lib/protect');
const { label, resolveItem } = require('../lib/items');
const { embed, COLORS, shortDuration, norm, fmt } = require('../lib/util');
const { fail } = require('../lib/game');

// Co da się użyć komendą: skrzynie, eliksiry, ochrony w grach i koniczyna.
const USABLE = Object.keys(ITEMS).filter((id) => ['crate', 'boost', 'protection'].includes(ITEMS[id].type) || id === 'koniczyna');

module.exports = {
  USABLE,

  data: new SlashCommandBuilder()
    .setName('uzyj')
    .setDescription('Użyj przedmiotu: eliksir, polisa, tarcza, koniczyna albo skrzynia')
    .addStringOption((o) => o.setName('przedmiot').setDescription('Co użyć – podpowiadam to, co masz').setRequired(true).setAutocomplete(true)),

  async autocomplete(interaction) {
    const k = norm(interaction.options.getFocused());
    const owned = db
      .inventory(interaction.user.id)
      .filter((r) => USABLE.includes(r.item) && (!k || norm(r.item).includes(k) || norm(ITEMS[r.item].name).includes(k)));
    await interaction.respond(owned.slice(0, 25).map((r) => ({ name: `${label(r.item)} ×${r.qty}`.slice(0, 100), value: r.item })));
  },

  async execute(interaction) {
    const userId = interaction.user.id;
    const typed = interaction.options.getString('przedmiot');
    const id = resolveItem(typed, USABLE);
    if (!id) return fail(interaction, `❌ Nie wiem, co chcesz użyć („${typed}”). Użyć da się: eliksiry, polisy, tarcze, koniczynę i skrzynie. Zobacz \`/ekwipunek\`.`);

    if (isCrate(id)) return openCrates(interaction, id, 1);

    if (db.itemQty(userId, id) < 1) return fail(interaction, `❌ Nie masz ${label(id)}. Kup ${ITEMS[id].type === 'crate' ? 'ją' : 'go'} w \`/sklep\`.`);

    if (BOOSTS[id]) {
      db.takeItem(userId, id, 1);
      const boost = activate(userId, id);
      return interaction.reply({
        embeds: [
          embed({
            title: `${ITEMS[id].emoji} ${ITEMS[id].name} – aktywny!`,
            color: COLORS.win,
            description: `**${boost.label}**, jeszcze **${shortDuration(boost.left)}**.${boost.extended ? '\n⏳ Czas poprzedniego eliksiru został przedłużony.' : ''}\nWszystkie mnożniki: \`/mnozniki\``,
          }),
        ],
      });
    }

    if (PROTECTIONS[id]) {
      db.takeItem(userId, id, 1);
      const armed = protect.arm(userId, id);
      const p = PROTECTIONS[id];
      const text =
        p.kind === 'polisa'
          ? `Gdy w jakiejś grze **całkowicie przegrasz** rundę, stawka (do ${fmt(p.cap)}) wróci do Ciebie.`
          : `**Pierwszy zły klik** w grze krokowej (miny, kurczak, wieża, hilo, blackjack) po prostu się nie liczy i grasz dalej. Działa przy stawkach do ${fmt(p.cap)}.`;
      return interaction.reply({
        embeds: [
          embed({
            title: `${ITEMS[id].emoji} ${ITEMS[id].name} – uzbrojona!`,
            color: COLORS.win,
            description: `${text}\nZużyje się dopiero wtedy, gdy zadziała. Uzbrojonych sztuk tego rodzaju: **${armed}**.\n*Ochrona działa tylko przy stawce nieprzekraczającej limitu; większej nie zużywa.*`,
          }),
        ],
      });
    }

    if (id === 'koniczyna') {
      db.takeItem(userId, 'koniczyna', 1);
      db.addBuff(userId, 'koniczyna', 5);
      return interaction.reply({
        embeds: [
          embed({
            title: '🍀 Szczęście Ci sprzyja!',
            color: COLORS.win,
            description: `Twoje następne **${db.buffUses(userId, 'koniczyna')}** wygrane w hazardzie dadzą o **${BONUSES.cloverPct * 100}% więcej zysku** (maks. ${BONUSES.cloverMax} monet na wygraną).`,
          }),
        ],
      });
    }

    return fail(interaction, '❌ Tego nie da się użyć.');
  },
};
