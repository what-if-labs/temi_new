package com.example.temicontroller.models

/**
 * Enum representing the different types of security alerts
 * that can be detected during patrol.
 */
enum class AlertType {
    LOITERING,
    SMOKING,
    STATIONARY_VEHICLE,
    FALLEN_PERSON,
    UNATTENDED_BAG,
    OVERLOADED_PERSON,
    QUEUE_CONGESTION
}

/**
 * Data class representing a security alert generated during patrol.
 */
data class SecurityAlert(
    val type: AlertType,
    val timestamp: Long = System.currentTimeMillis(),
    val zone: String = "",
    val location: LocationData? = null,
    val confidence: Float = 0f,
    val metadata: Map<String, Any> = emptyMap(),
    val snapshotBase64: String? = null
) {
    /**
     * Represents the location coordinates of a detected alert.
     */
    data class LocationData(
        val x: Float,
        val y: Float,
        val yaw: Float
    )
}
