package com.example.temicontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.temicontroller.detection.DetectedPerson
import com.example.temicontroller.detection.PersonDetector
import com.example.temicontroller.models.SecurityAlert
import com.example.temicontroller.models.ZoneDefaults
import com.example.temicontroller.tracking.DetectionConfig
import com.example.temicontroller.tracking.EventTracker
import com.example.temicontroller.tracking.TrackedPersonInZone
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SurveillanceService : Service() {
    private val binder = LocalBinder()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // Camera
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    
    // Face Detection
    private lateinit var faceDetector: FaceDetector
    
    // Person detection & event tracking
    private lateinit var personDetector: PersonDetector
    private val eventTracker = EventTracker()
    private lateinit var zones: List<com.example.temicontroller.models.SecurityZone>
    private var detectionConfig = DetectionConfig.default()
    private var alertHandler: Handler? = null
    
    // MQTT
    private var mqttClient: MqttClient? = null
    
    // Analytics state
    private var isSurveillanceActive = false
    private var lastSnapshotTime = 0L
    private val snapshotIntervalMs = 5000L // 5 seconds
    private var frameCount = 0
    private var faceDetectionCount = 0
    private var motionDetected = false
    private var lastFrameBytes: ByteArray? = null
    
    companion object {
        const val CHANNEL_ID = "TemiSurveillanceChannel"
        const val NOTIFICATION_ID = 2
        const val TOPIC_ANALYTICS = "temi/surveillance/analytics"
        const val TOPIC_SNAPSHOT = "temi/surveillance/snapshot"
        const val TOPIC_MOTION = "temi/surveillance/motion"
        const val TAG = "Surveillance"
        
        // SharedPreferences keys (mirror MainActivity keys for consistency)
        const val KEY_DETECT_LOITERING = "detect_loitering"
        const val KEY_DETECT_SMOKING = "detect_smoking"
        const val KEY_DETECT_FALLEN = "detect_fallen"
        const val KEY_LOITERING_THRESHOLD = "loitering_threshold"
        const val KEY_QUEUE_MAX_PEOPLE = "queue_max_people"
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): SurveillanceService = this@SurveillanceService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        setupFaceDetector()
        personDetector = PersonDetector(this)
        zones = loadZonesFromPrefs()  // Load custom zones from prefs (Issue #4 fix)
        Log.d(TAG, "Loaded ${zones.size} security zones from prefs")
        connectMqtt()
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    private fun setupFaceDetector() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)  // 15% of image width — filters tiny false positives
            .enableTracking()       // Enables trackingId for person association
            .build()
        faceDetector = FaceDetection.getClient(options)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Temi Surveillance",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Temi Surveillance")
            .setContentText(if (isSurveillanceActive) "Active - Monitoring" else "Standby")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .build()
    }
    
    fun startSurveillance() {
        if (isSurveillanceActive) {
            Log.d(TAG, "Surveillance already active")
            return
        }
        
        Log.d(TAG, "Starting surveillance...")
        
        // Start camera thread
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        
        // Setup image reader - small resolution for MQTT
        imageReader = ImageReader.newInstance(160, 120, ImageFormat.JPEG, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            image?.let { processImage(it) }
            image?.close()
        }, cameraHandler)
        
        // Open camera
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = findCameraId(cameraManager)
            if (cameraId == null) {
                Log.e(TAG, "No camera found")
                return
            }
            
            Log.d(TAG, "Opening camera: $cameraId")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, cameraHandler)
            
            isSurveillanceActive = true
            updateNotification()
            Log.d(TAG, "Surveillance started")
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
        }
    }
    
    private fun findCameraId(cameraManager: CameraManager): String? {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                Log.d(TAG, "Camera $cameraId facing: $facing")
                // Use first available camera (TEMI has external camera)
                return cameraId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding camera", e)
        }
        return null
    }
    
    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return
        
        try {
            // Create capture request
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(reader.surface)
            
            // Start capturing
            camera.createCaptureSession(
                listOf(reader.surface),
                object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                        try {
                            session.setRepeatingRequest(
                                requestBuilder.build(),
                                null,
                                cameraHandler
                            )
                            Log.d(TAG, "Capture session configured")
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Capture request failed", e)
                        }
                    }
                    
                    override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Create capture session failed", e)
        }
    }
    
    fun stopSurveillance() {
        if (!isSurveillanceActive) return
        
        Log.d(TAG, "Stopping surveillance...")
        
        try {
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            cameraThread?.quitSafely()
            cameraThread = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping surveillance", e)
        }
        
        isSurveillanceActive = false
        updateNotification()
        Log.d(TAG, "Surveillance stopped")
    }
    
    private fun processImage(image: Image) {
        val currentTime = System.currentTimeMillis()
        frameCount++
        
        try {
            // Get JPEG bytes directly from ImageReader
            val buffer = image.planes[0].buffer
            val jpegBytes = ByteArray(buffer.remaining())
            buffer.get(jpegBytes)
            
            // Motion detection using JPEG bytes hash
            motionDetected = if (lastFrameBytes != null) {
                calculateByteDifference(lastFrameBytes!!, jpegBytes) > 0.1f
            } else false
            lastFrameBytes = jpegBytes.copyOf()
            
            // Decode bitmap for face detection
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            if (bitmap != null) {
                detectFaces(bitmap)
                
                // Run person detection
                val detectedPersons = personDetector.detectPersons(bitmap)
                
                // Map detections to zone-aware tracked persons
                val trackedPersons = detectedPersons.map { p ->
                    // Assign person to zone based on polygon containment
                    val assignedZoneId = zones.find { zone ->
                        eventTracker.isInPolygon(
                            p.boundingBox.centerX().toFloat(),
                            p.boundingBox.centerY().toFloat(),
                            zone.polygon
                        )
                    }?.id ?: ""
                    
                    TrackedPersonInZone(
                        trackingId = p.trackingId,
                        x = p.boundingBox.centerX().toFloat(),
                        y = p.boundingBox.centerY().toFloat(),
                        isUpright = p.isUpright,
                        zoneId = assignedZoneId
                    )
                }
                
                // Load detection config from prefs (Issue #3 fix)
                detectionConfig = loadDetectionConfig()
                
                // Run event tracker
                val newAlerts = eventTracker.updateFrame(
                    timestamp = currentTime,
                    persons = trackedPersons,
                    objects = emptyList(),  // Objects from separate detector later
                    zones = zones,
                    config = detectionConfig
                )
                
                // Publish alerts via MQTT
                for (alert in newAlerts) {
                    publishSecurityAlert(alert)
                }
            }
            
            // Periodic snapshot - send JPEG directly as Base64
            if (currentTime - lastSnapshotTime > snapshotIntervalMs) {
                sendSnapshotJpeg(jpegBytes)
                sendAnalytics()
                lastSnapshotTime = currentTime
            }
            
            // Send motion alert
            if (motionDetected) {
                sendMotionAlert()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        }
    }
    
    private fun calculateByteDifference(bytes1: ByteArray, bytes2: ByteArray): Float {
        if (bytes1.size != bytes2.size) return 1f
        // Sample every Nth byte for performance
        var diff = 0
        val step = maxOf(1, bytes1.size / 1000)
        var count = 0
        for (i in bytes1.indices step step) {
            diff += kotlin.math.abs(bytes1[i] - bytes2[i])
            count++
        }
        return if (count > 0) diff.toFloat() / (count * 255) else 0f
    }
    

    
    private fun detectFaces(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                // Track total faces for analytics
                faceDetectionCount = faces.size
                
                if (faces.isEmpty()) {
                    // Clear person tracking when no faces
                    lastKnownPersons.clear()
                    return@addOnSuccessListener
                }
                
                // Filter and tag faces with person IDs
                val faceData = faces.mapNotNull { face ->
                    // Confidence proxy: use bounding box size relative to image
                    // ML Kit accurate mode + minFaceSize already filters most false positives
                    val faceArea = face.boundingBox.width() * face.boundingBox.height()
                    val imageArea = bitmap.width * bitmap.height
                    val relativeSize = faceArea.toFloat() / imageArea
                    
                    // Skip faces that are too small (< 3% of image) or too large (> 60%)
                    if (relativeSize < 0.03f || relativeSize > 0.6f) {
                        Log.d(TAG, "Skipping face: relativeSize=$relativeSize (likely false positive)")
                        return@mapNotNull null
                    }
                    
                    // Get or assign person ID from tracking
                    val trackingId = face.trackingId
                    val personTag = if (trackingId != null) {
                        getOrAssignPersonId(trackingId)
                    } else {
                        "unknown"
                    }
                    
                    JSONObject().apply {
                        put("x", face.boundingBox.centerX())
                        put("y", face.boundingBox.centerY())
                        put("width", face.boundingBox.width())
                        put("height", face.boundingBox.height())
                        put("personId", personTag)
                        put("trackingId", trackingId ?: -1)
                        put("relativeSize", String.format("%.3f", relativeSize))
                        // Classification confidence proxies (available with CLASSIFICATION_MODE_ALL)
                        put("smilingProbability", String.format("%.2f", face.smilingProbability))
                        put("leftEyeOpenProbability", String.format("%.2f", face.leftEyeOpenProbability))
                        put("rightEyeOpenProbability", String.format("%.2f", face.rightEyeOpenProbability))
                    }
                }
                
                if (faceData.isEmpty()) {
                    Log.d(TAG, "All faces filtered out as false positives")
                    return@addOnSuccessListener
                }
                
                val payload = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("faceCount", faceData.size)
                    put("faces", org.json.JSONArray(faceData))
                    put("totalPersons", lastKnownPersons.size)
                }
                
                publishMqtt("$TOPIC_ANALYTICS/faces", payload.toString())
                Log.d(TAG, "Published ${faceData.size} faces, ${lastKnownPersons.size} unique persons")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed", e)
            }
    }
    
    // Person tracking: maps ML Kit trackingId to stable person labels
    private val lastKnownPersons = mutableMapOf<Int, String>()
    private var personCounter = 0
    
    /**
     * Get or assign a stable person ID for a tracking ID.
     * Returns "Person1", "Person2", etc. for consistent MQTT tagging.
     */
    private fun getOrAssignPersonId(trackingId: Int): String {
        return lastKnownPersons.getOrPut(trackingId) {
            personCounter++
            "Person$personCounter"
        }
    }
    
    private fun sendSnapshotJpeg(jpegBytes: ByteArray) {
        try {
            // Encode JPEG bytes directly to Base64 (no recompression)
            val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            
            val payload = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("image", base64Image)
                put("size", jpegBytes.size)
            }
            
            publishMqtt(TOPIC_SNAPSHOT, payload.toString())
            Log.d(TAG, "Snapshot sent: ${jpegBytes.size} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send snapshot", e)
        }
    }
    
    private fun sendAnalytics() {
        val payload = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("frameCount", frameCount)
            put("facesDetected", faceDetectionCount)
            put("motionDetected", motionDetected)
            put("fps", frameCount / 5) // 5 second window
        }
        
        publishMqtt(TOPIC_ANALYTICS, payload.toString())
        frameCount = 0
    }
    
    private fun sendMotionAlert() {
        val payload = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("alert", "motion_detected")
            put("confidence", 0.85)
        }
        
        publishMqtt(TOPIC_MOTION, payload.toString())
    }
    
    private fun publishSecurityAlert(alert: SecurityAlert) {
        try {
            val json = JSONObject().apply {
                put("type", alert.type.name)
                put("timestamp", alert.timestamp)
                put("zone", alert.zone)
                put("confidence", alert.confidence)
                if (alert.location != null) {
                    put("location", JSONObject().apply {
                        put("x", alert.location.x)
                        put("y", alert.location.y)
                        put("yaw", alert.location.yaw)
                    })
                }
                // Metadata
                alert.metadata.forEach { (k, v) ->
                    when (v) {
                        is Int -> put(k, v)
                        is Long -> put(k, v)
                        is Float -> put(k, v.toDouble())
                        is String -> put(k, v)
                        is Boolean -> put(k, v)
                    }
                }
            }
            val topic = "temi/alerts/${alert.type.name.lowercase()}"
            publishMqtt(topic, json.toString())
            publishMqtt("temi/alerts/all", json.toString())
            Log.d(TAG, "Security alert published: ${alert.type} in ${alert.zone}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish security alert", e)
        }
    }
    
    /**
     * Load detection configuration from SharedPreferences.
     * Called every frame so settings changes take effect immediately.
     */
    private fun loadDetectionConfig(): DetectionConfig {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return DetectionConfig(
            loiteringEnabled = prefs.getBoolean(KEY_DETECT_LOITERING, true),
            smokingEnabled = prefs.getBoolean(KEY_DETECT_SMOKING, true),
            fallenPersonEnabled = prefs.getBoolean(KEY_DETECT_FALLEN, true),
            unattendedBagEnabled = prefs.getBoolean(MainActivity.KEY_DETECT_UNATTENDED_BAG, true),
            loiteringThresholdSec = prefs.getInt(KEY_LOITERING_THRESHOLD, 180),
            queueMaxPeople = prefs.getInt(KEY_QUEUE_MAX_PEOPLE, 5),
            unattendedBagThresholdSec = prefs.getInt("unattended_bag_threshold", 120)
        )
    }
    
    /**
     * Load security zones from SharedPreferences.
     * Falls back to ZoneDefaults if no custom zones saved.
     */
    private fun loadZonesFromPrefs(): List<com.example.temicontroller.models.SecurityZone> {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val zonesJson = prefs.getString(MainActivity.KEY_ZONES_JSON, null)
        return if (zonesJson != null) {
            try {
                val json = org.json.JSONArray(zonesJson)
                val zones = mutableListOf<com.example.temicontroller.models.SecurityZone>()
                for (i in 0 until json.length()) {
                    val z = json.getJSONObject(i)
                    val polygon = mutableListOf<com.example.temicontroller.models.SecurityPoint>()
                    val pointsArray = z.getJSONArray("polygon")
                    for (j in 0 until pointsArray.length()) {
                        val pt = pointsArray.getJSONObject(j)
                        polygon.add(com.example.temicontroller.models.SecurityPoint(
                            pt.getDouble("x").toFloat(),
                            pt.getDouble("y").toFloat()
                        ))
                    }
                    zones.add(com.example.temicontroller.models.SecurityZone(
                        id = z.getString("id"),
                        name = z.getString("name"),
                        polygon = polygon,
                        alertType = com.example.temicontroller.models.AlertType.valueOf(z.getString("alertType")),
                        threshold = z.getInt("threshold"),
                        cooldownMs = z.getLong("cooldownMs")
                    ))
                }
                zones
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse zones from prefs, using defaults", e)
                ZoneDefaults.defaultZones()
            }
        } else {
            ZoneDefaults.defaultZones()
        }
    }
    
    private fun connectMqtt() {
        // Read broker config from same SharedPreferences as MqttService (Issue #2 fix)
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val brokerIp = prefs.getString(MainActivity.KEY_BROKER_IP, "192.168.1.1") ?: "192.168.1.1"
        val brokerPort = prefs.getInt(MainActivity.KEY_BROKER_PORT, 1883)
        val brokerUrl = "tcp://$brokerIp:$brokerPort"
        Log.d(TAG, "Connecting to MQTT broker: $brokerUrl")
        
        Thread {
            try {
                mqttClient = MqttClient(
                    brokerUrl,
                    "temi-surveillance-${System.currentTimeMillis()}",
                    MemoryPersistence()
                )
                
                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                }
                
                mqttClient?.connect(options)
                Log.d(TAG, "MQTT connected")
            } catch (e: Exception) {
                Log.e(TAG, "MQTT connection failed", e)
            }
        }.start()
    }
    
    private fun publishMqtt(topic: String, payload: String) {
        try {
            if (mqttClient?.isConnected == true) {
                val message = MqttMessage(payload.toByteArray())
                mqttClient?.publish(topic, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish", e)
        }
    }
    
    private fun updateNotification() {
        val notification = createNotification()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopSurveillance()
        executor.shutdown()
        personDetector.close()
        faceDetector.close()
        try {
            mqttClient?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "MQTT disconnect error", e)
        }
    }
}