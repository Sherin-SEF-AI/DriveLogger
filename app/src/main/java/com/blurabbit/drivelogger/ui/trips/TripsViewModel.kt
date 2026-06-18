package com.blurabbit.drivelogger.ui.trips

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blurabbit.drivelogger.domain.model.Trip
import com.blurabbit.drivelogger.domain.model.TripProfile
import com.blurabbit.drivelogger.domain.usecase.CreateTripUseCase
import com.blurabbit.drivelogger.domain.usecase.DeleteTripUseCase
import com.blurabbit.drivelogger.domain.usecase.ObserveTripsUseCase
import com.blurabbit.drivelogger.recording.RecordingForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TripsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    observeTrips: ObserveTripsUseCase,
    private val createTrip: CreateTripUseCase,
    private val deleteTrip: DeleteTripUseCase,
) : ViewModel() {

    val trips: StateFlow<List<Trip>> =
        observeTrips().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Create a trip with metadata and immediately begin recording it. */
    fun createAndStart(profile: TripProfile) {
        viewModelScope.launch {
            val now = android.os.SystemClock.elapsedRealtimeNanos()
            val trip = createTrip(profile, now, System.currentTimeMillis())
            RecordingForegroundService.start(context, trip.id)
        }
    }

    fun delete(id: String) = viewModelScope.launch { deleteTrip(id) }
}
