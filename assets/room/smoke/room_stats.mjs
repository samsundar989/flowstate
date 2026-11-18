// Loading smoke test: parse room.glb with three.js GLTFLoader (as the WebXR app would)
// and write triangle / material / texture / draw-call counts to runs/sim/room_stats.txt.
//   cd assets/room/smoke && npm install && node room_stats.mjs
import { readFileSync, writeFileSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const glbPath = path.resolve(here, "..", "room.glb");
const outPath = path.resolve(here, "..", "..", "..", "runs", "sim", "room_stats.txt");
const buf = readFileSync(glbPath);
const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);

const gltf = await new Promise((res, rej) => new GLTFLoader().parse(ab, "", res, rej));
let tris = 0, drawCalls = 0;
const materials = new Set(), textures = new Set(), unlit = new Set();
gltf.scene.traverse((o) => {
  if (!o.isMesh) return;
  drawCalls += 1;
  const g = o.geometry;
  tris += (g.index ? g.index.count : g.attributes.position.count) / 3;
  for (const m of [].concat(o.material)) {
    materials.add(m.uuid);
    if (m.isMeshBasicMaterial) unlit.add(m.uuid);
    for (const k of Object.keys(m)) if (m[k] && m[k].isTexture) textures.add(m[k].uuid);
  }
});
const box = new THREE.Box3().setFromObject(gltf.scene);
const size = box.getSize(new THREE.Vector3());
const eye = gltf.scene.getObjectByName("EyeAnchor");
const lines = [
  `file: assets/room/room.glb (${statSync(glbPath).size} bytes)`,
  `loader: three ${THREE.REVISION} GLTFLoader (node ${process.version})`,
  `triangles: ${tris}`,
  `materials: ${materials.size} (unlit/MeshBasicMaterial: ${unlit.size})`,
  `textures: ${textures.size}`,
  `meshes (draw calls, upper bound): ${drawCalls}`,
  `bounds min: ${box.min.toArray().map((v) => v.toFixed(3)).join(", ")}`,
  `bounds max: ${box.max.toArray().map((v) => v.toFixed(3)).join(", ")}`,
  `size (m): ${size.toArray().map((v) => v.toFixed(2)).join(" x ")}`,
  `EyeAnchor: ${eye ? eye.position.toArray().join(", ") : "MISSING"}`,
  `budget (task #57): tris<=60000 ${tris <= 60000 ? "OK" : "FAIL"}; materials<=20 ${materials.size <= 20 ? "OK" : "FAIL"}; ` +
    `textures<=2048px ${textures.size === 0 ? "OK (none)" : "CHECK"}; file<=12MB ${statSync(glbPath).size <= 12e6 ? "OK" : "FAIL"}`,
];
writeFileSync(outPath, lines.join("\n") + "\n");
console.log(lines.join("\n"));
