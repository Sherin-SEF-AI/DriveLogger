package com.blurabbit.drivelogger.recording

/**
 * Audio timing math. Each `/audio/microphone` chunk carries an anchor `(sample_index, unified_ns)`
 * sourced from the audio HAL clock. Fitting a line through the anchors gives jitter-free,
 * sample-accurate alignment: `unified_ns(sample) = slope*sample + intercept`. The measured sample
 * rate is `1e9 / slope` (the true rate drifts slightly from the nominal 16000 Hz).
 */

/** Ordinary least-squares fit of y(=unified_ns) on x(=sample_index). Returns (slopeNsPerSample, interceptNs). */
internal fun leastSquaresFit(anchors: List<LongArray>): Pair<Double, Double>? {
    if (anchors.size < 2) return null
    val n = anchors.size.toDouble()
    val mx = anchors.sumOf { it[0].toDouble() } / n
    val my = anchors.sumOf { it[1].toDouble() } / n
    var sxx = 0.0; var sxy = 0.0
    for (a in anchors) {
        val dx = a[0] - mx; val dy = a[1] - my
        sxx += dx * dx; sxy += dx * dy
    }
    if (sxx == 0.0) return null
    val slope = sxy / sxx
    return slope to (my - slope * mx)
}

/**
 * Builds the self-describing `audio.wav.json` sidecar: nominal + measured sample rate, the
 * device→wall offset, the wall time of sample 0, and the raw HAL anchors. A consumer can map any
 * sample to wall time with `slope*sample + intercept + offset`, surviving without the MCAP.
 *
 * @param toEpochNs maps a unified (boottime) ns value to wall-epoch ns (MonotonicClock.toEpochNanos).
 */
internal fun buildAudioSidecarJson(
    anchors: List<LongArray>,
    nominalRateHz: Int,
    channels: Int,
    toEpochNs: (Long) -> Long,
): String? {
    val (slope, intercept) = leastSquaresFit(anchors) ?: return null
    val measuredRate = if (slope > 0) 1e9 / slope else nominalRateHz.toDouble()
    val offsetNs = toEpochNs(0L)               // device(boottime) → wall epoch offset
    val startWallNs = toEpochNs(intercept.toLong())  // wall time of sample 0
    return buildString {
        append("{\"schema_version\":1,")
        append("\"encoding\":\"pcm_s16le\",")
        append("\"channels\":").append(channels).append(",")
        append("\"nominal_sample_rate\":").append(nominalRateHz).append(",")
        append("\"measured_sample_rate\":").append(measuredRate).append(",")
        append("\"anchor_timebase\":\"boottime_ns\",")
        append("\"device_to_wall_offset_ns\":").append(offsetNs).append(",")
        append("\"start_wall_ns\":").append(startWallNs).append(",")
        append("\"anchors\":[")
        anchors.forEachIndexed { i, a ->
            if (i > 0) append(",")
            append("[").append(a[0]).append(",").append(a[1]).append("]")
        }
        append("]}")
    }
}
