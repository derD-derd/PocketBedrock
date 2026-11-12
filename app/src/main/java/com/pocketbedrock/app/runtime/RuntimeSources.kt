package com.pocketbedrock.app.runtime

/**
 * All download sources used by the setup wizard, in one place so they are easy to
 * audit and update. Every URL below was verified against the live source at the time
 * of writing:
 *
 *  - Ubuntu 22.04 base arm64 rootfs: official cdimage.ubuntu.com directory listing.
 *  - proot: Termux package pool (an Android/bionic build of proot).
 *  - BDS zip: minecraft.wiki exposes the latest version, and the zip lives on
 *    minecraft.net/bedrockdedicatedserver (azureedge mirror as fallback).
 *  - x86-64 shared libraries: official Ubuntu jammy archive pool, resolved at install
 *    time (the newest published version is picked from the pool listing, so no
 *    version-pinned URLs can rot).
 */
object RuntimeSources {

    const val UBUNTU_BASE_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz"

    const val PROOT_DEB_URL =
        "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.102_aarch64.deb"

    /** box64 is NOT downloaded on device: it must be bundled as jniLibs/arm64-v8a/libbox64.so. */
    const val BOX64_SOURCE_URL = "https://github.com/ptitSeb/box64"

    const val BDS_WIKI_MAIN = "https://minecraft.wiki/w/Bedrock_Dedicated_Server"

    val BDS_ZIP_URL_TEMPLATES = listOf(
        "https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-%s.zip",
        "https://minecraft.azureedge.net/bin-linux/bedrock-server-%s.zip",
    )

    const val MINECRAFT_EULA_URL = "https://www.minecraft.net/en-us/eula"
    const val MINECRAFT_PRIVACY_URL = "https://privacy.microsoft.com/privacystatement"

    const val UBUNTU_REPO = "https://archive.ubuntu.com/ubuntu"

    /**
     * x86-64 (amd64) packages bedrock_server needs at runtime: libstdc++6, libssl3 and
     * libcurl4 plus libcurl's transitive dependency tree on jammy. Each is resolved from
     * its source pool directory at install time.
     */
    val AMD64_PACKAGES: List<Amd64Package> = listOf(
        Amd64Package("glibc", "libc6"),
        Amd64Package("gcc-12", "libstdc++6"),
        Amd64Package("gcc-12", "libgcc-s1"),
        Amd64Package("openssl", "libssl3"),
        Amd64Package("curl", "libcurl4"),
        Amd64Package("zlib", "zlib1g"),
        Amd64Package("brotli", "libbrotli1"),
        Amd64Package("nghttp2", "libnghttp2-14"),
        Amd64Package("rtmpdump", "librtmp1"),
        Amd64Package("libssh", "libssh-4"),
        Amd64Package("libpsl", "libpsl5"),
        Amd64Package("openldap", "libldap-2.5-0"),
        Amd64Package("cyrus-sasl2", "libsasl2-2"),
        Amd64Package("krb5", "libgssapi-krb5-2"),
        Amd64Package("krb5", "libkrb5-3"),
        Amd64Package("krb5", "libk5crypto3"),
        Amd64Package("krb5", "libkrb5support0"),
        Amd64Package("e2fsprogs", "libcom-err2"),
        Amd64Package("keyutils", "libkeyutils1"),
        Amd64Package("libidn2", "libidn2-0"),
        Amd64Package("libunistring", "libunistring2"),
        Amd64Package("gnutls28", "libgnutls30"),
        Amd64Package("nettle", "libnettle8"),
        Amd64Package("nettle", "libhogweed6"),
        Amd64Package("gmp", "libgmp10"),
        Amd64Package("p11-kit", "libp11-kit0"),
        Amd64Package("libtasn1", "libtasn1-6"),
        Amd64Package("libffi", "libffi8"),
        Amd64Package("icu", "libicu70"),
        Amd64Package("libgcrypt20", "libgcrypt20"),
        Amd64Package("libgpg-error", "libgpg-error0"),
    )

    data class Amd64Package(val sourcePool: String, val packageName: String) {
        val poolUrl: String get() = "$UBUNTU_REPO/pool/main/$sourcePool/"

        /** Matches <pkg>_<version>_amd64.deb inside the pool listing HTML. */
        val debLinkRegex: Regex =
            Regex("href=\"(${Regex.escape(packageName)}_[A-Za-z0-9.~+-]+_amd64\\.deb)\"")
    }
}
