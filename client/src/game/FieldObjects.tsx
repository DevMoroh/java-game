import { useFrame, type ThreeEvent } from '@react-three/fiber'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import * as THREE from 'three'
import type { GameClient } from './GameClient'
import { boardColor, hashOf } from './fieldConstants'
import type { BoardInfo, EggView } from './types'

/** Тривалість одного стрибка кролика; як Animals.HOP_SECONDS на сервері. */
const HOP_DURATION = 0.4
const SMOOTHING = 12

/** Клік по об'єкту — іти до нього (відкривається, коли підійдеш, кнопкою чи E). */
function walkTo(client: GameClient, x: number, z: number) {
  return (e: ThreeEvent<PointerEvent>) => {
    if (e.button !== 0) return
    e.stopPropagation()
    const me = client.local?.render
    if (!me) return
    // Зупиняємось трохи перед об'єктом, а не в ньому.
    const d = Math.hypot(x - me.x, z - me.z)
    const k = d > 1.2 ? (d - 1.2) / d : 0
    client.local?.setTarget(me.x + (x - me.x) * k, me.z + (z - me.z) * k)
  }
}

export function Mailboxes({ client }: { client: GameClient }) {
  const boards = useSyncExternalStore(client.subscribe, client.getBoards)
  const status = useSyncExternalStore(client.subscribe, client.getBoardStatus)
  return (
    <>
      {boards.map((b) => (
        <Mailbox
          key={b.id}
          board={b}
          color={boardColor(boards, b.id)}
          unread={status.joined.includes(b.id) ? (status.unread[b.id] ?? 0) : 0}
          client={client}
        />
      ))}
    </>
  )
}

/** Скільки летить конверт у щілину і трусяться скринька, коли прийшло нове. */
const DELIVERY_SECONDS = 1.4

/**
 * Поштова скринька на стовпчику з прапорцем. Є непрочитане (у скриньці, до якої я приєднався) — прапорець піднятий,
 * скринька м'яко світиться; щойно прийшло нове — згори в щілину залітає конверт, а скринька підстрибує.
 */
function Mailbox({ board, color, unread, client }: { board: BoardInfo; color: string; unread: number; client: GameClient }) {
  const box = useRef<THREE.Group>(null!)
  const flag = useRef<THREE.Group>(null!)
  const envelope = useRef<THREE.Group>(null!)
  const bodyMaterials = useRef<THREE.MeshStandardMaterial[]>([])
  const anim = useRef({ delivery: -1, time: 0 })
  const hasMail = unread > 0

  useEffect(
    () =>
      client.on('boardNotify', (e) => {
        if (e.board === board.id) anim.current.delivery = 0
      }),
    [client, board.id],
  )

  useFrame((_, dt) => {
    const a = anim.current
    a.time += dt
    // Прапорець: піднятий (вертикально), коли є пошта, опущений назад — коли немає.
    const flagTarget = hasMail ? 0 : -Math.PI / 2
    flag.current.rotation.x += (flagTarget - flag.current.rotation.x) * Math.min(1, dt * 8)
    // М'яке світіння кольором скриньки, поки є непрочитане.
    const glow = hasMail ? 0.25 + 0.2 * Math.sin(a.time * 3) : 0
    for (const m of bodyMaterials.current) m.emissiveIntensity += (glow - m.emissiveIntensity) * Math.min(1, dt * 6)

    const env = envelope.current
    if (a.delivery >= 0) {
      a.delivery += dt
      const t = a.delivery / DELIVERY_SECONDS
      // Конверт падає згори до щілини (перші 60%), далі «пірнає» в неї й зникає.
      const fall = Math.min(1, t / 0.6)
      env.visible = t < 1
      env.position.set(0, 1.3 + (1 - fall * fall) * 2.6, 0.6 - Math.max(0, t - 0.6) * 0.8)
      env.rotation.set(-0.3, Math.sin(t * 9) * 0.4 * (1 - fall), 0)
      env.scale.setScalar(1.8 * (t < 0.6 ? 1 : Math.max(0.01, 1 - (t - 0.6) / 0.4)))
      // Коли конверт влетів — скринька підстрибує й трусяться.
      const hit = Math.max(0, t - 0.55) / 0.45
      const shake = hit > 0 && hit < 1 ? Math.sin(hit * Math.PI * 6) * (1 - hit) : 0
      box.current.rotation.z = shake * 0.12
      box.current.position.y = 1.25 + (hit > 0 && hit < 1 ? Math.sin(hit * Math.PI) * 0.18 : 0)
      if (t >= 1) {
        a.delivery = -1
        box.current.rotation.z = 0
        box.current.position.y = 1.25
      }
    } else env.visible = false
  })

  const bodyMaterial = (i: number) => (m: THREE.MeshStandardMaterial | null) => {
    if (m) bodyMaterials.current[i] = m
  }

  return (
    <group position={[board.x, 0, board.z]} rotation-y={(board.x * 7 + board.z * 3) % Math.PI} onPointerDown={walkTo(client, board.x, board.z)}>
      <mesh rotation-x={-Math.PI / 2} position-y={0.02}>
        <circleGeometry args={[0.9, 32]} />
        <meshBasicMaterial color="#000" transparent opacity={0.15} depthWrite={false} />
      </mesh>
      <mesh position-y={0.55} castShadow>
        <boxGeometry args={[0.14, 1.1, 0.14]} />
        <meshStandardMaterial color="#7c4a21" />
      </mesh>
      <group ref={box} position-y={1.25}>
        <mesh castShadow>
          <boxGeometry args={[0.55, 0.38, 0.8]} />
          <meshStandardMaterial ref={bodyMaterial(0)} color={color} emissive={color} emissiveIntensity={0} />
        </mesh>
        {/* Заокруглений дах */}
        <mesh position-y={0.19} rotation-x={Math.PI / 2} castShadow>
          <cylinderGeometry args={[0.275, 0.275, 0.8, 16, 1, false, 0, Math.PI]} />
          <meshStandardMaterial ref={bodyMaterial(1)} color={color} emissive={color} emissiveIntensity={0} side={THREE.DoubleSide} />
        </mesh>
        {/* Щілина для листів */}
        <mesh position={[0, 0.05, 0.401]}>
          <boxGeometry args={[0.3, 0.05, 0.01]} />
          <meshStandardMaterial color="#111827" />
        </mesh>
        {/* Прапорець на шарнірі: піднятий — є пошта */}
        <group ref={flag} position={[0.3, 0, -0.1]} rotation-x={-Math.PI / 2}>
          <mesh position-y={0.25} castShadow>
            <boxGeometry args={[0.03, 0.5, 0.03]} />
            <meshStandardMaterial color="#e5e7eb" />
          </mesh>
          <mesh position={[0, 0.42, 0.12]}>
            <boxGeometry args={[0.02, 0.16, 0.22]} />
            <meshStandardMaterial color="#facc15" emissive="#facc15" emissiveIntensity={0.3} />
          </mesh>
        </group>
      </group>
      {/* Конверт, що залітає в щілину */}
      <group ref={envelope} visible={false}>
        <mesh castShadow>
          <boxGeometry args={[0.36, 0.24, 0.02]} />
          <meshStandardMaterial color="#ffffff" emissive="#ffffff" emissiveIntensity={0.3} />
        </mesh>
        <mesh position={[0, 0.03, 0.011]} rotation-z={Math.PI / 4} scale={[1, 1, 1]}>
          <planeGeometry args={[0.18, 0.18]} />
          <meshBasicMaterial color="#e5e7eb" />
        </mesh>
        <mesh position={[0.11, 0.06, 0.012]}>
          <planeGeometry args={[0.07, 0.07]} />
          <meshBasicMaterial color="#dc2626" />
        </mesh>
      </group>
    </group>
  )
}

export function Eggs({ client }: { client: GameClient }) {
  const eggs = useSyncExternalStore(client.subscribe, client.getEggs)
  return (
    <>
      {eggs.map((e) => (
        <Egg key={e.id} egg={e} client={client} />
      ))}
    </>
  )
}

const EGG_GEOMETRY = new THREE.SphereGeometry(0.3, 24, 16)

/** Золоте яйце: блищить своїм кольором, похитується; викинуте — блимає, бо скоро зникне. */
function Egg({ egg, client }: { egg: EggView; client: GameClient }) {
  const body = useRef<THREE.Mesh>(null!)
  const glow = useRef<THREE.MeshBasicMaterial>(null!)
  const phase = useRef(hashOf(egg.id) % 7) // різні яйця гойдаються не в такт

  useFrame((_, dt) => {
    phase.current += dt
    const t = phase.current
    body.current.position.y = 0.45 + Math.sin(t * 2) * 0.06
    body.current.rotation.y = t * 0.8
    body.current.rotation.z = Math.sin(t * 1.3) * 0.12
    glow.current.opacity = egg.dropped ? 0.15 + 0.25 * (0.5 + 0.5 * Math.sin(t * 6)) : 0.35 + 0.1 * Math.sin(t * 2)
  })

  return (
    <group position={[egg.x, 0, egg.z]} onPointerDown={walkTo(client, egg.x, egg.z)}>
      <mesh rotation-x={-Math.PI / 2} position-y={0.015}>
        <circleGeometry args={[0.7, 32]} />
        <meshBasicMaterial ref={glow} color={egg.color} transparent opacity={0.35} depthWrite={false} />
      </mesh>
      <mesh ref={body} geometry={EGG_GEOMETRY} scale={[1, 1.35, 1]} castShadow>
        <meshStandardMaterial
          color={egg.color}
          metalness={0.85}
          roughness={0.22}
          emissive={egg.color}
          emissiveIntensity={0.25}
        />
      </mesh>
    </group>
  )
}

/** Кролики: список id — з серверних позицій (вони приходять у кожному state). */
export function Rabbits({ client }: { client: GameClient }) {
  const [ids, setIds] = useState<string[]>([])
  useEffect(() => {
    const check = () => {
      const now = [...client.animals.keys()]
      setIds((prev) => (prev.length === now.length && prev.every((id, i) => id === now[i]) ? prev : now))
    }
    check()
    const timer = setInterval(check, 500)
    return () => clearInterval(timer)
  }, [client])
  return (
    <>
      {ids.map((id) => (
        <Rabbit key={id} id={id} client={client} />
      ))}
    </>
  )
}

const FURS = [
  { fur: '#a47148', belly: '#e8d5b9' },
  { fur: '#8d8d8d', belly: '#e5e5e5' },
  { fur: '#f5f5f4', belly: '#ffffff' },
  { fur: '#5c4033', belly: '#c9a98a' },
]

function furOf(id: string) {
  return FURS[hashOf(id) % FURS.length]
}

/** Кролик з примітивів. Дивиться в +Z (як гравці); стрибок — дуга з легким стисканням при приземленні. */
function Rabbit({ id, client }: { id: string; client: GameClient }) {
  const root = useRef<THREE.Group>(null!)
  const body = useRef<THREE.Group>(null!)
  const hop = useRef({ phase: 0, init: false })
  const { fur, belly } = furOf(id)

  useFrame((_, dt) => {
    const pos = client.animals.get(id)
    if (!pos) return
    const g = root.current
    const h = hop.current
    if (!h.init) {
      g.position.set(pos.x, 0, pos.z)
      g.rotation.y = pos.rotation
      h.init = true
    }
    const k = 1 - Math.exp(-SMOOTHING * dt)
    g.position.x += (pos.x - g.position.x) * k
    g.position.z += (pos.z - g.position.z) * k
    g.rotation.y += shortestAngle(g.rotation.y, pos.rotation) * k

    if (pos.hopping || h.phase % 1 > 0.02) h.phase += dt / HOP_DURATION
    if (!pos.hopping && h.phase % 1 < 0.08) h.phase = Math.round(h.phase) // доземлитися й сидіти
    const p = h.phase % 1
    const lift = Math.sin(p * Math.PI)
    body.current.position.y = lift * 0.22 // × масштаб 1.7
    body.current.rotation.x = (p - 0.5) * 0.5 * (lift > 0.01 ? 1 : 0)
    const squash = lift > 0.01 ? 1 + lift * 0.12 : 1
    body.current.scale.set(1 / Math.sqrt(squash), squash, 1 / Math.sqrt(squash))
  })

  return (
    // Кролик крупніший за «справжній», щоб його було видно з камери згори.
    <group ref={root} scale={1.7}>
      <mesh rotation-x={-Math.PI / 2} position-y={0.015}>
        <circleGeometry args={[0.2, 16]} />
        <meshBasicMaterial color="#000" transparent opacity={0.18} depthWrite={false} />
      </mesh>
      <group ref={body}>
        <mesh position={[0, 0.17, -0.02]} scale={[0.17, 0.16, 0.24]} castShadow>
          <sphereGeometry args={[1, 16, 12]} />
          <meshStandardMaterial color={fur} />
        </mesh>
        <mesh position={[0, 0.13, 0.08]} scale={[0.12, 0.1, 0.12]}>
          <sphereGeometry args={[1, 12, 8]} />
          <meshStandardMaterial color={belly} />
        </mesh>
        <group position={[0, 0.3, 0.17]}>
          <mesh scale={[0.11, 0.1, 0.12]} castShadow>
            <sphereGeometry args={[1, 16, 12]} />
            <meshStandardMaterial color={fur} />
          </mesh>
          {[-1, 1].map((side) => (
            <group key={side}>
              <mesh position={[side * 0.05, 0.17, -0.04]} rotation={[-0.35, 0, side * 0.22]} scale={[0.035, 0.15, 0.02]} castShadow>
                <sphereGeometry args={[1, 10, 8]} />
                <meshStandardMaterial color={fur} />
              </mesh>
              <mesh position={[side * 0.065, 0.02, 0.085]}>
                <sphereGeometry args={[0.016, 8, 6]} />
                <meshStandardMaterial color="#111" />
              </mesh>
            </group>
          ))}
          <mesh position={[0, -0.01, 0.12]}>
            <sphereGeometry args={[0.016, 8, 6]} />
            <meshStandardMaterial color="#f9a8d4" />
          </mesh>
        </group>
        <mesh position={[0, 0.2, -0.26]}>
          <sphereGeometry args={[0.06, 10, 8]} />
          <meshStandardMaterial color="#ffffff" />
        </mesh>
      </group>
    </group>
  )
}

function shortestAngle(from: number, to: number) {
  let d = (to - from) % (Math.PI * 2)
  if (d > Math.PI) d -= Math.PI * 2
  if (d < -Math.PI) d += Math.PI * 2
  return d
}
