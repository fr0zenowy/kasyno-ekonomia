require('dotenv').config();
const { REST, Routes } = require('discord.js');
const { loadCommands } = require('./loader');

const { DISCORD_TOKEN, CLIENT_ID, GUILD_ID } = process.env;
if (!DISCORD_TOKEN || !CLIENT_ID) {
  console.error('❌ Uzupełnij DISCORD_TOKEN i CLIENT_ID w pliku .env (zobacz README).');
  process.exit(1);
}

const body = [...loadCommands().values()].map((c) => c.data.toJSON());
const rest = new REST().setToken(DISCORD_TOKEN);

(async () => {
  try {
    if (GUILD_ID) {
      await rest.put(Routes.applicationGuildCommands(CLIENT_ID, GUILD_ID), { body });
      console.log(`✅ Zarejestrowano ${body.length} komend na serwerze ${GUILD_ID} (działają od razu).`);
    } else {
      await rest.put(Routes.applicationCommands(CLIENT_ID), { body });
      console.log(`✅ Zarejestrowano ${body.length} komend globalnie (Discord może je pokazać dopiero po ok. godzinie).`);
    }
  } catch (err) {
    console.error('❌ Nie udało się zarejestrować komend:', err);
    process.exit(1);
  }
})();
