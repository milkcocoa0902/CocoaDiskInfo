package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.agent.maintenance.PeriodicMaintenanceRunner
import com.milkcocoa.info.sapphire.agent.maintenance.StandaloneMaintenanceRunner
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Starts cleanup after application startup and cancels it during shutdown.
 * The first cleanup runs after [cleanupInterval]; a non-positive interval is rejected.
 */
fun Application.installStandaloneMaintenance(
    runner: StandaloneMaintenanceRunner,
    cleanupInterval: Duration,
) {
    require(cleanupInterval.isPositive()) {
        "cleanupInterval must be greater than zero."
    }

    var maintenanceJob: Job? = null
    monitor.subscribe(ApplicationStarted) { application ->
        if (maintenanceJob?.isActive == true) return@subscribe

        maintenanceJob = application.launch {
            while (isActive) {
                delay(cleanupInterval)
                runner.runCleanup()
            }
        }
    }
    monitor.subscribe(ApplicationStopping) {
        maintenanceJob?.cancel()
        maintenanceJob = null
    }
}

/**
 * Starts periodic maintenance for a generic runner with the same startup/shutdown lifecycle
 * as Standalone cleanup. The first run is delayed by [cleanupInterval].
 */
fun Application.installPeriodicMaintenance(
    runner: PeriodicMaintenanceRunner,
    cleanupInterval: Duration,
) {
    require(cleanupInterval.isPositive()) {
        "cleanupInterval must be greater than zero."
    }

    var maintenanceJob: Job? = null
    monitor.subscribe(ApplicationStarted) { application ->
        if (maintenanceJob?.isActive == true) return@subscribe

        maintenanceJob = application.launch {
            while (isActive) {
                delay(cleanupInterval)
                runner.runCleanup()
            }
        }
    }
    monitor.subscribe(ApplicationStopping) {
        maintenanceJob?.cancel()
        maintenanceJob = null
    }
}
