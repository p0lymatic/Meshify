package com.polymatic.meshify.firmware

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.File

private const val CATALOG_URL = "https://flasher.meshcore.io/releases/"
private const val CATALOG_ORIGIN = "https://flasher.meshcore.io"

enum class FirmwareRole(val wireValue: String, val russianLabel: String, val englishLabel: String) {
    CompanionFirmware("companion", "Companion", "Companion"),
    Repeater("repeater", "Репитер", "Repeater"),
    RoomServer("room-server", "Room server", "Room server");

    companion object {
        fun fromWire(value: String): FirmwareRole? {
            val normalized = value.trim().lowercase().replace('_', '-').replace(' ', '-')
            return when {
                normalized.contains("companion") -> CompanionFirmware
                normalized.contains("repeater") -> Repeater
                normalized == "room" || normalized.contains("room-server") || normalized.contains("roomserver") -> RoomServer
                else -> null
            }
        }
    }
}

data class FirmwareRelease(
    val version: String,
    val role: FirmwareRole,
    val name: String,
    val notes: String,
    val files: List<FirmwareFile>,
)

data class FirmwareFile(val name: String, val url: String) {
    val isMergedEspImage: Boolean get() = name.endsWith("-merged.bin", ignoreCase = true)
    val isApplicationImage: Boolean get() = name.endsWith(".bin", ignoreCase = true) && !isMergedEspImage
    val absoluteUrl: String
        get() = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "$CATALOG_ORIGIN/${url.trimStart('/')}"
        }
}

data class FirmwareImage(val release: FirmwareRelease, val file: FirmwareFile, val board: String) {
    val searchText: String get() = "$board ${release.role.wireValue} ${release.version} ${release.name}".lowercase()
}

/** Parses the public MeshCore flasher catalogue and exposes only flashable ESP binary variants. */
object FirmwareCatalog {
    fun parse(json: String): List<FirmwareRelease> = runCatching {
        val root = JsonParser.parseString(json)
        val releases = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> {
                val rootObject = root.asJsonObject
                rootObject.getAsJsonArray("releases")
                    ?: rootObject.getAsJsonArray("items")
                    ?: com.google.gson.JsonArray().apply {
                        if (rootObject.has("version") && rootObject.has("type")) add(rootObject)
                    }
            }
            else -> com.google.gson.JsonArray()
        }
        releases.mapNotNull { releaseElement ->
            val release = releaseElement.asJsonObject
            val role = FirmwareRole.fromWire(release.string("type")) ?: return@mapNotNull null
            val version = release.string("version")
            if (version.isBlank()) return@mapNotNull null
            val files = release.getAsJsonArray("files")?.mapNotNull { fileElement ->
                val file = fileElement.asJsonObject
                val name = file.string("name")
                val url = file.string("url")
                if (name.isBlank() || url.isBlank()) null else FirmwareFile(name, url)
            }.orEmpty()
            FirmwareRelease(version, role, release.string("name"), release.string("notes"), files)
        }
    }.getOrDefault(emptyList())

    fun latestMergedImages(releases: List<FirmwareRelease>, role: FirmwareRole?, query: String): List<FirmwareImage> {
        val normalizedQuery = query.trim().lowercase()
        return releases.asSequence()
            .filter { role == null || it.role == role }
            .flatMap { release -> release.files.asSequence().filter(FirmwareFile::isMergedEspImage).map { file -> FirmwareImage(release, file, boardName(file.name, release)) } }
            .filter { normalizedQuery.isBlank() || it.searchText.contains(normalizedQuery) }
            .groupBy { "${it.release.role.wireValue}:${it.board.lowercase()}" }
            .mapNotNull { (_, images) -> images.maxWithOrNull(compareBy<FirmwareImage> { semanticVersion(it.release.version) }.thenBy { it.file.name }) }
            .sortedWith(compareBy<FirmwareImage> { it.board.lowercase() }.thenBy { it.release.role.ordinal })
            .toList()
    }

    /** Returns every ESP merged image, newest release first, for the firmware update browser. */
    fun mergedImages(releases: List<FirmwareRelease>, role: FirmwareRole?, query: String, newerThan: String? = null): List<FirmwareImage> {
        return images(releases, role, query, newerThan, fullReflash = true)
    }

    /** Regular images are exposed only when a matching merged ESP image proves this board is ESP-flashable. */
    fun images(releases: List<FirmwareRelease>, role: FirmwareRole?, query: String, newerThan: String? = null, fullReflash: Boolean): List<FirmwareImage> {
        val normalizedQuery = query.trim().lowercase()
        val installedVersion = newerThan?.let(::semanticVersion)
        return releases.asSequence()
            .filter { role == null || it.role == role }
            .flatMap { release ->
                val mergedBoards = release.files.asSequence()
                    .filter(FirmwareFile::isMergedEspImage)
                    .map { boardName(it.name, release).lowercase() }
                    .toSet()
                release.files.asSequence()
                    .filter { if (fullReflash) it.isMergedEspImage else it.isApplicationImage }
                    .map { file -> FirmwareImage(release, file, boardName(file.name, release)) }
                    .filter { image -> fullReflash || image.board.lowercase() in mergedBoards }
            }
            .filter { normalizedQuery.isBlank() || it.searchText.contains(normalizedQuery) }
            .filter { installedVersion == null || semanticVersion(it.release.version) > installedVersion }
            .sortedWith(
                compareByDescending<FirmwareImage> { semanticVersion(it.release.version) }
                    .thenBy { it.board.lowercase() }
                    .thenBy { it.release.role.ordinal },
            )
            .toList()
    }

    fun boardName(fileName: String, release: FirmwareRelease): String {
        val withoutExtension = fileName.removeSuffix("-merged.bin")
        val roleMarkers = when (release.role) {
            FirmwareRole.CompanionFirmware -> listOf("_companion")
            FirmwareRole.Repeater -> listOf("_repeater")
            FirmwareRole.RoomServer -> listOf("_room_server", "_room-server", "_roomserver")
        }
        val normalizedName = withoutExtension.lowercase()
        val markerIndex = roleMarkers.asSequence()
            .map { normalizedName.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: -1
        val rawBoard = if (markerIndex >= 0) withoutExtension.substring(0, markerIndex) else withoutExtension
        return rawBoard
            .replace('_', ' ')
            .trim()
    }

    private fun semanticVersion(version: String): Long = (Regex("""v?(\d+(?:\.\d+){0,2})""").find(version)?.groupValues?.get(1) ?: "0")
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
        .let { it + List((3 - it.size).coerceAtLeast(0)) { 0 } }
        .take(3)
        .let { (it[0].toLong() shl 40) + (it[1].toLong() shl 20) + it[2] }

    private fun com.google.gson.JsonObject.string(name: String): String = get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
}

class FirmwareCatalogRepository {
    suspend fun load(): List<FirmwareRelease> = withContext(Dispatchers.IO) {
        val connection = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            FirmwareCatalog.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(file: FirmwareFile, destinationDirectory: File, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        destinationDirectory.mkdirs()
        val target = File(destinationDirectory, file.name)
        val partial = File(destinationDirectory, "${file.name}.part")
        val connection = (URL(file.absoluteUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            requestMethod = "GET"
        }
        try {
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            var read = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        onProgress(read, total)
                    }
                }
            }
            if (target.exists()) target.delete()
            require(partial.renameTo(target)) { "Не удалось сохранить прошивку" }
            target
        } finally {
            connection.disconnect()
        }
    }
}
