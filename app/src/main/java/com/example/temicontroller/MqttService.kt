package com.example.temicontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

class MqttService : Service() {
    private val binder = LocalBinder()
    private var mqttClient: MqttClient? = null
    private var commandListener: ((String, Map<String, String>) -> Unit)? = null
    private var connectionStatus = "Disconnected"
    var onMqttConnected: (() -> Unit)? = null
    
    companion object {
        const val CHANNEL_ID = "TemiMqttChannel"
        const val NOTIFICATION_ID = 1
        const val COMMAND_TOPIC = "temi_commands"
        const val STATUS_TOPIC = "temi_status"
        const val LOCATIONS_TOPIC = "temi_locations"
        const val POSITION_TOPIC = "temi_position"
        const val MAP_TOPIC = "temi_map"
        const val VIRTUAL_WALLS_TOPIC = "temi_virtual_walls"
        const val BATTERY_TOPIC = "temi_battery"
    }
    
    private var brokerUrl = "tcp://192.168.1.1:1883"
    
    inner class LocalBinder : Binder() {
        fun getService(): MqttService = this@MqttService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val brokerIp = intent?.getStringExtra("broker_ip") ?: "192.168.2.150"
        val brokerPort = intent?.getIntExtra("broker_port", 1883) ?: 1883
        brokerUrl = "tcp://$brokerIp:$brokerPort"
        Log.d("MQTT", "Using broker: $brokerUrl")
        connectToMqtt()
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Temi MQTT Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Temi Controller")
            .setContentText("MQTT: $connectionStatus")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()
    }
    
    private fun connectToMqtt() {
        // Prevent multiple simultaneous connection attempts
        if (mqttClient?.isConnected == true && mqttClient?.serverURI == brokerUrl) {
            Log.d("MQTT", "Already connected to $brokerUrl. Skipping redundant connection.")
            return
        }

        Thread {
            try {
                // Use robot serial number for a persistent, unique Client ID
                val robot = com.robotemi.sdk.Robot.getInstance()
                val serialNumber = robot.serialNumber ?: "unknown-temi"
                val persistentClientId = "temi-controller-$serialNumber"
                
                Log.d("MQTT", "Attempting connection to $brokerUrl with Client ID: $persistentClientId")
                
                // If broker URL changed, we must recreate the client
                if (mqttClient != null && mqttClient?.serverURI != brokerUrl) {
                    try { mqttClient?.disconnect(); mqttClient?.close() } catch (e: Exception) {}
                    mqttClient = null
                }

                if (mqttClient == null) {
                    mqttClient = MqttClient(brokerUrl, persistentClientId, MemoryPersistence())
                }
                
                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = false
                    connectionTimeout = 10
                    keepAliveInterval = 20
                }
                
                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.e("MQTT", "Connection lost", cause)
                        connectionStatus = "Disconnected"
                        updateNotification()
                    }
                    
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        handleIncomingMessage(topic, message)
                    }
                    
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                
                mqttClient?.connect(options)
                mqttClient?.subscribe(COMMAND_TOPIC)
                
                connectionStatus = "Connected"
                updateNotification()
                publishStatus("Temi controller online")
                
                // Notify that MQTT is connected so we can publish pending data
                onMqttConnected?.invoke()
                
                Log.d("MQTT", "Connected to broker")
                
            } catch (e: Exception) {
                Log.e("MQTT", "Connection failed", e)
                connectionStatus = "Error: ${e.message}"
                updateNotification()
            }
        }.start()
    }
    
    private fun handleIncomingMessage(topic: String?, message: MqttMessage?) {
        try {
            val payload = message?.toString() ?: return
            Log.d("MQTT", "Received: $payload")
            
            val json = JSONObject(payload)
            val command = json.getString("command")
            val params = mutableMapOf<String, String>()
            
            if (json.has("params")) {
                val paramsObj = json.getJSONObject("params")
                paramsObj.keys().forEach { key ->
                    params[key] = paramsObj.getString(key)
                }
            }
            
            commandListener?.invoke(command, params)
            publishStatus("Executed: $command")
            
        } catch (e: Exception) {
            Log.e("MQTT", "Error handling message", e)
        }
    }

    private fun publishStatus(status: String) {
        val client = mqttClient
        if (client == null || !client.isConnected) return
        try {
            // 1. Get the robot instance and serial number
            val robot = com.robotemi.sdk.Robot.getInstance()
            val robotId = robot.serialNumber ?: "unknown-temi"

            // 2. Build the JSON payload
            val json = JSONObject()
            json.put("type", "status_update")
            json.put("robotId", robotId)
            json.put("status", status)
            json.put("timestamp", System.currentTimeMillis())

            // 3. Publish as JSON bytes
            val message = MqttMessage(json.toString().toByteArray())
            client.publish(STATUS_TOPIC, message)

            Log.d("MQTT", "Published status JSON: $status")
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish status JSON", e)
        }
    }
    
    fun publishLocations(robotId: String, locations: List<Map<String, Any>>) {
        val client = mqttClient
        if (client == null || !client.isConnected) return
        try {
            Log.d("MQTT", "Publishing locations: ${locations.size}")
            val json = JSONObject()
            json.put("type", "locations_list")
            json.put("robotId", robotId)
            json.put("locations", org.json.JSONArray(locations))
            val fullJsonString = json.toString()
            val message = MqttMessage(fullJsonString.toByteArray())
            message.isRetained = true
            client.publish(LOCATIONS_TOPIC, message)
            Log.d("MQTT", "Published locations: ${locations.size} (retained)")
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish locations", e)
        }
    }

    fun publishVirtualWalls(robotId: String, virtualWalls: List<Map<String, Any>>) {
        val client = mqttClient
        if (client == null || !client.isConnected) return
        try {
            val json = JSONObject()
            json.put("type", "virtual_walls_list")
            json.put("robotId", robotId)
            json.put("virtualWalls", org.json.JSONArray(virtualWalls))
            val message = MqttMessage(json.toString().toByteArray())
            message.isRetained = true
            client.publish(VIRTUAL_WALLS_TOPIC, message)
            Log.d("MQTT", "Published virtual walls: ${virtualWalls.size} (retained)")
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish virtual walls", e)
        }
    }

    fun publishPosition(robotId: String, x: Float, y: Float, yaw: Float) {
        val client = mqttClient
        if (client == null || !client.isConnected) return
        try {
            val json = JSONObject()
            json.put("type", "position_update")
            json.put("robotId", robotId)
            json.put("x", x)
            json.put("y", y)
            json.put("yaw", yaw)
            json.put("timestamp", System.currentTimeMillis())
            val message = MqttMessage(json.toString().toByteArray())
            client.publish(POSITION_TOPIC, message)
            Log.d("MQTT", "Published position: x=$x, y=$y, yaw=$yaw")
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish position", e)
        }
    }

    fun publishMap(robotId: String, compressedImageData: String, width: Int, height: Int, resolution: Float, originX: Float, originY: Float) {
        val client = mqttClient
        if (client == null || !client.isConnected) return
        try {
            val json = JSONObject()
            json.put("type", "map_metadata")
            json.put("robotId", robotId)
            json.put("compressedImageData", compressedImageData)
            json.put("width", width)
            json.put("height", height)
            json.put("resolution", resolution)
            json.put("originX", originX)
            json.put("originY", originY)
            val message = MqttMessage(json.toString().toByteArray())
            message.isRetained = true
            client.publish(MAP_TOPIC, message)
            Log.d("MQTT", "Published map data: ${width}x${height} (retained)")
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish map", e)
        }
    }

    fun publishBattery(robotId: String, level: Int, isCharging: Boolean) {
        val client = mqttClient
        if (client == null || !client.isConnected) return
        try {
            val json = JSONObject()
            json.put("type", "battery_status")
            json.put("robotId", robotId)
            json.put("level", level)
            json.put("isCharging", isCharging)
            val message = MqttMessage(json.toString().toByteArray())
            client.publish(BATTERY_TOPIC, message)
            Log.d("MQTT", "Published battery: $level%, charging=$isCharging")
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish battery", e)
        }
    }
    
    fun publishCommand(command: String, params: Map<String, Any> = emptyMap()) {
        val client = mqttClient
        if (client == null || !client.isConnected) return
        try {
            val json = JSONObject()
            json.put("command", command)
            if (params.isNotEmpty()) {
                val paramsJson = JSONObject()
                params.forEach { (key, value) -> paramsJson.put(key, value) }
                json.put("params", paramsJson)
            }
            val message = MqttMessage(json.toString().toByteArray())
            client.publish(COMMAND_TOPIC, message)
            Log.d("MQTT", "Published command: $command with params: $params")
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish command", e)
        }
    }

    fun publishNavigationStatus(result: String, details: String) {
        val client = mqttClient
        if (client == null || !client.isConnected) return
        try {
            val json = JSONObject()
            json.put("status", "navigation_status")
            json.put("result", result)
            json.put("details", details)

            val message = MqttMessage(json.toString().toByteArray())
            client.publish(STATUS_TOPIC, message)
            Log.d("MQTT", "Published navigation status: $result")
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish navigation status", e)
        }
    }
    
    private fun updateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w("MQTT", "Cannot update notification: POST_NOTIFICATIONS permission not granted")
                return
            }
        }
        val notification = createNotification()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    fun setCommandListener(listener: (String, Map<String, String>) -> Unit) {
        commandListener = listener
    }
    
    fun getConnectionStatus(): String = connectionStatus
    
    fun restartConnection() {
        Thread {
            try {
                mqttClient?.disconnect()
                mqttClient?.close()
            } catch (e: Exception) {
                Log.e("MQTT", "Error disconnecting", e)
            } finally {
                mqttClient = null
            }
            connectToMqtt()
        }.start()
    }
    
    fun reconnectWithNewBroker() {
        Thread {
            try {
                mqttClient?.disconnect()
                mqttClient?.close()
            } catch (e: Exception) {
                Log.e("MQTT", "Error disconnecting", e)
            } finally {
                mqttClient = null
            }
            // Read updated broker config from SharedPreferences
            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val brokerIp = prefs.getString(MainActivity.KEY_BROKER_IP, "192.168.88.30") ?: "192.168.88.30"
            val brokerPort = prefs.getInt(MainActivity.KEY_BROKER_PORT, 1883)
            brokerUrl = "tcp://$brokerIp:$brokerPort"
            Log.d("MQTT", "Reconnecting with new broker: $brokerUrl")
            connectToMqtt()
        }.start()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            mqttClient = null
        } catch (e: Exception) {
            Log.e("MQTT", "Error on destroy", e)
        }
    }
}