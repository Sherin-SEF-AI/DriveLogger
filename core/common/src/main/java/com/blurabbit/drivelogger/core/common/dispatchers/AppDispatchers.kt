package com.blurabbit.drivelogger.core.common.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Injectable dispatcher bundle so coroutine code never references [Dispatchers] directly —
 * makes every component deterministically testable with [kotlinx.coroutines.test] dispatchers.
 */
data class AppDispatchers(
    val main: CoroutineDispatcher = Dispatchers.Main,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val io: CoroutineDispatcher = Dispatchers.IO,
)

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Singleton
class DefaultAppDispatchers @Inject constructor() {
    fun provide(): AppDispatchers = AppDispatchers()
}
