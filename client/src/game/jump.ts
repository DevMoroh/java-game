/** Тривалість стрибка (с); має збігатися з GameWorld.JUMP_NANOS на сервері. */
export const JUMP_DURATION = 0.75
/** Висота верхньої точки стрибка (м). */
const JUMP_HEIGHT = 1.0

/** Висота над землею через t секунд після відштовхування: парабола 0 → H → 0. */
export function jumpHeight(t: number) {
  const k = t / JUMP_DURATION
  return k <= 0 || k >= 1 ? 0 : 4 * JUMP_HEIGHT * k * (1 - k)
}
