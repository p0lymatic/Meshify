package com.polymatic.meshify.media

import java.io.File
import java.security.MessageDigest

enum class PackInstallState { NotInstalled, Downloading, Installed, Error }

data class CatalogPack(val type: String, val id: String, val name: String, val version: Int, val manifestUrl: String, val manifestSha256: String, val sizeBytes: Long, val cover: String? = null)
data class PackInstall(val pack: CatalogPack, val state: PackInstallState, val installedBytes: Long = 0, val error: String? = null)

/** Disk-only cache policy: callers must explicitly invoke downloads; rendering never does network IO. */
class PackCache(private val root: File) {
    fun packDirectory(type: String, id: String): File = File(root, "$type/$id")
    fun isInstalled(pack: CatalogPack): Boolean = File(packDirectory(pack.type, pack.id), "manifest.json").isFile
    fun delete(pack: CatalogPack): Boolean = packDirectory(pack.type, pack.id).deleteRecursively()
    fun clear(): Boolean = root.deleteRecursively()
    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun verify(file: File, expectedSha256: String): Boolean = sha256(file).equals(expectedSha256, ignoreCase = true)

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
