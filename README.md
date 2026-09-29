# Pocket Storage

Pocket Storage is the Android storage owner for the Pocket ecosystem.

## Current architecture

```
Fellow phone
   │
   │ BLE presence / authenticated GATT
   ▼
Pocket BLE ──► Pocket Connection Service
                    │
                    ├── local-only Wi-Fi hotspot
                    │
                    └── Termux Pocket Web :8787
                              │
                              ▼
                       Pocket Storage API
                              │
                              ▼
                         app-private files
```

The Android package owns its private data. Termux is a client and gateway; it never mounts Pocket's private directory.

## Current repository

### Android

- **Pocket Storage** — headless Android package, private file root, authentication and ContentProvider API.
- **Pocket Identity** — Android Keystore EC signing identity.
- **Pocket BLE** — low-power advertising using a fixed Pocket service UUID.
- **Companion registration** — Android CompanionDeviceManager association and presence observation.
- **Pocket GATT** — challenge/response mutual authentication with device-identity pinning.
- **Pocket Fellow Service** — persistent fellow-phone BLE advertising/GATT service.
- **Pocket Connection Service** — persistent owner-side active connection lifecycle, local-only hotspot and Termux Web startup.
- **Pocket Hotspot Controller** — Android LocalOnlyHotspot integration.
- **Pocket Termux Bridge** — controlled Termux RunCommand startup/shutdown for Pocket Web.

### Termux

- **Pocket CLI** — `pocket connect`, `ls`, `read`, `write`, `append`, `mkdir`, `rename`, `delete`, fellow-device commands, and diagnostics.
- **Pocket Web** — browser file UI on port `8787`.
- **Pocket Bootstrap** — reusable installer for a fresh Termux environment.
- **Protocol docs** — current IPC and connection-layer contract.

## Fresh Termux setup

Install the bootstrap once:

```sh
pkg install -y curl
curl -fsSL https://raw.githubusercontent.com/fanmade2999-bit/storage-sharer/main/termux/bootstrap/install-from-github.sh | sh
```

Then:

```sh
pocket version
pocket doctor
pocket connect
```

For Android to start Pocket Web through Termux's external command interface, grant Pocket the Android `Run commands in Termux environment` additional permission, and enable Termux external apps in `~/.termux/termux.properties`:

```
allow-external-apps=true
```

In Android Settings, open **Pocket Storage → Permissions → Additional permissions** and grant **Run commands in Termux environment**. Restart Termux after changing the Termux property.

## First Pocket login

A fresh Pocket installation starts with the bootstrap password:

```
pocket
```

The first successful connection is intentionally marked `must_change_password`. Change it before filesystem operations:

```sh
pocket change-password
```

The password itself is not persisted in plaintext.

## Fellow sharer flow

On the fellow phone:

```sh
pocket fellow advertise
```

On the owner phone:

```sh
pocket fellow register
```

Android shows its companion-device selection/consent UI. After association, Pocket performs its own cryptographic GATT handshake and records the fellow public identity.

Presence is only a proximity state. It does not start Wi-Fi, Pocket Web, or file access.

To explicitly connect an already-registered nearby fellow:

```sh
pocket fellow list
pocket fellow connect <association_id>
```

The owner performs a fresh BLE handshake before the connection service starts the local-only hotspot and Pocket Web.

To disconnect:

```sh
pocket fellow disconnect
```

To revoke a fellow association:

```sh
pocket fellow remove <association_id>
```

## Security status

This is experimental software and has not received an independent security audit.

The current release protects data through the Android application sandbox, authenticated IPC, session tokens, path-boundary checks, Keystore-backed device identity, and authenticated AES-GCM file containers.

**Still in progress:** device-authenticated Web sessions and a fully automatic fellow-phone Wi-Fi join experience. The current fellow-side Wi-Fi step uses Android network suggestions, so Android remains in control of whether and when the phone joins the local network.
