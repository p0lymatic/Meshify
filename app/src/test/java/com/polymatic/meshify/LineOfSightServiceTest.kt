package com.polymatic.meshify

import com.polymatic.meshify.map.GeoPoint
import com.polymatic.meshify.map.LineOfSightService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineOfSightServiceTest {
    private val points = listOf(
        GeoPoint(55.0, 37.0),
        GeoPoint(55.0, 37.05),
        GeoPoint(55.0, 37.10),
    )

    @Test
    fun flatTerrainHasLineOfSight() {
        val result = LineOfSightService().compute(
            points = points,
            elevations = listOf(100.0, 100.0, 100.0),
            totalDistance = 10_000.0,
            frequencyMHz = 915.0,
            startAntennaMeters = 10.0,
            endAntennaMeters = 10.0,
        )

        assertTrue(result.hasData)
        assertTrue(result.clear)
    }

    @Test
    fun terrainAboveRadioPathBlocksLineOfSight() {
        val result = LineOfSightService().compute(
            points = points,
            elevations = listOf(100.0, 160.0, 100.0),
            totalDistance = 10_000.0,
            frequencyMHz = 915.0,
            startAntennaMeters = 10.0,
            endAntennaMeters = 10.0,
        )

        assertTrue(result.hasData)
        assertFalse(result.clear)
        assertTrue(result.maxObstructionMeters > 0.0)
        assertTrue(result.firstObstructionMeters != null)
    }
}
