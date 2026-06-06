package com.zunobotics.okellonexus.data.mqtt

import android.util.Log
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MqttBroker"

@Singleton
class MinimalMqttBroker @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    private val clients = ConcurrentHashMap<String, ClientSession>()

    // subscription pattern -> list of clientIds
    private val subscriptions = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()

    // retained messages: topic -> payload
    private val retained = ConcurrentHashMap<String, ByteArray>()

    fun start(port: Int = 1883) {
        scope.launch {
            try {
                serverSocket = ServerSocket(port).also { it.reuseAddress = true }
                Log.i(TAG, "MQTT broker listening on :$port")
                while (isActive) {
                    val socket = serverSocket!!.accept()
                    launch { handleClient(socket) }
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Broker error: ${e.message}")
            }
        }
    }

    fun stop() {
        scope.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
        clients.values.forEach { it.close() }
        clients.clear()
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        var clientId = "unknown"
        try {
            while (isActive && !socket.isClosed) {
                val firstByte = input.read()
                if (firstByte == -1) break
                val packetType = (firstByte and 0xF0) shr 4
                val flags = firstByte and 0x0F
                val remaining = readVarInt(input)
                val payload = ByteArray(remaining).also { if (it.isNotEmpty()) input.readFully(it) }

                when (packetType) {
                    1 -> { // CONNECT
                        clientId = parseClientId(payload)
                        clients[clientId] = ClientSession(clientId, output)
                        output.write(byteArrayOf(0x20, 0x02, 0x00, 0x00))
                        output.flush()
                        Log.i(TAG, "Client connected: $clientId (total=${clients.size})")
                        // deliver retained messages after subscribe, not here
                    }
                    3 -> { // PUBLISH
                        val qos = (flags and 0x06) shr 1
                        val retain = (flags and 0x01) != 0
                        val (topic, msgPayload, msgId) = parsePublish(payload, qos)
                        if (qos == 1 && msgId != null) {
                            synchronized(output) {
                                output.write(byteArrayOf(0x40, 0x02, (msgId shr 8).toByte(), msgId.toByte()))
                                output.flush()
                            }
                        }
                        if (retain) retained[topic] = msgPayload
                        forward(topic, msgPayload)
                        Log.d(TAG, "PUBLISH [$topic] ${msgPayload.size}B retain=$retain")
                    }
                    8 -> { // SUBSCRIBE
                        val (msgId, topics) = parseSubscribe(payload)
                        topics.forEach { topic ->
                            subscriptions.getOrPut(topic) { CopyOnWriteArrayList() }
                                .also { if (!it.contains(clientId)) it.add(clientId) }
                            Log.d(TAG, "$clientId subscribed to $topic")
                            // deliver matching retained
                            retained.forEach { (retTopic, retPayload) ->
                                if (topicMatches(topic, retTopic))
                                    clients[clientId]?.publish(retTopic, retPayload)
                            }
                        }
                        val suback = ByteArray(2 + topics.size)
                        suback[0] = (msgId shr 8).toByte(); suback[1] = msgId.toByte()
                        sendPacket(output, 0x90.toByte(), suback)
                    }
                    12 -> { // PINGREQ
                        output.write(byteArrayOf(0xD0.toByte(), 0x00))
                        output.flush()
                    }
                    14 -> break // DISCONNECT
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client $clientId gone: ${e.message}")
        } finally {
            clients.remove(clientId)
            subscriptions.values.forEach { it.remove(clientId) }
            try { socket.close() } catch (_: Exception) {}
            Log.i(TAG, "Client disconnected: $clientId (remaining=${clients.size})")
        }
    }

    private fun forward(topic: String, payload: ByteArray) {
        subscriptions.forEach { (pattern, ids) ->
            if (topicMatches(pattern, topic)) {
                ids.toList().forEach { id -> clients[id]?.publish(topic, payload) }
            }
        }
    }

    private fun topicMatches(pattern: String, topic: String): Boolean {
        if (pattern == topic) return true
        if (pattern.endsWith("/#")) return topic.startsWith(pattern.dropLast(2) + "/") || topic == pattern.dropLast(2)
        if (pattern == "#") return true
        return false
    }

    // ─── Packet parsers ───────────────────────────────────────────────────────

    private fun parseClientId(payload: ByteArray): String {
        var i = 0
        val protoLen = readU16(payload, i); i += 2 + protoLen // skip protocol name
        i += 1 + 1 + 2 // protocol level, connect flags, keepalive
        val len = readU16(payload, i); i += 2
        return if (len == 0) "anon-${System.currentTimeMillis()}" else String(payload, i, len)
    }

    private data class PubData(val topic: String, val payload: ByteArray, val msgId: Int?)

    private fun parsePublish(payload: ByteArray, qos: Int): PubData {
        var i = 0
        val topicLen = readU16(payload, i); i += 2
        val topic = String(payload, i, topicLen); i += topicLen
        val msgId = if (qos > 0) { val id = readU16(payload, i); i += 2; id } else null
        return PubData(topic, payload.copyOfRange(i, payload.size), msgId)
    }

    private fun parseSubscribe(payload: ByteArray): Pair<Int, List<String>> {
        var i = 0
        val msgId = readU16(payload, i); i += 2
        val topics = mutableListOf<String>()
        while (i < payload.size) {
            val len = readU16(payload, i); i += 2
            topics.add(String(payload, i, len)); i += len
            i++ // requested QoS byte
        }
        return Pair(msgId, topics)
    }

    private fun readVarInt(input: DataInputStream): Int {
        var value = 0; var shift = 0; var b: Int
        do { b = input.read(); if (b == -1) throw Exception("EOF"); value = value or ((b and 0x7F) shl shift); shift += 7 } while (b and 0x80 != 0)
        return value
    }

    private fun readU16(buf: ByteArray, offset: Int) =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun sendPacket(output: OutputStream, type: Byte, payload: ByteArray) {
        output.write(type.toInt())
        var len = payload.size
        do {
            var b = len % 128; len /= 128
            if (len > 0) b = b or 0x80
            output.write(b)
        } while (len > 0)
        output.write(payload)
        output.flush()
    }
}

class ClientSession(val clientId: String, private val out: OutputStream) {
    private val lock = Any()

    fun publish(topic: String, payload: ByteArray) {
        try {
            val topicBytes = topic.toByteArray(Charsets.UTF_8)
            val remaining = 2 + topicBytes.size + payload.size
            synchronized(lock) {
                out.write(0x30) // PUBLISH, QoS 0
                writeVarInt(out, remaining)
                out.write((topicBytes.size shr 8) and 0xFF)
                out.write(topicBytes.size and 0xFF)
                out.write(topicBytes)
                out.write(payload)
                out.flush()
            }
        } catch (_: Exception) {}
    }

    private fun writeVarInt(out: OutputStream, value: Int) {
        var v = value
        do { var b = v % 128; v /= 128; if (v > 0) b = b or 0x80; out.write(b) } while (v > 0)
    }

    fun close() { try { out.close() } catch (_: Exception) {} }
}
