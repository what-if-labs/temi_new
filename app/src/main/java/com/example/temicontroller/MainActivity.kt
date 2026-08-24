package com.example.temicontroller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.temicontroller.databinding.ActivityMainBinding
import com.example.temicontroller.models.ZoneDefaults
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.permission.Permission
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var robot: Robot? = null
    private var mqttService: MqttService? = null
    
    // Periodic data publisher
    private var publishHandler: Handler? = null
    private var publishRunnable: Runnable? = null
    private val PUBLISH_INTERVAL_MS = 3000L

    // Position publisher (every 2 seconds)
    private var positionHandler: Handler? = null
    private var positionRunnable: Runnable? = null
    private val POSITION_PUBLISH_INTERVAL_MS = 2000L

    // Map publisher (event-driven)
    private var mapHandler: Handler? = null
    private var mapRunnable: Runnable? = null

    companion object {
        const val TAG = "TemiFace"
        const val PREFS_NAME = "TemiSettings"
        const val KEY_BROKER_IP = "broker_ip"
        const val KEY_BROKER_PORT = "broker_port"
        const val KEY_PATROL_ROUTE = "patrol_route"
        const val KEY_LOITERING_THRESHOLD = "loitering_threshold"
        const val KEY_QUEUE_MAX_PEOPLE = "queue_max_people"
        const val KEY_DETECT_LOITERING = "detect_loitering"
        const val KEY_DETECT_SMOKING = "detect_smoking"
        const val KEY_DETECT_FALLEN = "detect_fallen"
        const val KEY_DETECT_UNATTENDED_BAG = "detect_unattended_bag"
        const val KEY_DETECT_UNAUTHORIZED = "detect_unauthorized"
        const val KEY_ZONES_JSON = "zones_json"
    }
    
    // Map data listener - receives map when SDK loads it asynchronously
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mqttService = (service as MqttService.LocalBinder).getService()
            mqttService?.setCommandListener { command, params ->
                handleCommand(command, params)
            }
            startPeriodicPublishing()
            startPositionPublishing()
            startMapPublishing()
            
            // Publish data after MQTT connects (with a small delay to allow map to load)
            mqttService?.onMqttConnected = {
                Handler(mainLooper).postDelayed({
                    robot?.let { r ->
                        publishMapData()
                        publishLocationsWithCoordinates(r)
                    }
                }, 2000) // 2 second delay for map initialization
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mqttService = null
        }
    }

    private fun publishLocationsWithCoordinates(r: Robot) {
        GlobalScope.launch(Dispatchers.Main) {
            try {
                val robotId = r.serialNumber ?: "unknown"
                // Ensure MAP permission is granted
                if (r.checkSelfPermission(Permission.MAP) != Permission.GRANTED) {
                    Log.w(TAG, "Map permission not granted. Cannot fetch locations.")
                }

                val mapData = r.getMapData()
                val extractedLocations = mutableListOf<Map<String, Any>>()
                val extractedWalls = mutableListOf<Map<String, Any>>()

                if (mapData != null) {
                    // Filter for Locations (Category 4)
                    mapData.locations.forEach { layer ->
                        if (layer.layerCategory == 4) {
                            layer.layerPoses?.forEach { pose ->
                                extractedLocations.add(mapOf(
                                    "id" to layer.layerId,
                                    "name" to layer.layerId,
                                    "x" to pose.x,
                                    "y" to pose.y,
                                    "yaw" to pose.theta
                                ))
                            }
                        }
                    }

                    // Filter for Virtual Walls (Category 3)
                    val allWallLayers = mapData.virtualWalls + mapData.locations.filter { it.layerCategory == 3 }
                    allWallLayers.forEach { layer ->
                        if (layer.layerCategory == 3) {
                            val points = layer.layerPoses?.map { mapOf("x" to it.x, "y" to it.y) } ?: emptyList()
                            if (points.isNotEmpty()) {
                                extractedWalls.add(mapOf(
                                    "id" to layer.layerId,
                                    "points" to points
                                ))
                            }
                        }
                    }
                }

                mqttService?.publishLocations(robotId, extractedLocations)
                mqttService?.publishVirtualWalls(robotId, extractedWalls)
                Log.d(TAG, "Published ${extractedLocations.size} locations and ${extractedWalls.size} walls")
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing locations/walls", e)
            }
        }
    }
    
    /** Query location coordinates from TEMI ContentProvider */
    private fun queryLocationFromContentProvider(locName: String, r: Robot): Map<String, Any> {
        return try {
            val uri = android.net.Uri.parse("content://com.robotemi.sdk.provider/map/location")
            // Try name and location_name columns for compatibility
            val projection = arrayOf("name", "location_name", "x", "y", "yaw")
            val selection = "name = ? OR location_name = ?"
            val selectionArgs = arrayOf(locName, locName)
            
            contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val xIdx = cursor.getColumnIndex("x")
                    val yIdx = cursor.getColumnIndex("y")
                    val yawIdx = cursor.getColumnIndex("yaw")
                    
                    val x = if (xIdx >= 0) cursor.getFloat(xIdx) else 0f
                    val y = if (yIdx >= 0) cursor.getFloat(yIdx) else 0f
                    val yaw = if (yawIdx >= 0) cursor.getFloat(yawIdx) else 0f
                    
                    Log.d(TAG, "ContentProvider success for '$locName' -> x=$x, y=$y")
                    return mapOf(
                        "id" to locName,
                        "name" to locName,
                        "x" to x,
                        "y" to y,
                        "yaw" to yaw,
                        "source" to "content_provider"
                    )
                }
            }
            
            // Real fallback: return current robot position only if totally lost
            val pos = r.getPosition()
            Log.w(TAG, "No data for '$locName', using robot position fallback")
            mapOf(
                "id" to locName,
                "name" to locName,
                "x" to pos.x,
                "y" to pos.y,
                "yaw" to pos.yaw,
                "source" to "fallback_position"
            )
        } catch (e: Exception) {
            Log.e(TAG, "ContentProvider query failed for '$locName': ${e.message}")
            val pos = r.getPosition()
            mapOf(
                "id" to locName,
                "name" to locName,
                "x" to pos.x,
                "y" to pos.y,
                "yaw" to pos.yaw,
                "source" to "fallback_error"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        robot = try { Robot.getInstance() } catch (e: Exception) { null }
        
        // Add SDK listeners for position and locations
        robot?.let { r ->
            r.addOnCurrentPositionChangedListener(positionListener)
            r.addOnLocationsUpdatedListener(locationsListener)
            r.addOnMapNameChangedListener(mapNameListener)
            r.addOnMapElementsChangedListener(mapElementsListener)
            r.addOnGoToLocationStatusChangedListener(goToStatusListener)
            Log.d(TAG, "Added position, locations, map, and goTo status listeners")
            
            // Request MAP permission (required for SDK content provider to serve maps)
            if (r.checkSelfPermission(Permission.MAP) != Permission.GRANTED) {
                Log.d(TAG, "MAP permission not granted, requesting...")
                r.requestPermissions(listOf(Permission.MAP), 1)
            } else {
                Log.d(TAG, "MAP permission already granted")
            }
        }
        
        // Start MQTT service
        startMqttService()
        
        // Request POST_NOTIFICATIONS for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        
        // Set initial face
        binding.faceView.setState(FaceView.FaceState.IDLE)
        
        // Long press to show secret settings
        binding.faceView.setOnLongClickListener {
            showSettingsDialog()
            true
        }
        
        // Double tap to toggle between IDLE and HAPPY
        var lastTapTime = 0L
        binding.faceView.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 300) {
                // Double tap
                binding.faceView.setState(FaceView.FaceState.HAPPY)
                speak("Hello!")
                Handler(mainLooper).postDelayed({
                    binding.faceView.setState(FaceView.FaceState.IDLE)
                }, 2000)
            }
            lastTapTime = now
        }
        
        // Fade out hint after 5 seconds
        Handler(mainLooper).postDelayed({
            binding.tvHint.animate().alpha(0f).duration = 1000
        }, 5000)
    }
    
    private fun handleCommand(command: String, params: Map<String, String>) {
        Log.d(TAG, "handleCommand called: $command with params: $params")
        Log.d(TAG, "Robot instance: ${robot != null}")
        runOnUiThread {
            when (command) {
                "move_forward", "move_back", "turn_left", "turn_right" -> {
                    Log.d(TAG, "Movement command: $command")
                    binding.faceView.setState(FaceView.FaceState.MOVING)
                    robot?.let { r ->
                        Log.d(TAG, "Calling skidJoy for: $command")
                        when (command) {
                            "move_forward" -> r.skidJoy(1.0f, 0f)
                            "move_back" -> r.skidJoy(-1.0f, 0f)
                            "turn_left" -> r.skidJoy(0f, 1.0f)
                            "turn_right" -> r.skidJoy(0f, -1.0f)
                        }
                        Log.d(TAG, "skidJoy called successfully")
                    } ?: Log.e(TAG, "Robot instance is null!")
                    speak("Moving")
                    resetFaceAfterDelay()
                }
                "stop" -> {
                    binding.faceView.setState(FaceView.FaceState.IDLE)
                    robot?.skidJoy(0f, 0f)
                }
                "go_home" -> {
                    binding.faceView.setState(FaceView.FaceState.HAPPY)
                    robot?.goTo("home base")
                    speak("Going home")
                    resetFaceAfterDelay()
                }
                "go_to_location" -> {
                    val location = params["location"]
                    if (location != null) {
                        // Ensure map is loaded before navigating
                        val mapData = robot?.getMapData()
                        if (mapData == null || mapData.mapId.isEmpty() || mapData.mapImage.cols == 0) {
                            Log.w(TAG, "Map not loaded yet. Cannot navigate to $location")
                            binding.faceView.setState(FaceView.FaceState.CONFUSED)
                            speak("Map not loaded. Please select a map on the robot.")
                            mqttService?.publishCommand("navigation_error", mapOf(
                                "error" to "map_not_loaded",
                                "requested" to location
                            ))
                            Handler(mainLooper).postDelayed({
                                binding.faceView.setState(FaceView.FaceState.IDLE)
                            }, 3000)
                            return@runOnUiThread
                        }
                        
                        // Map is loaded, validate location
                        val knownLocations = robot?.locations ?: emptyList()
                        if (knownLocations.isNotEmpty() && knownLocations.contains(location)) {
                            binding.faceView.setState(FaceView.FaceState.MOVING)
                            robot?.goTo(location)
                            speak("Going to $location")
                            Log.d(TAG, "Navigating to: $location")
                            resetFaceAfterDelay()
                        } else if (knownLocations.isEmpty()) {
                            // Fallback: try anyway if list is empty but map is loaded
                            robot?.goTo(location)
                            Log.w(TAG, "Locations list empty, attempting navigation to $location anyway")
                        } else {
                            binding.faceView.setState(FaceView.FaceState.CONFUSED)
                            speak("Location $location not found on map")
                            mqttService?.publishCommand("navigation_error", mapOf(
                                "error" to "unknown_location",
                                "requested" to location,
                                "available" to knownLocations.joinToString(", ")
                            ))
                            Log.w(TAG, "go_to failed: '$location' not in $knownLocations")
                            Handler(mainLooper).postDelayed({
                                binding.faceView.setState(FaceView.FaceState.IDLE)
                            }, 3000)
                        }
                    }
                }
                "go_to_coordinates" -> {
                    val xStr = params["x"]
                    val yStr = params["y"]
                    val x = xStr?.toDoubleOrNull()
                    val y = yStr?.toDoubleOrNull()
                    if (x != null && y != null) {
                        // TEMI SDK doesn't support direct coordinate navigation in all versions
                        // Use the nearest known location or log the coordinates for manual navigation
                        val locations = robot?.locations ?: emptyList()
                        if (locations.isNotEmpty()) {
                            // Navigate to the first known location as fallback
                            val target = locations.first()
                            binding.faceView.setState(FaceView.FaceState.MOVING)
                            robot?.goTo(target)
                            speak("Navigating to $target")
                            Log.d(TAG, "Navigating to nearest location: $target (requested coords: $x, $y)")
                        } else {
                            binding.faceView.setState(FaceView.FaceState.CONFUSED)
                            speak("No locations available for navigation")
                            mqttService?.publishCommand("navigation_error", mapOf(
                                "error" to "no_locations",
                                "requested_coords" to "($x, $y)"
                            ))
                        }
                        resetFaceAfterDelay()
                    } else {
                        Log.w(TAG, "Invalid coordinates: x=$xStr, y=$yStr")
                        mqttService?.publishCommand("navigation_error", mapOf(
                            "error" to "invalid_coordinates",
                            "x" to (xStr ?: ""),
                            "y" to (yStr ?: "")
                        ))
                    }
                }
                "go_to_position" -> {
                    val xStr = params["x"]
                    val yStr = params["y"]
                    val thetaStr = params["theta"]
                    val x = xStr?.toDoubleOrNull()?.toFloat()
                    val y = yStr?.toDoubleOrNull()?.toFloat()
                    val theta = thetaStr?.toDoubleOrNull()?.toFloat() ?: 0f

                    if (x != null && y != null) {
                        try {
                            val mapData = robot?.getMapData()
                            if (mapData == null || mapData.mapId.isEmpty() || mapData.mapImage.cols == 0) {
                                Log.w(TAG, "Map not loaded yet. Cannot navigate to coordinates.")
                                binding.faceView.setState(FaceView.FaceState.CONFUSED)
                                speak("Map not loaded. Please select a map on the robot.")
                                mqttService?.publishCommand("navigation_error", mapOf(
                                    "error" to "map_not_loaded",
                                    "requested_x" to x.toString(),
                                    "requested_y" to y.toString()
                                ))
                                Handler(mainLooper).postDelayed({
                                    binding.faceView.setState(FaceView.FaceState.IDLE)
                                }, 3000)
                                return@runOnUiThread
                            }

                            binding.faceView.setState(FaceView.FaceState.MOVING)
                            val position = com.robotemi.sdk.navigation.model.Position(x, y, theta)
                            robot?.goToPosition(position)
                            speak("Going to position ${x.toInt()}, ${y.toInt()}")
                            Log.d(TAG, "Navigating to position: x=$x, y=$y, theta=$theta")
                            resetFaceAfterDelay()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error navigating to position: ${e.message}")
                            binding.faceView.setState(FaceView.FaceState.CONFUSED)
                            speak("Navigation failed")
                            mqttService?.publishCommand("navigation_error", mapOf(
                                "error" to "goToPosition_failed",
                                "x" to x.toString(),
                                "y" to y.toString(),
                                "theta" to theta.toString()
                            ))
                            Handler(mainLooper).postDelayed({
                                binding.faceView.setState(FaceView.FaceState.IDLE)
                            }, 3000)
                        }
                    } else {
                        Log.w(TAG, "Invalid position: x=$xStr, y=$yStr, theta=$thetaStr")
                        mqttService?.publishCommand("navigation_error", mapOf(
                            "error" to "invalid_position",
                            "x" to (xStr ?: ""),
                            "y" to (yStr ?: ""),
                            "theta" to (thetaStr ?: "")
                        ))
                    }
                }
                "follow_me" -> {
                    binding.faceView.setState(FaceView.FaceState.HAPPY)
                    robot?.beWithMe()
                    speak("Following you")
                }
                "speak" -> {
                    binding.faceView.setState(FaceView.FaceState.SPEAKING)
                    params["text"]?.let { 
                        speak(it)
                    }
                    resetFaceAfterDelay()
                }
                "tilt_up" -> {
                    binding.faceView.setState(FaceView.FaceState.THINKING)
                    robot?.tiltAngle(55, 1f)
                    resetFaceAfterDelay()
                }
                "tilt_down" -> {
                    binding.faceView.setState(FaceView.FaceState.SLEEPY)
                    robot?.tiltAngle(-25, 1f)
                    resetFaceAfterDelay()
                }
                "start_patrol" -> {
                    val route = params["route"] ?: "default"
                    binding.faceView.setState(FaceView.FaceState.PATROL_ACTIVE)
                    speak("Starting patrol on route $route")
                    Log.d(TAG, "Patrol started with route: $route")
                    // Publish patrol start confirmation
                    mqttService?.publishCommand("patrol_started", mapOf("route" to route))
                }
                "stop_patrol" -> {
                    binding.faceView.setState(FaceView.FaceState.IDLE)
                    robot?.skidJoy(0f, 0f)
                    speak("Patrol stopped")
                    Log.d(TAG, "Patrol stopped")
                    mqttService?.publishCommand("patrol_stopped")
                }
                "list_locations" -> {
                    val locations = robot?.locations ?: emptyList()
                    mqttService?.publishCommand("available_locations", mapOf(
                        "locations" to locations.joinToString(","),
                        "count" to locations.size.toString()
                    ))
                    Log.d(TAG, "Published available locations: $locations")
                }
            }
        }
    }
    
    private fun resetFaceAfterDelay() {
        binding.faceView.postDelayed({
            binding.faceView.setState(FaceView.FaceState.IDLE)
        }, 3000)
    }
    
    private fun speak(text: String) {
        robot?.speak(TtsRequest.create(text, false))
    }
    
    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etBrokerIp = dialogView.findViewById<EditText>(R.id.etBrokerIp)
        val etBrokerPort = dialogView.findViewById<EditText>(R.id.etBrokerPort)
        val spinnerPatrolRoute = dialogView.findViewById<Spinner>(R.id.spinnerPatrolRoute)
        val etLoiteringThreshold = dialogView.findViewById<EditText>(R.id.etLoiteringThreshold)
        val etQueueMaxPeople = dialogView.findViewById<EditText>(R.id.etQueueMaxPeople)
        val switchLoitering = dialogView.findViewById<Switch>(R.id.switchLoitering)
        val switchSmoking = dialogView.findViewById<Switch>(R.id.switchSmoking)
        val switchFallenPerson = dialogView.findViewById<Switch>(R.id.switchFallenPerson)
        val switchUnauthorizedAccess = dialogView.findViewById<Switch>(R.id.switchUnauthorizedAccess)
        val btnStartPatrol = dialogView.findViewById<Button>(R.id.btnStartPatrol)
        val btnStopPatrol = dialogView.findViewById<Button>(R.id.btnStopPatrol)
        
        // Load saved settings
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        etBrokerIp.setText(prefs.getString(KEY_BROKER_IP, "192.168.88.30"))
        etBrokerPort.setText(prefs.getInt(KEY_BROKER_PORT, 1883).toString())
        
        // Setup patrol route spinner
        val routes = resources.getStringArray(R.array.patrol_routes)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, routes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPatrolRoute.adapter = adapter
        val savedRouteIndex = prefs.getInt(KEY_PATROL_ROUTE, 0)
        spinnerPatrolRoute.setSelection(savedRouteIndex.coerceIn(0, routes.size - 1))
        
        // Load thresholds
        etLoiteringThreshold.setText(prefs.getInt(KEY_LOITERING_THRESHOLD, 180).toString())
        etQueueMaxPeople.setText(prefs.getInt(KEY_QUEUE_MAX_PEOPLE, 5).toString())
        
        // Load detection toggles
        switchLoitering.isChecked = prefs.getBoolean(KEY_DETECT_LOITERING, true)
        switchSmoking.isChecked = prefs.getBoolean(KEY_DETECT_SMOKING, true)
        switchFallenPerson.isChecked = prefs.getBoolean(KEY_DETECT_FALLEN, true)
        switchUnauthorizedAccess.isChecked = prefs.getBoolean(KEY_DETECT_UNAUTHORIZED, true)
        
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        
        dialogView.findViewById<Button>(R.id.btnSave).setOnClickListener {
            val ip = etBrokerIp.text.toString().trim()
            val port = etBrokerPort.text.toString().toIntOrNull() ?: 1883
            val loiteringThreshold = etLoiteringThreshold.text.toString().toIntOrNull() ?: 180
            val queueMaxPeople = etQueueMaxPeople.text.toString().toIntOrNull() ?: 5
            
            if (ip.isNotEmpty()) {
                prefs.edit()
                    .putString(KEY_BROKER_IP, ip)
                    .putInt(KEY_BROKER_PORT, port)
                    .putInt(KEY_PATROL_ROUTE, spinnerPatrolRoute.selectedItemPosition)
                    .putInt(KEY_LOITERING_THRESHOLD, loiteringThreshold)
                    .putInt(KEY_QUEUE_MAX_PEOPLE, queueMaxPeople)
                    .putBoolean(KEY_DETECT_LOITERING, switchLoitering.isChecked)
                    .putBoolean(KEY_DETECT_SMOKING, switchSmoking.isChecked)
                    .putBoolean(KEY_DETECT_FALLEN, switchFallenPerson.isChecked)
                    .putBoolean(KEY_DETECT_UNAUTHORIZED, switchUnauthorizedAccess.isChecked)
                    .apply()
                
                // Reconnect MQTT services with new broker config
                mqttService?.reconnectWithNewBroker()
                sendBroadcast(Intent(SurveillanceService.ACTION_RECONNECT_MQTT))
                
                // Publish detection settings via MQTT (after reconnect)
                Handler(mainLooper).postDelayed({
                    mqttService?.publishCommand("detection_settings", mapOf(
                        "loitering_enabled" to switchLoitering.isChecked,
                        "smoking_enabled" to switchSmoking.isChecked,
                        "fallen_person_enabled" to switchFallenPerson.isChecked,
                        "unauthorized_access_enabled" to switchUnauthorizedAccess.isChecked,
                        "loitering_threshold_seconds" to loiteringThreshold,
                        "queue_max_people" to queueMaxPeople
                    ))
                }, 2000)
                
                Toast.makeText(this, "Settings saved! Reconnecting to $ip:$port", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            } else {
                etBrokerIp.error = "IP required"
            }
        }
        
        // Start Patrol button
        btnStartPatrol.setOnClickListener {
            val selectedRoute = spinnerPatrolRoute.selectedItem.toString()
            val loiteringThreshold = etLoiteringThreshold.text.toString().toIntOrNull() ?: 180
            val queueMaxPeople = etQueueMaxPeople.text.toString().toIntOrNull() ?: 5
            
            mqttService?.publishCommand("start_patrol", mapOf(
                "route" to selectedRoute,
                "loitering_threshold" to loiteringThreshold,
                "queue_max_people" to queueMaxPeople
            ))
            
            binding.faceView.setState(FaceView.FaceState.PATROL_ACTIVE)
            speak("Starting patrol: $selectedRoute")
            Toast.makeText(this, "Patrol started", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        
        // Stop Patrol button
        btnStopPatrol.setOnClickListener {
            mqttService?.publishCommand("stop_patrol")
            binding.faceView.setState(FaceView.FaceState.IDLE)
            robot?.skidJoy(0f, 0f)
            speak("Patrol stopped")
            Toast.makeText(this, "Patrol stopped", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        
        // ===== Zone Calibration Section (Issue #4 fix) =====
        val spinnerZoneName = dialogView.findViewById<Spinner>(R.id.spinnerZoneName)
        val spinnerVertex = dialogView.findViewById<Spinner>(R.id.spinnerVertex)
        val btnCaptureVertex = dialogView.findViewById<Button>(R.id.btnCaptureVertex)
        val btnResetZones = dialogView.findViewById<Button>(R.id.btnResetZones)
        val tvCalibrationStatus = dialogView.findViewById<android.widget.TextView>(R.id.tvCalibrationStatus)
        
        // Setup zone name spinner
        val zoneNames = arrayOf("Lobby Drop-off", "Turnstile Queue", "Main Corridor")
        val zoneAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, zoneNames)
        spinnerZoneName.adapter = zoneAdapter
        
        // Setup vertex spinner (1-4)
        val vertexNames = arrayOf("Vertex 1", "Vertex 2", "Vertex 3", "Vertex 4")
        val vertexAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vertexNames)
        spinnerVertex.adapter = vertexAdapter
        
        // Load custom zones from prefs or use defaults
        val customZones = loadZonesFromPrefs(prefs)
        tvCalibrationStatus.text = "Loaded ${customZones.size} zone(s) from storage"
        
        // Capture current robot position as zone vertex
        btnCaptureVertex.setOnClickListener {
            val pos = robot?.getPosition()
            if (pos == null) {
                Toast.makeText(this, "Robot not available", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val zoneIndex = spinnerZoneName.selectedItemPosition
            val vertexIndex = spinnerVertex.selectedItemPosition
            val zones = loadZonesFromPrefs(prefs)
            val zone = zones[zoneIndex]
            
            // Update the vertex
            val newPolygon = zone.polygon.toMutableList()
            newPolygon[vertexIndex] = com.example.temicontroller.models.SecurityPoint(pos.x, pos.y)
            
            val updatedZone = zone.copy(polygon = newPolygon)
            zones[zoneIndex] = updatedZone
            
            // Save to prefs
            saveZonesToPrefs(prefs, zones)
            
            val pointStr = "(${String.format("%.2f", pos.x)}, ${String.format("%.2f", pos.y)})"
            tvCalibrationStatus.text = "Captured ${zone.name} Vertex ${vertexIndex + 1}: $pointStr"
            Toast.makeText(this, "Vertex saved!", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Calibration: ${zone.name} Vertex ${vertexIndex + 1} = $pointStr")
        }
        
        // Reset zones to defaults
        btnResetZones.setOnClickListener {
            saveZonesToPrefs(prefs, ZoneDefaults.defaultZones())
            tvCalibrationStatus.text = "Zones reset to defaults"
            Toast.makeText(this, "Zones reset to defaults", Toast.LENGTH_SHORT).show()
        }
        
        dialog.show()
    }
    
    /**
     * Load security zones from SharedPreferences.
     * Falls back to ZoneDefaults if no custom zones saved.
     */
    private fun loadZonesFromPrefs(prefs: android.content.SharedPreferences): MutableList<com.example.temicontroller.models.SecurityZone> {
        val zonesJson = prefs.getString(KEY_ZONES_JSON, null)
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
                ZoneDefaults.defaultZones().toMutableList()
            }
        } else {
            ZoneDefaults.defaultZones().toMutableList()
        }
    }
    
    /**
     * Save security zones to SharedPreferences as JSON.
     */
    private fun saveZonesToPrefs(
        prefs: android.content.SharedPreferences,
        zones: List<com.example.temicontroller.models.SecurityZone>
    ) {
        try {
            val json = org.json.JSONArray()
            for (zone in zones) {
                val z = org.json.JSONObject().apply {
                    put("id", zone.id)
                    put("name", zone.name)
                    put("alertType", zone.alertType.name)
                    put("threshold", zone.threshold)
                    put("cooldownMs", zone.cooldownMs)
                    val pointsArray = org.json.JSONArray()
                    for (pt in zone.polygon) {
                        pointsArray.put(org.json.JSONObject().apply {
                            put("x", pt.x)
                            put("y", pt.y)
                        })
                    }
                    put("polygon", pointsArray)
                }
                json.put(z)
            }
            prefs.edit().putString(KEY_ZONES_JSON, json.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save zones to prefs", e)
        }
    }
    
    private fun startMqttService() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val brokerIp = prefs.getString(KEY_BROKER_IP, "192.168.4.154") ?: "192.168.4.154"
        val brokerPort = prefs.getInt(KEY_BROKER_PORT, 1883)
        
        val intent = Intent(this, MqttService::class.java).apply {
            putExtra("broker_ip", brokerIp)
            putExtra("broker_port", brokerPort)
        }
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun startPeriodicPublishing() {
        publishHandler = Handler(mainLooper)
        publishRunnable = object : Runnable {
            override fun run() {
                publishRobotData()
                publishHandler?.postDelayed(this, PUBLISH_INTERVAL_MS)
            }
        }
        publishHandler?.post(publishRunnable!!)
        Log.d(TAG, "Started periodic publishing every ${PUBLISH_INTERVAL_MS}ms")
    }
    
    private fun stopPeriodicPublishing() {
        publishRunnable?.let { publishHandler?.removeCallbacks(it) }
        publishRunnable = null
        publishHandler = null
        
        positionRunnable?.let { positionHandler?.removeCallbacks(it) }
        positionRunnable = null
        positionHandler = null
        
        mapRunnable?.let { mapHandler?.removeCallbacks(it) }
        mapRunnable = null
        mapHandler = null
    }
    
    private fun startMapPublishing() {
        // We no longer publish map periodically. 
        // Initial publish is handled in onMqttConnected.
        // Subsequent updates are handled by listeners.
        Log.d(TAG, "Map publishing initialized (event-driven)")
    }
    
    private fun startPositionPublishing() {
        positionHandler = Handler(mainLooper)
        positionRunnable = object : Runnable {
            override fun run() {
                publishCurrentPosition()
                positionHandler?.postDelayed(this, POSITION_PUBLISH_INTERVAL_MS)
            }
        }
        positionHandler?.post(positionRunnable!!)
        Log.d(TAG, "Started position publishing every ${POSITION_PUBLISH_INTERVAL_MS}ms")
    }
    
    private fun publishCurrentPosition() {
        robot?.let { r ->
            try {
                val robotId = r.serialNumber ?: "unknown"
                val position = r.getPosition()
                mqttService?.publishPosition(robotId, position.x, position.y, position.yaw)
                Log.d(TAG, "Periodic position published: x=${position.x}, y=${position.y}, yaw=${position.yaw}")
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing periodic position", e)
            }
        }
    }

    private fun compressGzip(data: List<Int>): String {
        val byteArray = data.map { it.toByte() }.toByteArray()
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(byteArray) }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun publishMapData() {
        robot?.let { r ->
            try {
                val robotId = r.serialNumber ?: "unknown"
                if (r.checkSelfPermission(Permission.MAP) != Permission.GRANTED) return

                val currentMapData = r.getMapData() ?: return
                if (currentMapData.mapId.isEmpty()) return

                val mapImage = currentMapData.mapImage
                if (mapImage.data.isEmpty()) return

                // GZIP compress the raw occupancy grid
                val compressedData = compressGzip(mapImage.data)
                
                // Get map info
                val mapInfo = currentMapData.mapInfo
                
                mqttService?.publishMap(
                    robotId,
                    compressedData,
                    mapImage.cols,
                    mapImage.rows,
                    mapInfo.resolution,
                    mapInfo.originX,
                    mapInfo.originY
                )
                Log.d(TAG, "Map published successfully (GZIP)")
            } catch (e: Exception) {
                Log.e(TAG, "Error in publishMapData", e)
            }
        }
    }

    private fun publishRobotData() {
        robot?.let { r ->
            try {
                val robotId = r.serialNumber ?: "unknown"
                val batteryData = r.batteryData
                if (batteryData != null) {
                    mqttService?.publishBattery(robotId, batteryData.level, batteryData.isCharging)
                } else {
                    val batteryIntent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                    val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || 
                                     status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    mqttService?.publishBattery(robotId, batteryPct, isCharging)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing robot data", e)
            }
        }
    }
    
    // Position listener for MQTT publishing
    private val positionListener = object : com.robotemi.sdk.navigation.listener.OnCurrentPositionChangedListener {
        override fun onCurrentPositionChanged(position: com.robotemi.sdk.navigation.model.Position) {
            try {
                val robotId = robot?.serialNumber ?: "unknown"
                mqttService?.publishPosition(robotId, position.x, position.y, position.yaw)
                Log.d(TAG, "Position published: x=${position.x}, y=${position.y}, yaw=${position.yaw}")
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing position", e)
            }
        }
    }
    
    // Map name listener to clear cache and republish
    private val mapNameListener = object : com.robotemi.sdk.map.OnMapNameChangedListener {
        override fun onMapNameChanged(mapName: String) {
            Log.d(TAG, "Map name changed to $mapName, republishing")
            publishMapData()
            // Also refresh locations since they are map-dependent
            robot?.let { publishLocationsWithCoordinates(it) }
        }
    }

    // Map elements listener to republish when locations/walls change
    private val mapElementsListener = object : com.robotemi.sdk.map.OnMapElementsChangedListener {
        override fun onMapElementsChanged() {
            Log.d(TAG, "Map elements changed, republishing locations")
            robot?.let { publishLocationsWithCoordinates(it) }
        }
    }

    // Locations listener for MQTT publishing
    private val locationsListener = object : com.robotemi.sdk.listeners.OnLocationsUpdatedListener {
        override fun onLocationsUpdated(locations: List<String>) {
            robot?.let { r ->
                publishLocationsWithCoordinates(r)
            }
        }
    }
    
    // Navigation status listener for go_to_location feedback
    private val goToStatusListener = object : com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener {
        override fun onGoToLocationStatusChanged(location: String, status: String, descriptionId: Int, description: String) {
            Log.d(TAG, "GoTo status: $status -> $location ($description)")
            when (status) {
                "complete" -> {
                    runOnUiThread {
                        binding.faceView.setState(FaceView.FaceState.HAPPY)
                        speak("Arrived at $location")
                        mqttService?.publishCommand("navigation_status", mapOf(
                            "status" to "complete",
                            "location" to location
                        ))
                        mqttService?.publishNavigationStatus("success", "arrived at $location")
                        Handler(mainLooper).postDelayed({
                            binding.faceView.setState(FaceView.FaceState.IDLE)
                        }, 2000)
                    }
                }
                "error" -> {
                    runOnUiThread {
                        binding.faceView.setState(FaceView.FaceState.CONFUSED)
                        speak("Could not reach $location")
                        mqttService?.publishCommand("navigation_status", mapOf(
                            "status" to "error",
                            "location" to location,
                            "description" to description
                        ))
                        mqttService?.publishNavigationStatus("error", "could not reach $location: $description")
                        Handler(mainLooper).postDelayed({
                            binding.faceView.setState(FaceView.FaceState.IDLE)
                        }, 3000)
                    }
                }
                "moving" -> {
                    runOnUiThread {
                        binding.faceView.setState(FaceView.FaceState.MOVING)
                    }
                }
                "blocked" -> {
                    runOnUiThread {
                        mqttService?.publishCommand("navigation_status", mapOf(
                            "status" to "blocked",
                            "location" to location,
                            "description" to description
                        ))
                        mqttService?.publishNavigationStatus("blocked", "path blocked to $location")
                    }
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicPublishing()
        robot?.removeOnCurrentPositionChangedListener(positionListener)
        robot?.removeOnLocationsUpdateListener(locationsListener)
        robot?.removeOnMapNameChangedListener(mapNameListener)
        robot?.removeOnMapElementsChangedListener(mapElementsListener)
        robot?.removeOnGoToLocationStatusChangedListener(goToStatusListener)
        unbindService(serviceConnection)
    }
}
