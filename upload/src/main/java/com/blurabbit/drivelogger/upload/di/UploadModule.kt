package com.blurabbit.drivelogger.upload.di

import com.blurabbit.drivelogger.domain.model.CloudProvider
import com.blurabbit.drivelogger.upload.AzureBlobProvider
import com.blurabbit.drivelogger.upload.CloudStorageProvider
import com.blurabbit.drivelogger.upload.S3Provider
import dagger.Binds
import dagger.MapKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Maps each [CloudProvider] to its implementation; the worker selects by the task's provider. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UploadBindingsModule {
    @Binds @IntoMap @ProviderKey(CloudProvider.AWS_S3)
    abstract fun s3(impl: S3Provider): CloudStorageProvider

    // MinIO uses the same S3 implementation (path-style toggled in CloudConfig).
    @Binds @IntoMap @ProviderKey(CloudProvider.MINIO)
    abstract fun minio(impl: S3Provider): CloudStorageProvider

    @Binds @IntoMap @ProviderKey(CloudProvider.AZURE_BLOB)
    abstract fun azure(impl: AzureBlobProvider): CloudStorageProvider
}

@Module
@InstallIn(SingletonComponent::class)
object UploadHttpModule {
    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

@MapKey
annotation class ProviderKey(val value: CloudProvider)
