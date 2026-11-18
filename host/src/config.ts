// Host identity and storage: everything lives in the user's config folder, never in a repo.
import { X509Certificate, createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync, chmodSync } from "node:fs";
import { homedir, hostname } from "node:os";
import { join } from "node:path";
import selfsigned from "selfsigned";

export function configDir(): string {
  if (process.env.FLOWSTATE_HOST_HOME) return process.env.FLOWSTATE_HOST_HOME;
  if (process.platform === "win32") return join(process.env.APPDATA ?? join(homedir(), "AppData", "Roaming"), "flowstate-host");
  return join(process.env.XDG_CONFIG_HOME ?? join(homedir(), ".config"), "flowstate-host");
}

export interface Identity { key: string; cert: string; fp: string; name: string }

export const fingerprint = (certPem: string) => createHash("sha256").update(new X509Certificate(certPem).raw).digest("hex");

/** Load the host certificate, or create a self-signed ECDSA P-256 one on first run (key file is owner-only). */
export async function loadIdentity(dir = configDir()): Promise<Identity> {
  mkdirSync(dir, { recursive: true });
  const kf = join(dir, "host.key"), cf = join(dir, "host.crt");
  if (!existsSync(kf) || !existsSync(cf)) {
    const notAfter = new Date(Date.now() + 10 * 365 * 86400_000);
    const p = await selfsigned.generate([{ name: "commonName", value: "flowstate-host" }],
      { keyType: "ec", curve: "P-256", algorithm: "sha256", notAfterDate: notAfter } as any);
    writeFileSync(kf, p.private, { mode: 0o600 }); try { chmodSync(kf, 0o600); } catch { /* Windows: ACL of the user profile */ }
    writeFileSync(cf, p.cert);
  }
  const cert = readFileSync(cf, "utf8");
  return { key: readFileSync(kf, "utf8"), cert, fp: fingerprint(cert), name: hostname().slice(0, 32) };
}

export interface Device { device_id: string; secret_sha256: string; name: string; paired_at: string; from: string }
export class DeviceStore {
  private file: string; devices: Device[];
  constructor(dir = configDir()) {
    this.file = join(dir, "devices.json");
    this.devices = existsSync(this.file) ? JSON.parse(readFileSync(this.file, "utf8")) : [];
  }
  add(d: Device) { this.devices.push(d); writeFileSync(this.file, JSON.stringify(this.devices, null, 1), { mode: 0o600 }); }
  find(id: string) { return this.devices.find(d => d.device_id === id); }
}
