package com.zunobotics.okellonexus.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

object AppPrefsKeys {
    val ROBOT_IP = stringPreferencesKey("robot_ip")
    val MQTT_PORT = intPreferencesKey("mqtt_port")
    val ROBOT_SSID = stringPreferencesKey("robot_ssid")
    val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
    val RECONNECT_INTERVAL = intPreferencesKey("reconnect_interval")
    val CAMERA_QUALITY = stringPreferencesKey("camera_quality")
    val THEME = stringPreferencesKey("theme")
    val ACTIVE_PERSONA_ID = stringPreferencesKey("active_persona_id")
    val ACTIVE_LOCATION_ID = stringPreferencesKey("active_location_id")
    // Languages — comma-separated list of ISO codes e.g. "en,sw,lg,fr"
    val SELECTED_LANGUAGES = stringPreferencesKey("selected_languages")
    val CODE_SWITCHING = booleanPreferencesKey("code_switching")
}

data class AppSettings(
    val robotIp: String = "192.168.49.1",
    val mqttPort: Int = 1883,
    val robotSsid: String = "NexusRobot",
    val autoConnect: Boolean = true,
    val reconnectInterval: Int = 5,
    val cameraQuality: String = "medium",
    val theme: String = "system",
    val activePersonaId: String = "",
    val activeLocationId: String = "",
    val selectedLanguages: String = "en",
    val codeSwitching: Boolean = false
)

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            AppSettings(
                robotIp = prefs[AppPrefsKeys.ROBOT_IP] ?: "192.168.49.1",
                mqttPort = prefs[AppPrefsKeys.MQTT_PORT] ?: 1883,
                robotSsid = prefs[AppPrefsKeys.ROBOT_SSID] ?: "NexusRobot",
                autoConnect = prefs[AppPrefsKeys.AUTO_CONNECT] ?: true,
                reconnectInterval = prefs[AppPrefsKeys.RECONNECT_INTERVAL] ?: 5,
                cameraQuality = prefs[AppPrefsKeys.CAMERA_QUALITY] ?: "medium",
                theme = prefs[AppPrefsKeys.THEME] ?: "system",
                activePersonaId = prefs[AppPrefsKeys.ACTIVE_PERSONA_ID] ?: "",
                activeLocationId = prefs[AppPrefsKeys.ACTIVE_LOCATION_ID] ?: "",
                selectedLanguages = prefs[AppPrefsKeys.SELECTED_LANGUAGES] ?: "en",
                codeSwitching = prefs[AppPrefsKeys.CODE_SWITCHING] ?: false
            )
        }

    suspend fun update(block: suspend MutablePreferences.() -> Unit) {
        dataStore.edit { block(it) }
    }

    suspend fun setBroker(ip: String, port: Int) {
        dataStore.edit {
            it[AppPrefsKeys.ROBOT_IP] = ip
            it[AppPrefsKeys.MQTT_PORT] = port
        }
    }

    suspend fun updateSelectedLanguages(codes: Set<String>, codeSwitching: Boolean) {
        dataStore.edit {
            it[AppPrefsKeys.SELECTED_LANGUAGES] = codes.joinToString(",")
            it[AppPrefsKeys.CODE_SWITCHING] = codeSwitching
        }
    }
}
