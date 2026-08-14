package com.polymatic.meshify.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

data class GeoPoint(val latitude: Double, val longitude: Double)
data class LosSample(val distanceMeters: Double, val terrainMeters: Double, val lineMeters: Double, val clearanceMeters: Double)
data class LosResult(
    val hasData: Boolean,
    val clear: Boolean,
    val distanceMeters: Double,
    val maxObstructionMeters: Double = 0.0,
    val firstObstructionMeters: Double? = null,
    val samples: List<LosSample> = emptyList(),
    val frequencyMHz: Double = 915.0,
    val error: String? = null,
)

/** Open-Meteo-backed terrain profile and radio line-of-sight calculator. */
class LineOfSightService {
    private data class CachedElevation(val elevation: Double, val savedAt: Long)
    private val elevationCache = mutableMapOf<String, CachedElevation>()

    suspend fun analyze(
        start: GeoPoint,
        end: GeoPoint,
        frequencyMHz: Double = 915.0,
        startAntennaMeters: Double = 1.5,
        endAntennaMeters: Double = 1.5,
    ): LosResult = withContext(Dispatchers.IO) {
        val distance = haversine(start, end)
        if (distance <= 1.0) return@withContext LosResult(true, true, distance, frequencyMHz = frequencyMHz)
        val count = when {
            distance < 25_000 -> 21
            distance < 100_000 -> 41
            else -> 81
        }
        val points = List(count) { index -> interpolate(start, end, index.toDouble() / (count - 1)) }
        val elevations = runCatching { elevations(points) }.getOrElse {
            return@withContext LosResult(false, false, distance, frequencyMHz = frequencyMHz, error = "Не удалось получить высоты")
        }
        if (elevations.any { it == null }) {
            return@withContext LosResult(false, false, distance, frequencyMHz = frequencyMHz, error = "Для части маршрута нет данных высот")
        }
        compute(points, elevations.filterNotNull(), distance, frequencyMHz, startAntennaMeters, endAntennaMeters)
    }

    internal fun compute(
        points: List<GeoPoint>,
        elevations: List<Double>,
        totalDistance: Double,
        frequencyMHz: Double,
        startAntennaMeters: Double = 1.5,
        endAntennaMeters: Double = 1.5,
    ): LosResult {
        require(points.size == elevations.size && points.size >= 2)
        val kFactor = (4.0 / 3.0) * sqrt(915.0 / frequencyMHz.coerceAtLeast(1.0))
        val effectiveRadius = EARTH_RADIUS_METERS * kFactor
        val lineStart = elevations.first() + startAntennaMeters
        val lineEnd = elevations.last() + endAntennaMeters
        var maxObstruction = 0.0
        var firstObstruction: Double? = null
        val samples = elevations.indices.map { index ->
            val fraction = index.toDouble() / (elevations.lastIndex)
            val distance = totalDistance * fraction
            val beam = lineStart + (lineEnd - lineStart) * fraction
            val earthBulge = distance * (totalDistance - distance) / (2.0 * effectiveRadius)
            val terrain = elevations[index] + earthBulge
            val clearance = beam - terrain
            if (index !in listOf(0, elevations.lastIndex) && clearance < 0) {
                val obstruction = -clearance
                if (obstruction > maxObstruction) maxObstruction = obstruction
                if (firstObstruction == null) firstObstruction = distance
            }
            LosSample(distance, terrain, beam, clearance)
        }
        return LosResult(true, maxObstruction <= 0.0, totalDistance, maxObstruction, firstObstruction, samples, frequencyMHz)
    }

    private fun elevations(points: List<GeoPoint>): List<Double?> {
        val now = System.currentTimeMillis()
        val output = MutableList<Double?>(points.size) { null }
        val missing = mutableListOf<Int>()
        points.forEachIndexed { index, point ->
            val cached = elevationCache[cacheKey(point)]
            if (cached != null && now - cached.savedAt < CACHE_TTL_MS) output[index] = cached.elevation else missing += index
        }
        if (missing.isEmpty()) return output
        missing.chunked(100).forEach { chunk ->
            val latitudes = chunk.joinToString(",") { points[it].latitude.toString() }
            val longitudes = chunk.joinToString(",") { points[it].longitude.toString() }
            val connection = URL("https://api.open-meteo.com/v1/elevation?latitude=$latitudes&longitude=$longitudes").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                if (connection.responseCode !in 200..299) return@forEach
                val values = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("elevation")
                chunk.forEachIndexed { resultIndex, pointIndex ->
                    val elevation = values?.optDouble(resultIndex, Double.NaN)?.takeIf { it.isFinite() }
                    output[pointIndex] = elevation
                    if (elevation != null) elevationCache[cacheKey(points[pointIndex])] = CachedElevation(elevation, now)
                }
            } finally {
                connection.disconnect()
            }
        }
        return output
    }

    private fun cacheKey(point: GeoPoint) = "%.4f,%.4f".format(java.util.Locale.US, point.latitude, point.longitude)
    private fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double) = GeoPoint(a.latitude + (b.latitude - a.latitude) * fraction, a.longitude + (b.longitude - a.longitude) * fraction)
    private fun haversine(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val h = sinLat * sinLat + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sinLon * sinLon
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h))
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val CACHE_TTL_MS = 24 * 60 * 60 * 1_000L
    }
}
