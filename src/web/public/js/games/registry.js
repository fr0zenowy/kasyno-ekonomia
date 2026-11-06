// Lista gier w kasynie. Każda ma własny ekran ładowany dopiero po wejściu (import dynamiczny).
export const GROUPS = [
  ['classic', '🎲 Klasyki'],
  ['cards', '🃏 Karty i strategia'],
  ['mult', '🚀 Na duże mnożniki'],
];

export const GAMES = [
  { id: 'moneta', group: 'classic', name: 'Moneta', emoji: '🪙', color: '#fbbf24', desc: 'Orzeł czy reszka? Rzut w 3D.', tags: ['x1.95', '50%'], load: () => import('./moneta.js') },
  { id: 'sloty', group: 'classic', name: 'Sloty', emoji: '🎰', color: '#f472b6', desc: 'Trzy bębny, trzy siódemki i jackpot x200.', tags: ['do x200'], load: () => import('./sloty.js') },
  { id: 'kosci', group: 'classic', name: 'Kości', emoji: '🎲', color: '#34d399', desc: 'Poniżej, powyżej albo dokładnie 7.', tags: ['x2.3', 'x5.7'], load: () => import('./kosci.js') },
  { id: 'ruletka', group: 'classic', name: 'Ruletka', emoji: '🎡', color: '#fb7185', desc: 'Europejskie koło 0-36, kolory, tuziny i numery.', tags: ['x2', 'x36'], load: () => import('./ruletka.js') },
  { id: 'kolo', group: 'classic', name: 'Koło fortuny', emoji: '🌠', color: '#a78bfa', desc: 'Jeden obrót: od x0 aż do x200.', tags: ['do x200'], load: () => import('./kolo.js') },
  { id: 'zdrapka', group: 'classic', name: 'Zdrapka', emoji: '🎟️', color: '#22d3ee', desc: 'Zdrap pola, trzy takie same to wygrana.', tags: ['do x500'], load: () => import('./zdrapka.js') },
  { id: 'wyscigi', group: 'classic', name: 'Wyścigi konne', emoji: '🏇', color: '#fb923c', desc: 'Obstaw konia: mały faworyt, duża wypłata.', tags: ['x2.7-x11.8'], load: () => import('./wyscigi.js') },
  { id: 'knp', group: 'classic', name: 'Kamień, papier, nożyce', emoji: '✊', color: '#38bdf8', desc: 'Pojedynek z botem o stawkę.', tags: ['x1.9'], load: () => import('./knp.js') },
  { id: 'blackjack', group: 'cards', name: 'Blackjack', emoji: '🃏', color: '#10b981', desc: 'Ugraj 21. Blackjack płaci 3:2.', tags: ['3:2', 'double'], load: () => import('./blackjack.js') },
  { id: 'hilo', group: 'cards', name: 'Wyżej czy niżej', emoji: '🎴', color: '#f59e0b', desc: 'Zgaduj kolejne karty i buduj serię.', tags: ['do x1000'], load: () => import('./hilo.js') },
  { id: 'miny', group: 'cards', name: 'Miny', emoji: '💣', color: '#ef4444', desc: 'Odkrywaj diamenty, omijaj miny.', tags: ['do x1000', 'tarcza'], load: () => import('./miny.js') },
  { id: 'crash', group: 'mult', name: 'Crash', emoji: '🚀', color: '#8b5cf6', desc: 'Mnożnik rośnie na żywo. Wypłać przed crashem.', tags: ['do x10 000'], load: () => import('./crash.js') },
  { id: 'kurczak', group: 'mult', name: 'Chicken Road', emoji: '🐔', color: '#facc15', desc: 'Przeprowadź kurczaka przez ulicę.', tags: ['do x1000', 'tarcza'], load: () => import('./kurczak.js') },
  { id: 'wieza', group: 'mult', name: 'Wieża', emoji: '🗼', color: '#818cf8', desc: 'Wybierz bezpieczne pole na każdym piętrze.', tags: ['do x1000', 'tarcza'], load: () => import('./wieza.js') },
  { id: 'plinko', group: 'mult', name: 'Plinko', emoji: '🎯', color: '#2dd4bf', desc: 'Kulka spada przez kołki do koszyka z mnożnikiem.', tags: ['do x1000'], load: () => import('./plinko.js') },
  { id: 'limbo', group: 'mult', name: 'Limbo', emoji: '🛸', color: '#c084fc', desc: 'Wybierz cel. Jeśli wynik go dobije, wygrywasz.', tags: ['do x1000'], load: () => import('./limbo.js') },
  { id: 'szansa', group: 'mult', name: 'Szansa', emoji: '📊', color: '#60a5fa', desc: 'Sam ustawiasz szansę na wygraną (0,5-95%).', tags: ['do x192'], load: () => import('./szansa.js') },
  { id: 'rewolwer', group: 'mult', name: 'Rewolwer', emoji: '🔫', color: '#f87171', desc: 'Rosyjska ruletka: więcej naboi, większa wypłata.', tags: ['do x5.76'], load: () => import('./rewolwer.js') },
];

export const gameById = (id) => GAMES.find((g) => g.id === id);
