package com.blurabbit.drivelogger.export

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipInputStream

class TripExporterTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `zip contains one entry per input file keyed by name`() {
        val a = tmp.newFile("trip.mcap").apply { writeText("mcap-bytes") }
        val b = tmp.newFile("metadata.json").apply { writeText("{}") }
        val dest = tmp.newFile("out.zip")

        writeZip(listOf(a, b), dest)

        val names = mutableListOf<String>()
        ZipInputStream(dest.inputStream()).use { zin ->
            var e = zin.nextEntry
            while (e != null) { names += e.name; e = zin.nextEntry }
        }
        assertThat(names).containsExactly("trip.mcap", "metadata.json")
    }
}
