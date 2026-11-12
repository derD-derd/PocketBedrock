package com.pocketbedrock.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Live world backups using BDS's safe-save protocol:
 *
 *   1. `save hold`      - freezes the world database (async, world stays playable)
 *   2. `save query`     - returns the frozen file list with lengths
 *   3. copy/zip files   - read from disk while frozen
 *   4. `save resume`    - unfreeze
 *
 * The file list from `save query` is relative to the level directory; paths are
 * resolved against the level folder first, then the server working directory.
 */
class BackupManager(
    private val logStore: LogStore,
    private val commandSender: suspend (String) -> Boolean,
    private val bdsDirProvider: () -> File,
) {

    /** Quick UI capability check: backups need a live server + stdin. */
    fun canBackup(): Boolean = commandSender != null

    suspend fun backupWorld(
        targetZip: File,
        onProgress: (String) -> Unit = {},
    ): Result<List<String>> = backupWorldToStream(
        FileOutputStream(targetZip),
        onProgress,
    ).also { result ->
        if (result.isFailure) {
            targetZip.delete()
        }
    }

    /** Zip straight into a caller-owned stream (SAF targets). */
    suspend fun backupWorldToStream(
        output: java.io.OutputStream,
        onProgress: (String) -> Unit = {},
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!commandSender("save hold")) {
                throw IOException("Server is not running - start it first.")
            }
            onProgress("Freezing world (save hold)…")
            logStore.awaitLine({ it.text.contains("Saving") }, 8_000)

            val levelName = readLevelName()
            val files = queryFileList(onProgress)
            if (files.isEmpty()) {
                commandSender("save resume")
                throw IOException("save query returned no files; is the world loaded?")
            }

            val bdsDir = bdsDirProvider()
            val levelDir = File(bdsDir, "worlds/$levelName")
            val copied = mutableListOf<File>()
            for (save in files) {
                val f = resolvePath(save.path, levelDir, bdsDir) ?: continue
                copied.add(f)
            }
            if (copied.isEmpty()) {
                commandSender("save resume")
                throw IOException("None of the ${files.size} listed files could be found on disk.")
            }

            onProgress("Zipping ${copied.size} files…")
            ZipOutputStream(output.buffered()).use { zip ->
                for (file in copied) {
                    val entryName = try {
                        file.relativeToOrNull(levelDir)?.path ?: file.name
                    } catch (_: Exception) {
                        file.name
                    }
                    val entry = ZipEntry(entryName)
                    entry.time = file.lastModified()
                    zip.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            commandSender("save resume")
            onProgress("Done")
            copied.map { it.relativeToOrNull(bdsDir)?.path ?: it.name }
        }
    }

    private suspend fun queryFileList(onProgress: (String) -> Unit): List<LogParser.SaveFile> {
        val byPath = LinkedHashMap<String, LogParser.SaveFile>()
        for (attempt in 1..MAX_QUERY_ATTEMPTS) {
            onProgress("Querying save snapshot (attempt $attempt)…")
            commandSender("save query")
            while (true) {
                val line = logStore.awaitLine(
                    predicate = {
                        LogParser.parseSaveQueryFileLine(it.text) != null ||
                            LogParser.isSaveQueryDoneLine(it.text)
                    },
                    timeoutMs = 3_000,
                ) ?: break
                val file = LogParser.parseSaveQueryFileLine(line.text)
                if (file != null) byPath[file.path] = file
                if (LogParser.isSaveQueryDoneLine(line.text)) return byPath.values.toList()
            }
            delay(1_000) // save still in progress; retry
        }
        return byPath.values.toList()
    }

    private fun readLevelName(): String {
        val props = ServerPropertiesManager { File(bdsDirProvider(), "server.properties") }
        return props.read()["level-name"]?.takeIf { it.isNotBlank() } ?: "world"
    }

    private fun resolvePath(path: String, levelDir: File, bdsDir: File): File? {
        val cleaned = path.removePrefix("./").trimStart('/')
        val inLevel = File(levelDir, cleaned)
        if (inLevel.exists()) return inLevel
        val inRoot = File(bdsDir, cleaned)
        if (inRoot.exists()) return inRoot
        return null
    }

    companion object {
        const val MAX_QUERY_ATTEMPTS = 12
    }
}
