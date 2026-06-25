package com.blurabbit.drivelogger.domain.repository

import com.blurabbit.drivelogger.domain.model.CloudProvider

/**
 * Seam that lets the recorder kick off background uploads without depending on the :upload module.
 * Implemented in :upload (WorkManager + credential-backed); injected where auto-upload is triggered.
 */
interface UploadTrigger {
    /** The provider to auto-upload to, or null if no cloud credentials are configured. */
    fun defaultProvider(): CloudProvider?

    /** Enqueue the upload worker (idempotent; honors network/charging constraints). */
    fun schedule()
}
