import type { BoardInfo } from './types'

/** Як Boards.OPEN_RANGE і Eggs.PICK_RANGE на сервері. */
export const BOARD_RANGE = 2.5
export const EGG_RANGE = 1.8

/** Кольори скриньок по черзі — щоб їх легше було розрізняти й запам'ятати. */
const BOX_COLORS = ['#dc2626', '#2563eb', '#ea580c', '#7c3aed', '#0891b2', '#db2777', '#4b5563']

export function boardColor(boards: BoardInfo[], id: string) {
  return BOX_COLORS[Math.max(0, boards.findIndex((b) => b.id === id)) % BOX_COLORS.length]
}

/** Стабільне невід'ємне число з рядка (вибрати колір, фазу анімації тощо). */
export function hashOf(text: string) {
  let h = 0
  for (const c of text) h = (h * 31 + c.charCodeAt(0)) | 0
  return Math.abs(h)
}
