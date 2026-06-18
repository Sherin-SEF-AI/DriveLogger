package com.blurabbit.drivelogger.upload

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Known-answer test against AWS's published Signature Version 4 example
 * (docs: "Examples of how to derive a signing key for Signature Version 4"). Using a fixed secret,
 * date, region, service, and string-to-sign, the derived signature must match AWS's documented value
 * — this locks our HMAC chain to the spec, independent of any okhttp request shaping.
 */
class AwsV4SignerTest {

    private val signer = AwsV4Signer(
        accessKey = "AKIDEXAMPLE",
        secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
        region = "us-east-1",
        service = "iam",
    )

    @Test
    fun `signature matches the published AWS SigV4 example`() {
        val stringToSign = buildString {
            append("AWS4-HMAC-SHA256\n")
            append("20150830T123600Z\n")
            append("20150830/us-east-1/iam/aws4_request\n")
            append("f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59")
        }
        val signingKey = signer.signatureKey("20150830")
        val signature = signer.hmac(signingKey, stringToSign).joinToString("") { "%02x".format(it) }

        assertThat(signature).isEqualTo("5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7")
    }
}
