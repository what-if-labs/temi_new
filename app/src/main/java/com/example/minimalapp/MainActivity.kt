package com.example.minimalapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var editText: EditText
    private lateinit var speakButton: Button
    private var robot: Robot? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("TemiController", "Received broadcast: ${intent?.action}")
            when (intent?.action) {
                "com.example.minimalapp.MOVE_FORWARD" -> {
                    val speak = intent.getBooleanExtra("speak", true)
                    if (speak) robot?.speak(TtsRequest.create("Moving forward", false))
                    robot?.skidJoy(1.0f, 0f)
                }
                "com.example.minimalapp.MOVE_BACK" -> {
                    val speak = intent.getBooleanExtra("speak", true)
                    if (speak) robot?.speak(TtsRequest.create("Moving backward", false))
                    robot?.skidJoy(-1.0f, 0f)
                }
                "com.example.minimalapp.TURN_LEFT" -> {
                    val speak = intent.getBooleanExtra("speak", true)
                    if (speak) robot?.speak(TtsRequest.create("Turning left", false))
                    robot?.skidJoy(0f, 1.0f)
                }
                "com.example.minimalapp.TURN_RIGHT" -> {
                    val speak = intent.getBooleanExtra("speak", true)
                    if (speak) robot?.speak(TtsRequest.create("Turning right", false))
                    robot?.skidJoy(0f, -1.0f)
                }
                "com.example.minimalapp.STOP" -> {
                    val speak = intent.getBooleanExtra("speak", true)
                    if (speak) robot?.speak(TtsRequest.create("Stopping", false))
                    robot?.skidJoy(0f, 0f)
                }
                "com.example.minimalapp.SPEAK" -> {
                    val text = intent.getStringExtra("text") ?: ""
                    if (text.isNotEmpty()) {
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                        robot?.speak(TtsRequest.create(text, false))
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this, this)
        editText = findViewById(R.id.editText)
        speakButton = findViewById(R.id.speakButton)

        // Initialize Temi Robot
        robot = Robot.getInstance()

        // Register broadcast receiver
        val filter = IntentFilter().apply {
            addAction("com.example.minimalapp.MOVE_FORWARD")
            addAction("com.example.minimalapp.MOVE_BACK")
            addAction("com.example.minimalapp.TURN_LEFT")
            addAction("com.example.minimalapp.TURN_RIGHT")
            addAction("com.example.minimalapp.STOP")
            addAction("com.example.minimalapp.SPEAK")
        }
        registerReceiver(commandReceiver, filter)

        speakButton.setOnClickListener {
            val text = editText.text.toString()
            if (text.isNotEmpty()) {
                speak(text)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        robot?.speak(TtsRequest.create(text, false))
    }

    override fun onDestroy() {
        unregisterReceiver(commandReceiver)
        tts.stop()
        tts.shutdown()
        robot?.skidJoy(0f, 0f)
        super.onDestroy()
    }
}