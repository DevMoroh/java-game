/** Загальний вимикач звуку нових повідомлень у скриньках — для себе, запам'ятовується в цьому браузері. */
const STORAGE_KEY = 'devworld.messageSound'

let on = read()
const listeners = new Set<() => void>()

function read() {
  try {
    return localStorage.getItem(STORAGE_KEY) !== 'off'
  } catch {
    return true
  }
}

export function messageSoundOn() {
  return on
}

export function setMessageSoundOn(value: boolean) {
  on = value
  try {
    localStorage.setItem(STORAGE_KEY, value ? 'on' : 'off')
  } catch {
    // приватний режим — вибір діятиме до перезавантаження
  }
  listeners.forEach((fn) => fn())
}

export function subscribeMessageSound(fn: () => void) {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}
