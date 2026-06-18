# Protobuf generated messages use reflection on field/descriptor names.
-keep class com.blurabbit.drivelogger.proto.** { *; }
-keep class foxglove.** { *; }
-keep class com.google.protobuf.** { *; }

# Keep generated Hilt/Room components.
-keep class * extends androidx.room.RoomDatabase
-keep class dagger.hilt.** { *; }

# lz4-java probes native then falls back to pure-Java implementations by reflection.
-keep class net.jpountz.** { *; }
-dontwarn net.jpountz.**
