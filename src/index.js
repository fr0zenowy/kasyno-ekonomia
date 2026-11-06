require('dotenv').config();
const { Client, GatewayIntentBits, Events, ActivityType, MessageFlags } = require('discord.js');
const { loadCommands } = require('./loader');
const lottery = require('./lib/lottery');
const { handleMessage } = require('./prefix');
const { connect } = require('./connect');

if (!process.env.DISCORD_TOKEN) {
  console.error('❌ Brak DISCORD_TOKEN w pliku .env. Uruchom `npm run setup` albo skopiuj .env.example do .env i uzupełnij.');
  process.exit(1);
}

const PREFIX = process.env.PREFIX || '!';
const commands = loadCommands();

function createClient(withPrefix) {
  // Komendy z prefiksem czytają treść wiadomości, a to wymaga uprawnienia „Message Content Intent” w Developer Portal.
  const intents = [GatewayIntentBits.Guilds];
  if (withPrefix) intents.push(GatewayIntentBits.GuildMessages, GatewayIntentBits.MessageContent);
  const client = new Client({ intents });
  process.env.PREFIX_ACTIVE = withPrefix ? '1' : '0'; // czyta go /pomoc, żeby nie obiecywać nieistniejącego prefiksu

  client.once(Events.ClientReady, (c) => {
    console.log(`✅ Zalogowano jako ${c.user.tag} • komend: ${commands.size} • serwerów: ${c.guilds.cache.size}`);
    console.log(withPrefix ? `✅ Komendy z prefiksem „${PREFIX}” włączone (np. ${PREFIX}saldo).` : 'ℹ️  Komendy z prefiksem wyłączone (działają tylko komendy slash).');
    c.user.setActivity(withPrefix ? `/pomoc lub ${PREFIX}pomoc • kasyno` : '/pomoc • kasyno i ekonomia', { type: ActivityType.Playing });
    lottery.startScheduler(c);
  });

  client.on(Events.Error, (err) => console.error('Błąd klienta Discorda:', err));

  client.on(Events.InteractionCreate, async (interaction) => {
    try {
      if (interaction.isAutocomplete()) {
        const cmd = commands.get(interaction.commandName);
        if (cmd?.autocomplete) await cmd.autocomplete(interaction);
        return;
      }
      if (!interaction.isChatInputCommand()) return;

      const cmd = commands.get(interaction.commandName);
      if (!cmd) return;
      await cmd.execute(interaction);
    } catch (err) {
      console.error(`Błąd w komendzie ${interaction.commandName ?? '?'}:`, err);
      if (!interaction.isRepliable() || interaction.isAutocomplete()) return;
      const payload = { content: '😵 Coś poszło nie tak. Spróbuj jeszcze raz!', flags: MessageFlags.Ephemeral };
      if (interaction.replied || interaction.deferred) await interaction.followUp(payload).catch(() => {});
      else await interaction.reply(payload).catch(() => {});
    }
  });

  if (withPrefix) {
    client.on(Events.MessageCreate, async (message) => {
      try {
        await handleMessage(message, commands, { prefix: PREFIX });
      } catch (err) {
        console.error('Błąd obsługi wiadomości z prefiksem:', err);
      }
    });
  }

  return client;
}

async function main() {
  const result = await connect(createClient, process.env.DISCORD_TOKEN, process.env.ENABLE_PREFIX !== 'false');
  if (result.ok) return;

  console.error(`❌ Nie udało się zalogować: ${result.intents ? 'brak wymaganych uprawnień (intentów)' : result.error?.message}`);
  console.error('Sprawdź token w pliku .env (Developer Portal → Bot → Reset Token) i spróbuj ponownie.');
  // Bez process.exit(): na Windowsie wywołuje asercję libuv. Klient jest zamknięty, więc proces skończy się sam.
  process.exitCode = 1;
}

// Żeby pojedynczy błąd (np. w kolektorze przycisków) nie wywalił całego bota.
process.on('unhandledRejection', (err) => console.error('Nieobsłużony błąd:', err));

main();
