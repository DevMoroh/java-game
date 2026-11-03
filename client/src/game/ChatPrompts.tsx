import { useEffect, useState, useSyncExternalStore } from 'react'
import { CIRCLE_RADIUS } from './Character'
import type { GameClient } from './GameClient'

const NOTICE_MS = 3_500
/** Як часто перевіряти, чи стоїш у чужому колі (позиції змінюються щокадру, але кнопці стільки не треба). */
const NEARBY_CHECK_MS = 200
/** Гравець рухається — тримаємо кнопку ще мить після виходу з його кола, щоб встигнути натиснути. */
const NEARBY_GRACE_MS = 2000

let noticeSeq = 0

/**
 * Внизу екрана: «Поруч X — Приєднатися до чату», коли стоїш у колі гравця, з яким ти ще не в одному чаті,
 * і короткі повідомлення про результат.
 */
export function ChatPrompts({ client }: { client: GameClient }) {
  const [notices, setNotices] = useState<{ id: number; text: string; ok: boolean }[]>([])
  const nearby = useNearbyPlayer(client)

  useEffect(
    () =>
      client.on('notice', (e) => {
        const notice = { id: ++noticeSeq, text: e.text, ok: e.ok }
        setNotices((n) => [...n, notice])
        setTimeout(() => setNotices((n) => n.filter((x) => x.id !== notice.id)), NOTICE_MS)
      }),
    [client],
  )

  if (!nearby && notices.length === 0) return null
  return (
    <div className="team-prompts">
      {nearby && (
        <div className="team-card">
          <span className="color-dot" style={{ background: nearby.color }} />
          <span>Поруч {nearby.name}</span>
          <button className="primary" onClick={() => client.joinPlayer(nearby.id)}>
            Приєднатися до чату
          </button>
        </div>
      )}
      {notices.map((n) => (
        <div key={n.id} className={n.ok ? 'team-card' : 'team-card error'}>
          {n.text}
        </div>
      ))}
    </div>
  )
}

/** Найближчий гравець, у чиєму колі ти стоїш і з ким ти ще не в одному чаті (або null). */
function useNearbyPlayer(client: GameClient) {
  const players = useSyncExternalStore(client.subscribe, client.getPlayers)
  const myGroup = client.getSelf()?.group ?? null
  const [nearbyId, setNearbyId] = useState<string | null>(null)

  useEffect(() => {
    let lastSeen: { id: string; at: number } | null = null
    const check = () => {
      const me = client.local?.render
      let best: { id: string; d: number } | null = null
      for (const p of players) {
        const pos = client.positions.get(p.id)
        if (!me || !pos || p.id === client.selfId || (myGroup && p.group === myGroup)) continue
        const d = Math.hypot(pos.x - me.x, pos.z - me.z)
        if (d <= CIRCLE_RADIUS && (!best || d < best.d)) best = { id: p.id, d }
      }
      const now = performance.now()
      if (best) lastSeen = { id: best.id, at: now }
      else if (lastSeen && now - lastSeen.at > NEARBY_GRACE_MS) lastSeen = null
      setNearbyId(lastSeen?.id ?? null)
    }
    check()
    const timer = setInterval(check, NEARBY_CHECK_MS)
    return () => clearInterval(timer)
  }, [client, players, myGroup])

  const p = players.find((x) => x.id === nearbyId)
  return p && !(myGroup && p.group === myGroup) ? p : null
}
