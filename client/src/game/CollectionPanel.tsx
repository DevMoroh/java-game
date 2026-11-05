import { useState, useSyncExternalStore } from 'react'
import type { GameClient } from './GameClient'

/** Моя колекція золотих яєць: кнопка з лічильником і список — клік, щоб перечитати статтю. */
export function CollectionPanel({ client }: { client: GameClient }) {
  const collection = useSyncExternalStore(client.subscribe, client.getCollection)
  const [open, setOpen] = useState(false)

  return (
    <div className="panel collection">
      <button
        className="collection-toggle"
        onClick={(e) => {
          setOpen((o) => !o)
          e.currentTarget.blur()
        }}
      >
        🥚 Egg collection{' '}
        <span className="muted">
          {collection.items.length}
          {collection.total > 0 && ` / ${collection.total}`}
        </span>
        <span className="caret">{open ? '▴' : '▾'}</span>
      </button>
      {open && (
        <div className="collection-list">
          {collection.items.length === 0 && (
            <div className="muted">Find golden eggs on the field and save the ones you like.</div>
          )}
          {collection.items.map((item) => (
            <button key={item.articleId} className="collection-item" onClick={() => client.readArticle(item.articleId)}>
              <span className="egg-dot" style={{ background: item.color }} />
              <span className="collection-title">{item.title}</span>
              <span className="muted collection-topic">{item.topic}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
