import { useSyncExternalStore } from 'react'
import type { GameClient } from './GameClient'

const TOP = 5

/** Топ гравців онлайн за очками. */
export function Leaderboard({ client }: { client: GameClient }) {
  const players = useSyncExternalStore(client.subscribe, client.getPlayers)
  const top = [...players].sort((a, b) => b.score - a.score || a.name.localeCompare(b.name)).slice(0, TOP)
  return (
    <div className="panel leaderboard">
      <strong>🏆 Leaderboard</strong>
      <ol>
        {top.map((p) => (
          <li key={p.id} className={p.id === client.selfId ? 'self' : ''}>
            <span className="lb-name">{p.name}</span>
            <span className="lb-score">{p.score}</span>
          </li>
        ))}
      </ol>
    </div>
  )
}
