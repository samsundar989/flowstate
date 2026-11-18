// An attacker on the same Wi-Fi: it answered the headset's mDNS query with its own address and its own certificate's
// fingerprint, and relays to the real host (whose fingerprint it knows from the real advertisement).
//   transparent: forwards every frame unchanged.
//   active:      plays the host to the headset (its own commitment, made with its own fingerprint) and the headset to
//                the host (its own device key and nonce).
//   grind:       learns the host's six digits first, then, after seeing the headset's nonce, searches for a nonce that
//                makes the headset show the same six digits, and reveals that instead of the one it committed to.
import { createServer } from "node:https";
import selfsigned from "selfsigned";
import { WebSocketServer, type WebSocket } from "ws";
import { HeadsetClient } from "../src/client.ts";
import { fingerprint } from "../src/config.ts";
import { commitment, digits, newNonce } from "../src/pairing_confirm.ts";
import { devicePub } from "./confirm_client.ts";

export type RelayMode = "transparent" | "active" | "grind";
export interface Relay {
  port: number; fp: string; close(): Promise<void>;
  /** What each side was led to compute: the headset's digits (with the relay's fp) and the host's (with its own). */
  seen: { headsetDigits?: string; hostDigits?: string; grindTries?: number; grindFound?: boolean };
}

export async function startRelay(o: { bind: string; host: string; hostPort: number; hostFp: string; mode: RelayMode }): Promise<Relay> {
  const p = await selfsigned.generate([{ name: "commonName", value: "flowstate-host" }], { keyType: "ec", curve: "P-256", algorithm: "sha256" } as any);
  const fp = fingerprint(p.cert);
  const https = createServer({ key: p.private, cert: p.cert });
  const wss = new WebSocketServer({ server: https, path: "/v1" });
  const seen: Relay["seen"] = {};
  const ups: HeadsetClient[] = [];
  wss.on("connection", (down: WebSocket) => {
    // Hold the headset's frames until the upstream connection is open.
    const early: string[] = []; const hold = (m: any) => early.push(String(m)); down.on("message", hold);
    new HeadsetClient(o.host, o.hostPort, o.hostFp).open().then(up => { ups.push(up); down.off("message", hold); wire(down, up); for (const m of early) down.emit("message", m); },
      () => down.close());
  });
  const wire = (down: WebSocket, up: HeadsetClient) => {
    const toDown = (f: object) => { if (down.readyState === down.OPEN) down.send(JSON.stringify(f)); };
    up.ws.on("close", () => down.close());
    down.on("close", () => up.close());
    if (o.mode === "transparent") {
      up.ws.on("message", d => toDown(JSON.parse(String(d))));
      down.on("message", d => up.ws.send(String(d)));
      return;
    }
    // active and grind: two separate exchanges.
    let headsetPub = "", relayNonce = "", hostDigits: Promise<string> | undefined;
    const myPub = devicePub();
    const hostSide = async (deviceNonce: string) => {
      up.send({ type: "pair_request", device_pub: myPub, device_name: "Headset" });
      const c = await up.waitFor(f => f.type === "pair_commit");
      up.send({ type: "pair_nonce", nonce: deviceNonce });
      const rev = await up.waitFor(f => f.type === "pair_reveal");
      const d = digits(o.hostFp, myPub, rev.nonce, deviceNonce);
      if (commitment(o.hostFp, myPub, rev.nonce) !== c.c) throw new Error("host commitment did not verify");
      seen.hostDigits = d;
      up.ws.on("message", m => { const f = JSON.parse(String(m)); if (f.type !== "pair_pending") toDown(f); }); // paired / refused
      return d;
    };
    down.on("message", async m => {
      const f = JSON.parse(String(m));
      if (f.type === "pair_request") {
        headsetPub = f.device_pub; relayNonce = newNonce();
        if (o.mode === "grind") hostDigits = hostSide(newNonce()); // the host's digits are fixed before the headset commits to anything
        toDown({ type: "pair_commit", c: commitment(fp, headsetPub, relayNonce) });
      } else if (f.type === "pair_nonce") {
        let reveal = relayNonce;
        if (o.mode === "active") await hostSide(newNonce());
        else {
          const want = await hostDigits!;
          let tries = 0, found = false;
          while (tries < 5_000_000) { // expected 10^6: find a nonce the headset would turn into the host's digits
            const n = newNonce(); tries++;
            if (digits(fp, headsetPub, n, f.nonce) === want) { reveal = n; found = true; break; }
          }
          seen.grindTries = tries; seen.grindFound = found;
        }
        seen.headsetDigits = digits(fp, headsetPub, reveal, f.nonce);
        toDown({ type: "pair_reveal", nonce: reveal });
        toDown({ type: "pair_pending", digits_hint: "none" });
      }
    });
  };
  await new Promise<void>(r => https.listen(0, o.bind, () => r()));
  return {
    port: (https.address() as any).port, fp, seen,
    close: async () => { for (const u of ups) u.close(); for (const c of wss.clients) c.terminate(); await new Promise<void>(r => wss.close(() => https.close(() => r()))); },
  };
}
