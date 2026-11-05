import { useSyncExternalStore } from 'react'
import type { GameClient } from './GameClient'
import { StatusPicker } from './StatusPicker'

/** Мої очки, статус і чат: загальний або груповий (з ким) — з груповим можна вийти. */
export function PlayerPanel({ client }: { client: GameClient }) {
  useSyncExternalStore(client.subscribe, client.getPlayers)
  const self = client.getSelf()
  if (!self) return null
  const mates = client.getGroupmates()

  return (
    <div className="panel team-panel">
      <div className="panel-row">
        <span className="color-dot" style={{ background: self.color }} title="Your circle color" />
        {/* key — щоб анімація «підстрибнути» запускалась на кожну зміну рахунку */}
        <strong key={self.score} className="score-bump">
          ⭐ {self.score}
        </strong>
        <span className="muted"> points</span>
      </div>
      <StatusPicker client={client} status={self.status} />
      {self.group ? (
        <div className="panel-row">
          <span>
            Chatting with {mates.length > 0 ? mates.map((m) => m.name).join(', ') : 'players who are offline'}
          </span>
          <button className="link-button" onClick={() => client.leaveGroup()}>
            Leave chat
          </button>
        </div>
      ) : (
        <div className="panel-row muted">General chat — walk up to someone to talk privately</div>
      )}
    </div>
  )
}
