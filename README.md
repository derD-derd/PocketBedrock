# PocketBedrock

A lightweight Android app that hosts a **Minecraft Bedrock Dedicated Server** on your
phone. Built for a Samsung Galaxy S21-class device (ARM64, 8 GB RAM, Android 12+) with
an external cooler, intended to run **24/7**.

The official Bedrock Dedicated Server (BDS) is an x86-64 Linux binary. On ARM64 Android,
PocketBedrock runs it through a **proot** ARM64 rootfs plus **box64** x86-64 emulation,
with the helper binaries executed from `nativeLibraryDir` to stay compatible with
Android 10+ W^X restrictions.

## Feature overview

| Area | What you get |
| --- | --- |
| Dashboard | Big status card (Offline/Starting/Online/Stopping/Crashed), uptime, version, world name, Start/Stop/Restart, players online/max, live gauges (server CPU from `/proc`, server RSS, device free RAM, battery temp, thermal status, best-effort CPU temp), warning banners (throttling, low battery, low RAM), connection info with LAN address + port-forwarding/tunnel guide, one-tap "Backup world now" |
| Console | Live colorized log (ANSI stripped), 2,000-line in-memory ring buffer with rotating files on disk, search/filter by level, auto-scroll + pause, copy/clear, command input with history (arrow keys / menu) and autocomplete, quick chips (Save, Time day, Weather clear, Say…) |
| Players | Live online list (name, XUID, session length) from log events, lifetime history with first/last seen + total playtime (Room DB), per-player Kick (reason), Ban (XUID-based, expiry optional, enforced app-side even when UI is closed), OP/Deop via `permissions.json`, allowlist add |
| Addons | Scan `behavior_packs/` + `resource_packs/`, install `.mcaddon`/`.mcpack`/`.zip` via SAF, register/unregister in `world_*_packs.json`, enable/disable without deleting, delete with confirmation, dependency warnings, malformed-manifest errors, restart-required banner with one-tap restart |
| Settings | Friendly grouped form over `server.properties` with validation and restart hints, raw editor, Battery saver / Balanced / Max performance presets, theme, keep-screen-on, boot auto-start, auto-restart toggle, temperature auto-stop threshold, log retention, backup folder, notification preferences, battery-optimization helper (ignore-optimizations request, Samsung "Never sleeping apps", phantom-process-killer adb commands) |

## Reliability model

- **Foreground service** (`specialUse`, no time budget) + partial wake lock keeps the
  process alive; the persistent notification carries Start/Stop/Restart actions.
- **Graceful stop chain**: stdin `stop` → 30 s wait → SIGTERM → 5 s → SIGKILL. BDS saves
  the world on `stop`.
- **Auto-restart** on crash with exponential backoff (1/2/4/8/16 s…, max 5 attempts, then
  a notification). Counter resets after 5 minutes of stable uptime.
- **Ban enforcement** lives in the application scope (not the UI): banned XUIDs are
  kicked the moment they connect, even with the app closed.
- **Optional** boot auto-start and scheduled daily restart.

## Performance budget (the app itself, excluding the server process)

- No busy loops anywhere: stdout/stderr use blocking reader threads; supervision uses
  `Process.waitFor()`; log matching is event-driven (`awaitLine` over a SharedFlow).
- Metrics sampling is adaptive: 1.5 s while the UI is visible, 10 s in background.
- Log buffer: last **2,000 lines** in memory; older lines rotate to size-capped files.
- UI: Jetpack Compose, Material 3, dark by default, minimal animations; the console
  LazyColumn uses stable sequence-number keys.
- APK: arm64-only, no bundled BDS or rootfs, no chart libraries → tens of MB,
  comfortably under the 40 MB budget (before the on-device downloads, which are
  ~30 MB rootfs + ~90 MB BDS zip + ~50 MB libraries once).

## Licensing notes (important)

- PocketBedrock does **not** bundle BDS or any Minecraft assets. The setup wizard
  downloads the official zip from `minecraft.net/bedrockdedicatedserver` (version
  resolved via minecraft.wiki) or accepts a zip you downloaded yourself. You must accept
  the [Minecraft EULA](https://www.minecraft.net/en-us/eula) in the wizard and comply
  with it when others connect.
- `libproot.so` / `libbox64.so` are GPL/LGPL tools you build or obtain yourself;
  see BUILDING.md for sources and license obligations (box64 is BSD-3, proot GPL-2.0+).
  Follow the licenses that apply to the builds you ship.

## Build

See [BUILDING.md](BUILDING.md). TL;DR:

1. Android Studio Ladybug+ with JDK 17.
2. Provide `app/src/main/jniLibs/arm64-v8a/libproot.so` and `libbox64.so`
   (Android/bionic ARM64 builds - BUILDING.md has the exact recipes).
3. `gradle wrapper --gradle-version 8.11.1 && ./gradlew assembleDebug`
4. First app launch: setup wizard (EULA → rootfs → BDS → x86-64 libs → verification boot).

## Architecture at a glance

```
MainActivity (Compose, 5 tabs, wizard gate)
   │
AppGraph (application scope - outlives UI and service)
 ├─ ProcessSupervisor ── ServerBackend interface
 │     └─ BdsBox64Backend (default): proot → box64 → bedrock_server
 │     └─ ArmNativeBackendStub (future PocketMine/Nukkit/Dragonfly)
 ├─ LogStore: 2,000-line ring buffer + StateFlow + rotating files + awaitLine
 ├─ PlayerTracker / BanManager / AllowlistManager / PermissionsManager
 ├─ BackupManager (save hold → save query → zip → save resume)
 ├─ AddonsManager (pack install + world registration)
 └─ MetricsSampler (/proc tree CPU/RSS, MemoryInfo, battery, thermal)
ServerService (FGS specialUse): wake lock, notification, thermal listener,
                temperature auto-stop, scheduled daily restart
BootReceiver: optional auto-start (with tappable-notification fallback)
```

`docs/SERVER_RUNTIME.md` documents the emulation layer, W^X handling and troubleshooting
in depth.

## Known limitations

- BDS runs under emulation: expect roughly 20-40% of native ARM throughput; a cooler is
  strongly recommended (the Dashboard will warn you otherwise).
- Vanilla BDS has no ban command: bans are enforced by PocketBedrock (XUID-based) which
  is slightly racy by definition - a player may connect for a fraction of a second before
  being kicked.
- Experimental toggles (scripting API/GameTest) live in `level.dat` (little-endian NBT);
  the app does not edit NBT yet - the Addons screen documents the workaround.
- UPnP port-forwarding is intentionally not automated.
- Phantom-process killing on Android 12+ can reap the emulation tree; the Settings
  battery helper shows the adb commands to disable it.
