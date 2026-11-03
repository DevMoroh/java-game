import type { MoveRules } from './movement'

export type Gender = 'MALE' | 'FEMALE'

export interface Appearance {
  skin: string
  hair: string
  shirt: string
  pants: string
  hairStyle: number
  beard: boolean
}

export interface PlayerInfo {
  id: string
  name: string
  gender: Gender
  appearance: Appearance
  x: number
  z: number
  rotation: number
  /** Особисті очки: +1 за рукостискання, −1 за отриманого ляща (не нижче 0). */
  score: number
  /** Груповий чат гравця (id групи) або null — загальний чат. */
  group: string | null
  /** Колір кола під гравцем: колір групи, якщо він у груповому чаті, інакше власний (унікальний на полі). */
  color: string
  /** Що гравець зараз робить («🎧 Слухаю музику»); порожньо — без статусу. */
  status: string
}

export interface PlayerPos {
  id: string
  x: number
  z: number
  rotation: number
  moving: boolean
  /** Останнє оброблене сервером введення цього гравця. */
  lastSeq: number
}

export type ServerMessage =
  | { type: 'welcome'; selfId: string; token: string; worldSize: number; rules: MoveRules; players: PlayerInfo[] }
  | { type: 'joined'; player: PlayerInfo }
  | { type: 'left'; id: string }
  | { type: 'state'; tick: number; players: PlayerPos[] }
  | GameEvent
  | { type: 'playerUpdate'; id: string; score: number; group: string | null; color: string; status: string }
  | { type: 'chatHistory'; group: string | null; messages: ChatMessage[] }

  | { type: 'chatError'; text: string }

/** Повідомлення чату (group === null — загальний); name — на випадок, якщо автор уже вийшов; at — epoch мс. */
export interface ChatMessage {
  group: string | null
  from: string
  name: string
  text: string
  at: number
}

/** Рядок у панелі чату: повідомлення або системний рядок (хтось зайшов/вийшов, помилка). */
export type ChatLine =
  | ({ kind: 'message'; key: number } & ChatMessage)
  | { kind: 'system'; key: number; text: string; error?: boolean }

/** Ігрові події, на які підписуються анімації, ефекти й інтерфейс (GameClient.on). */
export type GameEvent =
  | SlapEvent
  | SlapImpactEvent
  | JumpEvent
  | HandshakeOfferEvent
  | HandshakeEvent
  | NoticeEvent
  | ChatEvent

/** Нове повідомлення в моєму чаті (груповому або загальному). */
export interface ChatEvent extends ChatMessage {
  type: 'chat'
}

/** Повідомлення про дію з чатом (успіх або помилка). */
export interface NoticeEvent {
  type: 'notice'
  text: string
  ok: boolean
}

/** from простягнув руку to; рука чекає expiresInMs. */
export interface HandshakeOfferEvent {
  type: 'handshakeOffer'
  from: string
  to: string
  expiresInMs: number
}

/** Рукостискання відбулося: from простягнув руку, to потиснув; scored — обидва отримали +1. */
export interface HandshakeEvent {
  type: 'handshake'
  from: string
  to: string
  scored: boolean
}

/** Момент удару (сервер вирішує через 0.2 с після замаху): dodged — ціль ухилилася стрибком. */
export interface SlapImpactEvent {
  type: 'slapImpact'
  from: string
  to: string
  dodged: boolean
  /** Скільки очок зняли з того, хто отримав ляща (0 — ухилився або вже мав 0). */
  penalty: number
}

/** Гравець стрибнув. */
export interface JumpEvent {
  type: 'jump'
  id: string
}

/** Замах ляща від from на to; to === null — поруч нікого. Результат — окремо, у SlapImpactEvent. */
export interface SlapEvent {
  type: 'slap'
  from: string
  to: string | null
  /** Коли to === null: найближчий свій (спільна команда), якого бити не можна. */
  ally: string | null
}
