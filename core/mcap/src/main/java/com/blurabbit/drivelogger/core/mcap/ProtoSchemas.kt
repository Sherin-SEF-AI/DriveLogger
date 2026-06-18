package com.blurabbit.drivelogger.core.mcap

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors

/**
 * Builds the MCAP protobuf Schema payload for a message type.
 *
 * MCAP's protobuf encoding requires `schema.data` to be a serialized
 * `google.protobuf.FileDescriptorSet` containing the message's `.proto` file **and all of its
 * transitive dependencies**. This is only possible with the full protobuf runtime (lite strips
 * descriptors) — hence :core:proto builds with `protobuf-java`, not `-javalite`.
 */
object ProtoSchemas {

    /** Fully-qualified message name used as the MCAP schema name, e.g. `foxglove.LocationFix`. */
    fun schemaName(descriptor: Descriptors.Descriptor): String = descriptor.fullName

    /** Serialized FileDescriptorSet bytes (the MCAP `schema.data` for `encoding = "protobuf"`). */
    fun fileDescriptorSet(descriptor: Descriptors.Descriptor): ByteArray {
        val ordered = LinkedHashMap<String, DescriptorProtos.FileDescriptorProto>()
        collect(descriptor.file, ordered)
        return DescriptorProtos.FileDescriptorSet.newBuilder()
            .addAllFile(ordered.values)
            .build()
            .toByteArray()
    }

    private fun collect(
        file: Descriptors.FileDescriptor,
        out: LinkedHashMap<String, DescriptorProtos.FileDescriptorProto>,
    ) {
        if (out.containsKey(file.name)) return
        // Dependencies must precede dependents in the set.
        for (dep in file.dependencies) collect(dep, out)
        out[file.name] = file.toProto()
    }
}
