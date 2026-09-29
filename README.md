# Pocket Storage

Pocket Storage is the Android storage owner for the Pocket ecosystem.

## Phase 1

This repository currently contains:

- **Pocket Storage Android app** — headless package with app-private storage.
- **Authentication** — bootstrap password `pocket`, stored as a salted PBKDF2 verifier; first successful connection is marked **must change password**.
- **Sessions** — random bearer tokens with a 30-minute sliding lifetime; `lock` revokes active sessions.
- **Filesystem API** — list/stat through `query`, file streaming through `openFile`, plus create, rename, and recursive delete.
- **Path boundary** — canonical-path validation blocks traversal outside Pocket's private root.
- **GitHub Actions CI** — unit tests and a debug APK build on pushes and pull requests.

Pocket's files live below the Android app's private `filesDir/pocket` directory. The Termux/web layers are intended to call this controlled provider API rather than mount or expose that directory directly.

## Provider

Authority:

`com.pocket.storage.provider`

Base URI:

`content://com.pocket.storage.provider`

Provider call methods:

`ping`, `connect`, `whoami`, `change_password`, `disconnect`, `lock`

The provider is intentionally exported so a client such as Termux can reach it through Android IPC. Authentication is required for file operations.

## Planned phases

**Phase 2 — Pocket Web / CLI**

Add the Termux gateway, browser interface on port `8787`, and deployment/bootstrap tooling.

**Phase 3 — Pocket BLE**

Add companion-device registration, presence observation, and cryptographic device authentication.

**Phase 4 — Connection flow**

Add explicit CONNECT → BLE handshake → local-only hotspot → Pocket Web connection.

## Security status

This is experimental software and has not received an independent security audit. The default bootstrap password must be changed on first use.
