package com.blurabbit.drivelogger.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blurabbit.drivelogger.domain.model.TripProfile
import com.blurabbit.drivelogger.domain.usecase.CreateTripUseCase
import com.blurabbit.drivelogger.recording.RecordingController
import com.blurabbit.drivelogger.recording.RecordingForegroundService
import com.blurabbit.drivelogger.recording.RecordingPhase
import com.blurabbit.drivelogger.recording.RecordingState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    recordingController: RecordingController,
    private val createTrip: CreateTripUseCase,
) : ViewModel() {

    val state: StateFlow<RecordingState> = recordingController.state

    /** Quick-start a trip with a blank profile (full metadata is editable from Trips). */
    fun start() {
        viewModelScope.launch {
            val now = android.os.SystemClock.elapsedRealtimeNanos()
            val trip = createTrip(TripProfile(name = "Quick trip"), now, System.currentTimeMillis())
            RecordingForegroundService.start(context, trip.id)
        }
    }

    fun pause() = RecordingForegroundService.action(context, RecordingForegroundService.ACTION_PAUSE)
    fun resume() = RecordingForegroundService.action(context, RecordingForegroundService.ACTION_RESUME)
    fun stop() = RecordingForegroundService.action(context, RecordingForegroundService.ACTION_STOP)

    fun isRecording(state: RecordingState) = state.phase == RecordingPhase.RECORDING
    fun isPaused(state: RecordingState) = state.phase == RecordingPhase.PAUSED
}
