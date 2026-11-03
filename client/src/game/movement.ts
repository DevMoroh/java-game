/**
 * Правила руху — точна копія GameWorld.applyInput/resolveCollisions на сервері.
 * Якщо змінюєш тут, зміни й там, інакше передбачення постійно розходитиметься з сервером.
 */

export interface MoveRules {
  tickRate: number
  speed: number
  playerRadius: number
  boundary: number
}

export interface Vec {
  x: number
  z: number
}

export interface MoveResult {
  x: number
  z: number
  rotation: number
  /** Чи реально зрушив (а не впирається в когось) — для анімації. */
  moving: boolean
}

const RESOLVE_ITERATIONS = 3

/** Один крок руху тривалістю 1/tickRate у напрямку (dx, dz), |v| ≤ 1. */
export function applyMove(self: Vec, dx: number, dz: number, others: readonly Vec[], rules: MoveRules): MoveResult | null {
  let len = Math.hypot(dx, dz)
  if (len === 0) return null
  if (len > 1) {
    dx /= len
    dz /= len
    len = 1
  }
  const step = rules.speed / rules.tickRate
  const clamp = (v: number) => Math.max(-rules.boundary, Math.min(rules.boundary, v))
  const next = resolveCollisions(self, clamp(self.x + dx * step), clamp(self.z + dz * step), others, rules, clamp)
  const moved = Math.hypot(next.x - self.x, next.z - self.z)
  return { x: next.x, z: next.z, rotation: Math.atan2(dx, dz), moving: moved > step * 0.2 }
}

function resolveCollisions(
  self: Vec,
  x: number,
  z: number,
  others: readonly Vec[],
  rules: MoveRules,
  clamp: (v: number) => number,
): Vec {
  const minDist = rules.playerRadius * 2
  for (let i = 0; i < RESOLVE_ITERATIONS; i++) {
    let hit = false
    for (const q of others) {
      let dx = x - q.x
      let dz = z - q.z
      let d = Math.hypot(dx, dz)
      if (d >= minDist) continue
      if (d < 1e-9) {
        dx = self.x - q.x
        dz = self.z - q.z
        d = Math.hypot(dx, dz)
        if (d < 1e-9) {
          dx = 1
          dz = 0
          d = 1
        }
      }
      x = clamp(q.x + (dx / d) * minDist)
      z = clamp(q.z + (dz / d) * minDist)
      hit = true
    }
    if (!hit) return { x, z }
  }
  const overlaps = others.some((q) => Math.hypot(x - q.x, z - q.z) < minDist - 1e-6)
  return overlaps ? { x: self.x, z: self.z } : { x, z }
}
