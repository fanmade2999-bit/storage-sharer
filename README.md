# Pocket Storage

Private, app-owned Android storage with a controlled client API.

## Architecture

- **Pocket Storage** — Android package that owns private files and authentication.
- **Pocket BLE** — companion-device discovery, presence, registration, and authenticated handshakes.
- **Pocket Web** — Termux web gateway planned for port 8787.
- **Pocket CLI / Bootstrap** — Termux client and deployment tooling planned for later phases.

## Current milestone

Phase 1 establishes the Android storage/authentication core and build CI.

This project is experimental and has not received an independent security audit.
