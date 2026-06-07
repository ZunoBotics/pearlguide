package com.zunobotics.okellonexus.ui.screens.learning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.db.entity.KnowledgeEntry
import com.zunobotics.okellonexus.data.repository.KnowledgeRepository
import com.zunobotics.okellonexus.data.repository.SettingsRepository
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LearningModeViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val knowledgeRepo: KnowledgeRepository
) : ViewModel() {

    val learningMode: StateFlow<Boolean> = settingsRepo.settings
        .map { it.learningMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val taughtFacts: StateFlow<List<KnowledgeEntry>> = knowledgeRepo.entries
        .map { list -> list.filter { it.source == "taught" }.sortedByDescending { it.createdAt }.take(20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _sessionCount = MutableStateFlow(0)
    val sessionCount: StateFlow<Int> = _sessionCount.asStateFlow()

    private var sessionStart = 0L

    fun toggle() = viewModelScope.launch {
        val current = learningMode.value
        if (!current) sessionStart = System.currentTimeMillis()
        settingsRepo.setLearningMode(!current)
        if (!current) _sessionCount.value = 0
    }
}

@Composable
fun LearningModeScreen(
    onBack: () -> Unit,
    viewModel: LearningModeViewModel = hiltViewModel()
) {
    val learningMode by viewModel.learningMode.collectAsState()
    val taughtFacts by viewModel.taughtFacts.collectAsState()
    val sessionCount by viewModel.sessionCount.collectAsState()

    Scaffold(
        topBar = {
            NexusTopBar(title = "Learning Mode", showBack = true, onBack = onBack)
        },
        containerColor = BackgroundWhite
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (learningMode) SuccessTeal.copy(alpha = 0.1f) else SurfaceWhite
                    ),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.School,
                            contentDescription = null,
                            tint = if (learningMode) SuccessTeal else TextSecondary,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            if (learningMode) "Learning Mode ACTIVE" else "Learning Mode OFF",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (learningMode) SuccessTeal else TextPrimary
                        )
                        Text(
                            if (learningMode)
                                "Point the robot at objects and speak their details. The robot will ask for confirmation before saving each fact."
                            else
                                "Enable to teach the robot about objects, exhibits, or products. The trainer speaks and the robot saves facts to its knowledge base.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Button(
                            onClick = { viewModel.toggle() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (learningMode) ErrorRed else SuccessTeal
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                if (learningMode) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (learningMode) "Exit Learning Mode" else "Enter Learning Mode",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    }
                }
            }

            if (learningMode) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AmberGold.copy(alpha = 0.08f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = AmberGold, modifier = Modifier.size(20.dp))
                            Text(
                                "The robot is now in teaching mode. Speak object details clearly. The robot will repeat back and ask you to confirm before saving.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("What happens in Learning Mode", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        LearningStep("1", "Robot focuses its cameras and asks: \"What would you like to tell me about this?\"")
                        LearningStep("2", "You speak the details about the object or exhibit")
                        LearningStep("3", "Robot confirms: \"Got it. Shall I save this?\"")
                        LearningStep("4", "You confirm — fact is saved to the knowledge base")
                        LearningStep("5", "Robot asks: \"Ready for the next one?\"")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Object image capture and visual recognition will be available after camera pipeline integration.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            if (taughtFacts.isNotEmpty()) {
                item {
                    Text("Recently Taught", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                }
                items(taughtFacts) { fact ->
                    TaughtFactCard(fact)
                }
            } else {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.MenuBook, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(32.dp))
                            Text("No facts taught yet", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LearningStep(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = ElectricBlue.copy(alpha = 0.12f),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                number,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = ElectricBlue,
                fontWeight = FontWeight.Bold
            )
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
private fun TaughtFactCard(fact: KnowledgeEntry) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(fact.title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Medium)
                Text(
                    SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(fact.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Text(fact.content, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 2)
        }
    }
}
