package com.blurabbit.drivelogger

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.blurabbit.drivelogger.recording.RecoveryManager
import com.blurabbit.drivelogger.recording.RetentionManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class DriveLoggerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var recoveryManager: RecoveryManager
    @Inject lateinit var retentionManager: RetentionManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Finalize any trip interrupted by a crash/kill (rebuild MCAP summary, mark STOPPED),
        // then reclaim space from older, fully-uploaded trips.
        appScope.launch {
            recoveryManager.recoverInterruptedTrips()
            retentionManager.sweep()
        }
    }
}
