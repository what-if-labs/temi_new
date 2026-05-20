package com.example.temicontroller.patrol

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * A waypoint in a patrol route.
 *
 * @param locationName Name of the location as defined on the TEMI robot map
 * @param dwellTimeMs Time to wait after arriving at the location (ms)
 * @param scanDurationMs Time to actively scan/detect at this location (ms)
 */
data class PatrolWaypoint(
    val locationName: String,
    val dwellTimeMs: Long = 30_000,     // Wait time after arrival
    val scanDurationMs: Long = 60_000   // Detection/scan duration
)

/**
 * A patrol route consisting of ordered waypoints.
 *
 * @param id Unique route identifier
 * @param name Human-readable route name
 * @param waypoints Ordered list of waypoints to navigate
 * @param repeatCount Number of times to repeat (-1 = infinite loop)
 */
data class PatrolRoute(
    val id: String,
    val name: String,
    val waypoints: List<PatrolWaypoint>,
    val repeatCount: Int = -1  // -1 = infinite
)

/**
 * Status of the patrol controller.
 */
enum class PatrolStatus {
    IDLE,           // Not started
    NAVIGATING,     // Moving to next waypoint
    SCANNING,       // At waypoint, running detection
    PAUSED,         // Manually paused
    COMPLETED       // All waypoints completed
}

/**
 * Listener interface for patrol status changes.
 */
interface PatrolListener {
    fun onPatrolStatusChanged(status: PatrolStatus, waypoint: PatrolWaypoint?)
    fun onWaypointArrived(waypoint: PatrolWaypoint)
    fun onPatrolComplete()
}

/**
 * Controls autonomous patrol navigation on the TEMI robot.
 *
 * Usage:
 * 1. Create with a Robot instance and PatrolRoute
 * 2. Set a PatrolListener to receive callbacks
 * 3. Call start() to begin patrol
 * 4. Call stop() to halt patrol
 * 5. Call pause()/resume() for temporary halt
 */
class PatrolController(
    private val robot: com.robotemi.sdk.Robot,
    private val route: PatrolRoute
) : com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener {

    private val handler = Handler(Looper.getMainLooper())
    private var currentIndex = 0
    private var repeatCount = 0
    private var _status = PatrolStatus.IDLE
    private var listener: PatrolListener? = null

    val status: PatrolStatus get() = _status
    val currentWaypoint: PatrolWaypoint? get() = route.waypoints.getOrNull(currentIndex)

    init {
        robot.addOnGoToLocationStatusChangedListener(this)
    }

    fun setListener(listener: PatrolListener?) {
        this.listener = listener
    }

    /** Start autonomous patrol from the beginning. */
    fun start() {
        currentIndex = 0
        repeatCount = 0
        navigateToNext()
    }

    /** Start patrol from a specific waypoint index. */
    fun startFrom(index: Int) {
        currentIndex = index.coerceIn(0, route.waypoints.size - 1)
        repeatCount = 0
        navigateToNext()
    }

    /** Stop patrol and cancel any pending navigation. */
    fun stop() {
        _status = PatrolStatus.IDLE
        handler.removeCallbacksAndMessages(null)
        robot.stopMovement()
        listener?.onPatrolStatusChanged(_status, null)
    }

    /** Pause patrol at current location. */
    fun pause() {
        _status = PatrolStatus.PAUSED
        handler.removeCallbacksAndMessages(null)
        robot.stopMovement()
        listener?.onPatrolStatusChanged(_status, currentWaypoint)
    }

    /** Resume patrol from current location. */
    fun resume() {
        navigateToNext()
    }

    private fun navigateToNext() {
        if (_status == PatrolStatus.PAUSED || _status == PatrolStatus.COMPLETED) return

        val waypoint = route.waypoints.getOrNull(currentIndex) ?: run {
            handleRouteComplete()
            return
        }

        _status = PatrolStatus.NAVIGATING
        listener?.onPatrolStatusChanged(_status, waypoint)

        Log.d("PatrolController", "Navigating to: ${waypoint.locationName}")
        robot.goTo(waypoint.locationName)
    }

    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String
    ) {
        when (status) {
            "complete" -> {
                listener?.onWaypointArrived(currentWaypoint ?: return)
                startScanning()
            }
            "error" -> {
                Log.e("PatrolController", "Navigation error to $location: $description")
                // Skip to next waypoint after delay
                handler.postDelayed({ navigateToNext() }, 3000)
            }
        }
    }

    private fun startScanning() {
        val waypoint = currentWaypoint ?: return
        _status = PatrolStatus.SCANNING
        listener?.onPatrolStatusChanged(_status, waypoint)

        Log.d("PatrolController", "Scanning at ${waypoint.locationName} for ${waypoint.scanDurationMs}ms")

        handler.postDelayed({
            // Move to next waypoint
            currentIndex++
            if (currentIndex >= route.waypoints.size) {
                currentIndex = 0
                repeatCount++
                if (route.repeatCount != -1 && repeatCount >= route.repeatCount) {
                    handleRouteComplete()
                    return@postDelayed
                }
            }
            navigateToNext()
        }, waypoint.scanDurationMs)
    }

    private fun handleRouteComplete() {
        _status = PatrolStatus.COMPLETED
        listener?.onPatrolStatusChanged(_status, null)
        listener?.onPatrolComplete()
        Log.d("PatrolController", "Patrol route '${route.name}' completed after $repeatCount iterations")
    }

    fun release() {
        stop()
        robot.removeOnGoToLocationStatusChangedListener(this)
    }
}
