import { useEffect, useState, useSyncExternalStore, type FormEvent } from 'react'
import { Game } from './game/Game'
import { ArticleModal } from './game/ArticleModal'
import { BoardPanel } from './game/BoardPanel'
import { ChatPanel } from './game/ChatPanel'
import { CollectionPanel } from './game/CollectionPanel'
import { GameClient } from './game/GameClient'
import { HandshakePrompt } from './game/HandshakePrompt'
import { Leaderboard } from './game/Leaderboard'
import { ChatPrompts } from './game/ChatPrompts'
import { PlayerPanel } from './game/PlayerPanel'
import { messageSoundOn, setMessageSoundOn, subscribeMessageSound } from './game/messageSound'
import { clearSession, loadSession, type Session } from './game/session'
import type { Gender } from './game/types'

interface JoinRequest {
  name: string
  gender: Gender
  resume?: Session
}

/** Є сесія в цій вкладці (перезавантаження) — одразу заходимо тим самим персонажем, без екрана входу. */
function initialRequest(): JoinRequest | null {
  const session = loadSession()
  return session && { name: session.name, gender: session.gender, resume: session }
}

export default function App() {
  const [request, setRequest] = useState<JoinRequest | null>(initialRequest)
  const [client, setClient] = useState<GameClient | null>(null)

  // З'єднання створюємо в ефекті з очищенням: StrictMode викликає ініціалізатори двічі, і зайвий
  // WebSocket лишав би в грі двійника. Тут перший клієнт закривається ще до відкриття сокета.
  useEffect(() => {
    if (!request) return
    const c = new GameClient(request.name, request.gender, request.resume)
    // oxlint-disable-next-line react/set-state-in-effect -- синхронізація із зовнішнім ресурсом (WebSocket)
    setClient(c)
    return () => {
      c.close()
      setClient(null)
    }
  }, [request])

  // Для дебагу в консолі: __game.local, __game.positions …
  useEffect(() => {
    if (import.meta.env.DEV) Object.assign(window, { __game: client })
  }, [client])

  if (!request) {
    return <JoinScreen onJoin={(name, gender) => setRequest({ name, gender })} />
  }
  if (!client) return null
  return (
    <>
      <Game client={client} />
      <Hud
        client={client}
        onReconnect={() => setRequest(initialRequest() ?? { ...request })}
        onLeave={() => {
          clearSession()
          setRequest(null)
        }}
      />
    </>
  )
}

function JoinScreen({ onJoin }: { onJoin: (name: string, gender: Gender) => void }) {
  const [name, setName] = useState(() => localStorageGet('name'))
  const [gender, setGender] = useState<Gender>(() => (localStorageGet('gender') as Gender) || 'MALE')

  const submit = (e: FormEvent) => {
    e.preventDefault()
    localStorageSet('name', name)
    localStorageSet('gender', gender)
    onJoin(name.trim(), gender)
  }

  return (
    <div className="join">
      <form className="join-card" onSubmit={submit}>
        <h1>Dev World</h1>
        <p className="muted">Join as a guest and explore the field</p>
        <label>
          Name
          <input value={name} maxLength={20} placeholder="Guest" autoFocus onChange={(e) => setName(e.target.value)} />
        </label>
        <div className="gender">
          {(['MALE', 'FEMALE'] as const).map((g) => (
            <button type="button" key={g} className={gender === g ? 'active' : ''} onClick={() => setGender(g)}>
              {g === 'MALE' ? '👨 Male' : '👩 Female'}
            </button>
          ))}
        </div>
        <button type="submit" className="primary">Join the game</button>
      </form>
    </div>
  )
}

function Hud({ client, onReconnect, onLeave }: { client: GameClient; onReconnect: () => void; onLeave: () => void }) {
  const players = useSyncExternalStore(client.subscribe, client.getPlayers)
  const status = useSyncExternalStore(client.subscribe, client.getStatus)

  return (
    <>
      <div className="hud-left">
        <div className="hud">
          <strong>Dev World</strong>
          <span className="muted"> · online: {players.length}</span>
          <button className="link-button" onClick={onLeave} title="Leave and join as a different character">
            Leave
          </button>
        </div>
        <PlayerPanel client={client} />
        <CollectionPanel client={client} />
      </div>
      <MessageSoundToggle />
      <Leaderboard client={client} />
      <ChatPrompts client={client} />
      {/* Чат і підказка — одним стовпчиком: так вони не налазять одне на одного за будь-якої висоти. */}
      <div className="bottom-left-stack">
        <ChatPanel client={client} />
        <div className="hud hint muted">
          WASD / click — move · E — open box / egg · Space — jump · H — handshake · F — slap · Enter — chat · wheel — zoom
        </div>
      </div>
      <HandshakePrompt client={client} />
      <BoardPanel client={client} />
      <ArticleModal client={client} />
      <div className="actions">
        <ActionButton className="shake" title="Offer a handshake / accept an offered one (H)" onClick={() => client.handshake()}>
          🤝 Handshake <kbd>H</kbd>
        </ActionButton>
        <ActionButton className="slap" title="Slap the nearest player (F)" onClick={() => client.slap()}>
          👋 Slap <kbd>F</kbd>
        </ActionButton>
      </div>
      {status !== 'online' && (
        <div className="overlay">
          <div className="join-card">
            {status === 'connecting' ? (
              <p>Connecting…</p>
            ) : (
              <>
                <p>Connection to the server was lost</p>
                <button className="primary" onClick={onReconnect}>
                  Reconnect
                </button>
                <button onClick={onLeave}>Leave</button>
              </>
            )}
          </div>
        </div>
      )}
    </>
  )
}

/** Зверху по центру: вимкнути для себе звук нових повідомлень одразу в усіх скриньках. */
function MessageSoundToggle() {
  const on = useSyncExternalStore(subscribeMessageSound, messageSoundOn)
  return (
    <button
      className={`message-sound-toggle ${on ? '' : 'off'}`}
      title={on ? 'Mute new message sounds in all boxes' : 'Turn new message sounds back on'}
      onClick={(e) => {
        setMessageSoundOn(!on)
        e.currentTarget.blur()
      }}
    >
      {on ? '🔔' : '🔕'} <span className="label">Message sounds {on ? 'on' : 'off'}</span>
    </button>
  )
}

function ActionButton({
  className,
  title,
  onClick,
  children,
}: {
  className: string
  title: string
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      className={`action-button ${className}`}
      title={title}
      onClick={(e) => {
        onClick()
        e.currentTarget.blur() // щоб пробіл потім не «натискав» кнопку
      }}
    >
      {children}
    </button>
  )
}

function localStorageGet(key: string) {
  try {
    return localStorage.getItem(`devworld.${key}`) ?? ''
  } catch {
    return ''
  }
}

function localStorageSet(key: string, value: string) {
  try {
    localStorage.setItem(`devworld.${key}`, value)
  } catch {
    // приватний режим — не критично
  }
}
