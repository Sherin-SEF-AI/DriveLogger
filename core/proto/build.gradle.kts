import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.blurabbit.drivelogger.core.proto"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java")     // full java runtime — exposes Descriptors at runtime for MCAP schemas
                id("kotlin")   // kotlin DSL builders
            }
        }
    }
}

dependencies {
    api(libs.protobuf.java)
    api(libs.protobuf.kotlin)
}
