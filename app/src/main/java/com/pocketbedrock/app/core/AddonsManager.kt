package com.pocketbedrock.app.core

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Behavior/resource pack management: scan installed packs, install .mcaddon/.mcpack/.zip
 * via SAF, register/unregister packs in the world's world_behavior_packs.json /
 * world_resource_packs.json, warn on missing dependencies and malformed manifests.
 */
class AddonsManager(
    private val propsManager: ServerPropertiesManager,
    private val bdsDirProvider: () -> File,
) {
    enum class PackType(val folderName: String, val worldFile: String) {
        BEHAVIOR("behavior_packs", "world_behavior_packs.json"),
        RESOURCE("resource_packs", "world_resource_packs.json"),
    }

    @Serializable
    data class PackManifest(
        val name: String,
        val description: String? = null,
        val uuid: String,
        val version: List<Double> = emptyList(),
        val modules: List<Module> = emptyList(),
        val dependencies: List<Dependency> = emptyList(),
    ) {
        val versionString: String get() = formatVersion(version)
    }

    @Serializable
    data class Module(val type: String? = null, val uuid: String? = null, val version: List<Double>? = null)

    @Serializable
    data class Dependency(val uuid: String, val version: List<Double>? = null)

    @Serializable
    data class Registration(val pack_id: String, val version: List<Double> = listOf(1, 0, 0))

    data class PackInfo(
        val dir: File,
        val manifest: PackManifest?,
        val parseError: String?,
        val type: PackType,
        val registered: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun levelName(): String =
        propsManager.read()["level-name"]?.takeIf { it.isNotBlank() } ?: "world"

    fun worldDir(): File = File(File(bdsDirProvider(), "worlds"), levelName())

    fun packsRoot(type: PackType): File = File(bdsDirProvider(), type.folderName)

    /** Every installed pack (both types), including malformed ones with an error message. */
    fun listPacks(): List<PackInfo> {
        val registered = PackType.entries
            .flatMap { registrations(it) }
            .map { it.pack_id }
            .toSet()
        return PackType.entries.flatMap { type ->
            val root = packsRoot(type)
            root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
                val manifestFile = File(dir, "manifest.json")
                val (manifest, error) = parseManifest(manifestFile)
                PackInfo(
                    dir = dir,
                    manifest = manifest,
                    parseError = error,
                    type = type,
                    registered = manifest != null && manifest.uuid in registered,
                )
            } ?: emptyList()
        }.sortedWith(compareBy({ it.manifest?.name ?: it.dir.name }, { it.type.name }))
    }

    private fun parseManifest(file: File): Pair<PackManifest?, String?> {
        if (!file.exists()) return null to "manifest.json not found in this folder"
        return try {
            val manifest = json.decodeFromString(PackManifest.serializer(), file.readText())
            if (manifest.uuid.isBlank()) {
                null to "Manifest has no uuid"
            } else {
                manifest to null
            }
        } catch (e: Exception) {
            null to "Malformed manifest.json: ${e.message?.take(160)}"
        }
    }

    fun registrations(type: PackType): List<Registration> {
        val file = File(worldDir(), type.worldFile)
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(Registration.serializer()), file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeRegistrations(type: PackType, list: List<Registration>) {
        val file = File(worldDir(), type.worldFile)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ListSerializer(Registration.serializer()), list))
    }

    fun register(pack: PackInfo) {
        if (pack.manifest == null) return
        val list = registrations(pack.type).filterNot { it.pack_id == pack.manifest.uuid }
        writeRegistrations(
            pack.type,
            list + Registration(pack.manifest.uuid, pack.manifest.version.ifEmpty { listOf(1, 0, 0) }),
        )
    }

    fun unregister(pack: PackInfo) {
        if (pack.manifest == null) return
        writeRegistrations(
            pack.type,
            registrations(pack.type).filterNot { it.pack_id == pack.manifest.uuid },
        )
    }

    /** Enable/disable without deleting files. */
    fun setEnabled(pack: PackInfo, enabled: Boolean) {
        if (enabled) register(pack) else unregister(pack)
    }

    fun deletePack(pack: PackInfo) {
        unregister(pack)
        pack.dir.deleteRecursively()
    }

    /** UUIDs referenced by behavior-pack dependencies but not present among installed packs. */
    fun missingDependencies(packs: List<PackInfo>): List<String> {
        val installed = packs.mapNotNull { it.manifest?.uuid }.toSet()
        return packs.flatMap { pack ->
            pack.manifest?.dependencies?.map { it.uuid } ?: emptyList()
        }.filter { it !in installed }.distinct()
    }

    /**
     * Install a .mcaddon / .mcpack / .zip picked with SAF. A .mcaddon may contain
     * several packs (one folder each). Returns the installed pack infos.
     */
    suspend fun install(uri: Uri, resolver: ContentResolver): Result<List<PackInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val workDir = bdsDirProvider().parentFile ?: bdsDirProvider()
                val staging = File(workDir, "addon-${System.currentTimeMillis()}.zip")
                val extractDir = File(workDir, "extract-${System.currentTimeMillis()}")
                extractDir.mkdirs()
                try {
                    resolver.openInputStream(uri)?.use { input ->
                        staging.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("Could not read the selected file")
                    ZipInputStream(staging.inputStream().buffered(), Charsets.UTF_8).use { zin ->
                        while (true) {
                            val entry = zin.nextEntry ?: break
                            val out = File(extractDir, entry.name)
                            if (!out.canonicalPath.startsWith(extractDir.canonicalPath + File.separator)) continue
                            if (entry.isDirectory) out.mkdirs() else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zin.copyTo(it) }
                            }
                            zin.closeEntry()
                        }
                    }

                    val packDirs = findPackDirs(extractDir)
                    if (packDirs.isEmpty()) throw IllegalStateException("No manifest.json found in the archive")
                    val installed = mutableListOf<PackInfo>()
                    for (dir in packDirs) {
                        val (manifest, error) = parseManifest(File(dir, "manifest.json"))
                        if (manifest == null) {
                            throw IllegalStateException("Invalid pack (${dir.name}): $error")
                        }
                        val type = typeFor(manifest)
                        val target = File(packsRoot(type), sanitize(dir.name.ifBlank { manifest.name }))
                        target.parentFile?.mkdirs()
                        if (target.exists()) target.deleteRecursively()
                        if (!dir.renameTo(target)) throw IllegalStateException("Could not move pack into ${type.folderName}")
                        installed.add(
                            PackInfo(target, manifest, null, type, registered = false),
                        )
                    }
                    installed
                } finally {
                    staging.delete()
                    extractDir.deleteRecursively()
                }
            }
        }

    private fun findPackDirs(root: File): List<File> {
        val result = mutableListOf<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > 4) return
            if (File(dir, "manifest.json").exists()) {
                result.add(dir)
                return
            }
            dir.listFiles()?.filter { it.isDirectory }?.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return result
    }

    private fun typeFor(manifest: PackManifest): PackType {
        val moduleTypes = manifest.modules.mapNotNull { it.type?.lowercase() }
        return when {
            moduleTypes.any { it == "data" || it == "script" || it == "client_data" } -> PackType.BEHAVIOR
            moduleTypes.any { it == "resources" } -> PackType.RESOURCE
            else -> PackType.BEHAVIOR
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "pack" }

    companion object {
        fun formatVersion(v: List<Double>): String =
            if (v.isEmpty()) "?" else v.joinToString(".") { d -> if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString() }
    }
}
