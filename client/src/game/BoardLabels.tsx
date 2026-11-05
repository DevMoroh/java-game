import { useSyncExternalStore } from 'react'
import { boardColor } from './fieldConstants'
import type { GameClient } from './GameClient'
import { worldLabelRef } from './worldLabels'

/**
 * Підписи над скриньками: коротка назва; у скриньок, до яких я приєднався, — 🔔 (🔕 — звук вимкнено), а коли там
 * є нове — конверт з кількістю непрочитаного (своєю в кожного гравця), що «бумкає» на кожне нове повідомлення.
 */
export function BoardLabels({ client }: { client: GameClient }) {
  const boards = useSyncExternalStore(client.subscribe, client.getBoards)
  const status = useSyncExternalStore(client.subscribe, client.getBoardStatus)
  return (
    <div className="nameplates">
      {boards.map((b) => {
        const joined = status.joined.includes(b.id)
        const muted = status.muted.includes(b.id)
        const unread = status.unread[b.id] ?? 0
        return (
          <div key={b.id} className="label" ref={worldLabelRef(`board:${b.id}`, b.x, 2.05, b.z)}>
            {joined && unread > 0 && (
              // key — щоб «бум» запускався на кожне нове повідомлення
              <div key={unread} className="board-unread arrived" title={`${unread} new`}>
                ✉️ {unread > 99 ? '99+' : unread}
              </div>
            )}
            <div className="board-title" style={{ borderColor: boardColor(boards, b.id) }}>
              {joined && (muted ? <span title="Joined, sound off">🔕 </span> : <span title="You joined this box">🔔 </span>)}
              {b.anonymous && <span title="Anonymous">🕶️ </span>}
              {b.title}
            </div>
          </div>
        )
      })}
    </div>
  )
}
