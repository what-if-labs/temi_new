package com.example.temicontroller.detection

import android.graphics.RectF

/**
 * Counts bags per person by associating detected bag objects with nearby person detections.
 */
class BagCounter {
    companion object {
        // Known bag/ luggage labels from object detection (COCO classes)
        private val BAG_LABELS = setOf(
            "handbag", "backpack", "suitcase", "luggage", "bag", "briefcase", "duffel bag"
        )
        // Maximum distance (in pixel units) to associate a bag with a person
        private const val ASSOCIATION_DISTANCE = 1.5f
        // Maximum bags before triggering OVERLOADED alert
        private const val MAX_BAGS = 3
    }
    
    /**
     * Count bags associated with each person.
     * 
     * @param persons List of detected persons with bounding boxes
     * @param objects List of all detected objects with labels and bounding boxes
     * @return Map of person trackingId → bag count
     */
    fun countBagsPerPerson(
        persons: List<DetectedPerson>,
        objects: List<DetectedObject>
    ): Map<Int, Int> {
        return persons.associate { person ->
            val nearbyBags = objects.count { obj ->
                isBagLabel(obj.label) &&
                    distance(
                        person.boundingBox.centerX(), person.boundingBox.centerY(),
                        obj.boundingBox.centerX(), obj.boundingBox.centerY()
                    ) < ASSOCIATION_DISTANCE
            }
            person.trackingId to nearbyBags
        }
    }
    
    /**
     * Get persons carrying more than the threshold number of bags.
     */
    fun getOverloadedPersons(
        persons: List<DetectedPerson>,
        objects: List<DetectedObject>,
        threshold: Int = MAX_BAGS
    ): List<DetectedPerson> {
        val counts = countBagsPerPerson(persons, objects)
        return persons.filter { counts[it.trackingId] ?: 0 > threshold }
    }
    
    private fun isBagLabel(label: String): Boolean {
        return BAG_LABELS.any { label.lowercase().contains(it) }
    }
    
    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy).toFloat()
    }
}

/**
 * A generic detected object with label and bounding box.
 */
data class DetectedObject(
    val label: String,
    val boundingBox: RectF,
    val confidence: Float,
    val trackingId: Int = -1
)
