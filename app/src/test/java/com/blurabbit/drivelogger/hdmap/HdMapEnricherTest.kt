package com.blurabbit.drivelogger.hdmap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HdMapEnricherTest {

    @Test
    fun `normalizes lanes maxspeed and oneway tags`() {
        val seg = roadSegmentFromTags(
            42L,
            mapOf("highway" to "residential", "lanes" to "2", "maxspeed" to "30", "oneway" to "yes", "name" to "Main St"),
        )
        assertThat(seg.highway).isEqualTo("residential")
        assertThat(seg.lanes).isEqualTo(2)
        assertThat(seg.maxspeedKph).isEqualTo(30)
        assertThat(seg.oneway).isTrue()
        assertThat(seg.name).isEqualTo("Main St")
    }

    @Test
    fun `converts mph maxspeed to kph and tolerates missing tags`() {
        val seg = roadSegmentFromTags(7L, mapOf("highway" to "primary", "maxspeed" to "30 mph"))
        assertThat(seg.maxspeedKph).isEqualTo(48) // 30 mph ≈ 48 km/h
        assertThat(seg.lanes).isNull()
        assertThat(seg.oneway).isFalse()
        assertThat(seg.name).isNull()
    }

    @Test
    fun `bbox spans all points with padding, null when empty`() {
        val bbox = trackBbox(listOf(doubleArrayOf(10.0, 20.0), doubleArrayOf(11.0, 22.0)), padDeg = 0.001)!!
        assertThat(bbox[0]).isWithin(1e-9).of(9.999)   // minLat - pad
        assertThat(bbox[1]).isWithin(1e-9).of(19.999)  // minLon - pad
        assertThat(bbox[2]).isWithin(1e-9).of(11.001)  // maxLat + pad
        assertThat(bbox[3]).isWithin(1e-9).of(22.001)  // maxLon + pad
        assertThat(trackBbox(emptyList())).isNull()
    }
}
