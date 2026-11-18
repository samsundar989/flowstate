# Headset link client (`com.overhand.app.hostlink`)

The headset's end of the wire contract in [PROTOCOL.md](PROTOCOL.md). It is a self-contained package with JVM tests
against the host's fixtures and against the real `host/` running under Node. #814 wired it into the app: see
"Wiring (done in #814)" below.

| file | what it is |
|---|---|
| `HostLinkModels.kt` | `Endpoint`, `PairedHost`, `PairingInvite` (QR text), `Frames` (the 4 frames we send), `LinkState`/`LinkStatus`, `Refusal` (plain words), `Pin` (fingerprint + 4-char check), `PinningTrustManager` |
| `HostLink.kt` | pairing (QR or 6 digits), one resumable WebSocket (OkHttp 4.12), seq ordering, backoff, rediscovery, answer outbox |
| `HostSource.kt` | `HostSource : SessionSource`: open questions become decision cards; a confirm becomes an `answer` frame |
| `PairedHostStore.kt` | `PairedHostStore` interface; `InMemoryPairedHostStore` (tests); `KeystorePairedHostStore` (device) |
| `HostDiscovery.kt` | `HostDiscovery` interface; `NsdHostDiscovery` (`_flowstate._tcp`, matched by TXT `fp`); `NoDiscovery` |

## States (`HostLink.status`, one value the UI shows)

| state | when | `detail` example |
|---|---|---|
| `UNPAIRED` | no stored host, or after "Not my computer" | Not paired with a computer |
| `PAIRING` | a `pair_token`/`pair_code`/`pair_request` exchange is in flight | Pairing with example-pc |
| `CONFIRM_ON_COMPUTER` | `pairByConfirm`: the commitment checked out; `status.digits` holds the six digits | Check that the computer shows 123 456, then press y on its keyboard. |
| `CONNECTED` | `welcome` received | Connected to example-pc |
| `RECONNECTING` | socket lost; retrying with backoff (or paused by `stop()`) | Reconnecting to example-pc |
| `SEARCHING` | 2 failed tries in a row: looking for the host's fingerprint by mDNS | Looking for example-pc on this Wi-Fi |
| `REFUSED` | pairing refused, wrong certificate, headset forgotten by the host, version mismatch | Wrong code. Check the six digits on the computer. |

Every `detail` is plain English with no vendor names (`HostLinkFixtureTest.everyHostErrorCodeHasWordsWithoutVendorNames`).

### Confirm on the computer (`pairByConfirm`, PROTOCOL.md 2d): what the UI (B2) shows in each state
`pairByConfirm(endpoint)` blocks until paired or refused (up to 125 s): run it off the UI thread and drive the panel from
`statusListeners`. `cancelPairing()` ends it from any thread.

| state | B2 shows | wearer's actions |
|---|---|---|
| `SEARCHING` (from `NsdHostDiscovery.browse`) | the list of computers found on this Wi-Fi (name from TXT `n`) | pinch one; "Type a code instead" |
| `PAIRING` | "Pairing with *name*", a spinner | Cancel → `cancelPairing()` |
| `CONFIRM_ON_COMPUTER` | the six digits **large**, as `123 456` (`status.digits`), and `detail`: "Check that the computer shows 123 456, then press y on its keyboard." | "The numbers are different" and Cancel → both `cancelPairing()`; no other button |
| `CONNECTED` | "Connected to *name*"; then the cards | none needed |
| `REFUSED` + `DECLINED` / `CONFIRM_EXPIRED` / `BUSY` / `RATE_LIMITED` | `detail` (plain words) | "Try again" → `pairByConfirm` again |
| `REFUSED` + `NOT_THIS_COMPUTER` | `detail`: "This is not the computer it claims to be. Nothing was paired." | "Try again"; nothing is stored |
| `REFUSED` + `USE_CODE` (an older host) | `detail`, then the 6-digit keypad (`pairWithCode`) | type the code |
| `UNPAIRED` after `CANCELLED` | the computer list again | |

The headset never shows a "Yes" for the digits: the only confirmation is the `y` typed on the computer, and the fingerprint
is stored only after `paired`. Do not show `Paired.check` on this path (the digits replace it). Keep vendor names out of every
string; the host's device name for this headset is the `deviceName` passed to `HostLink`.

## Rules the code keeps

- **Trust**: TLS only, and only the certificate whose SHA-256 (DER) equals the paired `fp`. There is no trust-all path and no
  cleartext. The hostname check re-checks the pin. A wrong certificate fails in the handshake, before the WebSocket upgrade,
  so no frame is sent (test: `wrongFingerprintIsRefusedBeforeAnyFrame`, host connections = 0).
- **Check**: the 4 characters shown after pairing are computed from the certificate the headset actually saw
  (`Paired.check`). If they differ from the computer's, the UI calls `forget()`.
- **Resume**: `lastSeq` is the highest seq applied. A seq at or below it is dropped; a gap closes the socket and resumes
  from `lastSeq`. `lastSeq` is kept in memory only, so a new process resumes from 0 and rebuilds from the host's log.
- **Reconnect**: exponential backoff with jitter: the cap is 500 ms × 2^(n-1), up to 30 s, and the delay is drawn from
  [cap/2, cap]. A 20 s client ping detects a silent socket. `reconnectNow()` skips the wait (call it on resume).
  After 2 failures in a row, `HostDiscovery.resolve(fp)` looks for a new address, and a found one replaces the stored
  endpoint.
- **Answers** follow the Live delivery rules (`Delivery`, `DeliveryException`, `LivePendingStore`, `LiveSafety`):
  - The answers switch gates `HostSource.answer`. It also gates every frame inside the link, so a queued answer is held
    rather than sent if the switch goes off.
  - An answer is sent with `req_id = a_<decision_id>`. It is resent once on each new connection, and only after the
    replay has caught up to `welcome.head_seq`. An answer the host already took is therefore seen in the log and never
    resent.
  - `CONFIRMED` comes only from the host's `answered` event. `finished` or `unknown_decision` gives `CLOSED`.
  - An answer not confirmed within 8 s raises `NOT_RECORDED_YET`. It stays in the `LivePendingStore` and is re-queued
    after the next catch-up, even after an app restart.

## Storage

`PairedHostStore` holds `{address, port, fp, name, device_id, device_secret}`. On the device, `KeystorePairedHostStore`
encrypts it with AES-256-GCM. The key is generated in and never leaves the Android Keystore, and the ciphertext sits in
private prefs `hostlink-v1`. Nothing goes into the repo or a log. The JVM tests use `InMemoryPairedHostStore`.
`KeystorePairedHostStore` and `NsdHostDiscovery` compile but are only exercised on a headset (in the wiring task).

## Wiring (done in #814)

- **`features/Shell.kt`**: `hostDiscovery` (`NsdHostDiscovery`), `hostLink` (`KeystorePairedHostStore`, `deviceName="Headset"`,
  `mayTransmit={ LiveSafety.sendReal }`) and `hostLinkMade`. `sessionSource` has a third mode: `session-mode=host` gives
  `HostSource(hostLink, LivePendingStore(files/host-pending.json))` and starts the link. `live` and `recorded` are unchanged.
- **`features/connect/ConnectFeature.kt`** (new) owns the rest, so `SessionModeFeature` and `SettingsFeature` are untouched:
  - one Settings row (order 45), "Connect your computer", opens the Connect panel (registered at runtime, id `0x08140001`);
  - opening it browses mDNS for 3 s (with a multicast lock). If exactly one computer answers, it goes straight to
    `pairByConfirm`; otherwise it lists them. "Type a code instead" shows the keypad for the first listed computer
    (`pairWithCode`). The keypad needs mDNS too, because the fingerprint comes from the TXT record;
  - on `paired` it sets `session-mode=host` and restarts the session (as Live does), then reopens the panel on Connected;
  - `onResume` calls `reconnectNow()`, and a default-network callback calls it when Wi-Fi comes back. `onDestroy` shuts the link down;
  - "Forget this computer" calls `forget()` and returns to Recorded.
- **`hostlink/ui/ConnectScreen.kt`** maps (`LinkStatus`, hosts, keypad, diagnosis) to the panel's words and buttons, and
  `ConnectPanelView.kt` draws them. Every state is registered with `LayoutLintPanels` as `connect_<state>`.
- **Failure words** (`Diagnosis`), from what the headset can see when the link is down: no Wi-Fi address → "This headset is not
  on Wi-Fi. Connect both to the same Wi-Fi."; another subnet → "The computer is on a different network. …"; a TCP connect to
  the host's port that is refused → "The host is not running on the computer. Start it there."; one that times out → "The computer
  does not answer. Allow the host through the computer's firewall."; unreachable → "… Connect both to the same Wi-Fi, not a
  guest network." Refusals on the computer and expiry use `Refusal.words`.
- **`AndroidManifest.xml`**: `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE` (for the network callback) and
  `CHANGE_WIFI_MULTICAST_STATE`. All are normal permissions, so there is no prompt.
- **Debug intents** (debug builds): `--ez connect_open true` (clicks the Settings row), `--es connect_click <tag>` (clicks a
  panel button: `connect-retry`, `connect-forget`, `connect-host-0`, `connect-key-4`, …), `--ez connect_dump true`
  (writes `files/connect_trace.json`). Each screen the panel shows is also drawn to `files/connect_frames/`.

**Once on the computer:** the first time the host runs, Windows asks whether Node.js may accept connections. Allow it, and
tick Public as well if the Wi-Fi is marked Public. Otherwise the headset finds the host but cannot connect, and it shows the
firewall words above.

## Tests

`gradlew :app:testDebugUnitTest --tests "*hostlink*"` runs 28 tests:
- `ConfirmPairingTest` (10): confirm-on-computer against the real host: the fixture's derivation, the happy path (same digits,
  nothing stored before `y`), the computer says no, 120 s expiry, the wearer cancels, three relays with their own certificate,
  a raw client that withholds its nonce, and an older host that falls back to the typed code. `HOSTLINK_BIND=<Wi-Fi address>`
  makes the harness listen there instead of loopback.
- `HostLinkFixtureTest` (6): the fixture files, the QR, the check codes, frames identical field for field, the plain words.
- `HostLinkRealHostTest` (12): a real `host/src/server.ts` in Node on loopback, driven by `host_harness.ts`, which
  provides the test clock, socket kills, questions and an address move. The test runs `npm ci` in `host/` if
  `node_modules` is missing. It needs Node 22 or newer on PATH. Numbers are written to `$HOSTLINK_OUT`
  (`runs/host/hostlink/`).
