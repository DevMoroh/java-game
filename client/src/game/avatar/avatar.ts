import * as THREE from 'three'
import { clone as cloneSkinned } from 'three/examples/jsm/utils/SkeletonUtils.js'
import type { Appearance, Gender } from '../types'

/**
 * Збирання персонажа з characters.glb (див. scripts/build-models.mjs):
 * тіло клонується разом зі скелетом, зачіска перев'язується на кістки тіла за назвами,
 * матеріали отримують кольори гравця (шкіра, волосся, одяг).
 */

export const CHARACTERS_URL = '/models/characters.glb'
export const ANIMATIONS_URL = '/models/animations.glb'
export const SKIN_LIGHT_URLS = {
  MALE: '/models/skin_male_light.webp',
  FEMALE: '/models/skin_female_light.webp',
} as const

/** Зростання моделі ~1.81 м; трохи збільшуємо, щоб краще читалося з камери згори. */
export const AVATAR_SCALE = 1.1

const HAIR_STYLES: Record<Gender, string[]> = {
  MALE: ['Hair_Buzzed', 'Hair_SimpleParted', 'Hair_Buzzed', 'Hair_SimpleParted', 'Hair_Long', 'Hair_Buzzed'],
  FEMALE: ['Hair_Long', 'Hair_Buns', 'Hair_BuzzedFemale', 'Hair_Long', 'Hair_Buns', 'Hair_Long'],
}

/**
 * Тон шкіри: яка базова текстура (світла/темна) і чим її помножити.
 * Ключі — палітра Appearance.SKINS на сервері.
 */
const SKIN_TONES: Record<string, { light: boolean; tint: string }> = {
  '#f5d0b0': { light: true, tint: '#ffffff' },
  '#e8b48f': { light: true, tint: '#f3e2d4' },
  '#c98e66': { light: true, tint: '#dcbfa8' },
  '#a86b45': { light: false, tint: '#ffffff' },
  '#7a4a2c': { light: false, tint: '#b8a294' },
}

const SHOES_COLOR = '#2b2b2b'

/**
 * Регіони одягу за кістками скелета (індекси — порядок joints у скіні, однаковий у всіх моделях Quaternius).
 * Вершина належить регіону, до кісток якого має найбільшу сумарну вагу.
 */
const SHIRT_BONES = [2, 3, 4, 7, 8, 31, 32] // spine_01..03, ключиці, плечі (короткий рукав)
const PANTS_BONES = [1, 55, 56, 60, 61] // таз, стегна, гомілки
const SHOES_BONES = [57, 58, 59, 62, 63, 64] // стопи

export interface AvatarParts {
  root: THREE.Object3D
  dispose: () => void
}

export function buildAvatar(
  library: THREE.Object3D,
  gender: Gender,
  appearance: Appearance,
  lightSkin: THREE.Texture,
): AvatarParts {
  const source = library.getObjectByName(gender === 'FEMALE' ? 'Female' : 'Male')
  if (!source) throw new Error('characters.glb: no body for ' + gender)
  const root = cloneSkinned(source)
  root.position.set(0, 0, 0)

  const canonical = canonicalBoneNames(library)
  const bones = new Map<string, THREE.Bone>()
  root.traverse((o) => {
    if (!(o as THREE.Bone).isBone) return
    o.name = canonical(o.name) // інакше кліпи (pelvis.quaternion, …) не знайдуть кісток
    bones.set(o.name, o as THREE.Bone)
  })

  const hairNames = [HAIR_STYLES[gender][appearance.hairStyle % HAIR_STYLES[gender].length]]
  if (gender === 'MALE' && appearance.beard) hairNames.push('Hair_Beard')
  for (const name of hairNames) {
    library.getObjectByName(name)?.traverse((o) => {
      if ((o as THREE.SkinnedMesh).isSkinnedMesh) root.add(rebind(o as THREE.SkinnedMesh, bones, canonical))
    })
  }

  const materials: THREE.Material[] = []
  const tone = SKIN_TONES[appearance.skin] ?? { light: true, tint: '#ffffff' }
  root.traverse((o) => {
    const mesh = o as THREE.SkinnedMesh
    if (!mesh.isSkinnedMesh) return
    mesh.castShadow = true
    // Межі скінованих мешів рахуються в bind-позі — з анімацією їх легко «загубити» відсіканням.
    mesh.frustumCulled = false
    const src = mesh.material as THREE.MeshStandardMaterial
    let mat: THREE.MeshStandardMaterial
    if (src.name.startsWith('MI_Hair')) {
      mat = hairMaterial(src, appearance.hair)
    } else if (src.name.startsWith('MI_Superhero')) {
      mat = bodyMaterial(src, tone.light ? lightSkin : null, tone.tint, appearance)
    } else {
      mat = src // очі — без змін, спільний матеріал
    }
    if (mat !== src) materials.push(mat)
    mesh.material = mat
  })
  root.scale.setScalar(AVATAR_SCALE)

  return { root, dispose: () => materials.forEach((m) => m.dispose()) }
}

const canonicalCache = new WeakMap<THREE.Object3D, (name: string) => string>()

/**
 * GLTFLoader робить назви вузлів унікальними в межах файлу: у characters.glb вісім однакових скелетів,
 * тож кістки другого й далі отримують суфікси (pelvis_1, spine_01_3…). Справжні назви — в першому
 * скелеті (Male), за ними й відновлюємо.
 */
function canonicalBoneNames(library: THREE.Object3D) {
  let fn = canonicalCache.get(library)
  if (fn) return fn
  const names = new Set<string>()
  library.getObjectByName('Male')?.traverse((o) => {
    if ((o as THREE.Bone).isBone) names.add(o.name)
  })
  fn = (name: string) => {
    if (names.has(name)) return name
    const base = name.replace(/_\d+$/, '')
    return names.has(base) ? base : name
  }
  canonicalCache.set(library, fn)
  return fn
}

/** Копія меша зачіски, прив'язана до кісток тіла (у файлі в неї власна копія скелета). */
function rebind(
  src: THREE.SkinnedMesh,
  bones: Map<string, THREE.Bone>,
  canonical: (name: string) => string,
): THREE.SkinnedMesh {
  const mesh = new THREE.SkinnedMesh(src.geometry, src.material)
  mesh.name = src.name
  const skeleton = new THREE.Skeleton(
    src.skeleton.bones.map((b) => {
      const bone = bones.get(canonical(b.name))
      if (!bone) throw new Error(`Hairstyle ${src.name}: body has no bone ${b.name}`)
      return bone
    }),
    src.skeleton.boneInverses,
  )
  mesh.bind(skeleton, src.bindMatrix)
  return mesh
}

/** Волосся й брови: текстура лише дає світлотінь пасм, колір — від гравця. */
function hairMaterial(src: THREE.MeshStandardMaterial, color: string) {
  const mat = src.clone()
  const uniforms = { uHairColor: { value: new THREE.Color(color) } }
  mat.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, uniforms)
    shader.fragmentShader = shader.fragmentShader
      .replace('void main() {', 'uniform vec3 uHairColor;\nvoid main() {')
      .replace(
        '#include <map_fragment>',
        `#include <map_fragment>
        float hairLuma = dot(sampledDiffuseColor.rgb, vec3(0.299, 0.587, 0.114));
        diffuseColor.rgb = uHairColor * clamp(hairLuma * 4.0, 0.25, 1.6);`,
      )
  }
  mat.customProgramCacheKey = () => 'avatar-hair'
  return mat
}

/**
 * Тіло: шкіра з текстури (з тонуванням), а там, де «одяг», — суцільний колір футболки/штанів/взуття
 * з пригашеною картою нормалей, щоб не просвічували м'язи.
 */
function bodyMaterial(
  src: THREE.MeshStandardMaterial,
  lightSkin: THREE.Texture | null,
  tint: string,
  a: Appearance,
) {
  const mat = src.clone()
  if (lightSkin) mat.map = lightSkin
  mat.color.set(tint)
  const uniforms = {
    uShirt: { value: new THREE.Color(a.shirt) },
    uPants: { value: new THREE.Color(a.pants) },
    uShoes: { value: new THREE.Color(SHOES_COLOR) },
  }
  mat.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, uniforms)
    shader.vertexShader = shader.vertexShader
      .replace('void main() {', `varying vec4 vRegion;\n${regionFunction()}\nvoid main() {`)
      .replace(
        '#include <skinbase_vertex>',
        `#include <skinbase_vertex>
        vRegion = region(skinIndex.x) * skinWeight.x + region(skinIndex.y) * skinWeight.y
                + region(skinIndex.z) * skinWeight.z + region(skinIndex.w) * skinWeight.w;`,
      )
    shader.fragmentShader = shader.fragmentShader
      .replace('void main() {', 'varying vec4 vRegion;\nuniform vec3 uShirt, uPants, uShoes;\nvoid main() {')
      .replace(
        '#include <map_fragment>',
        `#include <map_fragment>
        // vRegion = (футболка, штани, взуття, шкіра); argmax по інтерпольованих вагах дає чіткий шов.
        float cloth = 1.0;
        if (vRegion.x >= max(max(vRegion.y, vRegion.z), vRegion.w)) diffuseColor.rgb = uShirt;
        else if (vRegion.y >= max(vRegion.z, vRegion.w)) diffuseColor.rgb = uPants;
        else if (vRegion.z >= vRegion.w) diffuseColor.rgb = uShoes;
        else cloth = 0.0;`,
      )
      .replace(
        '#include <roughnessmap_fragment>',
        `#include <roughnessmap_fragment>
        roughnessFactor = mix(roughnessFactor, 0.9, cloth);`,
      )
      .replace(
        '#include <normal_fragment_maps>',
        `#include <normal_fragment_maps>
        normal = normalize(mix(normal, nonPerturbedNormal, cloth * 0.8));`,
      )
  }
  mat.customProgramCacheKey = () => 'avatar-body'
  return mat
}

function regionFunction() {
  const test = (bones: number[]) => bones.map((b) => `abs(i - ${b}.0) < 0.5`).join(' || ')
  return `vec4 region(float i) {
    if (${test(SHIRT_BONES)}) return vec4(1.0, 0.0, 0.0, 0.0);
    if (${test(PANTS_BONES)}) return vec4(0.0, 1.0, 0.0, 0.0);
    if (${test(SHOES_BONES)}) return vec4(0.0, 0.0, 1.0, 0.0);
    return vec4(0.0, 0.0, 0.0, 1.0);
  }`
}
