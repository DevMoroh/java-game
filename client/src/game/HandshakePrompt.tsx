import { useEffect, useState } from 'react'
import type { GameClient } from './GameClient'

let promptSeq = 0

interface Prompt {
  /** Новий на кожну пропозицію — щоб смужка-таймер стартувала заново. */
  id: number
  text: string
  /** Тривалість очікування, мс — для смужки-таймера. */
  durationMs: number
}

/**
 * Підказка внизу екрана: «X простягає руку — натисни H» для того, кому простягнули,
 * і «Чекаєш, поки X потисне руку…» для того, хто простягнув.
 */
export function HandshakePrompt({ client }: { client: GameClient }) {
  const [prompt, setPrompt] = useState<Prompt | null>(null)

  useEffect(() => {
    const offs = [
      client.on('handshakeOffer', (e) => {
        let text: string | null = null
        if (e.to === client.selfId) text = `🤝 ${client.nameOf(e.from)} простягає руку — натисни H`
        else if (e.from === client.selfId) text = `Чекаєш, поки ${client.nameOf(e.to)} потисне руку…`
        if (!text) return
        const next = { id: ++promptSeq, text, durationMs: e.expiresInMs }
        setPrompt(next)
        setTimeout(() => setPrompt((p) => (p === next ? null : p)), e.expiresInMs)
      }),
      client.on('handshake', (e) => {
        if (e.from === client.selfId || e.to === client.selfId) setPrompt(null)
      }),
    ]
    return () => offs.forEach((off) => off())
  }, [client])

  if (!prompt) return null
  return (
    <div className="handshake-prompt">
      {prompt.text}
      <div key={prompt.id} className="handshake-timer" style={{ animationDuration: `${prompt.durationMs}ms` }} />
    </div>
  )
}
