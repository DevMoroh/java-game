import * as THREE from 'three'
import { HANDSHAKE_DURATION } from '../handshake'
import { JUMP_DURATION, jumpHeight } from '../jump'

/** Швидкість бігу в кліпі Jog_Fwd_Loop (м/с), виміряна з root motion-версії паку. */
const JOG_CLIP_SPEED = 5.36
const LOCOMOTION_FADE = 0.2
const ONE_SHOT_FADE_IN = 0.08
const ONE_SHOT_FADE_OUT = 0.2
/** Висота, на якій зустрічаються руки, у метрах моделі (до масштабу аватара). Нижче — рука не дотягується. */
const HANDSHAKE_HAND_HEIGHT = 1.15
const HANDSHAKE_BLEND_IN = 0.25
const HANDSHAKE_BLEND_OUT = 0.3
/** «Трусимо» руку: частота (Гц) і амплітуда (м). */
const HANDSHAKE_SHAKE_HZ = 3
const HANDSHAKE_SHAKE_AMPLITUDE = 0.04

// Тимчасові вектори для IK, щоб не алокувати щокадру.
const S = new THREE.Vector3()
const E = new THREE.Vector3()
const H = new THREE.Vector3()
const T = new THREE.Vector3()
const ROOT = new THREE.Vector3()
const PARTNER = new THREE.Vector3()
const DIR = new THREE.Vector3()
const POLE = new THREE.Vector3()
const ELBOW = new THREE.Vector3()
const FROM = new THREE.Vector3()
const Q = new THREE.Quaternion()
const Q_FULL = new THREE.Quaternion()
const Q_BONE = new THREE.Quaternion()
const Q_PARENT = new THREE.Quaternion()
const IDENTITY = new THREE.Quaternion()

export type Locomotion = 'idle' | 'run'

/**
 * Анімації одного персонажа: цикл руху (стоїть/біжить) з плавними переходами
 * разові дії (лящ, реакція на удар), що на час програвання повністю перекривають рух,
 * стрибок (висота моделі + пози відштовхування й приземлення) і рукостискання — права рука
 * процедурно (двокісткова IK) тягнеться до точки посередині між гравцями поверх будь-якої анімації.
 */
export class AvatarAnimator {
  private readonly mixer: THREE.AnimationMixer
  private readonly locomotion: Record<Locomotion, THREE.AnimationAction>
  private current: Locomotion = 'idle'
  private oneShot: THREE.AnimationAction | null = null
  /** Момент кліпу (с), після якого разова дія плавно повертається до руху. */
  private oneShotEndAt = 0
  private readonly clips: THREE.AnimationClip[]
  private readonly root: THREE.Object3D
  /** Час від початку стрибка (с) або null, якщо на землі. */
  private jumpTime: number | null = null
  private readonly avatarScale: number
  private readonly arm: { upper: THREE.Object3D; lower: THREE.Object3D; hand: THREE.Object3D }
  private handshake: { time: number; partner: () => THREE.Object3D | undefined } | null = null

  constructor(root: THREE.Object3D, clips: THREE.AnimationClip[], moveSpeed: number, avatarScale: number) {
    this.clips = clips
    this.root = root
    this.avatarScale = avatarScale
    const bone = (name: string) => {
      const b = root.getObjectByName(name)
      if (!b) throw new Error('Немає кістки ' + name)
      return b
    }
    this.arm = { upper: bone('upperarm_r'), lower: bone('lowerarm_r'), hand: bone('hand_r') }
    this.mixer = new THREE.AnimationMixer(root)
    this.locomotion = { idle: this.action('Idle_Loop'), run: this.action('Jog_Fwd_Loop') }
    // Синхронізуємо кроки зі швидкістю руху, щоб ноги не «ковзали» по траві.
    this.locomotion.run.timeScale = moveSpeed / (JOG_CLIP_SPEED * avatarScale)
    // Різні фази для різних гравців — щоб натовп не дихав синхронно.
    const idle = this.locomotion.idle
    idle.time = Math.random() * idle.getClip().duration
    idle.play()
  }

  /** Стрибок; повторний у повітрі ігнорується. */
  jump() {
    if (this.jumpTime !== null) return
    this.jumpTime = 0
    this.play('Jump_Start', Infinity) // поза в повітрі тримається до приземлення
  }

  /** Потиснути руку; {@code partner} — 3D-об'єкт іншого гравця (може зникнути, якщо той вийде). */
  shakeHands(partner: () => THREE.Object3D | undefined) {
    this.handshake = { time: 0, partner }
  }

  update(dt: number, moving: boolean) {
    if (this.jumpTime !== null) {
      this.jumpTime += dt
      if (this.jumpTime >= JUMP_DURATION) {
        this.jumpTime = null
        // На бігу приземлення коротке, на місці — з повним присіданням.
        this.play('Jump_Land', moving ? 0.25 : 0.75)
      }
    }
    // Піднімаємо лише модель: пляма й кільце в Character лишаються на землі.
    this.root.position.y = this.jumpTime === null ? 0 : jumpHeight(this.jumpTime)

    const next: Locomotion = moving ? 'run' : 'idle'
    if (this.oneShot) {
      // Під час разової дії лише запам'ятовуємо, до чого повертатися.
      this.current = next
      const action = this.oneShot
      if (action.time >= this.oneShotEndAt) {
        const back = this.locomotion[this.current]
        back.reset().play()
        action.crossFadeTo(back, ONE_SHOT_FADE_OUT, false)
        this.oneShot = null
      }
    } else if (next !== this.current) {
      const to = this.locomotion[next]
      to.reset().play()
      this.locomotion[this.current].crossFadeTo(to, LOCOMOTION_FADE, false)
      this.current = next
    }
    this.mixer.update(dt)
    this.updateHandshake(dt)
  }

  private updateHandshake(dt: number) {
    const hs = this.handshake
    if (!hs) return
    hs.time += dt
    const partner = hs.partner()
    if (hs.time >= HANDSHAKE_DURATION || !partner) {
      this.handshake = null
      return
    }
    // Плавно піднімаємо руку, тримаємо, плавно опускаємо.
    const t = hs.time
    const weight =
      THREE.MathUtils.smoothstep(t, 0, HANDSHAKE_BLEND_IN) *
      (1 - THREE.MathUtils.smoothstep(t, HANDSHAKE_DURATION - HANDSHAKE_BLEND_OUT, HANDSHAKE_DURATION))

    this.root.getWorldPosition(ROOT)
    partner.getWorldPosition(PARTNER)
    const shake = Math.sin(t * Math.PI * 2 * HANDSHAKE_SHAKE_HZ) * HANDSHAKE_SHAKE_AMPLITUDE * weight
    // Обидва тягнуться до середини; рука ледь не дотягується, тож зап'ястя зупиняються
    // за ~10–15 см одне від одного — якраз як при зімкнутих долонях.
    T.copy(ROOT).add(PARTNER).multiplyScalar(0.5)
    T.y = ROOT.y + (HANDSHAKE_HAND_HEIGHT + shake) * this.avatarScale
    this.reachRightHand(T, weight)
  }

  /**
   * Двокісткова IK: плече → лікоть → кисть до точки {@code target} (світові координати).
   * Кут у плечі — з теореми косинусів, лікоть гнеться вниз і назовні від тулуба.
   */
  private reachRightHand(target: THREE.Vector3, weight: number) {
    const { upper, lower, hand } = this.arm
    this.root.updateMatrixWorld(true)
    upper.getWorldPosition(S)
    lower.getWorldPosition(E)
    hand.getWorldPosition(H)
    const a = S.distanceTo(E)
    const b = E.distanceTo(H)

    DIR.subVectors(target, S)
    const c = THREE.MathUtils.clamp(DIR.length(), Math.abs(a - b) + 1e-3, (a + b) * 0.999)
    DIR.normalize()
    const alpha = Math.acos(THREE.MathUtils.clamp((a * a + c * c - b * b) / (2 * a * c), -1, 1))

    // Напрям, куди «дивиться» лікоть: вниз і в бік плеча від центру тіла.
    POLE.subVectors(S, ROOT).setY(0).normalize().multiplyScalar(0.6)
    POLE.y -= 1
    POLE.addScaledVector(DIR, -POLE.dot(DIR)).normalize()
    ELBOW.copy(DIR).multiplyScalar(Math.cos(alpha)).addScaledVector(POLE, Math.sin(alpha))

    aim(upper, FROM.subVectors(E, S).normalize(), ELBOW, weight)
    upper.updateMatrixWorld(true)
    lower.getWorldPosition(E)
    hand.getWorldPosition(H)
    aim(lower, FROM.subVectors(H, E).normalize(), DIR.subVectors(target, E).normalize(), weight)
  }

  /**
   * Разова анімація поверх руху (Melee_Hook, Hit_Head, …).
   * {@code endAt} — з якої секунди кліпу повертатися до руху (за замовчуванням — під кінець;
   * Infinity — тримати, доки не прийде наступна разова дія, як-от Jump_Start до приземлення).
   */
  play(name: string, endAt?: number) {
    const action = this.action(name)
    action.setLoop(THREE.LoopOnce, 1)
    action.clampWhenFinished = true
    const from = this.oneShot ?? this.locomotion[this.current]
    action.reset().play()
    if (from !== action) from.crossFadeTo(action, ONE_SHOT_FADE_IN, false)
    this.oneShot = action
    this.oneShotEndAt = endAt ?? action.getClip().duration - ONE_SHOT_FADE_OUT
  }

  private action(name: string) {
    const clip = this.clips.find((c) => c.name === name)
    if (!clip) throw new Error('animations.glb: немає кліпу ' + name)
    return this.mixer.clipAction(clip)
  }
}

/** Повертає кістку так, щоб світовий напрям {@code from} став {@code to} (з вагою {@code weight}). */
function aim(bone: THREE.Object3D, from: THREE.Vector3, to: THREE.Vector3, weight: number) {
  Q_FULL.setFromUnitVectors(from, to)
  // Окремий приймач: slerpQuaternions спершу копіює першу кватерніону в this.
  Q.slerpQuaternions(IDENTITY, Q_FULL, weight)
  bone.getWorldQuaternion(Q_BONE)
  bone.parent!.getWorldQuaternion(Q_PARENT)
  // локальна = батьківська⁻¹ · (поворот · поточна світова)
  bone.quaternion.copy(Q_PARENT.invert().multiply(Q.multiply(Q_BONE)))
}
