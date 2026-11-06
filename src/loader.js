const fs = require('node:fs');
const path = require('node:path');

/** Wczytuje wszystkie pliki z src/commands. Każdy eksportuje { data, execute, autocomplete? }. */
function loadCommands() {
  const dir = path.join(__dirname, 'commands');
  const commands = new Map();
  for (const file of fs.readdirSync(dir).filter((f) => f.endsWith('.js'))) {
    const cmd = require(path.join(dir, file));
    if (!cmd.data || typeof cmd.execute !== 'function') {
      console.warn(`⚠️  Pomijam ${file}: brak "data" lub "execute".`);
      continue;
    }
    if (commands.has(cmd.data.name)) throw new Error(`Zduplikowana komenda: ${cmd.data.name} (${file})`);
    commands.set(cmd.data.name, cmd);
  }
  return commands;
}

module.exports = { loadCommands };
