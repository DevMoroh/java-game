import { useFrame } from '@react-three/fiber'
import { useEffect, useRef } from 'react'
import * as THREE from 'three'
import { anchors } from './anchors'
import { useAvatar } from './avatar/useAvatar'
import type { GameClient } from './GameClient'
import type { PlayerInfo } from './types'

const SMOOTHING = 12 // чим більше, тим швидше рендер догоняє серверну позицію інших гравців
/** Радіус кола під гравцем; як Groups.CIRCLE_RADIUS на сервері. */
export const CIRCLE_RADIUS = 1.6
/** Скільки розходиться золоте кільце за отримані очки. */
const BURST_SECONDS = 0.9

interface Props {
  info: PlayerInfo
  client: GameClient
  isSelf: boolean
}

/** Гравець на полі: 3D-аватар (avatar/), позиція, поворот і анімація руху. Модель дивиться в +Z. */
export function Character({ info, client, isSelf }: Props) {
  const root = useRef<THREE.Group>(null!)
  const init = useRef(false)
  const avatar = useAvatar(info.gender, info.appearance, client.rules.speed)
  // Золоте кільце, що розходиться по траві, коли гравець отримав очки (0 — немає).
  const burst = useRef<THREE.Mesh>(null!)
  const burstAge = useRef(-1)

  // Підпис прив'язаний до моделі, а не до кореня, — щоб летів разом зі стрибком.
  useEffect(() => {
    anchors.set(info.id, avatar.object)
    return () => {
      anchors.delete(info.id)
    }
  }, [info.id, avatar.object])

  // Події: стрибок; лящ (замах одразу, реакція в момент удару, якщо не ухилився); рукостискання.
  useEffect(() => {
    const { animator } = avatar
    const offs = [
      client.on('jump', (e) => {
        if (e.id === info.id) animator.jump()
      }),
      client.on('slap', (e) => {
        if (e.from === info.id) animator.play('Melee_Hook')
      }),
      client.on('slapImpact', (e) => {
        if (e.to === info.id && !e.dodged) animator.play('Hit_Head')
      }),
      client.on('scoreGain', (e) => {
        if (e.id === info.id) burstAge.current = 0
      }),
      client.on('handshake', (e) => {
        const partner = e.from === info.id ? e.to : e.to === info.id ? e.from : null
        if (partner) animator.shakeHands(() => anchors.get(partner))
      }),
    ]
    return () => offs.forEach((off) => off())
  }, [client, info.id, avatar])

  useFrame((_, dt) => {
    // Свого гравця малюємо з передбачення (без затримки), інших — з серверних позицій.
    const pos = isSelf ? client.local?.render : client.positions.get(info.id)
    if (!pos) return
    const g = root.current
    if (!init.current) {
      g.position.set(pos.x, 0, pos.z)
      g.rotation.y = pos.rotation
      init.current = true
    }
    const k = 1 - Math.exp(-SMOOTHING * dt)
    if (isSelf) {
      g.position.set(pos.x, 0, pos.z) // вже згладжено в LocalPlayer
    } else {
      g.position.x += (pos.x - g.position.x) * k
      g.position.z += (pos.z - g.position.z) * k
    }
    g.rotation.y += shortestAngle(g.rotation.y, pos.rotation) * k
    avatar.animator.update(dt, pos.moving)

    const b = burst.current
    if (burstAge.current >= 0) {
      burstAge.current += dt
      const t = burstAge.current / BURST_SECONDS
      if (t >= 1) burstAge.current = -1
      b.visible = t < 1
      b.scale.setScalar(0.6 + Math.sqrt(t) * 3.4) // швидко вилітає, далі сповільнюється — до ~3.5 м
      ;(b.material as THREE.MeshBasicMaterial).opacity = 1 - t * t
    } else b.visible = false
  })

  return (
    <group ref={root}>
      {/* М'яка пляма під ногами, щоб персонажа було видно на траві навіть у тіні. */}
      <mesh rotation-x={-Math.PI / 2} position-y={0.02}>
        <circleGeometry args={[0.4, 24]} />
        <meshBasicMaterial color="#000" transparent opacity={0.18} depthWrite={false} />
      </mesh>
      {/* Коло ходить разом з гравцем: його колір — власний або колір групового чату; хто зайде в коло,
          може приєднатися до чату цього гравця. */}
      <mesh rotation-x={-Math.PI / 2} position-y={0.012} renderOrder={1}>
        <circleGeometry args={[CIRCLE_RADIUS, 48]} />
        <meshBasicMaterial color={info.color} transparent opacity={0.22} depthWrite={false} />
      </mesh>
      <mesh rotation-x={-Math.PI / 2} position-y={0.016} renderOrder={1}>
        <ringGeometry args={[CIRCLE_RADIUS - 0.08, CIRCLE_RADIUS, 64]} />
        <meshBasicMaterial color={info.color} transparent opacity={0.9} depthWrite={false} />
      </mesh>
      {isSelf && (
        // Позначка «це я» — біла, щоб не плутати з кольором кола.
        <mesh rotation-x={-Math.PI / 2} position-y={0.03}>
          <ringGeometry args={[0.55, 0.6, 48]} />
          <meshBasicMaterial color="#ffffff" transparent opacity={0.85} depthWrite={false} />
        </mesh>
      )}
      <mesh ref={burst} rotation-x={-Math.PI / 2} position-y={0.05} visible={false} renderOrder={2}>
        <ringGeometry args={[0.7, 1.05, 64]} />
        <meshBasicMaterial color="#ffc21a" transparent opacity={0} depthWrite={false} blending={THREE.AdditiveBlending} />
      </mesh>
      <primitive object={avatar.object} />
    </group>
  )
}

function shortestAngle(from: number, to: number) {
  let d = (to - from) % (Math.PI * 2)
  if (d > Math.PI) d -= Math.PI * 2
  if (d < -Math.PI) d += Math.PI * 2
  return d
}
