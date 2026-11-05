import * as THREE from 'three'

/**
 * DOM-підписи над нерухомими об'єктами поля (скриньки): id → елемент і точка у світі.
 * Позиції на екрані щокадру виставляє NameplateProjector — так само, як підписи гравців.
 */
export const worldLabels = new Map<string, { el: HTMLElement; at: THREE.Vector3 }>()

/** ref-колбек для React: реєструє елемент підпису над точкою (x, y, z). */
export function worldLabelRef(id: string, x: number, y: number, z: number) {
  return (el: HTMLElement | null) => {
    if (el) worldLabels.set(id, { el, at: new THREE.Vector3(x, y, z) })
    else worldLabels.delete(id)
  }
}
