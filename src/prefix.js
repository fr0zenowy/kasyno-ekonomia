// Komendy z prefiksem (np. !moneta 100 orzel): wiadomość jest zamieniana na obiekt, który udaje
// interakcję slash, więc wszystkie komendy z src/commands działają bez żadnych zmian.
const { ApplicationCommandOptionType: T } = require('discord.js');
const { norm } = require('./lib/util');

// Skróty i angielskie nazwy. Wartość to komenda albo [komenda, podkomenda].
const ALIASES = {
  bal: 'saldo', balance: 'saldo', konto: 'saldo', kasa: 'saldo',
  help: 'pomoc', komendy: 'pomoc', cmds: 'pomoc',
  profile: 'profil', lb: 'top', leaderboard: 'top', ranking: 'top',
  dep: 'wplac', deposit: 'wplac', withdraw: 'wyplac', wyp: 'wyplac', pay: 'przelew',
  inv: 'ekwipunek', eq: 'ekwipunek', shop: 'sklep', buy: 'kup', sell: 'sprzedaj', use: 'uzyj',
  work: 'praca', job: 'praca', beg: 'zebraj', fish: 'ryby', mine: 'kopalnia', hunt: 'polowanie',
  crime: 'przestepstwo', rob: 'okradnij', heist: 'napad',
  coinflip: 'moneta', cf: 'moneta', flip: 'moneta', slots: 'sloty', dice: 'kosci', roulette: 'ruletka',
  wheel: 'kolo', scratch: 'zdrapka', bj: 'blackjack', mines: 'miny', rps: 'knp', horses: 'wyscigi',
  duel: 'pojedynek', lottery: 'loteria', crates: 'skrzynie', crate: 'skrzynie', skrzynki: 'skrzynie', skrzynka: 'skrzynie',
  otworz: ['skrzynie', 'otworz'], open: ['skrzynie', 'otworz'], ulepsz: ['bank', 'ulepsz'], upgrade: ['bank', 'ulepsz'],
  boosty: 'mnozniki', boosts: 'mnozniki', multipliers: 'mnozniki', bonusy: 'mnozniki',
  chicken: 'kurczak', chickenroad: 'kurczak', tower: 'wieza', rr: 'rewolwer', revolver: 'rewolwer', roulette2: 'rewolwer',
  hourly: 'godzinna', forest: 'las', grzyby: 'las', trash: 'smietnik', scavenge: 'smietnik', busk: 'uliczny', street: 'uliczny',
  business: 'biznes', biz: 'biznes', farm: 'farma',
  zbierz: ['biznes', 'zbierz'], collect: ['biznes', 'zbierz'], zbiory: ['farma', 'zbierz'], harvest: ['farma', 'zbierz'], sadz: ['farma', 'zasadz'], plant: ['farma', 'zasadz'],
};

// Podkomenda używana, gdy gracz jej nie poda (np. samo „!praca”).
const DEFAULT_SUB = { praca: 'wykonaj', loteria: 'info', skrzynie: 'lista', bank: 'info', biznes: 'lista', farma: 'pola' };

const USER_RE = /^(?:<@!?(\d{17,20})>|(\d{17,20}))$/;
const defsCache = new WeakMap();
const getDefs = (cmd) => {
  if (!defsCache.has(cmd)) defsCache.set(cmd, cmd.data.toJSON());
  return defsCache.get(cmd);
};

/** Dopasowanie do listy wyborów: dokładna wartość, a potem jednoznaczny początek wartości lub nazwy. */
function matchChoice(opt, token, { exactOnly = false } = {}) {
  const k = norm(token);
  if (!k) return undefined;
  const exact = opt.choices.find((c) => norm(c.value) === k);
  if (exact || exactOnly) return exact;
  const starts = opt.choices.filter((c) => norm(c.value).startsWith(k) || norm(c.name).startsWith(k));
  return starts.length === 1 ? starts[0] : undefined;
}

function accepts(opt, token) {
  switch (opt.type) {
    case T.String:
      return opt.choices?.length ? Boolean(matchChoice(opt, token)) : true;
    case T.Integer:
      return /^[+-]?\d+$/.test(token);
    case T.Number:
      return /^[+-]?\d+(?:[.,]\d+)?$/.test(token);
    case T.User:
      return USER_RE.test(token);
    default:
      return false;
  }
}

async function resolveUser(token, message) {
  const m = USER_RE.exec(token);
  const id = m[1] ?? m[2];
  return message.mentions?.users?.get(id) ?? (await message.client.users.fetch(id).catch(() => null));
}

/**
 * Przypisuje kolejne słowa do opcji komendy. Idzie od lewej: słowo trafia do pierwszej opcji, która je przyjmie,
 * z jednym wyjątkiem – dokładne trafienie w listę wyborów (np. „orzel”) ma pierwszeństwo, więc
 * „!moneta orzel 100” też działa. Zwraca { values } albo { error }.
 */
async function parseOptions(optionDefs, tokens, message) {
  const remaining = optionDefs.slice();
  const picked = new Map();

  for (const token of tokens) {
    let idx = remaining.findIndex((o) => o.type === T.String && o.choices?.length && matchChoice(o, token, { exactOnly: true }));
    if (idx === -1) idx = remaining.findIndex((o) => accepts(o, token));
    if (idx === -1) return { error: `Nie rozumiem „${token}”.` };
    const [opt] = remaining.splice(idx, 1);
    picked.set(opt, token);
  }

  const missing = remaining.filter((o) => o.required);
  if (missing.length) return { error: `Brakuje: ${missing.map((o) => o.name).join(', ')}.` };

  const values = {};
  for (const [opt, token] of picked) {
    if (opt.type === T.String) {
      values[opt.name] = opt.choices?.length ? matchChoice(opt, token).value : token;
    } else if (opt.type === T.Integer || opt.type === T.Number) {
      const n = opt.type === T.Integer ? parseInt(token, 10) : parseFloat(token.replace(',', '.'));
      if ((opt.min_value !== undefined && n < opt.min_value) || (opt.max_value !== undefined && n > opt.max_value)) {
        return { error: `„${opt.name}” musi być liczbą od ${opt.min_value ?? '−∞'} do ${opt.max_value ?? '∞'}.` };
      }
      if (opt.choices?.length && !opt.choices.some((c) => c.value === n)) {
        return { error: `„${opt.name}” musi być jedną z wartości: ${opt.choices.map((c) => c.value).join(', ')}.` };
      }
      values[opt.name] = n;
    } else if (opt.type === T.User) {
      const user = await resolveUser(token, message);
      if (!user) return { error: 'Nie znalazłem takiego użytkownika. Oznacz go przez @.' };
      values[opt.name] = user;
    }
  }
  return { values };
}

/** Podpowiedź użycia, np. „!moneta <zaklad> <orzel|reszka>”. */
function usageOf(prefix, name, optionDefs, sub = null) {
  const parts = optionDefs.map((o) => {
    const label = o.type === T.String && o.choices?.length && o.choices.length <= 6 ? o.choices.map((c) => c.value).join('|') : o.name;
    return o.required ? `<${label}>` : `[${label}]`;
  });
  return `${prefix}${name}${sub ? ` ${sub}` : ''}${parts.length ? ` ${parts.join(' ')}` : ''}`;
}

/** Obiekt udający ChatInputCommandInteraction, odpowiadający zwykłymi wiadomościami. */
function createInteraction(message, commandName, sub, values) {
  let sent = null;
  const clean = (payload) => {
    if (typeof payload === 'string') return { content: payload };
    const { flags, ...rest } = payload; // wiadomości nie mogą być „ephemeral”
    return rest;
  };
  const mentions = { allowedMentions: { parse: ['users'], repliedUser: false } };
  const send = async (payload) => {
    try {
      return await message.reply({ ...clean(payload), ...mentions });
    } catch {
      return message.channel.send({ ...clean(payload), ...mentions }); // np. oryginał skasowany albo brak Read Message History
    }
  };

  const i = {
    user: message.author,
    member: message.member,
    guild: message.guild,
    channel: message.channel,
    channelId: message.channelId ?? message.channel?.id,
    client: message.client,
    commandName,
    fromPrefix: true,
    replied: false,
    deferred: false,
    options: {
      getString: (n) => (typeof values[n] === 'string' ? values[n] : null),
      getInteger: (n) => (Number.isInteger(values[n]) ? values[n] : null),
      getNumber: (n) => (typeof values[n] === 'number' ? values[n] : null),
      getUser: (n) => values[n] ?? null,
      getSubcommand: () => sub,
      getFocused: () => '',
    },
    isRepliable: () => true,
    isChatInputCommand: () => true,
    isAutocomplete: () => false,
    async reply(payload) {
      if (i.replied || i.deferred) throw new Error('Już odpowiedziano na tę komendę');
      i.replied = true;
      sent = await send(payload);
      return sent;
    },
    async deferReply() {
      i.deferred = true;
      await message.channel?.sendTyping?.().catch(() => {});
    },
    async editReply(payload) {
      if (!sent) {
        i.replied = true;
        sent = await send(payload);
        return sent;
      }
      return sent.edit(clean(payload));
    },
    async followUp(payload) {
      return message.channel.send({ ...clean(payload), ...mentions });
    },
    async fetchReply() {
      return sent;
    },
  };
  return i;
}

const lastUse = new Map();

/**
 * Obsługuje wiadomość z prefiksem. Zwraca true, jeśli wiadomość była komendą (nawet błędną), false jeśli trzeba ją zignorować.
 */
async function handleMessage(message, commands, { prefix = '!', throttleMs = 700 } = {}) {
  if (!message.author || message.author.bot || typeof message.content !== 'string' || !message.content.startsWith(prefix)) return false;
  const tokens = message.content.slice(prefix.length).trim().split(/\s+/).filter(Boolean);
  if (!tokens.length) return false;

  const word = norm(tokens.shift());
  const alias = ALIASES[word];
  const cmd = commands.get(Array.isArray(alias) ? alias[0] : alias ?? word);
  if (!cmd) return false;

  // Prosty hamulec przeciw spamowi komendami.
  const now = Date.now();
  if (now - (lastUse.get(message.author.id) ?? 0) < throttleMs) return true;
  if (lastUse.size > 5000) lastUse.clear();
  lastUse.set(message.author.id, now);

  const name = cmd.data.name;
  const defs = getDefs(cmd);
  const reply = (content) => message.reply({ content, allowedMentions: { parse: [], repliedUser: false } }).catch(() => message.channel.send(content).catch(() => {}));

  let optionDefs = defs.options ?? [];
  let sub = null;
  const subDefs = optionDefs.filter((o) => o.type === T.Subcommand);
  if (subDefs.length) {
    let chosen = Array.isArray(alias) ? subDefs.find((s) => s.name === alias[1]) : undefined;
    if (!chosen && tokens.length) {
      chosen = subDefs.find((s) => norm(s.name) === norm(tokens[0]));
      if (chosen) tokens.shift();
    }
    if (!chosen && DEFAULT_SUB[name]) chosen = subDefs.find((s) => s.name === DEFAULT_SUB[name]);
    if (!chosen) {
      await reply(`❌ Podaj, co chcesz zrobić: \`${prefix}${name} <${subDefs.map((s) => s.name).join('|')}>\``);
      return true;
    }
    sub = chosen.name;
    optionDefs = chosen.options ?? [];
  }

  let values = {};
  if (optionDefs.length) {
    const parsed = await parseOptions(optionDefs, tokens, message);
    if (parsed.error) {
      await reply(`❌ ${parsed.error}\nUżycie: \`${usageOf(prefix, name, optionDefs, sub)}\``);
      return true;
    }
    values = parsed.values;
  }

  const interaction = createInteraction(message, name, sub, values);
  try {
    await cmd.execute(interaction);
  } catch (err) {
    console.error(`Błąd w komendzie ${prefix}${name}:`, err);
    await reply('😵 Coś poszło nie tak. Spróbuj jeszcze raz!');
  }
  return true;
}

module.exports = { norm, ALIASES, DEFAULT_SUB, parseOptions, usageOf, createInteraction, handleMessage, matchChoice };
