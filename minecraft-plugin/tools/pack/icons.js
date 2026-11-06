'use strict';
// Ikony 16x16 rysowane kodem: kształty z prymitywów, potem automatyczna faza (jasna góra-lewo, ciemny dół-prawo) i obrys.
const { Img, mix } = require('./img');
const { drawText5, drawText3, width3 } = require('./font');
const cards = require('./cards');

const OUT = '#1b1014';
// [jasny, bazowy, ciemny]
const PAL = {
  white: ['#ffffff', '#e9edf5', '#aab3c5'],
  gold: ['#fff09a', '#f4bb2a', '#b9780f'],
  red: ['#ff8f86', '#e23d3d', '#99202a'],
  green: ['#a9f28e', '#44b94b', '#207b2f'],
  blue: ['#98ccff', '#3d84e2', '#214fa2'],
  purple: ['#dcb0ff', '#9c52e2', '#602c9c'],
  orange: ['#ffc888', '#f28c26', '#b4540e'],
  gray: ['#e2e7ef', '#9ca6b8', '#5d687b'],
  dark: ['#6a7082', '#3a3f4e', '#21242f'],
  brown: ['#d9a46a', '#9e6c38', '#5f3b1d'],
  cyan: ['#b0f6ff', '#38c6de', '#18809a'],
  pink: ['#ffc4e2', '#ff72b0', '#b93c7a'],
  skin: ['#fbdcb8', '#f0c093', '#c98c62'],
};

function mk() {
  return new Img(16, 16);
}

/** Dodaje jasny brzeg od góry i lewej i ciemny od dołu i prawej (po krawędziach alfa), co daje wrażenie objętości. */
function bevel(g, light = 0.32, dark = 0.28) {
  const src = g.clone();
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const p = src.get(x, y);
      if (p[3] === 0) continue;
      const up = src.get(x, y - 1)[3] === 0;
      const left = src.get(x - 1, y)[3] === 0;
      const down = src.get(x, y + 1)[3] === 0;
      const right = src.get(x + 1, y)[3] === 0;
      if (up || left) g.px(x, y, mix(p, '#ffffff', light));
      else if (down || right) g.px(x, y, mix(p, '#000000', dark));
    }
  }
}

function finish(g, opts = {}) {
  if (opts.bevel !== false) bevel(g);
  g.outline(OUT);
  return g;
}

function ball(g, cx, cy, r, pal) {
  g.disc(cx, cy, r, pal[1]);
}

function text3(g, label, color = '#ffffff', y = 5, dx = 0) {
  const w = width3(label);
  drawText3(g, Math.round((16 - w) / 2) + dx, y, label, color);
}

function bits(g, x, y, rows, color) {
  rows.forEach((row, j) => {
    for (let i = 0; i < row.length; i++) if (row[i] === '#') g.px(x + i, y + j, color);
  });
}

const HEART = ['.#.#.', '#####', '#####', '.###.', '..#..'];
const SPADE = ['..#..', '.###.', '#####', '#####', '..#..'];
const CLUB = ['.###.', '.###.', '#####', '..#..', '.###.'];
const DIAMOND = ['..#..', '.###.', '#####', '.###.', '..#..'];

function chip(g, pal, label) {
  g.disc(7.5, 7.5, 6.5, pal[2]);
  g.disc(7.5, 7.5, 5.5, pal[1]);
  for (const [x, y] of [[2, 2], [12, 2], [2, 12], [12, 12]]) g.rect(x, y, 2, 2, '#ffffff');
  for (const [x, y] of [[7, 1], [7, 13]]) g.rect(x, y, 2, 1, '#ffffff');
  text3(g, label, '#ffffff', 5);
}

function arrow(g, pal, dir) {
  // strzałka w lewo; w prawo to odbicie
  g.poly([[1, 8], [8, 2], [8, 14]], pal[1]);
  g.rect(8, 5, 6, 6, pal[1]);
  if (dir === 'right') {
    const c = g.clone();
    g.data.fill(0);
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) g.px(15 - x, y, c.get(x, y));
  }
}

function person(g, cx, top, pal, scale = 1) {
  g.disc(cx, top + 2.5 * scale, 2.6 * scale, pal[1]);
  g.ellipse(cx, top + 9 * scale, 4.4 * scale, 3.8 * scale, pal[2]);
}

const ICONS = {};
const def = (id, fn, opts) => {
  ICONS[id] = () => {
    const g = mk();
    fn(g);
    return id === 'blank' ? g : finish(g, opts);
  };
};

// ---------- wspólne ----------
def('blank', () => {});
def('back', (g) => arrow(g, PAL.gold, 'left'));
def('next', (g) => arrow(g, PAL.gold, 'right'));
def('prev', (g) => arrow(g, PAL.gold, 'left'));
def('close', (g) => {
  g.rect(1, 1, 14, 14, PAL.red[1]);
  for (let i = 0; i < 2; i++) {
    g.line(4 + i, 4, 11 + i, 11, '#ffffff');
    g.line(11 - i, 4, 4 - i, 11, '#ffffff');
  }
  g.line(4, 4, 11, 11, '#ffffff');
});
def('info', (g) => {
  ball(g, 7.5, 7.5, 6.5, PAL.blue);
  g.rect(7, 3, 2, 2, '#ffffff');
  g.rect(7, 6, 2, 6, '#ffffff');
});
def('check', (g) => {
  ball(g, 7.5, 7.5, 6.5, PAL.green);
  for (const o of [0, 1]) {
    g.line(4, 8 + o, 6, 10 + o, '#ffffff');
    g.line(6, 10 + o, 11, 5 + o, '#ffffff');
  }
});
def('cross', (g) => {
  ball(g, 7.5, 7.5, 6.5, PAL.red);
  for (const o of [0, 1]) {
    g.line(4, 4 + o, 11, 11 + o, '#ffffff');
    g.line(11, 4 + o, 4, 11 + o, '#ffffff');
  }
});
def('lock', (g) => {
  g.rect(3, 7, 10, 7, PAL.gold[1]);
  g.frame(5, 2, 6, 7, PAL.gray[1], 2);
  g.rect(7, 9, 2, 3, PAL.dark[1]);
});

// ---------- pasek stawki ----------
def('bet_min', (g) => chip(g, PAL.red, 'MIN'), { bevel: false });
def('bet_half', (g) => chip(g, PAL.orange, '1/2'), { bevel: false });
def('bet_double', (g) => chip(g, PAL.green, 'X2'), { bevel: false });
def('bet_max', (g) => chip(g, PAL.blue, 'MAX'), { bevel: false });
def('bet_stake', (g) => {
  for (let k = 0; k < 3; k++) {
    const y = 11 - k * 3.5;
    g.ellipse(7.5, y + 1.5, 6, 2.6, PAL.gold[2]);
    g.ellipse(7.5, y, 6, 2.6, PAL.gold[1]);
    g.ellipse(7.5, y - 0.4, 3.4, 1.2, PAL.gold[0]);
  }
}, { bevel: false });
def('play', (g) => {
  ball(g, 7.5, 7.5, 6.5, PAL.green);
  g.poly([[6, 4], [12, 8], [6, 12]], '#ffffff');
});
def('cashout', (g) => {
  g.rect(1, 3, 14, 10, PAL.green[1]);
  g.frame(1, 3, 14, 10, PAL.green[2], 1);
  g.disc(7.5, 7.5, 3, PAL.gold[1]);
  g.px(7, 7, PAL.gold[0]);
  g.rect(3, 5, 1, 1, PAL.green[0]);
  g.rect(12, 9, 1, 1, PAL.green[0]);
});
def('players', (g) => {
  person(g, 10.5, 2, PAL.gray, 0.9);
  person(g, 5.5, 3, PAL.blue, 1);
});

// ---------- sale ----------
def('kasyno', (g) => {
  g.disc(7.5, 7.5, 6.5, PAL.red[2]);
  g.disc(7.5, 7.5, 5.8, PAL.red[1]);
  for (const [x, y] of [[2, 3], [12, 3], [2, 11], [12, 11], [7, 1], [7, 13], [1, 7], [13, 7]]) g.rect(x, y, 2, 2, '#ffffff');
  g.disc(7.5, 7.5, 3.6, PAL.gold[1]);
  g.disc(7.5, 7.5, 2.6, PAL.gold[0]);
  bits(g, 5, 5, SPADE, PAL.red[2]);
}, { bevel: false });
def('hall_automaty', (g) => {
  g.rect(2, 2, 11, 13, PAL.red[1]);
  g.rect(2, 2, 11, 2, PAL.gold[1]);
  g.rect(3, 5, 9, 5, '#f8f8f8');
  g.rect(3, 5, 9, 1, PAL.gray[2]);
  g.rect(4, 6, 2, 3, PAL.red[1]);
  g.rect(7, 6, 2, 3, PAL.gold[1]);
  g.rect(10, 6, 1, 3, PAL.green[1]);
  g.rect(5, 12, 5, 2, PAL.dark[2]);
  g.rect(13, 4, 1, 6, PAL.gray[1]);
  g.disc(13.5, 3.5, 1.6, PAL.red[1]);
});
def('hall_karty', (g) => {
  g.rect(2, 4, 8, 10, PAL.white[1]);
  g.frame(2, 4, 8, 10, PAL.gray[2], 1);
  bits(g, 3, 7, HEART, PAL.red[1]);
  g.rect(6, 2, 8, 10, PAL.white[0]);
  g.frame(6, 2, 8, 10, PAL.gray[2], 1);
  bits(g, 8, 5, SPADE, PAL.dark[2]);
}, { bevel: false });
def('hall_ryzyko', (g) => {
  g.disc(6.5, 9.5, 5.5, PAL.dark[1]);
  g.rect(4, 6, 2, 2, PAL.dark[0]);
  g.rect(8, 2, 3, 2, PAL.gray[1]);
  g.line(9, 2, 11, 1, PAL.brown[1]);
  g.line(11, 1, 12, 0, PAL.brown[1]);
  g.poly([[13, 0], [14, 2], [12, 2]], PAL.gold[0]);
  g.px(13, 1, PAL.orange[1]);
});
def('hall_live', (g) => {
  // transmisja na żywo: czerwona kropka i fale po obu stronach
  g.disc(7.5, 8, 3, PAL.red[1]);
  g.px(6, 7, PAL.red[0]);
  for (const [r, col] of [[5.6, PAL.gold[1]], [7.2, PAL.gold[2]]]) {
    for (let t = 0; t < 360; t += 4) {
      const ang = (t * Math.PI) / 180;
      const side = Math.abs(Math.cos(ang));
      if (side < 0.55) continue; // tylko łuki po lewej i prawej
      g.px(7.5 + Math.cos(ang) * r, 8 + Math.sin(ang) * r, col);
    }
  }
}, { bevel: false });
def('hall_pokoj', (g) => {
  person(g, 10.5, 2, PAL.gold, 0.9);
  person(g, 5.5, 3, PAL.red, 1);
});

// ---------- menu główne ----------
def('m_zarobki', (g) => {
  g.line(3, 13, 11, 5, PAL.brown[1]);
  g.line(4, 14, 12, 6, PAL.brown[2]);
  g.line(3, 5, 5, 2, PAL.gray[1]);
  g.line(5, 2, 9, 1, PAL.gray[1]);
  g.line(9, 1, 13, 3, PAL.gray[1]);
  g.line(13, 3, 14, 7, PAL.gray[1]);
  g.line(4, 5, 6, 3, PAL.gray[0]);
  g.line(6, 3, 9, 2, PAL.gray[0]);
  g.line(9, 2, 12, 4, PAL.gray[0]);
  g.line(12, 4, 13, 7, PAL.gray[2]);
});
def('m_sklep', (g) => {
  g.poly([[3, 5], [12, 5], [13, 14], [2, 14]], PAL.green[1]);
  g.frame(5, 1, 6, 5, PAL.brown[1], 1);
  drawText5(g, 5, 7, '$', { color: '#ffffff' });
});
def('m_bank', (g) => {
  g.poly([[1, 6], [8, 1], [14, 6]], PAL.gray[1]);
  for (const x of [2, 5, 8, 11]) g.rect(x, 7, 2, 5, PAL.white[1]);
  g.rect(1, 12, 14, 3, PAL.gray[1]);
  g.rect(1, 6, 14, 1, PAL.gold[1]);
});
def('m_ekwipunek', (g) => {
  g.rect(3, 4, 10, 10, PAL.brown[1]);
  g.rect(3, 4, 10, 4, PAL.brown[0]);
  g.rect(5, 9, 6, 4, PAL.brown[2]);
  g.frame(5, 1, 6, 4, PAL.brown[2], 1);
  g.rect(7, 7, 2, 2, PAL.gold[1]);
});
def('m_biznes', (g) => {
  g.rect(3, 2, 7, 13, PAL.blue[1]);
  g.rect(10, 7, 4, 8, PAL.blue[2]);
  for (const y of [4, 7, 10]) for (const x of [4, 7]) g.rect(x, y, 2, 2, PAL.gold[0]);
  g.rect(11, 9, 2, 2, PAL.gold[0]);
  g.rect(11, 12, 2, 3, PAL.dark[2]);
});
def('m_skrzynie', (g) => {
  g.rect(2, 7, 12, 8, PAL.brown[1]);
  g.rect(2, 3, 12, 5, PAL.brown[0]);
  g.rect(2, 7, 12, 1, PAL.dark[1]);
  g.rect(2, 3, 1, 12, PAL.gold[1]);
  g.rect(13, 3, 1, 12, PAL.gold[1]);
  g.rect(7, 6, 2, 4, PAL.gold[1]);
  g.px(7, 8, PAL.dark[2]);
});
def('m_ranking', (g) => {
  g.poly([[3, 2], [12, 2], [11, 8], [8, 10], [7, 10], [4, 8]], PAL.gold[1]);
  g.frame(1, 3, 3, 4, PAL.gold[2], 1);
  g.frame(12, 3, 3, 4, PAL.gold[2], 1);
  g.rect(7, 10, 2, 2, PAL.gold[2]);
  g.rect(4, 12, 8, 2, PAL.gold[2]);
  g.rect(5, 3, 1, 4, PAL.gold[0]);
});
def('m_pomoc', (g) => {
  ball(g, 7.5, 7.5, 6.5, PAL.blue);
  drawText5(g, 5, 4, '?', { color: '#ffffff' });
});

// ---------- gry ----------
def('moneta', (g) => {
  g.disc(7.5, 7.5, 6.5, PAL.gold[2]);
  g.disc(7.5, 7.5, 5.5, PAL.gold[1]);
  g.disc(7.5, 7.5, 4.2, PAL.gold[0]);
  drawText5(g, 5, 4, '$', { color: PAL.gold[2] });
}, { bevel: false });
def('sloty', (g) => {
  g.rect(1, 3, 14, 10, PAL.gold[1]);
  g.rect(2, 4, 12, 8, '#fafafa');
  g.rect(2, 4, 12, 1, PAL.gray[2]);
  drawText3(g, 3, 5, '7', PAL.red[1]);
  drawText3(g, 7, 5, '7', PAL.red[1]);
  drawText3(g, 11, 5, '7', PAL.red[1]);
  g.px(1, 2, PAL.gold[0]);
  g.px(14, 2, PAL.gold[0]);
}, { bevel: false });
def('zdrapka', (g) => {
  g.rect(1, 3, 14, 10, PAL.gold[1]);
  for (const y of [5, 8, 11]) {
    g.px(1, y, [0, 0, 0, 0]);
    g.px(14, y, [0, 0, 0, 0]);
  }
  g.rect(3, 5, 10, 6, PAL.gray[1]);
  g.rect(3, 5, 10, 1, PAL.gray[2]);
  g.disc(8, 8, 2.2, PAL.gold[0]);
  g.px(7, 7, '#ffffff');
});
def('szansa', (g) => {
  for (const [x, y] of [[5, 5], [10, 5], [5, 10], [10, 10]]) g.disc(x, y, 3.2, PAL.green[1]);
  g.disc(7.5, 7.5, 1.6, PAL.green[2]);
  g.line(8, 9, 12, 14, PAL.green[2]);
  g.px(4, 3, PAL.green[0]);
  g.px(9, 3, PAL.green[0]);
});
def('blackjack', (g) => {
  g.rect(2, 1, 12, 14, PAL.white[0]);
  g.frame(2, 1, 12, 14, PAL.gray[2], 1);
  drawText3(g, 4, 6, '21', PAL.red[2]);
  bits(g, 3, 2, ['.#.', '###', '#.#'].map((r) => r), PAL.dark[2]);
  g.px(11, 12, PAL.red[1]);
  g.px(12, 12, PAL.red[1]);
  g.px(10, 12, PAL.red[1]);
}, { bevel: false });
def('hilo', (g) => {
  g.poly([[4, 1], [1, 7], [7, 7]], PAL.green[1]);
  g.rect(3, 7, 2, 7, PAL.green[1]);
  g.poly([[11, 14], [8, 8], [14, 8]], PAL.red[1]);
  g.rect(10, 2, 2, 6, PAL.red[1]);
});
def('kosci', (g) => {
  g.rect(1, 1, 14, 14, PAL.white[0]);
  g.frame(1, 1, 14, 14, PAL.gray[2], 1);
  for (const [x, y] of [[4, 4], [10, 4], [7, 7], [4, 10], [10, 10]]) g.rect(x, y, 2, 2, PAL.dark[2]);
}, { bevel: false });
def('knp', (g) => {
  for (const o of [0, 1]) {
    g.line(3 + o, 2, 11 + o, 10, PAL.gray[1]);
    g.line(12 - o, 2, 4 - o, 10, PAL.gray[0]);
  }
  g.disc(4, 12, 2.2, PAL.red[1]);
  g.disc(11, 12, 2.2, PAL.red[1]);
  g.px(4, 12, [0, 0, 0, 0]);
  g.px(11, 12, [0, 0, 0, 0]);
});
def('miny', (g) => {
  for (let a = 0; a < 8; a++) {
    const ang = (a * Math.PI) / 4;
    g.line(7.5 + Math.cos(ang) * 3, 7.5 + Math.sin(ang) * 3, 7.5 + Math.cos(ang) * 6.5, 7.5 + Math.sin(ang) * 6.5, PAL.gray[2]);
  }
  g.disc(7.5, 7.5, 4.2, PAL.dark[1]);
  g.disc(7.5, 7.5, 1.6, PAL.red[1]);
  g.px(5, 5, PAL.dark[0]);
});

function ascii(g, rows, legend) {
  rows.forEach((row, y) => {
    for (let x = 0; x < row.length; x++) {
      const c = row[x];
      if (c !== '.' && c !== ' ') g.px(x, y, legend[c] || '#ff00ff');
    }
  });
}

def('kurczak', (g) => {
  ascii(g, [
    '................',
    '.....rr.........',
    '....rrrr........',
    '....wwww........',
    '...wWWWWw.......',
    '...wWKWWyyy.....',
    '...wWWWWyOy.....',
    '...wWWWWwrr.....',
    '....wWWWw.......',
    '..wwwWWWWww.....',
    '.wWWWWWWWWWw....',
    '.wWWWWWWWWWw....',
    '.wwWWWWWWWww....',
    '..wwwWWWwww.....',
    '.....Oo.Oo......',
    '................',
  ], { r: PAL.red[1], w: PAL.white[2], W: PAL.white[0], K: '#1b1014', y: PAL.orange[1], O: PAL.orange[2], o: PAL.orange[2] });
}, { bevel: false });
def('wieza', (g) => {
  for (const x of [3, 7, 11]) g.rect(x, 2, 2, 3, PAL.gray[1]);
  g.rect(3, 5, 10, 10, PAL.gray[1]);
  g.hline(3, 8, 10, PAL.gray[2]);
  g.hline(3, 11, 10, PAL.gray[2]);
  for (const [x, y] of [[6, 5], [9, 5], [4, 8], [8, 8], [11, 8], [6, 11], [10, 11]]) g.vline(x, y, 3, PAL.gray[2]);
  g.rect(7, 11, 3, 4, PAL.dark[2]);
});
def('rewolwer', (g) => {
  g.rect(7, 4, 8, 3, PAL.gray[1]);
  g.rect(7, 4, 8, 1, PAL.gray[0]);
  g.rect(2, 4, 6, 5, PAL.gray[2]);
  g.disc(6, 6, 2.6, PAL.gray[1]);
  g.disc(6, 6, 1, PAL.dark[2]);
  g.poly([[2, 9], [6, 9], [5, 15], [1, 14]], PAL.brown[1]);
  g.frame(6, 8, 4, 4, PAL.gray[2], 1);
  g.px(14, 3, PAL.gray[2]);
});
def('limbo', (g) => {
  g.disc(7.5, 7.5, 6.5, PAL.red[1]);
  g.disc(7.5, 7.5, 5, PAL.white[1]);
  g.disc(7.5, 7.5, 3.4, PAL.red[1]);
  g.disc(7.5, 7.5, 1.8, PAL.white[1]);
}, { bevel: false });
def('plinko', (g) => {
  g.disc(7.5, 2.8, 2.3, PAL.red[1]);
  for (const [x, y] of [[7, 6], [4, 9], [10, 9], [1, 12], [7, 12], [13, 12]]) g.rect(x, y, 2, 2, PAL.gold[1]);
  g.px(7, 2, PAL.red[0]);
}, { bevel: false });
def('crash', (g) => {
  g.poly([[8, 0], [11, 4], [11, 11], [5, 11], [5, 4]], PAL.white[1]);
  g.poly([[8, 0], [10, 3], [6, 3]], PAL.red[1]);
  g.disc(8, 6.5, 1.8, PAL.blue[1]);
  g.poly([[5, 8], [1, 13], [5, 11]], PAL.red[1]);
  g.poly([[11, 8], [15, 13], [11, 11]], PAL.red[1]);
  g.poly([[6, 12], [8, 15], [10, 12]], PAL.orange[1]);
  g.px(8, 13, PAL.gold[0]);
});
def('ruletka', (g) => {
  g.disc(7.5, 7.5, 6.5, PAL.gold[2]);
  g.disc(7.5, 7.5, 5.7, PAL.dark[2]);
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const d = Math.hypot(x - 7.5, y - 7.5);
      if (d > 5.4 || d < 2.8) continue;
      const a = (Math.atan2(y - 7.5, x - 7.5) + Math.PI) / (2 * Math.PI);
      const k = Math.floor(a * 12);
      g.px(x, y, k === 0 ? PAL.green[1] : k % 2 === 0 ? PAL.red[1] : '#2a2e3b');
    }
  }
  g.disc(7.5, 7.5, 2.4, PAL.gold[1]);
  g.px(7, 7, PAL.gold[0]);
  g.px(11, 3, '#ffffff');
}, { bevel: false });
def('kolo', (g) => {
  const cols = [PAL.red[1], PAL.gold[1], PAL.green[1], PAL.blue[1], PAL.purple[1], PAL.orange[1], PAL.pink[1], PAL.cyan[1]];
  g.disc(7.5, 8.5, 6.5, PAL.dark[2]);
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const d = Math.hypot(x - 7.5, y - 8.5);
      if (d > 5.8) continue;
      const a = (Math.atan2(y - 8.5, x - 7.5) + Math.PI) / (2 * Math.PI);
      g.px(x, y, cols[Math.floor(a * 8) % 8]);
    }
  }
  g.disc(7.5, 8.5, 1.8, PAL.white[1]);
  g.poly([[6, 0], [9, 0], [7.5, 3]], PAL.red[1]);
}, { bevel: false });
def('wyscigi', (g) => {
  g.disc(7.5, 8.5, 6.5, PAL.gray[1]);
  g.disc(7.5, 8.5, 3.4, [0, 0, 0, 0]);
  g.rect(4, 0, 8, 8, [0, 0, 0, 0]);
  for (const [x, y] of [[2, 9], [3, 12], [12, 9], [11, 12], [2, 11], [13, 11]]) g.px(x, y, PAL.dark[2]);
});

// ---------- pokój graczy ----------
def('jackpot', (g) => {
  g.poly([[4, 6], [11, 6], [14, 12], [12, 14], [3, 14], [1, 12]], PAL.brown[1]);
  g.rect(5, 3, 5, 3, PAL.brown[2]);
  g.rect(4, 5, 7, 1, PAL.gold[1]);
  drawText5(g, 5, 7, '$', { color: PAL.gold[0] });
});
def('napad', (g) => {
  g.rect(1, 4, 14, 8, PAL.dark[1]);
  g.rect(3, 6, 4, 3, '#ffffff');
  g.rect(9, 6, 4, 3, '#ffffff');
  g.rect(5, 7, 2, 2, PAL.dark[2]);
  g.rect(9, 7, 2, 2, PAL.dark[2]);
  g.rect(1, 4, 14, 1, PAL.red[1]);
});
def('pojedynek', (g) => {
  for (const o of [0, 1]) {
    g.line(2 + o, 2, 11 + o, 11, PAL.gray[0]);
    g.line(13 - o, 2, 4 - o, 11, PAL.gray[1]);
  }
  g.rect(9, 11, 5, 2, PAL.gold[1]);
  g.rect(2, 11, 5, 2, PAL.gold[1]);
  g.rect(12, 13, 2, 2, PAL.brown[1]);
  g.rect(2, 13, 2, 2, PAL.brown[1]);
});
def('loteria', (g) => {
  g.disc(7.5, 7.5, 6.5, PAL.orange[1]);
  g.disc(7.5, 7.5, 3.8, '#ffffff');
  drawText3(g, 6, 5, '7', PAL.red[2]);
}, { bevel: false });


// ---------- zarobki ----------
function calendar(g, label) {
  g.rect(2, 2, 12, 13, PAL.white[1]);
  g.rect(2, 2, 12, 4, PAL.red[1]);
  g.rect(5, 1, 1, 3, PAL.dark[2]);
  g.rect(10, 1, 1, 3, PAL.dark[2]);
  if (label) drawText3(g, 6, 8, label, PAL.dark[2]);
  else {
    g.disc(7.5, 10, 2.6, PAL.gold[1]);
    g.px(6, 9, PAL.gold[0]);
  }
}
def('e_daily', (g) => calendar(g, null));
def('e_weekly', (g) => calendar(g, '7'));
def('e_hourly', (g) => {
  g.disc(7.5, 7.5, 6.5, PAL.gray[1]);
  g.disc(7.5, 7.5, 5.4, PAL.white[0]);
  g.line(7.5, 7.5, 7.5, 3, PAL.dark[2]);
  g.line(7.5, 7.5, 10.5, 9, PAL.red[1]);
  g.px(7, 7, PAL.dark[2]);
}, { bevel: false });
def('e_work', (g) => {
  g.rect(1, 5, 14, 9, PAL.brown[1]);
  g.frame(5, 2, 6, 4, PAL.brown[2], 1);
  g.rect(1, 9, 14, 1, PAL.brown[2]);
  g.rect(7, 8, 2, 3, PAL.gold[1]);
});
def('e_beg', (g) => {
  for (const [x, y] of [[5, 6], [8, 4], [11, 6]]) g.disc(x, y, 1.7, PAL.gold[1]);
  g.poly([[1, 8], [14, 8], [12, 14], [3, 14]], PAL.brown[1]);
  g.rect(1, 8, 14, 1, PAL.brown[0]);
});
def('e_busk', (g) => {
  g.disc(4, 12, 2.2, PAL.purple[2]);
  g.disc(11, 10, 2.2, PAL.purple[2]);
  g.vline(5, 3, 9, PAL.purple[2]);
  g.vline(12, 1, 9, PAL.purple[2]);
  g.rect(5, 2, 8, 2, PAL.purple[1]);
});
def('e_fish', (g) => {
  g.ellipse(7, 8, 5.5, 3.6, PAL.blue[1]);
  g.poly([[11, 8], [15, 4], [15, 12]], PAL.blue[2]);
  g.px(4, 7, '#ffffff');
  g.rect(6, 10, 3, 1, PAL.blue[0]);
});
def('e_hunt', (g) => {
  for (const [a, b] of [[[4, 1], [2, 4]], [[2, 4], [1, 8]], [[1, 8], [2, 12]], [[2, 12], [4, 15]]]) g.line(a[0], a[1], b[0], b[1], PAL.brown[1]);
  g.line(4, 1, 4, 15, PAL.white[2]);
  g.line(2, 8, 14, 8, PAL.gray[1]);
  g.poly([[15, 8], [12, 6], [12, 10]], PAL.gold[1]);
  g.rect(3, 6, 1, 1, PAL.red[1]);
  g.rect(3, 10, 1, 1, PAL.red[1]);
});
def('e_forest', (g) => {
  g.ellipse(7.5, 7, 6.3, 5, PAL.red[1]);
  g.rect(0, 9, 16, 7, [0, 0, 0, 0]);
  g.rect(6, 8, 4, 6, PAL.white[1]);
  for (const [x, y] of [[4, 4], [8, 3], [11, 5], [6, 6]]) g.px(x, y, '#ffffff');
});
def('e_trash', (g) => {
  g.poly([[3, 4], [12, 4], [11, 15], [4, 15]], PAL.gray[1]);
  g.rect(2, 2, 12, 2, PAL.gray[2]);
  g.rect(6, 1, 4, 1, PAL.gray[2]);
  for (const x of [6, 8, 10]) g.vline(x, 6, 7, PAL.gray[2]);
});
def('e_trivia', (g) => {
  g.rect(2, 1, 12, 14, PAL.blue[1]);
  g.rect(2, 1, 2, 14, PAL.blue[2]);
  drawText5(g, 6, 4, '?', { color: '#ffffff' });
});
def('e_math', (g) => {
  g.rect(3, 1, 10, 14, PAL.dark[1]);
  g.rect(4, 2, 8, 3, PAL.green[0]);
  for (let r = 0; r < 3; r++) for (let c = 0; c < 3; c++) g.rect(4 + c * 3, 7 + r * 3, 2, 2, r === 2 && c === 2 ? PAL.orange[1] : PAL.gray[0]);
});
def('e_rob', (g) => {
  g.disc(4.5, 4.5, 3.4, PAL.gold[1]);
  g.disc(4.5, 4.5, 1.4, [0, 0, 0, 0]);
  g.line(7, 7, 13, 13, PAL.gold[1]);
  g.line(8, 7, 14, 13, PAL.gold[2]);
  g.rect(10, 11, 2, 2, [0, 0, 0, 0]);
  g.px(13, 11, PAL.gold[1]);
  g.px(12, 13, PAL.gold[1]);
});
def('e_job', (g) => {
  g.ellipse(7.5, 9, 6, 5.5, PAL.gold[1]);
  g.rect(0, 10, 16, 6, [0, 0, 0, 0]);
  g.rect(1, 10, 14, 3, PAL.gold[2]);
  g.rect(7, 3, 2, 7, PAL.gold[0]);
});
def('e_last', (g) => {
  g.rect(3, 3, 10, 10, PAL.white[1]);
  g.ellipse(8, 3, 5.5, 1.8, PAL.brown[0]);
  g.ellipse(8, 13, 5.5, 1.8, PAL.brown[0]);
  for (const [y, w] of [[5, 6], [7, 6], [9, 4]]) g.hline(5, y, w, PAL.gray[2]);
});

// ---------- bank ----------
function coinsAt(g, y) {
  g.ellipse(7.5, y + 1, 5.5, 2.4, PAL.gold[2]);
  g.ellipse(7.5, y, 5.5, 2.4, PAL.gold[1]);
  g.ellipse(7.5, y - 0.4, 3, 1, PAL.gold[0]);
}
def('b_deposit', (g) => {
  g.poly([[8, 10], [3, 4], [13, 4]], PAL.green[1]);
  g.rect(6, 0, 4, 4, PAL.green[1]);
  coinsAt(g, 12.5);
}, { bevel: false });
def('b_withdraw', (g) => {
  g.poly([[8, 1], [3, 7], [13, 7]], PAL.red[1]);
  g.rect(6, 7, 4, 4, PAL.red[1]);
  coinsAt(g, 13);
}, { bevel: false });
def('b_transfer', (g) => {
  g.rect(1, 3, 14, 10, PAL.white[1]);
  g.line(1, 3, 8, 9, PAL.gray[1]);
  g.line(15, 3, 8, 9, PAL.gray[1]);
  g.disc(12, 11, 2.2, PAL.gold[1]);
});

// ---------- sklep ----------
def('s_wedki', (g) => {
  g.line(2, 14, 12, 2, PAL.brown[1]);
  g.line(3, 14, 13, 2, PAL.brown[2]);
  g.line(13, 2, 14, 11, PAL.white[2]);
  g.disc(14, 12, 1.5, PAL.red[1]);
});
def('s_ochrona', (g) => {
  g.poly([[2, 2], [14, 2], [14, 8], [8, 15], [2, 8]], PAL.blue[1]);
  g.poly([[4, 4], [12, 4], [12, 7], [8, 12], [4, 7]], PAL.blue[0]);
  g.rect(7, 4, 2, 7, PAL.gold[1]);
  g.rect(5, 6, 6, 2, PAL.gold[1]);
});
def('s_zwierzaki', (g) => {
  g.ellipse(8, 11, 4.6, 3.6, PAL.brown[1]);
  for (const [x, y] of [[3, 7], [6, 4], [10, 4], [13, 7]]) g.disc(x, y, 1.7, PAL.brown[1]);
});
def('s_vip', (g) => {
  g.rect(1, 3, 14, 10, PAL.gold[1]);
  g.rect(1, 3, 14, 1, PAL.gold[0]);
  drawText3(g, 2, 6, 'VIP', PAL.dark[2]);
});
def('s_eliksiry', (g) => {
  g.disc(8, 10, 4.6, PAL.purple[1]);
  g.rect(6, 3, 4, 5, PAL.white[1]);
  g.rect(6, 1, 4, 2, PAL.brown[1]);
  g.px(6, 9, PAL.purple[0]);
  g.px(7, 8, PAL.purple[0]);
});
def('s_luksus', (g) => {
  g.poly([[3, 5], [5, 2], [11, 2], [13, 5], [8, 14]], PAL.cyan[1]);
  g.poly([[3, 5], [13, 5], [8, 14]], PAL.cyan[2]);
  g.line(5, 2, 8, 5, PAL.cyan[0]);
  g.line(11, 2, 8, 5, PAL.cyan[0]);
  g.hline(3, 5, 10, PAL.cyan[0]);
}, { bevel: false });
def('star', (g) => {
  const pts = [];
  for (let i = 0; i < 10; i++) {
    const ang = -Math.PI / 2 + (i * Math.PI) / 5;
    const r = i % 2 === 0 ? 7 : 3;
    pts.push([7.5 + Math.cos(ang) * r, 8 + Math.sin(ang) * r]);
  }
  g.poly(pts, PAL.gold[1]);
});
def('farm', (g) => {
  g.ellipse(7.5, 13, 6, 2.2, PAL.brown[1]);
  g.vline(7, 6, 7, PAL.green[2]);
  g.poly([[7, 8], [1, 5], [3, 2], [7, 5]], PAL.green[1]);
  g.poly([[8, 7], [14, 4], [12, 1], [8, 4]], PAL.green[0]);
});

// ---------- napad na bank ----------
def('h_door', (g) => {
  g.rect(3, 1, 10, 14, PAL.brown[1]);
  g.frame(3, 1, 10, 14, PAL.brown[2], 1);
  g.rect(5, 3, 6, 5, PAL.brown[0]);
  g.rect(5, 9, 6, 4, PAL.brown[0]);
  g.disc(10.5, 8.5, 1, PAL.gold[1]);
});
def('h_camera', (g) => {
  g.rect(1, 3, 10, 6, PAL.gray[1]);
  g.rect(1, 3, 10, 1, PAL.gray[0]);
  g.disc(11.5, 6, 2.6, PAL.dark[1]);
  g.disc(11.5, 6, 1.2, PAL.blue[1]);
  g.px(2, 4, PAL.red[1]);
  g.rect(4, 9, 2, 3, PAL.gray[2]);
  g.rect(2, 12, 6, 2, PAL.gray[2]);
});
def('h_vault', (g) => {
  g.disc(7.5, 7.5, 6.5, PAL.gray[2]);
  g.disc(7.5, 7.5, 5.2, PAL.gray[1]);
  g.disc(7.5, 7.5, 3, PAL.gray[0]);
  g.rect(7, 3, 2, 10, PAL.gold[1]);
  g.rect(3, 7, 10, 2, PAL.gold[1]);
  g.disc(7.5, 7.5, 1.4, PAL.gold[2]);
  for (const [x, y] of [[2, 2], [13, 2], [2, 13], [13, 13]]) g.px(x, y, PAL.gray[0]);
}, { bevel: false });
def('h_loot', (g) => {
  const bar = (x, y, w) => {
    g.poly([[x + 1, y], [x + w - 1, y], [x + w, y + 3], [x, y + 3]], PAL.gold[1]);
    g.hline(x + 1, y, w - 2, PAL.gold[0]);
  };
  bar(4, 3, 8);
  bar(1, 8, 8);
  bar(7, 8, 8);
  bar(4, 12, 8);
});
def('h_car', (g) => {
  g.rect(1, 8, 14, 4, PAL.red[1]);
  g.poly([[3, 8], [5, 4], [11, 4], [13, 8]], PAL.red[2]);
  g.rect(5, 5, 3, 3, PAL.cyan[0]);
  g.rect(9, 5, 3, 3, PAL.cyan[0]);
  g.disc(4, 12, 2.2, PAL.dark[2]);
  g.disc(12, 12, 2.2, PAL.dark[2]);
  g.px(4, 12, PAL.gray[1]);
  g.px(12, 12, PAL.gray[1]);
  g.px(14, 9, PAL.gold[0]);
}, { bevel: false });
def('h_siren', (g) => {
  g.rect(3, 11, 10, 3, PAL.gray[1]);
  g.ellipse(7.5, 10, 4.2, 4.5, PAL.red[1]);
  g.rect(0, 11, 16, 5, [0, 0, 0, 0]);
  g.rect(3, 11, 10, 3, PAL.gray[1]);
  g.px(6, 7, PAL.red[0]);
  g.px(7, 6, PAL.red[0]);
  g.line(1, 5, 3, 7, PAL.gold[1]);
  g.line(14, 5, 12, 7, PAL.gold[1]);
  g.line(7, 1, 7, 3, PAL.gold[1]);
}, { bevel: false });
def('h_cuffs', (g) => {
  g.disc(4.5, 9, 3.6, PAL.gray[1]);
  g.disc(4.5, 9, 1.8, [0, 0, 0, 0]);
  g.disc(11.5, 9, 3.6, PAL.gray[1]);
  g.disc(11.5, 9, 1.8, [0, 0, 0, 0]);
  g.rect(7, 8, 2, 2, PAL.gray[2]);
  g.rect(3, 3, 3, 2, PAL.gray[2]);
  g.rect(10, 3, 3, 2, PAL.gray[2]);
}, { bevel: false });

def('h_guard', (g) => {
  g.ellipse(7.5, 6, 6, 4.5, PAL.blue[2]);
  g.rect(0, 8, 16, 8, [0, 0, 0, 0]);
  g.rect(2, 8, 12, 2, PAL.dark[1]);
  g.rect(4, 10, 8, 5, PAL.skin[1]);
  g.px(6, 12, PAL.dark[2]);
  g.px(9, 12, PAL.dark[2]);
  g.rect(6, 14, 4, 1, PAL.skin[2]);
  g.rect(7, 4, 2, 3, PAL.gold[1]);
  g.px(7, 3, PAL.gold[0]);
}, { bevel: false });

// ---------- talia kart (blackjack i hi-lo) ----------
for (const id of cards.names()) ICONS[id] = () => cards.render(id);

/** Wszystkie identyfikatory ikon (bez ścieżek). */
function names() {
  return Object.keys(ICONS);
}

function render(id) {
  return ICONS[id]();
}

module.exports = { names, render, OUT, PAL };
