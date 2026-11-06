'use strict';
// Prosty renderer modeli blokowych (tylko elementy z `from`/`to`, obrotem wokół jednej osi i ścianami z UV) na potrzeby podglądów:
// widok ortogonalny, odrzucanie ścian tyłem, malarz od najdalszych, cieniowanie jak w grze (góra jasna, dół ciemny, boki pośrednie).
const { Img } = require('./img');

const SHADE = { up: 1.0, down: 0.5, north: 0.8, south: 0.8, east: 0.6, west: 0.6 };

/** Wierzchołki ściany w kolejności: lewy górny, prawy górny, prawy dolny, lewy dolny (tak jak czyta je UV). */
function corners(dir, f, t) {
  const [x0, y0, z0] = f;
  const [x1, y1, z1] = t;
  switch (dir) {
    case 'south': return [[x0, y1, z1], [x1, y1, z1], [x1, y0, z1], [x0, y0, z1]];
    case 'north': return [[x1, y1, z0], [x0, y1, z0], [x0, y0, z0], [x1, y0, z0]];
    case 'east': return [[x1, y1, z1], [x1, y1, z0], [x1, y0, z0], [x1, y0, z1]];
    case 'west': return [[x0, y1, z0], [x0, y1, z1], [x0, y0, z1], [x0, y0, z0]];
    case 'up': return [[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]];
    default: return [[x0, y0, z1], [x1, y0, z1], [x1, y0, z0], [x0, y0, z0]];
  }
}

/** Obrót punktu wokół osi (prawoskrętnie dla dodatniego kąta) względem origin. */
function rotate(p, rot) {
  if (!rot || !rot.angle) return p;
  const a = (rot.angle * Math.PI) / 180;
  const c = Math.cos(a);
  const s = Math.sin(a);
  const [ox, oy, oz] = rot.origin;
  const x = p[0] - ox;
  const y = p[1] - oy;
  const z = p[2] - oz;
  if (rot.axis === 'y') return [ox + x * c + z * s, oy + y, oz - x * s + z * c];
  if (rot.axis === 'x') return [ox + x, oy + y * c - z * s, oz + y * s + z * c];
  return [ox + x * c - y * s, oy + x * s + y * c, oz + z];
}

function rotateView(p, yaw, pitch) {
  const cy = Math.cos(yaw);
  const sy = Math.sin(yaw);
  const x1 = p[0] * cy + p[2] * sy;
  const z1 = -p[0] * sy + p[2] * cy;
  const cp = Math.cos(pitch);
  const sp = Math.sin(pitch);
  return [x1, p[1] * cp - z1 * sp, p[1] * sp + z1 * cp];
}

/**
 * Rysuje model w obrazie size x size. `textures` mapuje nazwę z `model.textures` (bez #) na Img.
 * Kamera patrzy z południa (+z) na północ; yaw/pitch w stopniach obracają model.
 */
function render(model, textures, size, yaw, pitch) {
  const [tw, th] = [16, 16]; // jak w grze: UV są w skali 0-16, bez względu na rozmiar tekstury
  const quads = [];
  const yawR = (yaw * Math.PI) / 180;
  const pitR = (pitch * Math.PI) / 180;
  for (const el of model.elements) {
    for (const [dir, face] of Object.entries(el.faces)) {
      const pts = corners(dir, el.from, el.to).map((p) => rotateView(rotate(p, el.rotation).map((v) => v - 8), yawR, pitR));
      // zewnętrzna normalna ściany (z kierunku, a nie z kolejności wierzchołków), obrócona razem z elementem i widokiem
      const base = { south: [0, 0, 1], north: [0, 0, -1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] }[dir];
      const n = rotateView(rotate(base, el.rotation && { ...el.rotation, origin: [0, 0, 0] }), yawR, pitR);
      if (n[2] <= 0) continue; // kamera jest w +z: ściana zwrócona od niej
      const depth = pts.reduce((a, p) => a + p[2], 0) / 4;
      const len = 1;
      const light = 0.5 + 0.5 * Math.min(1, Math.max(0, (n[1] / len) * 0.8 + (n[2] / len) * 0.55 + (n[0] / len) * 0.2));
      quads.push({ pts, depth, uv: face.uv, tex: textures[model.textures[face.texture.replace('#', '')].split('/').pop()], light });
    }
  }
  quads.sort((a, b) => a.depth - b.depth);
  const out = new Img(size, size);
  const zbuf = new Float32Array(size * size).fill(-Infinity);
  const k = size / 20; // 16 jednostek modelu + zapas na czubki
  const sx = (p) => size / 2 + p[0] * k;
  const sy = (p) => size / 2 - p[1] * k;
  for (const q of quads) {
    const P = q.pts.map((p) => [sx(p), sy(p)]);
    const [u0, v0, u1, v1] = q.uv;
    const T = [[u0, v0], [u1, v0], [u1, v1], [u0, v1]];
    const tri = (a, b, c) => {
      const minX = Math.max(0, Math.floor(Math.min(P[a][0], P[b][0], P[c][0])));
      const maxX = Math.min(size - 1, Math.ceil(Math.max(P[a][0], P[b][0], P[c][0])));
      const minY = Math.max(0, Math.floor(Math.min(P[a][1], P[b][1], P[c][1])));
      const maxY = Math.min(size - 1, Math.ceil(Math.max(P[a][1], P[b][1], P[c][1])));
      const det = (P[b][1] - P[c][1]) * (P[a][0] - P[c][0]) + (P[c][0] - P[b][0]) * (P[a][1] - P[c][1]);
      if (Math.abs(det) < 1e-9) return;
      for (let y = minY; y <= maxY; y++) {
        for (let x = minX; x <= maxX; x++) {
          const px = x + 0.5;
          const py = y + 0.5;
          const l1 = ((P[b][1] - P[c][1]) * (px - P[c][0]) + (P[c][0] - P[b][0]) * (py - P[c][1])) / det;
          const l2 = ((P[c][1] - P[a][1]) * (px - P[c][0]) + (P[a][0] - P[c][0]) * (py - P[c][1])) / det;
          const l3 = 1 - l1 - l2;
          if (l1 < -0.001 || l2 < -0.001 || l3 < -0.001) continue;
          const z = l1 * q.pts[a][2] + l2 * q.pts[b][2] + l3 * q.pts[c][2];
          if (z <= zbuf[y * size + x]) continue;
          zbuf[y * size + x] = z;
          const u = l1 * T[a][0] + l2 * T[b][0] + l3 * T[c][0];
          const v = l1 * T[a][1] + l2 * T[b][1] + l3 * T[c][1];
          const tx = Math.min(q.tex.w - 1, Math.max(0, Math.floor((u / tw) * q.tex.w)));
          const ty = Math.min(q.tex.h - 1, Math.max(0, Math.floor((v / th) * q.tex.h)));
          const col = q.tex.get(tx, ty);
          out.px(x, y, [Math.round(col[0] * q.light), Math.round(col[1] * q.light), Math.round(col[2] * q.light), 255]);
        }
      }
    };
    tri(0, 1, 2);
    tri(0, 2, 3);
  }
  return out;
}

module.exports = { render };
