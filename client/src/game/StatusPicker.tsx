import { useState, type FormEvent } from 'react'
import type { GameClient } from './GameClient'

/** Готові статуси; свій можна написати вручну. */
const PRESETS = [
  '🎧 Слухаю музику',
  '😎 Чілю',
  '🎬 Дивлюсь фільм',
  '📚 Готуюсь до тех. співбесіди',
  '💻 Пишу код',
  '🐞 Дебажу',
  '☕ На каві',
  '🤝 Відкритий до спілкування',
  '🔕 Не турбувати',
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
      <button className="status-current" onClick={() => setOpen((o) => !o)} title="Що ти зараз робиш?">
        {status || <span className="muted">+ Статус</span>}
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
              placeholder="Свій статус…"
              onChange={(e) => setCustom(e.target.value)}
              onKeyDown={(e) => e.key === 'Escape' && setOpen(false)}
            />
          </form>
          {status && (
            <button className="clear" onClick={() => choose('')}>
              Без статусу
            </button>
          )}
        </div>
      )}
    </div>
  )
}
