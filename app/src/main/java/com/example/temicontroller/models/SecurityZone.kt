package com.example.temicontroller.models

/**
 * A point in 2D space representing a vertex of a security zone polygon.
 *
 * @param x X coordinate in the zone's local coordinate system
 * @param y Y coordinate in the zone's local coordinate system
 */
data class SecurityPoint(
    val x: Float,
    val y: Float
)

/**
 * Represents a polygonal security zone used for surveillance monitoring.
 *
 * @param id Unique identifier for the zone
 * @param name Human-readable name displayed in the UI
 * @param polygon Ordered list of vertices defining the zone boundary
 * @param alertType The type of alert to monitor for within this zone
 * @param threshold Alert trigger threshold — interpretation depends on alertType:
 *   - Time-based alerts (STATIONARY_VEHICLE, LOITERING): duration in seconds
 *   - Count-based alerts (QUEUE_CONGESTION): number of people
 * @param cooldownMs Minimum time between consecutive alerts for this zone (default 60s)
 */
data class SecurityZone(
    val id: String,
    val name: String,
    val polygon: List<SecurityPoint>,
    val alertType: AlertType,
    val threshold: Int,
    val cooldownMs: Long = 60_000L
)

/**
 * Factory for default security zone configurations.
 */
object ZoneDefaults {

    /**
     * Returns a list of three default security zones suitable for a typical
     * building lobby / corridor layout.
     */
    fun defaultZones(): List<SecurityZone> = listOf(
        SecurityZone(
            id = "lobby_dropoff",
            name = "Lobby Drop-off",
            polygon = listOf(
                SecurityPoint(0f, 0f),
                SecurityPoint(10f, 0f),
                SecurityPoint(10f, 8f),
                SecurityPoint(0f, 8f)
            ),
            alertType = AlertType.STATIONARY_VEHICLE,
            threshold = 120 // seconds
        ),
        SecurityZone(
            id = "turnstiles",
            name = "Turnstile Queue",
            polygon = listOf(
                SecurityPoint(0f, 0f),
                SecurityPoint(6f, 0f),
                SecurityPoint(6f, 4f),
                SecurityPoint(0f, 4f)
            ),
            alertType = AlertType.QUEUE_CONGESTION,
            threshold = 5 // people
        ),
        SecurityZone(
            id = "patrol_corridor",
            name = "Main Corridor",
            polygon = listOf(
                SecurityPoint(0f, 0f),
                SecurityPoint(20f, 0f),
                SecurityPoint(20f, 3f),
                SecurityPoint(0f, 3f)
            ),
            alertType = AlertType.LOITERING,
            threshold = 180 // seconds
        )
    )
}
