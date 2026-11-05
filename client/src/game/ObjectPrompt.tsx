import { useEffect, useState, useSyncExternalStore } from 'react'
import { BOARD_RANGE, EGG_RANGE } from './fieldConstants'
import type { GameClient } from './GameClient'

const CHECK_MS = 200

type Nearby = { kind: 'board'; id: string; title: string } | { kind: 'egg'; id: string; color: string }

/**
 * «📮 Company reviews — Open (E)» / «🥚 Golden egg — Open (E)», коли стоїш біля скриньки чи яйця.
 * Яйце важливіше: воно поруч ненадовго. Поки відкрита скринька чи стаття — не показуємо.
 */
export function ObjectPrompt({ client }: { client: GameClient }) {
  const boards = useSyncExternalStore(client.subscribe, client.getBoards)
  const eggs = useSyncExternalStore(client.subscribe, client.getEggs)
  const openBoard = useSyncExternalStore(client.subscribe, client.getOpenBoard)
  const article = useSyncExternalStore(client.subscribe, client.getArticle)
  const [nearby, setNearby] = useState<Nearby | null>(null)
  const busy = openBoard !== null || article !== null

  useEffect(() => {
    const check = () => {
      const me = client.local?.render
      if (!me) return setNearby(null)
      const egg = eggs
        .map((e) => ({ e, d: Math.hypot(e.x - me.x, e.z - me.z) }))
        .filter((x) => x.d <= EGG_RANGE)
        .sort((a, b) => a.d - b.d)[0]?.e
      if (egg) return setNearby((n) => (n?.kind === 'egg' && n.id === egg.id ? n : { kind: 'egg', id: egg.id, color: egg.color }))
      const board = boards
        .map((b) => ({ b, d: Math.hypot(b.x - me.x, b.z - me.z) }))
        .filter((x) => x.d <= BOARD_RANGE)
        .sort((a, b) => a.d - b.d)[0]?.b
      setNearby((n) =>
        board ? (n?.kind === 'board' && n.id === board.id ? n : { kind: 'board', id: board.id, title: board.title }) : null,
      )
    }
    check()
    const timer = setInterval(check, CHECK_MS)
    return () => clearInterval(timer)
  }, [client, boards, eggs])

  const open = () => {
    if (!nearby) return
    if (nearby.kind === 'egg') client.openEgg(nearby.id)
    else client.openBoardById(nearby.id)
  }

  useEffect(() => {
    if (!nearby || busy) return
    const onKey = (e: KeyboardEvent) => {
      if (e.code !== 'KeyE' || e.repeat || e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
      open()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  if (!nearby || busy) return null
  return (
    <div className="team-card">
      {nearby.kind === 'egg' ? (
        <>
          <span className="egg-dot" style={{ background: nearby.color }} />
          <span>A golden egg — new knowledge inside</span>
        </>
      ) : (
        <span>📮 {nearby.title}</span>
      )}
      <button
        className="primary"
        onClick={(e) => {
          open()
          e.currentTarget.blur()
        }}
      >
        Open <kbd>E</kbd>
      </button>
    </div>
  )
}
