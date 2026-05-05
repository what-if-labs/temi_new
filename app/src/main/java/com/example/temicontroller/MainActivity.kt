package com.example.temicontroller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var robot: Robot? = null
    private var mqttService: MqttService? = null
    
    // Periodic data publisher
    private var publishHandler: Handler? = null
    private var publishRunnable: Runnable? = null
    private val PUBLISH_INTERVAL_MS = 3000L
    
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
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mqttService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        robot = try { Robot.getInstance() } catch (e: Exception) { null }
        
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
        runOnUiThread {
            when (command) {
                "move_forward", "move_back", "turn_left", "turn_right" -> {
                    binding.faceView.setState(FaceView.FaceState.MOVING)
                    robot?.let { r ->
                        when (command) {
                            "move_forward" -> r.skidJoy(1.0f, 0f)
                            "move_back" -> r.skidJoy(-1.0f, 0f)
                            "turn_left" -> r.skidJoy(0f, 1.0f)
                            "turn_right" -> r.skidJoy(0f, -1.0f)
                        }
                    }
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
        etBrokerIp.setText(prefs.getString(KEY_BROKER_IP, "192.168.7.31"))
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
        val brokerIp = prefs.getString(KEY_BROKER_IP, "192.168.7.31") ?: "192.168.7.31"
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
    }
    
    private fun publishRobotData() {
        robot?.let { r ->
            try {
                // Publish position
                val position = r.position
                mqttService?.publishPosition(position.x, position.y, position.yaw)
                
                // Publish locations
                val locations = r.locations.map { loc ->
                    mapOf(
                        "id" to loc,
                        "name" to loc,
                        "x" to 0,
                        "y" to 0
                    )
                }
                mqttService?.publishLocations(locations)
                
                // Publish battery
                val batteryIntent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || 
                                 status == android.os.BatteryManager.BATTERY_STATUS_FULL
                mqttService?.publishBattery(batteryPct, isCharging)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing robot data", e)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicPublishing()
        unbindService(serviceConnection)
    }
}
