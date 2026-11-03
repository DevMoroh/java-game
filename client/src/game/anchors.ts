import type * as THREE from 'three'

/** Реєстр: id гравця → 3D-якір (модель персонажа). Читає NameplateProjector. */
export const anchors = new Map<string, THREE.Object3D>()
