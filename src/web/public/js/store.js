// Wspólny stan aplikacji: zalogowany gracz, jego saldo i ekwipunek, katalog, trwające wiadomości.
const listeners = new Set();

export const store = {
  user: null, // { id, name }
  me: null, // pełny stan gracza z /api/me
  catalog: null,
  online: 0,
  feed: [],
  live: new Map(), // trwające wiadomości (gry, lobby) po id
  holds: 0, // >0: nie pokazuj nowego salda (trwa animacja wyniku)
  pendingMe: null,
  last: { wallet: null }, // poprzednie saldo, do podświetlenia zmiany

  subscribe(fn) {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },

  emit(what = 'me') {
    for (const fn of listeners) fn(what);
  },

  setMe(me) {
    if (this.holds > 0) {
      this.pendingMe = me;
      return;
    }
    this.last.wallet = this.me?.wallet ?? me.wallet;
    this.me = me;
    this.emit('me');
  },

  /** Wstrzymuje pokazywanie nowego stanu, żeby saldo nie zdradziło wyniku przed końcem animacji. */
  hold() {
    this.holds += 1;
  },

  release() {
    this.holds = Math.max(0, this.holds - 1);
    if (!this.holds && this.pendingMe) {
      const me = this.pendingMe;
      this.pendingMe = null;
      this.setMe(me);
    }
  },

  item(id) {
    return this.catalog?.items[id];
  },

  qty(id) {
    return this.me?.inventory.find((r) => r.item === id)?.qty ?? 0;
  },
};
