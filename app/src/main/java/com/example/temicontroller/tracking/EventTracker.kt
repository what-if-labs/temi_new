package com.example.temicontroller.tracking

import com.example.temicontroller.models.AlertType
import com.example.temicontroller.models.SecurityAlert
import com.example.temicontroller.models.SecurityZone

/**
 * Runtime configuration for enabled/disabled detection types and thresholds.
 * Read from SharedPreferences and applied to EventTracker each frame.
 */
data class DetectionConfig(
    val loiteringEnabled: Boolean = true,
    val smokingEnabled: Boolean = true,
    val fallenPersonEnabled: Boolean = true,
    val unattendedBagEnabled: Boolean = true,
    val loiteringThresholdSec: Int = 180,
    val queueMaxPeople: Int = 5,
    val unattendedBagThresholdSec: Int = 120
) {
    companion object {
        fun default() = DetectionConfig()
    }
}

/**
 * Person detection with zone context, used as input to [EventTracker].
 *
 * @param trackingId Unique identifier assigned by the person detector/tracker
 * @param x X coordinate in the zone's local coordinate system
 * @param y Y coordinate in the zone's local coordinate system
 * @param isUpright Whether the person is detected in an upright posture
 * @param zoneId The zone this person has been assigned to (empty if not in any zone)
 */
data class TrackedPersonInZone(
    val trackingId: Int,
    val x: Float,
    val y: Float,
    val isUpright: Boolean,
    val zoneId: String = ""
)

/**
 * Object detection with zone context, used as input to [EventTracker].
 *
 * @param trackingId Unique identifier assigned by the object detector/tracker
 * @param type Object classification label (e.g., "bag", "person")
 * @param x X coordinate in the zone's local coordinate system
 * @param y Y coordinate in the zone's local coordinate system
 * @param zoneId The zone this object has been assigned to (empty if not in any zone)
 */
data class TrackedObjectInZone(
    val trackingId: Int,
    val type: String,  // "bag", "person", etc.
    val x: Float,
    val y: Float,
    val zoneId: String = ""
)

/**
 * Internal representation of a tracked security event.
 *
 * Tracks lifecycle: first seen, last seen, whether alert was already sent,
 * and arbitrary metadata for downstream consumers.
 */
data class TrackedEvent(
    val id: String,
    val type: AlertType,
    val zone: String,
    val firstSeenMs: Long,
    var lastSeenMs: Long,
    var alertSent: Boolean = false,
    val metadata: MutableMap<String, Any> = mutableMapOf()
)

/**
 * Core time-based event tracking engine.
 *
 * Receives person/object detections each frame and returns security alerts
 * based on configurable zone rules. Supports loitering detection, queue
 * congestion, fallen person, unattended bag detection, cooldown management,
 * and stale event cleanup.
 */
class EventTracker {

    companion object {
        private const val STALE_THRESHOLD_MS = 5 * 60 * 1_000L // 5 minutes
        private const val DEFAULT_PROXIMITY_THRESHOLD = 2.0f // meters for unattended bag check
    }

    private val activeEvents = mutableMapOf<String, TrackedEvent>()
    private val cooldowns = mutableMapOf<String, Long>()

    /**
     * Process a single frame of detections and return any triggered alerts.
     *
     * @param timestamp Current frame timestamp in milliseconds
     * @param persons List of detected persons with zone assignments
     * @param objects List of detected objects with zone assignments
     * @param zones Configured security zones with rules
     * @return List of security alerts triggered by this frame
     */
    fun updateFrame(
        timestamp: Long,
        persons: List<TrackedPersonInZone>,
        objects: List<TrackedObjectInZone>,
        zones: List<SecurityZone>,
        config: DetectionConfig = DetectionConfig.default()
    ): List<SecurityAlert> {
        val alerts = mutableListOf<SecurityAlert>()

        // Build zone membership maps
        val personsByZone = persons.groupBy { it.zoneId }
        val objectsByZone = objects.groupBy { it.zoneId }

        for (zone in zones) {
            val zonePersons = personsByZone[zone.id].orEmpty()
            val zoneObjects = objectsByZone[zone.id].orEmpty()

            // Check fallen person — only if enabled
            if (config.fallenPersonEnabled) {
                checkFallenPerson(timestamp, zone, zonePersons, alerts)
            }

            // Check zone-specific rules based on configured alert type
            when (zone.alertType) {
                AlertType.LOITERING -> {
                    if (config.loiteringEnabled) {
                        checkLoitering(timestamp, zone, zonePersons, alerts, config.loiteringThresholdSec)
                    }
                }
                AlertType.QUEUE_CONGESTION -> {
                    checkQueueCongestion(timestamp, zone, zonePersons, alerts, config.queueMaxPeople)
                }
                else -> {
                    // Other alert types may be handled by different modules
                }
            }

            // Unattended bag check — only if enabled
            if (config.unattendedBagEnabled) {
                checkUnattendedBag(timestamp, zone, zonePersons, zoneObjects, alerts, config.unattendedBagThresholdSec)
            }
        }

        // Clean up stale events not seen in 5 minutes
        cleanupStaleEvents(timestamp)

        return alerts
    }

    // -----------------------------------------------------------------------
    // Individual check methods
    // -----------------------------------------------------------------------

    /**
     * Check if a person has been in a zone longer than the zone's threshold.
     * Fires LOITERING alert when threshold is exceeded.
     */
    private fun checkLoitering(
        timestamp: Long,
        zone: SecurityZone,
        persons: List<TrackedPersonInZone>,
        alerts: MutableList<SecurityAlert>,
        overrideThresholdSec: Int = -1  // -1 means use zone.threshold
    ) {
        val thresholdMs = if (overrideThresholdSec > 0) {
            overrideThresholdSec * 1_000L
        } else {
            zone.threshold * 1_000L
        }

        for (person in persons) {
            val eventId = "loitering_${zone.id}_${person.trackingId}"
            trackPersonEvent(
                eventId = eventId,
                type = AlertType.LOITERING,
                zone = zone,
                person = person,
                timestamp = timestamp,
                thresholdMs = thresholdMs,
                alerts = alerts,
                metadata = mapOf("trackingId" to person.trackingId)
            )
        }
    }

    /**
     * Check if person count in zone meets or exceeds the threshold.
     * Fires QUEUE_CONGESTION alert when count >= threshold.
     */
    private fun checkQueueCongestion(
        timestamp: Long,
        zone: SecurityZone,
        persons: List<TrackedPersonInZone>,
        alerts: MutableList<SecurityAlert>,
        overrideThreshold: Int = -1  // -1 means use zone.threshold
    ) {
        val threshold = if (overrideThreshold > 0) overrideThreshold else zone.threshold
        if (persons.size >= threshold) {
            val eventId = "queue_${zone.id}"
            val event = activeEvents.getOrPut(eventId) {
                TrackedEvent(
                    id = eventId,
                    type = AlertType.QUEUE_CONGESTION,
                    zone = zone.id,
                    firstSeenMs = timestamp,
                    lastSeenMs = timestamp
                )
            }
            event.lastSeenMs = timestamp

            if (!isOnCooldown(eventId, timestamp, zone.cooldownMs)) {
                val avgX = persons.map { it.x }.average().toFloat()
                val avgY = persons.map { it.y }.average().toFloat()
                alerts.add(
                    SecurityAlert(
                        type = AlertType.QUEUE_CONGESTION,
                        timestamp = timestamp,
                        zone = zone.name,
                        confidence = 1f,
                        metadata = mapOf(
                            "personCount" to persons.size,
                            "threshold" to zone.threshold,
                            "avgX" to avgX,
                            "avgY" to avgY
                        )
                    )
                )
                event.alertSent = true
                setCooldown(eventId, timestamp)
            }
        } else {
            // Congestion cleared — remove the event so it can re-trigger later
            activeEvents.remove("queue_${zone.id}")
        }
    }

    /**
     * Check if a person in the zone is not upright.
     * Fires FALLEN_PERSON alert immediately.
     */
    private fun checkFallenPerson(
        timestamp: Long,
        zone: SecurityZone,
        persons: List<TrackedPersonInZone>,
        alerts: MutableList<SecurityAlert>
    ) {
        for (person in persons) {
            if (!person.isUpright) {
                val eventId = "fallen_${zone.id}_${person.trackingId}"
                val event = activeEvents.getOrPut(eventId) {
                    TrackedEvent(
                        id = eventId,
                        type = AlertType.FALLEN_PERSON,
                        zone = zone.id,
                        firstSeenMs = timestamp,
                        lastSeenMs = timestamp
                    )
                }
                event.lastSeenMs = timestamp

                if (!isOnCooldown(eventId, timestamp, zone.cooldownMs)) {
                    alerts.add(
                        SecurityAlert(
                            type = AlertType.FALLEN_PERSON,
                            timestamp = timestamp,
                            zone = zone.name,
                            confidence = 1f,
                            location = SecurityAlert.LocationData(
                                x = person.x,
                                y = person.y,
                                yaw = 0f
                            ),
                            metadata = mapOf("trackingId" to person.trackingId)
                        )
                    )
                    event.alertSent = true
                    setCooldown(eventId, timestamp)
                }
            }
        }
    }

    /**
     * Check if a bag-type object is in a zone with no nearby person.
     * Fires UNATTENDED_BAG alert when duration exceeds threshold.
     */
    private fun checkUnattendedBag(
        timestamp: Long,
        zone: SecurityZone,
        persons: List<TrackedPersonInZone>,
        objects: List<TrackedObjectInZone>,
        alerts: MutableList<SecurityAlert>,
        overrideThresholdSec: Int = -1  // -1 means use zone.threshold
    ) {
        val bags = objects.filter { it.type.equals("bag", ignoreCase = true) }

        for (bag in bags) {
            val nearbyPerson = persons.any { person ->
                distance(bag.x, bag.y, person.x, person.y) <= DEFAULT_PROXIMITY_THRESHOLD
            }

            if (!nearbyPerson) {
                val eventId = "unattended_${zone.id}_${bag.trackingId}"
                val event = activeEvents.getOrPut(eventId) {
                    TrackedEvent(
                        id = eventId,
                        type = AlertType.UNATTENDED_BAG,
                        zone = zone.id,
                        firstSeenMs = timestamp,
                        lastSeenMs = timestamp
                    )
                }
                event.lastSeenMs = timestamp

                val thresholdMs = if (overrideThresholdSec > 0) {
                    overrideThresholdSec * 1_000L
                } else {
                    zone.threshold * 1_000L
                }
                val elapsed = timestamp - event.firstSeenMs

                if (elapsed >= thresholdMs && !isOnCooldown(eventId, timestamp, zone.cooldownMs)) {
                    alerts.add(
                        SecurityAlert(
                            type = AlertType.UNATTENDED_BAG,
                            timestamp = timestamp,
                            zone = zone.name,
                            confidence = 1f,
                            location = SecurityAlert.LocationData(
                                x = bag.x,
                                y = bag.y,
                                yaw = 0f
                            ),
                            metadata = mapOf(
                                "trackingId" to bag.trackingId,
                                "durationSec" to (elapsed / 1_000L)
                            )
                        )
                    )
                    event.alertSent = true
                    setCooldown(eventId, timestamp)
                }
            } else {
                // Person nearby — remove event if it existed
                activeEvents.remove("unattended_${zone.id}_${bag.trackingId}")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /**
     * Track a person-based event over time and fire alert when threshold exceeded.
     */
    private fun trackPersonEvent(
        eventId: String,
        type: AlertType,
        zone: SecurityZone,
        person: TrackedPersonInZone,
        timestamp: Long,
        thresholdMs: Long,
        alerts: MutableList<SecurityAlert>,
        metadata: Map<String, Any>
    ) {
        val event = activeEvents.getOrPut(eventId) {
            TrackedEvent(
                id = eventId,
                type = type,
                zone = zone.id,
                firstSeenMs = timestamp,
                lastSeenMs = timestamp
            ).apply {
                metadata.forEach { (k, v) -> this.metadata[k] = v }
            }
        }
        event.lastSeenMs = timestamp

        val elapsed = timestamp - event.firstSeenMs
        if (elapsed >= thresholdMs && !isOnCooldown(eventId, timestamp, zone.cooldownMs)) {
            alerts.add(
                SecurityAlert(
                    type = type,
                    timestamp = timestamp,
                    zone = zone.name,
                    confidence = 1f,
                    location = SecurityAlert.LocationData(
                        x = person.x,
                        y = person.y,
                        yaw = 0f
                    ),
                    metadata = event.metadata.toMap() + mapOf(
                        "durationSec" to (elapsed / 1_000L)
                    )
                )
            )
            event.alertSent = true
            setCooldown(eventId, timestamp)
        }
    }

    // -----------------------------------------------------------------------
    // Cooldown system
    // -----------------------------------------------------------------------

    private fun isOnCooldown(eventId: String, timestamp: Long, cooldownMs: Long): Boolean {
        val lastAlertTime = cooldowns[eventId] ?: return false
        return (timestamp - lastAlertTime) < cooldownMs
    }

    private fun setCooldown(eventId: String, timestamp: Long) {
        cooldowns[eventId] = timestamp
    }

    // -----------------------------------------------------------------------
    // Stale cleanup
    // -----------------------------------------------------------------------

    private fun cleanupStaleEvents(timestamp: Long) {
        val staleIds = activeEvents.filter { (_, event) ->
            (timestamp - event.lastSeenMs) > STALE_THRESHOLD_MS
        }.keys
        staleIds.forEach {
            activeEvents.remove(it)
            cooldowns.remove(it)
        }
    }

    // -----------------------------------------------------------------------
    // Geometry helpers
    // -----------------------------------------------------------------------

    /**
     * Ray-casting point-in-polygon test.
     *
     * @param px Point x coordinate
     * @param py Point y coordinate
     * @param polygon Ordered list of polygon vertices
     * @return true if point is inside the polygon
     */
    fun isInPolygon(px: Float, py: Float, polygon: List<com.example.temicontroller.models.SecurityPoint>): Boolean {
        if (polygon.size < 3) return false

        var inside = false
        var j = polygon.size - 1

        for (i in polygon.indices) {
            val vi = polygon[i]
            val vj = polygon[j]

            val intersects = ((vi.y > py) != (vj.y > py)) &&
                (px < (vj.x - vi.x) * (py - vi.y) / (vj.y - vi.y) + vi.x)

            if (intersects) {
                inside = !inside
            }

            j = i
        }

        return inside
    }

    /**
     * Euclidean distance between two 2D points.
     */
    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
