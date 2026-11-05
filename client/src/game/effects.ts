import type { GameClient } from './GameClient'
import { playChimeSound, playMailSound, playPatSound, playSlapSound, playWhooshSound } from './sound'

/** Далі за цю відстань від себе лящ уже не чути. */
const HEARING_DISTANCE = 25

/**
 * Звуки: ляпас або «вжух» при ухилянні, «плесь-плесь» при рукостисканні, «дзінь-дзінь» за очки
 * (свої — гучно, чужі — тихіше).
 * Гучність — за відстанню до себе. Повертає відписку.
 */
export function subscribeSounds(client: GameClient) {
  const volumeAt = (from: string, to: string) => {
    const self = client.local?.render
    const target = client.positions.get(to)
    if (!self || !target) return 0
    if (from === client.selfId || to === client.selfId) return 0.9
    return 0.9 * Math.max(0, 1 - Math.hypot(target.x - self.x, target.z - self.z) / HEARING_DISTANCE)
  }
  const offs = [
    client.on('slapImpact', (e) => {
      const volume = volumeAt(e.from, e.to)
      if (e.dodged) playWhooshSound(volume * 0.6)
      else playSlapSound(volume)
    }),
    // Плескаємо в момент, коли руки зійшлися (після підйому руки).
    client.on('handshake', (e) => {
      setTimeout(() => playPatSound(volumeAt(e.from, e.to) * 0.5), 300)
    }),
    // Нове в моїй скриньці — якщо я не вимкнув звук цієї скриньки (усі звуки разом вимикає soundSettings).
    client.on('boardNotify', (e) => {
      if (!e.muted) playMailSound(0.8)
    }),
    client.on('scoreGain', (e) => {
      // Після «плесь» рукостискання, щоб звуки не злипались.
      setTimeout(() => playChimeSound(e.id === client.selfId ? 0.9 : volumeAt(e.id, e.id) * 0.4), 350)
    }),
  ]
  return () => offs.forEach((off) => off())
}
