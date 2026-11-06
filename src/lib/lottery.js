const db = require('./db');
const { fmt, embed, COLORS } = require('./util');

const DAY = 24 * 3600 * 1000;
const TICKET_PRICE = 100;
const MAX_TICKETS_PER_USER = 500;
const PAYOUT_RATE = 0.9; // 10% z każdej puli znika z gry (podatek)

function nextDraw() {
  let next = Number(db.kvGet('lottery_next', 0));
  if (!next) {
    next = Date.now() + DAY;
    db.kvSet('lottery_next', next);
  }
  return next;
}

function poolInfo() {
  const all = db.lotteryAll();
  const tickets = all.reduce((sum, r) => sum + r.tickets, 0);
  return { tickets, players: all.length, pot: Math.floor(tickets * TICKET_PRICE * PAYOUT_RATE) };
}

function lastWinner() {
  const raw = db.kvGet('lottery_last');
  return raw ? JSON.parse(raw) : null;
}

/** Losuje zwycięzcę (szansa proporcjonalna do liczby biletów) i wypłaca całą pulę. */
async function draw(client) {
  db.kvSet('lottery_next', Date.now() + DAY);
  const all = db.lotteryAll();
  if (!all.length) return null;

  const { tickets, pot } = poolInfo();
  let r = Math.random() * tickets;
  let winner = all[all.length - 1];
  for (const entry of all) {
    r -= entry.tickets;
    if (r < 0) {
      winner = entry;
      break;
    }
  }

  db.tx(() => {
    db.addWallet(winner.user_id, pot);
    db.lotteryClear();
    db.kvSet(
      'lottery_last',
      JSON.stringify({ winner: winner.user_id, amount: pot, tickets: winner.tickets, total: tickets, at: Date.now() }),
    );
  });

  const result = { winner: winner.user_id, amount: pot, tickets: winner.tickets, total: tickets };
  await announce(client, result);
  return result;
}

async function announce(client, result) {
  if (!client) return;
  const e = embed({
    title: '🎟️ Losowanie loterii!',
    color: COLORS.gold,
    description: `Zwycięzcą jest <@${result.winner}>! Wygrywa **${fmt(result.amount)}**.\nMiał ${result.tickets} z ${result.total} biletów.\n\nNowa loteria już ruszyła – kup bilet: \`/loteria kup\``,
  });
  const channelId = process.env.LOTTERY_CHANNEL_ID;
  if (channelId) {
    const channel = await client.channels.fetch(channelId).catch(() => null);
    if (channel && channel.isTextBased()) await channel.send({ content: `<@${result.winner}>`, embeds: [e] }).catch(() => {});
  }
  const user = await client.users.fetch(result.winner).catch(() => null);
  if (user) await user.send({ embeds: [e] }).catch(() => {});
}

function startScheduler(client) {
  nextDraw();
  setInterval(() => {
    if (Date.now() >= nextDraw()) draw(client).catch((err) => console.error('Błąd losowania loterii:', err));
  }, 30_000);
}

module.exports = {
  DAY,
  TICKET_PRICE,
  MAX_TICKETS_PER_USER,
  nextDraw,
  poolInfo,
  lastWinner,
  draw,
  startScheduler,
};
