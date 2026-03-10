package com.example.minimalapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest

class TemiCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("TemiController", "Receiver got: ${intent?.action}")
        
        val robot = try { Robot.getInstance() } catch (e: Exception) { null }
        
        when (intent?.action) {
            "com.example.minimalapp.MOVE_FORWARD" -> {
                val speak = intent.getBooleanExtra("speak", true)
                if (speak) robot?.speak(TtsRequest.create("Moving forward", false))
                robot?.skidJoy(1.0f, 0f)
                Log.d("TemiController", "Moving forward")
            }
            "com.example.minimalapp.MOVE_BACK" -> {
                val speak = intent.getBooleanExtra("speak", true)
                if (speak) robot?.speak(TtsRequest.create("Moving backward", false))
                robot?.skidJoy(-1.0f, 0f)
                Log.d("TemiController", "Moving back")
            }
            "com.example.minimalapp.TURN_LEFT" -> {
                val speak = intent.getBooleanExtra("speak", true)
                if (speak) robot?.speak(TtsRequest.create("Turning left", false))
                robot?.skidJoy(0f, 1.0f)
                Log.d("TemiController", "Turning left")
            }
            "com.example.minimalapp.TURN_RIGHT" -> {
                val speak = intent.getBooleanExtra("speak", true)
                if (speak) robot?.speak(TtsRequest.create("Turning right", false))
                robot?.skidJoy(0f, -1.0f)
                Log.d("TemiController", "Turning right")
            }
            "com.example.minimalapp.STOP" -> {
                val speak = intent.getBooleanExtra("speak", true)
                if (speak) robot?.speak(TtsRequest.create("Stopping", false))
                robot?.skidJoy(0f, 0f)
                Log.d("TemiController", "Stopping")
            }
            "com.example.minimalapp.SPEAK" -> {
                val text = intent.getStringExtra("text") ?: ""
                if (text.isNotEmpty()) {
                    robot?.speak(TtsRequest.create(text, false))
                    Log.d("TemiController", "Speaking: $text")
                }
            }
        }
    }
}