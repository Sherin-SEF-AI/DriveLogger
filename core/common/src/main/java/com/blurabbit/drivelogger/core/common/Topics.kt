package com.blurabbit.drivelogger.core.common

/**
 * Canonical MCAP topic names. Single source of truth shared by sensor sources, the MCAP
 * writer, the event engine, and downstream tooling. Adding a new sensor means adding a
 * constant here plus a [Topics] entry — no other module needs to change.
 */
object Topics {
    const val CAMERA_FRONT = "/camera/front"
    const val CAMERA_FRONT_VIDEO = "/camera/front/video"
    const val CAMERA_REAR = "/camera/rear"
    const val GPS_FIX = "/gps/fix"
    const val GNSS_RAW = "/gps/raw"
    const val IMU_ACCEL = "/imu/accelerometer"
    const val IMU_GYRO = "/imu/gyroscope"
    const val IMU_MAG = "/imu/magnetometer"
    const val IMU_ROTATION = "/imu/rotation"
    const val IMU_GRAVITY = "/imu/gravity"
    const val IMU_LINEAR_ACCEL = "/imu/linear_acceleration"
    const val ENVIRONMENT = "/environment"
    const val DEVICE_TELEMETRY = "/device/telemetry"
    const val EVENTS = "/events"

    // Reserved for the future AI annotation pipeline (designed-in, not yet produced).
    const val ANNOTATIONS = "/annotations"
    const val OBJECTS = "/objects"
    const val TRACKS = "/tracks"
}
