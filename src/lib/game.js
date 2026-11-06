const { MessageFlags } = require('discord.js');
const db = require('./db');
const { BONUSES } = require('./data');
const protect = require('./protect');
const { fmt, parseAmount, embed, COLORS, duration } = require('./util');

const MIN_BET = 10;
const MAX_BET = 1_000_000_000;
const EPHEMERAL = MessageFlags.Ephemeral;

// Tempo animacji i timerów; w testach przyspieszone, żeby nie czekać.
const FAST = process.env.BOT_FAST === '1';
const delay = (ms) => (FAST ? 1 : ms);

// Nasłuchiwacze rozliczeń (strona używa ich do tablicy dużych wygranych). Błąd nasłuchiwacza nigdy nie psuje gry.
const settleListeners = new Set();
const onSettle = (fn) => {
  settleListeners.add(fn);
  return () => settleListeners.delete(fn);
};

// Gracze z rozpoczętą grą (zapobiega graniu kilkoma grami naraz i wyścigom o tę samą stawkę).
const active = new Set();
// Stawki zdjęte z portfela, a jeszcze nierozliczone: userId -> kwota. Dzięki temu błąd w trakcie gry nie kosztuje gracza pieniędzy.
const pending = new Map();

const fail = (interaction, content) => interaction.reply({ content, flags: EPHEMERAL });

/**
 * Waliduje stawkę i od razu zabiera ją z portfela. Zwraca stawkę albo null (odpowiedź z błędem już poszła).
 * Każda gra MUSI potem wywołać settle() albo refund() – oba zwalniają blokadę gracza.
 */
async function takeBet(interaction, input, { min = MIN_BET } = {}) {
  const id = interaction.user.id;
  if (active.has(id)) {
    await fail(interaction, '⏳ Masz już rozpoczętą grę. Dokończ ją najpierw!');
    return null;
  }
  const user = db.getUser(id);
  const bet = parseAmount(input, user.wallet);
  if (!bet) {
    await fail(interaction, '❌ Podaj poprawną stawkę, np. `500`, `2k`, `50%` albo `all`.');
    return null;
  }
  if (bet < min) {
    await fail(interaction, `❌ Minimalna stawka to ${fmt(min)}.`);
    return null;
  }
  if (bet > MAX_BET) {
    await fail(interaction, `❌ Maksymalna stawka to ${fmt(MAX_BET)}.`);
    return null;
  }
  if (bet > user.wallet) {
    await fail(interaction, `❌ Masz tylko ${fmt(user.wallet)} w portfelu. (Pieniądze z banku musisz najpierw wypłacić: \`/wyplac\`.)`);
    return null;
  }
  if (!db.takeWallet(id, bet)) {
    await fail(interaction, '❌ Nie udało się pobrać stawki, spróbuj ponownie.');
    return null;
  }
  active.add(id);
  pending.set(id, bet);
  return bet;
}

/** Dokłada do trwającej stawki (np. double w blackjacku). Zwraca false, jeśli brakuje środków. */
function increaseBet(userId, extra) {
  if (!pending.has(userId) || !db.takeWallet(userId, extra)) return false;
  pending.set(userId, pending.get(userId) + extra);
  return true;
}

/** Zwraca nierozliczoną stawkę do portfela i zwalnia blokadę. Bezpieczne, gdy nic nie czeka. */
function refund(userId) {
  const bet = pending.get(userId) ?? 0;
  if (bet) db.addWallet(userId, bet);
  pending.delete(userId);
  active.delete(userId);
  return bet;
}

/** Wykonuje fn; jeśli rzuci błąd, zanim gra zostanie rozliczona, oddaje stawkę. */
async function guarded(userId, fn) {
  try {
    return await fn();
  } catch (err) {
    refund(userId);
    throw err;
  }
}

/**
 * Rozlicza grę: `payout` to całość, która wraca do portfela (0 = przegrana, bet = remis).
 * `bet` to łączna stawka (z ewentualnym doublem). Uwzględnia koniczynę, statystyki i XP, zwalnia blokadę.
 */
function settle(userId, bet, payout, xp = 3) {
  pending.delete(userId);
  active.delete(userId);
  let final = Math.max(0, Math.floor(payout));
  let bonus = 0;
  let refunded = null;
  if (final === 0 && bet > 0) {
    // Polisa: całkowita przegrana oddaje stawkę (do sufitu polisy).
    refunded = protect.consume(userId, 'polisa', bet);
    if (refunded) final = bet;
  } else if (final > bet && db.useBuff(userId, 'koniczyna')) {
    // Bonus ma sufit: bez niego rósłby razem ze stawką i koniczyna za 1 500 monet dawałaby setki tysięcy.
    bonus = Math.min(Math.floor((final - bet) * BONUSES.cloverPct), BONUSES.cloverMax);
    final += bonus;
  }
  if (final > 0) db.addWallet(userId, final);
  const net = final - bet;
  db.recordGame(userId, net);
  const lv = db.addXp(userId, xp);
  for (const fn of settleListeners) {
    try {
      fn({ userId, bet, payout: final, net, bonus });
    } catch {
      /* nasłuchiwacz to dodatek, nie może zepsuć rozliczenia */
    }
  }
  return { payout: final, net, bonus, lv, refunded };
}

function resultEmbed({ title, description = '', bet, res, userId }) {
  const { net } = res;
  const color = net > 0 ? COLORS.win : net < 0 ? COLORS.lose : COLORS.push;
  const verdict =
    net > 0 ? `🎉 Wygrywasz **${fmt(net)}**!` : net < 0 ? `💸 Tracisz **${fmt(-net)}**.` : '🤝 Remis – stawka wraca do Ciebie.';
  const bonus =
    (res.bonus ? `\n🍀 Bonus z koniczyny: +${fmt(res.bonus)}` : '') +
    (res.refunded ? `\n${res.refunded.emoji} **${res.refunded.name}** zwróciła Ci stawkę!` : '');
  return embed({
    title,
    color,
    description: `${description}${description ? '\n\n' : ''}${verdict}${bonus}${res.lv.note}`,
    footer: `Stawka: ${fmt(bet)} • Portfel: ${fmt(db.getUser(userId).wallet)}`,
  });
}

/** Sprawdza cooldown; jeśli trwa, odpowiada i zwraca false. W przeciwnym razie ustawia nowy i zwraca true. */
async function guardCooldown(interaction, key, ms) {
  const left = db.cooldownLeft(interaction.user.id, key);
  if (left > 0) {
    await interaction.reply({
      content: `⏰ Odpocznij chwilę! Możesz to powtórzyć za **${duration(left)}**.`,
      flags: EPHEMERAL,
    });
    return false;
  }
  db.setCooldown(interaction.user.id, key, db.scaledCooldown(interaction.user.id, ms));
  return true;
}

/** Filtr kolektora: tylko autor komendy może klikać przyciski. */
function ownerFilter(interaction) {
  return async (i) => {
    if (i.user.id === interaction.user.id) return true;
    await i.reply({ content: '✋ To nie Twoja gra!', flags: EPHEMERAL }).catch(() => {});
    return false;
  };
}

module.exports = {
  MIN_BET,
  MAX_BET,
  EPHEMERAL,
  FAST,
  delay,
  active,
  pending,
  fail,
  takeBet,
  increaseBet,
  refund,
  guarded,
  settle,
  onSettle,
  resultEmbed,
  guardCooldown,
  ownerFilter,
};
