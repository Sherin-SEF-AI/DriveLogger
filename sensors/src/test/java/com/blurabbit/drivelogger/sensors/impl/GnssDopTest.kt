package com.blurabbit.drivelogger.sensors.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GnssDopTest {

    @Test
    fun `parses DOP from a legacy GPGSA sentence`() {
        val dop = parseGsaDop("\$GPGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1*39")
        assertThat(dop).isNotNull()
        assertThat(dop!!.first).isEqualTo(2.5)   // PDOP
        assertThat(dop.second).isEqualTo(1.3)    // HDOP
        assertThat(dop.third).isEqualTo(2.1)     // VDOP
    }

    @Test
    fun `parses DOP from an NMEA 4_10 GNGSA sentence with a trailing system id`() {
        val dop = parseGsaDop("\$GNGSA,A,3,80,71,73,,,,,,,,,,2.1,1.1,1.8,4*3A")
        assertThat(dop).isEqualTo(Triple(2.1, 1.1, 1.8))
    }

    @Test
    fun `rejects non-GSA and malformed sentences`() {
        assertThat(parseGsaDop("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M*47")).isNull()
        assertThat(parseGsaDop("\$GPGSA,A,1")).isNull()
        assertThat(parseGsaDop("garbage")).isNull()
    }
}
