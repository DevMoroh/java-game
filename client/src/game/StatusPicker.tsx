import { useState, type FormEvent } from 'react'
import type { GameClient } from './GameClient'

/** Готові статуси; свій можна написати вручну. */
const PRESETS = [
  '🎧 Listening to music',
  '😎 Chilling',
  '🎬 Watching a movie',
  '📚 Prepping for a tech interview',
  '💻 Coding',
  '🐞 Debugging',
  '☕ Coffee break',
  '🤝 Open to chat',
  '🔕 Do not disturb',
]
const MAX_STATUS = 40 // як GameWorld.MAX_STATUS на сервері

/** Вибір статусу: що я зараз роблю. Бачать усі — рядком під ім'ям над головою. */
export function StatusPicker({ client, status }: { client: GameClient; status: string }) {
  const [open, setOpen] = useState(false)
  const [custom, setCustom] = useState('')

  const choose = (text: string) => {
    client.setStatus(text)
    setOpen(false)
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (custom.trim()) choose(custom.trim())
    setCustom('')
  }

  return (
    <div className="status-picker">
      <button className="status-current" onClick={() => setOpen((o) => !o)} title="What are you up to?">
        {status || <span className="muted">+ Status</span>}
        <span className="caret">{open ? '▴' : '▾'}</span>
      </button>
      {open && (
        <div className="status-menu">
          {PRESETS.map((s) => (
            <button key={s} className={s === status ? 'active' : ''} onClick={() => choose(s)}>
              {s}
            </button>
          ))}
          <form onSubmit={submit}>
            <input
              value={custom}
              maxLength={MAX_STATUS}
              placeholder="Custom status…"
              onChange={(e) => setCustom(e.target.value)}
              onKeyDown={(e) => e.key === 'Escape' && setOpen(false)}
            />
          </form>
          {status && (
            <button className="clear" onClick={() => choose('')}>
              Clear status
            </button>
          )}
        </div>
      )}
    </div>
  )
}
