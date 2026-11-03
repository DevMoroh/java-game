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
        <span className="color-dot" style={{ background: self.color }} title="Колір твого кола" />
        <strong>⭐ {self.score}</strong>
        <span className="muted"> очок</span>
      </div>
      <StatusPicker client={client} status={self.status} />
      {self.group ? (
        <div className="panel-row">
          <span>
            У чаті з {mates.length > 0 ? mates.map((m) => m.name).join(', ') : 'тими, хто зараз не в грі'}
          </span>
          <button className="link-button" onClick={() => client.leaveGroup()}>
            Вийти з чату
          </button>
        </div>
      ) : (
        <div className="panel-row muted">Загальний чат — підійди до когось, щоб поговорити окремо</div>
      )}
    </div>
  )
}
