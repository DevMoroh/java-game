import { Canvas, useFrame, useThree, type ThreeEvent } from '@react-three/fiber'
import { Suspense, useEffect, useMemo, useRef, useSyncExternalStore } from 'react'
import * as THREE from 'three'
import { preloadAvatarAssets } from './avatar/useAvatar'
import { BoardLabels } from './BoardLabels'
import { Character } from './Character'
import { Eggs, Mailboxes, Rabbits } from './FieldObjects'
import { Npcs } from './Dancer'
import type { GameClient } from './GameClient'
import { NameplateProjector, Nameplates } from './Nameplates'
import { subscribeSounds } from './effects'
import { createGrassTexture, seededRandom } from './textures'
import type { BoardInfo } from './types'

preloadAvatarAssets()

// Камера «як у стратегії»: дивиться згори під кутом ~50°, без обертання.
const CAMERA_OFFSET = new THREE.Vector3(0, 16, 13)
const MIN_ZOOM = 0.5
const MAX_ZOOM = 2.2

export function Game({ client }: { client: GameClient }) {
  const players = useSyncExternalStore(client.subscribe, client.getPlayers)
  const boards = useSyncExternalStore(client.subscribe, client.getBoards)
  useKeyboard(client)
  useEffect(() => subscribeSounds(client), [client])

  return (
    <>
      <Canvas shadows camera={{ fov: 45, near: 0.5, far: 300 }} dpr={[1, 2]}>
        <color attach="background" args={['#a8d8f0']} />
        <fog attach="fog" args={['#a8d8f0', 40, 90]} />
        <hemisphereLight args={['#dff3ff', '#3d5c2a', 0.9]} />
        <LocalPlayerDriver client={client} />
        <CameraRig client={client} />
        <Ground client={client} />
        <Decorations size={client.worldSize} boards={boards} />
        <Mailboxes client={client} />
        <Eggs client={client} />
        <Rabbits client={client} />
        <Suspense fallback={null}>
          <Npcs client={client} />
        </Suspense>
        {players.map((p) => (
          // Окремий Suspense на кожного: поки вантажаться моделі, решта сцени вже малюється.
          <Suspense key={p.id} fallback={null}>
            <Character info={p} client={client} isSelf={p.id === client.selfId} />
          </Suspense>
        ))}
        <NameplateProjector />
      </Canvas>
      <Nameplates players={players} client={client} />
      <BoardLabels client={client} />
    </>
  )
}

/** Крутить передбачення власного руху. Стоїть першим у сцені, щоб інші useFrame бачили свіжу позицію. */
function LocalPlayerDriver({ client }: { client: GameClient }) {
  useFrame((_, dt) => client.local?.update(dt, client.keys))
  return null
}

/** Камера й сонце плавно слідують за власним персонажем; колесо миші — зум. */
function CameraRig({ client }: { client: GameClient }) {
  const { camera, gl } = useThree()
  const light = useRef<THREE.DirectionalLight>(null!)
  const focus = useRef(new THREE.Vector3())
  const zoom = useRef(1)
  const ready = useRef(false)

  useEffect(() => {
    const el = gl.domElement
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      zoom.current = THREE.MathUtils.clamp(zoom.current * (1 + e.deltaY * 0.001), MIN_ZOOM, MAX_ZOOM)
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [gl])

  useFrame((_, dt) => {
    const self = client.local?.render
    if (self) {
      const k = ready.current ? 1 - Math.exp(-5 * dt) : 1
      focus.current.x += (self.x - focus.current.x) * k
      focus.current.z += (self.z - focus.current.z) * k
      ready.current = true
    }
    camera.position.copy(focus.current).addScaledVector(CAMERA_OFFSET, zoom.current)
    camera.lookAt(focus.current)

    // Тіні рахуються лише навколо гравця — так вони чіткі на великому полі.
    light.current.position.set(focus.current.x + 15, 30, focus.current.z + 10)
    light.current.target.position.copy(focus.current)
    light.current.target.updateMatrixWorld()
  })

  return (
    <directionalLight
      ref={light}
      intensity={2.2}
      castShadow
      shadow-mapSize={[2048, 2048]}
      shadow-camera-left={-30}
      shadow-camera-right={30}
      shadow-camera-top={30}
      shadow-camera-bottom={-30}
      shadow-camera-far={80}
      shadow-bias={-0.0005}
    />
  )
}

function Ground({ client }: { client: GameClient }) {
  const size = client.worldSize
  const texture = useMemo(() => {
    const t = createGrassTexture()
    t.repeat.set(size / 6, size / 6)
    return t
  }, [size])
  const marker = useRef<THREE.Mesh>(null!)
  const markerLife = useRef(0)

  useFrame((_, dt) => {
    markerLife.current = Math.max(0, markerLife.current - dt)
    const m = marker.current
    m.visible = markerLife.current > 0
    const mat = m.material as THREE.MeshBasicMaterial
    mat.opacity = markerLife.current
    m.scale.setScalar(0.6 + (1 - markerLife.current) * 0.6)
  })

  const onPointerDown = (e: ThreeEvent<PointerEvent>) => {
    if (e.button !== 0) return
    e.stopPropagation()
    client.local?.setTarget(e.point.x, e.point.z)
    marker.current.position.set(e.point.x, 0.04, e.point.z)
    markerLife.current = 1
  }

  return (
    <>
      <mesh rotation-x={-Math.PI / 2} receiveShadow onPointerDown={onPointerDown}>
        <planeGeometry args={[size, size]} />
        <meshStandardMaterial map={texture} />
      </mesh>
      {/* Земля за межами поля, щоб не було «краю світу» */}
      <mesh rotation-x={-Math.PI / 2} position-y={-0.05}>
        <planeGeometry args={[size * 4, size * 4]} />
        <meshStandardMaterial color="#5b7a3a" />
      </mesh>
      <mesh ref={marker} rotation-x={-Math.PI / 2} visible={false}>
        <ringGeometry args={[0.35, 0.5, 32]} />
        <meshBasicMaterial color="#ffffff" transparent depthWrite={false} />
      </mesh>
    </>
  )
}

/** Дерева, каміння й квіти. Сід фіксований — у всіх гравців однакова карта. Біля скриньок — вільно. */
function Decorations({ size, boards }: { size: number; boards: BoardInfo[] }) {
  const items = useMemo(() => {
    const rnd = seededRandom(42)
    const half = size / 2 - 2
    const trees: { x: number; z: number; s: number }[] = []
    const rocks: { x: number; z: number; s: number; r: number }[] = []
    const flowers: { x: number; z: number; c: string }[] = []
    const nearBoard = (x: number, z: number) => boards.some((b) => Math.hypot(x - b.x, z - b.z) < 3)
    const away = (x: number, z: number) => Math.hypot(x, z) > 14 // порожня зона спавну
    while (trees.length < 45) {
      const x = (rnd() * 2 - 1) * half
      const z = (rnd() * 2 - 1) * half
      if (away(x, z)) trees.push({ x, z, s: 0.8 + rnd() * 0.7 })
    }
    while (rocks.length < 30) {
      const x = (rnd() * 2 - 1) * half
      const z = (rnd() * 2 - 1) * half
      if (away(x, z)) rocks.push({ x, z, s: 0.3 + rnd() * 0.6, r: rnd() * Math.PI })
    }
    const colors = ['#ffffff', '#fde047', '#f472b6', '#c084fc', '#f87171']
    for (let i = 0; i < 300; i++) {
      flowers.push({
        x: (rnd() * 2 - 1) * half,
        z: (rnd() * 2 - 1) * half,
        c: colors[(rnd() * colors.length) | 0],
      })
    }
    // Відкидаємо вже після генерації: решта карти лишається такою ж, як без скриньок.
    return {
      trees: trees.filter((t) => !nearBoard(t.x, t.z)),
      rocks: rocks.filter((r) => !nearBoard(r.x, r.z)),
      flowers,
    }
  }, [size, boards])

  return (
    <group>
      {items.trees.map((t, i) => (
        <group key={i} position={[t.x, 0, t.z]} scale={t.s}>
          <mesh position-y={1} castShadow>
            <cylinderGeometry args={[0.2, 0.3, 2, 8]} />
            <meshStandardMaterial color="#6b4423" />
          </mesh>
          <mesh position-y={2.8} castShadow>
            <coneGeometry args={[1.4, 2.6, 8]} />
            <meshStandardMaterial color="#2f6b2a" flatShading />
          </mesh>
          <mesh position-y={3.9} castShadow>
            <coneGeometry args={[1, 1.8, 8]} />
            <meshStandardMaterial color="#3a7d33" flatShading />
          </mesh>
        </group>
      ))}
      {items.rocks.map((r, i) => (
        <mesh key={i} position={[r.x, r.s * 0.3, r.z]} rotation-y={r.r} scale={r.s} castShadow>
          <dodecahedronGeometry args={[0.7, 0]} />
          <meshStandardMaterial color="#8a8f94" flatShading />
        </mesh>
      ))}
      {items.flowers.map((f, i) => (
        <mesh key={i} position={[f.x, 0.06, f.z]}>
          <sphereGeometry args={[0.07, 6, 4]} />
          <meshStandardMaterial color={f.c} />
        </mesh>
      ))}
    </group>
  )
}

/** WASD / стрілки → client.keys (сам рух робить LocalPlayer); F — лящ; H — рукостискання; пробіл — стрибок. Камера не обертається, тож W завжди «вгору» по екрану (−Z). */
function useKeyboard(client: GameClient) {
  useEffect(() => {
    const pressed = new Set<string>()
    const update = () => {
      let dx = 0
      let dz = 0
      if (pressed.has('KeyW') || pressed.has('ArrowUp')) dz -= 1
      if (pressed.has('KeyS') || pressed.has('ArrowDown')) dz += 1
      if (pressed.has('KeyA') || pressed.has('ArrowLeft')) dx -= 1
      if (pressed.has('KeyD') || pressed.has('ArrowRight')) dx += 1
      const len = Math.hypot(dx, dz) || 1
      client.keys.x = dx / len
      client.keys.z = dz / len
    }
    const down = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
      if (e.code === 'KeyF') {
        if (!e.repeat) client.slap()
        return
      }
      if (e.code === 'KeyH') {
        if (!e.repeat) client.handshake()
        return
      }
      if (e.code === 'Space') {
        e.preventDefault() // інакше пробіл «натисне» кнопку у фокусі або прокрутить сторінку
        if (!e.repeat) client.jump()
        return
      }
      pressed.add(e.code)
      update()
    }
    const up = (e: KeyboardEvent) => {
      pressed.delete(e.code)
      update()
    }
    const blur = () => {
      pressed.clear()
      update()
    }
    window.addEventListener('keydown', down)
    window.addEventListener('keyup', up)
    window.addEventListener('blur', blur)
    return () => {
      window.removeEventListener('keydown', down)
      window.removeEventListener('keyup', up)
      window.removeEventListener('blur', blur)
    }
  }, [client])
}
