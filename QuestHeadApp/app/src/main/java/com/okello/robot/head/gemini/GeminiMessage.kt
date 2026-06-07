package com.okello.robot.head.gemini

import org.json.JSONArray
import org.json.JSONObject

// ─── Outbound — raw wire format for Gemini Live WebSocket ────────────────────
// All keys MUST be camelCase — this is a gRPC-JSON transcoded API.
// "mediaChunks" is deprecated; use the "audio" / "video" sub-keys under "realtimeInput".

object GeminiMessage {

    fun setup(systemPrompt: String, voiceName: String = "Aoede", includeText: Boolean = false): String {
        // outputAudioTranscription must be at the setup level (NOT inside generationConfig).
        // When enabled, Gemini sends serverContent.outputTranscription.text alongside audio.
        val setupObj = JSONObject()
            .put("model", "models/gemini-3.1-flash-live-preview")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject()
                    .put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject()
                            .put("voiceName", voiceName)))))
            .put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(
                    JSONObject().put("text", systemPrompt))))
            .put("realtimeInputConfig", JSONObject()
                .put("automaticActivityDetection", JSONObject()
                    .put("disabled", false)
                    .put("silenceDurationMs", 1500)))
        if (includeText) {
            setupObj.put("outputAudioTranscription", JSONObject())
        }
        return JSONObject().put("setup", setupObj).toString()
    }

    fun audioChunk(base64Pcm: String): String =
        JSONObject().put(
            "realtimeInput", JSONObject()
                .put("audio", JSONObject()
                    .put("data", base64Pcm)
                    .put("mimeType", "audio/pcm;rate=16000"))
        ).toString()

    fun videoFrame(base64Jpeg: String): String =
        JSONObject().put(
            "realtimeInput", JSONObject()
                .put("video", JSONObject()
                    .put("data", base64Jpeg)
                    .put("mimeType", "image/jpeg"))
        ).toString()

    fun clientText(text: String): String =
        JSONObject().put(
            "clientContent", JSONObject()
                .put("turns", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(
                            JSONObject().put("text", text)))))
                .put("turnComplete", true)
        ).toString()
}

// ─── Inbound — parsed events from Gemini Live ────────────────────────────────

sealed class GeminiEvent {
    data class SetupComplete(val sessionId: String?) : GeminiEvent()
    data class AudioChunk(val pcmBase64: String) : GeminiEvent()
    data class TextChunk(val text: String) : GeminiEvent()
    object TurnComplete : GeminiEvent()
    data class Interrupted(val reason: String) : GeminiEvent()
    data class Error(val message: String) : GeminiEvent()
}

// Returns all events from a single server message — a message can contain both
// an audio chunk and a transcription text at the same time.
fun parseGeminiMessages(json: String): List<GeminiEvent> {
    val results = mutableListOf<GeminiEvent>()
    try {
        val obj = JSONObject(json)
        when {
            obj.has("setupComplete") -> results.add(
                GeminiEvent.SetupComplete(
                    obj.optJSONObject("setupComplete")?.optString("sessionId")?.ifEmpty { null }
                )
            )
            obj.has("serverContent") -> {
                val content = obj.getJSONObject("serverContent")
                if (content.optBoolean("interrupted")) { results.add(GeminiEvent.Interrupted("barge-in")); return results }
                if (content.optBoolean("turnComplete")) { results.add(GeminiEvent.TurnComplete); return results }

                // Audio / inline text from modelTurn
                val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        part.optJSONObject("inlineData")?.let { inline ->
                            val mime = inline.optString("mimeType")
                            val data = inline.optString("data")
                            if (mime.startsWith("audio/pcm") && data.isNotEmpty())
                                results.add(GeminiEvent.AudioChunk(data))
                        }
                        part.optString("text").takeIf { it.isNotEmpty() }?.let {
                            results.add(GeminiEvent.TextChunk(it))
                        }
                    }
                }

                // outputAudioTranscription — can arrive alongside or separate from modelTurn
                content.optJSONObject("outputTranscription")?.let { t ->
                    val text = t.optString("text")
                    if (text.isNotEmpty()) results.add(GeminiEvent.TextChunk(text))
                }
            }
        }
    } catch (e: Exception) {
        results.add(GeminiEvent.Error("parse error: ${e.message}"))
    }
    return results
}
