This directory must contain the ARM64 helper binaries at build time:

    libproot.so   - Android (bionic) build of proot
    libbox64.so   - Android (bionic) build of box64

They are renamed to `lib*.so` so the Android package manager extracts them to
nativeLibraryDir, the only app-controlled location from which exec() is permitted
on Android 10+ (W^X policy). Downloading them at runtime is impossible by design.

Full build instructions (NDK/cmake commands, known-good sources and versions) are
in the project root: BUILDING.md.
