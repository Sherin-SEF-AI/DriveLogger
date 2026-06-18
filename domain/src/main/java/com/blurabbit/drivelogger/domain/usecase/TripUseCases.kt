package com.blurabbit.drivelogger.domain.usecase

import com.blurabbit.drivelogger.domain.model.Trip
import com.blurabbit.drivelogger.domain.model.TripProfile
import com.blurabbit.drivelogger.domain.model.TripStatus
import com.blurabbit.drivelogger.domain.repository.TripRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveTripsUseCase @Inject constructor(private val repo: TripRepository) {
    operator fun invoke(): Flow<List<Trip>> = repo.observeTrips()
}

class CreateTripUseCase @Inject constructor(private val repo: TripRepository) {
    suspend operator fun invoke(profile: TripProfile, startElapsedNs: Long, startWallMs: Long): Trip =
        repo.createTrip(profile, startElapsedNs, startWallMs)
}

class SetTripStatusUseCase @Inject constructor(private val repo: TripRepository) {
    suspend operator fun invoke(id: String, status: TripStatus, endWallMs: Long? = null) =
        repo.updateStatus(id, status, endWallMs)
}

class DeleteTripUseCase @Inject constructor(private val repo: TripRepository) {
    suspend operator fun invoke(id: String) = repo.delete(id)
}
