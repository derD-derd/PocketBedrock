# Building PocketBedrock

## 1. Prerequisites

- Android Studio Ladybug (2024.2) or newer, with:
  - JDK 17 (bundled),
  - Android SDK Platform 35,
  - Build Tools 35.x.
- The Gradle wrapper jar is intentionally not committed. Either open the project in
  Android Studio (it bootstraps from `gradle/wrapper/gradle-wrapper.properties`) or run:

  ```bash
  gradle wrapper --gradle-version 8.11.1
  ./gradlew assembleDebug
  ```

CI (`.github/workflows/ci.yml`) does exactly this and uploads the debug APK.

## 2. Helper binaries (required - cannot be skipped)

Android 10+ (targetSdk >= 29) blocks `exec()` and executable file mappings for anything
under app-writable storage (W^X). The only app-controlled executable location is
`nativeLibraryDir`, whose contents come from `app/src/main/jniLibs/arm64-v8a/*.so`.
That is why proot and box64 ship as `libproot.so` / `libbox64.so`. They must be present
at **build time**; the app deliberately has no runtime download path for them.

Place the two ARM64 binaries here:

```
app/src/main/jniLibs/arm64-v8a/libproot.so
app/src/main/jniLibs/arm64-v8a/libbox64.so
```

Both must be **Android/bionic builds** (linked against the Bionic libc), not glibc
builds. A glibc box64 exec'd from nativeLibraryDir would try to load
`/lib/ld-linux-aarch64.so.1` from the host kernel namespace and fail.

`app/build.gradle.kts` sets `packaging.jniLibs.useLegacyPackaging = true` so the `.so`
files are extracted to disk instead of being left inside the APK - required for `exec()`.

### 2a. Building proot for Android

Option A - fetch the verified Termux package (bionic build):

```
https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.102_aarch64.deb
```

Extract the binary (`ar x proot_*.deb && tar xf data.tar.xz`), take
`data/data/com.termux/files/usr/bin/proot`, `strip` it, and copy it as
`libproot.so`. Check the pool directory for a newer version if available.

Option B - build from source:

```bash
git clone https://github.com/proot-me/proot
cd proot
cmake -B build \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
arm64-v8a-strip build/proot && cp build/proot /path/to/PocketBedrock/app/src/main/jniLibs/arm64-v8a/libproot.so
```

### 2b. Building box64 for Android

Source: https://github.com/ptitSeb/box64 (BSD-3). Either use an existing Android
build (Termux/TUR communities publish bionic box64 packages; verify it is bionic with
`readelf -d box64 | grep NEEDED` - it should list `libandroid.so`/`libc.so`, not
`libc.so.6`), or build it:

```bash
git clone --depth 1 https://github.com/ptitSeb/box64
cd box64
mkdir build && cd build
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DARM_DYNAREC=ON -DARM64=1
make -j$(nproc)
llvm-strip box64 && cp box64 /path/to/PocketBedrock/app/src/main/jniLibs/arm64-v8a/libbox64.so
```

Known-good reference points are the Android box64 builds used by the Mobox/Winlator
ecosystems, which solve the same W^X problem. If your build reports
`Exec format error` or dies loading libraries during the verification boot, the binary
is almost certainly a glibc build.

## 3. Release signing

Create `keystore.properties` (do not commit it):

```properties
storeFile=/absolute/path/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

and wire it in `app/build.gradle.kts` via `signingConfigs` if you publish builds.
The repo ships the debug key configuration only.

## 4. What lands on the device (never in the APK)

Downloaded at first-run by the setup wizard, stored under `files/pocketbedrock/`:

| Component | Source (verified URLs in `RuntimeSources.kt`) | Size |
| --- | --- | --- |
| Ubuntu 22.04 base arm64 rootfs | cdimage.ubuntu.com (official) | ~30 MB → ~100 MB |
| BDS zip | minecraft.net/bedrockdedicatedserver (version from minecraft.wiki) | ~90 MB |
| x86-64 libs | archive.ubuntu.com jammy pool (31 packages, newest published version resolved per package) | ~50 MB |

## 5. Unit tests

```bash
./gradlew testDebugUnitTest
```

Pure-logic coverage: log parsing (levels, player events, save-query output), server.properties
round-trip + validation, restart policy, ring buffer + event-driven log matching, deb
version ordering.
