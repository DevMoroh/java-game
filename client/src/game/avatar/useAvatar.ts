import { useGLTF, useTexture } from '@react-three/drei'
import { useEffect, useMemo } from 'react'
import * as THREE from 'three'
import type { Appearance, Gender } from '../types'
import { ANIMATIONS_URL, AVATAR_SCALE, buildAvatar, CHARACTERS_URL, SKIN_LIGHT_URLS } from './avatar'
import { AvatarAnimator } from './AvatarAnimator'

export interface AvatarHandle {
  object: THREE.Object3D
  animator: AvatarAnimator
}

export function preloadAvatarAssets() {
  useGLTF.preload(CHARACTERS_URL)
  useGLTF.preload(ANIMATIONS_URL)
  useTexture.preload(Object.values(SKIN_LIGHT_URLS))
}

/** Текстури з glTF не перевертаються по Y і живуть у sRGB — підганяємо окремо завантажену так само. */
function prepareSkinTexture(texture: THREE.Texture | THREE.Texture[]) {
  for (const t of Array.isArray(texture) ? texture : [texture]) {
    t.flipY = false
    t.colorSpace = THREE.SRGBColorSpace
    t.needsUpdate = true
  }
}

export function useAvatar(gender: Gender, appearance: Appearance, speed: number): AvatarHandle {
  const { scene } = useGLTF(CHARACTERS_URL)
  const { animations } = useGLTF(ANIMATIONS_URL)
  const lightSkin = useTexture(SKIN_LIGHT_URLS[gender], prepareSkinTexture)

  const parts = useMemo(() => buildAvatar(scene, gender, appearance, lightSkin), [scene, gender, appearance, lightSkin])
  useEffect(() => parts.dispose, [parts])

  // Без dispose у cleanup-ефекті: StrictMode «демонтує» компонент і монтує знову з тим самим
  // useMemo-аніматором — зупинений міксер лишав персонажа в T-позі. Міксер не має глобальних
  // посилань, тож прибирається збирачем сміття разом із моделлю.
  const animator = useMemo(() => new AvatarAnimator(parts.root, animations, speed, AVATAR_SCALE), [parts, animations, speed])

  return { object: parts.root, animator }
}
