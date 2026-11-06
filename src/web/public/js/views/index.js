// Mapa tras: pierwszy człon hasha (#/shop/...) wskazuje widok. Widoki ładują się leniwie.
const lazy = (loader, pick = 'default') => async (container, params) => (await loader())[pick](container, params);

export const routes = {
  '': lazy(() => import('./home.js')),
  casino: lazy(() => import('./casino.js')),
  game: lazy(() => import('./game.js')),
  lobby: lazy(() => import('./lobby.js')),
  earn: lazy(() => import('./earn.js')),
  empire: lazy(() => import('./empire.js')),
  crates: lazy(() => import('./crates.js')),
  shop: lazy(() => import('./shop.js')),
  inventory: lazy(() => import('./inventory.js')),
  bank: lazy(() => import('./bank.js')),
  ranking: lazy(() => import('./ranking.js')),
  profile: lazy(() => import('./profile.js')),
  help: lazy(() => import('./help.js')),
};
