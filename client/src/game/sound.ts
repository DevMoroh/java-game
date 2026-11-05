import { soundsOn } from './soundSettings'

/** Синтезований звук ляпаса: короткий сплеск шуму через смуговий фільтр — без аудіофайлів. */
let ctx: AudioContext | null = null

/** «Вжух» — рука пролетіла повз: м'який шум, фільтр якого ковзає вниз. */
export function playWhooshSound(volume: number) {
  if (volume <= 0.01 || !soundsOn()) return
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
  if (volume <= 0.01 || !soundsOn()) return
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
  if (volume <= 0.01 || !soundsOn()) return
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

/** «Дзінь-дзінь» за очки: три ноти вгору (до-мі-соль) і дзвіночок октавою вище — як коротка фанфара. */
export function playChimeSound(volume: number) {
  if (volume <= 0.01 || !soundsOn()) return
  try {
    ctx ??= new AudioContext()
    const notes = [
      { freq: 523.25, at: 0, len: 0.18 },
      { freq: 659.25, at: 0.09, len: 0.18 },
      { freq: 783.99, at: 0.18, len: 0.22 },
      { freq: 1046.5, at: 0.27, len: 0.55 },
    ]
    for (const n of notes) {
      const now = ctx.currentTime + n.at
      for (const [type, level] of [['triangle', 1], ['sine', 0.5]] as const) {
        const osc = ctx.createOscillator()
        osc.type = type
        // Другий голос трохи вище — «блиск», як у дзвіночка.
        osc.frequency.value = type === 'sine' ? n.freq * 2 : n.freq
        const gain = ctx.createGain()
        gain.gain.setValueAtTime(0, now)
        gain.gain.linearRampToValueAtTime(volume * 0.35 * level, now + 0.015)
        gain.gain.exponentialRampToValueAtTime(0.0001, now + n.len)
        osc.connect(gain).connect(ctx.destination)
        osc.start(now)
        osc.stop(now + n.len + 0.05)
      }
    }
  } catch {
    // Аудіо недоступне — грається без звуку.
  }
}

/** «Дінь-дон» нової пошти: дві м'які ноти вниз з довгим дзвінким згасанням — тихіше й спокійніше за фанфару очок. */
export function playMailSound(volume: number) {
  if (volume <= 0.01 || !soundsOn()) return
  try {
    ctx ??= new AudioContext()
    for (const n of [
      { freq: 1318.5, at: 0 },
      { freq: 987.77, at: 0.16 },
    ]) {
      const now = ctx.currentTime + n.at
      for (const [mult, level] of [
        [1, 1],
        [2.76, 0.25], // негармонійний обертон — «метал» дзвіночка
      ] as const) {
        const osc = ctx.createOscillator()
        osc.type = 'sine'
        osc.frequency.value = n.freq * mult
        const gain = ctx.createGain()
        gain.gain.setValueAtTime(0, now)
        gain.gain.linearRampToValueAtTime(volume * 0.3 * level, now + 0.01)
        gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.9)
        osc.connect(gain).connect(ctx.destination)
        osc.start(now)
        osc.stop(now + 0.95)
      }
    }
  } catch {
    // Аудіо недоступне — грається без звуку.
  }
}
