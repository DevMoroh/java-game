import * as THREE from 'three'

/** Процедурна текстура трави: зелена основа + тисячі коротких штрихів-травинок. */
export function createGrassTexture(): THREE.CanvasTexture {
  const size = 512
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = size
  const ctx = canvas.getContext('2d')!

  ctx.fillStyle = '#4a7a2c'
  ctx.fillRect(0, 0, size, size)

  // Великі плями для неоднорідності.
  for (let i = 0; i < 40; i++) {
    const x = Math.random() * size
    const y = Math.random() * size
    const r = 20 + Math.random() * 60
    const g = ctx.createRadialGradient(x, y, 0, x, y, r)
    const light = Math.random() > 0.5
    g.addColorStop(0, light ? 'rgba(120,170,60,0.35)' : 'rgba(40,80,20,0.35)')
    g.addColorStop(1, 'rgba(0,0,0,0)')
    ctx.fillStyle = g
    wrapped(size, x, y, r, (ox, oy) => {
      ctx.save()
      ctx.translate(ox, oy)
      ctx.fillRect(x - r, y - r, r * 2, r * 2)
      ctx.restore()
    })
  }

  // Травинки.
  const greens = ['#3d6b22', '#5a8f34', '#6fa63f', '#2f5a1a', '#7cb349', '#4f8530']
  ctx.lineCap = 'round'
  for (let i = 0; i < 9000; i++) {
    const x = Math.random() * size
    const y = Math.random() * size
    const len = 3 + Math.random() * 6
    const angle = -Math.PI / 2 + (Math.random() - 0.5) * 0.9
    ctx.strokeStyle = greens[(Math.random() * greens.length) | 0]
    ctx.lineWidth = 1 + Math.random()
    wrapped(size, x, y, len, (ox, oy) => {
      ctx.beginPath()
      ctx.moveTo(x + ox, y + oy)
      ctx.lineTo(x + ox + Math.cos(angle) * len, y + oy + Math.sin(angle) * len)
      ctx.stroke()
    })
  }

  const texture = new THREE.CanvasTexture(canvas)
  texture.wrapS = texture.wrapT = THREE.RepeatWrapping
  texture.colorSpace = THREE.SRGBColorSpace
  texture.anisotropy = 8
  return texture
}

/** Малює елемент ще й з іншого боку полотна, якщо він вилазить за край — текстура стає безшовною. */
function wrapped(size: number, x: number, y: number, r: number, draw: (ox: number, oy: number) => void) {
  const xs = [0, ...(x < r ? [size] : []), ...(x > size - r ? [-size] : [])]
  const ys = [0, ...(y < r ? [size] : []), ...(y > size - r ? [-size] : [])]
  for (const ox of xs) for (const oy of ys) draw(ox, oy)
}

/** Детермінований ГПВЧ, щоб декорації були однакові в усіх клієнтів. */
export function seededRandom(seed: number) {
  return () => {
    seed |= 0
    seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
