# Flowstate privacy

Effective: 2026-11-18. Maintainer: Flowstate contributors. Contact: use this repository's Issues for non-sensitive questions; never post credentials, private code, device logs or personal recordings.

The bundled recorded day works locally. The app stores settings, layouts, practice answers and pending host answers on the headset. It reads hand tracking for interaction and optionally room data for placement. Debug builds can write local diagnostic hand and interaction logs; treat exported logs as sensitive. Passthrough is supplied by the headset platform; the app does not receive camera images.

If the optional speech bundle is installed, recognition runs on the headset. Microphone permission is required; listening is paused when the app/headset is inactive or voice is muted. Voice audio is not uploaded by the recognizer. A local transcript can become a project request or an answer sent to your paired host.

Live mode sends project status, code views, events and your answers between your PC host and headset over paired, pinned TLS on the local network. The host advertises its computer name and endpoint over local discovery. The host runs your selected coding CLI under your account. That CLI can send prompts and code to its provider under its own terms. The host stores local events, task sessions, raw coding-agent output, a certificate and paired-device secrets. Raw logs can contain private data; do not share them unreviewed.

The optional Smart answers relay is disabled by default in this export. If you explicitly build with `-PrelayUrl=<URL>` and enable Smart answers, prompts/context may be sent to that endpoint; its operator's policy applies. No analytics, advertising or Flowstate account is required. Platform SDK and operating-system data handling is governed by their providers' policies.

Uninstalling the headset app removes its app-private state. Stop the host and delete its configuration folder and any projects you want removed; deleting only this source checkout does not remove those separate folders. Your coding provider may retain information according to its own policy. Future changes to this document are published here.
