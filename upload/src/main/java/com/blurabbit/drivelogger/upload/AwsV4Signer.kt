package com.blurabbit.drivelogger.upload

import okhttp3.HttpUrl
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal AWS Signature Version 4 (header auth) for the S3 REST API — also drives MinIO and other
 * S3-compatible stores. Uses `x-amz-content-sha256: UNSIGNED-PAYLOAD` so large multipart parts are
 * never hashed in memory (safe because uploads run over TLS).
 */
class AwsV4Signer(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
    private val service: String = "s3",
) {
    /** Returns a signed copy of [request] (adds Authorization + x-amz-* headers). */
    fun sign(request: Request, nowMillis: Long): Request {
        val amzDate = AMZ_DATE.get().format(Date(nowMillis))
        val dateStamp = DATE_STAMP.get().format(Date(nowMillis))
        val url = request.url
        val payloadHash = UNSIGNED_PAYLOAD

        val host = url.host + if (isDefaultPort(url)) "" else ":${url.port}"
        val headers = sortedMapOf(
            "host" to host,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate,
        )

        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val signedHeaders = headers.keys.joinToString(";")
        val canonicalRequest = buildString {
            append(request.method).append('\n')
            append(canonicalUri(url)).append('\n')
            append(canonicalQuery(url)).append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(payloadHash)
        }

        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = buildString {
            append("AWS4-HMAC-SHA256\n")
            append(amzDate).append('\n')
            append(scope).append('\n')
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }

        val signingKey = signatureKey(dateStamp)
        val signature = hmac(signingKey, stringToSign).toHex()
        val authorization =
            "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"

        return request.newBuilder()
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
            .header("Authorization", authorization)
            .build()
    }

    private fun isDefaultPort(url: HttpUrl): Boolean =
        (url.scheme == "https" && url.port == 443) || (url.scheme == "http" && url.port == 80)

    private fun canonicalUri(url: HttpUrl): String =
        url.encodedPathSegments.joinToString("/", prefix = "/") { uriEncode(it, false) }

    private fun canonicalQuery(url: HttpUrl): String {
        if (url.querySize == 0) return ""
        val params = (0 until url.querySize).map { url.queryParameterName(it) to (url.queryParameterValue(it) ?: "") }
        return params.sortedBy { it.first }
            .joinToString("&") { "${uriEncode(it.first, true)}=${uriEncode(it.second, true)}" }
    }

    private fun signatureKey(dateStamp: String): ByteArray {
        val kDate = hmac("AWS4$secretKey".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        return hmac(kService, "aws4_request")
    }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(data.toByteArray(Charsets.UTF_8))
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun uriEncode(input: String, encodeSlash: Boolean): String = buildString {
        for (b in input.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            when {
                c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                    c == '_'.code || c == '-'.code || c == '~'.code || c == '.'.code -> append(c.toChar())
                c == '/'.code && !encodeSlash -> append('/')
                else -> append("%%%02X".format(c))
            }
        }
    }

    companion object {
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
        private val AMZ_DATE = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
        private val DATE_STAMP = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
        fun sha256Hex(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
