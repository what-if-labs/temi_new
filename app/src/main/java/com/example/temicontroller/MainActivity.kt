package com.example.temicontroller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import com.robotemi.sdk.map.Layer
import com.robotemi.sdk.map.MapDataModel
import com.robotemi.sdk.permission.Permission
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // Position publisher (every 5 seconds to distinguish from saved locations)
    private var positionHandler: Handler? = null
    private var positionRunnable: Runnable? = null
    private val POSITION_PUBLISH_INTERVAL_MS = 5000L

    // Map publisher (less frequent)
    private var mapHandler: Handler? = null
    private var mapRunnable: Runnable? = null
    private val MAP_PUBLISH_INTERVAL_MS = 30000L  // Every 30 seconds

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
        const val TARGET_MAP_NAME = "eunos1"
    }
    
    // Map data listener - receives map when SDK loads it asynchronously
    private var mapDataModel: MapDataModel? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mqttService = (service as MqttService.LocalBinder).getService()
            mqttService?.setCommandListener { command, params ->
                handleCommand(command, params)
            }
            startPeriodicPublishing()
            startPositionPublishing()
            startMapPublishing()
            
            // Publish locations after MQTT connects
            mqttService?.onMqttConnected = {
                robot?.let { r ->
                    publishLocationsWithCoordinates(r)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mqttService = null
        }
    }

    private fun publishLocationsWithCoordinates(r: Robot) {
        GlobalScope.launch(Dispatchers.Main) {
            try {
                val locationNames = r.locations
                Log.d(TAG, "Locations: ${locationNames.size}, Names: $locationNames")
                
                if (locationNames.isEmpty()) {
                    Log.w(TAG, "No locations found")
                    return@launch
                }
                
                // Try multiple strategies to get location coordinates
                val locationsWithCoords = try {
                    // Strategy 1: Try getCurrentFloor() first
                    val currentFloor = r.getCurrentFloor()
                    if (currentFloor != null && currentFloor.locations.isNotEmpty()) {
                        val floorLocations: List<com.robotemi.sdk.map.Location> = currentFloor.locations
                        Log.d(TAG, "Floor locations count: ${floorLocations.size}")
                        
                        val locationMap = floorLocations.associate { loc ->
                            loc.name to mapOf(
                                "id" to loc.name,
                                "name" to loc.name,
                                "x" to loc.x,
                                "y" to loc.y,
                                "yaw" to loc.yaw,
                                "tiltAngle" to loc.tiltAngle,
                                "source" to "floor"
                            )
                        }
                        
                        Log.d(TAG, "Location map keys: ${locationMap.keys}")
                        
                        locationNames.map { locName ->
                            locationMap[locName] ?: run {
                                Log.w(TAG, "No floor coords for '$locName', using ContentProvider")
                                queryLocationFromContentProvider(locName, r)
                            }
                        }
                    } else {
                        // Strategy 2: Fall back to ContentProvider for each location
                        Log.w(TAG, "No floor data, fetching locations via ContentProvider")
                        locationNames.map { locName ->
                            queryLocationFromContentProvider(locName, r)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading floor data: ${e.message}, using ContentProvider")
                    locationNames.map { locName ->
                        queryLocationFromContentProvider(locName, r)
                    }
                }
                
                mqttService?.publishLocations(locationsWithCoords)
                Log.d(TAG, "Published ${locationsWithCoords.size} locations with coordinates")
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing locations", e)
            }
        }
    }
    
    /** Query location coordinates from TEMI ContentProvider */
    private fun queryLocationFromContentProvider(locName: String, r: Robot): Map<String, Any> {
        return try {
            val uri = android.net.Uri.parse("content://com.robotemi.sdk.provider/map/location")
            val projection = arrayOf("name", "x", "y", "yaw")
            val selection = "name = ?"
            val selectionArgs = arrayOf(locName)
            
            contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex("name")
                    val xIdx = cursor.getColumnIndex("x")
                    val yIdx = cursor.getColumnIndex("y")
                    val yawIdx = cursor.getColumnIndex("yaw")
                    
                    val x = if (xIdx >= 0) cursor.getFloat(xIdx) else 0f
                    val y = if (yIdx >= 0) cursor.getFloat(yIdx) else 0f
                    val yaw = if (yawIdx >= 0) cursor.getFloat(yawIdx) else 0f
                    
                    Log.d(TAG, "ContentProvider('$locName') -> x=$x, y=$y, yaw=$yaw")
                    return mapOf(
                        "id" to locName,
                        "name" to locName,
                        "x" to x,
                        "y" to y,
                        "yaw" to yaw,
                        "tiltAngle" to 0f,
                        "source" to "content_provider"
                    )
                }
            }
            
            // ContentProvider failed — last resort: use robot position
            Log.w(TAG, "ContentProvider returned no data for '$locName', using robot position")
            val pos = r.getPosition()
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
            r.addOnGoToLocationStatusChangedListener(goToStatusListener)
            Log.d(TAG, "Added position, locations, and goTo status listeners")
            
            // Request MAP permission (required for SDK content provider to serve maps)
            if (r.checkSelfPermission(Permission.MAP) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "MAP permission not granted, requesting...")
                r.requestPermissions(listOf(Permission.MAP), 1)
            } else {
                Log.d(TAG, "MAP permission already granted")
            }
            
            // Try to load the target map immediately (use the 3-arg form from working app)
            Log.d(TAG, "Attempting to load map: $TARGET_MAP_NAME")
            try {
                r.loadMap(TARGET_MAP_NAME, false, null)
                Log.d(TAG, "loadMap('$TARGET_MAP_NAME', false, null) called")
            } catch (e: Exception) {
                Log.e(TAG, "loadMap failed: ${e.message}")
            }
        }
        
        // Start MQTT service
        startMqttService()
        
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
                        if (mapData == null || mapData.mapImage == null || mapData.mapImage.cols == 0) {
                            Log.w(TAG, "Map not loaded yet, loading $TARGET_MAP_NAME before navigation")
                            try {
                                robot?.loadMap(TARGET_MAP_NAME, false, null)
                                // Wait briefly then retry
                                Handler(mainLooper).postDelayed({
                                    val location2 = params["location"]
                                    robot?.let { r ->
                                        val locations2 = r.locations
                                        if (locations2.isEmpty() || locations2.contains(location2)) {
                                            binding.faceView.setState(FaceView.FaceState.MOVING)
                                            r.goTo(location2!!)
                                            speak("Going to $location2")
                                            Log.d(TAG, "Navigating (after map load) to: $location2")
                                            resetFaceAfterDelay()
                                        } else {
                                            binding.faceView.setState(FaceView.FaceState.CONFUSED)
                                            speak("Location $location2 not found")
                                            Handler(mainLooper).postDelayed({
                                                binding.faceView.setState(FaceView.FaceState.IDLE)
                                            }, 3000)
                                        }
                                    }
                                }, 2000)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to load map: ${e.message}")
                                binding.faceView.setState(FaceView.FaceState.CONFUSED)
                                speak("Map not available")
                                mqttService?.publishCommand("navigation_error", mapOf(
                                    "error" to "map_not_loaded",
                                    "requested" to location
                                ))
                                Handler(mainLooper).postDelayed({
                                    binding.faceView.setState(FaceView.FaceState.IDLE)
                                }, 3000)
                            }
                            return@runOnUiThread
                        }
                        
                        // Map is loaded, validate location
                        val knownLocations = robot?.locations ?: emptyList()
                        if (knownLocations.isEmpty() || knownLocations.contains(location)) {
                            binding.faceView.setState(FaceView.FaceState.MOVING)
                            robot?.goTo(location)
                            speak("Going to $location")
                            Log.d(TAG, "Navigating to: $location (locations: $knownLocations)")
                            resetFaceAfterDelay()
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
                
                // Publish detection settings via MQTT
                mqttService?.publishCommand("detection_settings", mapOf(
                    "loitering_enabled" to switchLoitering.isChecked,
                    "smoking_enabled" to switchSmoking.isChecked,
                    "fallen_person_enabled" to switchFallenPerson.isChecked,
                    "unauthorized_access_enabled" to switchUnauthorizedAccess.isChecked,
                    "loitering_threshold_seconds" to loiteringThreshold,
                    "queue_max_people" to queueMaxPeople
                ))
                
                Toast.makeText(this, "Settings saved!", Toast.LENGTH_LONG).show()
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
        mapHandler = Handler(mainLooper)
        mapRunnable = object : Runnable {
            override fun run() {
                publishMapData()
                mapHandler?.postDelayed(this, MAP_PUBLISH_INTERVAL_MS)
            }
        }
        mapHandler?.post(mapRunnable!!)
        Log.d(TAG, "Started map publishing every ${MAP_PUBLISH_INTERVAL_MS}ms")
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
                val position = r.getPosition()
                mqttService?.publishPosition(position.x, position.y, position.yaw)
                Log.d(TAG, "Periodic position published: x=${position.x}, y=${position.y}, yaw=${position.yaw}")
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing periodic position", e)
            }
        }
    }
    
    private fun publishMapData() {
        robot?.let { r ->
            try {
                // If we already have cached map data, use it directly
                var cachedMapData = this.mapDataModel
                
                if (cachedMapData == null) {
                    // Try getMapData() first
                    cachedMapData = r.getMapData()
                    Log.d(TAG, "getMapData() returned: ${if (cachedMapData != null) "map ${cachedMapData.mapImage.cols}x${cachedMapData.mapImage.rows}" else "null"}")
                }
                
                // Still null — enumerate maps and load by ID (matching working temi-mqtt-bridge pattern)
                if (cachedMapData == null) {
                    try {
                        val mapList = r.getMapList()
                        Log.d(TAG, "getMapList() returned ${mapList.size} maps")
                        if (mapList.isNotEmpty()) {
                            mapList.forEach { m ->
                                Log.d(TAG, "Map entry: name='${m.name}', id='${m.id}'")
                            }
                            // Try to find target map by name, otherwise use first
                            val target = mapList.find { it.name.equals(TARGET_MAP_NAME, ignoreCase = true) } ?: mapList.first()
                            Log.d(TAG, "Loading map: '${target.name}' (id='${target.id}')")
                            r.loadMap(target.id, false, null)
                            // Wait for SDK to process
                            Thread.sleep(2000)
                            cachedMapData = r.getMapData()
                            Log.d(TAG, "getMapData() after loadMap: ${if (cachedMapData != null) "map ${cachedMapData.mapImage.cols}x${cachedMapData.mapImage.rows}" else "null"}")
                        } else {
                            Log.w(TAG, "SDK getMapList() is empty")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error enumerating/loading maps: ${e.message}")
                    }
                }
                
                // Cache the model for future use
                if (cachedMapData != null) {
                    this.mapDataModel = cachedMapData
                    Log.d(TAG, "Map data loaded: ${cachedMapData.mapImage.cols}x${cachedMapData.mapImage.rows}")
                }
                
                if (cachedMapData == null) {
                    Log.w(TAG, "Still no map data after loadMap attempt")
                    return
                }
                
                val mapImage = cachedMapData.mapImage
                
                // Compute world coordinate bounds from Floor locations
                // Try getCurrentFloor() first, fall back to individual location lookups
                val currentFloor = r.getCurrentFloor()
                var boundsMinX: Float? = null
                var boundsMinY: Float? = null
                var boundsMaxX: Float? = null
                var boundsMaxY: Float? = null
                
                if (currentFloor != null && currentFloor.locations.isNotEmpty()) {
                    val xs = currentFloor.locations.map { it.x }
                    val ys = currentFloor.locations.map { it.y }
                    boundsMinX = xs.minOrNull()
                    boundsMinY = ys.minOrNull()
                    boundsMaxX = xs.maxOrNull()
                    boundsMaxY = ys.maxOrNull()
                    Log.d(TAG, "Floor bounds: [$boundsMinX,$boundsMinY]-[$boundsMaxX,$boundsMaxY]")
                } else {
                    // Fallback: get bounds from individual location lookups via ContentProvider
                    try {
                        val locationNames = r.locations
                        if (locationNames.isNotEmpty()) {
                            val xs = mutableListOf<Float>()
                            val ys = mutableListOf<Float>()
                            for (name in locationNames) {
                                val uri = android.net.Uri.parse("content://com.robotemi.sdk.provider/map/location")
                                contentResolver.query(
                                    uri, arrayOf("x", "y"), "name = ?", arrayOf(name), null
                                )?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        val xIdx = cursor.getColumnIndex("x")
                                        val yIdx = cursor.getColumnIndex("y")
                                        if (xIdx >= 0) xs.add(cursor.getFloat(xIdx))
                                        if (yIdx >= 0) ys.add(cursor.getFloat(yIdx))
                                    }
                                }
                            }
                            if (xs.isNotEmpty()) {
                                boundsMinX = xs.minOrNull()
                                boundsMinY = ys.minOrNull()
                                boundsMaxX = xs.maxOrNull()
                                boundsMaxY = ys.maxOrNull()
                                Log.d(TAG, "ContentProvider-based bounds: [$boundsMinX,$boundsMinY]-[$boundsMaxX,$boundsMaxY]")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to compute bounds from ContentProvider: ${e.message}")
                    }
                }
                
                // Convert map data to bitmap
                val bitmap = Bitmap.createBitmap(
                    mapImage.data.map { android.graphics.Color.argb((it * 2.55).toInt(), 0, 0, 0) }.toIntArray(),
                    mapImage.cols,
                    mapImage.rows,
                    Bitmap.Config.ARGB_8888
                )
                
                // Convert bitmap to base64 PNG
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val byteArray = stream.toByteArray()
                val base64Image = Base64.encodeToString(byteArray, Base64.NO_WRAP)
                
                mqttService?.publishMap(base64Image, bitmap.width, bitmap.height, boundsMinX, boundsMinY, boundsMaxX, boundsMaxY)
                Log.d(TAG, "Map published: ${bitmap.width}x${bitmap.height}")
                
                // Recycle bitmap to free memory
                bitmap.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing map data: ${e.message}")
            }
        }
    }
    
    private fun publishRobotData() {
        robot?.let { r ->
            try {
                // Publish TEMI robot battery (not tablet battery)
                val batteryData = r.batteryData
                if (batteryData != null) {
                    val batteryPct = batteryData.level
                    val isCharging = batteryData.isCharging
                    mqttService?.publishBattery(batteryPct, isCharging)
                    Log.d(TAG, "Published TEMI battery: $batteryPct%, charging=$isCharging")
                } else {
                    // Fallback to tablet battery if TEMI battery unavailable
                    val batteryIntent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                    val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || 
                                     status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    mqttService?.publishBattery(batteryPct, isCharging)
                    Log.d(TAG, "Published tablet battery (fallback): $batteryPct%")
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
                mqttService?.publishPosition(position.x, position.y, position.yaw)
                Log.d(TAG, "Position published: x=${position.x}, y=${position.y}, yaw=${position.yaw}")
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing position", e)
            }
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
        robot?.removeOnGoToLocationStatusChangedListener(goToStatusListener)
        unbindService(serviceConnection)
    }
}
