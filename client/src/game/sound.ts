/** Синтезований звук ляпаса: короткий сплеск шуму через смуговий фільтр — без аудіофайлів. */
let ctx: AudioContext | null = null

/** «Вжух» — рука пролетіла повз: м'який шум, фільтр якого ковзає вниз. */
export function playWhooshSound(volume: number) {
  if (volume <= 0.01) return
  try {
    ctx ??= new AudioContext()
    const now = ctx.currentTime
    const duration = 0.25
    const length = Math.floor(ctx.sampleRate * duration)
    const buffer = ctx.createBuffer(1, length, ctx.sampleRate)
    const data = buffer.getChannelData(0)
    for (let i = 0; i < length; i++) {
      const k = i / length
      data[i] = (Math.random() * 2 - 1) * Math.sin(Math.PI * k) // плавно наростає і згасає
    }
    const source = ctx.createBufferSource()
    source.buffer = buffer
    const filter = ctx.createBiquadFilter()
    filter.type = 'bandpass'
    filter.Q.value = 1.5
    filter.frequency.setValueAtTime(2500, now)
    filter.frequency.exponentialRampToValueAtTime(400, now + duration)
    const gain = ctx.createGain()
    gain.gain.setValueAtTime(volume, now)
    source.connect(filter).connect(gain).connect(ctx.destination)
    source.start(now)
  } catch {
    // Аудіо недоступне — грається без звуку.
  }
}

export function playSlapSound(volume: number) {
  if (volume <= 0.01) return
  try {
    ctx ??= new AudioContext()
    const now = ctx.currentTime
    const length = Math.floor(ctx.sampleRate * 0.12)
    const buffer = ctx.createBuffer(1, length, ctx.sampleRate)
    const data = buffer.getChannelData(0)
    for (let i = 0; i < length; i++) {
      // Різкий початок і швидке згасання — «шльоп».
      data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / length, 4)
    }
    const source = ctx.createBufferSource()
    source.buffer = buffer
    const filter = ctx.createBiquadFilter()
    filter.type = 'bandpass'
    filter.frequency.value = 1800
    filter.Q.value = 0.8
    const gain = ctx.createGain()
    gain.gain.setValueAtTime(volume, now)
    source.connect(filter).connect(gain).connect(ctx.destination)
    source.start(now)
  } catch {
    // Аудіо недоступне (немає жесту користувача тощо) — грається без звуку.
  }
}

/** «Плесь-плесь» рукостискання: два м'які глухі шльопки. */
export function playPatSound(volume: number) {
  if (volume <= 0.01) return
  try {
    ctx ??= new AudioContext()
    for (const delay of [0, 0.16]) {
      const now = ctx.currentTime + delay
      const length = Math.floor(ctx.sampleRate * 0.08)
      const buffer = ctx.createBuffer(1, length, ctx.sampleRate)
      const data = buffer.getChannelData(0)
      for (let i = 0; i < length; i++) data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / length, 3)
      const source = ctx.createBufferSource()
      source.buffer = buffer
      const filter = ctx.createBiquadFilter()
      filter.type = 'lowpass'
      filter.frequency.value = 900
      const gain = ctx.createGain()
      gain.gain.setValueAtTime(volume, now)
      source.connect(filter).connect(gain).connect(ctx.destination)
      source.start(now)
    }
  } catch {
    // Аудіо недоступне — грається без звуку.
  }
}
