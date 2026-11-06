// Kreator konfiguracji: pyta o dane i zapisuje plik .env. Uruchom: npm run setup
const readline = require('node:readline/promises');
const fs = require('node:fs');
const path = require('node:path');

const envPath = path.join(__dirname, '..', '.env');

(async () => {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const ask = async (q) => (await rl.question(q)).trim();

  console.log('\n🛠️  Konfiguracja bota ekonomicznego\n');
  console.log('Dane znajdziesz w https://discord.com/developers/applications (szczegóły w README.md).\n');

  if (fs.existsSync(envPath)) {
    const answer = (await ask('Plik .env już istnieje. Nadpisać? (t/N): ')).toLowerCase();
    if (answer !== 't' && answer !== 'tak') {
      console.log('Anulowano – zostawiam istniejący .env.');
      rl.close();
      return;
    }
  }

  const token = await ask('1/5 Token bota (Bot → Reset Token): ');
  const clientId = await ask('2/5 Application ID (General Information): ');
  const guildId = await ask('3/5 ID Twojego serwera (zalecane, komendy działają od razu; Enter = pomiń): ');
  const owner = await ask('4/5 Twoje ID użytkownika dla /admin (Enter = pomiń): ');
  const channel = await ask('5/5 ID kanału na wyniki loterii (Enter = pomiń): ');
  rl.close();

  if (!token || !clientId) {
    console.error('\n❌ Token i Application ID są wymagane. Uruchom kreator ponownie.');
    process.exit(1);
  }

  const lines = [
    `DISCORD_TOKEN=${token}`,
    `CLIENT_ID=${clientId}`,
    `GUILD_ID=${guildId}`,
    `OWNER_IDS=${owner}`,
    `LOTTERY_CHANNEL_ID=${channel}`,
    '',
  ];
  fs.writeFileSync(envPath, lines.join('\n'), 'utf8');

  console.log('\n✅ Zapisano .env.');
  console.log('Dalej:\n  1) npm run deploy   – rejestruje komendy slash\n  2) npm start        – uruchamia bota\n');
  console.log('💡 Chcesz komendy z prefiksem „!” (np. !saldo)? Włącz MESSAGE CONTENT INTENT:');
  console.log('   Developer Portal → Twoja aplikacja → Bot → Privileged Gateway Intents.\n   Bez tego bot też działa, tylko na komendach /.\n');
})();
