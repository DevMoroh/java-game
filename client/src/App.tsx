import { useEffect, useRef, useState, useSyncExternalStore, type FormEvent } from 'react'
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
import { onSoundsChange, setSoundsOn, soundsOn } from './game/soundSettings'
import { clearSession, loadSession, type Session } from './game/session'
import type { Gender } from './game/types'

/**
 * Зв'язок обірвався (найчастіше — деплой: старий сервер зупиняється, новий уже працює): перепідключаємось самі,
 * тим самим персонажем — спершу за 0.3–0.8 с (розкид, щоб усі гравці не прийшли однією хвилею), далі 2, 4, 8, 8… с.
 * Кнопки «Reconnect / Leave» — лише якщо сервер так і не повернувся (~1.5 хв).
 */
const AUTO_RECONNECT_ATTEMPTS = 12

/**
 * Фонову вкладку браузер пригальмовує або «заморожує», а сервер далі шле стан 20 разів на секунду — повідомлення
 * чекають у мережевих буферах. Після повернення клієнт програвав би цю чергу: кролики стрибають на місці, а потім
 * мчать. Тож якщо вкладки довго не було видно, перепідключаємось — і одразу отримуємо свіжий стан світу.
 */
const RESYNC_AFTER_HIDDEN_MS = 30_000

function reconnectDelay(attempt: number) {
  return attempt === 0 ? 300 + Math.random() * 500 : Math.min(1000 * 2 ** attempt, 8000)
}

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
  const [gaveUp, setGaveUp] = useState(false)
  const attempts = useRef(0)

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

  useEffect(() => {
    if (!client) return
    let timer: number | undefined
    let disposed = false
    const check = () => {
      const status = client.getStatus()
      if (status === 'online') {
        attempts.current = 0
        setGaveUp(false)
      }
      if (status !== 'closed' || disposed || timer !== undefined) return
      if (attempts.current >= AUTO_RECONNECT_ATTEMPTS) {
        setGaveUp(true)
        return
      }
      timer = window.setTimeout(() => {
        // Новий об'єкт запиту — новий GameClient; сесія з sessionStorage повертає того самого персонажа.
        setRequest((prev) => initialRequest() ?? (prev && { ...prev }))
      }, reconnectDelay(attempts.current++))
    }
    const off = client.subscribe(check)
    check()

    let hiddenAt = document.hidden ? performance.now() : null
    const onVisibility = () => {
      if (document.hidden) {
        hiddenAt = performance.now()
        return
      }
      const away = hiddenAt === null ? 0 : performance.now() - hiddenAt
      hiddenAt = null
      // Яйце в руках сервер притримує, доки повернемось, — тож і відкрита стаття з яйця не пропаде.
      if (away > RESYNC_AFTER_HIDDEN_MS && client.getStatus() === 'online') {
        client.close() // далі — звичайне автоперепідключення тим самим персонажем
      }
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      disposed = true
      off()
      document.removeEventListener('visibilitychange', onVisibility)
      window.clearTimeout(timer)
    }
  }, [client])

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
        gaveUp={gaveUp}
        onReconnect={() => {
          attempts.current = 0
          setGaveUp(false)
          setRequest(initialRequest() ?? { ...request })
        }}
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

function Hud({
  client,
  gaveUp,
  onReconnect,
  onLeave,
}: {
  client: GameClient
  gaveUp: boolean
  onReconnect: () => void
  onLeave: () => void
}) {
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
      <SoundToggle />
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
      {status !== 'online' && !gaveUp && <div className="reconnect-banner">Connecting to the server…</div>}
      {status !== 'online' && gaveUp && (
        <div className="overlay">
          <div className="join-card">
            <p>Connection to the server was lost</p>
            <button className="primary" onClick={onReconnect}>
              Reconnect
            </button>
            <button onClick={onLeave}>Leave</button>
          </div>
        </div>
      )}
    </>
  )
}

/** Зверху по центру: вимкнути для себе всі звуки гри (бали, пошта, ляпаси, рукостискання). */
function SoundToggle() {
  const on = useSyncExternalStore(onSoundsChange, soundsOn)
  return (
    <button
      className={`message-sound-toggle ${on ? '' : 'off'}`}
      title={on ? 'Mute all game sounds' : 'Turn game sounds back on'}
      onClick={(e) => {
        setSoundsOn(!on)
        e.currentTarget.blur()
      }}
    >
      {on ? '🔔' : '🔕'} <span className="label">Sounds {on ? 'on' : 'off'}</span>
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
