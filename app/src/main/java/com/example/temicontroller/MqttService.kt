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
        val CLIENT_ID = "temi-controller-" + System.currentTimeMillis()
        const val COMMAND_TOPIC = "temi/commands"
        const val STATUS_TOPIC = "temi/status"
        const val LOCATIONS_TOPIC = "temi/locations"
        const val POSITION_TOPIC = "temi/position"
        const val MAP_TOPIC = "temi/map"
        const val BATTERY_TOPIC = "temi/battery"
    }
    
    private var brokerUrl = "tcp://192.168.4.34:1883"
    
    inner class LocalBinder : Binder() {
        fun getService(): MqttService = this@MqttService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val brokerIp = intent?.getStringExtra("broker_ip") ?: "192.168.4.34"
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
        Thread {
            try {
                mqttClient = MqttClient(brokerUrl, CLIENT_ID, MemoryPersistence())
                
                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
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
        try {
            val message = MqttMessage(status.toByteArray())
            mqttClient?.publish(STATUS_TOPIC, message)
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish status", e)
        }
    }
    
    fun publishLocations(locations: List<Map<String, Any>>) {
        try {
            Log.d("MQTT", "Publishing locations: ${locations.size}, mqttClient=${mqttClient != null}")
            val json = JSONObject()
            json.put("locations", org.json.JSONArray(locations))
            val message = MqttMessage(json.toString().toByteArray())
            mqttClient?.publish(LOCATIONS_TOPIC, message)
            Log.d("MQTT", "Published locations: ${locations.size} locations")
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish locations", e)
        }
    }
    
    fun publishPosition(x: Float, y: Float, yaw: Float) {
        try {
            val json = JSONObject()
            json.put("x", x)
            json.put("y", y)
            json.put("yaw", yaw)
            json.put("timestamp", System.currentTimeMillis())
            val message = MqttMessage(json.toString().toByteArray())
            mqttClient?.publish(POSITION_TOPIC, message)
            Log.d("MQTT", "Published position: x=$x, y=$y, yaw=$yaw")
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish position", e)
        }
    }
    
    fun publishMap(imageBase64: String, width: Int, height: Int) {
        try {
            val json = JSONObject()
            json.put("image", imageBase64)
            json.put("width", width)
            json.put("height", height)
            val message = MqttMessage(json.toString().toByteArray())
            mqttClient?.publish(MAP_TOPIC, message)
            Log.d("MQTT", "Published map: ${width}x${height}")
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish map", e)
        }
    }
    
    fun publishBattery(level: Int, isCharging: Boolean) {
        try {
            val json = JSONObject()
            json.put("level", level)
            json.put("isCharging", isCharging)
            val message = MqttMessage(json.toString().toByteArray())
            mqttClient?.publish(BATTERY_TOPIC, message)
            Log.d("MQTT", "Published battery: $level%, charging=$isCharging")
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish battery", e)
        }
    }
    
    fun publishCommand(command: String, params: Map<String, Any> = emptyMap()) {
        try {
            val json = JSONObject()
            json.put("command", command)
            if (params.isNotEmpty()) {
                val paramsJson = JSONObject()
                params.forEach { (key, value) -> paramsJson.put(key, value) }
                json.put("params", paramsJson)
            }
            val message = MqttMessage(json.toString().toByteArray())
            mqttClient?.publish(COMMAND_TOPIC, message)
            Log.d("MQTT", "Published command: $command with params: $params")
        } catch (e: Exception) {
            Log.e("MQTT", "Failed to publish command", e)
        }
    }
    
    private fun updateNotification() {
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
            }
            connectToMqtt()
        }.start()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) {
            Log.e("MQTT", "Error on destroy", e)
        }
    }
}