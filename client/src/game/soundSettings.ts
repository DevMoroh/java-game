/** Загальний вимикач звуків гри для себе (бали, пошта, ляпаси, рукостискання) — запам'ятовується в цьому браузері. */
const STORAGE_KEY = 'devworld.sounds'
/** Раніше вимикач стосувався лише звуку пошти — його вибір переносимо. */
const OLD_KEY = 'devworld.messageSound'

let on = read()
const listeners = new Set<() => void>()

function read() {
  try {
    return (localStorage.getItem(STORAGE_KEY) ?? localStorage.getItem(OLD_KEY)) !== 'off'
  } catch {
    return true
  }
}

export function soundsOn() {
  return on
}

export function setSoundsOn(value: boolean) {
  on = value
  try {
    localStorage.setItem(STORAGE_KEY, value ? 'on' : 'off')
  } catch {
    // приватний режим — вибір діятиме до перезавантаження
  }
  listeners.forEach((fn) => fn())
}

export function onSoundsChange(fn: () => void) {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}
