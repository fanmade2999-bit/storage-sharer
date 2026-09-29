# Pocket protocol v0.1

## Layers

Pocket Storage owns the data. Termux is a client. Pocket Web is a gateway. None of the Termux code mounts the Android app private directory.

## Authentication

1. Client calls provider method `connect` with the bootstrap password `pocket`.
2. Pocket verifies the salted password verifier.
3. Pocket returns a random 256-bit session token.
4. The client keeps the token locally with mode 0600.
5. Sessions expire after 30 minutes of inactivity. `lock` revokes every active session.
6. A fresh installation marks the bootstrap password as requiring change. Filesystem operations are refused until `change_password` succeeds.

## Filesystem IPC

Android content supports provider `call`, `query`, `read`, and `write`. The Termux CLI uses those primitives rather than touching the Android private data directory directly.

| Pocket operation | Android operation |
| --- | --- |
| ping/connect/whoami/change-password/lock | provider `call` |
| ls/stat | provider `query` |
| read | provider `read` |
| write/append | provider `write` |
| mkdir/touch | provider `insert` |
| rename | provider `update` |
| delete | provider `delete` |

## Web

Default listener: `127.0.0.1:8787`.

To expose Pocket Web to a future local-only Wi-Fi hotspot, start it on the hotspot-facing interface only after the connection controller has authenticated the fellow device.

Pocket Web stores browser sessions in memory. It does not persist browser passwords or Pocket provider tokens to disk.

## BLE

BLE is deliberately outside v0.1 of the Termux protocol. The later Android layer will use CompanionDeviceManager for registered-device association and presence observation, then perform Pocket cryptographic handshake before starting the local network path.
