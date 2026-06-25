package com.blurabbit.drivelogger.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.blurabbit.drivelogger.domain.model.CloudProvider
import com.blurabbit.drivelogger.domain.repository.UploadTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-backed [UploadTrigger]. Lets the recorder (in :recording) start uploads without a
 * compile dependency on :upload — it talks to the domain interface; this impl is wired by Hilt.
 */
@Singleton
class WorkManagerUploadTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: CredentialStore,
) : UploadTrigger {

    /** First provider that has saved credentials (S3 → MinIO → Azure), else null. */
    override fun defaultProvider(): CloudProvider? =
        listOf(CloudProvider.AWS_S3, CloudProvider.MINIO, CloudProvider.AZURE_BLOB)
            .firstOrNull { credentials.load(it) != null }

    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UploadWorker.UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
