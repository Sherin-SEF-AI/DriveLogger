package com.blurabbit.drivelogger.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blurabbit.drivelogger.domain.repository.UploadTrigger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * On device boot, finalize any trip interrupted by a shutdown and resume pending uploads. Does NOT
 * auto-start recording (left to the operator / a future fleet flag).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var recovery: RecoveryManager
    @Inject lateinit var retention: RetentionManager
    @Inject lateinit var uploadTrigger: UploadTrigger

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                recovery.recoverInterruptedTrips()
                retention.sweep()
                uploadTrigger.schedule()
            } finally {
                pending.finish()
            }
        }
    }
}
