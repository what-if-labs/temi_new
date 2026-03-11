package com.example.temicontroller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.temicontroller.databinding.ActivityMainBinding
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var robot: Robot? = null
    private var mqttService: MqttService? = null
    private var surveillanceService: SurveillanceService? = null
    
    // Camera
    private var cameraDevice: CameraDevice? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var previewSession: android.hardware.camera2.CameraCaptureSession? = null
    
    companion object {
        const val CAMERA_PERMISSION_REQUEST = 1001
        const val TAG = "TemiFace"
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mqttService = (service as MqttService.LocalBinder).getService()
            mqttService?.setCommandListener { command, params ->
                handleCommand(command, params)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mqttService = null
        }
    }
    
    private val surveillanceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            surveillanceService = (service as SurveillanceService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            surveillanceService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        robot = try { Robot.getInstance() } catch (e: Exception) { null }
        
        // Start services
        startMqttService()
        startSurveillanceService()
        
        // Setup camera
        checkCameraPermission()
        setupCameraPreview()
        
        // Set initial face
        binding.faceView.setState(FaceView.FaceState.IDLE)
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
                        binding.tvStatus.text = it
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
                "start_surveillance" -> {
                    binding.faceView.setState(FaceView.FaceState.THINKING)
                    surveillanceService?.startSurveillance()
                    speak("Watching")
                }
                "stop_surveillance" -> {
                    binding.faceView.setState(FaceView.FaceState.IDLE)
                    surveillanceService?.stopSurveillance()
                    speak("Stopped watching")
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
    
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST && 
            grantResults.isNotEmpty() && 
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupCameraPreview()
        }
    }
    
    private fun setupCameraPreview() {
        binding.cameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                startCamera(surface)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }
    
    private fun startCamera(surfaceTexture: SurfaceTexture) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId == null) {
                binding.tvCameraStatus.text = "No Camera"
                return
            }
            
            cameraThread = HandlerThread("Camera").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera, surfaceTexture)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    binding.tvCameraStatus.text = "Error"
                }
            }, cameraHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "Camera failed", e)
            binding.tvCameraStatus.text = "Failed"
        }
    }
    
    private fun createSession(camera: CameraDevice, surfaceTexture: SurfaceTexture) {
        surfaceTexture.setDefaultBufferSize(640, 480)
        val surface = Surface(surfaceTexture)
        
        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(surface)
            
            camera.createCaptureSession(
                listOf(surface),
                object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                        previewSession = session
                        try {
                            session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                            runOnUiThread { binding.tvCameraStatus.text = "" }
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Capture failed", e)
                        }
                    }
                    override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                        Log.e(TAG, "Config failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Session failed", e)
        }
    }
    
    private fun stopCamera() {
        previewSession?.close()
        previewSession = null
        cameraDevice?.close()
        cameraDevice = null
        cameraThread?.quitSafely()
        cameraThread = null
    }
    
    private fun startMqttService() {
        val intent = Intent(this, MqttService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun startSurveillanceService() {
        val intent = Intent(this, SurveillanceService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, surveillanceConnection, Context.BIND_AUTO_CREATE)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
        unbindService(surveillanceConnection)
        stopCamera()
    }
}