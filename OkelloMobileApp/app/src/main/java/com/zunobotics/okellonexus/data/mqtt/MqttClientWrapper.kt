package com.zunobotics.okellonexus.data.mqtt

import android.content.Context
import android.util.Log
import com.zunobotics.okellonexus.data.model.RobotConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MqttClientWrapper"

@Singleton
class MqttClientWrapper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var mqttClient: MqttAsyncClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(RobotConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RobotConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(replay = 0, extraBufferCapacity = 128)
    val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages.asSharedFlow()

    private var currentBrokerUri = ""
    private var currentClientId = ""
    private var reconnectJob: Job? = null

    fun connect(brokerUri: String, clientId: String) {
        currentBrokerUri = brokerUri
        currentClientId = clientId
        _connectionState.value = RobotConnectionState.CONNECTING
        scope.launch { doConnect() }
    }

    private suspend fun doConnect() = withContext(Dispatchers.IO) {
        try {
            mqttClient?.disconnect()
        } catch (_: Exception) {}

        val persistence = MemoryPersistence()
        val client = MqttAsyncClient(currentBrokerUri, currentClientId, persistence)
        mqttClient = client

        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "Connection lost: ${cause?.message}")
                _connectionState.value = RobotConnectionState.DISCONNECTED
                scheduleReconnect()
            }
            override fun messageArrived(topic: String, message: MqttMessage) {
                scope.launch {
                    _incomingMessages.emit(IncomingMessage(topic, String(message.payload)))
                }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        val options = MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            isAutomaticReconnect = false
        }

        try {
            val token = client.connect(options)
            token.waitForCompletion(10_000)
            _connectionState.value = RobotConnectionState.CONNECTED_IDLE
            Log.i(TAG, "Connected to $currentBrokerUri")
            subscribe("robot/#", 0)
            reconnectJob?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}")
            _connectionState.value = RobotConnectionState.ERROR
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5_000)
            if (_connectionState.value != RobotConnectionState.CONNECTED_IDLE &&
                _connectionState.value != RobotConnectionState.CONNECTED_ACTIVE) {
                Log.i(TAG, "Reconnecting…")
                _connectionState.value = RobotConnectionState.CONNECTING
                doConnect()
            }
        }
    }

    fun publish(topic: String, payload: ByteArray, qos: Int = 1, retained: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                val client = mqttClient ?: return@launch
                if (!client.isConnected) return@launch
                val msg = MqttMessage(payload).apply {
                    this.qos = qos
                    this.isRetained = retained
                }
                client.publish(topic, msg)
            } catch (e: Exception) {
                Log.e(TAG, "Publish failed [$topic]: ${e.message}")
            }
        }
    }

    fun subscribe(topic: String, qos: Int = 0) {
        scope.launch(Dispatchers.IO) {
            try {
                mqttClient?.subscribe(topic, qos)
            } catch (e: Exception) {
                Log.e(TAG, "Subscribe failed [$topic]: ${e.message}")
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        scope.launch(Dispatchers.IO) {
            try {
                mqttClient?.disconnect()
            } catch (_: Exception) {}
            _connectionState.value = RobotConnectionState.DISCONNECTED
        }
    }

    fun isConnected() = mqttClient?.isConnected == true
}
