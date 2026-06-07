package com.okello.robot.head.gemini

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

private const val TAG = "GeminiLiveClient"
private const val WS_URL = "wss://generativelanguage.googleapis.com/ws/" +
        "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

class GeminiLiveClient(
    private val apiKey: String,
    private val logDir: File? = null
) {
    private val logFile: File? = logDir?.let { File(it, "okello_gemini.log") }

    private fun flog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        Log.i(TAG, msg)
        try { logFile?.appendText("$ts $msg\n") } catch (_: Exception) {}
    }

    private var wsClient: WebSocketClient? = null
    val events = Channel<GeminiEvent>(capacity = 256)
    private val setupDone = AtomicBoolean(false)

    fun connect(systemPrompt: String, voiceName: String = "Aoede") {
        val uri = URI("$WS_URL?key=$apiKey")
        flog("CONNECTING key=${apiKey.take(8)}... uri=$uri")

        wsClient = object : WebSocketClient(uri) {

            override fun onOpen(handshakedata: ServerHandshake?) {
                flog("WS OPEN status=${handshakedata?.httpStatus} msg=${handshakedata?.httpStatusMessage}")
                val msg = GeminiMessage.setup(systemPrompt, voiceName)
                send(msg)
                flog("SETUP SEND msgLen=${msg.length}")

                Thread {
                    Thread.sleep(1000)
                    flog("1s probe: isOpen=$isOpen setupDone=${setupDone.get()}")
                    Thread.sleep(14000)
                    if (!setupDone.get()) {
                        flog("TIMEOUT: no setupComplete after 15s — closing")
                        close()
                    }
                }.start()
            }

            override fun onMessage(message: String?) {
                if (message != null) {
                    flog("TEXT FRAME << ${message.take(200)}")
                    handleMessage(message)
                }
            }

            override fun onMessage(bytes: ByteBuffer?) {
                if (bytes != null) {
                    val text = StandardCharsets.UTF_8.decode(bytes).toString()
                    flog("BINARY FRAME << ${text.take(200)}")
                    handleMessage(text)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                flog("WS CLOSED: $code $reason remote=$remote")
                if (code != 1000 || remote) {
                    events.trySendBlocking(GeminiEvent.Error("closed: $code $reason"))
                }
                if (!events.isClosedForSend) events.close()
            }

            override fun onError(ex: Exception?) {
                flog("WS ERROR: ${ex?.message}")
                events.trySendBlocking(GeminiEvent.Error(ex?.message ?: "unknown error"))
            }
        }

        wsClient?.setSocketFactory(SSLSocketFactory.getDefault() as SSLSocketFactory)
        wsClient?.connect()
    }

    private fun handleMessage(text: String) {
        val event = parseGeminiMessage(text) ?: return
        if (event is GeminiEvent.SetupComplete) {
            setupDone.set(true)
            flog("SETUP COMPLETE — Gemini Live is READY")
        }
        events.trySendBlocking(event)
    }

    fun sendAudio(base64Pcm: String) {
        if (!setupDone.get()) return
        wsClient?.send(GeminiMessage.audioChunk(base64Pcm))
    }

    fun sendVideo(base64Jpeg: String) {
        if (!setupDone.get()) return
        wsClient?.send(GeminiMessage.videoFrame(base64Jpeg))
    }

    fun sendText(text: String) {
        if (!setupDone.get()) return
        wsClient?.send(GeminiMessage.clientText(text))
    }

    fun disconnect() {
        setupDone.set(false)
        try { wsClient?.close() } catch (_: Exception) {}
        wsClient = null
        if (!events.isClosedForSend) events.close()
    }
}
