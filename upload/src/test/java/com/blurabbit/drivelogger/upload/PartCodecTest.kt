package com.blurabbit.drivelogger.upload

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PartCodecTest {

    @Test
    fun `round-trips parts including quoted etags and base64 checksums`() {
        val parts = listOf(
            PartRef(1, "\"abc123def456\"", 8 * 1024 * 1024, "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg="),
            PartRef(2, "deadbeef-2", 5 * 1024 * 1024, null),
        )
        val decoded = PartCodec.decode(PartCodec.encode(parts))

        assertThat(decoded).hasSize(2)
        // ETag quotes are stripped on encode (re-added by the complete() XML).
        assertThat(decoded[0]).isEqualTo(PartRef(1, "abc123def456", 8 * 1024 * 1024, "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg="))
        assertThat(decoded[1]).isEqualTo(PartRef(2, "deadbeef-2", 5 * 1024 * 1024, null))
    }

    @Test
    fun `empty and null encode to an empty list`() {
        assertThat(PartCodec.encode(emptyList())).isEmpty()
        assertThat(PartCodec.decode("")).isEmpty()
        assertThat(PartCodec.decode(null)).isEmpty()
    }

    @Test
    fun `detects an S3 error body even on an HTTP 200 response`() {
        val errorBody = "<?xml version=\"1.0\"?><Error><Code>InternalError</Code><Message>boom</Message></Error>"
        val successBody = "<?xml version=\"1.0\"?><CompleteMultipartUploadResult><ETag>\"xyz\"</ETag></CompleteMultipartUploadResult>"

        assertThat(isS3Error(errorBody)).isTrue()
        assertThat(isS3Error(successBody)).isFalse()
    }
}
