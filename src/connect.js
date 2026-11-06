const { Events } = require('discord.js');

// Kody zamknięcia bramki Discorda, po których discord.js nie próbuje się łączyć ponownie.
const DISALLOWED_INTENTS = 4014;
const AUTHENTICATION_FAILED = 4004;
const isIntentsMessage = (err) => /disallowed intents/i.test(err?.message ?? '');

/**
 * Loguje klienta i mówi, jak się skończyło: 'ok', 'intents' (brak włączonego „Message Content Intent”)
 * albo { error }. Samo `await client.login()` nie wystarczy: przy braku uprawnienia discord.js nie odrzuca
 * obietnicy, tylko emituje `shardDisconnect` i zdarzenie `error`.
 */
function loginClient(client, token) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (result) => {
      if (settled) return;
      settled = true;
      resolve(result);
    };

    client.on(Events.ShardDisconnect, (event) => {
      if (event?.code === DISALLOWED_INTENTS) finish('intents');
      else if (event?.code === AUTHENTICATION_FAILED) finish({ error: new Error('Discord odrzucił token (kod 4004).') });
    });
    client.on(Events.Error, (err) => {
      if (isIntentsMessage(err)) finish('intents');
    });

    client.login(token).then(
      () => finish('ok'),
      (err) => finish(isIntentsMessage(err) ? 'intents' : { error: err }),
    );
  });
}

/**
 * Łączy bota. Jeśli Discord odrzuci uprawnienie do czytania treści wiadomości („Message Content Intent”),
 * próbuje jeszcze raz bez komend z prefiksem, żeby bot przynajmniej działał na komendach slash.
 * `createClient(withPrefix)` buduje klienta. Zwraca { ok: true, client, withPrefix } albo { ok: false, intents, error }.
 */
async function connect(createClient, token, withPrefix, log = console) {
  for (;;) {
    const client = createClient(withPrefix);
    const result = await loginClient(client, token);
    if (result === 'ok') return { ok: true, client, withPrefix };

    try {
      await client.destroy();
    } catch {
      // klient i tak się nie połączył
    }
    if (result === 'intents' && withPrefix) {
      log.warn('⚠️  Komendy z prefiksem są wyłączone, bo w Developer Portal nie włączyłeś „Message Content Intent”.');
      log.warn('   Włącz je: discord.com/developers/applications → Twoja aplikacja → Bot → Privileged Gateway Intents → MESSAGE CONTENT INTENT.');
      log.warn('   Bot startuje teraz tylko z komendami slash (/). Po włączeniu intentu uruchom go ponownie.\n');
      withPrefix = false;
      continue;
    }
    return { ok: false, intents: result === 'intents', error: result.error };
  }
}

module.exports = { loginClient, connect, DISALLOWED_INTENTS };
