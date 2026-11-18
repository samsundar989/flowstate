// One command: `npm start` (or `flowstate-host`). No desktop UI: prints a QR and a 6-digit code in the terminal.
import { networkInterfaces, homedir } from "node:os";
import { join } from "node:path";
import QRCode from "qrcode";
import { Bonjour } from "bonjour-service";
import { Host } from "./server.ts";
import { configDir } from "./config.ts";
import { DEFAULT_PORT, checkCode, pairUri } from "./protocol.ts";
import { attachTerminal } from "./pairing_confirm.ts";

const arg = (k: string, d?: string) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };

/** The address a headset on the same Wi-Fi can reach: a private IPv4 on a non-virtual adapter, Wi-Fi first. */
export function lanAddress(): string | undefined {
  const all = Object.entries(networkInterfaces()).flatMap(([name, list]) => (list ?? []).map(a => ({ name, ...a })))
    .filter(a => a.family === "IPv4" && !a.internal && /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(a.address) && !/vEthernet|WSL|Hyper-V|VirtualBox|VMware|docker|Tailscale/i.test(a.name));
  return (all.find(a => /wi-?fi|wlan|wireless/i.test(a.name)) ?? all[0])?.address;
}

/** This computer's address on a private overlay network (192.0.2.10/10, the shared-address range such networks use). */
export function tailnetAddress(): string | undefined {
  return Object.values(networkInterfaces()).flatMap(l => l ?? [])
    .find(a => a.family === "IPv4" && !a.internal && /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(a.address))?.address;
}

/** `--bind`: `lan` (default), `tailnet`, or a literal address or name. The headset can then type this computer's name. */
export function resolveBind(spec: string | undefined): string | undefined {
  if (spec === undefined || spec === "lan") return lanAddress();
  if (spec === "tailnet") return tailnetAddress();
  return spec;
}

async function main() {
  const sub = process.argv[2];
  if (["new", "status", "stop", "answer"].includes(sub)) { const { cli } = await import("./cli.ts"); process.exit(await cli(process.argv.slice(2))); }
  const bind = resolveBind(arg("bind"));
  if (!bind) {
    console.error(arg("bind") === "tailnet" ? "No private-network address found. Sign in to the private network on this computer first, or pass --bind <address>."
      : "No LAN address found. Connect this computer to the same Wi-Fi as the headset, or pass --bind <address>.");
    process.exit(2);
  }
  const host = new Host({ bind, port: Number(arg("port", String(DEFAULT_PORT))), dir: arg("config", configDir())!,
    projectsRoot: arg("projects", join(homedir(), "FlowstateProjects"))!, agent: (arg("agent", "auto") as any), replayFile: arg("replay"),
    perProject: Number(arg("per-project", "1")), maxRunning: Number(arg("max-running", "2")), taskTimeoutMs: Number(arg("task-timeout-min", "30")) * 60_000 });
  const { address, port } = await host.start();
  const show = async () => {
    const uri = pairUri({ h: address, p: port, fp: host.id.fp, t: host.pairing.token.value, n: host.id.name });
    const qr = await QRCode.toString(uri, { type: "terminal", small: true, errorCorrectionLevel: "M" });
    const c = host.pairing.code.value;
    console.log(`\n${qr}\nFlowstate host on ${address}:${port}  (${host.id.name})`);
    console.log(`Scan the code with the headset, or pick "${host.id.name}" in the headset and enter:  ${c.slice(0, 3)} ${c.slice(3)}`);
    console.log(`The headset will then show the check:  ${checkCode(host.id.fp, c)}   (QR check: ${checkCode(host.id.fp, host.pairing.token.value)})`);
    console.log(`Certificate fingerprint: ${host.id.fp}\nCodes expire in 2 minutes; a new one is printed then. Ctrl+C stops the host.`);
    if (process.env.FLOWSTATE_PRINT_URI) console.log(`PAIR_URI ${uri}`);
  };
  host.pairing.onRotate = () => { void show(); };
  await show();
  setInterval(() => host.pairing.tick(), 1000).unref();
  attachTerminal(host.confirm); // "Pair "Headset" (192.0.2.10)? Code 123 456. Type y to allow"
  let mdns: Bonjour | undefined;
  // mDNS does not cross a private overlay network: there the headset types this computer's name instead.
  if (arg("bind") === "tailnet") console.log(`Not advertised on Wi-Fi. On the headset: Other ways to connect, Enter computer address, then this computer's name.`);
  else if (!process.argv.includes("--no-mdns")) {
    try {
      mdns = new Bonjour({ interface: address } as any);
      mdns.publish({ name: `Flowstate ${host.id.name}`, type: "flowstate", protocol: "tcp", port, host: undefined as any,
        txt: { v: "1", fp: host.id.fp, n: host.id.name, pair: "confirm1" } });
      console.log(`Discoverable on the local network as _flowstate._tcp`);
    } catch (e) { console.log(`mDNS advertisement unavailable (${(e as Error).message}); pair with the QR code.`); }
  }
  const stop = async () => { mdns?.unpublishAll(); mdns?.destroy(); await host.stop(); process.exit(0); };
  process.once("SIGINT", stop); process.once("SIGTERM", stop);
}

if (import.meta.url === `file:///${process.argv[1].replace(/\\/g, "/").replace(/^\//, "")}` || process.argv[1]?.endsWith("main.ts")) void main();
