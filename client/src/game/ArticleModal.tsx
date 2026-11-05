import { useEffect, useSyncExternalStore } from 'react'
import type { GameClient } from './GameClient'
import { Markdown } from './Markdown'

/**
 * Стаття з яйця: прочитати й зберегти в колекцію або викинути (яйце ляже поруч, і його зможе підібрати
 * хтось інший). Закрити, нічого не вибравши, — теж викинути. Стаття з колекції — просто читати.
 */
export function ArticleModal({ client }: { client: GameClient }) {
  const open = useSyncExternalStore(client.subscribe, client.getArticle)

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') client.closeArticle()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [client, open])

  if (!open) return null
  const { article, egg, collected, points } = open
  return (
    <div className="modal-layer">
      <div className="modal article-modal" style={{ borderTopColor: article.color }}>
        <div className="modal-header">
          <div>
            <span className="topic-chip" style={{ background: article.color }}>
              🥚 {article.topic}
            </span>
            {points > 0 && <span className="points-chip">+{points} ⭐ for finding the egg</span>}
            <h2>{article.title}</h2>
          </div>
          <button className="modal-close" title={egg ? 'Throw away (Esc)' : 'Close (Esc)'} onClick={() => client.closeArticle()}>
            ✕
          </button>
        </div>
        <div className="article-body">
          <Markdown text={article.body} />
        </div>
        <div className="modal-actions">
          {egg ? (
            <>
              {collected ? (
                <span className="muted">Already in your collection</span>
              ) : (
                <button className="primary" onClick={() => client.keepEgg()}>
                  ⭐ Save to my collection
                </button>
              )}
              <button onClick={() => client.dropEgg()} title="The egg stays on the field for a while — someone else may pick it up">
                {collected ? 'Leave it for others' : 'Throw away'}
              </button>
            </>
          ) : (
            <button className="primary" onClick={() => client.closeArticle()}>
              Close
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
