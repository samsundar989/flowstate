import { AnimatedHand, createSystem, type World } from '@iwsdk/core';

// The SDK's hand visual loads from a public CDN unless its class names an assetPath. One path
// serves one file, so each side gets its own subclass. See public/hands/generic-hand/NOTICE.md.
const handUrl = (side: 'left' | 'right') =>
  `${import.meta.env.BASE_URL}hands/generic-hand/${side}.glb`;

class LeftHand extends AnimatedHand {
  static assetPath = handUrl('left');
}
class RightHand extends AnimatedHand {
  static assetPath = handUrl('right');
}

/** Must run before an XR session starts, so the CDN default is never requested. */
export function useLocalHandModels(world: World): void {
  const { left, right } = world.input.xr.visualAdapters.hand;
  left.updateVisualImplementation(LeftHand);
  right.updateVisualImplementation(RightHand);
}

/**
 * A controller keeps its ray and its trigger at every hold. The SDK gives each side a touch pointer
 * that outranks the ray within 0.15 m of a panel and uses a controller's ray origin as its fingertip,
 * so a controller resting near the tray lost its ray and its trigger chose nothing (task #641). Touch
 * stays on only for a side whose primary input source is a tracked hand, so hands poke as before.
 * Checked every frame: the SDK's input system turns touch back on whenever a pokeable panel appears.
 */
export class TouchForHandsSystem extends createSystem({}) {
  private shown = '';

  update(): void {
    const xr = this.world.input.xr;
    const said = (['left', 'right'] as const).map((side) => {
      const hand = !!xr.getPrimaryInputSource(side)?.hand;
      xr.multiPointers[side].toggleSubPointer('touch', hand);
      return `${side}=${hand ? 'touch+ray' : 'ray'}`;
    }).join(' ');
    if (said !== this.shown) console.info(`[overhand] pointers ${(this.shown = said)}`);
  }
}
