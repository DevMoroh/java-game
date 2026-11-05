import { useEffect, useState, useSyncExternalStore, type FormEvent } from 'react'
import { boardColor } from './fieldConstants'
import type { GameClient } from './GameClient'
import type { BoardComment, BoardInfo, BoardPost, OpenBoard } from './types'

/** Як Boards.POST_MAX / COMMENT_MAX на сервері. */
const POST_MAX = 1000
const COMMENT_MAX = 500

/**
 * Відкрита скринька: опис, кнопка «приєднатися» (лічильник нового над скринькою на карті), новий пост
 * і список постів з кількістю коментарів. Клік по посту — розгорнути коментарі й написати свій. Esc — закрити.
 */
export function BoardPanel({ client }: { client: GameClient }) {
  const open = useSyncExternalStore(client.subscribe, client.getOpenBoard)
  const boards = useSyncExternalStore(client.subscribe, client.getBoards)
  const board = open && boards.find((b) => b.id === open.board)

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !(e.target instanceof HTMLTextAreaElement || e.target instanceof HTMLInputElement)) {
        client.closeBoard()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [client, open])

  if (!open || !board) return null
  return (
    <div className="modal-layer">
      <div className="modal board-panel" style={{ borderTopColor: boardColor(boards, board.id) }}>
        <div className="modal-header">
          <div>
            <h2>📮 {board.title}</h2>
            <p className="muted">{board.description}</p>
          </div>
          <button className="modal-close" title="Close (Esc)" onClick={() => client.closeBoard()}>
            ✕
          </button>
        </div>
        <JoinRow client={client} board={board} />
        <Composer
          key={board.id}
          placeholder={board.anonymous ? 'Write a review — it will be posted anonymously' : 'Write a post…'}
          max={POST_MAX}
          rows={3}
          button="Post"
          onSend={(text) => client.postToBoard(text)}
        />
        <div className="board-posts">
          {open.posts.length === 0 && <div className="muted board-empty">No posts yet — be the first.</div>}
          {open.posts.map((p) => (
            <PostCard key={p.id} client={client} board={board} open={open} post={p} />
          ))}
        </div>
      </div>
    </div>
  )
}

function JoinRow({ client, board }: { client: GameClient; board: BoardInfo }) {
  // Статус з сервера, а не з моменту відкриття: перший пост теж приєднує до скриньки.
  const status = useSyncExternalStore(client.subscribe, client.getBoardStatus)
  const muted = status.muted.includes(board.id)
  return (
    <div className="board-join">
      {status.joined.includes(board.id) ? (
        <>
          <span>{muted ? '🔕' : '🔔'} You joined this box — new posts and comments show up above it on the map.</span>
          <span className="board-join-actions">
            <button
              className={muted ? 'sound-toggle off' : 'sound-toggle'}
              title={muted ? 'Turn the new-message sound back on' : 'Mute the new-message sound for this box (only for you)'}
              onClick={(e) => {
                client.setBoardMuted(board.id, !muted)
                e.currentTarget.blur()
              }}
            >
              {muted ? '🔕 Muted' : '🔔 Sound on'}
            </button>
            <button className="link-button" onClick={() => client.setBoardJoined(board.id, false)}>
              Leave
            </button>
          </span>
        </>
      ) : (
        <>
          <span className="muted">Join to see the count of new messages above the box on the map.</span>
          <button className="primary" onClick={() => client.setBoardJoined(board.id, true)}>
            Join
          </button>
        </>
      )}
    </div>
  )
}

function PostCard({ client, board, open, post }: { client: GameClient; board: BoardInfo; open: OpenBoard; post: BoardPost }) {
  const expanded = post.id in open.threads
  const comments = open.threads[post.id]
  const isNew = open.lastRead !== null && post.lastActivity > open.lastRead

  return (
    <div className={expanded ? 'post expanded' : 'post'}>
      <button className="post-main" onClick={() => client.toggleThread(post.id)}>
        <div className="post-meta">
          <Author name={post.author} mine={post.mine} anonymous={board.anonymous} />
          <span className="muted">{formatWhen(post.at)}</span>
          {isNew && <span className="new-badge">NEW</span>}
        </div>
        <div className={expanded ? 'post-text' : 'post-text clamped'}>{post.text}</div>
        <div className="post-footer muted">
          💬 {post.comments} {post.comments === 1 ? 'comment' : 'comments'}
          <span className="post-toggle">{expanded ? 'Hide' : 'Open'}</span>
        </div>
      </button>
      {expanded && (
        <div className="comments">
          {comments === undefined && <div className="muted">Loading…</div>}
          {comments?.map((c) => (
            <Comment key={c.id} comment={c} board={board} isNew={open.lastRead !== null && c.id > open.lastRead} />
          ))}
          <Composer
            placeholder={board.anonymous ? 'Comment anonymously…' : 'Write a comment…'}
            max={COMMENT_MAX}
            rows={1}
            button="Reply"
            onSend={(text) => client.commentOnPost(post.id, text)}
          />
        </div>
      )}
    </div>
  )
}

function Comment({ comment, board, isNew }: { comment: BoardComment; board: BoardInfo; isNew: boolean }) {
  return (
    <div className="comment">
      <div className="post-meta">
        <Author name={comment.author} mine={comment.mine} anonymous={board.anonymous} />
        <span className="muted">{formatWhen(comment.at)}</span>
        {isNew && <span className="new-badge">NEW</span>}
      </div>
      <div className="post-text">{comment.text}</div>
    </div>
  )
}

function Author({ name, mine, anonymous }: { name: string | null; mine: boolean; anonymous: boolean }) {
  const label = name ?? (anonymous ? 'Anonymous' : 'Someone')
  return (
    <strong className={mine ? 'post-author mine' : 'post-author'}>
      {name === null && '🕶️ '}
      {label}
      {mine && ' (you)'}
    </strong>
  )
}

/** Поле вводу з кнопкою; Ctrl/Cmd+Enter — надіслати, Esc — прибрати фокус. */
function Composer({
  placeholder,
  max,
  rows,
  button,
  onSend,
}: {
  placeholder: string
  max: number
  rows: number
  button: string
  onSend: (text: string) => void
}) {
  const [text, setText] = useState('')
  const submit = (e?: FormEvent) => {
    e?.preventDefault()
    if (!text.trim()) return
    onSend(text)
    setText('')
  }
  return (
    <form className="composer" onSubmit={submit}>
      <textarea
        value={text}
        rows={rows}
        maxLength={max}
        placeholder={placeholder}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey || rows === 1) && !e.shiftKey) {
            e.preventDefault()
            submit()
          }
          if (e.key === 'Escape') e.currentTarget.blur()
        }}
      />
      <button type="submit" className="primary" disabled={!text.trim()}>
        {button}
      </button>
    </form>
  )
}

function formatWhen(at: number) {
  const d = new Date(at)
  const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  const today = new Date()
  if (d.toDateString() === today.toDateString()) return time
  return `${d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })} ${time}`
}
