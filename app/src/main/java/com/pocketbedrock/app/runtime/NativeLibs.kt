package com.pocketbedrock.app.runtime

import android.content.Context
import java.io.File

/**
 * proot and box64 are packaged as `lib*.so` inside jniLibs/arm64-v8a. The Android
 * package manager extracts them to nativeLibraryDir, which is the ONLY app-controlled
 * location from which exec() is permitted on Android 10+ (targetSdk >= 29 enforces W^X
 * on app-writable storage). Downloading them at runtime cannot work - they must be
 * bundled at build time (see BUILDING.md).
 */
class NativeLibs(context: Context) {

    val dir: File = File(context.applicationInfo.nativeLibraryDir)

    val proot: File = File(dir, "libproot.so")
    val box64: File = File(dir, "libbox64.so")

    val prootPresent: Boolean get() = proot.exists() && proot.length() > 0
    val box64Present: Boolean get() = box64.exists() && box64.length() > 0

    val allPresent: Boolean get() = prootPresent && box64Present

    /** Human-readable status used by the setup wizard and diagnostics. */
    fun describe(): String = buildString {
        append("nativeLibraryDir: ${dir.absolutePath}\n")
        append("libproot.so: ${if (prootPresent) "found" else "MISSING"}\n")
        append("libbox64.so: ${if (box64Present) "found" else "MISSING"}")
    }
}
