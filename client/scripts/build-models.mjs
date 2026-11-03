/**
 * Збирає ігрові моделі з сирих паків Quaternius (CC0) у client/assets-src/quaternius/.
 *
 *   npm run build:models
 *
 * Результат у public/models/:
 *   characters.glb — тіла (Male, Female) і зачіски (Hair_*), кожне — окремий вузол зі своїм скелетом;
 *                    у грі зачіски перев'язуються на скелет тіла за назвами кісток.
 *   animations.glb — лише потрібні кліпи з обох Animation Library, без мешів.
 *   skin_*.webp    — світлі варіанти текстури шкіри (у .gltf тіл прописані темні).
 */
import { NodeIO } from '@gltf-transform/core'
import { ALL_EXTENSIONS } from '@gltf-transform/extensions'
import { dedup, meshopt, mergeDocuments, prune, resample, textureCompress } from '@gltf-transform/functions'
import { access, mkdir, readFile, stat } from 'node:fs/promises'
import { basename, dirname, join } from 'node:path'
import { MeshoptEncoder } from 'meshoptimizer'
import sharp from 'sharp'

const SRC = 'assets-src/quaternius'
const OUT = 'public/models'
const TEXTURE_SIZE = 1024

const BODIES = {
  Male: `${SRC}/Base Characters/Godot - UE/Superhero_Male_FullBody.gltf`,
  Female: `${SRC}/Base Characters/Godot - UE/Superhero_Female_FullBody.gltf`,
}
const HAIRS = ['Hair_Buzzed', 'Hair_SimpleParted', 'Hair_Beard', 'Hair_Long', 'Hair_Buns', 'Hair_BuzzedFemale']
const HAIR_DIR = `${SRC}/Hairstyles/Rigged to Head Bone/glTF (Godot -Unreal)`

const CLIPS = {
  [`${SRC}/Unreal-Godot/UAL1_Standard.glb`]: [
    'Idle_Loop', 'Walk_Loop', 'Jog_Fwd_Loop', 'Sprint_Loop', 'Idle_Talking_Loop', 'Interact',
    'Punch_Jab', 'Punch_Cross', 'Hit_Head', 'Hit_Chest', 'Dance_Loop', 'Jump_Start', 'Jump_Land',
  ],
  [`${SRC}/Unreal-Godot/UAL2_Standard.glb`]: [
    'Idle_FoldArms_Loop', 'Yes', 'Idle_No_Loop', 'Melee_Hook', 'Hit_Knockback', 'Chest_Open',
  ],
}
/** Анімації зняті з чоловічого манекена: переміщення кісток несуть його пропорції. Лишаємо лише таз. */
const KEEP_TRANSLATION = new Set(['pelvis'])

const io = new NodeIO().registerExtensions(ALL_EXTENSIONS).registerDependencies({ 'meshopt.encoder': MeshoptEncoder })
await MeshoptEncoder.ready
await mkdir(OUT, { recursive: true })

await buildCharacters()
await buildAnimations()
await buildSkinTextures()

async function buildCharacters() {
  const parts = [...Object.entries(BODIES), ...HAIRS.map((h) => [h, `${HAIR_DIR}/${h}.gltf`])]
  const doc = await readGltf(parts[0][1])
  nameRoot(doc, parts[0][0])
  for (const [name, path] of parts.slice(1)) {
    const src = await readGltf(path)
    nameRoot(src, name)
    mergeDocuments(doc, src)
  }
  // mergeDocuments додає кожне джерело окремою сценою — зводимо в одну.
  const [main, ...rest] = doc.getRoot().listScenes()
  for (const scene of rest) {
    for (const node of scene.listChildren()) main.addChild(node)
    scene.dispose()
  }
  doc.getRoot().setDefaultScene(main)
  doc.getRoot().listBuffers().slice(1).forEach((b) => b.dispose())
  const buffer = doc.getRoot().listBuffers()[0]
  doc.getRoot().listAccessors().forEach((a) => a.setBuffer(buffer))

  // Зайві UV-канали й вершинні кольори (для шейдерів рушіїв) — інакше three.js помножить колір на них.
  for (const mesh of doc.getRoot().listMeshes()) {
    for (const prim of mesh.listPrimitives()) {
      for (const sem of ['TEXCOORD_1', 'TEXCOORD_2', 'TEXCOORD_3', 'COLOR_0', 'COLOR_1']) prim.setAttribute(sem, null)
    }
  }

  await doc.transform(
    prune(),
    dedup(),
    textureCompress({ encoder: sharp, targetFormat: 'webp', resize: [TEXTURE_SIZE, TEXTURE_SIZE], quality: 88 }),
    meshopt({ encoder: MeshoptEncoder, level: 'medium' }),
  )
  await write(doc, 'characters.glb')
}

async function buildAnimations() {
  const entries = Object.entries(CLIPS)
  const doc = await io.read(entries[0][0])
  keepClips(doc, entries[0][1])
  const bones = new Map(doc.getRoot().listNodes().map((n) => [n.getName(), n]))

  for (const [path, names] of entries.slice(1)) {
    const src = await io.read(path)
    keepClips(src, names)
    const map = mergeDocuments(doc, src)
    // Перенаправляємо канали з кісток другого паку на кістки першого (скелет однаковий).
    for (const anim of src.getRoot().listAnimations()) {
      for (const ch of map.get(anim).listChannels()) {
        const bone = bones.get(ch.getTargetNode().getName())
        if (bone) ch.setTargetNode(bone)
        else ch.dispose()
      }
    }
    for (const scene of doc.getRoot().listScenes().slice(1)) scene.dispose()
  }
  // Кістки з другого паку тепер нікому не потрібні.
  const mainBones = new Set(bones.values())
  doc.getRoot().listNodes().filter((n) => !mainBones.has(n)).forEach((n) => n.dispose())
  doc.getRoot().listBuffers().slice(1).forEach((b) => b.dispose())
  const buffer = doc.getRoot().listBuffers()[0]
  doc.getRoot().listAccessors().forEach((a) => a.setBuffer(buffer))

  for (const node of doc.getRoot().listNodes()) {
    node.setMesh(null)
    node.setSkin(null)
  }
  for (const anim of doc.getRoot().listAnimations()) {
    for (const ch of anim.listChannels()) {
      const path = ch.getTargetPath()
      if (path === 'scale' || (path === 'translation' && !KEEP_TRANSLATION.has(ch.getTargetNode().getName()))) {
        ch.getSampler().dispose()
        ch.dispose()
      }
    }
  }
  await doc.transform(
    resample(),
    // keepLeaves: кінцеві кістки (…_leaf) без анімації теж мають лишитися — їх шукає скелет.
    prune({ keepLeaves: true, propertyTypes: ['Node'] }),
    prune({ propertyTypes: ['Accessor', 'Buffer', 'Mesh', 'Skin', 'Material', 'Texture'] }),
    dedup(),
    meshopt({ encoder: MeshoptEncoder, level: 'medium' }),
  )
  console.log('  clips:', doc.getRoot().listAnimations().map((a) => a.getName()).join(', '))
  await write(doc, 'animations.glb')
}

async function buildSkinTextures() {
  for (const [name, file] of [
    ['skin_male_light.webp', 'T_Superhero_Male_Ligh.png'],
    ['skin_female_light.webp', 'T_Superhero_Female_Light_BaseColor.png'],
  ]) {
    await sharp(`${SRC}/Base Characters/Textures/${file}`).resize(TEXTURE_SIZE, TEXTURE_SIZE).webp({ quality: 88 }).toFile(`${OUT}/${name}`)
    await report(name)
  }
}

/**
 * Читає .gltf, виправляючи биті посилання на текстури: у паку частина файлів має суфікс
 * «_png.png» у .gltf, а на диску — «.png», або лежить у сусідній папці Textures.
 */
async function readGltf(path) {
  const dir = dirname(path)
  const json = JSON.parse(await readFile(path, 'utf8'))
  const resources = {}
  for (const item of [...(json.buffers ?? []), ...(json.images ?? [])]) {
    if (!item.uri || item.uri.startsWith('data:')) continue
    const uri = decodeURIComponent(item.uri)
    const candidates = [uri, uri.replace(/_png\.png$/, '.png'), join('../Textures', basename(uri)), join('../Textures', basename(uri).replace(/_png\.png$/, '.png'))]
    const found = await firstExisting(candidates.map((c) => join(dir, c)))
    if (!found) throw new Error(`${path}: не знайдено ${uri}`)
    resources[item.uri] = new Uint8Array(await readFile(found))
  }
  return io.readJSON({ json, resources })
}

async function firstExisting(paths) {
  for (const p of paths) {
    try {
      await access(p)
      return p
    } catch {
      // наступний кандидат
    }
  }
  return null
}

function nameRoot(doc, name) {
  const roots = doc.getRoot().getDefaultScene().listChildren()
  if (roots.length !== 1) throw new Error(`${name}: очікував один кореневий вузол, маю ${roots.length}`)
  roots[0].setName(name)
}

function keepClips(doc, names) {
  const wanted = new Set(names)
  for (const anim of doc.getRoot().listAnimations()) {
    if (wanted.delete(anim.getName())) continue
    // Семплери треба знищувати явно: інакше вони тримають accessor'и і prune їх не прибере.
    anim.listSamplers().forEach((sampler) => sampler.dispose())
    anim.dispose()
  }
  if (wanted.size) throw new Error(`Не знайдено кліпів: ${[...wanted].join(', ')}`)
}

async function write(doc, name) {
  await io.write(`${OUT}/${name}`, doc)
  await report(name)
}

async function report(name) {
  const { size } = await stat(`${OUT}/${name}`)
  console.log(`${name.padEnd(24)} ${(size / 1024 / 1024).toFixed(2)} MB`)
}
