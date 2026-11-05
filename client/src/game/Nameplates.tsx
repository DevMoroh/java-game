import { useFrame, useThree } from '@react-three/fiber'
import { useEffect, useState } from 'react'
import * as THREE from 'three'
import { anchors } from './anchors'
import type { GameClient } from './GameClient'
import type { PlayerInfo } from './types'
import { worldLabels } from './worldLabels'

const LABEL_HEIGHT = 2.3

/** id гравця → DOM-підпис. */
const labels = new Map<string, HTMLDivElement>()

/** Скільки висить спливаючий напис (має збігатися з анімацією .pop у CSS). */
const POP_MS = 900
/** Скільки висить «+1 ⭐» за очки (як анімація .score-pop у CSS). */
const SCORE_POP_MS = 1800
/** Зірочки, що розлітаються навколо «+1»: кут і відстань — у CSS-змінних. */
const SPARKS = Array.from({ length: 10 }, (_, i) => ({ angle: (i / 10) * 360 + (i % 2) * 18, dist: i % 2 ? 60 : 90 }))
/** Скільки висить бульбашка з повідомленням чату над головою. */
const BUBBLE_MS = 5000
const BUBBLE_MAX = 80

interface Pop {
  at: number
  text: string
  kind: 'hit' | 'dodge' | 'shake' | 'ally'
}

/**
 * DOM-шар з підписами поверх canvas: ім'я з очками й статусом, значок 🤝 у того, хто простягнув руку,
 * і спливаючі написи («ЛЯЩ! −1», «Ухилився!», «🤝 +1», «Свої!»). Позиції виставляє {@link NameplateProjector}.
 */
export function Nameplates({ players, client }: { players: PlayerInfo[]; client: GameClient }) {
  // id гравця → останній напис; at — він же key, щоб CSS-анімація стартувала знову.
  const [pops, setPops] = useState<Record<string, Pop>>({})
  // id того, хто простягнув руку → коли рука опуститься (Date.now()).
  const [offers, setOffers] = useState<Record<string, number>>({})
  // id гравця → останнє «+N ⭐» (окремо від pops, щоб не перекривати «🤝»).
  const [scorePops, setScorePops] = useState<Record<string, { at: number; delta: number }>>({})
  // id гравця → останнє повідомлення чату (бульбашка над головою).
  const [bubbles, setBubbles] = useState<Record<string, { at: number; text: string }>>({})

  useEffect(() => {
    const showPop = (id: string, text: string, kind: Pop['kind']) => {
      const pop: Pop = { at: Date.now(), text, kind }
      setPops((p) => ({ ...p, [id]: pop }))
      setTimeout(() => setPops((p) => (p[id] === pop ? withoutKey(p, id) : p)), POP_MS)
    }
    const offs = [
      client.on('slapImpact', (e) => {
        if (e.dodged) showPop(e.to, 'Dodged!', 'dodge')
        else showPop(e.to, e.penalty > 0 ? `SLAP! −${e.penalty}` : 'SLAP!', 'hit')
      }),
      client.on('slap', (e) => {
        if (!e.to && e.ally) showPop(e.ally, 'Friend!', 'ally')
      }),
      client.on('chat', (e) => {
        const bubble = { at: Date.now(), text: e.text.length > BUBBLE_MAX ? e.text.slice(0, BUBBLE_MAX - 1) + '…' : e.text }
        setBubbles((b) => ({ ...b, [e.from]: bubble }))
        setTimeout(() => setBubbles((b) => (b[e.from] === bubble ? withoutKey(b, e.from) : b)), BUBBLE_MS)
      }),
      client.on('handshakeOffer', (e) => {
        const until = Date.now() + e.expiresInMs
        setOffers((o) => ({ ...o, [e.from]: until }))
        setTimeout(() => setOffers((o) => (o[e.from] === until ? withoutKey(o, e.from) : o)), e.expiresInMs)
      }),
      client.on('handshake', (e) => {
        setOffers((o) => withoutKey(withoutKey(o, e.from), e.to))
        // «+1» за рукостискання покаже scoreGain.
        showPop(e.from, '🤝', 'shake')
        showPop(e.to, '🤝', 'shake')
      }),
      client.on('scoreGain', (e) => {
        const pop = { at: Date.now() + Math.random(), delta: e.delta }
        setScorePops((p) => ({ ...p, [e.id]: pop }))
        setTimeout(() => setScorePops((p) => (p[e.id] === pop ? withoutKey(p, e.id) : p)), SCORE_POP_MS)
      }),
    ]
    return () => offs.forEach((off) => off())
  }, [client])

  return (
    <div className="nameplates">
      {players.map((p) => {
        const pop = pops[p.id]
        const scorePop = scorePops[p.id]
        const bubble = bubbles[p.id]
        return (
          <div
            key={p.id}
            className="label"
            ref={(el) => {
              if (el) labels.set(p.id, el)
              else labels.delete(p.id)
            }}
          >
            {pop && (
              <div key={pop.at} className={`pop ${pop.kind}`}>
                {pop.text}
              </div>
            )}
            {scorePop && (
              <div key={scorePop.at} className={p.id === client.selfId ? 'score-pop self' : 'score-pop'}>
                {SPARKS.map((s, i) => (
                  <span
                    key={i}
                    className="spark"
                    style={{ '--angle': `${s.angle}deg`, '--dist': `${s.dist}px` } as React.CSSProperties}
                  >
                    ★
                  </span>
                ))}
                <span className="score-pop-text">+{scorePop.delta} ⭐</span>
              </div>
            )}
            {bubble && (
              <div key={bubble.at} className="bubble">
                {bubble.text}
              </div>
            )}
            <div className={p.id === client.selfId ? 'nameplate self' : 'nameplate'}>
              {offers[p.id] && <span className="offer-badge">🤝 </span>}
              {p.name}
              <span className="score">⭐ {p.score}</span>
            </div>
            {p.status && <div className="status-line">{p.status}</div>}
          </div>
        )
      })}
    </div>
  )
}

function withoutKey<T>(record: Record<string, T>, key: string) {
  const copy = { ...record }
  delete copy[key]
  return copy
}

const v = new THREE.Vector3()

/** Всередині Canvas: щокадру проєктує голови персонажів на екран і рухає підписи. */
export function NameplateProjector() {
  const { camera, size } = useThree()
  const place = (el: HTMLElement, anchor: THREE.Object3D | undefined, lift: number, anchorY: string) => {
    if (!anchor) {
      el.style.display = 'none'
      return
    }
    anchor.getWorldPosition(v)
    project(el, v.setY(v.y + lift), anchorY)
  }
  const project = (el: HTMLElement, point: THREE.Vector3, anchorY: string) => {
    v.copy(point).project(camera)
    if (v.z > 1) {
      el.style.display = 'none'
      return
    }
    const x = ((v.x + 1) / 2) * size.width
    const y = ((1 - v.y) / 2) * size.height
    el.style.display = ''
    el.style.transform = `translate(-50%, ${anchorY}) translate(${x.toFixed(1)}px, ${y.toFixed(1)}px)`
  }
  useFrame(() => {
    for (const [id, el] of labels) place(el, anchors.get(id), LABEL_HEIGHT, '-100%')
    for (const { el, at } of worldLabels.values()) project(el, at, '-100%')
  })
  return null
}
