package com.zunobotics.okellonexus.ui.screens.language

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.repository.MqttRepository
import com.zunobotics.okellonexus.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RobotLanguage(val code: String, val name: String)

val ALL_LANGUAGES = listOf(
    RobotLanguage("en",  "English"),
    RobotLanguage("sw",  "Swahili"),
    RobotLanguage("fr",  "French"),
    RobotLanguage("ar",  "Arabic"),
    RobotLanguage("es",  "Spanish"),
    RobotLanguage("pt",  "Portuguese"),
    RobotLanguage("de",  "German"),
    RobotLanguage("zh",  "Chinese"),
    RobotLanguage("hi",  "Hindi"),
    RobotLanguage("ja",  "Japanese"),
    RobotLanguage("ko",  "Korean"),
    RobotLanguage("ru",  "Russian"),
    RobotLanguage("it",  "Italian"),
    RobotLanguage("tr",  "Turkish"),
    RobotLanguage("vi",  "Vietnamese"),
    RobotLanguage("th",  "Thai"),
    RobotLanguage("id",  "Indonesian"),
    RobotLanguage("ms",  "Malay"),
    RobotLanguage("fa",  "Persian"),
    RobotLanguage("uk",  "Ukrainian"),
    RobotLanguage("pl",  "Polish"),
    RobotLanguage("nl",  "Dutch"),
    RobotLanguage("ro",  "Romanian"),
    RobotLanguage("hu",  "Hungarian"),
    RobotLanguage("cs",  "Czech"),
    RobotLanguage("el",  "Greek"),
    RobotLanguage("he",  "Hebrew"),
    RobotLanguage("bn",  "Bengali"),
    RobotLanguage("ur",  "Urdu"),
    RobotLanguage("ta",  "Tamil"),
    RobotLanguage("te",  "Telugu"),
    RobotLanguage("mr",  "Marathi"),
    RobotLanguage("am",  "Amharic"),
    RobotLanguage("ha",  "Hausa"),
    RobotLanguage("yo",  "Yoruba"),
    RobotLanguage("ig",  "Igbo"),
    RobotLanguage("zu",  "Zulu"),
    RobotLanguage("af",  "Afrikaans"),
    RobotLanguage("so",  "Somali"),
    RobotLanguage("rw",  "Kinyarwanda"),
    RobotLanguage("lg",  "Luganda"),
    RobotLanguage("nyn", "Runyankole"),
    RobotLanguage("ach", "Acholi"),
    RobotLanguage("teo", "Ateso"),
    RobotLanguage("luo", "Luo"),
    RobotLanguage("cgg", "Rukiga"),
    RobotLanguage("ny",  "Chichewa"),
    RobotLanguage("sn",  "Shona"),
    RobotLanguage("st",  "Sesotho"),
    RobotLanguage("tn",  "Setswana"),
    RobotLanguage("xh",  "Xhosa"),
)

@HiltViewModel
class LanguageViewModel @Inject constructor(
    private val mqttRepo: MqttRepository,
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    private val _selected = MutableStateFlow<Set<String>>(setOf("en"))
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _codeSwitching = MutableStateFlow(false)
    val codeSwitching: StateFlow<Boolean> = _codeSwitching.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    val filteredLanguages: StateFlow<List<RobotLanguage>> = _query
        .map { q ->
            if (q.isBlank()) ALL_LANGUAGES
            else ALL_LANGUAGES.filter { it.name.contains(q, ignoreCase = true) || it.code.contains(q, ignoreCase = true) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ALL_LANGUAGES)

    init {
        viewModelScope.launch {
            settingsRepo.settings.first().let { s ->
                _selected.value = s.selectedLanguages.split(",").filter { it.isNotBlank() }.toSet()
                _codeSwitching.value = s.codeSwitching
            }
        }
    }

    fun toggle(code: String) {
        val current = _selected.value
        _selected.value = if (code in current) {
            if (current.size > 1) current - code else current
        } else {
            current + code
        }
    }

    fun setQuery(q: String) { _query.value = q }

    fun setCodeSwitching(enabled: Boolean) { _codeSwitching.value = enabled }

    fun save() = viewModelScope.launch {
        val codes = _selected.value
        val cs = _codeSwitching.value
        settingsRepo.updateSelectedLanguages(codes, cs)
        mqttRepo.sendLanguage(codes.toList(), cs)
        _saved.value = true
    }
}
