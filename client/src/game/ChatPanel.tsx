import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type FormEvent } from 'react'
import type { GameClient } from './GameClient'

const MAX_LENGTH = 300 // як Chat.MAX_LENGTH на сервері

/**
 * Мій чат: загальний або груповий (з тими, до кого я приєднався). Enter — почати писати, Enter — надіслати,
 * Esc — назад у гру.
 * Поки фокус у полі, ігрові клавіші (WASD, пробіл, F, H) не спрацьовують — їх ігнорує useKeyboard.
 */
export function ChatPanel({ client }: { client: GameClient }) {
  const lines = useSyncExternalStore(client.subscribe, client.getChat)
  const group = useSyncExternalStore(client.subscribe, client.getChatGroup)
  useSyncExternalStore(client.subscribe, client.getPlayers)
  const self = client.getSelf()
  const mates = client.getGroupmates()
  const [text, setText] = useState('')
  const input = useRef<HTMLInputElement>(null)
  const list = useRef<HTMLDivElement>(null)
  const stickToBottom = useRef(true)

  // Enter у грі — перейти до чату.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.code === 'Enter' && !(e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement)) {
        e.preventDefault()
        input.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // Нові повідомлення — прокручуємо вниз, якщо користувач не гортає історію.
  useLayoutEffect(() => {
    const el = list.current
    if (el && stickToBottom.current) el.scrollTop = el.scrollHeight
  }, [lines])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    client.sendChat(text)
    setText('')
    stickToBottom.current = true
  }

  return (
    <div className="chat">
      <div className="chat-header">
        {group ? (
          <>
            <span className="color-dot" style={{ background: self?.color }} />
            Chat with {mates.length > 0 ? mates.map((m) => m.name).join(', ') : '…'}
            <button
              className="chat-leave"
              title="Back to the general chat with your own color"
              onClick={(e) => {
                client.leaveGroup()
                e.currentTarget.blur() // щоб пробіл потім не «натискав» кнопку
              }}
            >
              Leave chat
            </button>
          </>
        ) : (
          <>💬 General chat</>
        )}
      </div>
      <div
        className="chat-lines"
        ref={list}
        onScroll={(e) => {
          const el = e.currentTarget
          stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 24
        }}
      >
        {lines.length === 0 && <div className="chat-system">Quiet here so far — say hi first 👋</div>}
        {lines.map((line) =>
          line.kind === 'system' ? (
            <div key={line.key} className={line.error ? 'chat-system error' : 'chat-system'}>
              {line.text}
            </div>
          ) : (
            <div key={line.key} className="chat-line">
              <span className="chat-time">{formatTime(line.at)}</span>
              <span className={line.from === client.selfId ? 'chat-name self' : 'chat-name'}>{line.name}:</span>{' '}
              <span className="chat-text">{line.text}</span>
            </div>
          ),
        )}
      </div>
      <form onSubmit={submit}>
        <input
          ref={input}
          value={text}
          maxLength={MAX_LENGTH}
          placeholder={group ? 'Enter — message the group' : 'Enter — message everyone'}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') e.currentTarget.blur()
          }}
        />
      </form>
    </div>
  )
}

function formatTime(at: number) {
  const d = new Date(at)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
