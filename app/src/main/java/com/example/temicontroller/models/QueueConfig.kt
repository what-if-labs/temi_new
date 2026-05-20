package com.example.temicontroller.models

/**
 * Configuration for queue congestion detection at turnstiles or other choke points.
 */
data class QueueConfig(
    val zoneId: String = "turnstiles",
    val maxPeople: Int = 5,
    val alertCooldownMs: Long = 120_000  // 2 minutes between alerts
) {
    companion object {
        /** Default queue config for turnstile monitoring */
        fun default() = QueueConfig(
            zoneId = "turnstiles",
            maxPeople = 5,
            alertCooldownMs = 120_000
        )

        /** Heavy traffic config — higher threshold, shorter cooldown */
        fun heavyTraffic() = QueueConfig(
            zoneId = "turnstiles",
            maxPeople = 8,
            alertCooldownMs = 60_000
        )
    }

    /**
     * Convert this config to a SecurityZone for use with EventTracker.
     */
    fun toSecurityZone(): SecurityZone {
        return SecurityZone(
            id = zoneId,
            name = if (zoneId == "turnstiles") "Turnstile Queue" else zoneId,
            polygon = if (zoneId == "turnstiles") listOf(
                SecurityPoint(0f, 0f),
                SecurityPoint(6f, 0f),
                SecurityPoint(6f, 4f),
                SecurityPoint(0f, 4f)
            ) else emptyList(),
            alertType = AlertType.QUEUE_CONGESTION,
            threshold = maxPeople,
            cooldownMs = alertCooldownMs
        )
    }
}
