package com.pocketbedrock.app.runtime

import java.io.IOException

/**
 * Resolves the newest published amd64 .deb for a package by reading the official Ubuntu
 * jammy pool directory listing. Avoids brittle version-pinned URLs: whatever version is
 * currently published is picked, versions sorted numerically.
 */
class DebResolver(private val downloader: HttpDownloader) {

    data class Resolved(val packageName: String, val version: String, val url: String)

    fun resolve(pkg: RuntimeSources.Amd64Package): Resolved {
        val html = downloader.fetchText(pkg.poolUrl)
            ?: throw IOException("Could not read pool listing ${pkg.poolUrl}")
        val names = pkg.debLinkRegex.findAll(html).map { it.groupValues[1] }.toList()
        if (names.isEmpty()) {
            throw IOException("No ${pkg.packageName} amd64 .deb found in ${pkg.poolUrl}")
        }
        val best = names.maxWithOrNull(
            compareBy { name -> Version(name.substringAfter('_').substringBefore('_amd64')) },
        )!!
        val version = best.substringAfter('_').substringBefore('_amd64')
        return Resolved(pkg.packageName, version, pkg.poolUrl + best)
    }

    /** Numeric-aware Debian-ish version ordering (good enough for picking the newest). */
    data class Version(val raw: String) : Comparable<Version> {

        private val tokens: List<Pair<Int, Long>> = tokenize(raw)

        override fun compareTo(other: Version): Int {
            val n = maxOf(tokens.size, other.tokens.size)
            for (i in 0 until n) {
                val a = tokens.getOrNull(i) ?: Pair(0, 0L)
                val b = other.tokens.getOrNull(i) ?: Pair(0, 0L)
                if (a.first != b.first) return a.first - b.first
                if (a.second != b.second) return a.second.compareTo(b.second)
            }
            return raw.length.compareTo(other.raw.length)
        }

        companion object {
            // Numeric runs sort after alpha runs so "3.8" > "3ubuntu1".
            private fun tokenize(v: String): List<Pair<Int, Long>> =
                v.split(Regex("[._~+-]")).filter { it.isNotEmpty() }.map { token ->
                    token.toLongOrNull()?.let { Pair(1, it) } ?: Pair(0, token.hashCode().toLong() and 0xFFFF)
                }
        }
    }
}
