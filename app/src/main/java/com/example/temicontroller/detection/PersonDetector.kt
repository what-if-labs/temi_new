package com.example.temicontroller.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.ObjectDetectorOptionsBase.DetectorMode
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

/**
 * A detected person with metadata including bounding box, confidence,
 * and upright/fall estimation based on bounding box aspect ratio.
 */
data class DetectedPerson(
    val boundingBox: RectF,
    val confidence: Float,
    val isUpright: Boolean = true,    // false = likely lying/fallen
    val trackingId: Int = -1
)

/**
 * Detects persons in camera frames using ML Kit Object Detection (bundled model)
 * and estimates whether each detected person is upright or fallen using
 * bounding box aspect ratio heuristics.
 *
 * ML Kit Object Detection with the bundled COCO model is used instead of
 * MediaPipe Pose to avoid ARM64 compatibility issues on some devices.
 */
class PersonDetector(context: Context) {

    private var objectDetector: ObjectDetector? = null

    companion object {
        private const val TAG = "PersonDetector"

        // ML Kit Object Detection uses COCO categories; "person" is index 0
        private const val COCO_PERSON_LABEL = "person"

        // Aspect ratio threshold: height/width > 0.5 means taller than wide
        // Standing person typically has aspect ratio >> 1 (e.g. 1.5-3.0)
        // Fallen/lying person typically has aspect ratio < 1 (e.g. 0.3-0.8)
        private const val UPRIGHT_ASPECT_THRESHOLD = 0.5f

        // Minimum confidence to accept a detection
        private const val MIN_CONFIDENCE = 0.5f
    }

    init {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()

        objectDetector = ObjectDetection.getClient(options)
        Log.d(TAG, "PersonDetector initialized with ML Kit Object Detection")
    }

    /**
     * Detect persons in the given bitmap and estimate their posture.
     *
     * @param bitmap The camera frame to analyze
     * @return List of detected persons with bounding boxes and upright status
     */
    fun detectPersons(bitmap: Bitmap): List<DetectedPerson> {
        val results = mutableListOf<DetectedPerson>()

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        // Use Tasks.await for synchronous processing — caller should run on background thread
        try {
            val detector = objectDetector
            if (detector == null) {
                Log.w(TAG, "Detector not initialized")
                return results
            }

            val task = detector.process(inputImage)
            val detectedObjects = com.google.android.gms.tasks.Tasks.await(task)

            for (obj in detectedObjects) {
                // Filter for person class only
                val personLabel = obj.labels.find { label ->
                    label.text.equals(COCO_PERSON_LABEL, ignoreCase = true)
                }

                if (personLabel != null && personLabel.confidence >= MIN_CONFIDENCE) {
                    val box = obj.boundingBox
                    val rectF = RectF(
                        box.left.toFloat(),
                        box.top.toFloat(),
                        box.right.toFloat(),
                        box.bottom.toFloat()
                    )

                    val isUpright = estimateUprightness(rectF)

                    results.add(
                        DetectedPerson(
                            boundingBox = rectF,
                            confidence = personLabel.confidence,
                            isUpright = isUpright,
                            trackingId = obj.trackingId ?: -1
                        )
                    )
                }
            }

            Log.d(
                TAG,
                "Detected ${results.size} persons " +
                    "(upright: ${results.count { it.isUpright }}, " +
                    "fallen: ${results.count { !it.isUpright }})"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Person detection failed", e)
        }

        return results
    }

    /**
     * Estimate if a detected person is upright or fallen based on bounding box aspect ratio.
     *
     * Heuristic logic:
     * - A standing person's bounding box is taller than it is wide (height >> width)
     * - A fallen/lying person's bounding box is wider than it is tall (width >> height)
     * - We use aspect ratio = height / width; if > 0.5 the person is considered upright
     *
     * @param box The bounding box of the detected person
     * @return true if the person appears upright, false if likely fallen
     */
    private fun estimateUprightness(box: RectF): Boolean {
        val width = box.width()
        val height = box.height()

        // Guard against degenerate boxes
        if (width <= 0f || height <= 0f) {
            Log.w(TAG, "Degenerate bounding box: ${width}x${height}")
            return true // Default to upright for safety
        }

        val aspectRatio = height / width

        // Additional heuristics for robustness:
        // - Very square boxes (0.8-1.2) are ambiguous; default upright
        // - Boxes where height < 50% of width strongly suggest fallen posture
        return aspectRatio > UPRIGHT_ASPECT_THRESHOLD
    }

    /**
     * Release ML Kit detector resources.
     */
    fun close() {
        try {
            objectDetector?.close()
            objectDetector = null
            Log.d(TAG, "PersonDetector closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PersonDetector", e)
        }
    }
}
