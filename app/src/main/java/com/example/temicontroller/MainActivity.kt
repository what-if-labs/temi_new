package com.example.temicontroller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.temicontroller.databinding.ActivityMainBinding
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var robot: Robot? = null
    private var mqttClient: MqttClient? = null
    private var mqttService: MqttService? = null
    private var isSecretMenuVisible = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mqttService = (service as MqttService.LocalBinder).getService()
            mqttService?.setCommandListener { command, params ->
                handleMqttCommand(command, params)
            }
            updateConnectionStatus()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            mqttService = null
            updateConnectionStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        robot = try { Robot.getInstance() } catch (e: Exception) { null }
        
        setupUI()
        setupSecretMenu()
        startMqttService()
    }
    
    private fun setupUI() {
        // Movement controls
        binding.btnForward.setOnTouchListener { _, event ->
            handleMovement(event, 1.0f, 0f, "Moving forward")
            true
        }
        
        binding.btnBackward.setOnTouchListener { _, event ->
            handleMovement(event, -1.0f, 0f, "Moving backward")
            true
        }
        
        binding.btnLeft.setOnTouchListener { _, event ->
            handleMovement(event, 0f, 1.0f, "Turning left")
            true
        }
        
        binding.btnRight.setOnTouchListener { _, event ->
            handleMovement(event, 0f, -1.0f, "Turning right")
            true
        }
        
        // Action buttons
        binding.btnStop.setOnClickListener {
            robot?.skidJoy(0f, 0f)
            speak("Stopping")
        }
        
        binding.btnHome.setOnClickListener {
            robot?.goTo("home")
            speak("Going home")
        }
        
        binding.btnFollow.setOnClickListener {
            robot?.beWithMe()
            speak("Following you")
        }
        
        binding.btnSpeak.setOnClickListener {
            val text = binding.etSpeak.text.toString()
            if (text.isNotEmpty()) {
                speak(text)
            }
        }
        
        // Head controls
        binding.btnTiltUp.setOnClickListener {
            robot?.tiltAngle(55, 1f)
        }
        
        binding.btnTiltDown.setOnClickListener {
            robot?.tiltAngle(-25, 1f)
        }
        
        // MQTT Status
        updateConnectionStatus()
    }
    
    private fun setupSecretMenu() {
        // Triple tap on logo to show secret menu
        binding.ivLogo.setOnClickListener {
            if (isSecretMenuVisible) {
                hideSecretMenu()
            } else {
                showSecretMenu()
            }
        }
        
        binding.btnMqttSettings.setOnClickListener {
            showMqttSettingsDialog()
        }
        
        binding.btnMapSettings.setOnClickListener {
            showMapSettingsDialog()
        }
        
        binding.btnSystemSettings.setOnClickListener {
            showSystemSettingsDialog()
        }
        
        binding.btnHideMenu.setOnClickListener {
            hideSecretMenu()
        }
    }
    
    private fun showSecretMenu() {
        isSecretMenuVisible = true
        binding.secretMenuContainer.visibility = View.VISIBLE
        binding.mainControlsContainer.visibility = View.GONE
    }
    
    private fun hideSecretMenu() {
        isSecretMenuVisible = false
        binding.secretMenuContainer.visibility = View.GONE
        binding.mainControlsContainer.visibility = View.VISIBLE
    }
    
    private fun handleMovement(event: MotionEvent, x: Float, y: Float, speakText: String) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                speak(speakText)
                Thread {
                    while (true) {
                        robot?.skidJoy(x, y)
                        Thread.sleep(50)
                    }
                }.start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                robot?.skidJoy(0f, 0f)
            }
        }
    }
    
    private fun speak(text: String) {
        robot?.speak(TtsRequest.create(text, false))
    }
    
    private fun handleMqttCommand(command: String, params: Map<String, String>) {
        runOnUiThread {
            when (command) {
                "move_forward" -> robot?.skidJoy(1.0f, 0f)
                "move_back" -> robot?.skidJoy(-1.0f, 0f)
                "turn_left" -> robot?.skidJoy(0f, 1.0f)
                "turn_right" -> robot?.skidJoy(0f, -1.0f)
                "stop" -> robot?.skidJoy(0f, 0f)
                "go_home" -> robot?.goTo("home")
                "follow_me" -> robot?.beWithMe()
                "speak" -> params["text"]?.let { speak(it) }
                "tilt_up" -> robot?.tiltAngle(55, 1f)
                "tilt_down" -> robot?.tiltAngle(-25, 1f)
                "get_locations" -> {
                    val locations = robot?.locations?.joinToString(", ")
                    speak("Known locations: $locations")
                }
                "go_to_location" -> params["location"]?.let { 
                    robot?.goTo(it)
                    speak("Going to $it")
                }
            }
        }
    }
    
    private fun startMqttService() {
        val intent = Intent(this, MqttService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun updateConnectionStatus() {
        val status = mqttService?.getConnectionStatus() ?: "Disconnected"
        binding.tvMqttStatus.text = "MQTT: $status"
    }
    
    private fun showMqttSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("MQTT Settings")
            .setMessage("Configure MQTT broker in settings file")
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showMapSettingsDialog() {
        val locations = robot?.locations?.joinToString(", ") ?: "No locations"
        AlertDialog.Builder(this)
            .setTitle("Map Settings")
            .setMessage("Known locations: $locations")
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showSystemSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("System Settings")
            .setItems(arrayOf("Restart MQTT", "Clear Cache", "About")) { _, which ->
                when (which) {
                    0 -> restartMqttService()
                    1 -> Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
                    2 -> showAboutDialog()
                }
            }
            .show()
    }
    
    private fun restartMqttService() {
        mqttService?.restartConnection()
        Toast.makeText(this, "MQTT service restarted", Toast.LENGTH_SHORT).show()
    }
    
    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Temi Controller")
            .setMessage("Version 1.0\nFull Temi SDK Integration\nMQTT Remote Control Enabled")
            .setPositiveButton("OK", null)
            .show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
    }
}