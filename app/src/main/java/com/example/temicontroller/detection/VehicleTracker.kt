package com.example.temicontroller.detection

import android.graphics.RectF
import com.example.temicontroller.models.SecurityAlert
import com.example.temicontroller.models.AlertType
import com.example.temicontroller.models.SecurityZone

// A detected vehicle from object detection
data class DetectedVehicle(
    val trackingId: Int,
    val type: String,  // "car", "bus", "truck"
    val boundingBox: RectF,
    val confidence: Float
)

// Tracked vehicle with history
data class TrackedVehicle(
    val trackingId: Int,
    val type: String,
    var firstSeenMs: Long,
    var lastSeenMs: Long,
    var lastX: Float,
    var lastY: Float,
    var positionHistory: MutableList<Pair<Float, Float>> = mutableListOf()
) {
    fun positionVariance(): Float {
        if (positionHistory.size < 2) return 0f
        val avgX = positionHistory.map { it.first }.average()
        val avgY = positionHistory.map { it.second }.average()
        val varX = positionHistory.map { (it.first - avgX) * (it.first - avgX) }.average()
        val varY = positionHistory.map { (it.second - avgY) * (it.second - avgY) }.average()
        return (varX + varY).toFloat()
    }
}

class VehicleTracker {
    private val trackedVehicles = mutableMapOf<Int, TrackedVehicle>()
    private val cooldowns = mutableMapOf<String, Long>()
    
    companion object {
        // Vehicle must move more than this variance to be considered "moving"
        private const val STATIONARY_VARIANCE_THRESHOLD = 2.0f  // squared meters
        // Max distance from zone center to consider "in zone"
        private const val MAX_ZONE_DISTANCE = 20f  // meters
        // Clean up vehicles not seen for this duration
        private const val STALE_MS = 10_000L
    }
    
    /**
     * Update frame with detected vehicles. Returns security alerts for stationary vehicles.
     * 
     * @param timestamp Current timestamp in ms
     * @param vehicles Detected vehicles this frame
     * @param zone The zone to monitor (typically lobby drop-off)
     * @param zoneCenterX Zone center X coordinate for distance check
     * @param zoneCenterY Zone center Y coordinate for distance check
     */
    fun updateFrame(
        timestamp: Long,
        vehicles: List<DetectedVehicle>,
        zone: SecurityZone,
        zoneCenterX: Float = 5f,
        zoneCenterY: Float = 4f
    ): List<SecurityAlert> {
        val alerts = mutableListOf<SecurityAlert>()
        
        // Update tracked vehicles
        for (v in vehicles) {
            val tracked = trackedVehicles.getOrPut(v.trackingId) {
                TrackedVehicle(
                    trackingId = v.trackingId,
                    type = v.type,
                    firstSeenMs = timestamp,
                    lastSeenMs = timestamp,
                    lastX = v.boundingBox.centerX().toFloat(),
                    lastY = v.boundingBox.centerY().toFloat()
                )
            }
            
            tracked.lastSeenMs = timestamp
            tracked.positionHistory.add(Pair(tracked.lastX, tracked.lastY))
            
            // Keep history bounded
            if (tracked.positionHistory.size > 30) {
                tracked.positionHistory = tracked.positionHistory.takeLast(15).toMutableList()
            }
        }
        
        // Check for stationary vehicles exceeding threshold
        for ((id, tracked) in trackedVehicles) {
            if (timestamp - tracked.lastSeenMs > STALE_MS) continue // stale
            
            val duration = timestamp - tracked.firstSeenMs
            val variance = tracked.positionVariance()
            
            if (variance < STATIONARY_VARIANCE_THRESHOLD &&
                duration > zone.threshold * 1000L) {
                val cooldownKey = "vehicle_${id}"
                if (isCooldownExpired(cooldownKey, timestamp, zone.cooldownMs)) {
                    alerts.add(SecurityAlert(
                        type = AlertType.STATIONARY_VEHICLE,
                        zone = zone.name,
                        confidence = 0.8f,
                        metadata = mapOf(
                            "vehicleType" to tracked.type,
                            "durationSec" to duration / 1000,
                            "trackingId" to tracked.trackingId
                        )
                    ))
                    cooldowns[cooldownKey] = timestamp
                }
            }
        }
        
        // Clean up stale vehicles
        trackedVehicles.entries.removeAll { 
            timestamp - it.value.lastSeenMs > STALE_MS 
        }
        
        return alerts
    }
    
    private fun isCooldownExpired(key: String, now: Long, cooldownMs: Long): Boolean {
        val lastAlert = cooldowns[key] ?: 0L
        return now - lastAlert > cooldownMs
    }
    
    fun reset() {
        trackedVehicles.clear()
        cooldowns.clear()
    }
}
