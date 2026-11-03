import type { GameClient } from './GameClient'
import { playPatSound, playSlapSound, playWhooshSound } from './sound'

/** Далі за цю відстань від себе лящ уже не чути. */
const HEARING_DISTANCE = 25

/**
 * Звуки: ляпас або «вжух» при ухилянні, «плесь-плесь» при рукостисканні.
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
  ]
  return () => offs.forEach((off) => off())
}
