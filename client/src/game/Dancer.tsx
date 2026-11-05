import { useGLTF } from '@react-three/drei'
import { useFrame } from '@react-three/fiber'
import { useEffect, useMemo, useRef, useState } from 'react'
import * as THREE from 'three'
import { clone as cloneSkinned } from 'three/examples/jsm/utils/SkeletonUtils.js'
import type { GameClient } from './GameClient'
import type { NpcPos } from './types'

const MODEL_URL = '/models/girl.glb'
/** Зріст на полі (у модельних одиницях Meshy вона крихітна — масштабуємо до цього). */
const HEIGHT = 2.1
const SMOOTHING = 10
const FADE = 0.35
/** Кліпи з girl.glb (Meshy + Mixamo) для кожної дії; швидкості ходи й бігу — у game/Dancer.java. */
const CLIPS: Record<NpcPos['action'], string> = {
  walk: 'Walking',
  run: 'Running',
  dance: 'Bass_Beats',
}

useGLTF.preload(MODEL_URL)

/** Танцівниця — NPC, якого рухає сервер (game/Dancer.java): ходить, бігає, танцює. */
export function Dancer({ client, id }: { client: GameClient; id: string }) {
  const gltf = useGLTF(MODEL_URL)
  const root = useRef<THREE.Group>(null!)
  const placed = useRef(false)

  const { model, mixer, actions } = useMemo(() => {
    const model = cloneSkinned(gltf.scene)
    model.traverse((o) => {
      if ((o as THREE.Mesh).isMesh) {
        o.castShadow = true
        o.frustumCulled = false // межі скінованої сітки рахуються в позі прив'язки — інакше зникає з краю кадру
      }
    })
    // Масштаб Meshy сидить у кістках, а не в сітці: міряємо зріст по вершинах уже в позі скелета (precise).
    model.updateMatrixWorld(true)
    const size = new THREE.Box3().setFromObject(model, true).getSize(new THREE.Vector3())
    model.scale.multiplyScalar(HEIGHT / Math.max(size.y, 1e-6))
    const mixer = new THREE.AnimationMixer(model)
    const actions = {} as Record<NpcPos['action'], THREE.AnimationAction>
    for (const [action, name] of Object.entries(CLIPS) as [NpcPos['action'], string][]) {
      const clip = gltf.animations.find((c) => c.name === name)
      if (clip) actions[action] = mixer.clipAction(clip)
    }
    return { model, mixer, actions }
  }, [gltf])

  const current = useRef<NpcPos['action'] | null>(null)
  useEffect(() => () => void mixer.stopAllAction(), [mixer])

  useFrame((_, dt) => {
    const pos = client.npcs.get(id)
    if (!pos) return
    const g = root.current
    if (!placed.current) {
      g.position.set(pos.x, 0, pos.z)
      g.rotation.y = pos.rotation
      placed.current = true
    }
    const k = 1 - Math.exp(-SMOOTHING * dt)
    g.position.x += (pos.x - g.position.x) * k
    g.position.z += (pos.z - g.position.z) * k
    g.rotation.y += shortestAngle(g.rotation.y, pos.rotation) * k

    if (current.current !== pos.action) {
      const next = actions[pos.action]
      const prev = current.current && actions[current.current]
      if (next) {
        next.reset().play()
        if (prev) prev.crossFadeTo(next, FADE, false)
        else next.fadeIn(FADE)
      }
      current.current = pos.action
    }
    mixer.update(dt)
  })

  return (
    <group ref={root}>
      <primitive object={model} />
    </group>
  )
}

/** Усі NPC на полі (поки що — одна танцівниця). */
export function Npcs({ client }: { client: GameClient }) {
  const [ids, setIds] = useState<string[]>([])
  useEffect(() => {
    const check = () => {
      const now = [...client.npcs.keys()]
      setIds((prev) => (prev.length === now.length && prev.every((id, i) => id === now[i]) ? prev : now))
    }
    check()
    const timer = setInterval(check, 500)
    return () => clearInterval(timer)
  }, [client])
  return (
    <>
      {ids.map((id) => (
        <Dancer key={id} id={id} client={client} />
      ))}
    </>
  )
}

function shortestAngle(from: number, to: number) {
  let d = (to - from) % (Math.PI * 2)
  if (d > Math.PI) d -= Math.PI * 2
  if (d < -Math.PI) d += Math.PI * 2
  return d
}
