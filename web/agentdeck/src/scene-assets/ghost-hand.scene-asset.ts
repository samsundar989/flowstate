import { Group, Mesh, MeshBasicMaterial, CylinderGeometry, Vector3 } from '@iwsdk/core';
import { hex, T } from '../theme.js';
import poses from '../../tests/hand_poses.json';

/** A translucent joint diagram in metres, wrist at origin; +Y up, -Z fingers.
 * Captured emulator poses supply proportions. No skin, handedness mirroring or force feedback.
 */
function hand(pose: Record<string, number[]>, pinch = false): Group {
  const root = new Group();
  const material = new MeshBasicMaterial({ color: hex(T.ion), transparent: true, opacity: 0.48, depthWrite: false });
  const wrist = new Vector3(...pose.wrist);
  const a = new Vector3(), b = new Vector3(), direction = new Vector3();
  for (const finger of ['thumb', 'index', 'middle', 'ring', 'pinky']) {
    const prefix = finger === 'thumb' ? 'thumb' : `${finger}-finger`;
    const keys = ['metacarpal', 'phalanx-proximal', ...(finger === 'thumb' ? [] : ['phalanx-intermediate']), 'phalanx-distal', 'tip'];
    let previous = pose.wrist;
    for (const key of keys) {
      const point = pose[`${prefix}-${key}`];
      if (!point) continue;
      const target = pinch && finger === 'thumb' && key === 'tip' ? pose['index-finger-tip'] : point;
      a.fromArray(previous).sub(wrist); b.fromArray(target).sub(wrist);
      direction.subVectors(b, a);
      const bone = new Mesh(new CylinderGeometry(0.003, 0.003, direction.length(), 6), material);
      bone.name = `${finger}-${key}`;
      bone.position.copy(a).add(b).multiplyScalar(0.5);
      bone.quaternion.setFromUnitVectors(new Vector3(0, 1, 0), direction.normalize());
      root.add(bone);
      previous = target;
    }
  }
  return root;
}
export const ghostPoke = hand(poses.point_up);
export const ghostPinch = hand(poses.default_up, true);
export const ghostThumb = hand(poses['thumbs-up_up']);
