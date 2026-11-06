'use strict';
// Obraz RGBA z prymitywami do rysowania pixel-artu: bez antyaliasingu, bo ikony i tła mają mieć ostre piksele.

function hex(c) {
  if (Array.isArray(c)) return c.length === 3 ? [...c, 255] : c;
  if (c === null || c === undefined) return [0, 0, 0, 0];
  let s = c.replace('#', '');
  if (s.length === 3) s = s.split('').map((x) => x + x).join('');
  const n = parseInt(s.slice(0, 6), 16);
  const a = s.length === 8 ? parseInt(s.slice(6, 8), 16) : 255;
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255, a];
}

function mix(a, b, t) {
  const A = hex(a);
  const B = hex(b);
  return [0, 1, 2, 3].map((i) => Math.round(A[i] + (B[i] - A[i]) * t));
}

class Img {
  constructor(w, h) {
    this.w = w;
    this.h = h;
    this.data = new Uint8Array(w * h * 4);
  }

  inside(x, y) {
    return x >= 0 && y >= 0 && x < this.w && y < this.h;
  }

  get(x, y) {
    if (!this.inside(x, y)) return [0, 0, 0, 0];
    const i = (y * this.w + x) * 4;
    return [this.data[i], this.data[i + 1], this.data[i + 2], this.data[i + 3]];
  }

  /** Nadpisuje piksel (bez mieszania). */
  px(x, y, c) {
    x = Math.round(x);
    y = Math.round(y);
    if (!this.inside(x, y)) return this;
    const [r, g, b, a] = hex(c);
    const i = (y * this.w + x) * 4;
    this.data[i] = r;
    this.data[i + 1] = g;
    this.data[i + 2] = b;
    this.data[i + 3] = a;
    return this;
  }

  /** Nakłada piksel z uwzględnieniem alfy (źródło nad celem). */
  over(x, y, c) {
    x = Math.round(x);
    y = Math.round(y);
    if (!this.inside(x, y)) return this;
    const [r, g, b, a] = hex(c);
    if (a === 255) return this.px(x, y, [r, g, b, 255]);
    if (a === 0) return this;
    const [dr, dg, db, da] = this.get(x, y);
    const sa = a / 255;
    const oa = sa + (da / 255) * (1 - sa);
    const f = (s, d) => Math.round((s * sa + d * (da / 255) * (1 - sa)) / oa);
    return this.px(x, y, [f(r, dr), f(g, dg), f(b, db), Math.round(oa * 255)]);
  }

  rect(x, y, w, h, c) {
    for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) this.px(x + i, y + j, c);
    return this;
  }

  /** Obrys prostokąta o grubości t. */
  frame(x, y, w, h, c, t = 1) {
    this.rect(x, y, w, t, c);
    this.rect(x, y + h - t, w, t, c);
    this.rect(x, y, t, h, c);
    this.rect(x + w - t, y, t, h, c);
    return this;
  }

  hline(x, y, w, c) {
    return this.rect(x, y, w, 1, c);
  }

  vline(x, y, h, c) {
    return this.rect(x, y, 1, h, c);
  }

  line(x0, y0, x1, y1, c) {
    x0 = Math.round(x0);
    y0 = Math.round(y0);
    x1 = Math.round(x1);
    y1 = Math.round(y1);
    const dx = Math.abs(x1 - x0);
    const dy = -Math.abs(y1 - y0);
    const sx = x0 < x1 ? 1 : -1;
    const sy = y0 < y1 ? 1 : -1;
    let err = dx + dy;
    for (;;) {
      this.px(x0, y0, c);
      if (x0 === x1 && y0 === y1) break;
      const e2 = 2 * err;
      if (e2 >= dy) {
        err += dy;
        x0 += sx;
      }
      if (e2 <= dx) {
        err += dx;
        y0 += sy;
      }
    }
    return this;
  }

  /** Wypełnione koło (środek w punkcie (cx, cy), współrzędne mogą być połówkowe). */
  disc(cx, cy, r, c) {
    for (let y = Math.floor(cy - r - 1); y <= Math.ceil(cy + r + 1); y++) {
      for (let x = Math.floor(cx - r - 1); x <= Math.ceil(cx + r + 1); x++) {
        const dx = x + 0.5 - cx - 0.5;
        const dy = y + 0.5 - cy - 0.5;
        if (dx * dx + dy * dy <= r * r + 0.25) this.px(x, y, c);
      }
    }
    return this;
  }

  ellipse(cx, cy, rx, ry, c) {
    for (let y = Math.floor(cy - ry - 1); y <= Math.ceil(cy + ry + 1); y++) {
      for (let x = Math.floor(cx - rx - 1); x <= Math.ceil(cx + rx + 1); x++) {
        const dx = (x - cx) / rx;
        const dy = (y - cy) / ry;
        if (dx * dx + dy * dy <= 1.02) this.px(x, y, c);
      }
    }
    return this;
  }

  /** Wypełniony wielokąt (reguła parzystości), punkty [[x,y],...]. */
  poly(points, c) {
    let minY = Infinity;
    let maxY = -Infinity;
    for (const [, y] of points) {
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
    }
    for (let y = Math.floor(minY); y <= Math.ceil(maxY); y++) {
      const yy = y + 0.5;
      const xs = [];
      for (let i = 0; i < points.length; i++) {
        const [x1, y1] = points[i];
        const [x2, y2] = points[(i + 1) % points.length];
        if ((y1 <= yy && y2 > yy) || (y2 <= yy && y1 > yy)) xs.push(x1 + ((yy - y1) / (y2 - y1)) * (x2 - x1));
      }
      xs.sort((a, b) => a - b);
      for (let i = 0; i + 1 < xs.length; i += 2) {
        for (let x = Math.round(xs[i]); x < Math.round(xs[i + 1]); x++) this.px(x, y, c);
      }
    }
    return this;
  }

  /** Wkleja inny obraz w miejscu (x, y) z mieszaniem alfy. */
  blit(src, x, y) {
    for (let j = 0; j < src.h; j++) for (let i = 0; i < src.w; i++) {
      const p = src.get(i, j);
      if (p[3] > 0) this.over(x + i, y + j, p);
    }
    return this;
  }

  /** Dokłada jednopikselowy obrys wokół nieprzezroczystych pikseli (tylko na przezroczystym tle). */
  outline(c, diagonal = false) {
    const marks = [];
    for (let y = 0; y < this.h; y++) for (let x = 0; x < this.w; x++) {
      if (this.get(x, y)[3] > 0) continue;
      const n = [[1, 0], [-1, 0], [0, 1], [0, -1]];
      if (diagonal) n.push([1, 1], [-1, -1], [1, -1], [-1, 1]);
      if (n.some(([dx, dy]) => this.get(x + dx, y + dy)[3] > 0)) marks.push([x, y]);
    }
    for (const [x, y] of marks) this.px(x, y, c);
    return this;
  }

  /** Przycina do pikseli, które mają alfę > 0 w masce (maska to Img o tym samym rozmiarze). */
  mask(m) {
    for (let y = 0; y < this.h; y++) for (let x = 0; x < this.w; x++) if (m.get(x, y)[3] === 0) this.px(x, y, [0, 0, 0, 0]);
    return this;
  }

  scaled(k) {
    const out = new Img(this.w * k, this.h * k);
    for (let y = 0; y < out.h; y++) for (let x = 0; x < out.w; x++) out.px(x, y, this.get(Math.floor(x / k), Math.floor(y / k)));
    return out;
  }

  clone() {
    const c = new Img(this.w, this.h);
    c.data.set(this.data);
    return c;
  }
}

module.exports = { Img, hex, mix };
