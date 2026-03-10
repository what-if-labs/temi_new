package com.example.minimalapp

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.map.MapDataModel
import com.robotemi.sdk.map.OnLoadMapStatusChangedListener
import kotlinx.coroutines.*

class TemiCommandService : Service() {
    private var robot: Robot? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var movementJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("TemiController", "Service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("TemiController", "Service received: $action")
        
        // Cancel any ongoing movement
        movementJob?.cancel()
        
        // Initialize robot with application context
        robot = try { 
            Robot.getInstance()
        } catch (e: Exception) { 
            Log.e("TemiController", "Failed to get robot instance: ${e.message}")
            null 
        }
        Log.d("TemiController", "Robot instance: $robot")
        
        if (robot == null) {
            Log.e("TemiController", "Robot is null, cannot execute command")
            return START_NOT_STICKY
        }
        
        when (action) {
            "com.example.minimalapp.MOVE_FORWARD" -> {
                val speak = intent.getBooleanExtra("speak", true)
                if (speak) robot?.speak(TtsRequest.create("Moving forward", false))
                // Continuously call skidJoy for 2 seconds
                movementJob = serviceScope.launch {
                    val endTime = System.currentTimeMillis() + 2000
                    Log.d("TemiController", "Starting forward movement loop")
                    while (System.currentTimeMillis() < endTime && isActive) {
                        robot?.skidJoy(1.0f, 0f)
                        Log.d("TemiController", "skidJoy forward called")
                        delay(50) // 50ms delay between calls
                    }
                    robot?.skidJoy(0f, 0f) // Stop after movement
                    Log.d("TemiController", "Forward movement completed")
                }
                Log.d("TemiController", "Executed MOVE_FORWARD")
            }
            "com.example.minimalapp.MOVE_BACK" -> {
                val speak = intent.getBooleanExtra("speak", true)
                if (speak) robot?.speak(TtsRequest.create("Moving backward", false))
                movementJob = serviceScope.launch {
                    val endTime = System.currentTimeMillis() + 2000
                    while (System.currentTimeMillis() < endTime && isActive) {
                        robot?.skidJoy(-1.0f, 0f)
                        delay(50)
                    }
                    robot?.skidJoy(0f, 0f)
                }
                Log.d("TemiController", "Executed MOVE_BACK")
            }
            "com.example.minimalapp.TURN_LEFT" -> {
                val speak = intent.getBooleanExtra("speak", true)
                if (speak) robot?.speak(TtsRequest.create("Turning left", false))
                movementJob = serviceScope.launch {
                    val endTime = System.currentTimeMillis() + 2000
                    while (System.currentTimeMillis() < endTime && isActive) {
                        robot?.skidJoy(0f, 1.0f)
                        delay(50)
                    }
                    robot?.skidJoy(0f, 0f)
                }
                Log.d("TemiController", "Executed TURN_LEFT")
            }
            "com.example.minimalapp.TURN_RIGHT" -> {
                val speak = intent.getBooleanExtra("speak", true)
                if (speak) robot?.speak(TtsRequest.create("Turning right", false))
                movementJob = serviceScope.launch {
                    val endTime = System.currentTimeMillis() + 2000
                    while (System.currentTimeMillis() < endTime && isActive) {
                        robot?.skidJoy(0f, -1.0f)
                        delay(50)
                    }
                    robot?.skidJoy(0f, 0f)
                }
                Log.d("TemiController", "Executed TURN_RIGHT")
            }
            "com.example.minimalapp.STOP" -> {
                val speak = intent.getBooleanExtra("speak", true)
                if (speak) robot?.speak(TtsRequest.create("Stopping", false))
                robot?.skidJoy(0f, 0f)
                Log.d("TemiController", "Executed STOP")
            }
            "com.example.minimalapp.SPEAK" -> {
                val text = intent.getStringExtra("text") ?: ""
                if (text.isNotEmpty()) {
                    robot?.speak(TtsRequest.create(text, false))
                    Log.d("TemiController", "Executed SPEAK: $text")
                }
            }
            "com.example.minimalapp.START_MAPPING" -> {
                robot?.speak(TtsRequest.create("Starting to map the area. Please follow me.", false))
                // Enable mapping mode - use continueMapping or finishMapping
                robot?.continueMapping()
                Log.d("TemiController", "Executed START_MAPPING")
            }
            "com.example.minimalapp.STOP_MAPPING" -> {
                robot?.speak(TtsRequest.create("Stopping mapping.", false))
                // Finish mapping with a name
                robot?.finishMapping("NewMap")
                Log.d("TemiController", "Executed STOP_MAPPING")
            }
            "com.example.minimalapp.SAVE_MAP" -> {
                val mapName = intent.getStringExtra("name") ?: "MyMap"
                robot?.speak(TtsRequest.create("Saving map as $mapName", false))
                robot?.finishMapping(mapName)
                Log.d("TemiController", "Executed SAVE_MAP: $mapName")
            }
            "com.example.minimalapp.GET_LOCATIONS" -> {
                val locations = robot?.locations
                robot?.speak(TtsRequest.create("I know these locations: ${locations?.joinToString(", ")}", false))
                Log.d("TemiController", "Locations: $locations")
            }
            "com.example.minimalapp.GO_TO_LOCATION" -> {
                val location = intent.getStringExtra("location") ?: ""
                if (location.isNotEmpty()) {
                    robot?.speak(TtsRequest.create("Going to $location", false))
                    robot?.goTo(location)
                    Log.d("TemiController", "Going to: $location")
                }
            }
            "com.example.minimalapp.GO_HOME" -> {
                robot?.speak(TtsRequest.create("Going home", false))
                robot?.goTo("home")
                Log.d("TemiController", "Going home")
            }
            "com.example.minimalapp.FOLLOW_ME" -> {
                robot?.speak(TtsRequest.create("I will follow you now", false))
                robot?.beWithMe()
                Log.d("TemiController", "Follow me mode activated")
            }
            "com.example.minimalapp.TILT_UP" -> {
                robot?.tiltAngle(55, 1f)
                Log.d("TemiController", "Tilted up")
            }
            "com.example.minimalapp.TILT_DOWN" -> {
                robot?.tiltAngle(-25, 1f)
                Log.d("TemiController", "Tilted down")
            }
            "com.example.minimalapp.STATUS" -> {
                val isMapLost = robot?.isMapLost()
                val isMapLocked = robot?.isMapLocked()
                val status = "Map lost: $isMapLost, Map locked: $isMapLocked"
                robot?.speak(TtsRequest.create(status, false))
                Log.d("TemiController", "Status: $status")
            }
            "com.example.minimalapp.RESET" -> {
                robot?.speak(TtsRequest.create("Resetting movement", false))
                robot?.skidJoy(0f, 0f)
                // Try to reinitialize
                robot = try { Robot.getInstance() } catch (e: Exception) { null }
                Log.d("TemiController", "Reset executed")
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        movementJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}