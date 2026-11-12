# The server runtime layer, in depth

This document explains exactly how the official Bedrock Dedicated Server (BDS) - an
x86-64 Linux binary - runs on an ARM64 Android phone, and why every design choice is
forced by Android security policy.

## 1. The W^X problem

Since Android 10, an app with `targetSdk >= 29` may **not**:

- `exec()` any file under its writable storage (`filesDir`, `cacheDir`, ...),
- create executable file-backed mappings for such files (SELinux
  `neverallow untrusted_app app_data_file:file execute;`).

The only app-controlled location from which `exec()` is still permitted is
**`nativeLibraryDir`** (`/data/app/<pkg>-*/lib/arm64`), populated at install time from
`jniLibs/arm64-v8a/lib*.so` (with `useLegacyPackaging = true` so files land on disk).

Consequences:

1. `proot` and `box64` **must** be bundled in the APK as `libproot.so` / `libbox64.so`.
   There is no runtime download fallback - downloading into `filesDir` would produce a
   file the app can never execute. (This is also why they must be bionic builds: a
   glibc ARM64 binary exec'd from `nativeLibraryDir` would look for
   `/lib/ld-linux-aarch64.so.1` in the host namespace and fail.)
2. Everything inside the rootfs (the ARM64 system files, the amd64 libraries,
   `bedrock_server` itself) is only ever **read as data** - never executed by the kernel.

## 2. The process chain

```
PocketBedrock app process (Kotlin, Compose UI, foreground service)
 └─ libproot.so   (nativeLibraryDir, ARM64, exec()'d via ProcessBuilder)
     │  flags: --kill-on-exit -0 -w /bds
     │          -R <rootfs>                       (Ubuntu 22.04 arm64 base, guest /)
     │          -b /dev  -b /proc  -b /sys        (device nodes, /proc tree)
     │          -b <bdsDir>:/bds                  (server working directory)
     │          -b <nativeLib>/libbox64.so:/usr/local/bin/box64
     └─ box64  (ARM64/bionic, exec()'d from nativeLibraryDir THROUGH the bind mount)
         │  loads bedrock_server (x86-64) AS DATA and interprets/recompiles it
         │  env: LD_LIBRARY_PATH=/lib/x86_64-linux-gnu, BOX64_PREFER_WRAPPED=1
         └─ bedrock_server (x86-64, never exec()'d by the kernel)
```

Key properties:

- The **kernel only ever exec()s binaries from `nativeLibraryDir`** (proot, box64).
  Everything else is data read by box64's own ELF loader.
- proot gives the guest a coherent Linux namespace (real `/etc`, `/proc`, writable
  `/tmp`), so BDS's file handling behaves like on a normal Linux host.
- DNS: PocketBedrock writes `etc/resolv.conf` (1.1.1.1 / 8.8.8.8) into the rootfs.
  box64 also forwards libc name resolution to the host resolver, so both paths work.
- `PROOT_NO_SECCOMP=1` is set defensively: some OEM kernels ship broken seccomp
  filters that make ptrace-based proot fail.

## 3. Rootfs

- Default: **ubuntu-base-22.04.5-base-arm64.tar.gz** from
  `https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/` (official, ~30 MB).
- Any glibc arm64 rootfs tarball can be imported instead (`Import tar.gz` in the
  wizard). musl-based rootfs (plain Alpine) will not run the amd64 libraries.
- Extraction handles symlinks (e.g. `/bin -> usr/bin`) and hardlinks, restores unix
  permissions (`Os.chmod`), and prepares `tmp/`, `bds/` (bind target), `resolv.conf`,
  `hosts`.

## 4. x86-64 system libraries (the clever part)

BDS links against `libstdc++`, `libssl3`, `libcurl4` and libcurl's dependency tree
(brotli, nghttp2, rtmp, libssh, libpsl+libicu, openldap, sasl, krb5, gnutls, gcrypt...).
On a PC you would `apt install` these - but running apt inside proot would require
executing guest binaries from app storage, which W^X forbids.

PocketBedrock instead:

1. reads the **Ubuntu jammy pool directory listing** for each of the 31 packages
   (`RuntimeSources.AMD64_PACKAGES`), picks the newest published amd64 `.deb`
   (numeric-aware version ordering, so no pinned URLs can rot),
2. downloads it (~50 MB total),
3. extracts the shared libraries straight out of `data.tar.{zst,xz,gz,bz2}` with
   commons-compress (`ArArchiveInputStream` → decompressor → `TarArchiveInputStream`),
4. copies every `*/x86_64-linux-gnu/*.so*` into
   `rootfs/usr/lib/x86_64-linux-gnu/`.

`LD_LIBRARY_PATH=/lib/x86_64-linux-gnu` makes box64 find them; with
`BOX64_PREFER_WRAPPED=1` box64 wraps common libc functions to the host (bionic) libc and
falls back to the real amd64 `.so` files for the rest.

## 5. Downloading BDS legally

Mojang does not allow redistribution, so BDS is never bundled:

1. The wizard asks you to accept the Minecraft EULA + Privacy Policy (links out).
2. The latest version is resolved from `https://minecraft.wiki/w/Bedrock_Dedicated_Server`
   (server-rendered, exposes the version), then the zip is fetched from
   `https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-<v>.zip`
   (azureedge mirror as fallback, both verified with a HEAD request first).
3. Manual version entry and zip import are first-class alternatives.

The zip is extracted to `files/pocketbedrock/bds/bedrock-server-<v>/` - this directory
is bind-mounted at `/bds` and is the server's working directory (`server.properties`,
`worlds/`, `allowlist.json`, `permissions.json` all live here and are owned by the app).

## 6. Supervision and graceful stop

`ProcessSupervisor` (application scope, not the UI) owns the backend:

- **start**: spawn chain; state `STARTING`; on the `"Server started."` log line →
  `ONLINE`; one blocked `waitFor()` thread classifies the exit.
- **stop**: stdin `stop` (BDS flushes and saves the world) → wait 30 s → SIGTERM →
  wait 5 s → SIGKILL.
- **crash**: state `CRASHED`; auto-restart with exponential backoff (1/2/4/8/16 s…,
  capped 60 s), max 5 attempts, then a notification. The attempt budget resets after
  5 minutes of stable uptime.

## 7. Live backups

Vanilla-safe sequence (`save hold` → `save query` → read files → `save resume`):

1. `save hold` freezes the world DB while the server keeps running.
2. `save query` prints the frozen file list (`  db/000014.log: 4096`, terminated by
   `#Files: N, TotalSize: M`). PocketBedrock matches these lines event-driven from the
   log stream (retrying every second while the save is still in progress).
3. Paths are resolved under the level directory first, then the server root, zipped to
   the user-chosen SAF folder or the default app-external backups folder.
4. `save resume` unfreezes.

## 8. Troubleshooting table

| Symptom | Likely cause / fix |
| --- | --- |
| "libproot.so missing" in wizard step 2 | APK was built without the helper binaries; see BUILDING.md §2 |
| `Exec format error` in the log tail | helper binary is a glibc build (wrong ELF interpreter) - use a bionic build |
| Crash while loading `.so` libraries after boot | libbox64.so is glibc-built or the x86 libs step failed - rerun wizard step 5 |
| Server boots but Xbox auth fails | DNS blocked by captive portal; test with a browser, check `etc/resolv.conf` |
| Server dies after ~10 min in background | Android 12+ phantom process killer - Settings → Battery helper → adb commands |
| Port unreachable from outside | CGNAT on mobile data (use playit.gg/Tailscale), or missing UDP 19132 forwarding |
| `save query` never completes | world larger than RAM or very slow flash; backups retry 12 times automatically |

## 9. Sources (verified at time of writing)

| Resource | URL |
| --- | --- |
| Ubuntu base arm64 | `https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz` |
| proot (Termux bionic build) | `https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.102_aarch64.deb` |
| box64 source | `https://github.com/ptitSeb/box64` |
| BDS version reference | `https://minecraft.wiki/w/Bedrock_Dedicated_Server` |
| BDS zip | `https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-<version>.zip` |
| Ubuntu jammy pool | `https://archive.ubuntu.com/ubuntu/pool/main/<source>/` |
| Minecraft EULA | `https://www.minecraft.net/en-us/eula` |

All sources live in one auditable file: `runtime/RuntimeSources.kt`.
