package com.example.temicontroller.detection

/**
 * Represents a single pose landmark with normalized coordinates.
 */
data class PoseLandmark(
    val index: Int,
    val x: Float,   // normalized 0-1
    val y: Float,   // normalized 0-1
    val z: Float,   // depth
    val visibility: Float  // 0-1 confidence
)

class SmokingDetector {
    companion object {
        const val NOSE_INDEX = 0
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_ELBOW = 13
        const val RIGHT_ELBOW = 14
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        
        // Threshold for hand-to-mouth distance (normalized coordinates)
        private const val HAND_MOUTH_THRESHOLD = 0.12f
        // Minimum elbow bend angle (degrees) for smoking posture
        private const val MIN_ELBOW_BEND = 30f
    }

    /**
     * Detect smoking pose from landmarks.
     * Returns confidence 0.0 - 1.0.
     * 
     * Heuristic:
     * 1. Check if either wrist is close to nose (hand near mouth)
     * 2. Check if corresponding elbow is bent (not straight arm reaching)
     * 3. Combine into confidence score
     */
    fun detectSmokingPose(landmarks: List<PoseLandmark>): Float {
        val nose = landmarks.find { it.index == NOSE_INDEX }
        val leftWrist = landmarks.find { it.index == LEFT_WRIST }
        val rightWrist = landmarks.find { it.index == RIGHT_WRIST }
        val leftElbow = landmarks.find { it.index == LEFT_ELBOW }
        val rightElbow = landmarks.find { it.index == RIGHT_ELBOW }
        val leftShoulder = landmarks.find { it.index == LEFT_SHOULDER }
        val rightShoulder = landmarks.find { it.index == RIGHT_SHOULDER }
        
        // Skip if key landmarks not visible
        if (nose?.visibility == null || nose.visibility < 0.5f) return 0f
        
        var maxConfidence = 0f
        
        // Check left side
        if (leftWrist != null && leftWrist.visibility > 0.3f && 
            leftElbow != null && leftElbow.visibility > 0.3f) {
            val handDist = normalizedDistance(nose, leftWrist)
            val elbowAngle = calculateAngle(leftShoulder, leftElbow, leftWrist)
            
            if (handDist < HAND_MOUTH_THRESHOLD) {
                val proximityConf = 1f - (handDist / HAND_MOUTH_THRESHOLD)
                val elbowConf = if (elbowAngle < 90f) 1f - (elbowAngle / 90f) else 0f
                maxConfidence = maxOf(maxConfidence, proximityConf * 0.7f + elbowConf * 0.3f)
            }
        }
        
        // Check right side (same logic)
        if (rightWrist != null && rightWrist.visibility > 0.3f &&
            rightElbow != null && rightElbow.visibility > 0.3f) {
            val handDist = normalizedDistance(nose, rightWrist)
            val elbowAngle = calculateAngle(rightShoulder, rightElbow, rightWrist)
            
            if (handDist < HAND_MOUTH_THRESHOLD) {
                val proximityConf = 1f - (handDist / HAND_MOUTH_THRESHOLD)
                val elbowConf = if (elbowAngle < 90f) 1f - (elbowAngle / 90f) else 0f
                maxConfidence = maxOf(maxConfidence, proximityConf * 0.7f + elbowConf * 0.3f)
            }
        }
        
        return maxConfidence
    }
    
    private fun normalizedDistance(a: PoseLandmark, b: PoseLandmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).toFloat()
    }
    
    private fun calculateAngle(a: PoseLandmark?, b: PoseLandmark, c: PoseLandmark): Float {
        if (a == null) return 180f // Unknown shoulder = assume straight
        val baX = a.x - b.x; val baY = a.y - b.y; val baZ = a.z - b.z
        val bcX = c.x - b.x; val bcY = c.y - b.y; val bcZ = c.z - b.z
        val dot = baX * bcX + baY * bcY + baZ * bcZ
        val magBA = kotlin.math.sqrt(baX * baX + baY * baY + baZ * baZ)
        val magBC = kotlin.math.sqrt(bcX * bcX + bcY * bcY + bcZ * bcZ)
        if (magBA == 0f || magBC == 0f) return 180f
        val cosAngle = (dot / (magBA * magBC)).coerceIn(-1.0f, 1.0f)
        return Math.toDegrees(Math.acos(cosAngle.toDouble())).toFloat()
    }
}
