package com.example.temicontroller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.temicontroller.databinding.ActivityMainBinding
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.map.Layer
import com.robotemi.sdk.map.MapDataModel
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
    
    // Map publisher (less frequent)
    private var mapHandler: Handler? = null
    private var mapRunnable: Runnable? = null
    private val MAP_PUBLISH_INTERVAL_MS = 30000L  // Every 30 seconds
    
    companion object {
        const val TAG = "TemiFace"
        const val PREFS_NAME = "TemiSettings"
        const val KEY_BROKER_IP = "broker_ip"
        const val KEY_BROKER_PORT = "broker_port"
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mqttService = (service as MqttService.LocalBinder).getService()
            mqttService?.setCommandListener { command, params ->
                handleCommand(command, params)
            }
            startPeriodicPublishing()
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
        // Try to get coordinates from robot position instead of map data
        // (getMapData() requires content provider access that may not be available)
        GlobalScope.launch(Dispatchers.Main) {
            try {
                val locationNames = r.locations
                Log.d(TAG, "Locations: ${locationNames.size}, Names: $locationNames")
                
                if (locationNames.isEmpty()) {
                    Log.w(TAG, "No locations found")
                    return@launch
                }
                
                // Get current robot position as reference
                val position = r.getPosition()
                Log.d(TAG, "Current position: x=${position.x}, y=${position.y}, yaw=${position.yaw}")
                
                // For now, publish locations with current position as reference
                // In a real scenario, you'd navigate to each location and record position
                val locationsWithCoords = locationNames.map { locName ->
                    mapOf(
                        "id" to locName,
                        "name" to locName,
                        "x" to position.x,
                        "y" to position.y,
                        "yaw" to position.yaw,
                        "isCurrent" to (locName == "home base") // Mark home base as current position
                    )
                }
                
                mqttService?.publishLocations(locationsWithCoords)
                Log.d(TAG, "Published locations with position: ${locationsWithCoords.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing locations", e)
            }
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
            Log.d(TAG, "Added position and locations listeners")
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
                    robot?.goTo("home")
                    speak("Going home")
                    resetFaceAfterDelay()
                }
                "go_to_location" -> {
                    val location = params["location"]
                    if (location != null) {
                        binding.faceView.setState(FaceView.FaceState.HAPPY)
                        robot?.goTo(location)
                        speak("Going to $location")
                        resetFaceAfterDelay()
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
        
        // Load saved settings
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        etBrokerIp.setText(prefs.getString(KEY_BROKER_IP, "192.168.4.34"))
        etBrokerPort.setText(prefs.getInt(KEY_BROKER_PORT, 1883).toString())
        
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        
        dialogView.findViewById<Button>(R.id.btnSave).setOnClickListener {
            val ip = etBrokerIp.text.toString().trim()
            val port = etBrokerPort.text.toString().toIntOrNull() ?: 1883
            
            if (ip.isNotEmpty()) {
                prefs.edit()
                    .putString(KEY_BROKER_IP, ip)
                    .putInt(KEY_BROKER_PORT, port)
                    .apply()
                
                Toast.makeText(this, "Settings saved! Restart app to apply.", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            } else {
                etBrokerIp.error = "IP required"
            }
        }
        
        dialog.show()
    }
    
    private fun startMqttService() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val brokerIp = prefs.getString(KEY_BROKER_IP, "192.168.4.34") ?: "192.168.4.34"
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
    
    private fun publishMapData() {
        robot?.let { r ->
            try {
                // Get map data from TEMI SDK
                val mapDataModel = r.getMapData()
                if (mapDataModel != null) {
                    val mapImage = mapDataModel.mapImage
                    
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
                    val base64Image = Base64.encodeToString(byteArray, Base64.DEFAULT)
                    
                    mqttService?.publishMap(base64Image, bitmap.width, bitmap.height)
                    Log.d(TAG, "Map published: ${bitmap.width}x${bitmap.height}")
                    
                    // Recycle bitmap to free memory
                    bitmap.recycle()
                } else {
                    Log.d(TAG, "No map data available from TEMI")
                }
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
    
    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicPublishing()
        unbindService(serviceConnection)
    }
}
