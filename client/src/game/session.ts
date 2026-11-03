import type { Appearance, Gender } from './types'

/**
 * Сесія гостя в sessionStorage: переживає перезавантаження сторінки, але своя в кожній вкладці
 * (кілька вкладок = кілька різних персонажів). token — від сервера; appearance — запасний кеш
 * на випадок, якщо сервер перезапустили і він забув профіль.
 */
export interface Session {
  token: string
  name: string
  gender: Gender
  appearance: Appearance
}

const KEY = 'devworld.session'

export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    return raw ? (JSON.parse(raw) as Session) : null
  } catch {
    return null
  }
}

export function saveSession(session: Session) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(session))
  } catch {
    // приватний режим — просто не пам'ятатимемо
  }
}

export function clearSession() {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // нічого
  }
}
