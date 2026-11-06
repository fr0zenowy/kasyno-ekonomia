const { SlashCommandBuilder } = require('discord.js');
const { embed, COLORS } = require('../lib/util');

module.exports = {
  data: new SlashCommandBuilder().setName('pomoc').setDescription('Lista wszystkich komend bota'),

  async execute(interaction) {
    // index.js ustawia PREFIX_ACTIVE tylko wtedy, gdy komendy z prefiksem faktycznie działają.
    const prefix = process.env.PREFIX || '!';
    const prefixActive = process.env.PREFIX_ACTIVE === '1';

    const fields = [
      {
        name: '💳 Konto',
        value: '`/saldo` `/profil` `/top` `/wplac` `/wyplac` `/przelew` `/ekwipunek`\n`/bank` – limit na start 100 000, ulepszenia (`/bank ulepsz`) podnoszą go aż do miliardów',
      },
      {
        name: '🎁 Darmowe zarobki',
        value:
          '`/daily` • `/weekly` • `/godzinna` (premia co godzinę)\n' +
          '`/zebraj` • `/las` 🍄 • `/smietnik` 🗑️ • `/uliczny` 🎸 (instrument = większe napiwki) • `/trivia` • `/matma`',
      },
      {
        name: '💼 Praca i łowy',
        value:
          '`/praca` (zawody wg poziomu)\n' +
          '`/ryby` 🎣 • `/kopalnia` ⛏️ • `/polowanie` 🔫 (narzędzia ze `/sklep`, aż do legendarnych)\n' +
          '`/przestepstwo` 🦹 • `/okradnij` • `/napad` 🏦 (z ekipą)',
      },
      {
        name: '🏢 Płatne zarobki',
        value:
          '`/biznes` – kupuj i ulepszaj biznesy, zbieraj dochód pasywny (`/biznes zbierz`)\n' +
          '`/farma` – sadź uprawy, a po czasie zbieraj plon wart więcej niż nasiona',
      },
      {
        name: '🎰 Hazard',
        value:
          '`/moneta` • `/sloty` • `/kosci` • `/ruletka` • `/kolo` • `/zdrapka` • `/wyscigi` • `/knp`\n' +
          '`/blackjack` • `/miny` • `/crash` • `/hilo` • `/kurczak` 🐔 (Chicken Road) • `/wieza` 🗼\n' +
          '`/plinko` 🎯 • `/limbo` 🚀 (sam wybierasz cel nawet x1000) • `/szansa` 🎲 • `/rewolwer` 🔫\n' +
          '`/pojedynek` (PvP) • `/jackpot` (wiele osób, jedna wygrywa) • `/loteria`',
      },
      {
        name: '🛒 Sklep i przedmioty',
        value:
          '`/sklep` (przegląd, `/sklep kategoria` ze szczegółami) • `/kup` • `/sprzedaj` • `/uzyj`\n' +
          '80+ przedmiotów: narzędzia, zwierzaki, karty VIP, eliksiry, luksus. Aktywne bonusy: `/mnozniki`',
      },
      {
        name: '🧾 Ochrona w grach',
        value:
          '**Polisa** zwraca stawkę po całkowitej przegranej. **Tarcza** anuluje pierwszy zły klik (mina, auto, zła karta) w grach krokowych: `/miny`, `/kurczak`, `/wieza`, `/hilo`, `/blackjack`.\n' +
          'Uzbrajasz je `/uzyj`, zużywają się dopiero, gdy zadziałają, a chronią stawki do limitu swojego poziomu.',
      },
      {
        name: '🎁 Skrzynie',
        value:
          '`/skrzynie lista` – 6 rodzajów (od drewnianej po legendarną) i szanse na rzadkości\n' +
          '`/skrzynie otworz` – otwieranie z animacją (do 10 naraz). Skrzynie dostajesz też z `/daily`, `/weekly`, awansów co 5 poziomów i łupów.',
      },
    ];

    if (prefixActive) {
      fields.push({
        name: `⌨️ Komendy z prefiksem „${prefix}”`,
        value:
          `Każdą komendę wpiszesz też jako \`${prefix}nazwa\`, np. \`${prefix}saldo\`, \`${prefix}moneta 100 orzel\`, \`${prefix}kurczak 500 trudny\`, \`${prefix}limbo 1k 10\`, \`${prefix}biznes kup kiosk\`.\n` +
          `Argumenty podajesz po spacji. Skróty: \`${prefix}bj\` \`${prefix}bal\` \`${prefix}cf\` \`${prefix}ulepsz\` \`${prefix}otworz\` \`${prefix}zbierz\`. Zła komenda? Bot podpowie, jak jej użyć.`,
      });
    }

    fields.push({
      name: '💡 Wskazówki',
      value:
        '• Pieniądze w **banku** są bezpieczne przed `/okradnij`, ale w hazardzie liczy się tylko **portfel**.\n' +
        '• Biznesy i farma zarabiają także wtedy, gdy Cię nie ma. Wpadaj odebrać zarobki.\n' +
        '• Kłódka 🔒 chroni przed okradzeniem, łom 🪓 ją przebija, pies 🐕 i alarm 🚨 odstraszają złodziei.',
    });

    await interaction.reply({
      embeds: [
        embed({
          title: '📖 Pomoc – bot ekonomiczny',
          color: COLORS.info,
          description:
            'Zaczynasz z **500 💰**. Zarabiaj, hazarduj się, kupuj sprzęt i wspinaj się w rankingu!\n' +
            'Stawki możesz wpisywać jako `500`, `2k`, `1.5m`, `50%`, `half` albo `all`.',
          fields,
          footer: 'Graj odpowiedzialnie – to tylko wirtualne monety 🙂',
        }),
      ],
    });
  },
};
