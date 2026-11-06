// Serwer strony do oglądania i zabawy bez ruszania prawdziwej bazy: osobny plik bazy w katalogu tymczasowym
// i dwa gotowe konta testowe (demo / rywal, hasło: demo1234).
//   node --disable-warning=ExperimentalWarning test/dev-web.js
const os = require('node:os');
const path = require('node:path');

process.env.DB_PATH ||= path.join(os.tmpdir(), 'economy-web-dev.db');
process.env.WEB_PORT ||= '3011';

const db = require('../src/lib/db');
const accounts = require('../src/web/accounts');
const { startServer } = require('../src/web/server');

(async () => {
  for (const name of ['demo', 'rywal']) {
    if (accounts.search(name, 1).some((a) => a.username === name)) continue;
    const r = await accounts.register(name, 'demo1234');
    if (r.ok && name === 'demo') db.setWallet(r.account.id, 250_000);
  }
  console.log(`Baza: ${process.env.DB_PATH}`);
  startServer();
})();
