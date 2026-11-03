import { applyMove, type MoveRules, type Vec } from './movement'
import type { PlayerPos } from './types'

interface Input {
  seq: number
  dx: number
  dz: number
}

const ARRIVE_EPS = 0.1
/** Скільки тіків без поступу до цілі кліку, перш ніж здатися (~0.5 с). */
const STUCK_TICKS = 10
/** Максимальне «доганяння» після підвисання вкладки — щоб не слати сервера пачку введень. */
const MAX_CATCH_UP_SECONDS = 0.25
/** Розбіжність з сервером, більша за цю, — телепорт, а не плавна корекція. */
const SNAP_DISTANCE = 3
const CORRECTION_SPEED = 12

/**
 * Передбачення власного руху (client-side prediction + reconciliation).
 *
 * Симуляція йде фіксованими кроками 1/tickRate, як на сервері. Кожен крок — введення з seq:
 * застосовуємо одразу локально й надсилаємо серверу. Коли сервер присилає позицію з lastSeq,
 * беремо її за основу й переграємо ще не підтверджені введення. Різниця між тим, що вже
 * показали, і новим результатом гаситься плавно через {@link offsetX}/{@link offsetZ}.
 */
export class LocalPlayer {
  /** Те, що малюємо цього кадру. */
  readonly render = { x: 0, z: 0, rotation: 0, moving: false }

  private x: number
  private z: number
  private rotation: number
  private prevX: number
  private prevZ: number
  private moving = false
  private seq = 0
  private pending: Input[] = []
  private accumulator = 0
  private offsetX = 0
  private offsetZ = 0
  private target: Vec | null = null
  private stuckTicks = 0

  private readonly rules: MoveRules
  private readonly send: (input: Input) => void
  private readonly others: () => Vec[]

  constructor(
    start: { x: number; z: number; rotation: number },
    rules: MoveRules,
    send: (input: Input) => void,
    others: () => Vec[],
  ) {
    this.rules = rules
    this.send = send
    this.others = others
    this.x = this.prevX = this.render.x = start.x
    this.z = this.prevZ = this.render.z = start.z
    this.rotation = this.render.rotation = start.rotation
  }

  setTarget(x: number, z: number) {
    this.target = { x, z }
    this.stuckTicks = 0
  }

  /** Розвернутися до точки (сервер розвертає того, хто б'є, до цілі — повторюємо локально). */
  faceTowards(x: number, z: number) {
    if (x !== this.x || z !== this.z) this.rotation = Math.atan2(x - this.x, z - this.z)
  }

  /** Викликається щокадру. {@code key} — напрям з клавіатури (нуль, якщо нічого не натиснуто). */
  update(frameDt: number, key: Vec) {
    const dt = 1 / this.rules.tickRate
    this.accumulator = Math.min(this.accumulator + frameDt, MAX_CATCH_UP_SECONDS)
    while (this.accumulator >= dt) {
      this.accumulator -= dt
      this.tick(key)
    }

    // Між кроками симуляції інтерполюємо, щоб рух був плавним на 60+ fps.
    const decay = Math.exp(-CORRECTION_SPEED * frameDt)
    this.offsetX *= decay
    this.offsetZ *= decay
    const alpha = this.accumulator / dt
    this.render.x = this.prevX + (this.x - this.prevX) * alpha + this.offsetX
    this.render.z = this.prevZ + (this.z - this.prevZ) * alpha + this.offsetZ
    this.render.rotation = this.rotation
    this.render.moving = this.moving
  }

  /** Серверна позиція свого гравця прийшла — звіряємося. */
  reconcile(server: PlayerPos) {
    this.pending = this.pending.filter((i) => i.seq > server.lastSeq)
    let pos: Vec = { x: server.x, z: server.z }
    const others = this.others()
    for (const input of this.pending) {
      const r = applyMove(pos, input.dx, input.dz, others, this.rules)
      if (r) pos = r
    }

    const errX = this.x - pos.x
    const errZ = this.z - pos.z
    if (Math.hypot(errX, errZ) > SNAP_DISTANCE) {
      this.offsetX = this.offsetZ = 0
      this.prevX = pos.x
      this.prevZ = pos.z
    } else {
      // Зсуваємо і поточну, і попередню точку — тоді намальована позиція не стрибає,
      // а вся різниця йде в offset, який плавно згасає.
      this.offsetX += errX
      this.offsetZ += errZ
      this.prevX -= errX
      this.prevZ -= errZ
    }
    this.x = pos.x
    this.z = pos.z
  }

  private tick(key: Vec) {
    this.prevX = this.x
    this.prevZ = this.z

    let { x: dx, z: dz } = key
    if (dx !== 0 || dz !== 0) {
      this.target = null // клавіатура скасовує рух до цілі
    } else if (this.target) {
      const tx = this.target.x - this.x
      const tz = this.target.z - this.z
      const dist = Math.hypot(tx, tz)
      if (dist <= ARRIVE_EPS) {
        this.target = null
      } else {
        // Останній крок коротший, щоб зупинитися точно в цілі.
        const scale = Math.min(1, dist / (this.rules.speed / this.rules.tickRate))
        dx = (tx / dist) * scale
        dz = (tz / dist) * scale
      }
    }

    if (dx === 0 && dz === 0) {
      this.moving = false
      return
    }

    const input = { seq: ++this.seq, dx, dz }
    this.send(input)
    this.pending.push(input)
    const r = applyMove({ x: this.x, z: this.z }, dx, dz, this.others(), this.rules)!
    this.x = r.x
    this.z = r.z
    this.rotation = r.rotation
    this.moving = r.moving

    if (this.target) {
      if (Math.hypot(this.target.x - this.x, this.target.z - this.z) <= ARRIVE_EPS) {
        this.target = null
      } else if (r.moving) {
        this.stuckTicks = 0
      } else if (++this.stuckTicks >= STUCK_TICKS) {
        this.target = null // ціль зайнята або прохід заблоковано
      }
    }
  }
}
