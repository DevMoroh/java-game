import { LocalPlayer } from './LocalPlayer'
import type { MoveRules, Vec } from './movement'
import { JUMP_DURATION } from './jump'
import { saveSession, type Session } from './session'
import type { ChatLine, ChatMessage, GameEvent, Gender, PlayerInfo, PlayerPos, ServerMessage } from './types'

/** ?lag=200 в адресі — штучна затримка (мс туди й назад) для перевірки гри на повільній мережі. */
const SIMULATED_LAG_MS = Number(new URLSearchParams(location.search).get('lag')) || 0

/** Клієнтська пауза між лящами — як на сервері, щоб не слати марних запитів. */
const SLAP_COOLDOWN_MS = 800

type EventOf<T extends GameEvent['type']> = Extract<GameEvent, { type: T }>

/** Скільки рядків чату тримати в пам'яті клієнта. */
const CHAT_LINES = 200

export type Status = 'connecting' | 'online' | 'closed'

/**
 * WebSocket-клієнт і локальна копія світу.
 * Список гравців (join/leave) — для React через subscribe/getPlayers,
 * позиції (state) — мутабельна мапа, яку читає useFrame щокадру без ре-рендерів.
 * Власний гравець рухається через {@link LocalPlayer} (передбачення).
 */
export class GameClient {
  selfId: string | null = null
  worldSize = 100
  status: Status = 'connecting'
  local: LocalPlayer | null = null
  /** Правила руху від сервера; до welcome — значення за замовчуванням. */
  rules: MoveRules = { tickRate: 20, speed: 6, playerRadius: 0.45, boundary: 49 }
  /** Напрям з клавіатури; LocalPlayer читає його щокроку. */
  readonly keys: Vec = { x: 0, z: 0 }
  /** Останні серверні позиції всіх гравців (для свого — лише для звірки). */
  readonly positions = new Map<string, PlayerPos>()

  private players: PlayerInfo[] = []
  private chat: ChatLine[] = []
  private chatKey = 0
  /** Груповий чат, який зараз показуємо, або null — загальний. */
  private chatGroup: string | null = null
  private readonly listeners = new Set<() => void>()
  private readonly eventListeners = new Map<GameEvent['type'], Set<(e: GameEvent) => void>>()
  private lastSlapAt = -Infinity
  private lastJumpAt = -Infinity
  private readonly ws: WebSocket

  /** {@code resume} — сесія з минулого входу: сервер поверне того самого персонажа на те саме місце. */
  constructor(name: string, gender: Gender, resume?: Session) {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    this.ws = new WebSocket(`${proto}://${location.host}/ws`)
    this.ws.onopen = () =>
      this.send({ type: 'join', name, gender, token: resume?.token, appearance: resume?.appearance })
    this.ws.onmessage = (e) => this.delayed(() => this.handle(JSON.parse(e.data) as ServerMessage))
    this.ws.onclose = () => {
      this.status = 'closed'
      this.emit()
    }
  }

  subscribe = (fn: () => void) => {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  getPlayers = () => this.players
  getStatus = () => this.status
  getChat = () => this.chat
  getChatGroup = () => this.chatGroup

  sendChat(text: string) {
    const clean = text.trim()
    if (clean) this.send({ type: 'chat', text: clean })
  }

  /**
   * Підписка на ігрові події (замах, удар, стрибок, рукостискання) для анімацій, ефектів і інтерфейсу.
   * Повертає функцію відписки.
   */
  on<T extends GameEvent['type']>(type: T, fn: (e: EventOf<T>) => void): () => void {
    let set = this.eventListeners.get(type)
    if (!set) this.eventListeners.set(type, (set = new Set()))
    const listener = fn as (e: GameEvent) => void
    set.add(listener)
    return () => {
      set.delete(listener)
    }
  }

  nameOf(id: string) {
    return this.players.find((p) => p.id === id)?.name ?? '?'
  }

  getSelf = () => this.players.find((p) => p.id === this.selfId)

  /** Встановити статус («🎧 Слухаю музику»); порожній рядок — прибрати. */
  setStatus(text: string) {
    this.send({ type: 'status', text })
  }

  /** Приєднатися до чату гравця, у чиєму колі стоїш (коло стане його кольору). */
  joinPlayer(id: string) {
    this.send({ type: 'groupJoin', player: id })
  }

  /** Вийти з групового чату — назад у загальний, зі своїм кольором. */
  leaveGroup() {
    this.send({ type: 'groupLeave' })
  }

  /** Інші гравці в моєму груповому чаті (онлайн). */
  getGroupmates = () => {
    const self = this.getSelf()
    return self?.group ? this.players.filter((p) => p.group === self.group && p.id !== this.selfId) : []
  }

  /** Стрибок: свій персонаж стрибає одразу, не чекаючи сервера (як і з рухом). */
  jump() {
    const now = performance.now()
    if (!this.selfId || now - this.lastJumpAt < JUMP_DURATION * 1000) return
    this.lastJumpAt = now
    this.send({ type: 'jump' })
    this.dispatch({ type: 'jump', id: this.selfId })
  }

  /** Простягнути руку найближчому або потиснути руку, простягнуту тобі. */
  handshake() {
    this.send({ type: 'handshake' })
  }

  slap() {
    const now = performance.now()
    if (now - this.lastSlapAt < SLAP_COOLDOWN_MS) return
    this.lastSlapAt = now
    this.send({ type: 'slap' })
  }

  close() {
    this.ws.close()
  }

  private handle(msg: ServerMessage) {
    switch (msg.type) {
      case 'welcome': {
        this.selfId = msg.selfId
        this.worldSize = msg.worldSize
        this.rules = msg.rules
        this.status = 'online'
        this.players = msg.players
        msg.players.forEach((p) => this.positions.set(p.id, { ...p, moving: false, lastSeq: 0 }))
        const self = msg.players.find((p) => p.id === msg.selfId)!
        saveSession({ token: msg.token, name: self.name, gender: self.gender, appearance: self.appearance })
        this.local = new LocalPlayer(
          self,
          msg.rules,
          (input) => this.send({ type: 'input', ...input }),
          () => this.otherPositions(),
        )
        this.emit()
        break
      }
      case 'joined':
        if (this.players.some((p) => p.id === msg.player.id)) return
        this.players = [...this.players, msg.player]
        this.positions.set(msg.player.id, { ...msg.player, moving: false, lastSeq: 0 })
        this.emit()
        break
      case 'left':
        this.players = this.players.filter((p) => p.id !== msg.id)
        this.positions.delete(msg.id)
        this.emit()
        break
      case 'state': {
        let self: PlayerPos | undefined
        for (const p of msg.players) {
          this.positions.set(p.id, p)
          if (p.id === this.selfId) self = p
        }
        // Звіряємось після оновлення всіх, щоб переграти введення з актуальними позиціями інших.
        if (self) this.local?.reconcile(self)
        break
      }
      case 'jump':
        // Свій стрибок уже показали при натисканні.
        if (msg.id !== this.selfId) this.dispatch(msg)
        break
      case 'slap':
        if (msg.from === this.selfId) this.faceTowards(msg.to)
        this.dispatch(msg)
        break
      case 'handshake':
        // Сервер уже розвернув обох; свій поворот — локальний, тож повторюємо.
        if (msg.from === this.selfId) this.faceTowards(msg.to)
        if (msg.to === this.selfId) this.faceTowards(msg.from)
        this.dispatch(msg)
        break
      case 'playerUpdate': {
        const before = this.players.find((p) => p.id === msg.id)
        // Хтось приєднався до мого групового чату або вийшов з нього — системний рядок.
        if (before && msg.id !== this.selfId && this.chatGroup && before.group !== msg.group) {
          if (msg.group === this.chatGroup) this.addChat({ kind: 'system', key: 0, text: `${before.name} приєднався до чату` })
          else if (before.group === this.chatGroup) this.addChat({ kind: 'system', key: 0, text: `${before.name} вийшов з чату` })
        }
        // Новий об'єкт гравця, але та сама appearance — аватар не перебудовується.
        this.players = this.players.map((p) =>
          p.id === msg.id ? { ...p, score: msg.score, group: msg.group, color: msg.color, status: msg.status } : p,
        )
        this.emit()
        break
      }
      case 'chatHistory':
        // Мій чат (груповий або загальний): при вході й щоразу, коли він змінюється.
        this.chatGroup = msg.group
        this.chat = []
        msg.messages.forEach((m) => this.addChat(toLine(m)))
        this.emit()
        break
      case 'chat':
        if (msg.group !== this.chatGroup) break // запізніле повідомлення попереднього чату
        this.addChat(toLine(msg))
        this.emit()
        this.dispatch(msg)
        break
      case 'chatError':
        this.addChat({ kind: 'system', key: 0, text: msg.text, error: true })
        this.emit()
        break
      case 'slapImpact':
      case 'handshakeOffer':
      case 'notice':
        this.dispatch(msg)
        break
    }
  }

  /** Додає рядок у чат (новий масив — для useSyncExternalStore); emit робить той, хто викликає. */
  private addChat(line: ChatLine) {
    this.chat = [...this.chat.slice(-(CHAT_LINES - 1)), { ...line, key: ++this.chatKey }]
  }

  private faceTowards(id: string | null) {
    const target = id ? this.positions.get(id) : undefined
    if (target) this.local?.faceTowards(target.x, target.z)
  }

  private dispatch(e: GameEvent) {
    this.eventListeners.get(e.type)?.forEach((fn) => fn(e))
  }

  private otherPositions(): Vec[] {
    const result: Vec[] = []
    for (const p of this.positions.values()) {
      if (p.id !== this.selfId) result.push(p)
    }
    return result
  }

  private send(msg: object) {
    const text = JSON.stringify(msg)
    this.delayed(() => {
      if (this.ws.readyState === WebSocket.OPEN) this.ws.send(text)
    })
  }

  private delayed(fn: () => void) {
    if (SIMULATED_LAG_MS > 0) setTimeout(fn, SIMULATED_LAG_MS / 2)
    else fn()
  }

  private emit() {
    this.listeners.forEach((fn) => fn())
  }
}

function toLine(m: ChatMessage): ChatLine {
  return { kind: 'message', key: 0, group: m.group, from: m.from, name: m.name, text: m.text, at: m.at }
}
